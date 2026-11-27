using System.Collections.Concurrent;
using SaveGaze.Core;
using Windows.Media.Control;

namespace SaveGaze.Desktop;

public sealed record PlaybackSnapshot(
    string SourceId,
    string Title,
    string Subtitle,
    long PositionMs,
    long DurationMs,
    bool IsPlaying
);

public abstract record SkipLookupState
{
    public sealed record Idle() : SkipLookupState;
    public sealed record Loading(string NormalizedTitle) : SkipLookupState;
    public sealed record Found(
        string CacheKey,
        string SourceLabel,
        string DisplayTitle,
        string? ImdbId,
        IReadOnlyList<SkipSegment> Segments) : SkipLookupState;
    public sealed record NotFound(string NormalizedTitle, long CachedAtMs) : SkipLookupState;
}

/// <summary>
/// Resolves scene skip timestamps: custom entries first (instant, offline), then CleanStream + Cinemeta APIs.
/// </summary>
public sealed class CommunitySkipRepository
{
    private const string CleanStreamCatalogUrl = "https://cleanstream.elfhosted.com/catalog/movie/cleanstream-all/search=";
    private const string CleanStreamSkipsUrl = "https://cleanstream.elfhosted.com/api/skips/";
    private const string CinemetaSearchUrl = "https://v3-cinemeta.strem.io/catalog/movie/top/search=";
    private const long NegativeTtlMs = 20 * 60_000L;

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMilliseconds(4500) };
    private readonly ConcurrentDictionary<string, SkipLookupState> _remoteCache = new(StringComparer.Ordinal);
    private volatile IReadOnlyList<CustomSkipEntry> _customEntries = [];

    static CommunitySkipRepository()
    {
        Http.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "application/json");
        Http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "SaveGaze-HybridSkip/1.0");
    }

    public void UpdateCustomEntries(IReadOnlyList<CustomSkipEntry> entries) => _customEntries = entries ?? [];

    public SkipLookupState ResolveNonBlocking(string rawTitle, string rawSubtitle = "")
    {
        string combined = !string.IsNullOrWhiteSpace(rawSubtitle) &&
            !rawTitle.Contains(rawSubtitle, StringComparison.OrdinalIgnoreCase)
            ? $"{rawTitle} {rawSubtitle}"
            : rawTitle;

        var norm = CommunitySkipRules.NormalizeTitle(combined);
        if (norm.BaseTitle.Length < 2) return new SkipLookupState.Idle();

        string exactKey = CommunitySkipRules.NormalizeKey(norm.BaseTitle, norm.EpisodeTag);
        string baseKey = CommunitySkipRules.NormalizeKey(norm.BaseTitle, null);
        var entries = _customEntries;
        var customMatch = entries.FirstOrDefault(e => e.Key == exactKey)
            ?? entries.FirstOrDefault(e => e.Key == baseKey);
        if (customMatch != null && customMatch.Segments.Count > 0)
        {
            return new SkipLookupState.Found(
                customMatch.Key, "Custom", customMatch.DisplayTitle, null, customMatch.Segments);
        }

        string cacheKey = $"{baseKey}:{norm.Year ?? 0}";
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (_remoteCache.TryGetValue(cacheKey, out var existing))
        {
            if (existing is SkipLookupState.Found or SkipLookupState.Loading) return existing;
            if (existing is SkipLookupState.NotFound nf && now - nf.CachedAtMs < NegativeTtlMs) return nf;
        }

        var loading = new SkipLookupState.Loading(norm.BaseTitle);
        _remoteCache[cacheKey] = loading;
        _ = Task.Run(() => FetchCleanStreamSegmentsAsync(cacheKey, norm));
        return loading;
    }

    private async Task FetchCleanStreamSegmentsAsync(string cacheKey, NormalizedMediaTitle norm)
    {
        try
        {
            string encoded = Uri.EscapeDataString(norm.BaseTitle);
            string? cleanJson = await HttpGetAsync($"{CleanStreamCatalogUrl}{encoded}.json");
            string? imdbId = cleanJson != null
                ? CommunitySkipRules.MatchCatalogCandidate(norm.BaseTitle, norm.Year, CommunitySkipRules.ParseCatalogJson(cleanJson))
                : null;

            if (imdbId == null)
            {
                string? cinJson = await HttpGetAsync($"{CinemetaSearchUrl}{encoded}.json");
                imdbId = cinJson != null
                    ? CommunitySkipRules.MatchCatalogCandidate(norm.BaseTitle, norm.Year, CommunitySkipRules.ParseCatalogJson(cinJson))
                    : null;
            }

            if (imdbId == null)
            {
                _remoteCache[cacheKey] = new SkipLookupState.NotFound(norm.BaseTitle, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                return;
            }

            string skipsUrl = $"{CleanStreamSkipsUrl}{imdbId}?nudity=low&sex=low&violence=off&language=off&drugs=off&fear=off";
            string? skipsJson = await HttpGetAsync(skipsUrl);
            var segments = skipsJson != null ? CommunitySkipRules.ParseCleanStreamSkipsJson(skipsJson) : [];
            if (segments.Count == 0)
            {
                _remoteCache[cacheKey] = new SkipLookupState.NotFound(norm.BaseTitle, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            }
            else
            {
                _remoteCache[cacheKey] = new SkipLookupState.Found(cacheKey, "CleanStream", norm.BaseTitle, imdbId, segments);
            }
        }
        catch
        {
            _remoteCache[cacheKey] = new SkipLookupState.NotFound(norm.BaseTitle, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        }
    }

    private static async Task<string?> HttpGetAsync(string url)
    {
        try
        {
            using var resp = await Http.GetAsync(url);
            return resp.IsSuccessStatusCode ? await resp.Content.ReadAsStringAsync() : null;
        }
        catch
        {
            return null;
        }
    }
}

/// <summary>
/// Passive Windows 10/11 GlobalSystemMediaTransportControls (GSMSC) bridge to read active playing
/// media title + position and seek directly past community/custom marked scenes across Chrome, Edge,
/// Brave, Firefox, VLC, Stremio, and desktop players.
/// </summary>
public static class WindowsMediaSessionBridge
{
    public static async Task<PlaybackSnapshot?> GetActivePlaybackSnapshotAsync(string procName, string windowTitle)
    {
        try
        {
            var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var session = PickSession(mgr.GetSessions(), mgr.GetCurrentSession(), procName);
            if (session == null) return FallbackFromWindowTitle(procName, windowTitle);

            var props = await session.TryGetMediaPropertiesAsync();
            var timeline = session.GetTimelineProperties();
            var info = session.GetPlaybackInfo();

            string title = !string.IsNullOrWhiteSpace(props?.Title) ? props.Title.Trim() : CleanWindowTitle(windowTitle);
            string subtitle = props?.Artist?.Trim() ?? "";
            bool isPlaying = info?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            long durationMs = timeline != null ? Math.Max(0L, (long)timeline.EndTime.TotalMilliseconds) : 0L;
            long posMs = ComputeLivePositionMs(timeline, isPlaying, durationMs);

            if (posMs < 0L)
            {
                var parsed = CommunitySkipRules.ParseClockTimestampFromUi(windowTitle);
                if (parsed is { } clk)
                {
                    posMs = clk.PositionMs;
                    if (durationMs <= 0L && clk.DurationMs is { } d) durationMs = d;
                }
            }

            if (string.IsNullOrWhiteSpace(title) && posMs < 0L) return null;
            return new PlaybackSnapshot(session.SourceAppUserModelId ?? procName, title, subtitle, posMs, durationMs, isPlaying);
        }
        catch
        {
            return FallbackFromWindowTitle(procName, windowTitle);
        }
    }

    public static async Task<bool> SeekActivePlaybackAsync(string procName, long targetPositionMs)
    {
        try
        {
            var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var session = PickSession(mgr.GetSessions(), mgr.GetCurrentSession(), procName);
            if (session == null) return false;
            long ticks = Math.Max(0L, targetPositionMs) * 10_000L;
            return await session.TryChangePlaybackPositionAsync(ticks);
        }
        catch
        {
            return false;
        }
    }

    private static GlobalSystemMediaTransportControlsSession? PickSession(
        IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions,
        GlobalSystemMediaTransportControlsSession? current,
        string procName)
    {
        if (sessions.Count == 0) return current;
        var matching = sessions.Where(s =>
            !string.IsNullOrEmpty(procName) &&
            (s.SourceAppUserModelId ?? "").Contains(procName, StringComparison.OrdinalIgnoreCase)).ToList();
        if (matching.Count > 0)
        {
            return matching.FirstOrDefault(s =>
                       s.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                   ?? matching[0];
        }
        return current ?? sessions.FirstOrDefault(s =>
            s.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing);
    }

    private static long ComputeLivePositionMs(
        GlobalSystemMediaTransportControlsSessionTimelineProperties? timeline,
        bool isPlaying,
        long durationMs)
    {
        if (timeline == null || timeline.EndTime <= TimeSpan.Zero) return -1L;
        long pos = (long)timeline.Position.TotalMilliseconds;
        if (pos < 0L) return -1L;

        if (isPlaying && timeline.LastUpdatedTime != DateTimeOffset.MinValue)
        {
            double elapsedMs = Math.Clamp((DateTimeOffset.UtcNow - timeline.LastUpdatedTime).TotalMilliseconds, 0.0, 10_000.0);
            pos += (long)elapsedMs;
        }
        return durationMs > 0L ? Math.Clamp(pos, 0L, durationMs) : pos;
    }

    private static PlaybackSnapshot? FallbackFromWindowTitle(string procName, string windowTitle)
    {
        string clean = CleanWindowTitle(windowTitle);
        if (string.IsNullOrWhiteSpace(clean)) return null;
        var clk = CommunitySkipRules.ParseClockTimestampFromUi(windowTitle);
        return new PlaybackSnapshot(procName, clean, "", clk?.PositionMs ?? -1L, clk?.DurationMs ?? 0L, true);
    }

    private static string CleanWindowTitle(string windowTitle) =>
        (windowTitle ?? "")
            .Replace(" - VLC media player", "", StringComparison.OrdinalIgnoreCase)
            .Replace(" - Google Chrome", "", StringComparison.OrdinalIgnoreCase)
            .Replace(" - Brave", "", StringComparison.OrdinalIgnoreCase)
            .Replace(" - Microsoft​ Edge", "", StringComparison.OrdinalIgnoreCase)
            .Replace(" - Microsoft Edge", "", StringComparison.OrdinalIgnoreCase)
            .Replace(" - Mozilla Firefox", "", StringComparison.OrdinalIgnoreCase)
            .Trim();
}
