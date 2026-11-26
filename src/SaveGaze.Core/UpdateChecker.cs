using System.Net.Http.Headers;
using System.Text.Json;

namespace SaveGaze.Core;

public sealed record PcUpdateInfo(
    string VersionName,
    int VersionCode,
    string ReleaseNotes,
    string DownloadUrl,
    long SizeBytes,
    string AssetName,
    string ReleaseUrl,
    string PublishedAt
);

public static class UpdateChecker
{
    public static bool IsNewerVersion(
        string remoteVersion,
        string currentVersion,
        int remoteCode = 0,
        int currentCode = 0)
    {
        string cleanRemote = StripPrefix(remoteVersion);
        string cleanCurrent = StripPrefix(currentVersion);

        if (!string.IsNullOrWhiteSpace(cleanRemote) && !string.IsNullOrWhiteSpace(cleanCurrent))
        {
            int[] remoteParts = ParseSemverParts(cleanRemote);
            int[] currentParts = ParseSemverParts(cleanCurrent);
            if (remoteParts.Length > 0 && currentParts.Length > 0)
            {
                int maxLen = Math.Max(remoteParts.Length, currentParts.Length);
                for (int i = 0; i < maxLen; i++)
                {
                    int r = i < remoteParts.Length ? remoteParts[i] : 0;
                    int c = i < currentParts.Length ? currentParts[i] : 0;
                    if (r > c) return true;
                    if (r < c) return false;
                }
            }
        }

        return remoteCode > 0 && currentCode > 0 && remoteCode > currentCode;
    }

    private static string StripPrefix(string version)
    {
        string s = (version ?? "").Trim();
        if (s.StartsWith(BrandConfig.PcReleaseTagPrefix, StringComparison.OrdinalIgnoreCase))
            s = s[BrandConfig.PcReleaseTagPrefix.Length..];
        else if (s.StartsWith("v", StringComparison.OrdinalIgnoreCase))
            s = s[1..];
        return s;
    }

    private static int[] ParseSemverParts(string ver)
    {
        string main = ver.Split(['-', '+'])[0];
        return main.Split('.')
            .Select(p => int.TryParse(p, out int n) ? (int?)n : null)
            .Where(n => n.HasValue)
            .Select(n => n!.Value)
            .ToArray();
    }

    public static PcUpdateInfo? ParsePcReleaseFromGitHubArray(string releasesJson)
    {
        using var doc = JsonDocument.Parse(releasesJson);
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return null;

        foreach (var rel in doc.RootElement.EnumerateArray())
        {
            if (rel.TryGetProperty("draft", out var draft) && draft.GetBoolean()) continue;
            string tag = rel.TryGetProperty("tag_name", out var t) ? (t.GetString() ?? "").Trim() : "";
            if (!tag.StartsWith(BrandConfig.PcReleaseTagPrefix, StringComparison.OrdinalIgnoreCase)) continue;

            string ver = StripPrefix(tag);
            if (string.IsNullOrWhiteSpace(ver)) continue;

            string body = rel.TryGetProperty("body", out var b) ? (b.GetString() ?? "") : "";
            string htmlUrl = rel.TryGetProperty("html_url", out var hu) ? (hu.GetString() ?? "") : "";
            string pubAt = rel.TryGetProperty("published_at", out var pa) ? (pa.GetString() ?? "") : "";

            string dlUrl = "";
            string assetName = "";
            long size = 0;

            if (rel.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
            {
                // Prefer Setup.exe first, then portable .zip
                foreach (var asset in assets.EnumerateArray())
                {
                    string name = asset.TryGetProperty("name", out var n) ? (n.GetString() ?? "") : "";
                    if (name.EndsWith("Setup.exe", StringComparison.OrdinalIgnoreCase) ||
                        name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                    {
                        dlUrl = asset.TryGetProperty("browser_download_url", out var u) ? (u.GetString() ?? "") : "";
                        size = asset.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                        assetName = name;
                        break;
                    }
                }
                if (string.IsNullOrEmpty(dlUrl))
                {
                    foreach (var asset in assets.EnumerateArray())
                    {
                        string name = asset.TryGetProperty("name", out var n) ? (n.GetString() ?? "") : "";
                        if (name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) &&
                            !name.Contains("extension", StringComparison.OrdinalIgnoreCase))
                        {
                            dlUrl = asset.TryGetProperty("browser_download_url", out var u) ? (u.GetString() ?? "") : "";
                            size = asset.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                            assetName = name;
                            break;
                        }
                    }
                }
            }

            if (string.IsNullOrEmpty(dlUrl)) continue;
            return new PcUpdateInfo(ver, 0, body, dlUrl, size, assetName, htmlUrl, pubAt);
        }

        return null;
    }

    public static PcUpdateInfo? ParseFallbackVersionJson(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        string ver = root.TryGetProperty("versionName", out var v) ? (v.GetString() ?? "").Trim() : "";
        if (string.IsNullOrWhiteSpace(ver)) return null;

        int code = root.TryGetProperty("versionCode", out var vc) ? vc.GetInt32() : 0;
        string notes = root.TryGetProperty("releaseNotes", out var rn) ? (rn.GetString() ?? "") : "";
        string url = root.TryGetProperty("downloadUrl", out var du) ? (du.GetString() ?? "") : "";
        long size = root.TryGetProperty("installerSize", out var sz) ? sz.GetInt64() : 0;
        string pubAt = root.TryGetProperty("publishedAt", out var pa) ? (pa.GetString() ?? "") : "";

        if (string.IsNullOrWhiteSpace(url)) return null;
        string assetName = url.Split('/').LastOrDefault() ?? $"{BrandConfig.AppName}-Setup.exe";
        return new PcUpdateInfo(ver, code, notes, url, size, assetName, url, pubAt);
    }

    public static async Task<PcUpdateInfo?> CheckLatestAsync(CancellationToken ct = default)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(12) };
        client.DefaultRequestHeaders.UserAgent.Add(
            new ProductInfoHeaderValue(BrandConfig.AppName + "PC", BrandConfig.AppVersion));
        client.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/vnd.github.v3+json"));

        try
        {
            string relJson = await client.GetStringAsync(BrandConfig.GitHubReleasesApiUrl, ct);
            var parsed = ParsePcReleaseFromGitHubArray(relJson);
            if (parsed != null) return parsed;
        }
        catch { }

        try
        {
            string fallbackJson = await client.GetStringAsync(BrandConfig.FallbackVersionUrl, ct);
            return ParseFallbackVersionJson(fallbackJson);
        }
        catch
        {
            return null;
        }
    }
}
