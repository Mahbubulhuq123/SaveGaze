using System.Diagnostics;
using System.Text;
using System.Windows.Automation;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

/// <summary>
/// Context-aware intimate scene response ("Blur First, Then Act"):
/// 1. Covers screen immediately with IntimateShield + full-screen blur
/// 2. Classifies foreground app/browser context (Reels/Shorts, Movie/Series Player, Browser/General)
/// 3. Dispatches context-appropriate exit/skip input
/// </summary>
public sealed class ContextExitController
{
    private readonly Func<AppConfig> _getConfig;
    private readonly OverlayManager _overlay;
    private readonly Action<string, string>? _onAuditLog;

    private int _actionInProgress;
    private long _lastActionMs;
    private int _consecutiveSkipsOnSameVideo;
    private string _lastVideoKey = "";

    public bool IsActionInProgress => Volatile.Read(ref _actionInProgress) == 1;
    public long LastActionMs => Interlocked.Read(ref _lastActionMs);
    public int ConsecutiveSkipsOnSameVideo => Volatile.Read(ref _consecutiveSkipsOnSameVideo);

    public ContextExitController(
        Func<AppConfig> getConfig,
        OverlayManager overlay,
        Action<string, string>? onAuditLog = null)
    {
        _getConfig = getConfig;
        _overlay = overlay;
        _onAuditLog = onAuditLog;
    }

    public void ResetVideoSkipStreak()
    {
        Volatile.Write(ref _consecutiveSkipsOnSameVideo, 0);
        _lastVideoKey = "";
    }

    public void TriggerIntimateAction(
        IntimateSignals signals,
        string? knownUrl = null,
        Action? onResetAfterAction = null)
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (now - LastActionMs < 1400) return;
        if (Interlocked.CompareExchange(ref _actionInProgress, 1, 0) != 0) return;

        var (hwnd, procName, title, isFullscreen) = CaptureForegroundWindowInfo();
        string videoKey = $"{procName}:{title}";
        if (!string.Equals(videoKey, _lastVideoKey, StringComparison.Ordinal))
        {
            _lastVideoKey = videoKey;
            Volatile.Write(ref _consecutiveSkipsOnSameVideo, 0);
        }

        var cfg = _getConfig();
        var mediaContext = IntimateExitRules.ClassifyDesktopContext(procName, title, knownUrl, isFullscreen);
        var action = IntimateExitRules.ChooseAction(mediaContext, cfg.IntimateActionMode, ConsecutiveSkipsOnSameVideo);

        string actionDetail = action switch
        {
            ExitActionType.NextReelSwipe => "Blurring first & scrolling to next reel...",
            ExitActionType.SkipSceneForward => "Blurring first & skipping scene forward...",
            _ => "Blurring first & closing / pausing media..."
        };

        var vs = SystemInformation.VirtualScreen;
        _overlay.ShowIntimateShield(signals.Reason, actionDetail);
        _overlay.UpdateBlurRegions(
            [new BoundingBox(vs.Left, vs.Top, vs.Right, vs.Bottom)],
            []);
        _onAuditLog?.Invoke(signals.Reason, $"{action} in {procName}");

        Task.Run(async () =>
        {
            try
            {
                await Task.Delay(240);
                if (hwnd != IntPtr.Zero) NativeMethods.SetForegroundWindow(hwnd);
                await Task.Delay(60);

                switch (action)
                {
                    case ExitActionType.NextReelSwipe:
                        Interlocked.Increment(ref _consecutiveSkipsOnSameVideo);
                        NativeMethods.SendKey(NativeMethods.VK_DOWN, extended: true);
                        await Task.Delay(780);
                        break;

                    case ExitActionType.SkipSceneForward:
                        Interlocked.Increment(ref _consecutiveSkipsOnSameVideo);
                        DispatchForwardSeek(procName, title);
                        await Task.Delay(950);
                        break;

                    case ExitActionType.PauseAndBack:
                        Volatile.Write(ref _consecutiveSkipsOnSameVideo, 0);
                        ExecutePauseAndExit(procName, isFullscreen);
                        _overlay.ShowBlockBanner("Exited Intimate Scene", signals.Reason);
                        await Task.Delay(700);
                        break;
                }
            }
            catch { }
            finally
            {
                _overlay.ClearBlurRegions();
                _overlay.HideIntimateShield();
                onResetAfterAction?.Invoke();
                await Task.Delay(200);
                Interlocked.Exchange(ref _lastActionMs, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                Volatile.Write(ref _actionInProgress, 0);
            }
        });
    }

