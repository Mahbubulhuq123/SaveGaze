using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

public sealed class ProtectionHealthSnapshot
{
    public bool DetectorReady { get; init; }
    public bool OverlayActive { get; init; }
    public bool AudioActive { get; init; }
    public bool ClipActive { get; init; }
    public string AudioStatus { get; init; } = "Idle";
    public string? ActiveSkipMatchInfo { get; init; }
    public long LastFrameAtMs { get; init; }
    public string LastError { get; init; } = "";
    public int ActiveBlurBoxCount { get; init; }
}

/// <summary>
/// Continuous low-CPU system-wide screen scanner:
/// - Skips scanning when workstation is locked
/// - Hybrid Community/Custom Timestamp Skip via Windows GSMSC + CleanStream
/// - Concurrent NudeNet + PordaV4x3 + SwiftFormer + async MobileCLIP2-S0 + WASAPI YAMNet audio
/// </summary>
public sealed class ScreenScannerService : IDisposable
{
    private readonly Func<AppConfig> _getConfig;
    private readonly OverlayManager _overlay;
    private readonly NudeNetOnnxDetector _nudeDetector;
    private readonly GenderPersonOnnxDetector _genderDetector;
    private readonly IntimateSceneOnnxDetector _sceneDetector;
    private readonly MobileClipIntimateOnnxDetector? _clipDetector;
    private readonly WasapiAudioMonitor _audioMonitor;
    private readonly ContextExitController _exitController;
    private readonly CommunitySkipRepository _skipRepo = new();

    private CancellationTokenSource? _cts;
    private Task? _loopTask;

    private int[]? _lastSignature;
    private long _lastFullInferenceMs;
    private int _changedFrameCounter;
    private bool _lastScreenIsStatic;
    private GenderFilter? _lastGenderFilter;
    private IReadOnlyList<BoundingBox> _activeBlurBoxes = [];
    private int _consecutiveIntimateHits;
    private int _consecutiveAudioHits;
    private int _cleanFramesSinceSkip;
    private long _lastAudioScoredMs;
    private AudioIntimateResult _lastAudioResult = new(0f, "", 0f, false);
    private string _lastForegroundProc = "";

    private SceneSafetyScores? _lastSceneSafety;
    private ClipIntimateScore? _lastClipScore;
    private long _lastClipScoredMs;
    private long _lastEvaluatedClipMs;
    private long _lastClipLaunchMs;
    private volatile bool _staticClipEvaluated;
    private int _clipInferenceInFlight;
    private int _clipGeneration;

    private long _lastCommunityJumpMs;
    private string _lastSkippedSegmentKey = "";
    private string? _lastPublishedSkipInfo;
    private volatile bool _hasTimestampMatchActive;

    private sealed class TrackedBox
    {
        public BoundingBox Box { get; set; }
        public long LastSeenMs { get; set; }
    }
    private readonly List<TrackedBox> _trackedBoxes = [];

    public ProtectionHealthSnapshot Health { get; private set; } = new();
    public event Action<ProtectionHealthSnapshot>? HealthUpdated;

    public ScreenScannerService(
        Func<AppConfig> getConfig,
        OverlayManager overlay,
        NudeNetOnnxDetector nudeDetector,
        GenderPersonOnnxDetector genderDetector,
        IntimateSceneOnnxDetector sceneDetector,
        WasapiAudioMonitor audioMonitor,
        ContextExitController exitController,
        MobileClipIntimateOnnxDetector? clipDetector = null)
    {
        _getConfig = getConfig;
        _overlay = overlay;
        _nudeDetector = nudeDetector;
        _genderDetector = genderDetector;
        _sceneDetector = sceneDetector;
        _clipDetector = clipDetector;
        _audioMonitor = audioMonitor;
        _exitController = exitController;

        _skipRepo.UpdateCustomEntries(getConfig().CustomSkipEntries);
        _audioMonitor.ResultScored += OnAudioResult;
    }

    public void Start()
    {
        if (_loopTask != null) return;
        _cts = new CancellationTokenSource();
        var cfg = _getConfig();
        if (cfg.ProtectionEnabled && cfg.IntimateGuardEnabled && (cfg.IntimateAudioEnabled || cfg.AutoMovieAudioEnabled))
        {
            _audioMonitor.Start();
        }
        _loopTask = Task.Run(() => ScanLoopAsync(_cts.Token));
    }

