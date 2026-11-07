package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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

            // 2. Dual-tile pass for tall mobile displays to preserve thumbnail clarity and anatomical proportions
            if (aspectRatio > 1.30f && originalHeight > 600f) {
                val tile1H = (originalHeight * 0.58f).toInt()
                val tile2Top = (originalHeight * 0.42f).toInt()
                val tile2H = (originalHeight - tile2Top).toInt()

                // Top Tile (0% to 58%)
                try {
                    val tile1Bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, tile1H)
                    val dets1 = runInference(tile1Bitmap, 0f, originalWidth, tile1H.toFloat())
                    tile1Bitmap.recycle()
                    allDetections.addAll(dets1)
                } catch (e: Exception) {
                    Log.w(TAG, "Tile 1 inference skipped", e)
                }

                // Bottom Tile (42% to 100%)
                try {
                    val tile2Bitmap = Bitmap.createBitmap(bitmap, 0, tile2Top, bitmap.width, tile2H)
                    val dets2 = runInference(tile2Bitmap, tile2Top.toFloat(), originalWidth, tile2H.toFloat())
                    tile2Bitmap.recycle()
                    allDetections.addAll(dets2)
                } catch (e: Exception) {
                    Log.w(TAG, "Tile 2 inference skipped", e)
                }
            }

            // Consolidate detections via NMS
            val consolidated = nms(allDetections)

            // Validate exposed anatomical body parts against real human skin chrominance
            // Completely rejects inanimate objects (onions, pillows, furniture wood carvings, walls)
            val validated = consolidated.filter { det ->
                if (det.label.isFace) {
                    true
                } else if (det.label.isExposed) {
                    // Exposed body parts MUST exhibit human skin tones (eliminates onions, pillows, and wooden bedposts)
                    bitmap.hasHumanSkin(det.box, minFraction = 0.08f)
                } else {
                    true
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
            // If male face score is close (>= 70% of female), prioritize MALE so men with beards/stubble
            // or short hair are NEVER falsely classified as female in Female-Only mode!
            val femaleFaceScore = output[4 + 1][i]
            val maleFaceScore = output[4 + 12][i]
            if (bestClassIdx == 1 && maleFaceScore >= femaleFaceScore * 0.70f) {
                bestClassIdx = 12
                bestClassScore = maleFaceScore
            } else if (bestClassIdx == 12 && femaleFaceScore > maleFaceScore * 1.35f) {
                bestClassIdx = 1
                bestClassScore = femaleFaceScore
            }

            val label = NudeLabel.fromIndex(bestClassIdx) ?: continue

            val minThreshold = when (label) {
                NudeLabel.FACE_FEMALE -> 0.22f
                NudeLabel.FACE_MALE -> 0.18f
                NudeLabel.FEMALE_BREAST_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_EXPOSED,
                NudeLabel.BUTTOCKS_EXPOSED,
                NudeLabel.ANUS_EXPOSED -> minOf(confidenceThreshold, 0.14f)
                NudeLabel.FEET_EXPOSED -> minOf(confidenceThreshold, 0.18f)
                NudeLabel.ARMPITS_EXPOSED,
                NudeLabel.BELLY_EXPOSED -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.MALE_BREAST_EXPOSED,
                NudeLabel.MALE_GENITALIA_EXPOSED -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.FEMALE_GENITALIA_COVERED,
                NudeLabel.BUTTOCKS_COVERED -> minOf(confidenceThreshold, 0.20f)
                NudeLabel.FEMALE_BREAST_COVERED -> maxOf(confidenceThreshold, 0.26f)
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
        blurFullBody: Boolean = false
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
                val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                val faceBottom = c.faces.maxOfOrNull { it.box.y2 } ?: 0f
                val faceTop = c.faces.minOfOrNull { it.box.y1 } ?: 0f
                val faceH = c.faces.maxOfOrNull { it.box.height } ?: c.height

                // Strict Vertical & Proximity Alignment (solves split-screen contamination in Case 8!):
                // A face at the top of a split screen cannot claim a body at the bottom of the screen!
                val vertDist = box.y1 - faceBottom
                val isBelowFace = box.y1 >= faceTop - 20f
                val isWithinTorsoReach = vertDist <= faceH * 5.5f
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
                it.label == NudeLabel.FEET_EXPOSED ||
                it.label == NudeLabel.ANUS_EXPOSED
            }

            // CRITICAL: Inanimate object protection (Cases 5 & 7):
            // If an isolated detection has NO human face attached and only contains covered fabric
            // (e.g. pillow, bedsheet, car seat, fruit), NEVER blur it!
            if (c.faces.isEmpty() && !hasExposedSkin) {
                return@filter false
            }

            if (c.faces.isNotEmpty() && !hasExposedSkin &&
                c.intimateDetections.all { it.label == NudeLabel.FEMALE_BREAST_COVERED && it.confidence < 0.38f }) {
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
                    // Male clusters are 100% EXCLUDED in Female-Only mode!
                    if (gender == ClusterGender.MALE) return@filter false

                    if (gender == ClusterGender.FEMALE) return@filter true

                    // Unknown headless objects: ONLY allow if confirmed bare female anatomy with high confidence!
                    // Inanimate objects (pillows, onions) NEVER pass this!
                    gender == ClusterGender.UNKNOWN && c.intimateDetections.any {
                        (it.label == NudeLabel.FEMALE_BREAST_EXPOSED || it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED) &&
                        it.confidence >= 0.32f
                    }
                }
                GenderFilter.MALE_ONLY -> {
                    if (gender == ClusterGender.FEMALE) return@filter false
                    if (gender == ClusterGender.MALE) return@filter true
                    gender == ClusterGender.UNKNOWN && c.intimateDetections.any {
                        it.label == NudeLabel.MALE_BREAST_EXPOSED || it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                    }
                }
            }
        }

        // Filter individual detections under opposite-gender faces (solves Cases 1, 8, 9)
        val filteredParts = keptClusters.flatMap { it.intimateDetections }.filter { det ->
            when (genderFilter) {
                GenderFilter.FEMALE_ONLY -> {
                    val underMaleFace = maleFaces.any { m ->
                        val bodyTop = m.box.y1 - 10f
                        val bodyBottom = m.box.y2 + m.box.height * 6.0f
                        val colLeft = m.box.centerX - m.box.width * 1.5f
                        val colRight = m.box.centerX + m.box.width * 1.5f
                        val closerFemale = femaleFaces.any { f ->
                            val distF = kotlin.math.hypot(f.box.centerX - det.box.centerX, f.box.y2 - det.box.y1)
                            val distM = kotlin.math.hypot(m.box.centerX - det.box.centerX, m.box.y2 - det.box.y1)
                            distF < distM
                        }
                        !closerFemale && det.box.centerY in bodyTop..bodyBottom && det.box.centerX in colLeft..colRight
                    }
                    !underMaleFace
                }
                GenderFilter.MALE_ONLY -> {
                    val underFemaleFace = femaleFaces.any { f ->
                        val bodyTop = f.box.y1 - 10f
                        val bodyBottom = f.box.y2 + f.box.height * 6.0f
                        val colLeft = f.box.centerX - f.box.width * 1.5f
                        val colRight = f.box.centerX + f.box.width * 1.5f
                        val closerMale = maleFaces.any { m ->
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
                faces = allFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        // ── FULL BODY BLUR LOGIC (When toggled ON in Settings) ───────────────────
        if (blurFullBody) {
            val fullBodyBoxes = keptClusters.map { c ->
                val padX = c.width * 0.12f
                val left = (c.minX - padX).coerceAtLeast(0f)
                val right = c.maxX + padX

                // If person has a face, preserve the face and shield from chin/neck down to feet
                val topY = if (c.faces.isNotEmpty()) {
                    val face = c.faces.minByOrNull { it.box.y1 }!!
                    (face.box.y2 - 4f).coerceAtLeast(0f)
                } else {
                    (c.minY - c.height * 0.08f).coerceAtLeast(0f)
                }

                // Generous downward margin to shield legs, back, and midriff completely
                val bottomY = c.maxY + c.height * 0.18f

                BoundingBox(left, topY, right, bottomY)
            }.filter { it.width > 8f && it.height > 8f }

            val mergedFull = mergeAdjacentBoxes(fullBodyBoxes)
            return FilteredDetections(
                toBlur = filteredParts,
                faces = allFaces,
                hasNsfwContent = mergedFull.isNotEmpty(),
                mergedBlurBoxes = mergedFull
            )
        }

        // ── TARGETED BLUR LOGIC (When Full Body Blur is OFF) ────────────────────
        // Tight, accurate boxes encompassing detected anatomy (back, chest, midriff, legs)
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
            // Enhanced padding for cleavage, bare back, and exposed breasts (Cases 3, 4, 10)
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.FEMALE_BREAST_EXPOSED -> Pair(w * 0.16f, h * 0.20f)

            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED -> Pair(w * 0.14f, h * 0.16f)

            // Enhanced padding for bare midriff, waist, and navel (Case 10 saree profile)
            NudeLabel.BELLY_EXPOSED -> Pair(w * 0.18f, h * 0.22f)

            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED -> Pair(w * 0.16f, h * 0.18f)

            // Generous expansion for armpit/side-bust/back contour (Case 10)
            NudeLabel.ARMPITS_EXPOSED -> Pair(w * 0.22f, h * 0.26f)

            // Legs and feet coverage
            NudeLabel.FEET_EXPOSED -> Pair(w * 0.15f, h * 0.22f)

            NudeLabel.MALE_BREAST_EXPOSED,
            NudeLabel.MALE_GENITALIA_EXPOSED -> Pair(w * 0.10f, h * 0.12f)

            NudeLabel.ANUS_COVERED,
            NudeLabel.ANUS_EXPOSED -> Pair(w * 0.08f, h * 0.08f)

            NudeLabel.FACE_FEMALE,
            NudeLabel.FACE_MALE -> Pair(0f, 0f)

            else -> Pair(w * 0.08f, h * 0.08f)
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
