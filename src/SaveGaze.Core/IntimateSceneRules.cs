using System.Text.RegularExpressions;

namespace SaveGaze.Core;

public static class MobileClipIntimateRules
{
    public static bool HasVisualContent(ReadOnlySpan<float> lumas)
    {
        if (lumas.Length == 0) return false;
        double sum = 0.0;
        for (int i = 0; i < lumas.Length; i++) sum += lumas[i];
        double mean = sum / lumas.Length;
        if (mean < 6.5) return false;

        double varSum = 0.0;
        for (int i = 0; i < lumas.Length; i++)
        {
            double d = lumas[i] - mean;
            varSum += d * d;
        }
        return Math.Sqrt(varSum / lumas.Length) >= 5.0;
    }

    public static ClipIntimateScore Score(
        float[] imageEmbedding,
        float[][] promptEmbeddings,
        int positiveCount,
        float logitScale = 55f)
    {
        if (promptEmbeddings.Length == 0 || positiveCount <= 0 || positiveCount >= promptEmbeddings.Length)
        {
            return new ClipIntimateScore(0f, 0f, 0f, 0f);
        }

        float normSq = 0f;
        for (int i = 0; i < imageEmbedding.Length; i++) normSq += imageEmbedding[i] * imageEmbedding[i];
        float invNorm = normSq > 1e-8f ? 1f / MathF.Sqrt(normSq) : 1f;

        var sims = new float[promptEmbeddings.Length];
        float maxPos = float.NegativeInfinity;
        float maxNeg = float.NegativeInfinity;
        float maxAll = float.NegativeInfinity;

        for (int i = 0; i < promptEmbeddings.Length; i++)
        {
            float[] p = promptEmbeddings[i];
            int len = Math.Min(imageEmbedding.Length, p.Length);
            float dot = 0f;
            for (int k = 0; k < len; k++)
            {
                dot += (imageEmbedding[k] * invNorm) * p[k];
            }
            sims[i] = dot;
            if (dot > maxAll) maxAll = dot;
            if (i < positiveCount)
            {
                if (dot > maxPos) maxPos = dot;
            }
            else
            {
                if (dot > maxNeg) maxNeg = dot;
            }
        }

        double sumPosExp = 0.0;
        double sumNegExp = 0.0;
        for (int i = 0; i < sims.Length; i++)
        {
            double e = Math.Exp(Math.Clamp((sims[i] - maxAll) * (double)logitScale, -40.0, 40.0));
            if (i < positiveCount) sumPosExp += e;
            else sumNegExp += e;
        }
        double total = sumPosExp + sumNegExp;
        float prob = total > 0.0 ? (float)(sumPosExp / total) : 0f;

        return new ClipIntimateScore(prob, maxPos - maxNeg, maxPos, maxNeg);
    }
}

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
                    bool horizontalBedPose = unionW >= unionH * 1.15f && Math.Max(p1.Height, p2.Height) >= screenH * 0.16f;
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

    public static bool IsVisualUnclearForAudio(
        SceneSafetyScores? sceneSafety,
        bool hasSemiNude,
        bool kissOrBedCue,
        float clipKissProb,
        float clipMargin,
        bool hasFreshVisual)
    {
        if (!hasFreshVisual) return true;
        if (clipKissProb == 0f && clipMargin <= -0.15f) return true;
        float nsfwProb = sceneSafety?.Nsfw ?? 0f;
        return nsfwProb >= 0.18f ||
               hasSemiNude ||
               kissOrBedCue ||
               (clipKissProb >= 0.48f && clipMargin >= -0.015f);
    }

    public static IntimateSignals Evaluate(
        IReadOnlyList<Detection> detections,
        IReadOnlyList<PersonGenderBox> personBoxes,
        SceneSafetyScores? sceneSafety,
        float audioScore,
        string? audioLabel,
        float screenW,
        float screenH,
        float sensitivity = 0.25f,
        float clipKissProb = 0f,
        float clipMargin = 0f,
        bool allowDarkClipFallback = false,
        bool continuingIntimateScene = false,
        bool? hasFreshVisualOverride = null)
    {
        bool hasFreshVisual = hasFreshVisualOverride ?? (sceneSafety != null || clipKissProb > 0f || clipMargin != 0f);
        float explicitThresh = Math.Clamp(sensitivity, 0.26f, 0.45f);
        bool hasExplicit = detections.Any(d => ExplicitNudityLabels.Contains(d.Label) && d.Confidence >= explicitThresh);
        bool highExplicit = detections.Any(d => ExplicitNudityLabels.Contains(d.Label) && d.Confidence >= 0.62f);
        bool hasSemiNude = detections.Any(d => SemiNudeLabels.Contains(d.Label) && d.Confidence >= sensitivity);
        var faces = detections.Where(d => d.Label.IsFace()).ToList();

        float nsfwProb = sceneSafety?.Nsfw ?? 0f;
        bool kissOrBedCue = HasCloseUpKissingOrEmbrace(faces, personBoxes, screenW, screenH);

        // 1. Explicit visual nudity confirmed by scene classifier or high NudeNet confidence
        float explicitNsfwFloor = continuingIntimateScene ? 0.35f : 0.42f;
        if (hasExplicit && (nsfwProb >= explicitNsfwFloor || highExplicit))
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: continuingIntimateScene || (highExplicit && nsfwProb >= 0.45f),
                Reason: "Explicit Scene Detected",
                Confidence: Math.Max(nsfwProb, 0.78f),
                IsKissOrBedCue: kissOrBedCue);
        }

        // 2. Strong overall NSFW/suggestive frame (bed scenes, lingerie/partial nudity)
        float nsfwSceneFloor = continuingIntimateScene ? 0.65f : 0.82f;
        if (nsfwProb >= nsfwSceneFloor)
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: continuingIntimateScene || nsfwProb >= 0.93f,
                Reason: "Intimate / NSFW Scene",
                Confidence: nsfwProb,
                IsKissOrBedCue: kissOrBedCue);
        }

        // 3. MobileCLIP2-S0 clothed kissing / bed / romantic embrace detector
        bool hasHumanCue = faces.Count > 0 || personBoxes.Count > 0 || hasSemiNude || nsfwProb >= 0.16f;
        bool closePairClip = kissOrBedCue && clipKissProb >= 0.74f && clipMargin >= 0.023f;
        bool humanBackedClip = hasHumanCue && clipKissProb >= 0.75f && clipMargin >= 0.025f;
        bool darkMovieBedClip = allowDarkClipFallback && clipKissProb >= 0.82f && clipMargin >= 0.027f;
        bool continueMovieClip = continuingIntimateScene && allowDarkClipFallback &&
            ((kissOrBedCue && clipKissProb >= 0.74f && clipMargin >= 0.023f) ||
             (clipKissProb >= 0.80f && clipMargin >= 0.026f));

        if (closePairClip || humanBackedClip || darkMovieBedClip || continueMovieClip)
        {
            bool isInstantKiss = continueMovieClip || (allowDarkClipFallback
                ? (closePairClip && clipKissProb >= 0.78f && clipMargin >= 0.028f) ||
                  (humanBackedClip && clipKissProb >= 0.86f && clipMargin >= 0.038f) ||
                  (darkMovieBedClip && clipKissProb >= 0.87f && clipMargin >= 0.038f)
                : closePairClip && clipKissProb >= 0.80f && clipMargin >= 0.032f);

            return new IntimateSignals(
                Triggered: true,
                Instant: isInstantKiss,
                Reason: "Intimate Scene / Kissing (CLIP)",
                Confidence: clipKissProb,
                IsKissOrBedCue: true);
        }

        // 4. Kissing / bed pose must have real NSFW, semi-nude, or intimate audio support;
        // never let noisy low SwiftFormer scores override a fresh safe CLIP score (prevents post-skip loops)
        bool hasFreshClipScore = hasFreshVisual && (clipKissProb > 0f || clipMargin != 0f);
        bool clipClearlySafe = hasFreshClipScore && (clipKissProb < 0.74f || clipMargin < 0.023f);
        bool poseTriggered = clipClearlySafe
            ? (nsfwProb >= 0.65f || (hasSemiNude && nsfwProb >= 0.42f))
            : (nsfwProb >= 0.36f || (nsfwProb >= 0.24f && (hasSemiNude || audioScore >= 0.35f)));

        if (kissOrBedCue && poseTriggered)
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: nsfwProb >= 0.65f || (hasSemiNude && audioScore >= 0.35f),
                Reason: "Intimate Kissing / Bed Scene",
                Confidence: Math.Max(nsfwProb, 0.58f),
                IsKissOrBedCue: true);
        }

        // 5. Visual-first policy: audio triggers only when visual frame is unclear or borderline
        bool hasVocalLabel = !string.IsNullOrWhiteSpace(audioLabel);
        bool visualUnclear = IsVisualUnclearForAudio(
            sceneSafety, hasSemiNude, kissOrBedCue, clipKissProb, clipMargin, hasFreshVisual);
        if (hasVocalLabel && visualUnclear &&
            (audioScore >= 0.55f || (audioScore >= 0.35f && (nsfwProb >= 0.25f || hasSemiNude || kissOrBedCue || continuingIntimateScene))))
        {
            return new IntimateSignals(
                Triggered: true,
                Instant: continuingIntimateScene,
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
            MediaAppContext.MovieSeriesPlayer => ExitActionType.SkipSceneForward,
            _ => ExitActionType.PauseAndBack
        };
    }

    public static bool IsSocialFeedBrowse(string processName, string windowTitle, string? urlText, bool isFullscreenWindow)
    {
        if (isFullscreenWindow) return false;
        string proc = (processName ?? "").Trim().ToLowerInvariant();
        if (!ContentGuardLists.DesktopBrowserProcesses.Contains(proc)) return false;

        if (ClassifyDesktopContext(proc, windowTitle, urlText, isFullscreenWindow) != MediaAppContext.BrowserOrGeneral)
        {
            return false;
        }

        string combined = $"{windowTitle} {urlText}".ToLowerInvariant();
        return combined.Contains("facebook.com") ||
               combined.Contains("instagram.com") ||
               combined.Contains("reddit.com") ||
               combined.Contains("x.com") ||
               combined.Contains("twitter.com") ||
               combined.Contains("threads.net");
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