    public void SyncConfig()
    {
        var cfg = _getConfig();
        _overlay.SetStyleAndOpacity(cfg.BlurStyle, cfg.BlurOpacity);
        _skipRepo.UpdateCustomEntries(cfg.CustomSkipEntries);

        if (!cfg.CommunitySkipEnabled)
        {
            PublishSkipInfo(null, activeMatch: false);
        }

        if (!cfg.ProtectionEnabled)
        {
            _trackedBoxes.Clear();
            _activeBlurBoxes = [];
            _lastSignature = null;
            _overlay.ClearBlurRegions();
            _audioMonitor.Stop();
            PublishSkipInfo(null, activeMatch: false);
            return;
        }

        if (cfg.IntimateGuardEnabled && (cfg.IntimateAudioEnabled || cfg.AutoMovieAudioEnabled))
        {
            if (!_audioMonitor.IsCapturing) _audioMonitor.Start();
        }
        else
        {
            _audioMonitor.Stop();
        }
    }

    private void OnAudioResult(AudioIntimateResult result)
    {
        _lastAudioResult = result;
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _lastAudioScoredMs = now;

        var cfg = _getConfig();
        if (!cfg.ProtectionEnabled || !cfg.IntimateGuardEnabled || (!cfg.IntimateAudioEnabled && !cfg.AutoMovieAudioEnabled))
            return;
        if (_exitController.IsActionInProgress || NativeMethods.IsWorkstationLocked())
            return;
        if (IsSelfOrIgnoredProcess(_lastForegroundProc))
            return;

        // Visual-first audio policy: audio cannot trigger on a clearly safe scene
        var freshClip = Volatile.Read(ref _lastClipScoredMs) is long cs && now - cs < 2500 ? Volatile.Read(ref _lastClipScore) : null;
        var freshSafety = now - _lastFullInferenceMs < 2500 ? _lastSceneSafety : null;
        bool hasFreshVisual = freshClip != null || freshSafety != null;
        bool visualUnclear = IntimateSceneRules.IsVisualUnclearForAudio(
            sceneSafety: freshSafety,
            hasSemiNude: _activeBlurBoxes.Count > 0,
            kissOrBedCue: false,
            clipKissProb: freshClip?.IntimateProb ?? 0f,
            clipMargin: freshClip?.Margin ?? 0f,
            hasFreshVisual: hasFreshVisual);
        if (!visualUnclear)
        {
            _consecutiveAudioHits = 0;
            return;
        }

        bool postSkipWindow = _exitController.ConsecutiveSkipsOnSameVideo is >= 1 and <= 11 &&
            now - _exitController.LastActionMs <= 5500;
        int requiredAudioHits = postSkipWindow ? 2 : 3;

        if (result.Score >= 0.55f && !string.IsNullOrWhiteSpace(result.TopLabel))
        {
            _consecutiveAudioHits++;
            if (_consecutiveAudioHits >= requiredAudioHits)
            {
                _consecutiveAudioHits = 0;
                _exitController.TriggerIntimateAction(
                    new IntimateSignals(
                        Triggered: true,
                        Instant: true,
                        Reason: $"Intimate Audio ({result.TopLabel})",
                        Confidence: result.Score,
                        IsKissOrBedCue: false),
                    onResetAfterAction: ResetPostActionWindows);
            }
        }
        else
        {
            _consecutiveAudioHits = 0;
        }
    }

    private void ResetPostActionWindows()
    {
        _audioMonitor.ResetWindow();
        _trackedBoxes.Clear();
        _activeBlurBoxes = [];
        _lastSignature = null;
        _staticClipEvaluated = false;
        Interlocked.Increment(ref _clipGeneration);
        Volatile.Write(ref _lastClipScore, null);
        Volatile.Write(ref _lastClipScoredMs, 0L);
        _lastEvaluatedClipMs = 0L;
        _lastClipLaunchMs = 0L;
        _lastSceneSafety = null;
        _lastAudioScoredMs = 0L;
        _consecutiveIntimateHits = 0;
        _consecutiveAudioHits = 0;
        _cleanFramesSinceSkip = 0;
    }

    private async Task ScanLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var cfg = _getConfig();
                if (NativeMethods.IsWorkstationLocked())
                {
                    if (_activeBlurBoxes.Count > 0)
                    {
                        _trackedBoxes.Clear();
                        _activeBlurBoxes = [];
                        _overlay.ClearBlurRegions();
                    }
                    PublishHealth(0);
                    await Task.Delay(1500, ct);
                    continue;
                }

