using SaveGaze.Core;
using Xunit;

namespace SaveGaze.Tests;

public sealed class SaveGazeRulesTests
{
    [Fact]
    public void HybridGenderFilterCorrectsBiasedMaleFaceAndPreservesUpperFaceWhenFaceBlurOff()
    {
        var personMale = new PersonGenderBox(PersonGender.Male, 0.78f, new BoundingBox(100, 50, 350, 700));
        var wrongFace = new Detection(NudeLabel.FaceFemale, 0.45f, new BoundingBox(170, 70, 280, 190));
        var belly = new Detection(NudeLabel.BellyExposed, 0.60f, new BoundingBox(150, 220, 300, 400));

        var filtered = NudeNetFilterEngine.FilterDetectionsPure(
            [wrongFace, belly], [personMale], GenderFilter.FemaleOnly, blurFaces: true);

        Assert.False(filtered.HasNsfwContent);
        Assert.Empty(filtered.MergedBlurBoxes);
        Assert.Contains(filtered.Faces, f => f.Label == NudeLabel.FaceMale);
        Assert.Single(filtered.ProtectedFaces);
    }

    [Fact]
    public void SemiNudeTriggersWholeBodyBlurAndJawlineProtection()
    {
        var woman = new PersonGenderBox(PersonGender.Female, 0.85f, new BoundingBox(100, 80, 380, 920));
        var face = new Detection(NudeLabel.FaceFemale, 0.88f, new BoundingBox(180, 100, 300, 220));
        var cleavage = new Detection(NudeLabel.FemaleBreastCovered, 0.65f, new BoundingBox(170, 280, 310, 400));

        var bodyOnly = NudeNetFilterEngine.FilterDetectionsPure(
            [face, cleavage], [woman], GenderFilter.FemaleOnly, blurFaces: false);

        Assert.True(bodyOnly.HasNsfwContent);
        Assert.Single(bodyOnly.MergedBlurBoxes);
        var shield = bodyOnly.MergedBlurBoxes[0];
        Assert.True(shield.Y2 >= 900f);
        Assert.InRange(shield.Y1, 186f, 212f);
        Assert.Single(bodyOnly.ProtectedFaces);
        Assert.True(bodyOnly.ProtectedFaces[0].Y2 <= 212f);

        var bodyAndFace = NudeNetFilterEngine.FilterDetectionsPure(
            [face, cleavage], [woman], GenderFilter.FemaleOnly, blurFaces: true);

        Assert.True(bodyAndFace.HasNsfwContent);
        Assert.Single(bodyAndFace.MergedBlurBoxes);
        Assert.True(bodyAndFace.MergedBlurBoxes[0].Y1 <= 85f);
        Assert.Empty(bodyAndFace.ProtectedFaces);
    }

    [Fact]
    public void DecodeYoloHeadExtractsGenderAndBoxCoordinates()
    {
        int gridH = 10, gridW = 17;
        var tensor = Enumerable.Range(0, 21).Select(_ => Enumerable.Repeat(-10f, gridH * gridW).ToArray()).ToArray();
        int idx = 5 * gridW + 8;
        tensor[0][idx] = 0f;
        tensor[1][idx] = 0f;
        tensor[2][idx] = 0f;
        tensor[3][idx] = 0f;
        tensor[4][idx] = 5f;
        tensor[5][idx] = -5f;
        tensor[6][idx] = 5f;

        var decoded = GenderPersonDecoder.DecodeHead(tensor, gridH, gridW, 0, 1f, 1f, 0f, 0f, 544f, 320f, 0.25f);
        Assert.Single(decoded);
        Assert.Equal(PersonGender.Female, decoded[0].Gender);
        Assert.True(decoded[0].Confidence > 0.95f);
        Assert.InRange(decoded[0].Box.Width, 79.5f, 82.5f);
        Assert.InRange(decoded[0].Box.Height, 80.5f, 83.5f);
    }

