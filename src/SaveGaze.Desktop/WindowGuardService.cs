using System.Diagnostics;
using System.Windows.Automation;
using Microsoft.Win32;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

/// <summary>
/// OS-level real-time guard (350ms tick):
/// - App killer, regedit/incognito tamper defense, browser extension removal & settings shield
/// - Browser address bar + live search input single-word keyword guard
/// - Caption/subtitle intimate guard ([moaning], (kissing), etc.) & DRM app policy
/// - 5-in-10m cross-block tracking + fullscreen lockout sync
/// </summary>
public sealed class WindowGuardService : IDisposable
{
    private readonly Control _uiInvoker;
    private readonly Func<AppConfig> _getConfig;
    private readonly Action<AppConfig> _updateConfig;
    private readonly OverlayManager _overlay;
    private readonly ContextExitController _exitController;
    private readonly Action<string, string>? _onBlockedEvent;

    private readonly System.Windows.Forms.Timer _tickTimer;
    private readonly HashSet<string> _drmWarnedSession = new(StringComparer.OrdinalIgnoreCase);
    private bool _challengeActive;
    private long _lastBlockMs;
    private long _lastCaptionTriggerMs;
    private long _lastPolicySyncMs;

    public WindowGuardService(
        Control uiInvoker,
        Func<AppConfig> getConfig,
        Action<AppConfig> updateConfig,
        OverlayManager overlay,
        ContextExitController exitController,
        Action<string, string>? onBlockedEvent = null)
    {
        _uiInvoker = uiInvoker;
        _getConfig = getConfig;
        _updateConfig = updateConfig;
        _overlay = overlay;
        _exitController = exitController;
        _onBlockedEvent = onBlockedEvent;

        _tickTimer = new System.Windows.Forms.Timer { Interval = 350 };
        _tickTimer.Tick += OnTick;
    }

    public void Start() => _tickTimer.Start();