                if (!cfg.ProtectionEnabled)
                {
                    if (_activeBlurBoxes.Count > 0)
                    {
                        _trackedBoxes.Clear();
                        _activeBlurBoxes = [];
                        _overlay.ClearBlurRegions();
                    }
                    PublishHealth(0);
                    await Task.Delay(900, ct);
                    continue;
                }

                var (hwnd, fgProc, fgTitle, isFullscreen) = ContextExitController.CaptureForegroundWindowInfo();
                if (!string.IsNullOrEmpty(fgProc) && fgProc != _lastForegroundProc)
                {
                    if (!string.IsNullOrEmpty(_lastForegroundProc))
                    {
                        ResetPostActionWindows();
                        _exitController.ResetVideoSkipStreak();
                        _overlay.ClearBlurRegions();
                    }
                    _lastForegroundProc = fgProc;
                }

                if (IsSelfOrIgnoredProcess(fgProc) || _exitController.IsActionInProgress)
                {
                    if (_activeBlurBoxes.Count > 0 && !_exitController.IsActionInProgress)
                    {
                        _trackedBoxes.Clear();
                        _activeBlurBoxes = [];
                        _overlay.ClearBlurRegions();
                    }
                    PublishHealth(_activeBlurBoxes.Count);
                    await Task.Delay(700, ct);
                    continue;
                }

