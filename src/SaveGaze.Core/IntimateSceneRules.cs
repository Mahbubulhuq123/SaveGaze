using System.Text.RegularExpressions;

namespace SaveGaze.Core;

public static partial class IntimateSceneRules
{
    private static readonly HashSet<NudeLabel> ExplicitNudityLabels =
    [
        NudeLabel.FemaleGenitaliaExposed,
        NudeLabel.MaleGenitaliaExposed,
        NudeLabel.FemaleBreastExposed,
        NudeLabel.ButtocksExposed,
        NudeLabel.AnusExposed
    ];

    private static readonly HashSet<NudeLabel> SemiNudeLabels =
    [
        NudeLabel.FemaleBreastCovered,
        NudeLabel.FemaleGenitaliaCovered,
        NudeLabel.ButtocksCovered,
        NudeLabel.BellyExposed,
        NudeLabel.MaleBreastExposed
    ];

    public static bool HasCloseUpKissingOrEmbrace(
        IReadOnlyList<Detection> faces,
        IReadOnlyList<PersonGenderBox> personBoxes,
        float screenW,
        float screenH)
    {
        if (screenW <= 0f || screenH <= 0f) return false;

        if (faces.Count >= 2)
        {
            for (int i = 0; i < faces.Count; i++)
            {
                for (int j = i + 1; j < faces.Count; j++)
                {
                    var f1 = faces[i];
                    var f2 = faces[j];
                    float minFaceH = Math.Min(f1.Box.Height, f2.Box.Height);
                    if (minFaceH < screenH * 0.045f) continue;

                    float avgW = (f1.Box.Width + f2.Box.Width) * 0.5f;
                    if (avgW <= 1f) continue;

                    float hGap = Math.Max(f1.Box.X1, f2.Box.X1) - Math.Min(f1.Box.X2, f2.Box.X2);
                    float vDiff = MathF.Abs(f1.Box.CenterY - f2.Box.CenterY);
                    float maxH = Math.Max(f1.Box.Height, f2.Box.Height);
                    float dist = MathF.Sqrt(
                        MathF.Pow(f1.Box.CenterX - f2.Box.CenterX, 2f) +
                        MathF.Pow(f1.Box.CenterY - f2.Box.CenterY, 2f));

                    if (hGap <= avgW * 0.08f && vDiff <= maxH * 0.55f && dist <= avgW * 1.15f)
                    {
                        return true;
                    }
                }
            }
        }

        if (personBoxes.Count >= 2)
        {
            for (int i = 0; i < personBoxes.Count; i++)
            {
                for (int j = i + 1; j < personBoxes.Count; j++)
                {
                    var p1 = personBoxes[i].Box;
                    var p2 = personBoxes[j].Box;
                    float iou = p1.Iou(p2);
                    if (iou < 0.25f) continue;

                    float unionW = Math.Max(p1.X2, p2.X2) - Math.Min(p1.X1, p2.X1);
                    float unionH = Math.Max(p1.Y2, p2.Y2) - Math.Min(p1.Y1, p2.Y1);
                    bool horizontalBedPose = unionW >= unionH * 0.85f && Math.Max(p1.Height, p2.Height) >= screenH * 0.16f;
                    bool mixedGender = personBoxes[i].Gender != personBoxes[j].Gender;
                    if (horizontalBedPose && (mixedGender || iou >= 0.35f))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static IntimateSignals Evaluate(
        IReadOnlyList<Detection> detections,
        IReadOnlyList<PersonGenderBox> personBoxes,
        SceneSafetyScores? sceneSafety,
        float audioScore,
        string? audioLabel,
        float screenW,
        float screenH,
        float sensitivity = 0.25f)
    {
        float explicitThresh = Math.Clamp(sensitivity, 0.26f, 0.45f);
        bool hasExplicit = detections.Any(d => ExplicitNudityLabels.Contains(d.Label) && d.Confidence >= explicitThresh);
        bool highExplicit = detections.Any(d => ExplicitNudityLabels.Contains(d.Label) && d.Confidence >= 0.62f);
        bool hasSemiNude = detections.Any(d => SemiNudeLabels.Contains(d.Label) && d.Confidence >= sensitivity);
        var faces = detections.Where(d => d.Label.IsFace()).ToList();

        float nsfwProb = sceneSafety?.Nsfw ?? 0f;
        bool kissOrBedCue = HasCloseUpKissingOrEmbrace(faces, personBoxes, screenW, screenH);

        if (hasExplicit && (nsfwProb >= 0.42f || highExplicit))
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: highExplicit && nsfwProb >= 0.45f,
                Reason: "Explicit Scene Detected",
                Confidence: Math.Max(nsfwProb, 0.78f),
                IsKissOrBedCue: kissOrBedCue);
        }

        if (nsfwProb >= 0.82f)
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: nsfwProb >= 0.93f,
                Reason: "Intimate / NSFW Scene",
                Confidence: nsfwProb,
                IsKissOrBedCue: kissOrBedCue);
        }

        if (kissOrBedCue && (nsfwProb >= 0.34f || (nsfwProb >= 0.18f && (hasSemiNude || audioScore >= 0.30f))))
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: nsfwProb >= 0.65f || (hasSemiNude && audioScore >= 0.35f),
                Reason: "Intimate Kissing / Bed Scene",
                Confidence: Math.Max(nsfwProb, 0.58f),
                IsKissOrBedCue: true);
        }

        bool hasVocalLabel = !string.IsNullOrWhiteSpace(audioLabel);
        if (hasVocalLabel && (audioScore >= 0.55f || (audioScore >= 0.35f && (nsfwProb >= 0.25f || hasSemiNude || kissOrBedCue))))
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: false,
                Reason: $"Intimate Audio ({audioLabel})",
                Confidence: Math.Max(audioScore, nsfwProb),
                IsKissOrBedCue: kissOrBedCue);
        }

        return new IntimateSignals(
            Triggered: false,
            Instant: false,
            Reason: "",
            Confidence: Math.Max(nsfwProb, audioScore),
            IsKissOrBedCue: kissOrBedCue);
    }
}

