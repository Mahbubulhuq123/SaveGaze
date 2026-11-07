package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.savegaze.util.findMediaContainerBottom
import com.savegaze.util.getSkinFraction
import com.savegaze.util.hasHumanSkin
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
            NudeLabel.ARMPITS_EXPOSED,
            NudeLabel.FEET_EXPOSED
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

    /**
     * Executes multi-scale, aspect-ratio aware detection.
     * For tall mobile displays (height / width > 1.30), runs dual-tile scanning (top & bottom halves)
     * plus a global pass so small feed thumbnails (e.g. YouTube Music, reels) retain high resolution
     * and normal proportions instead of being squished vertically by 7.5x.
     */
    suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (isClosed) return@withContext emptyList()

            val originalWidth = bitmap.width.toFloat()
            val originalHeight = bitmap.height.toFloat()
            val aspectRatio = originalHeight / originalWidth

            val allDetections = mutableListOf<Detection>()

            // 1. Global pass (entire screen)
            allDetections.addAll(runInference(bitmap, 0f, originalWidth, originalHeight))

            // 2. Tri-tile pass for tall mobile displays to preserve thumbnail clarity and anatomical proportions
            // (Resolves Case 6 / Screenshot 1: small cards in YouTube Music, reels, and feeds)
            if (aspectRatio > 1.30f && originalHeight > 600f) {
                val tileH = (originalHeight * 0.44f).toInt()
                val midTop = (originalHeight * 0.28f).toInt()
                val botTop = (originalHeight * 0.56f).toInt()
                val botH = (originalHeight - botTop).toInt()

                // Top Tile (0% to 44%)
                try {
                    val t1 = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, tileH)
                    val dets1 = runInference(t1, 0f, originalWidth, tileH.toFloat())
                    t1.recycle()
                    allDetections.addAll(dets1)
                } catch (e: Exception) {
                    Log.w(TAG, "Tile 1 inference skipped", e)
                }

                // Middle Tile (28% to 72% - where YouTube Music cards & social video reels reside)
                try {
                    val t2 = Bitmap.createBitmap(bitmap, 0, midTop, bitmap.width, tileH)
                    val dets2 = runInference(t2, midTop.toFloat(), originalWidth, tileH.toFloat())
                    t2.recycle()
                    allDetections.addAll(dets2)
                } catch (e: Exception) {
                    Log.w(TAG, "Tile 2 inference skipped", e)
                }

                // Bottom Tile (56% to 100%)
                try {
                    val t3 = Bitmap.createBitmap(bitmap, 0, botTop, bitmap.width, botH)
                    val dets3 = runInference(t3, botTop.toFloat(), originalWidth, botH.toFloat())
                    t3.recycle()
                    allDetections.addAll(dets3)
                } catch (e: Exception) {
                    Log.w(TAG, "Tile 3 inference skipped", e)
                }
            }

            // Consolidate detections via NMS
            val consolidated = nms(allDetections)
            val allFaces = consolidated.filter { it.label.isFace }

            // Validate anatomical body parts against human skin chrominance
            // Completely rejects inanimate objects (onions, pillows, furniture wood carvings, walls)
            val validated = consolidated.filter { det ->
                if (det.label.isFace) {
                    true
                } else if (det.label.isExposed) {
                    val hasNearFace = allFaces.any { f ->
                        kotlin.math.abs(f.box.centerX - det.box.centerX) < maxOf(f.box.width * 2.5f, det.box.width * 1.5f) &&
                        det.box.y1 >= f.box.y1 - 10f && det.box.y1 <= f.box.y2 + f.box.height * 6.0f
                    }
                    bitmap.hasHumanSkin(
                        det.box,
                        minFraction = if (hasNearFace) 0.05f else 0.15f,
                        hasNearFace = hasNearFace
                    )
                } else {
                    // For covered clothing: if headless, ensure it does NOT match red onion skin
                    if (allFaces.isEmpty()) {
                        bitmap.hasHumanSkin(det.box, minFraction = 0.08f, hasNearFace = false)
                    } else {
                        true
                    }
                }
            }

            validated
        }
    }

    private fun runInference(
        tileBitmap: Bitmap,
        offsetY: Float,
        originalWidth: Float,
        tileHeight: Float
    ): List<Detection> {
        val floatBuffer = tileBitmap.toFloatBuffer(MODEL_WIDTH, MODEL_HEIGHT)
        val shape = longArrayOf(1, NUM_CHANNELS.toLong(), MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
            val inputs = mapOf(INPUT_NAME to inputTensor)
            val results = ortSession.run(inputs)

            val outputTensor = results.get(OUTPUT_NAME)
            if (!outputTensor.isPresent) {
                results.close()
                return emptyList()
            }

            @Suppress("UNCHECKED_CAST")
            val rawOutput = outputTensor.get().value as Array<Array<FloatArray>>
            val detections = postProcess(rawOutput[0], originalWidth, tileHeight, offsetY)
            results.close()
            detections
        } catch (e: Exception) {
            Log.e(TAG, "Inference pass failed", e)
            emptyList()
        } finally {
            inputTensor?.close()
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

            // Gender protection for faces: FACE_FEMALE is 1, FACE_MALE is 12
            // If male face score shows masculine features (>= 55% of female, or absolute score >= 0.14),
            // prioritize MALE so men (e.g. Javed Ali, video call reels) are NEVER falsely classified as female!
            val femaleFaceScore = output[4 + 1][i]
            val maleFaceScore = output[4 + 12][i]
            if (bestClassIdx == 1 && (maleFaceScore >= femaleFaceScore * 0.55f || maleFaceScore >= 0.14f)) {
                bestClassIdx = 12
                bestClassScore = maxOf(maleFaceScore, bestClassScore)
            }

            val label = NudeLabel.fromIndex(bestClassIdx) ?: continue

            val minThreshold = when (label) {
                NudeLabel.FACE_FEMALE -> 0.20f
                NudeLabel.FACE_MALE -> 0.16f
                NudeLabel.FEMALE_BREAST_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_EXPOSED,
                NudeLabel.BUTTOCKS_EXPOSED,
                NudeLabel.ANUS_EXPOSED -> minOf(confidenceThreshold, 0.14f)
                NudeLabel.FEET_EXPOSED -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.ARMPITS_EXPOSED,
                NudeLabel.BELLY_EXPOSED -> minOf(confidenceThreshold, 0.15f)
                NudeLabel.MALE_BREAST_EXPOSED,
                NudeLabel.MALE_GENITALIA_EXPOSED -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.FEMALE_GENITALIA_COVERED,
                NudeLabel.BUTTOCKS_COVERED -> minOf(confidenceThreshold, 0.20f)
                NudeLabel.FEMALE_BREAST_COVERED -> minOf(confidenceThreshold, 0.22f)
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
                y1 = y1.coerceIn(0f, originalWidth * 5f), // bounded by full screen
                x2 = x2.coerceIn(0f, originalWidth),
                y2 = y2.coerceIn(0f, originalWidth * 5f)
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
                (it.label == NudeLabel.FEMALE_BREAST_EXPOSED && it.confidence >= 0.28f) ||
                (it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED && it.confidence >= 0.28f)
            }
            val hasMaleAnatomy = intimateDetections.any {
                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                it.label == NudeLabel.MALE_GENITALIA_EXPOSED
            }

            // 1. Confirmed MALE face or male anatomy takes strict precedence over false positives!
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
                // Headless covered detections (pillows, onions, blankets) must NEVER be inferred as FEMALE!
                if (faces.isEmpty()) {
                    if (hasBareFemaleAnatomy) return ClusterGender.FEMALE
                    return ClusterGender.UNKNOWN
                }
                if (sceneHasMaleFace && !sceneHasFemaleFace) return ClusterGender.MALE
                return ClusterGender.FEMALE
            }

            // 4. Headless scene fallback: only assign if confident bare female anatomy exists
            if (hasBareFemaleAnatomy) return ClusterGender.FEMALE

            return ClusterGender.UNKNOWN
        }
    }

    fun filterDetections(
        detections: List<Detection>,
        genderFilter: GenderFilter = GenderFilter.BOTH,
        blurFullBody: Boolean = false,
        bitmap: Bitmap? = null
    ): FilteredDetections {
        val allFaces = detections.filter { it.label.isFace }
        val maleFaces = allFaces.filter { it.label == NudeLabel.FACE_MALE }
        val femaleFaces = allFaces.filter { it.label == NudeLabel.FACE_FEMALE }

        val nsfwParts = detections.filter { det ->
            det.label in NSFW_LABELS && allFaces.none { face -> det.box.iou(face.box) > 0.35f }
        }

        if (nsfwParts.isEmpty()) {
            return FilteredDetections(
                toBlur = emptyList(),
                faces = allFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        // Validate intimate parts against real human skin chrominance:
        // Modest clothed shirts/polos (< 14% skin) on men or women are NEVER treated as intimate nudity!
        val validNsfwParts = if (bitmap != null) {
            nsfwParts.filter { det ->
                if (det.label == NudeLabel.FEMALE_BREAST_COVERED) {
                    val skinFrac = bitmap.getSkinFraction(det.box)
                    skinFrac >= 0.14f
                } else if (det.label == NudeLabel.MALE_BREAST_EXPOSED) {
                    val skinFrac = bitmap.getSkinFraction(det.box)
                    skinFrac >= 0.22f
                } else {
                    true
                }
            }
        } else {
            nsfwParts
        }

        if (validNsfwParts.isEmpty()) {
            return FilteredDetections(
                toBlur = emptyList(),
                faces = allFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        val sceneHasFemaleFace = femaleFaces.isNotEmpty()
        val sceneHasMaleFace = maleFaces.isNotEmpty()
        val sceneHasFemaleExposed = validNsfwParts.any {
            it.label == NudeLabel.FEMALE_BREAST_EXPOSED || it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED
        }
        val sceneHasMaleSpecific = validNsfwParts.any { it.label.isFemale == false }

        val clusters = mutableListOf<PersonCluster>()
        for (face in allFaces) {
            clusters.add(PersonCluster().apply { faces.add(face) })
        }

        for (det in validNsfwParts) {
            val box = det.box
            var bestCluster: PersonCluster? = null
            var minDist = Float.MAX_VALUE

            for (c in clusters) {
                val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                val faceBottom = c.faces.maxOfOrNull { it.box.y2 } ?: 0f
                val faceTop = c.faces.minOfOrNull { it.box.y1 } ?: 0f
                val faceH = c.faces.maxOfOrNull { it.box.height } ?: c.height

                val vertDist = box.y1 - faceBottom
                val isBelowFace = box.y1 >= faceTop - 20f
                val isWithinTorsoReach = vertDist <= faceH * 5.0f
                val horizDist = kotlin.math.abs(box.centerX - c.centerX)
                val allowHorizDist = maxOf(c.width * 1.1f, box.width * 1.1f, faceW * 1.8f)

                if (isBelowFace && isWithinTorsoReach && horizDist < allowHorizDist && horizDist < minDist) {
                    minDist = horizDist
                    bestCluster = c
                }
            }

            val target = bestCluster ?: PersonCluster().also { clusters.add(it) }
            target.intimateDetections.add(det)
        }

        // Correct face classifications based on confident cluster anatomy:
        // If a person cluster has confirmed male anatomy (MALE_BREAST_EXPOSED or MALE_GENITALIA_EXPOSED),
        // or is determined to be ClusterGender.MALE: any face in that cluster is a MALE face!
        // This permanently corrects NudeNet's bias of predicting FACE_FEMALE on men.
        val correctedFaces = allFaces.map { face ->
            val parentCluster = clusters.firstOrNull { it.faces.contains(face) }
            if (parentCluster != null) {
                val hasMaleAnat = parentCluster.intimateDetections.any {
                    it.label == NudeLabel.MALE_BREAST_EXPOSED || it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                }
                val hasBareFemAnat = parentCluster.intimateDetections.any {
                    (it.label == NudeLabel.FEMALE_BREAST_EXPOSED || it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED) &&
                    it.confidence >= 0.28f
                }
                if (hasMaleAnat && !hasBareFemAnat && face.label == NudeLabel.FACE_FEMALE) {
                    face.copy(label = NudeLabel.FACE_MALE)
                } else if (hasBareFemAnat && !hasMaleAnat && face.label == NudeLabel.FACE_MALE) {
                    face.copy(label = NudeLabel.FACE_FEMALE)
                } else {
                    face
                }
            } else {
                face
            }
        }

        // Update face references in clusters
        for (c in clusters) {
            val updatedClusterFaces = c.faces.map { f -> correctedFaces.firstOrNull { it.box == f.box } ?: f }
            c.faces.clear()
            c.faces.addAll(updatedClusterFaces)
        }

        val updatedMaleFaces = correctedFaces.filter { it.label == NudeLabel.FACE_MALE }
        val updatedFemaleFaces = correctedFaces.filter { it.label == NudeLabel.FACE_FEMALE }

        val keptClusters = clusters.filter { c ->
            if (c.intimateDetections.isEmpty()) return@filter false

            val hasMaleFace = c.faces.any { it.label == NudeLabel.FACE_MALE }
            val hasFemaleFace = c.faces.any { it.label == NudeLabel.FACE_FEMALE }

            val gender = c.inferGender(
                sceneHasFemaleFace,
                sceneHasMaleFace,
                sceneHasFemaleExposed,
                sceneHasMaleSpecific
            )

            when (genderFilter) {
                GenderFilter.BOTH -> {
                    if (gender == ClusterGender.MALE || (hasMaleFace && !hasFemaleFace)) {
                        val isShirtless = c.intimateDetections.any {
                            it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                            it.label == NudeLabel.BELLY_EXPOSED ||
                            it.label == NudeLabel.ARMPITS_EXPOSED
                        }
                        val isShorts = c.intimateDetections.any {
                            it.label == NudeLabel.FEET_EXPOSED ||
                            it.label == NudeLabel.BUTTOCKS_COVERED ||
                            it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                            it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                        }
                        isShirtless || isShorts
                    } else {
                        true
                    }
                }
                GenderFilter.FEMALE_ONLY -> {
                    if (gender == ClusterGender.MALE || (hasMaleFace && !hasFemaleFace)) return@filter false
                    if (gender == ClusterGender.FEMALE || hasFemaleFace) {
                        val hasIntimateOrExposed = c.intimateDetections.any {
                            it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                            it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED ||
                            it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                            it.label == NudeLabel.ANUS_EXPOSED ||
                            it.label == NudeLabel.BELLY_EXPOSED ||
                            it.label == NudeLabel.ARMPITS_EXPOSED ||
                            it.label == NudeLabel.FEET_EXPOSED ||
                            it.label == NudeLabel.FEMALE_GENITALIA_COVERED ||
                            it.label == NudeLabel.BUTTOCKS_COVERED ||
                            it.label == NudeLabel.FEMALE_BREAST_COVERED
                        }
                        return@filter hasIntimateOrExposed
                    }
                    val hasFemaleSpecific = c.intimateDetections.any {
                        it.label.isFemale == true && it.confidence >= 0.25f
                    }
                    val hasBackside = c.intimateDetections.any {
                        (it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                         it.label == NudeLabel.BUTTOCKS_COVERED ||
                         it.label == NudeLabel.ANUS_EXPOSED ||
                         it.label == NudeLabel.ANUS_COVERED) && it.confidence >= 0.25f
                    }
                    val hasMaleAnat = c.intimateDetections.any {
                        it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                        it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                    }
                    (hasFemaleSpecific || hasBackside) && !hasMaleAnat
                }
                GenderFilter.MALE_ONLY -> {
                    if (gender == ClusterGender.FEMALE) return@filter false
                    if (hasFemaleFace && !hasMaleFace && gender != ClusterGender.MALE) return@filter false

                    val isShirtless = c.intimateDetections.any {
                        it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                        it.label == NudeLabel.BELLY_EXPOSED ||
                        it.label == NudeLabel.ARMPITS_EXPOSED
                    }
                    val isShorts = c.intimateDetections.any {
                        it.label == NudeLabel.FEET_EXPOSED ||
                        it.label == NudeLabel.BUTTOCKS_COVERED ||
                        it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                        it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                    }
                    val hasMaleAnat = c.intimateDetections.any {
                        it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                        it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                    }
                    (isShirtless || isShorts) && (gender == ClusterGender.MALE || hasMaleFace || hasMaleAnat || (!hasFemaleFace && (isShirtless || isShorts)))
                }
            }
        }

        // Filter individual detections under opposite-gender faces
        val filteredParts = keptClusters.flatMap { it.intimateDetections }.filter { det ->
            when (genderFilter) {
                GenderFilter.FEMALE_ONLY -> {
                    val underMaleFace = updatedMaleFaces.any { m ->
                        val bodyTop = m.box.y1 - 10f
                        val bodyBottom = m.box.y2 + m.box.height * 5.0f
                        val colLeft = m.box.centerX - m.box.width * 1.2f
                        val colRight = m.box.centerX + m.box.width * 1.2f
                        val closerFemale = updatedFemaleFaces.any { f ->
                            val distF = kotlin.math.hypot(f.box.centerX - det.box.centerX, f.box.y2 - det.box.y1)
                            val distM = kotlin.math.hypot(m.box.centerX - det.box.centerX, m.box.y2 - det.box.y1)
                            distF < distM
                        }
                        !closerFemale && det.box.centerY in bodyTop..bodyBottom && det.box.centerX in colLeft..colRight
                    }
                    !underMaleFace
                }
                GenderFilter.MALE_ONLY -> {
                    val underFemaleFace = updatedFemaleFaces.any { f ->
                        val bodyTop = f.box.y1 - 10f
                        val bodyBottom = f.box.y2 + f.box.height * 5.0f
                        val colLeft = f.box.centerX - f.box.width * 1.2f
                        val colRight = f.box.centerX + f.box.width * 1.2f
                        val closerMale = updatedMaleFaces.any { m ->
                            val distM = kotlin.math.hypot(m.box.centerX - det.box.centerX, m.box.y2 - det.box.y1)
                            val distF = kotlin.math.hypot(f.box.centerX - det.box.centerX, f.box.y2 - det.box.y1)
                            distM < distF
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
                faces = correctedFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        // ── FULL BODY BLUR LOGIC (When toggled ON in Settings) ───────────────────
        if (blurFullBody) {
            val fullBodyBoxes = keptClusters.map { c ->
                val personBounds = BoundingBox(c.minX, c.minY, c.maxX, c.maxY)

                if (c.faces.isNotEmpty()) {
                    val face = c.faces.minByOrNull { it.box.y1 }!!
                    val faceW = face.box.width
                    val faceH = face.box.height

                    val personCenterX = if (c.intimateDetections.isNotEmpty()) {
                        (face.box.centerX + c.intimateDetections.map { it.box.centerX }.average().toFloat()) / 2f
                    } else {
                        face.box.centerX
                    }

                    val shoulderHalfWidth = maxOf(faceW * 1.55f, c.width * 0.70f)
                    val left = (personCenterX - shoulderHalfWidth).coerceAtLeast(0f)
                    val right = personCenterX + shoulderHalfWidth

                    // Shield starts right at the chin line (preserving face)
                    val topY = (face.box.y2 - 2f).coerceAtLeast(0f)

                    val hasLowerLegsOrFeet = c.intimateDetections.any {
                        it.label == NudeLabel.FEET_EXPOSED || it.box.y2 > face.box.y2 + faceH * 3.8f
                    }

                    val naturalBottomY = if (hasLowerLegsOrFeet) {
                        maxOf(c.maxY + 15f, face.box.y2 + faceH * 6.2f)
                    } else {
                        maxOf(c.maxY + 25f, face.box.y2 + faceH * 3.0f)
                    }

                    // Clamp to media card bottom strictly below the detected intimate parts
                    val containerBottom = if (bitmap != null) {
                        bitmap.findMediaContainerBottom(c.maxY, personBounds)
                    } else {
                        Float.MAX_VALUE
                    }

                    val bottomY = maxOf(c.maxY + 15f, minOf(naturalBottomY, containerBottom))

                    BoundingBox(left, topY, right, bottomY)
                } else {
                    // Headless or rear-view person
                    val padX = maxOf(c.width * 0.35f, 28f)
                    val left = (c.minX - padX).coerceAtLeast(0f)
                    val right = c.maxX + padX

                    val hasBacksideOnly = c.intimateDetections.any {
                        it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                        it.label == NudeLabel.BUTTOCKS_COVERED ||
                        it.label == NudeLabel.ANUS_EXPOSED
                    }
                    // If seen from behind, the back and neck extend UPWARDS from the buttocks!
                    val topY = if (hasBacksideOnly) {
                        (c.minY - c.height * 2.2f).coerceAtLeast(0f)
                    } else {
                        (c.minY - c.height * 0.25f).coerceAtLeast(0f)
                    }
                    val naturalBottomY = c.maxY + c.height * 0.40f

                    val containerBottom = if (bitmap != null) {
                        bitmap.findMediaContainerBottom(c.maxY, personBounds)
                    } else {
                        Float.MAX_VALUE
                    }

                    val bottomY = maxOf(c.maxY + 15f, minOf(naturalBottomY, containerBottom))

                    BoundingBox(left, topY, right, bottomY)
                }
            }.filter { it.width > 8f && it.height > 8f }

            val mergedFull = mergeAdjacentBoxes(fullBodyBoxes)
            return FilteredDetections(
                toBlur = filteredParts,
                faces = correctedFaces,
                hasNsfwContent = mergedFull.isNotEmpty(),
                mergedBlurBoxes = mergedFull
            )
        }

        // ── TARGETED BLUR LOGIC (When Full Body Blur is OFF) ────────────────────
        // Accurate boxes encompassing detected anatomy (back, chest, midriff, legs)
        val rawBoxes = filteredParts.map { det ->
            var topY = det.box.y1
            var bottomY = det.box.y2
            var left = det.box.x1
            var right = det.box.x2

            // If breast is exposed / low-cut: extend blur upwards to meet the neck / jawline!
            if (det.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                det.label == NudeLabel.FEMALE_BREAST_COVERED ||
                det.label == NudeLabel.MALE_BREAST_EXPOSED) {

                val faceAbove = correctedFaces.firstOrNull { f ->
                    val horizDist = kotlin.math.abs(f.box.centerX - det.box.centerX)
                    val vertDist = det.box.y1 - f.box.y2
                    horizDist < maxOf(f.box.width * 1.5f, det.box.width * 0.85f) &&
                    vertDist in -10f..(f.box.height * 2.5f)
                }

                if (faceAbove != null) {
                    topY = (faceAbove.box.y2 - 2f).coerceAtLeast(0f)
                    val neckW = maxOf(faceAbove.box.width * 0.65f, det.box.width * 0.45f)
                    left = minOf(left, faceAbove.box.centerX - neckW)
                    right = maxOf(right, faceAbove.box.centerX + neckW)
                } else {
                    topY = (det.box.y1 - det.box.height * 0.30f).coerceAtLeast(0f)
                }
            } else if (det.label == NudeLabel.BUTTOCKS_EXPOSED || det.label == NudeLabel.BUTTOCKS_COVERED) {
                // If backside / buttocks is detected:
                // Check if exposed skin extends up the back (backless dress, swimwear from behind)
                if (bitmap != null) {
                    val skinAbove = bitmap.getSkinFraction(
                        BoundingBox(
                            x1 = det.box.x1,
                            y1 = (det.box.y1 - det.box.height * 0.6f).coerceAtLeast(0f),
                            x2 = det.box.x2,
                            y2 = det.box.y1
                        )
                    )
                    if (skinAbove >= 0.15f) {
                        topY = (det.box.y1 - det.box.height * 0.6f).coerceAtLeast(0f)
                    }
                }
            }

            for (face in correctedFaces) {
                if (kotlin.math.abs(face.box.centerX - det.box.centerX) < maxOf(face.box.width, det.box.width * 0.8f)) {
                    if (topY < face.box.y2 && bottomY > face.box.y2) {
                        topY = maxOf(topY, face.box.y2 - 2f)
                    }
                }
            }

            BoundingBox(
                x1 = left.coerceAtLeast(0f),
                y1 = topY.coerceAtLeast(0f),
                x2 = right,
                y2 = bottomY
            )
        }.filter { it.width > 6f && it.height > 6f }

        val finalBoxes = mergeAdjacentBoxes(rawBoxes)

        return FilteredDetections(
            toBlur = filteredParts,
            faces = correctedFaces,
            hasNsfwContent = finalBoxes.isNotEmpty(),
            mergedBlurBoxes = finalBoxes
        )
    }

    fun mergeAdjacentBoxes(boxes: List<BoundingBox>): List<BoundingBox> {
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
                    // Merge boxes that overlap or connect on the same torso/limb
                    val shouldMerge = (ox > -12f && oy > -12f) &&
                            (iou > 0.08f || (ox > minOf(curr.width, cand.width) * 0.35f && oy > -15f))
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
        return when (label) {
            // Enhanced padding for cleavage, exposed breasts, and low-cut dresses (Cases 1, 3, 6)
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.FEMALE_BREAST_EXPOSED -> Pair(w * 0.25f, h * 0.30f)

            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED -> Pair(w * 0.20f, h * 0.22f)

            // Enhanced padding for bare midriff, waist, and navel (Case 10 saree profile)
            NudeLabel.BELLY_EXPOSED -> Pair(w * 0.35f, h * 0.35f)

            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED -> Pair(w * 0.22f, h * 0.25f)

            // Generous expansion for armpit/side-bust/bare-back contour (Case 10 saree/backless profile)
            NudeLabel.ARMPITS_EXPOSED -> Pair(w * 0.65f, h * 0.60f)

            // Legs and feet coverage: expands upwards along the leg corridor
            NudeLabel.FEET_EXPOSED -> Pair(w * 0.35f, h * 0.70f)

            NudeLabel.MALE_BREAST_EXPOSED,
            NudeLabel.MALE_GENITALIA_EXPOSED -> Pair(w * 0.10f, h * 0.12f)

            NudeLabel.ANUS_COVERED,
            NudeLabel.ANUS_EXPOSED -> Pair(w * 0.10f, h * 0.10f)

            NudeLabel.FACE_FEMALE,
            NudeLabel.FACE_MALE -> Pair(0f, 0f)

            else -> Pair(w * 0.10f, h * 0.10f)
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
