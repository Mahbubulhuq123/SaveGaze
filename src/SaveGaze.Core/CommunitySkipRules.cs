using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SaveGaze.Core;

public sealed record SkipSegment(
    long StartMs,
    long EndMs,
    string Category = "nudity",
    string Severity = "high"
);

public sealed record CatalogCandidate(
    string ImdbId,
    string Title,
    int? Year = null
);

public sealed record NormalizedMediaTitle(
    string BaseTitle,
    int? Year,
    string? EpisodeTag
);

public sealed record CustomSkipEntry(
    string Key,
    string DisplayTitle,
    List<SkipSegment> Segments
);

public sealed record MatchedSkipWindow(
    SkipSegment Segment,
    long TargetEndMs
);

/// <summary>
/// Pure title normalizer, CleanStream / MCF timestamp parser, catalog matcher,
/// and custom skip window matcher. Shared across Desktop, Tests, and Extension sync.
/// </summary>
public static partial class CommunitySkipRules
{
    [GeneratedRegex(@"\b[sS](\d{1,2})[\s._-]*[eE](\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b")]
    private static partial Regex EpisodeRegex();

    [GeneratedRegex(@"\bseason\s*(\d{1,2})\s*episode\s*(\d{1,3})\b", RegexOptions.IgnoreCase)]
    private static partial Regex WordEpisodeRegex();

    [GeneratedRegex(@"\b(19\d{2}|20\d{2})\b")]
    private static partial Regex YearRegex();

    [GeneratedRegex(@"\.(mkv|mp4|avi|mov|webm|m4v)$", RegexOptions.IgnoreCase)]
    private static partial Regex FileExtRegex();

    [GeneratedRegex(
        @"\b(2160p|1080p|720p|480p|4k|hdr|dv|dolby|atmos|web[- ]?dl|webrip|bluray|blu-ray|bdrip|hdrip|remux|x264|x265|hevc|h\.?264|h\.?265|aac\d*(?:\.\d)?|ddp\d*(?:\.\d)?|dts|multi|dual)\b",
        RegexOptions.IgnoreCase)]
    private static partial Regex ReleaseJunkRegex();

    [GeneratedRegex(@"\[[^\]]*]|\([^\)]*\)")]
    private static partial Regex BracketGroupRegex();

    [GeneratedRegex(
        @"(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d{1,3})?)\s*(?:-|–|—|->|to)\s*(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d{1,3})?)",
        RegexOptions.IgnoreCase)]
    private static partial Regex TimeRangeRegex();

    [GeneratedRegex(@"\b(\d{1,2}:\d{2}(?::\d{2})?)\s*/\s*(\d{1,2}:\d{2}(?::\d{2})?)\b")]
    private static partial Regex ClockPairRegex();

    [GeneratedRegex(@"[^a-z0-9]+")]
    private static partial Regex NonAlphaNumRegex();

    private static readonly HashSet<string> IntimateCategories = new(StringComparer.OrdinalIgnoreCase)
    {
        "nudity", "sex", "sexual", "intimate", "kissing"
    };

    public static NormalizedMediaTitle NormalizeTitle(string rawTitle)
    {
        string s = (rawTitle ?? "").Trim();
        if (s.Length == 0) return new NormalizedMediaTitle("", null, null);

        s = FileExtRegex().Replace(s, "");

        string? episodeTag = null;
        var epMatch = EpisodeRegex().Match(s);
        if (epMatch.Success)
        {
            string sRaw = epMatch.Groups[1].Length > 0 ? epMatch.Groups[1].Value : epMatch.Groups[3].Value;
            string eRaw = epMatch.Groups[2].Length > 0 ? epMatch.Groups[2].Value : epMatch.Groups[4].Value;
            if (int.TryParse(sRaw, out int sNum) && int.TryParse(eRaw, out int eNum))
            {
                episodeTag = $"S{sNum:D2}E{eNum:D2}";
            }
            s = s.Remove(epMatch.Index, epMatch.Length);
        }
        else
        {
            var wMatch = WordEpisodeRegex().Match(s);
            if (wMatch.Success)
            {
                if (int.TryParse(wMatch.Groups[1].Value, out int sNum) &&
                    int.TryParse(wMatch.Groups[2].Value, out int eNum))
                {
                    episodeTag = $"S{sNum:D2}E{eNum:D2}";
                }
                s = s.Remove(wMatch.Index, wMatch.Length);
            }
        }

        int? extractedYear = null;
        foreach (Match m in YearRegex().Matches(s))
        {
            if (int.TryParse(m.Groups[1].Value, out int y) && y is >= 1920 and <= 2035)
            {
                extractedYear = y;
            }
        }

        s = BracketGroupRegex().Replace(s, " ");
        s = s.Replace('.', ' ').Replace('_', ' ');

        var junkMatch = ReleaseJunkRegex().Match(s);
        if (junkMatch.Success && junkMatch.Index > 1)
        {
            s = s[..junkMatch.Index];
        }
        else
        {
            s = ReleaseJunkRegex().Replace(s, " ");
        }

        if (extractedYear is { } yr)
        {
            s = Regex.Replace(s, $@"\b{yr}\b", " ");
        }

        string cleaned = Regex.Replace(s, @"[\s\-–—:|]+$", "");
        cleaned = Regex.Replace(cleaned, @"\s+", " ").Trim();
        return new NormalizedMediaTitle(cleaned, extractedYear, episodeTag);
    }

