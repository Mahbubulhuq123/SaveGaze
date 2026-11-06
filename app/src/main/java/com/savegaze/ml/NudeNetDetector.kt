package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.savegaze.util.toFloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NudeNetDetector(
    context: Context,
    var confidenceThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "NudeNetDetector"
        private const val MODEL_FILE = "best_320s.onnx"
        private const val INPUT_NAME = "images"
        private const val OUTPUT_NAME = "output0"
        private const val MODEL_WIDTH = 320
        private const val MODEL_HEIGHT = 320
        private const val NUM_CHANNELS = 3
        private const val NUM_CLASSES = 18
        private const val NUM_PREDICTIONS = 2100

        private val NSFW_LABELS: Set<NudeLabel> = setOf(
            NudeLabel.FEMALE_BREAST_EXPOSED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED,
            NudeLabel.MALE_GENITALIA_EXPOSED,
            NudeLabel.MALE_BREAST_EXPOSED,
            NudeLabel.BUTTOCKS_EXPOSED,
            NudeLabel.ANUS_EXPOSED,
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.ANUS_COVERED,
            NudeLabel.BELLY_EXPOSED,
            NudeLabel.ARMPITS_EXPOSED
        )
    }

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val mutex = Mutex()
    private var isClosed = false

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        ortSession = ortEnvironment.createSession(modelBytes, sessionOptions)
        Log.d(TAG, "NudeNet model loaded successfully")
    }

    suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (isClosed) return@withContext emptyList()

            val originalWidth = bitmap.width.toFloat()
            val originalHeight = bitmap.height.toFloat()

            val floatBuffer = bitmap.toFloatBuffer(MODEL_WIDTH, MODEL_HEIGHT)
            val shape = longArrayOf(1, NUM_CHANNELS.toLong(), MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
            var inputTensor: OnnxTensor? = null
            try {
                inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
                val inputs = mapOf(INPUT_NAME to inputTensor)
                val results = ortSession.run(inputs)

                val outputTensor = results.get(OUTPUT_NAME)
                if (!outputTensor.isPresent) {
                    results.close()
                    return@withContext emptyList()
                }

                @Suppress("UNCHECKED_CAST")
                val rawOutput = outputTensor.get().value as Array<Array<FloatArray>>
                val detections = postProcess(rawOutput[0], originalWidth, originalHeight, offsetY = 0f)
                results.close()
                nms(detections)
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                emptyList()
            } finally {
                inputTensor?.close()
            }
        }
    }

    private fun postProcess(
        output: Array<FloatArray>,
        originalWidth: Float,
        tileHeight: Float,
        offsetY: Float = 0f
    ): List<Detection> {
        val scaleX = originalWidth / MODEL_WIDTH
        val scaleY = tileHeight / MODEL_HEIGHT

        val candidates = mutableListOf<Detection>()

        for (i in 0 until NUM_PREDICTIONS) {
            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            var bestClassIdx = 0
            var bestClassScore = output[4][i]
            for (c in 1 until NUM_CLASSES) {
                val score = output[4 + c][i]
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClassIdx = c
                }
            }

            // Clean face gender check: FACE_FEMALE is 1, FACE_MALE is 12
            val femaleFaceScore = output[4 + 1][i]
            val maleFaceScore = output[4 + 12][i]
            if (bestClassIdx == 1 && maleFaceScore > femaleFaceScore) {
                bestClassIdx = 12
                bestClassScore = maleFaceScore
            } else if (bestClassIdx == 12 && femaleFaceScore > maleFaceScore) {
                bestClassIdx = 1
                bestClassScore = femaleFaceScore
            }

            val label = NudeLabel.fromIndex(bestClassIdx) ?: continue

            val minThreshold = when (label) {
                NudeLabel.FACE_FEMALE,
                NudeLabel.FACE_MALE -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.FEMALE_BREAST_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_EXPOSED,
                NudeLabel.BUTTOCKS_EXPOSED,
                NudeLabel.ANUS_EXPOSED -> minOf(confidenceThreshold, 0.15f)
                NudeLabel.MALE_BREAST_EXPOSED,
                NudeLabel.MALE_GENITALIA_EXPOSED -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.BELLY_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_COVERED,
                NudeLabel.BUTTOCKS_COVERED,
                NudeLabel.ARMPITS_EXPOSED -> minOf(confidenceThreshold, 0.18f)
                // Require solid confidence for covered chest so normal men's/women's shirts aren't falsely flagged
                NudeLabel.FEMALE_BREAST_COVERED -> maxOf(confidenceThreshold, 0.28f)
                else -> confidenceThreshold
            }
            if (bestClassScore < minThreshold) continue

            val (padW, padH) = getLabelPadding(label, w, h)

            val x1 = (cx - w / 2f - padW) * scaleX
            val y1 = (cy - h / 2f - padH) * scaleY + offsetY
            val x2 = (cx + w / 2f + padW) * scaleX
            val y2 = (cy + h / 2f + padH) * scaleY + offsetY

            val box = BoundingBox(
                x1 = x1.coerceIn(0f, originalWidth),
                y1 = y1.coerceIn(0f, tileHeight),
                x2 = x2.coerceIn(0f, originalWidth),
                y2 = y2.coerceIn(0f, tileHeight)
            )

            if (box.width > 4f && box.height > 4f) {
                candidates.add(Detection(label, bestClassScore, box))
            }
        }

        return candidates
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val grouped = detections.groupBy { it.label }
        val result = mutableListOf<Detection>()

        for ((_, classDets) in grouped) {
            val sorted = classDets.sortedByDescending { it.confidence }.toMutableList()
            val kept = mutableListOf<Detection>()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { other -> best.box.iou(other.box) > iouThreshold }
            }
            result.addAll(kept)
        }
        return result
    }

    enum class ClusterGender {
        FEMALE,
        MALE,
        UNKNOWN
    }

    private class PersonCluster {
        val faces = mutableListOf<Detection>()
        val intimateDetections = mutableListOf<Detection>()

        val allDetections: List<Detection> get() = faces + intimateDetections
        val minX: Float get() = if (allDetections.isEmpty()) 0f else allDetections.minOf { it.box.x1 }
        val maxX: Float get() = if (allDetections.isEmpty()) 0f else allDetections.maxOf { it.box.x2 }
        val minY: Float get() = if (allDetections.isEmpty()) 0f else allDetections.minOf { it.box.y1 }
        val maxY: Float get() = if (allDetections.isEmpty()) 0f else allDetections.maxOf { it.box.y2 }
        val width: Float get() = maxOf(maxX - minX, 1f)
        val height: Float get() = maxOf(maxY - minY, 1f)
        val centerX: Float get() = if (allDetections.isEmpty()) 0f else allDetections.map { it.box.centerX }.average().toFloat()

        fun inferGender(
            sceneHasFemaleFace: Boolean,
            sceneHasMaleFace: Boolean,
            sceneHasFemaleExposed: Boolean,
            sceneHasMaleSpecific: Boolean
        ): ClusterGender {
            val hasFemaleFace = faces.any { it.label == NudeLabel.FACE_FEMALE }
            val hasMaleFace = faces.any { it.label == NudeLabel.FACE_MALE }

            val hasBareFemaleAnatomy = intimateDetections.any {
                (it.label == NudeLabel.FEMALE_BREAST_EXPOSED && it.confidence >= 0.35f) ||
                (it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED && it.confidence >= 0.35f)
            }
            val hasMaleAnatomy = intimateDetections.any {
                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                it.label == NudeLabel.MALE_GENITALIA_EXPOSED
            }

            // 1. Confirmed MALE face or male anatomy takes strict precedence over covered-shirt false positives!
            if (hasMaleAnatomy && !hasBareFemaleAnatomy) return ClusterGender.MALE
            if (hasMaleFace && !hasFemaleFace && !hasBareFemaleAnatomy) return ClusterGender.MALE

            // 2. Confirmed FEMALE face or bare female anatomy
            if (hasFemaleFace && !hasMaleFace) return ClusterGender.FEMALE
            if (hasBareFemaleAnatomy && !hasMaleAnatomy) return ClusterGender.FEMALE

            // 3. Covered female swimwear/lingerie when no male face is attached
            val hasFemaleCovered = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_COVERED ||
                it.label == NudeLabel.FEMALE_GENITALIA_COVERED
            }
            if (hasFemaleCovered && !hasMaleFace && !hasMaleAnatomy) {
                if (sceneHasMaleFace && !sceneHasFemaleFace) return ClusterGender.MALE
                return ClusterGender.FEMALE
            }

            // 4. Scene-level fallback for headless crops
            if (sceneHasMaleFace && !sceneHasFemaleFace && !sceneHasFemaleExposed) return ClusterGender.MALE
            if (sceneHasFemaleFace && !sceneHasMaleFace && !sceneHasMaleSpecific) return ClusterGender.FEMALE

            return ClusterGender.UNKNOWN
        }
    }

    fun filterDetections(
        detections: List<Detection>,
        genderFilter: GenderFilter = GenderFilter.BOTH,
        blurFullBody: Boolean = false
    ): FilteredDetections {
        val allFaces = detections.filter { it.label.isFace }
        val maleFaces = allFaces.filter { it.label == NudeLabel.FACE_MALE }
        val femaleFaces = allFaces.filter { it.label == NudeLabel.FACE_FEMALE }

        val nsfwParts = detections.filter { det ->
            det.label in NSFW_LABELS && allFaces.none { face -> det.box.iou(face.box) > 0.40f }
        }

        if (nsfwParts.isEmpty()) {
            return FilteredDetections(
                toBlur = emptyList(),
                faces = allFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        val sceneHasFemaleFace = femaleFaces.isNotEmpty()
        val sceneHasMaleFace = maleFaces.isNotEmpty()
        val sceneHasFemaleExposed = nsfwParts.any {
            it.label == NudeLabel.FEMALE_BREAST_EXPOSED || it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED
        }
        val sceneHasMaleSpecific = nsfwParts.any { it.label.isFemale == false }

        val clusters = mutableListOf<PersonCluster>()
        for (face in allFaces) {
            clusters.add(PersonCluster().apply { faces.add(face) })
        }

        for (det in nsfwParts) {
            val box = det.box
            var bestCluster: PersonCluster? = null
            var minDist = Float.MAX_VALUE

            for (c in clusters) {
                val dist = kotlin.math.abs(box.centerX - c.centerX)
                val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                val allowDist = maxOf(c.width * 0.95f, box.width * 0.95f, faceW * 1.65f)
                val faceBottom = c.faces.maxOfOrNull { it.box.y2 }
                val verticalOk = faceBottom == null || box.y2 >= faceBottom - 40f

                if (verticalOk && dist < allowDist && dist < minDist) {
                    minDist = dist
                    bestCluster = c
                }
            }

            val target = bestCluster ?: PersonCluster().also { clusters.add(it) }
            target.intimateDetections.add(det)
        }

        val keptClusters = clusters.filter { c ->
            if (c.intimateDetections.isEmpty()) return@filter false

            // Reject ordinary modest shirts when a face is present and there is no exposed skin
            val hasExposedSkin = c.intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                it.label == NudeLabel.BELLY_EXPOSED ||
                it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED ||
                it.label == NudeLabel.MALE_GENITALIA_EXPOSED ||
                it.label == NudeLabel.FEMALE_GENITALIA_COVERED ||
                it.label == NudeLabel.BUTTOCKS_COVERED ||
                it.label == NudeLabel.ANUS_EXPOSED
            }
            if (c.faces.isNotEmpty() && !hasExposedSkin &&
                c.intimateDetections.all { it.label == NudeLabel.FEMALE_BREAST_COVERED && it.confidence < 0.36f }) {
                return@filter false
            }

            val gender = c.inferGender(
                sceneHasFemaleFace,
                sceneHasMaleFace,
                sceneHasFemaleExposed,
                sceneHasMaleSpecific
            )

            when (genderFilter) {
                GenderFilter.BOTH -> true
                GenderFilter.FEMALE_ONLY -> {
                    gender == ClusterGender.FEMALE ||
                    (gender == ClusterGender.UNKNOWN && !sceneHasMaleFace && !sceneHasMaleSpecific)
                }
                GenderFilter.MALE_ONLY -> {
                    gender == ClusterGender.MALE ||
                    (gender == ClusterGender.UNKNOWN && !sceneHasFemaleFace && !sceneHasFemaleExposed)
                }
            }
        }

        // Filter out any individual detection that sits directly beneath a confirmed opposite-gender face
        val filteredParts = keptClusters.flatMap { it.intimateDetections }.filter { det ->
            when (genderFilter) {
                GenderFilter.FEMALE_ONLY -> {
                    val underMaleFace = maleFaces.any { m ->
                        val bodyTop = m.box.y1 - 10f
                        val bodyBottom = m.box.y2 + m.box.height * 6.0f
                        val colLeft = m.box.centerX - m.box.width * 1.35f
                        val colRight = m.box.centerX + m.box.width * 1.35f
                        val closerFemale = femaleFaces.any { f ->
                            kotlin.math.abs(f.box.centerX - det.box.centerX) < kotlin.math.abs(m.box.centerX - det.box.centerX)
                        }
                        !closerFemale && det.box.centerY in bodyTop..bodyBottom && det.box.centerX in colLeft..colRight
                    }
                    !underMaleFace
                }
                GenderFilter.MALE_ONLY -> {
                    val underFemaleFace = femaleFaces.any { f ->
                        val bodyTop = f.box.y1 - 10f
                        val bodyBottom = f.box.y2 + f.box.height * 6.0f
                        val colLeft = f.box.centerX - f.box.width * 1.35f
                        val colRight = f.box.centerX + f.box.width * 1.35f
                        val closerMale = maleFaces.any { m ->
                            kotlin.math.abs(m.box.centerX - det.box.centerX) < kotlin.math.abs(f.box.centerX - det.box.centerX)
                        }
                        !closerMale && det.box.centerY in bodyTop..bodyBottom && det.box.centerX in colLeft..colRight
                    }
                    !underFemaleFace
                }
                GenderFilter.BOTH -> true
            }
        }

        if (filteredParts.isEmpty()) {
            return FilteredDetections(
                toBlur = emptyList(),
                faces = allFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        // Build tight, accurate blur boxes around the actual detected body parts (never crossing into empty screen space)
        val rawBoxes = filteredParts.map { det ->
            var topY = det.box.y1
            // Never allow a body blur box to creep up over a nearby face
            for (face in allFaces) {
                if (kotlin.math.abs(face.box.centerX - det.box.centerX) < maxOf(face.box.width, det.box.width)) {
                    if (topY < face.box.y2 && det.box.y2 > face.box.y2) {
                        topY = maxOf(topY, face.box.y2 - 2f)
                    }
                }
            }
            BoundingBox(
                x1 = det.box.x1,
                y1 = topY,
                x2 = det.box.x2,
                y2 = det.box.y2
            )
        }.filter { it.width > 6f && it.height > 6f }

        val finalBoxes = mergeAdjacentBoxes(rawBoxes)

        return FilteredDetections(
            toBlur = filteredParts,
            faces = allFaces,
            hasNsfwContent = finalBoxes.isNotEmpty(),
            mergedBlurBoxes = finalBoxes
        )
    }

    private fun mergeAdjacentBoxes(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 1) return boxes
        val remaining = boxes.toMutableList()
        val merged = mutableListOf<BoundingBox>()

        while (remaining.isNotEmpty()) {
            var curr = remaining.removeAt(0)
            var changed = true
            while (changed) {
                changed = false
                var i = 0
                while (i < remaining.size) {
                    val cand = remaining[i]
                    val ox = minOf(curr.x2, cand.x2) - maxOf(curr.x1, cand.x1)
                    val oy = minOf(curr.y2, cand.y2) - maxOf(curr.y1, cand.y1)
                    val iou = curr.iou(cand)
                    // Only merge boxes that genuinely overlap or directly touch on the same torso
                    val shouldMerge = (ox > 0f && oy >= -6f) && (iou > 0.12f || ox > minOf(curr.width, cand.width) * 0.50f)
                    if (shouldMerge) {
                        curr = BoundingBox(
                            minOf(curr.x1, cand.x1),
                            minOf(curr.y1, cand.y1),
                            maxOf(curr.x2, cand.x2),
                            maxOf(curr.y2, cand.y2)
                        )
                        remaining.removeAt(i)
                        changed = true
                    } else {
                        i++
                    }
                }
            }
            merged.add(curr)
        }
        return merged
    }

    private fun getLabelPadding(label: NudeLabel, w: Float, h: Float): Pair<Float, Float> {
        // Snug padding so blur covers only the body part and never bleeds outside the person or video frame
        return when (label) {
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.FEMALE_BREAST_EXPOSED -> Pair(w * 0.10f, h * 0.10f)

            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED -> Pair(w * 0.10f, h * 0.10f)

            NudeLabel.BELLY_EXPOSED -> Pair(w * 0.08f, h * 0.08f)

            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED -> Pair(w * 0.10f, h * 0.10f)

            NudeLabel.ARMPITS_EXPOSED -> Pair(w * 0.06f, h * 0.06f)

            NudeLabel.MALE_BREAST_EXPOSED,
            NudeLabel.MALE_GENITALIA_EXPOSED -> Pair(w * 0.08f, h * 0.08f)

            NudeLabel.ANUS_COVERED,
            NudeLabel.ANUS_EXPOSED -> Pair(w * 0.08f, h * 0.08f)

            NudeLabel.FACE_FEMALE,
            NudeLabel.FACE_MALE -> Pair(0f, 0f)

            else -> Pair(w * 0.05f, h * 0.05f)
        }
    }

    fun close() {
        isClosed = true
        try {
            ortSession.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing OrtSession", e)
        }
        try {
            ortEnvironment.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing OrtEnvironment", e)
        }
    }
}
