package com.savegaze.ml

import com.savegaze.service.ExitActionType
import com.savegaze.service.IntimateActionMode
import com.savegaze.service.IntimateExitRules
import com.savegaze.service.MediaAppContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntimateGuardRulesTest {

    @Test
    fun closeUpKissingWithSupportiveCueTriggersIntimateScene() {
        val leftFace = Detection(NudeLabel.FACE_MALE, 0.85f, BoundingBox(260f, 380f, 500f, 650f))
        val rightFace = Detection(NudeLabel.FACE_FEMALE, 0.88f, BoundingBox(490f, 390f, 730f, 660f))

        val signals = IntimateSceneRules.evaluate(
            detections = listOf(leftFace, rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0.01f, nsfw = 0.36f, sfw = 0.63f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 1080f,
            screenH = 1920f,
            sensitivity = 0.25f
        )

        assertTrue(signals.triggered)
        assertTrue(signals.isKissOrBedCue)
    }

    @Test
    fun sfwCloseUpTalkingHeadsDoNotFalseTrigger() {
        val leftFace = Detection(NudeLabel.FACE_MALE, 0.85f, BoundingBox(260f, 380f, 500f, 680f))
        val rightFace = Detection(NudeLabel.FACE_FEMALE, 0.88f, BoundingBox(490f, 390f, 730f, 690f))

        val signals = IntimateSceneRules.evaluate(
            detections = listOf(leftFace, rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0.01f, nsfw = 0.06f, sfw = 0.93f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 1080f,
            screenH = 1920f,
            sensitivity = 0.25f
        )

        assertFalse(signals.triggered)
    }

    @Test
    fun captionRulesDetectStageCaptionsWithoutScreenCast() {
        assertEquals("[moaning]", IntimateCaptionRules.detectIntimateCaption("Wait for me [moaning] please"))
        assertEquals("(kissing)", IntimateCaptionRules.detectIntimateCaption("Subtitles: (kissing)"))
        assertEquals(null, IntimateCaptionRules.detectIntimateCaption("They are talking about dinner tonight."))
    }

    @Test
    fun distantClothedFacesDoNotTriggerIntimateScene() {
        val leftFace = Detection(NudeLabel.FACE_MALE, 0.85f, BoundingBox(120f, 400f, 260f, 560f))
        val rightFace = Detection(NudeLabel.FACE_FEMALE, 0.88f, BoundingBox(700f, 410f, 840f, 570f))

        val signals = IntimateSceneRules.evaluate(
            detections = listOf(leftFace, rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0.01f, nsfw = 0.08f, sfw = 0.91f),
            audioScore = 0.05f,
            audioLabel = null,
            screenW = 1080f,
            screenH = 1920f,
            sensitivity = 0.25f
        )

        assertFalse(signals.triggered)
        assertFalse(signals.isKissOrBedCue)
    }

    @Test
    fun explicitNudityTriggersInstantShield() {
        val breast = Detection(NudeLabel.FEMALE_BREAST_EXPOSED, 0.72f, BoundingBox(300f, 600f, 520f, 820f))

        val signals = IntimateSceneRules.evaluate(
            detections = listOf(breast),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0.01f, nsfw = 0.60f, sfw = 0.39f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 1080f,
            screenH = 1920f,
            sensitivity = 0.25f
        )

        assertTrue(signals.triggered)
        assertTrue(signals.instant)
    }

    @Test
    fun yamnetAudioMoanScoringDetectsMoansAndSuppressesSpeechMusic() {
        val probs = FloatArray(521)
        probs[AudioIntimateRules.IDX_WAIL_MOAN] = 0.38f
        probs[AudioIntimateRules.IDX_PANT] = 0.22f
        val moanRes = AudioIntimateRules.scoreFrame(probs, rms = 0.12f)
        assertTrue("Expected elevated moan score, got ${moanRes.score}", moanRes.score >= 0.45f)
        assertEquals("Moan", moanRes.topLabel)

        val speechProbs = FloatArray(521)
        speechProbs[AudioIntimateRules.IDX_SPEECH] = 0.85f
        speechProbs[AudioIntimateRules.IDX_BREATHING] = 0.12f
        val speechRes = AudioIntimateRules.scoreFrame(speechProbs, rms = 0.12f)
        assertTrue(speechRes.distractorDominant)
        assertTrue("Speech should stay low, got ${speechRes.score}", speechRes.score < 0.25f)

        val silentRes = AudioIntimateRules.scoreFrame(probs, rms = 0.002f)
        assertEquals(0f, silentRes.score, 0.0001f)
    }

    @Test
    fun cryingDoesNotMasqueradeAsMoaning() {
        val cryProbs = FloatArray(521)
        cryProbs[AudioIntimateRules.IDX_CRY_SOB] = 0.60f
        cryProbs[AudioIntimateRules.IDX_WAIL_MOAN] = 0.12f
        val res = AudioIntimateRules.scoreFrame(cryProbs, rms = 0.10f)
        assertTrue(res.distractorDominant)
        assertTrue("Sad crying should be suppressed, got ${res.score}", res.score < 0.25f)
    }

    @Test
    fun exitRulesMatchReelsMoviesAndBrowsersCleanly() {
        val igReels = IntimateExitRules.classifyContext(
            "com.instagram.android",
            setOf("com.instagram.android:id/clips_viewer_video_layout"),
            hasSeekBarOrPlayerControls = false,
            isLandscape = false
        )
        assertEquals(MediaAppContext.REELS_SHORT, igReels)
        assertEquals(
            ExitActionType.NEXT_REEL_SWIPE,
            IntimateExitRules.chooseAction(igReels, IntimateActionMode.SMART_CONTEXT, 0)
        )
        assertEquals(
            ExitActionType.PAUSE_AND_BACK,
            IntimateExitRules.chooseAction(igReels, IntimateActionMode.SMART_CONTEXT, 3)
        )

        val nuvio = IntimateExitRules.classifyContext(
            "com.nuvio.streaming",
            emptySet(),
            hasSeekBarOrPlayerControls = false,
            isLandscape = true
        )
        assertEquals(MediaAppContext.MOVIE_SERIES_PLAYER, nuvio)
        assertEquals(
            ExitActionType.SKIP_SCENE_FORWARD,
            IntimateExitRules.chooseAction(nuvio, IntimateActionMode.SMART_CONTEXT, 0)
        )
        assertEquals(
            ExitActionType.SKIP_SCENE_FORWARD,
            IntimateExitRules.chooseAction(nuvio, IntimateActionMode.SMART_CONTEXT, 2)
        )
        assertEquals(
            ExitActionType.SKIP_SCENE_FORWARD,
            IntimateExitRules.chooseAction(nuvio, IntimateActionMode.SMART_CONTEXT, 10)
        )
        assertEquals(
            ExitActionType.PAUSE_AND_BACK,
            IntimateExitRules.chooseAction(nuvio, IntimateActionMode.BACK_ONLY, 0)
        )

        val chromeArticle = IntimateExitRules.classifyContext(
            "com.android.chrome",
            emptySet(),
            hasSeekBarOrPlayerControls = false,
            isLandscape = false
        )
        assertEquals(MediaAppContext.BROWSER_OR_GENERAL, chromeArticle)
        assertEquals(
            ExitActionType.PAUSE_AND_BACK,
            IntimateExitRules.chooseAction(chromeArticle, IntimateActionMode.SMART_CONTEXT, 0)
        )
    }

    @Test
    fun autoCastRulesTargetMoviesPlayersAndExcludeSocialAndArticles() {
        assertTrue(IntimateExitRules.shouldAutoCastAudio("com.stremio.one", emptySet(), false, false, false))
        assertTrue(IntimateExitRules.shouldAutoCastAudio("com.nuvio.streaming", emptySet(), false, false, false))
        assertTrue(IntimateExitRules.shouldAutoCastAudio("org.videolan.vlc", emptySet(), false, false, false))
        assertTrue(IntimateExitRules.shouldAutoCastAudio("com.mxtech.videoplayer.ad", emptySet(), false, false, false))

        assertTrue(
            IntimateExitRules.shouldAutoCastAudio(
                "com.google.android.youtube",
                setOf("com.google.android.youtube:id/watch_player"),
                hasSeekBarOrPlayerControls = true,
                isLandscape = false,
                isMediaPlaying = true
            )
        )
        assertFalse(
            IntimateExitRules.shouldAutoCastAudio(
                "com.google.android.youtube",
                setOf("com.google.android.youtube:id/reel_recycler"),
                hasSeekBarOrPlayerControls = false,
                isLandscape = false,
                isMediaPlaying = true
            )
        )
        assertFalse(
            IntimateExitRules.shouldAutoCastAudio(
                "com.google.android.youtube",
                setOf("com.google.android.youtube:id/results"),
                hasSeekBarOrPlayerControls = false,
                isLandscape = false,
                isMediaPlaying = false
            )
        )

        assertFalse(
            IntimateExitRules.shouldAutoCastAudio(
                "com.android.chrome",
                emptySet(),
                hasSeekBarOrPlayerControls = false,
                isLandscape = false,
                isMediaPlaying = false
            )
        )
        assertTrue(
            IntimateExitRules.shouldAutoCastAudio(
                "com.android.chrome",
                emptySet(),
                hasSeekBarOrPlayerControls = true,
                isLandscape = true,
                isMediaPlaying = true
            )
        )

        assertFalse(IntimateExitRules.shouldAutoCastAudio("com.instagram.android", setOf("clips_viewer"), true, true, true))
        assertFalse(IntimateExitRules.shouldAutoCastAudio("com.facebook.katana", setOf("fb_shorts"), true, true, true))
        assertFalse(IntimateExitRules.shouldAutoCastAudio("com.zhiliaoapp.musically", emptySet(), true, true, true))
    }

    @Test
    fun mobileClipScoringDistinguishesKissingFromSfwDialogue() {
        val prompts = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(0.8f, 0.6f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0.8f, 0.6f)
        )
        val kissEmb = floatArrayOf(0.95f, 0.25f, 0.10f, 0.0f)
        val kissScore = MobileClipIntimateRules.score(kissEmb, prompts, positiveCount = 2)
        assertTrue(kissScore.intimateProb >= 0.72f)
        assertTrue(kissScore.margin >= 0.025f)

        val talkEmb = floatArrayOf(0.05f, 0.05f, 0.96f, 0.20f)
        val talkScore = MobileClipIntimateRules.score(talkEmb, prompts, positiveCount = 2)
        assertTrue(talkScore.intimateProb < 0.30f)
        assertTrue(talkScore.margin < 0f)

        // Blank/black screens must fail visual content check, while genuine dim night cinema frames pass
        assertFalse(MobileClipIntimateRules.hasVisualContent(FloatArray(256) { 3f }))
        assertFalse(MobileClipIntimateRules.hasVisualContent(FloatArray(256) { 128f }))
        assertTrue(MobileClipIntimateRules.hasVisualContent(FloatArray(256) { i -> if (i % 2 == 0) 6f else 18f }))
        assertTrue(MobileClipIntimateRules.hasVisualContent(FloatArray(256) { i -> if (i % 2 == 0) 30f else 180f }))
    }

    @Test
    fun clothedMovieKissWithMobileClipTriggersWithoutNsfwScore() {
        // Clothed PG-13 movie kissing (SwiftFormer scores nsfw=0.05, NudeNet sees only two close faces)
        val leftFace = Detection(NudeLabel.FACE_MALE, 0.82f, BoundingBox(420f, 180f, 660f, 440f))
        val rightFace = Detection(NudeLabel.FACE_FEMALE, 0.85f, BoundingBox(650f, 190f, 890f, 450f))

        val sfwDialogue = IntimateSceneRules.evaluate(
            detections = listOf(leftFace, rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0f, nsfw = 0.05f, sfw = 0.95f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 2400f,
            screenH = 1080f,
            sensitivity = 0.25f,
            clipKissProb = 0.28f,
            clipMargin = -0.02f,
            allowDarkClipFallback = true
        )
        assertFalse(sfwDialogue.triggered)

        val clipKiss = IntimateSceneRules.evaluate(
            detections = listOf(leftFace, rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0f, nsfw = 0.05f, sfw = 0.95f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 2400f,
            screenH = 1080f,
            sensitivity = 0.25f,
            clipKissProb = 0.78f,
            clipMargin = 0.028f,
            allowDarkClipFallback = true
        )
        assertTrue(clipKiss.triggered)
        assertTrue(clipKiss.instant)
        assertTrue(clipKiss.reason.contains("CLIP"))

        // Moderate 1-face close-up kiss requires 2 separate scans (prevents pre-kiss/dance single-frame false skip)
        val moderateDaylightKiss = IntimateSceneRules.evaluate(
            detections = listOf(rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0f, nsfw = 0.04f, sfw = 0.96f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 2400f,
            screenH = 1080f,
            sensitivity = 0.25f,
            clipKissProb = 0.79f,
            clipMargin = 0.030f,
            allowDarkClipFallback = true
        )
        assertTrue(moderateDaylightKiss.triggered)
        assertFalse(moderateDaylightKiss.instant)

        // Strong daylight/movie kiss fires IMMEDIATELY (matches real 0.86-0.97 phone log scores)
        val strongDaylightKiss = IntimateSceneRules.evaluate(
            detections = listOf(rightFace),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0f, nsfw = 0.04f, sfw = 0.96f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 2400f,
            screenH = 1080f,
            sensitivity = 0.25f,
            clipKissProb = 0.87f,
            clipMargin = 0.040f,
            allowDarkClipFallback = true
        )
        assertTrue(strongDaylightKiss.triggered)
        assertTrue(strongDaylightKiss.instant)

        // Dark bedroom kissing/bed scene in movie player (0 upright faces detected in dark lighting) fires immediately when clear
        val darkBedMovieScene = IntimateSceneRules.evaluate(
            detections = emptyList(),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0f, nsfw = 0.06f, sfw = 0.94f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 2400f,
            screenH = 1080f,
            sensitivity = 0.25f,
            clipKissProb = 0.88f,
            clipMargin = 0.042f,
            allowDarkClipFallback = true
        )
        assertTrue(darkBedMovieScene.triggered)
        assertTrue(darkBedMovieScene.instant)

        // Same dark room without faces on Social Reels (allowDarkClipFallback=false) must NOT false-trigger
        val darkRoomReelsNoPeople = IntimateSceneRules.evaluate(
            detections = emptyList(),
            personBoxes = emptyList(),
            sceneSafety = SceneSafetyScores(nsfl = 0f, nsfw = 0.04f, sfw = 0.96f),
            audioScore = 0f,
            audioLabel = null,
            screenW = 1080f,
            screenH = 2340f,
            sensitivity = 0.25f,
            clipKissProb = 0.88f,
            clipMargin = 0.042f,
            allowDarkClipFallback = false
        )
        assertFalse(darkRoomReelsNoPeople.triggered)
    }

    @Test
    fun facebookFeedWithReelsTabIsNotMisclassifiedAsReelsPlayer() {
        val fbHomeFeedWithReelsTab = IntimateExitRules.classifyContext(
            packageName = "com.facebook.katana",
            treeHints = setOf("com.facebook.katana:id/feed_story_container", "reels"),
            hasSeekBarOrPlayerControls = false,
            isLandscape = false
        )
        assertEquals(MediaAppContext.BROWSER_OR_GENERAL, fbHomeFeedWithReelsTab)

        val fbActualReelsViewer = IntimateExitRules.classifyContext(
            packageName = "com.facebook.katana",
            treeHints = setOf("com.facebook.katana:id/fb_shorts_video_container"),
            hasSeekBarOrPlayerControls = false,
            isLandscape = false
        )
        assertEquals(MediaAppContext.REELS_SHORT, fbActualReelsViewer)
    }

    @Test
    fun cloudstreamAndNuvioClassifiedAsDedicatedStreamingPlayers() {
        for (pkg in listOf("com.nuvio.app", "com.lagradost.cloudstream3")) {
            val ctx = IntimateExitRules.classifyContext(
                packageName = pkg,
                treeHints = emptySet(),
                hasSeekBarOrPlayerControls = false,
                isLandscape = true
            )
            assertEquals(MediaAppContext.MOVIE_SERIES_PLAYER, ctx)
            assertEquals(
                ExitActionType.SKIP_SCENE_FORWARD,
                IntimateExitRules.chooseAction(ctx, IntimateActionMode.SMART_CONTEXT, 0)
            )
            assertTrue(IntimateExitRules.shouldAutoCastAudio(pkg, emptySet(), false, true, true))
        }
    }
}
