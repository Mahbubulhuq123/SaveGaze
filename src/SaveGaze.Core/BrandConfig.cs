namespace SaveGaze.Core;

/// <summary>
/// Centralized branding, identifiers, and repository constants.
/// Rename the application everywhere on Windows by editing this single file (and extension/brand.js).
/// </summary>
public static class BrandConfig
{
    public const string AppName = "SaveGaze";
    public const string DesktopDisplayName = "SaveGaze PC";
    public const string Subtitle = "On-Device AI Screen Shield & Content Guard";
    public const string ArabicTag = "حفظ البصر";

    public const string AppVersion = "1.1.0";
    public const int AppVersionCode = 10100;

    // GitHub repository & PC release feed (isolated from Android v* releases via pc-v* tag prefix)
    public const string GitHubOwner = "Mahbubulhuq123";
    public const string GitHubRepo = "SaveGaze";
    public const string PcReleaseTagPrefix = "pc-v";
    public const string PcBranch = "pc-version";
    public const string GitHubReleasesApiUrl = $"https://api.github.com/repos/{GitHubOwner}/{GitHubRepo}/releases?per_page=20";
    public const string FallbackVersionUrl = $"https://raw.githubusercontent.com/{GitHubOwner}/{GitHubRepo}/{PcBranch}/pc-version.json";

    // Windows OS identifiers
    public const string NativeHostName = "com.savegaze.host";
    public const string DefaultExtensionId = "fmaficaffkacmlcbhhjkhhkhfibhomnj";
    public const string ScheduledTaskName = "SaveGazeService";
    public const string RunRegistryValueName = "SaveGaze";
    public const string MutexName = @"Local\SaveGaze_PC_SingleInstance_v1";
    public const string PipeName = "SaveGazeIpcPipe_v1";

    // Legacy ContentGuard identifiers (migrated/disabled cleanly without altering C:\Users\user\ContentGuard files)
    public const string LegacyAppName = "ContentGuard";
    public const string LegacyNativeHostName = "com.contentguard.host";
    public const string LegacyScheduledTaskName = "ContentGuardService";
    public const string LegacyRunRegistryValueName = "ContentGuard";
    public const string LegacyHostsMarkerBegin = "# >>> ContentGuard Block List – DO NOT EDIT <<<";
    public const string LegacyHostsMarkerEnd = "# >>> ContentGuard Block List END <<<";

    // Hosts file markers
    public const string HostsMarkerBegin = "# >>> SaveGaze Block List – DO NOT EDIT <<<";
    public const string HostsMarkerEnd = "# >>> SaveGaze Block List END <<<";

    // Crypto salt shared with SaveGaze Android & Extension
    public const string PasswordSaltPrefix = "contentguard_salt_2024:";
    public const string ExtensionPasswordSaltSuffix = "contentguard_salt_2024";

    // Theme colors (R, G, B)
    public static readonly (byte R, byte G, byte B) BgDark = (15, 23, 42);
    public static readonly (byte R, byte G, byte B) CardDark = (30, 41, 59);
    public static readonly (byte R, byte G, byte B) AccentPurple = (124, 58, 237);
    public static readonly (byte R, byte G, byte B) AccentRose = (244, 63, 94);
    public static readonly (byte R, byte G, byte B) AccentCyan = (56, 189, 248);
    public static readonly (byte R, byte G, byte B) AccentGreen = (74, 222, 128);
}
