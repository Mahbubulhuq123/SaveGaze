package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class SceneSafetyScores(
    val nsfl: Float,
    val nsfw: Float,
    val sfw: Float
)

data class IntimateSignals(
    val triggered: Boolean,
    val instant: Boolean,
    val reason: String,
    val confidence: Float,
    val isKissOrBedCue: Boolean
)

/**
 * Pure decision logic combining visual classifier scores, NudeNet cues,
 * close-up kissing/embrace geometry, and audio moaning scores.
 * Separated from Android/ONNX runtime for JVM unit testing.
 */
object IntimateSceneRules {

    private val EXPLICIT_NUDITY_LABELS = setOf(
        NudeLabel.FEMALE_GENITALIA_EXPOSED,
        NudeLabel.MALE_GENITALIA_EXPOSED,
        NudeLabel.FEMALE_BREAST_EXPOSED,
        NudeLabel.BUTTOCKS_EXPOSED,
        NudeLabel.ANUS_EXPOSED
    )

    private val SEMI_NUDE_LABELS = setOf(
        NudeLabel.FEMALE_BREAST_COVERED,
        NudeLabel.FEMALE_GENITALIA_COVERED,
        NudeLabel.BUTTOCKS_COVERED,
        NudeLabel.BELLY_EXPOSED,
        NudeLabel.MALE_BREAST_EXPOSED
    )