    public static string NormalizeKey(string title, string? episodeTag = null)
    {
        string normTitle = NormalizeAlphaNum(title);
        return string.IsNullOrWhiteSpace(episodeTag)
            ? normTitle
            : $"{normTitle}|{episodeTag.ToUpperInvariant()}";
    }

    private static string NormalizeAlphaNum(string text) =>
        NonAlphaNumRegex().Replace((text ?? "").ToLowerInvariant(), " ").Trim();

    public static string? MatchCatalogCandidate(
        string queryTitle,
        int? queryYear,
        IReadOnlyList<CatalogCandidate> candidates)
    {
        if (string.IsNullOrWhiteSpace(queryTitle) || candidates.Count == 0) return null;
        string target = NormalizeAlphaNum(queryTitle);
        if (target.Length == 0) return null;

        string? bestId = null;
        int bestScore = 0;

        foreach (var cand in candidates)
        {
            string candNorm = NormalizeAlphaNum(cand.Title);
            if (candNorm.Length == 0 || !cand.ImdbId.StartsWith("tt", StringComparison.Ordinal)) continue;

            int score = 0;
            if (candNorm == target && queryYear != null && cand.Year == queryYear) score = 100;
            else if (candNorm == target) score = 90;
            else if ((candNorm.StartsWith(target, StringComparison.Ordinal) ||
                      target.StartsWith(candNorm, StringComparison.Ordinal)) &&
                     queryYear != null && cand.Year == queryYear) score = 75;

            if (score > bestScore)
            {
                bestScore = score;
                bestId = cand.ImdbId;
            }
        }

        return bestId;
    }

    public static List<SkipSegment> ParseCleanStreamSkipsJson(string jsonStr, bool requireIntimateCategory = true)
    {
        if (string.IsNullOrWhiteSpace(jsonStr)) return [];
        try
        {
            using var doc = JsonDocument.Parse(jsonStr);
            if (!doc.RootElement.TryGetProperty("skips", out var skipsEl) || skipsEl.ValueKind != JsonValueKind.Array)
            {
                return [];
            }

            var raw = new List<SkipSegment>();
            foreach (var item in skipsEl.EnumerateArray())
            {
                if (!item.TryGetProperty("startMs", out var sEl) || !sEl.TryGetInt64(out long startMs)) continue;
                if (!item.TryGetProperty("endMs", out var eEl) || !eEl.TryGetInt64(out long endMs)) continue;
                string category = item.TryGetProperty("category", out var cEl)
                    ? (cEl.GetString() ?? "nudity").ToLowerInvariant()
                    : "nudity";
                string severity = item.TryGetProperty("severity", out var svEl)
                    ? (svEl.GetString() ?? "medium").ToLowerInvariant()
                    : "medium";
                if (endMs <= startMs || startMs < 0L) continue;
                if (requireIntimateCategory && !IntimateCategories.Contains(category)) continue;
                raw.Add(new SkipSegment(startMs, endMs, category, severity));
            }
            return MergeNearbySegments(raw);
        }
        catch
        {
            return [];
        }
    }

    public static List<CatalogCandidate> ParseCatalogJson(string jsonStr)
    {
        if (string.IsNullOrWhiteSpace(jsonStr)) return [];
        try
        {
            using var doc = JsonDocument.Parse(jsonStr);
            JsonElement arr;
            if (!doc.RootElement.TryGetProperty("metas", out arr) &&
                !doc.RootElement.TryGetProperty("filters", out arr))
            {
                return [];
            }
            if (arr.ValueKind != JsonValueKind.Array) return [];

            var outList = new List<CatalogCandidate>();
            foreach (var obj in arr.EnumerateArray())
            {
                string? imdbId = TryGetString(obj, "imdb_id")
                    ?? TryGetString(obj, "imdbId")
                    ?? TryGetString(obj, "id");
                string? title = TryGetString(obj, "name") ?? TryGetString(obj, "title");
                if (string.IsNullOrWhiteSpace(imdbId) || string.IsNullOrWhiteSpace(title) ||
                    !imdbId.StartsWith("tt", StringComparison.Ordinal))
                {
                    continue;
                }

                int? year = null;
                if (obj.TryGetProperty("year", out var yEl) && yEl.TryGetInt32(out int yVal))
                {
                    year = yVal;
                }
                else if (TryGetString(obj, "releaseInfo") is { } rel)
                {
                    var ym = YearRegex().Match(rel);
                    if (ym.Success && int.TryParse(ym.Groups[1].Value, out int ry)) year = ry;
                }

                outList.Add(new CatalogCandidate(imdbId, title, year));
            }
            return outList;
        }
        catch
        {
            return [];
        }
    }