    private void OnTick(object? sender, EventArgs e)
    {
        if (NativeMethods.IsWorkstationLocked()) return;

        var cfg = _getConfig();
        long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        // 1. Sync 5-min Auto-Lockout overlay
        _overlay.SyncLockout(
            cfg.LockoutUntilMs,
            cfg.PasswordHash,
            onUnlockedEarly: () =>
            {
                var c = _getConfig();
                c.LockoutUntilMs = 0L;
                c.BlockTimestampsMs.Clear();
                c.Save();
                _updateConfig(c);
            },
            onExpired: () =>
            {
                var c = _getConfig();
                c.LockoutUntilMs = 0L;
                c.BlockTimestampsMs.Clear();
                c.Save();
                _updateConfig(c);
            });

        // 2. Periodically enforce Incognito policies & hosts file integrity (every 30s)
        if (nowMs - _lastPolicySyncMs >= 30_000)
        {
            _lastPolicySyncMs = nowMs;
            if (cfg.BlockIncognito) InstallerEngine.EnforceIncognitoPolicies();
        }

        // 3. Kill custom blocked apps
        if (cfg.AppBlockerEnabled && cfg.CustomApps.Count > 0)
        {
            foreach (string app in cfg.CustomApps)
            {
                try
                {
                    foreach (var p in Process.GetProcessesByName(app))
                    {
                        try
                        {
                            p.Kill();
                            RecordBlockAndLockout($"Blocked App: {app}", $"{app}.exe terminated");
                        }
                        catch { }
                    }
                }
                catch { }
            }
        }

        // 4. Browser Extension Removal Dialog Defense
        if (cfg.TamperGuardEnabled && !_challengeActive)
        {
            CheckBrowserExtensionRemoveDialogs(cfg);
        }

        // 5. Inspect Foreground Window
        var (hwnd, procName, title, _) = ContextExitController.CaptureForegroundWindowInfo();
        if (hwnd == IntPtr.Zero || string.IsNullOrEmpty(title)) return;
        if (procName.Equals(BrandConfig.AppName, StringComparison.OrdinalIgnoreCase)) return;

        // 6. Registry editor tamper shield
        if ((cfg.BlockIncognito || cfg.TamperGuardEnabled) && procName == "regedit")
        {
            try
            {
                NativeMethods.GetWindowThreadProcessId(hwnd, out uint pid);
                Process.GetProcessById((int)pid).Kill();
                InstallerEngine.EnforceIncognitoPolicies();
                _overlay.ShowBlockBanner("Registry Access Blocked",
                    $"Registry Editor was closed to protect {BrandConfig.AppName} policies.");
            }
            catch { }
            return;
        }

        bool isBrowser = ContentGuardLists.DesktopBrowserProcesses.Contains(procName);
        string urlText = isBrowser ? ContextExitController.TryReadBrowserAddressBar(hwnd) : "";

        // 7. Extension settings tamper protection
        if (cfg.TamperGuardEnabled && isBrowser && !_challengeActive &&
            ContentGuardRules.IsExtensionManagementScreen(procName, title, urlText))
        {
            _challengeActive = true;
            try
            {
                bool passed = VerificationDialogForm.VerifyProtectionChange(
                    "Extension Management & Deactivation", cfg.PasswordHash, null, requireSentence: true);
                if (!passed)
                {
                    CloseActiveTab(hwnd);
                    _overlay.ShowBlockBanner("Extension Shield Active",
                        $"Extension settings closed to protect {BrandConfig.AppName}.");
                    return;
                }
            }
            finally
            {
                _challengeActive = false;
            }
        }

        // 8. DRM App/Site Policy (Warn Only / Auto-Block / Off)
        if (cfg.ProtectionEnabled && cfg.DrmAppPolicy != DrmAppPolicy.Off)
        {
            string? drmHit = IntimateExitRules.DetectDrmTarget(procName, title, urlText);
            if (drmHit != null)
            {
                if (cfg.DrmAppPolicy == DrmAppPolicy.AutoBlock)
                {
                    if (isBrowser) CloseActiveTab(hwnd);
                    else
                    {
                        try
                        {
                            NativeMethods.GetWindowThreadProcessId(hwnd, out uint pid);
                            Process.GetProcessById((int)pid).Kill();
                        }
                        catch { }
                    }
                    RecordBlockAndLockout("DRM Streaming Blocked",
                        $"Blocked {drmHit} (DRM prevents AI scene inspection)");
                    return;
                }
                else if (cfg.DrmAppPolicy == DrmAppPolicy.WarnOnly && _drmWarnedSession.Add(drmHit))
                {
                    _overlay.ShowBlockBanner($"DRM Notice ({drmHit})",
                        "DRM apps/sites may restrict AI inspection. Intimate auto-skip may not apply inside DRM video frames.");
                }
            }
        }

        // 9. URL, Domain, Title & Live Search Input Keyword Blocker
        if ((cfg.WebsiteBlockerEnabled || cfg.KeywordBlockerEnabled) && (isBrowser || procName == "telegram" || procName == "discord"))
        {
            var activeDomains = cfg.WebsiteBlockerEnabled
                ? ContentGuardRules.BuildActiveDomains(cfg.CustomWebsites, cfg.BlockSocialDistractions)
                : new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            // Check address bar URL
            if (!string.IsNullOrWhiteSpace(urlText))
            {
                var urlDecision = ContentGuardRules.CheckUrl(urlText, activeDomains, cfg.CustomKeywords, cfg.KeywordBlockerEnabled);
                if (urlDecision.Blocked)
                {
                    InterceptBrowserViolation(hwnd, urlDecision.Reason, urlDecision.MatchedItem);
                    return;
                }
            }

            // Check live typed search input via UIA FocusedElement (single-word instant guard)
            string focusedText = ContextExitController.TryReadFocusedInputText();
            if (!string.IsNullOrWhiteSpace(focusedText) && focusedText.Length <= 220)
            {
                var searchDecision = ContentGuardRules.CheckSearchOrInputText(
                    focusedText, activeDomains, cfg.CustomKeywords, cfg.KeywordBlockerEnabled);
                if (searchDecision.Blocked)
                {
                    InterceptBrowserViolation(hwnd, $"Search Blocked: {searchDecision.Reason}", searchDecision.MatchedItem);
                    return;
                }
            }

            // Check window title for blocked domain or single custom keyword / adult keyword
            var titleDecision = ContentGuardRules.CheckUrl(title, activeDomains, cfg.CustomKeywords, cfg.KeywordBlockerEnabled);
            if (titleDecision.Blocked && !title.Contains("Settings", StringComparison.OrdinalIgnoreCase))
            {
                InterceptBrowserViolation(hwnd, titleDecision.Reason, titleDecision.MatchedItem);
                return;
            }
        }

        // 10. Subtitle / Caption Intimate Guard ([moaning], (kissing), etc.)
        if (cfg.ProtectionEnabled && cfg.IntimateGuardEnabled && !_exitController.IsActionInProgress &&
            (nowMs - _lastCaptionTriggerMs >= 2500) &&
            (isBrowser || procName is "vlc" or "stremio" or "nuvio" or "mpv" or "kodi"))
        {
            string captionText = ContextExitController.TryReadCaptionCandidates(hwnd);
            string? hit = IntimateCaptionRules.DetectIntimateCaption(captionText);
            if (hit != null)
            {
                _lastCaptionTriggerMs = nowMs;
                _exitController.TriggerIntimateAction(new IntimateSignals(
                    Triggered: true,
                    Instant: true,
                    Reason: $"Intimate Caption ({hit})",
                    Confidence: 0.85f,
                    IsKissOrBedCue: false),
                    urlText);
            }
        }
    }