    public bool TryTriggerTimestampSkip(
        PlaybackSnapshot snap,
        SkipLookupState.Found lookup,
        MatchedSkipWindow match,
        IntPtr hwnd,
        string procName,
        string windowTitle,
        Action? onResetAfterAction = null)
    {
        if (Interlocked.CompareExchange(ref _actionInProgress, 1, 0) != 0) return false;

        string startStr = CommunitySkipRules.FormatMs(match.Segment.StartMs);
        string endStr = CommunitySkipRules.FormatMs(match.Segment.EndMs);
        string reason = $"{lookup.SourceLabel} Skip ({startStr} -> {endStr})";

        var vs = SystemInformation.VirtualScreen;
        _overlay.ShowIntimateShield(reason, $"Jumping past {lookup.DisplayTitle} scene...");
        _overlay.UpdateBlurRegions(
            [new BoundingBox(vs.Left, vs.Top, vs.Right, vs.Bottom)],
            []);
        _onAuditLog?.Invoke(reason, $"{lookup.DisplayTitle} in {procName}");

        Task.Run(async () =>
        {
            try
            {
                await Task.Delay(160);
                bool sought = await WindowsMediaSessionBridge.SeekActivePlaybackAsync(procName, match.TargetEndMs);
                await Task.Delay(550);

                var after = await WindowsMediaSessionBridge.GetActivePlaybackSnapshotAsync(procName, windowTitle);
                bool moved = sought && after != null && (
                    after.PositionMs >= match.Segment.StartMs + 2000L ||
                    Math.Abs(after.PositionMs - match.TargetEndMs) <= 4500L);

                if (!moved && IntimateExitRules.DetectDrmTarget(procName, windowTitle, null) == null)
                {
                    if (hwnd != IntPtr.Zero) NativeMethods.SetForegroundWindow(hwnd);
                    await Task.Delay(60);
                    DispatchForwardSeek(procName, windowTitle);
                    await Task.Delay(950);
                }
            }
            catch { }
            finally
            {
                _overlay.ClearBlurRegions();
                _overlay.HideIntimateShield();
                onResetAfterAction?.Invoke();
                await Task.Delay(200);
                Interlocked.Exchange(ref _lastActionMs, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                Volatile.Write(ref _actionInProgress, 0);
            }
        });

        return true;
    }

    private static void DispatchForwardSeek(string procName, string title)
    {
        NativeMethods.SendKey(NativeMethods.VK_RIGHT, extended: true);
        if (ContentGuardLists.DesktopBrowserProcesses.Contains(procName) &&
            title.Contains("youtube", StringComparison.OrdinalIgnoreCase))
        {
            Thread.Sleep(60);
            NativeMethods.SendKey(NativeMethods.VK_L);
        }
    }

    private static void ExecutePauseAndExit(string procName, bool isFullscreen)
    {
        try
        {
            NativeMethods.SendKey(NativeMethods.VK_MEDIA_PLAY_PAUSE, extended: true);
            if (ContentGuardLists.DesktopBrowserProcesses.Contains(procName))
            {
                NativeMethods.SendKeyCombo(NativeMethods.VK_CONTROL, NativeMethods.VK_W);
            }
            else if (isFullscreen)
            {
                NativeMethods.SendKey(NativeMethods.VK_ESCAPE);
                Thread.Sleep(80);
                NativeMethods.SendKeyCombo(NativeMethods.VK_MENU, NativeMethods.VK_LEFT, extendedKey: true);
            }
            else
            {
                NativeMethods.SendKeyCombo(NativeMethods.VK_MENU, NativeMethods.VK_LEFT, extendedKey: true);
            }
        }
        catch { }
    }

    public static (IntPtr Hwnd, string ProcessName, string Title, bool IsFullscreen) CaptureForegroundWindowInfo()
    {
        IntPtr hwnd = NativeMethods.GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return (IntPtr.Zero, "", "", false);

        var sb = new StringBuilder(512);
        NativeMethods.GetWindowText(hwnd, sb, sb.Capacity);
        string title = sb.ToString();

        string procName = "";
        try
        {
            NativeMethods.GetWindowThreadProcessId(hwnd, out uint pid);
            procName = Process.GetProcessById((int)pid).ProcessName.ToLowerInvariant();
        }
        catch { }

        bool isFullscreen = false;
        try
        {
            if (NativeMethods.GetWindowRect(hwnd, out var rect))
            {
                var scr = Screen.FromHandle(hwnd).Bounds;
                int rw = rect.Right - rect.Left;
                int rh = rect.Bottom - rect.Top;
                isFullscreen = rw >= scr.Width - 12 && rh >= scr.Height - 12;
            }
        }
        catch { }

        return (hwnd, procName, title, isFullscreen);
    }

    public static string TryReadBrowserAddressBar(IntPtr hwnd)
    {
        try
        {
            var root = AutomationElement.FromHandle(hwnd);
            var cond = new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit);
            var edit = root.FindFirst(TreeScope.Descendants, cond);
            if (edit != null && edit.TryGetCurrentPattern(ValuePattern.Pattern, out object patObj) &&
                patObj is ValuePattern valPat)
            {
                return valPat.Current.Value ?? "";
            }
        }
        catch { }
        return "";
    }

    public static string TryReadFocusedInputText()
    {
        try
        {
            var focused = AutomationElement.FocusedElement;
            if (focused == null) return "";
            if (focused.TryGetCurrentPattern(ValuePattern.Pattern, out object patObj) &&
                patObj is ValuePattern valPat)
            {
                return valPat.Current.Value ?? "";
            }
        }
        catch { }
        return "";
    }

    public static string TryReadCaptionCandidates(IntPtr hwnd)
    {
        try
        {
            var root = AutomationElement.FromHandle(hwnd);
            var cond = new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Text);
            var texts = root.FindAll(TreeScope.Descendants, cond);
            var sb = new StringBuilder();
            int limit = Math.Min(texts.Count, 35);
            for (int i = 0; i < limit; i++)
            {
                string name = texts[i].Current.Name ?? "";
                if (name.Length is >= 4 and <= 180 && (name.Contains('[') || name.Contains('(')))
                {
                    sb.Append(name).Append(' ');
                }
            }
            return sb.ToString();
        }
        catch { }
        return "";
    }
}