    public static List<SkipSegment> ParseCustomTimestampsInput(string rawInput)
    {
        string trimmed = (rawInput ?? "").Trim();
        if (trimmed.Length == 0) return [];

        if (trimmed.Contains("\"startMs\"", StringComparison.Ordinal) &&
            trimmed.Contains("\"endMs\"", StringComparison.Ordinal))
        {
            string wrapped = trimmed.Contains("\"skips\"", StringComparison.Ordinal)
                ? trimmed
                : $"{{\"skips\":{trimmed}}}";
            var parsed = ParseCleanStreamSkipsJson(wrapped, requireIntimateCategory: true);
            if (parsed.Count > 0) return parsed;
            var generic = ParseCleanStreamSkipsJson(wrapped, requireIntimateCategory: false);
            if (generic.Count > 0) return generic;
        }

        var segments = new List<SkipSegment>();
        foreach (Match m in TimeRangeRegex().Matches(trimmed))
        {
            long? startMs = ParseClockToMs(m.Groups[1].Value);
            long? endMs = ParseClockToMs(m.Groups[2].Value);
            if (startMs is { } s && endMs is { } e && e > s)
            {
                segments.Add(new SkipSegment(s, e, "custom", "high"));
            }
        }
        return MergeNearbySegments(segments);
    }

    public static List<SkipSegment> MergeNearbySegments(IReadOnlyList<SkipSegment> segments, long gapToleranceMs = 1_500L)
    {
        if (segments.Count <= 1) return segments.OrderBy(s => s.StartMs).ToList();
        var sorted = segments.OrderBy(s => s.StartMs).ToList();
        var merged = new List<SkipSegment>();
        var current = sorted[0];
        for (int i = 1; i < sorted.Count; i++)
        {
            var next = sorted[i];
            if (next.StartMs <= current.EndMs + gapToleranceMs)
            {
                current = current with { EndMs = Math.Max(current.EndMs, next.EndMs) };
            }
            else
            {
                merged.Add(current);
                current = next;
            }
        }
        merged.Add(current);
        return merged;
    }

    public static MatchedSkipWindow? FindActiveSkipWindow(
        long positionMs,
        IReadOnlyList<SkipSegment> segments,
        long lookaheadMs = 500L,
        long minRemainingMs = 2_000L)
    {
        if (positionMs < 0L || segments.Count == 0) return null;
        foreach (var seg in segments)
        {
            long triggerStart = Math.Max(0L, seg.StartMs - lookaheadMs);
            long triggerEnd = seg.EndMs - minRemainingMs;
            if (triggerEnd > triggerStart && positionMs >= triggerStart && positionMs <= triggerEnd)
            {
                return new MatchedSkipWindow(seg, seg.EndMs + 400L);
            }
        }
        return null;
    }

    public static (long PositionMs, long? DurationMs)? ParseClockTimestampFromUi(string screenText)
    {
        if (string.IsNullOrWhiteSpace(screenText)) return null;
        var m = ClockPairRegex().Match(screenText);
        if (!m.Success) return null;
        long? cur = ParseClockToMs(m.Groups[1].Value);
        if (cur == null) return null;
        return (cur.Value, ParseClockToMs(m.Groups[2].Value));
    }

    public static long? ParseClockToMs(string raw)
    {
        string clean = (raw ?? "").Trim();
        string[] parts = clean.Split(':');
        if (parts.Length is < 2 or > 3) return null;
        if (!double.TryParse(parts[^1], NumberStyles.Float, CultureInfo.InvariantCulture, out double secPart)) return null;
        if (!long.TryParse(parts[^2], NumberStyles.Integer, CultureInfo.InvariantCulture, out long mins)) return null;
        long hrs = 0L;
        if (parts.Length == 3 && !long.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out hrs)) return null;
        if (hrs < 0 || mins is < 0 or > 59 || secPart < 0.0 || secPart >= 60.0) return null;
        return ((hrs * 3600L + mins * 60L) * 1000L) + (long)(secPart * 1000.0);
    }

    public static string FormatMs(long ms)
    {
        long totalSec = Math.Max(0L, ms / 1000L);
        long hrs = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        return hrs > 0 ? $"{hrs}:{mins:D2}:{secs:D2}" : $"{mins:D2}:{secs:D2}";
    }

    private static string? TryGetString(JsonElement obj, string prop)
    {
        if (obj.TryGetProperty(prop, out var el) && el.ValueKind == JsonValueKind.String)
        {
            return el.GetString();
        }
        return null;
    }
}
