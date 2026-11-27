using System.Text.Json;
using System.Text.Json.Serialization;

namespace SaveGaze.Core;

public sealed class AppConfig
{
    public string ExtensionId { get; set; } = "";
    public string PasswordHash { get; set; } = "";
    public string InstallDir { get; set; } = "";

    // AI Screen Shield settings (SaveGaze v2.2 parity)
    public bool ProtectionEnabled { get; set; } = true;
    public GenderFilter GenderFilter { get; set; } = GenderFilter.Both;
    public float Sensitivity { get; set; } = 0.25f;
    public int ScanIntervalMs { get; set; } = 320;
    public BlurStyle BlurStyle { get; set; } = BlurStyle.SolidBlack;
    public int BlurOpacity { get; set; } = 100;
    public bool BlurFaces { get; set; } = false;

    // Intimate Video, Scene & Audio Guard
    public bool IntimateGuardEnabled { get; set; } = true;
    public bool IntimateAudioEnabled { get; set; } = true;
    public bool AutoMovieAudioEnabled { get; set; } = true;
    public IntimateActionMode IntimateActionMode { get; set; } = IntimateActionMode.SmartContext;
    public DrmAppPolicy DrmAppPolicy { get; set; } = DrmAppPolicy.WarnOnly;
    public bool CommunitySkipEnabled { get; set; } = true;
    public List<CustomSkipEntry> CustomSkipEntries { get; set; } = [];

    // ContentGuard Parity
    public bool WebsiteBlockerEnabled { get; set; } = true;
    public bool KeywordBlockerEnabled { get; set; } = true;
    public bool AppBlockerEnabled { get; set; } = true;
    public bool BlockIncognito { get; set; } = true;
    public bool CrossBlockLockoutEnabled { get; set; } = true;
    public bool BlockSocialDistractions { get; set; } = false;
    public bool TamperGuardEnabled { get; set; } = true;
    public bool BlockHostsFile { get; set; } = true;

    // Updates
    public bool AutoCheckUpdates { get; set; } = true;
    public string DismissedUpdateVersion { get; set; } = "";
    public long RemindMeLaterUntilMs { get; set; } = 0L;

