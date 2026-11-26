using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace SaveGaze.Core;

public sealed record BlockDecision(bool Blocked, string Reason = "", string MatchedItem = "");

public sealed record CrossBlockUpdate(
    IReadOnlyList<long> Timestamps,
    long LockoutUntilMs,
    bool TriggeredLockout
);

public static class ContentGuardRules
{
    public const int CrossBlockLimit = 5;
    public const long CrossBlockWindowMs = 10 * 60 * 1000L;
    public const long LockoutDurationMs = 5 * 60 * 1000L;
    public const int ChallengeSeconds = 40;

    public static string NormalizeHost(string rawHostOrUrl)
    {
        if (string.IsNullOrWhiteSpace(rawHostOrUrl)) return "";
        string s = rawHostOrUrl.Trim().ToLowerInvariant();
        if (s.StartsWith("https://")) s = s["https://".Length..];
        else if (s.StartsWith("http://")) s = s["http://".Length..];

        int cut = s.IndexOfAny(['/', '?', '#']);
        if (cut >= 0) s = s[..cut];
        int port = s.IndexOf(':');
        if (port >= 0) s = s[..port];

        if (s.StartsWith("www.")) s = s["www.".Length..];
        return s.TrimEnd('.');
    }

    public static HashSet<string> BuildActiveDomains(
        IEnumerable<string> customWebsites,
        bool blockSocialDistractions = false)
    {
        var result = new HashSet<string>(ContentGuardLists.DefaultAdultDomains, StringComparer.OrdinalIgnoreCase);
        foreach (string g in ContentGuardLists.GamblingDomains) result.Add(g);
        if (blockSocialDistractions)
        {
            foreach (string s in ContentGuardLists.OptionalSocialDomains) result.Add(s);
        }
        foreach (string site in customWebsites)
        {
            string clean = NormalizeHost(site);
            if (!string.IsNullOrEmpty(clean)) result.Add(clean);
        }
        return result;
    }

    public static string? IsDomainBlocked(string hostOrUrl, IReadOnlySet<string> blockedDomains)
    {
        string host = NormalizeHost(hostOrUrl);
        if (string.IsNullOrEmpty(host) || !host.Contains('.')) return null;

        foreach (string domain in blockedDomains)
        {
            string cleanDomain = NormalizeHost(domain);
            if (string.IsNullOrEmpty(cleanDomain)) continue;
            if (host.Equals(cleanDomain, StringComparison.OrdinalIgnoreCase) ||
                host.EndsWith("." + cleanDomain, StringComparison.OrdinalIgnoreCase))
            {
                return cleanDomain;
            }
        }
        return null;
    }

    public static string SafeUrlDecode(string raw)
    {
        try
        {
            return Uri.UnescapeDataString(raw.Replace('+', ' '));
        }
        catch
        {
            return raw;
        }
    }