    fun hasCloseUpKissingOrEmbrace(
        faces: List<Detection>,
        personBoxes: List<PersonGenderBox>,
        screenW: Float,
        screenH: Float
    ): Boolean {
        if (screenW <= 0f || screenH <= 0f) return false

        if (faces.size >= 2) {
            for (i in 0 until faces.size) {
                for (j in i + 1 until faces.size) {
                    val f1 = faces[i]
                    val f2 = faces[j]
                    val minFaceH = min(f1.box.height, f2.box.height)
                    if (minFaceH < screenH * 0.045f) continue

                    val avgW = (f1.box.width + f2.box.width) / 2f
                    if (avgW <= 1f) continue

                    val hGap = max(f1.box.x1, f2.box.x1) - min(f1.box.x2, f2.box.x2)
                    val vDiff = abs(f1.box.centerY - f2.box.centerY)
                    val maxH = max(f1.box.height, f2.box.height)
                    val dist = hypot(f1.box.centerX - f2.box.centerX, f1.box.centerY - f2.box.centerY)

                    // Direct kissing / touching cheeks proximity (tight overlap/gap to prevent false triggers on distant talking heads)
                    if (hGap <= avgW * 0.08f && vDiff <= maxH * 0.55f && dist <= avgW * 1.15f) {
                        return true
                    }
                }
            }
        }

        // Overlapping horizontal / lying bodies (common in bed scenes)
        if (personBoxes.size >= 2) {
            for (i in 0 until personBoxes.size) {
                for (j in i + 1 until personBoxes.size) {
                    val p1 = personBoxes[i].box
                    val p2 = personBoxes[j].box
                    val iou = p1.iou(p2)
                    if (iou < 0.25f) continue
                    val unionW = max(p1.x2, p2.x2) - min(p1.x1, p2.x1)
                    val unionH = max(p1.y2, p2.y2) - min(p1.y1, p2.y1)
                    val horizontalBedPose = unionW >= unionH * 1.15f && max(p1.height, p2.height) >= screenH * 0.16f
                    val mixedGender = personBoxes[i].gender != personBoxes[j].gender
                    if (horizontalBedPose && (mixedGender || iou >= 0.35f)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    fun isVisualUnclearForAudio(
        sceneSafety: SceneSafetyScores?,
        hasSemiNude: Boolean,
        kissOrBedCue: Boolean,
        clipKissProb: Float,
        clipMargin: Float,
        hasFreshVisual: Boolean
    ): Boolean {
        if (!hasFreshVisual) return true
        // MobileCLIP returns prob=0f, margin=-0.2f when the frame is too dark or occluded for vision
        if (clipKissProb == 0f && clipMargin <= -0.15f) return true
        val nsfwProb = sceneSafety?.nsfw ?: 0f
        return nsfwProb >= 0.18f ||
            hasSemiNude ||
            kissOrBedCue ||
            (clipKissProb >= 0.48f && clipMargin >= -0.015f)
    }

    fun evaluate(
        detections: List<Detection>,
        personBoxes: List<PersonGenderBox>,
        sceneSafety: SceneSafetyScores?,
        audioScore: Float,
        audioLabel: String?,
        screenW: Float,
        screenH: Float,
        sensitivity: Float = 0.25f,
        clipKissProb: Float = 0f,
        clipMargin: Float = 0f,
        allowDarkClipFallback: Boolean = false,
        continuingIntimateScene: Boolean = false,
        hasFreshVisual: Boolean = (sceneSafety != null || clipKissProb > 0f || clipMargin != 0f)
    ): IntimateSignals {
        val explicitThresh = sensitivity.coerceIn(0.26f, 0.45f)
        val hasExplicit = detections.any { it.label in EXPLICIT_NUDITY_LABELS && it.confidence >= explicitThresh }
        val highExplicit = detections.any { it.label in EXPLICIT_NUDITY_LABELS && it.confidence >= 0.62f }
        val hasSemiNude = detections.any { it.label in SEMI_NUDE_LABELS && it.confidence >= sensitivity }
        val faces = detections.filter { it.label.isFace }

        val nsfwProb = sceneSafety?.nsfw ?: 0f
        val kissOrBedCue = hasCloseUpKissingOrEmbrace(faces, personBoxes, screenW, screenH)

        // 1. Explicit visual nudity confirmed by scene classifier or high NudeNet confidence
        val explicitNsfwFloor = if (continuingIntimateScene) 0.35f else 0.42f
        if (hasExplicit && (nsfwProb >= explicitNsfwFloor || highExplicit)) {
            return IntimateSignals(
                triggered = true,
                instant = continuingIntimateScene || (highExplicit && nsfwProb >= 0.45f),
                reason = "Explicit Scene Detected",
                confidence = max(nsfwProb, 0.78f),
                isKissOrBedCue = kissOrBedCue
            )
        }

        // 2. Strong overall NSFW/suggestive frame (bed scenes, lingerie/partial nudity)
        val nsfwSceneFloor = if (continuingIntimateScene) 0.65f else 0.82f
        if (nsfwProb >= nsfwSceneFloor) {
            return IntimateSignals(
                triggered = true,
                instant = continuingIntimateScene || nsfwProb >= 0.93f,
                reason = "Intimate / NSFW Scene",
                confidence = nsfwProb,
                isKissOrBedCue = kissOrBedCue
            )
        }

        // 3. MobileCLIP2-S0 clothed kissing / bed / romantic embrace detector (daylight, night, heterosexual & same-sex)
        val hasHumanCue = faces.isNotEmpty() || personBoxes.isNotEmpty() || hasSemiNude || nsfwProb >= 0.16f
        val closePairClip = kissOrBedCue && clipKissProb >= 0.74f && clipMargin >= 0.023f
        val humanBackedClip = hasHumanCue && clipKissProb >= 0.75f && clipMargin >= 0.025f
        val darkMovieBedClip = allowDarkClipFallback && clipKissProb >= 0.82f && clipMargin >= 0.027f
        val continueMovieClip = continuingIntimateScene && allowDarkClipFallback &&
            ((kissOrBedCue && clipKissProb >= 0.74f && clipMargin >= 0.023f) ||
                (clipKissProb >= 0.80f && clipMargin >= 0.026f))
        if (closePairClip || humanBackedClip || darkMovieBedClip || continueMovieClip) {
            val isInstantKiss = if (continueMovieClip) {
                true
            } else if (allowDarkClipFallback) {
                (closePairClip && clipKissProb >= 0.74f && clipMargin >= 0.024f) ||
                    (humanBackedClip && clipKissProb >= 0.81f && clipMargin >= 0.030f) ||
                    (darkMovieBedClip && clipKissProb >= 0.85f && clipMargin >= 0.028f)
            } else {
                closePairClip && clipKissProb >= 0.80f && clipMargin >= 0.032f
            }
            return IntimateSignals(
                triggered = true,
                instant = isInstantKiss,
                reason = "Intimate Scene / Kissing (CLIP)",
                confidence = clipKissProb,
                isKissOrBedCue = true
            )
        }

        // 4. Kissing / bed pose must have real NSFW, semi-nude, or intimate audio support;
        // never let noisy low SwiftFormer scores override a fresh safe CLIP score (prevents post-skip loops)
        val hasFreshClipScore = hasFreshVisual && (clipKissProb > 0f || clipMargin != 0f)
        val clipClearlySafe = hasFreshClipScore && (clipKissProb < 0.74f || clipMargin < 0.023f)
        val poseTriggered = if (clipClearlySafe) {
            nsfwProb >= 0.65f || (hasSemiNude && nsfwProb >= 0.42f)
        } else {
            nsfwProb >= 0.36f || (nsfwProb >= 0.24f && (hasSemiNude || audioScore >= 0.35f))
        }
        if (kissOrBedCue && poseTriggered) {
            return IntimateSignals(
                triggered = true,
                instant = nsfwProb >= 0.65f || (hasSemiNude && audioScore >= 0.35f),
                reason = "Intimate Kissing / Bed Scene",
                confidence = max(nsfwProb, 0.58f),
                isKissOrBedCue = true
            )
        }

        // 5. Visual-first policy: audio triggers ONLY when visual frame is unclear (dark, occluded, missing, or borderline)
        val hasVocalLabel = !audioLabel.isNullOrBlank()
        val visualUnclear = isVisualUnclearForAudio(
            sceneSafety = sceneSafety,
            hasSemiNude = hasSemiNude,
            kissOrBedCue = kissOrBedCue,
            clipKissProb = clipKissProb,
            clipMargin = clipMargin,
            hasFreshVisual = hasFreshVisual
        )
        if (hasVocalLabel && visualUnclear && (audioScore >= 0.55f || (audioScore >= 0.35f && (nsfwProb >= 0.25f || hasSemiNude || kissOrBedCue || continuingIntimateScene)))) {
            return IntimateSignals(
                triggered = true,
                instant = continuingIntimateScene,
                reason = "Intimate Audio ($audioLabel)",
                confidence = max(audioScore, nsfwProb),
                isKissOrBedCue = kissOrBedCue
            )
        }

        return IntimateSignals(
            triggered = false,
            instant = false,
            reason = "",
            confidence = max(nsfwProb, audioScore),
            isKissOrBedCue = kissOrBedCue
        )
    }
}

/**
 * Zero-battery, zero-Screen-Cast subtitle/CC cue detector reading explicit stage captions
 * (e.g., "[moaning]", "[kissing]", "(groaning)") from Accessibility text nodes.
 */
object IntimateCaptionRules {
    private val CAPTION_REGEX = Regex(
        """[\[(]\s*(?:woman|girl|female|man|couple|both)?\s*(?:moaning|moans|groaning|groans|panting|heavy breathing|kissing|making out|sexual\s+\w+|orgasm)\s*[\])]""",
        RegexOption.IGNORE_CASE
    )

    fun detectIntimateCaption(screenText: String): String? {
        if (screenText.isBlank()) return null
        val match = CAPTION_REGEX.find(screenText) ?: return null
        return match.value.trim()
    }
}

/**
 * Lightweight on-device SwiftFormer image safety classifier (`image_safety_xs.onnx`)
 * returning `[NSFL, NSFW, SFW]` probabilities for whole-frame intimate/bed scene awareness.
 */
class IntimateSceneDetector(context: Context) {

    companion object {
        private const val TAG = "IntimateSceneDetector"
        private const val MODEL_FILE = "image_safety_xs.onnx"
        private const val INPUT_SIZE = 224
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val mutex = Mutex()

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
    }

    suspend fun classify(bitmap: Bitmap): SceneSafetyScores? = withContext(Dispatchers.Default) {
        try {
            val tensorBuffer = bitmap.toImageNetBuffer()
            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val probs = mutex.withLock {
                val inputName = session.inputNames.firstOrNull() ?: "image"
                val tensor = OnnxTensor.createTensor(env, tensorBuffer, shape)
                val result = session.run(mapOf(inputName to tensor))
                try {
                    val out = result[0].value as? Array<*> ?: return@withLock null
                    val row = out.firstOrNull() as? FloatArray ?: return@withLock null
                    if (row.size >= 3) SceneSafetyScores(row[0], row[1], row[2]) else null
                } finally {
                    result.close()
                    tensor.close()
                }
            }
            probs
        } catch (e: Exception) {
            Log.w(TAG, "Scene safety inference error", e)
            null
        }
    }

    private fun Bitmap.toImageNetBuffer(): FloatBuffer {
        val resized = if (width == INPUT_SIZE && height == INPUT_SIZE) {
            this
        } else {
            Bitmap.createScaledBitmap(this, INPUT_SIZE, INPUT_SIZE, true)
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized !== this) {
            resized.recycle()
        }

        val channelSize = INPUT_SIZE * INPUT_SIZE
        val byteBuffer = ByteBuffer.allocateDirect(3 * channelSize * 4)
            .order(ByteOrder.nativeOrder())
        val buf = byteBuffer.asFloatBuffer()
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (((px shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            val g = (((px shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            val b = ((px and 0xFF) / 255f - MEAN[2]) / STD[2]
            buf.put(i, r)
            buf.put(i + channelSize, g)
            buf.put(i + 2 * channelSize, b)
        }
        buf.rewind()
        return buf
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
    }
}