    // Lists & Counters
    public List<string> CustomKeywords { get; set; } = [];
    public List<string> CustomWebsites { get; set; } = [];
    public List<string> CustomApps { get; set; } = [];
    public List<long> BlockTimestampsMs { get; set; } = [];
    public long LockoutUntilMs { get; set; } = 0L;
    public int TotalBlockedCount { get; set; } = 0;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() }
    };

    public static string GetDefaultConfigPath()
    {
        string? customDir = Environment.GetEnvironmentVariable("SAVEGAZE_DATA_DIR");
        if (!string.IsNullOrWhiteSpace(customDir))
        {
            return Path.Combine(customDir, "config.json");
        }

        string portableCfg = Path.Combine(AppContext.BaseDirectory, "config.json");
        if (File.Exists(portableCfg)) return portableCfg;

        string dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            BrandConfig.AppName);
        return Path.Combine(dir, "config.json");
    }

    public static AppConfig Load(string? path = null)
    {
        string cfgPath = path ?? GetDefaultConfigPath();
        try
        {
            if (File.Exists(cfgPath))
            {
                string json = File.ReadAllText(cfgPath);
                var cfg = JsonSerializer.Deserialize<AppConfig>(json, JsonOpts);
                if (cfg != null)
                {
                    cfg.Normalize();
                    return cfg;
                }
            }
        }
        catch { }

        var fresh = new AppConfig();
        fresh.TryImportLegacyContentGuardConfig();
        fresh.Normalize();
        return fresh;
    }

    public void Save(string? path = null)
    {
        Normalize();
        string cfgPath = path ?? GetDefaultConfigPath();
        string? dir = Path.GetDirectoryName(cfgPath);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        string tmp = cfgPath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(this, JsonOpts));
        File.Move(tmp, cfgPath, overwrite: true);
    }

    public void Normalize()
    {
        Sensitivity = Sensitivity > 0.45f ? 0.25f : Math.Clamp(Sensitivity, 0.15f, 0.45f);
        BlurOpacity = Math.Clamp(BlurOpacity, 50, 100);
        ScanIntervalMs = Math.Clamp(ScanIntervalMs, 180, 2000);
        CustomKeywords = CustomKeywords.Select(k => k.Trim().ToLowerInvariant()).Where(k => k.Length > 0).Distinct().ToList();
        CustomWebsites = CustomWebsites.Select(ContentGuardRules.NormalizeHost).Where(w => w.Length > 0).Distinct().ToList();
        CustomApps = CustomApps.Select(a => a.Trim().ToLowerInvariant().Replace(".exe", "")).Where(a => a.Length > 0).Distinct().ToList();
        CustomSkipEntries ??= [];
    }

    public bool SaveCustomSkipEntry(string rawTitle, IReadOnlyList<SkipSegment> segments)
    {
        if (segments.Count == 0) return false;
        var norm = CommunitySkipRules.NormalizeTitle(rawTitle);
        string displayTitle = norm.EpisodeTag != null ? $"{norm.BaseTitle} ({norm.EpisodeTag})" : norm.BaseTitle;
        if (string.IsNullOrWhiteSpace(displayTitle)) return false;

        string key = CommunitySkipRules.NormalizeKey(norm.BaseTitle, norm.EpisodeTag);
        CustomSkipEntries = CustomSkipEntries
            .Where(e => !string.Equals(e.Key, key, StringComparison.Ordinal))
            .Append(new CustomSkipEntry(key, displayTitle, CommunitySkipRules.MergeNearbySegments(segments)))
            .ToList();
        return true;
    }

    public bool RemoveCustomSkipEntry(string key)
    {
        int before = CustomSkipEntries.Count;
        CustomSkipEntries = CustomSkipEntries
            .Where(e => !string.Equals(e.Key, key, StringComparison.Ordinal))
            .ToList();
        return CustomSkipEntries.Count != before;
    }

    /// <summary>
    /// One-time read-only migration from legacy C:\Users\user\ContentGuard\windows-app\config.json if present.
    /// Never modifies C:\Users\user\ContentGuard.
    /// </summary>
    public bool TryImportLegacyContentGuardConfig(string? legacyPath = null)
    {
        string path = legacyPath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "ContentGuard", "windows-app", "config.json");
        if (!File.Exists(path)) return false;

        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(path));
            var root = doc.RootElement;
            if (root.TryGetProperty("passwordHash", out var pw) && pw.ValueKind == JsonValueKind.String)
                PasswordHash = pw.GetString() ?? "";
            if (root.TryGetProperty("customKeywords", out var kws) && kws.ValueKind == JsonValueKind.Array)
                CustomKeywords.AddRange(kws.EnumerateArray().Select(e => e.GetString() ?? ""));
            if (root.TryGetProperty("customWebsites", out var ws) && ws.ValueKind == JsonValueKind.Array)
                CustomWebsites.AddRange(ws.EnumerateArray().Select(e => e.GetString() ?? ""));
            if (root.TryGetProperty("customApps", out var apps) && apps.ValueKind == JsonValueKind.Array)
                CustomApps.AddRange(apps.EnumerateArray().Select(e => e.GetString() ?? ""));
            if (root.TryGetProperty("settings", out var s) && s.ValueKind == JsonValueKind.Object)
            {
                if (s.TryGetProperty("blockIncognito", out var bi)) BlockIncognito = bi.GetBoolean();
                if (s.TryGetProperty("blockHostsFile", out var bh)) BlockHostsFile = bh.GetBoolean();
            }
            return true;
        }
        catch
        {
            return false;
        }
    }
}