    public static bool MatchesWholeWordKeyword(string text, string keyword)
    {
        if (string.IsNullOrWhiteSpace(text) || string.IsNullOrWhiteSpace(keyword)) return false;
        string trimmed = keyword.Trim();
        string[] parts = Regex.Split(trimmed, @"[\s+_\-]+")
            .Where(p => p.Length > 0)
            .ToArray();
        if (parts.Length == 0) return false;

        string pattern = "(?<![a-zA-Z0-9])" +
            string.Join(@"[\s+_\-]+", parts.Select(Regex.Escape)) +
            "(?![a-zA-Z0-9])";

        return Regex.IsMatch(text, pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    }

    public static BlockDecision CheckUrl(
        string rawUrl,
        IReadOnlySet<string> blockedDomains,
        IEnumerable<string> customKeywords,
        bool keywordBlockerEnabled = true)
    {
        if (string.IsNullOrWhiteSpace(rawUrl)) return new BlockDecision(false);
        string decoded = SafeUrlDecode(rawUrl).ToLowerInvariant();

        string? matchedDomain = IsDomainBlocked(decoded, blockedDomains);
        if (matchedDomain != null)
        {
            return new BlockDecision(true, "Blocked Website", matchedDomain);
        }

        if (keywordBlockerEnabled)
        {
            foreach (string kw in customKeywords)
            {
                if (MatchesWholeWordKeyword(decoded, kw))
                {
                    return new BlockDecision(true, "Custom Blocked Keyword", kw);
                }
            }
            foreach (string kw in ContentGuardLists.DefaultKeywords)
            {
                if (MatchesWholeWordKeyword(decoded, kw))
                {
                    return new BlockDecision(true, "Blocked Adult Keyword in URL", kw);
                }
            }
        }

        return new BlockDecision(false);
    }

    public static BlockDecision CheckSearchOrInputText(
        string rawInput,
        IReadOnlySet<string> blockedDomains,
        IEnumerable<string> customKeywords,
        bool keywordBlockerEnabled = true) =>
        CheckUrl(rawInput, blockedDomains, customKeywords, keywordBlockerEnabled);

    public static BlockDecision CheckVisibleScreenText(
        string screenText,
        IEnumerable<string> customKeywords,
        IReadOnlySet<string> blockedDomains,
        int threshold = 3)
    {
        if (string.IsNullOrWhiteSpace(screenText)) return new BlockDecision(false);
        string clipped = screenText.Length > 50_000 ? screenText[..50_000] : screenText;

        foreach (string kw in customKeywords)
        {
            if (MatchesWholeWordKeyword(clipped, kw))
            {
                return new BlockDecision(true, "Custom Keyword Detected", kw);
            }
        }

        foreach (string domain in blockedDomains)
        {
            if (domain.Length >= 5 && MatchesWholeWordKeyword(clipped, domain))
            {
                return new BlockDecision(true, "Blocked Domain on Screen", domain);
            }
        }

        int hitCount = 0;
        string lastHit = "";
        foreach (string kw in ContentGuardLists.DefaultKeywords)
        {
            if (MatchesWholeWordKeyword(clipped, kw))
            {
                hitCount++;
                lastHit = kw;
                if (hitCount >= threshold)
                {
                    return new BlockDecision(true, $"Adult Keywords on Screen ({hitCount} matches)", lastHit);
                }
            }
        }

        return new BlockDecision(false);
    }

    public static CrossBlockUpdate RecordCrossBlockEvent(
        IEnumerable<long> existingTimestamps,
        long nowMs,
        bool lockoutEnabled = true)
    {
        long windowStart = nowMs - CrossBlockWindowMs;
        var recent = existingTimestamps.Where(t => t >= windowStart).ToList();
        recent.Add(nowMs);

        if (lockoutEnabled && recent.Count >= CrossBlockLimit)
        {
            return new CrossBlockUpdate([], nowMs + LockoutDurationMs, true);
        }

        return new CrossBlockUpdate(recent, 0L, false);
    }

    public static string HashPassword(string password)
    {
        byte[] bytes = SHA256.HashData(Encoding.UTF8.GetBytes(BrandConfig.PasswordSaltPrefix + password));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    public static string HashLegacyUnsalted(string password)
    {
        byte[] bytes = SHA256.HashData(Encoding.UTF8.GetBytes(password));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    public static bool VerifyPassword(string inputPassword, string? storedHash)
    {
        if (string.IsNullOrWhiteSpace(storedHash)) return true;
        return string.Equals(HashPassword(inputPassword), storedHash, StringComparison.OrdinalIgnoreCase) ||
               string.Equals(HashLegacyUnsalted(inputPassword), storedHash, StringComparison.OrdinalIgnoreCase);
    }

    public static bool IsExtensionManagementScreen(string processName, string windowTitle, string? urlText)
    {
        if (!ContentGuardLists.DesktopBrowserProcesses.Contains(processName)) return false;
        string title = (windowTitle ?? "").ToLowerInvariant();
        string url = (urlText ?? "").ToLowerInvariant();
        return title.StartsWith("extensions -") ||
               title.StartsWith("extensions –") ||
               title.Equals("extensions") ||
               title.Contains("extensions - " + BrandConfig.AppName.ToLowerInvariant()) ||
               title.Contains("addons") ||
               url.Contains("://extensions") ||
               url.Contains("about:addons");
    }
}
