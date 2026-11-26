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
            ExitActionType.PAUSE_AND_BACK,
            IntimateExitRules.chooseAction(nuvio, IntimateActionMode.SMART_CONTEXT, 2)
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
}