    private void CheckBrowserExtensionRemoveDialogs(AppConfig cfg)
    {
        try
        {
            foreach (string procName in new[] { "brave", "chrome", "msedge" })
            {
                foreach (var bp in Process.GetProcessesByName(procName))
                {
                    if (bp.MainWindowHandle == IntPtr.Zero) continue;
                    try
                    {
                        var root = AutomationElement.FromHandle(bp.MainWindowHandle);
                        string appName = BrandConfig.AppName;
                        var cond = new OrCondition(
                            new PropertyCondition(AutomationElement.NameProperty, $"Remove \"{appName}\"?"),
                            new PropertyCondition(AutomationElement.NameProperty, $"Remove '{appName}'?"),
                            new PropertyCondition(AutomationElement.NameProperty, $"Remove \"{BrandConfig.LegacyAppName}\"?"));
                        var dlg = root.FindFirst(TreeScope.Descendants, cond);
                        if (dlg != null)
                        {
                            NativeMethods.SendKey(NativeMethods.VK_ESCAPE);
                            _challengeActive = true;
                            try
                            {
                                _overlay.ShowBlockBanner("Extension Protected",
                                    $"{BrandConfig.AppName} extension removal was blocked.");
                                VerificationDialogForm.VerifyProtectionChange(
                                    $"Uninstall {BrandConfig.AppName} Extension", cfg.PasswordHash, null, requireSentence: true);
                            }
                            finally
                            {
                                _challengeActive = false;
                            }
                            return;
                        }
                    }
                    catch { }
                }
            }
        }
        catch { }
    }

    private void InterceptBrowserViolation(IntPtr hwnd, string reason, string detail)
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (now - _lastBlockMs < 1400) return;
        _lastBlockMs = now;

        CloseActiveTab(hwnd);
        RecordBlockAndLockout(reason, detail);
    }

    public void RecordBlockAndLockout(string reason, string detail)
    {
        var cfg = _getConfig();
        long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var update = ContentGuardRules.RecordCrossBlockEvent(cfg.BlockTimestampsMs, nowMs, cfg.CrossBlockLockoutEnabled);

        cfg.BlockTimestampsMs = update.Timestamps.ToList();
        cfg.TotalBlockedCount++;
        if (update.TriggeredLockout)
        {
            cfg.LockoutUntilMs = update.LockoutUntilMs;
        }
        cfg.Save();
        _updateConfig(cfg);

        _overlay.ShowBlockBanner(reason, detail);
        _onBlockedEvent?.Invoke(reason, detail);
    }

    private static void CloseActiveTab(IntPtr hwnd)
    {
        try
        {
            NativeMethods.SetForegroundWindow(hwnd);
            NativeMethods.SendKeyCombo(NativeMethods.VK_CONTROL, NativeMethods.VK_W);
        }
        catch { }
    }

    public void Dispose()
    {
        _tickTimer.Stop();
        _tickTimer.Dispose();
    }
}