    [Fact]
    public void IntimateSceneAndAudioRulesMatchAndroidThresholds()
    {
        var f1 = new Detection(NudeLabel.FaceMale, 0.85f, new BoundingBox(260, 380, 500, 650));
        var f2 = new Detection(NudeLabel.FaceFemale, 0.88f, new BoundingBox(490, 390, 730, 660));

        var kissHit = IntimateSceneRules.Evaluate([f1, f2], [], new SceneSafetyScores(0.01f, 0.36f, 0.63f), 0f, null, 1080, 1920);
        Assert.True(kissHit.Triggered);
        Assert.True(kissHit.IsKissOrBedCue);

        var talkingHeads = IntimateSceneRules.Evaluate([f1, f2], [], new SceneSafetyScores(0.01f, 0.06f, 0.93f), 0f, null, 1080, 1920);
        Assert.False(talkingHeads.Triggered);

        Assert.Equal("[moaning]", IntimateCaptionRules.DetectIntimateCaption("Please wait [moaning] now"));
        Assert.Null(IntimateCaptionRules.DetectIntimateCaption("Normal dialogue subtitle."));

        var probs = new float[521];
        probs[AudioIntimateRules.IdxWailMoan] = 0.38f;
        probs[AudioIntimateRules.IdxPant] = 0.22f;
        var moanRes = AudioIntimateRules.ScoreFrame(probs, rms: 0.12f);
        Assert.True(moanRes.Score >= 0.45f);
        Assert.Equal("Moan", moanRes.TopLabel);

        var silentRes = AudioIntimateRules.ScoreFrame(probs, rms: 0.002f);
        Assert.Equal(0f, silentRes.Score);

        var speechProbs = new float[521];
        speechProbs[AudioIntimateRules.IdxSpeech] = 0.85f;
        speechProbs[AudioIntimateRules.IdxBreathing] = 0.12f;
        var speechRes = AudioIntimateRules.ScoreFrame(speechProbs, rms: 0.12f);
        Assert.True(speechRes.DistractorDominant);
        Assert.True(speechRes.Score < 0.25f);
    }

    [Fact]
    public void DesktopContextExitAndContentGuardRulesWorkAccurately()
    {
        var reels = IntimateExitRules.ClassifyDesktopContext("chrome", "YouTube Shorts - Funny", "https://youtube.com/shorts/abc", false);
        Assert.Equal(MediaAppContext.ReelsShort, reels);
        Assert.Equal(ExitActionType.NextReelSwipe, IntimateExitRules.ChooseAction(reels, IntimateActionMode.SmartContext, 0));

        var vlc = IntimateExitRules.ClassifyDesktopContext("vlc", "Movie.mkv - VLC media player", "", true);
        Assert.Equal(MediaAppContext.MovieSeriesPlayer, vlc);
        Assert.Equal(ExitActionType.SkipSceneForward, IntimateExitRules.ChooseAction(vlc, IntimateActionMode.SmartContext, 0));
        Assert.Equal(ExitActionType.PauseAndBack, IntimateExitRules.ChooseAction(vlc, IntimateActionMode.SmartContext, 2));

        var active = ContentGuardRules.BuildActiveDomains(["https://www.CustomSite.org/path"]);
        Assert.Equal("pornhub.com", ContentGuardRules.IsDomainBlocked("m.pornhub.com", active));
        Assert.Null(ContentGuardRules.IsDomainBlocked("notpornhub.com", active));

        Assert.False(ContentGuardRules.MatchesWholeWordKeyword("data analyst report", "anal"));
        Assert.True(ContentGuardRules.MatchesWholeWordKeyword("porn", "porn"));
        Assert.True(ContentGuardRules.CheckSearchOrInputText("porn", active, []).Blocked);

        var hash = ContentGuardRules.HashPassword("Secret123");
        Assert.True(ContentGuardRules.VerifyPassword("Secret123", hash));
        Assert.False(ContentGuardRules.VerifyPassword("wrong", hash));
        Assert.True(ContentGuardRules.VerifyPassword("test", ContentGuardRules.HashLegacyUnsalted("test")));

        long baseMs = 1_700_000_000_000L;
        IReadOnlyList<long> ts = [];
        for (int i = 0; i < 4; i++)
        {
            var r = ContentGuardRules.RecordCrossBlockEvent(ts, baseMs + i * 1000L);
            Assert.False(r.TriggeredLockout);
            ts = r.Timestamps;
        }
        var fifth = ContentGuardRules.RecordCrossBlockEvent(ts, baseMs + 5000L);
        Assert.True(fifth.TriggeredLockout);
    }

    [Fact]
    public void UpdateCheckerFiltersPcTagsAndIgnoresAndroidReleases()
    {
        const string json = """
        [
          { "tag_name": "v2.2", "draft": false, "assets": [{ "name": "SaveGaze.apk", "browser_download_url": "https://example.com/SaveGaze.apk", "size": 100 }] },
          { "tag_name": "pc-v1.1.0", "draft": false, "body": "PC release", "assets": [{ "name": "SaveGaze-Setup.exe", "browser_download_url": "https://example.com/SaveGaze-Setup.exe", "size": 500 }] }
        ]
        """;
        var parsed = UpdateChecker.ParsePcReleaseFromGitHubArray(json);
        Assert.NotNull(parsed);
        Assert.Equal("1.1.0", parsed!.VersionName);
        Assert.True(UpdateChecker.IsNewerVersion(parsed.VersionName, "1.0.0"));
        Assert.False(UpdateChecker.IsNewerVersion("1.0.0", "1.0.0"));
    }
}