public static partial class IntimateCaptionRules
{
    [GeneratedRegex(
        @"[\[(]\s*(?:woman|girl|female|man|couple|both)?\s*(?:moaning|moans|groaning|groans|panting|heavy breathing|kissing|making out|sexual\s+\w+|orgasm)\s*[\])]",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex CaptionRegex();

    public static string? DetectIntimateCaption(string screenText)
    {
        if (string.IsNullOrWhiteSpace(screenText)) return null;
        var match = CaptionRegex().Match(screenText);
        return match.Success ? match.Value.Trim() : null;
    }
}

public static class AudioIntimateRules
{
    public const int IdxSpeech = 0;
    public const int IdxWhisper = 12;
    public const int IdxLaughter = 13;
    public const int IdxCrySob = 19;
    public const int IdxWailMoan = 22;
    public const int IdxSigh = 23;
    public const int IdxSinging = 24;
    public const int IdxGroan = 33;
    public const int IdxGrunt = 34;
    public const int IdxBreathing = 36;
    public const int IdxGasp = 39;
    public const int IdxPant = 40;
    public const int IdxMusic = 132;
    public const int IdxVocalMusic = 249;

    public const float SilenceRmsGate = 0.008f;

    public static AudioIntimateResult ScoreFrame(float[] probs, float rms)
    {
        if (probs.Length < 250 || rms < SilenceRmsGate)
        {
            return new AudioIntimateResult(0f, "", rms, false);
        }

        float moan = probs[IdxWailMoan];
        float groan = probs[IdxGroan];
        float pant = probs[IdxPant];
        float gasp = probs[IdxGasp];
        float sigh = probs[IdxSigh];
        float breath = probs[IdxBreathing];
        float grunt = probs[IdxGrunt];
        float whisper = probs[IdxWhisper];

        float weighted = moan * 1.40f +
            groan * 1.25f +
            pant * 1.30f +
            gasp * 0.80f +
            sigh * 0.65f +
            breath * 0.55f +
            grunt * 0.50f +
            whisper * 0.35f;

        float maxPrimary = Math.Max(moan, Math.Max(groan, pant));
        float distractor = new[]
        {
            probs[IdxSpeech],
            probs[IdxMusic],
            probs[IdxSinging],
            probs[IdxVocalMusic],
            probs[IdxLaughter],
            probs[IdxCrySob]
        }.Max();

        string label = (moan >= groan && moan >= pant && moan >= 0.08f) ? "Moan"
            : (groan >= pant && groan >= 0.08f) ? "Groan"
            : (pant >= 0.08f) ? "Pant / Breathing"
            : (gasp >= 0.12f || breath >= 0.14f) ? "Heavy Breathing"
            : "";

        bool cryingHigher = probs[IdxCrySob] > maxPrimary * 1.35f && probs[IdxCrySob] >= 0.22f;
        bool distractorDominant = cryingHigher || (distractor >= 0.50f && maxPrimary < 0.20f);

        float finalScore = string.IsNullOrEmpty(label) ? 0f
            : cryingHigher ? Math.Clamp(weighted * 0.15f, 0f, 1f)
            : distractorDominant ? Math.Clamp(weighted * 0.25f, 0f, 1f)
            : Math.Clamp(weighted, 0f, 1f);

        return new AudioIntimateResult(finalScore, label, rms, distractorDominant);
    }
}

public static class IntimateExitRules
{
    private static readonly HashSet<string> DedicatedVideoPlayers = new(StringComparer.OrdinalIgnoreCase)
    {
        "vlc", "stremio", "stremio-shell-ng", "nuvio", "mpv", "kodi", "plex", "plexmediaplayer",
        "potplayermini64", "potplayermini", "mpc-hc64", "mpc-hc", "mpc-be64", "wmplayer", "video.ui"
    };

    private static readonly string[] ReelUrlOrTitleHints =
    [
        "/shorts/", "/reel/", "/reels/", "youtube.com/shorts", "instagram.com/reels",
        "facebook.com/reel", "tiktok.com/@", "tiktok.com/foryou"
    ];

    private static readonly string[] MovieUrlOrTitleHints =
    [
        "youtube.com/watch", "youtu.be/", "stremio", "nuvio", "vlc media player",
        "twitch.tv/", "dailymotion.com/video", "vimeo.com/", "plex", "jellyfin", "emby"
    ];

    public static MediaAppContext ClassifyDesktopContext(
        string processName,
        string windowTitle,
        string? urlText,
        bool isFullscreenWindow)
    {
        string proc = (processName ?? "").Trim().ToLowerInvariant();
        string title = (windowTitle ?? "").Trim().ToLowerInvariant();
        string url = (urlText ?? "").Trim().ToLowerInvariant();
        string combined = $"{title} {url}";

        if (DedicatedVideoPlayers.Contains(proc) ||
            proc.Contains("stremio") ||
            proc.Contains("nuvio"))
        {
            return MediaAppContext.MovieSeriesPlayer;
        }

        if (ContentGuardLists.DesktopBrowserProcesses.Contains(proc))
        {
            if (ReelUrlOrTitleHints.Any(h => combined.Contains(h, StringComparison.OrdinalIgnoreCase)) ||
                (title.Contains("shorts", StringComparison.OrdinalIgnoreCase) && title.Contains("youtube", StringComparison.OrdinalIgnoreCase)) ||
                (title.Contains("reels", StringComparison.OrdinalIgnoreCase) &&
                 (title.Contains("facebook") || title.Contains("instagram"))))
            {
                return MediaAppContext.ReelsShort;
            }

            if (isFullscreenWindow || MovieUrlOrTitleHints.Any(h => combined.Contains(h, StringComparison.OrdinalIgnoreCase)))
            {
                return MediaAppContext.MovieSeriesPlayer;
            }

            return MediaAppContext.BrowserOrGeneral;
        }

        if (isFullscreenWindow)
        {
            return MediaAppContext.MovieSeriesPlayer;
        }

        return MediaAppContext.BrowserOrGeneral;
    }

    public static ExitActionType ChooseAction(
        MediaAppContext context,
        IntimateActionMode mode,
        int consecutiveSkipsOnSameVideo)
    {
        if (mode == IntimateActionMode.BackOnly)
        {
            return ExitActionType.PauseAndBack;
        }

        return context switch
        {
            MediaAppContext.ReelsShort => consecutiveSkipsOnSameVideo < 3
                ? ExitActionType.NextReelSwipe
                : ExitActionType.PauseAndBack,
            MediaAppContext.MovieSeriesPlayer => consecutiveSkipsOnSameVideo < 2
                ? ExitActionType.SkipSceneForward
                : ExitActionType.PauseAndBack,
            _ => ExitActionType.PauseAndBack
        };
    }

    public static string? DetectDrmTarget(string processName, string windowTitle, string? urlText)
    {
        string proc = (processName ?? "").Trim().ToLowerInvariant();
        string combined = $"{windowTitle} {urlText}".ToLowerInvariant();

        if (ContentGuardLists.DesktopDrmProcessHints.Any(p => proc.Contains(p)))
        {
            return proc;
        }

        foreach (string domain in ContentGuardLists.DesktopDrmSiteHints)
        {
            if (combined.Contains(domain)) return domain;
        }

        return null;
    }
}