                bool jumpedByTimestamp = await TryHandleCommunityTimestampSkipAsync(cfg, hwnd, fgProc, fgTitle, isFullscreen);
                if (!jumpedByTimestamp)
                {
                    await PerformScanPassAsync(cfg, hwnd, fgProc, fgTitle, isFullscreen);
                }

                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                bool verifyingPostSkip = _exitController.ConsecutiveSkipsOnSameVideo is >= 1 and <= 11 &&
                    nowMs - _exitController.LastActionMs <= 5500;
                int delayMs = verifyingPostSkip
                    ? 140
                    : _hasTimestampMatchActive
                        ? Math.Clamp(cfg.ScanIntervalMs, 220, 300)
                        : _lastScreenIsStatic
                            ? 850
                            : _activeBlurBoxes.Count > 0
                                ? Math.Clamp(cfg.ScanIntervalMs, 220, 320)
                                : Math.Clamp(cfg.ScanIntervalMs, 260, 400);
                await Task.Delay(delayMs, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                PublishHealth(_activeBlurBoxes.Count, ex.Message);
                await Task.Delay(500, ct);
            }
        }
    }

    private async Task<bool> TryHandleCommunityTimestampSkipAsync(
        AppConfig cfg,
        IntPtr hwnd,
        string procName,
        string windowTitle,
        bool isFullscreen)
    {
        if (!cfg.ProtectionEnabled || !cfg.IntimateGuardEnabled || !cfg.CommunitySkipEnabled)
        {
            PublishSkipInfo(null, activeMatch: false);
            return false;
        }
        if (string.IsNullOrWhiteSpace(procName) || IsSelfOrIgnoredProcess(procName))
        {
            PublishSkipInfo(null, activeMatch: false);
            return false;
        }

        var mediaCtx = IntimateExitRules.ClassifyDesktopContext(procName, windowTitle, null, isFullscreen);
        if (mediaCtx != MediaAppContext.MovieSeriesPlayer &&
            IntimateExitRules.DetectDrmTarget(procName, windowTitle, null) == null)
        {
            PublishSkipInfo(null, activeMatch: false);
            return false;
        }

        var snap = await WindowsMediaSessionBridge.GetActivePlaybackSnapshotAsync(procName, windowTitle);
        if (snap == null || string.IsNullOrWhiteSpace(snap.Title))
        {
            PublishSkipInfo(null, activeMatch: false);
            return false;
        }

        var lookup = _skipRepo.ResolveNonBlocking(snap.Title, snap.Subtitle);
        switch (lookup)
        {
            case SkipLookupState.Found found:
                PublishSkipInfo($"{found.SourceLabel}: {found.DisplayTitle} ({found.Segments.Count} skips)", activeMatch: true);
                if (snap.PositionMs < 0L || _exitController.IsActionInProgress) return false;

                var match = CommunitySkipRules.FindActiveSkipWindow(snap.PositionMs, found.Segments);
                if (match == null) return false;

                string segKey = $"{found.CacheKey}:{match.Segment.StartMs}:{match.Segment.EndMs}";
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                bool sameSegCooldown = segKey == _lastSkippedSegmentKey && now - _lastCommunityJumpMs < 8000;
                if (sameSegCooldown || now - _lastCommunityJumpMs < 2500 || now - _exitController.LastActionMs < 1400)
                {
                    return false;
                }

                if (_exitController.TryTriggerTimestampSkip(snap, found, match, hwnd, procName, windowTitle, ResetPostActionWindows))
                {
                    _lastSkippedSegmentKey = segKey;
                    _lastCommunityJumpMs = now;
                    return true;
                }
                return false;

            case SkipLookupState.Loading loading:
                PublishSkipInfo($"Checking timestamps: {loading.NormalizedTitle}...", activeMatch: false);
                return false;

            case SkipLookupState.NotFound nf:
                PublishSkipInfo($"AI Fallback: {nf.NormalizedTitle}", activeMatch: false);
                return false;

            default:
                PublishSkipInfo(null, activeMatch: false);
                return false;
        }
    }

    private void PublishSkipInfo(string? info, bool activeMatch)
    {
        _hasTimestampMatchActive = activeMatch;
        _lastPublishedSkipInfo = info;
    }

    private async Task PerformScanPassAsync(AppConfig cfg, IntPtr hwnd, string fgProc, string fgTitle, bool isFullscreen)
    {
        var vs = SystemInformation.VirtualScreen;
        if (vs.Width <= 0 || vs.Height <= 0) return;

        using var bmp = new Bitmap(vs.Width, vs.Height, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.CopyFromScreen(vs.Left, vs.Top, 0, 0, vs.Size, CopyPixelOperation.SourceCopy);
        }

        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var sampler = ModelPathLocator.CaptureSampler(bmp);

        // Screen-diff hash excluding active blur boxes (converted to local 0..W, 0..H coordinates)
        var localExcluded = _activeBlurBoxes
            .Select(b => new BoundingBox(b.X1 - vs.Left, b.Y1 - vs.Top, b.X2 - vs.Left, b.Y2 - vs.Top))
            .Concat(_trackedBoxes.Select(t => new BoundingBox(t.Box.X1 - vs.Left, t.Box.Y1 - vs.Top, t.Box.X2 - vs.Left, t.Box.Y2 - vs.Top)))
            .ToList();

        int[] sig = ComputeScreenSignature(sampler, localExcluded);
        var (isStatic, isSceneCut) = AnalyzeScreenChange(_lastSignature, sig);
        var activeContext = IntimateExitRules.ClassifyDesktopContext(fgProc, fgTitle, null, isFullscreen);
        bool isActiveVideoPlayback = activeContext == MediaAppContext.MovieSeriesPlayer;
        _lastScreenIsStatic = isStatic;
        if (!isStatic || isSceneCut)
        {
            _staticClipEvaluated = false;
        }

        if (isStatic && !isActiveVideoPlayback && (now - _lastFullInferenceMs < 2500))
        {
            PublishHealth(_activeBlurBoxes.Count);
            return;
        }
        _lastFullInferenceMs = now;

        _nudeDetector.ConfidenceThreshold = cfg.Sensitivity;
        _genderDetector.ConfidenceThreshold = Math.Clamp(cfg.Sensitivity + 0.02f, 0.22f, 0.45f);

        bool runTiles = isSceneCut || (_changedFrameCounter++ % 3 == 0);
        bool recentAudioActive = (now - _lastAudioScoredMs < 2000) && _lastAudioResult.Score >= 0.25f;
        bool runSceneClassifier = cfg.IntimateGuardEnabled &&
            (!isStatic || isActiveVideoPlayback || _activeBlurBoxes.Count > 0 || recentAudioActive);

        var nudeTask = Task.Run(() => _nudeDetector.Detect(bmp, sampler, includeQuadrants: runTiles));
        var genderTask = Task.Run(() => _genderDetector.Detect(bmp, includeTiles: runTiles));
        var sceneTask = Task.Run(() => runSceneClassifier ? _sceneDetector.Classify(bmp) : null);
        await Task.WhenAll(nudeTask, genderTask, sceneTask);

        var detections = nudeTask.Result;
        var personBoxes = genderTask.Result;
        var sceneSafety = sceneTask.Result;
        if (sceneSafety != null) _lastSceneSafety = sceneSafety;

        if (_lastGenderFilter != null && _lastGenderFilter != cfg.GenderFilter)
        {
            _trackedBoxes.Clear();
            _activeBlurBoxes = [];
            _overlay.ClearBlurRegions();
        }
        _lastGenderFilter = cfg.GenderFilter;

        var filtered = NudeNetFilterEngine.FilterDetectionsPure(
            detections, personBoxes, cfg.GenderFilter, cfg.BlurFaces, sampler);

        // Offset local bitmap boxes back into virtual-screen coordinates
        var screenCandidateBoxes = filtered.MergedBlurBoxes
            .Select(b => new BoundingBox(b.X1 + vs.Left, b.Y1 + vs.Top, b.X2 + vs.Left, b.Y2 + vs.Top))
            .ToList();
        var screenProtectedFaces = filtered.ProtectedFaces
            .Select(b => new BoundingBox(b.X1 + vs.Left, b.Y1 + vs.Top, b.X2 + vs.Left, b.Y2 + vs.Top))
            .ToList();

        var oppositeFaces = cfg.GenderFilter switch
        {
            GenderFilter.FemaleOnly => filtered.Faces.Where(f => f.Label.IsFemale() == false).ToList(),
            GenderFilter.MaleOnly => filtered.Faces.Where(f => f.Label.IsFemale() == true).ToList(),
            _ => []
        };
        var matchingFaces = cfg.GenderFilter switch
        {
            GenderFilter.FemaleOnly => filtered.Faces.Where(f => f.Label.IsFemale() == true).ToList(),
            GenderFilter.MaleOnly => filtered.Faces.Where(f => f.Label.IsFemale() == false).ToList(),
            _ => filtered.Faces.ToList()
        };

        var screenOppositeFaces = oppositeFaces
            .Select(f => f with { Box = new BoundingBox(f.Box.X1 + vs.Left, f.Box.Y1 + vs.Top, f.Box.X2 + vs.Left, f.Box.Y2 + vs.Top) })
            .ToList();
        var screenMatchingFaces = matchingFaces
            .Select(f => f with { Box = new BoundingBox(f.Box.X1 + vs.Left, f.Box.Y1 + vs.Top, f.Box.X2 + vs.Left, f.Box.Y2 + vs.Top) })
            .ToList();

        var boxesToApply = UpdateTrackedBoxes(
            screenCandidateBoxes, screenMatchingFaces, screenOppositeFaces,
            vs, now, isStatic, isSceneCut, cfg.BlurFaces);

        if (boxesToApply.Count > 0)
        {
            _activeBlurBoxes = boxesToApply;
            if (sig.Count(v => v != -1) >= 8) _lastSignature = sig;
            _overlay.UpdateBlurRegions(boxesToApply, screenProtectedFaces);
        }
        else
        {
            _activeBlurBoxes = [];
            _lastSignature = sig;
            _overlay.ClearBlurRegions();
        }

        if (cfg.IntimateGuardEnabled && !IsSelfOrIgnoredProcess(fgProc) && !_exitController.IsActionInProgress)
        {
            bool isSocialFeedBrowse = IntimateExitRules.IsSocialFeedBrowse(fgProc, fgTitle, null, isFullscreen);
            if (_clipDetector != null &&
                (!isStatic || (isActiveVideoPlayback && !_staticClipEvaluated)) &&
                !isSocialFeedBrowse &&
                (activeContext != MediaAppContext.BrowserOrGeneral || isFullscreen) &&
                now - _lastClipLaunchMs >= 400 &&
                Interlocked.CompareExchange(ref _clipInferenceInFlight, 1, 0) == 0)
            {
                if (isStatic) _staticClipEvaluated = true;
                _lastClipLaunchMs = now;
                int launchGen = Volatile.Read(ref _clipGeneration);
                var focusBoxes = filtered.Faces.Count > 0
                    ? filtered.Faces.Select(f => f.Box).ToList()
                    : personBoxes.Select(p => p.Box).ToList();
                var clipCrop = ModelPathLocator.CreateCenterSquareClipCrop(bmp, focusBoxes);
                if (clipCrop != null)
                {
                    _ = Task.Run(() =>
                    {
                        try
                        {
                            var score = _clipDetector.Classify(clipCrop);
                            if (score != null && Volatile.Read(ref _clipGeneration) == launchGen)
                            {
                                Volatile.Write(ref _lastClipScore, score);
                                Volatile.Write(ref _lastClipScoredMs, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                            }
                        }
                        finally
                        {
                            clipCrop.Dispose();
                            Volatile.Write(ref _clipInferenceInFlight, 0);
                        }
                    });
                }
                else
                {
                    Volatile.Write(ref _clipInferenceInFlight, 0);
                }
            }

            bool audioActive = cfg.IntimateAudioEnabled || (cfg.AutoMovieAudioEnabled && _audioMonitor.IsCapturing);
            float audioScore = (now - _lastAudioScoredMs < 2000 && audioActive) ? _lastAudioResult.Score : 0f;
            string audioLabel = (now - _lastAudioScoredMs < 2000 && audioActive) ? _lastAudioResult.TopLabel : "";
            var evalSafety = sceneSafety ?? ((!isStatic && !isActiveVideoPlayback) ? null : _lastSceneSafety);
            long clipScoredAt = Volatile.Read(ref _lastClipScoredMs);
            var freshClip = !isSocialFeedBrowse && now - clipScoredAt < 2200 ? Volatile.Read(ref _lastClipScore) : null;
            bool hasFreshVisual = evalSafety != null || freshClip != null;
            bool continuingIntimateScene = isActiveVideoPlayback &&
                _exitController.ConsecutiveSkipsOnSameVideo is >= 1 and <= 11 &&
                (now - _exitController.LastActionMs <= 5500);

            var rawSignals = IntimateSceneRules.Evaluate(
                detections: detections,
                personBoxes: personBoxes,
                sceneSafety: evalSafety,
                audioScore: audioScore,
                audioLabel: audioLabel,
                screenW: bmp.Width,
                screenH: bmp.Height,
                sensitivity: cfg.Sensitivity,
                clipKissProb: freshClip?.IntimateProb ?? 0f,
                clipMargin: freshClip?.Margin ?? 0f,
                allowDarkClipFallback: isActiveVideoPlayback,
                continuingIntimateScene: continuingIntimateScene,
                hasFreshVisualOverride: hasFreshVisual);

            var signals = isSocialFeedBrowse && rawSignals.Triggered &&
                !rawSignals.Reason.Contains("Explicit", StringComparison.OrdinalIgnoreCase)
                ? rawSignals with { Triggered = false, Instant = false }
                : rawSignals;

            if (signals.Triggered)
            {
                bool isClipTrigger = signals.Reason.Contains("CLIP", StringComparison.Ordinal);
                bool isDuplicateClipSample = !signals.Instant && isClipTrigger && freshClip != null && clipScoredAt == _lastEvaluatedClipMs;
                if (!isDuplicateClipSample)
                {
                    if (isClipTrigger && freshClip != null) _lastEvaluatedClipMs = clipScoredAt;
                    _consecutiveIntimateHits++;
                    int needed = signals.Instant ? 1 : 2;
                    if (_consecutiveIntimateHits >= needed && now - _exitController.LastActionMs >= 1400)
                    {
                        _consecutiveIntimateHits = 0;
                        _cleanFramesSinceSkip = 0;
                        _exitController.TriggerIntimateAction(signals, onResetAfterAction: ResetPostActionWindows);
                    }
                }
            }
            else
            {
                _consecutiveIntimateHits = Math.Max(0, _consecutiveIntimateHits - 1);
                if (_exitController.ConsecutiveSkipsOnSameVideo > 0 &&
                    (freshClip != null || (!isActiveVideoPlayback && sceneSafety != null)))
                {
                    _cleanFramesSinceSkip++;
                }
                if ((_cleanFramesSinceSkip >= 2 && now - _exitController.LastActionMs >= 1800) ||
                    now - _exitController.LastActionMs >= 6000)
                {
                    _exitController.ResetVideoSkipStreak();
                    _cleanFramesSinceSkip = 0;
                }
            }
        }

        PublishHealth(_activeBlurBoxes.Count);
    }

    private List<BoundingBox> UpdateTrackedBoxes(
        List<BoundingBox> candidates,
        List<Detection> matchingFaces,
        List<Detection> oppositeFaces,
        Rectangle vs,
        long now,
        bool isStaticScreen,
        bool isSceneCut,
        bool blurFaces)
    {
        if (oppositeFaces.Count > 0)
        {
            _trackedBoxes.RemoveAll(tracked => oppositeFaces.Any(opp =>
            {
                bool touchesFace = tracked.Box.Iou(opp.Box) > 0.08f;
                float bodyTop = opp.Box.Y1;
                float bodyBottom = opp.Box.Y2 + opp.Box.Height * 5.0f;
                float colLeft = opp.Box.CenterX - opp.Box.Width;
                float colRight = opp.Box.CenterX + opp.Box.Width;
                bool underOpp = tracked.Box.CenterY >= bodyTop && tracked.Box.CenterY <= bodyBottom &&
                    tracked.Box.CenterX >= colLeft && tracked.Box.CenterX <= colRight;
                bool closerSame = matchingFaces.Any(mf =>
                    MathF.Sqrt(MathF.Pow(mf.Box.CenterX - tracked.Box.CenterX, 2f) + MathF.Pow(Math.Max(0f, mf.Box.Y2 - tracked.Box.Y1), 2f)) <
                    MathF.Sqrt(MathF.Pow(opp.Box.CenterX - tracked.Box.CenterX, 2f) + MathF.Pow(Math.Max(0f, opp.Box.Y2 - tracked.Box.Y1), 2f)));
                return touchesFace || (!closerSame && underOpp);
            }));
        }

        if (candidates.Count == 0 && matchingFaces.Count == 0)
        {
            if (isSceneCut)
            {
                _trackedBoxes.Clear();
                return [];
            }
            if (isStaticScreen && oppositeFaces.Count == 0 && _trackedBoxes.Count > 0)
            {
                foreach (var t in _trackedBoxes) t.LastSeenMs = now;
                return _trackedBoxes.Select(t => t.Box).ToList();
            }
            long hold = isStaticScreen ? 2200L : 850L;
            _trackedBoxes.RemoveAll(t => now - t.LastSeenMs > hold);
            return _trackedBoxes.Select(t => t.Box).ToList();
        }

        if (isSceneCut && candidates.Count == 0)
        {
            _trackedBoxes.Clear();
            return [];
        }

        var matched = new HashSet<int>();
        foreach (var cand in candidates)
        {
            int bestIdx = -1;
            float bestIou = 0f;
            for (int i = 0; i < _trackedBoxes.Count; i++)
            {
                if (matched.Contains(i)) continue;
                var t = _trackedBoxes[i];
                float iou = cand.Iou(t.Box);
                if (iou > 0.10f && iou > bestIou)
                {
                    bestIou = iou;
                    bestIdx = i;
                }
                else if (bestIou == 0f)
                {
                    float dx = MathF.Abs(cand.CenterX - t.Box.CenterX);
                    float dy = MathF.Abs(cand.CenterY - t.Box.CenterY);
                    if (dx < Math.Max(cand.Width, t.Box.Width) * 0.55f && dy < Math.Max(cand.Height, t.Box.Height) * 0.55f)
                    {
                        bestIdx = i;
                    }
                }
            }

            var clamped = new BoundingBox(
                Math.Clamp(cand.X1, vs.Left, vs.Right),
                Math.Clamp(cand.Y1, vs.Top, vs.Bottom),
                Math.Clamp(cand.X2, vs.Left, vs.Right),
                Math.Clamp(cand.Y2, vs.Top, vs.Bottom));

            if (bestIdx >= 0)
            {
                matched.Add(bestIdx);
                var t = _trackedBoxes[bestIdx];
                t.LastSeenMs = now;
                float deadband = isStaticScreen ? 12f : 6f;
                bool withinDeadband =
                    MathF.Abs(clamped.X1 - t.Box.X1) <= deadband &&
                    MathF.Abs(clamped.Y1 - t.Box.Y1) <= deadband &&
                    MathF.Abs(clamped.X2 - t.Box.X2) <= deadband &&
                    MathF.Abs(clamped.Y2 - t.Box.Y2) <= deadband;
                bool isOccludedShrink = isStaticScreen && !isSceneCut &&
                    (clamped.Width < t.Box.Width * 0.85f || clamped.Height < t.Box.Height * 0.85f);
                if (!withinDeadband && !isOccludedShrink)
                {
                    float alpha = isStaticScreen ? 0.45f : isSceneCut ? 1.0f : 0.85f;
                    float cx = t.Box.CenterX * (1f - alpha) + clamped.CenterX * alpha;
                    float cy = t.Box.CenterY * (1f - alpha) + clamped.CenterY * alpha;
                    float w = t.Box.Width * (1f - alpha) + clamped.Width * alpha;
                    float h = t.Box.Height * (1f - alpha) + clamped.Height * alpha;
                    t.Box = new BoundingBox(
                        Math.Clamp(cx - w * 0.5f, vs.Left, vs.Right),
                        Math.Clamp(cy - h * 0.5f, vs.Top, vs.Bottom),
                        Math.Clamp(cx + w * 0.5f, vs.Left, vs.Right),
                        Math.Clamp(cy + h * 0.5f, vs.Top, vs.Bottom));
                }
            }
            else
            {
                _trackedBoxes.Add(new TrackedBox { Box = clamped, LastSeenMs = now });
                matched.Add(_trackedBoxes.Count - 1);
            }
        }

        for (int i = 0; i < _trackedBoxes.Count; i++)
        {
            if (!matched.Contains(i) && isStaticScreen && !isSceneCut && oppositeFaces.Count == 0)
            {
                _trackedBoxes[i].LastSeenMs = now;
            }
        }

        long holdTime = isStaticScreen ? 2200L
            : (matchingFaces.Count > 0 || (_trackedBoxes.Count > 0 && blurFaces)) ? 850L
            : 550L;
        _trackedBoxes.RemoveAll(t => now - t.LastSeenMs > holdTime);
        return _trackedBoxes.Select(t => t.Box).ToList();
    }

    private static int[] ComputeScreenSignature(ISkinImageSampler frame, List<BoundingBox> excludeBoxes)
    {
        var sig = new int[196];
        int idx = 0;
        for (int row = 1; row <= 14; row++)
        {
            for (int col = 1; col <= 14; col++)
            {
                int x = Math.Clamp(frame.Width * col / 15, 0, frame.Width - 1);
                int y = Math.Clamp(frame.Height * row / 15, 0, frame.Height - 1);
                bool inside = excludeBoxes.Any(b =>
                    x >= b.X1 - 6f && x <= b.X2 + 6f && y >= b.Y1 - 6f && y <= b.Y2 + 6f);
                if (inside)
                {
                    sig[idx++] = -1;
                }
                else
                {
                    var (r, g, b) = frame.GetRgb(x, y);
                    sig[idx++] = (r << 16) | (g << 8) | b;
                }
            }
        }
        return sig;
    }

    private static (bool IsStatic, bool IsSceneCut) AnalyzeScreenChange(int[]? sig1, int[]? sig2)
    {
        if (sig1 == null || sig2 == null || sig1.Length != sig2.Length) return (false, false);
        int valid = 0;
        int matches = 0;
        for (int i = 0; i < sig1.Length; i++)
        {
            int c1 = sig1[i];
            int c2 = sig2[i];
            if (c1 == -1 || c2 == -1) continue;
            valid++;
            int diff = Math.Abs(((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF)) +
                       Math.Abs(((c1 >> 8) & 0xFF) - ((c2 >> 8) & 0xFF)) +
                       Math.Abs((c1 & 0xFF) - (c2 & 0xFF));
            if (diff < 18) matches++;
        }
        if (valid < 8) return (true, false);
        float rate = (float)matches / valid;
        return (rate >= 0.96f, rate < 0.30f);
    }

    private static bool IsSelfOrIgnoredProcess(string proc)
    {
        if (string.IsNullOrEmpty(proc)) return false;
        return proc.Equals(BrandConfig.AppName, StringComparison.OrdinalIgnoreCase) ||
               proc.Equals("savegaze", StringComparison.OrdinalIgnoreCase) ||
               proc is "lockapp" or "logonui" or "searchhost" or "shellexperiencehost" or "startmenuexperiencehost";
    }

    private void PublishHealth(int activeBoxes, string err = "")
    {
        var snap = new ProtectionHealthSnapshot
        {
            DetectorReady = true,
            OverlayActive = _overlay.IsOverlayActive,
            AudioActive = _audioMonitor.IsCapturing,
            ClipActive = _clipDetector != null,
            AudioStatus = _audioMonitor.StatusMessage,
            ActiveSkipMatchInfo = _lastPublishedSkipInfo,
            LastFrameAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            LastError = err,
            ActiveBlurBoxCount = activeBoxes
        };
        Health = snap;
        HealthUpdated?.Invoke(snap);
    }

    public void Dispose()
    {
        _audioMonitor.ResultScored -= OnAudioResult;
        _cts?.Cancel();
        try { _loopTask?.Wait(1000); } catch { }
        _cts?.Dispose();
    }
}
