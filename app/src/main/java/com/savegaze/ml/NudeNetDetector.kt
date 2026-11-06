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

            // Also check if any exposed female anatomy class has a strong secondary score
            // when a neutral class (like BELLY_COVERED or ARMPITS_COVERED) slightly edges it out
            val labelCandidate = NudeLabel.fromIndex(bestClassIdx) ?: continue
            if (!labelCandidate.isExposed && !labelCandidate.isFace && labelCandidate != NudeLabel.FEMALE_BREAST_COVERED && labelCandidate != NudeLabel.FEMALE_GENITALIA_COVERED && labelCandidate != NudeLabel.BUTTOCKS_COVERED) {
                // Check exposed classes
                val exposedIndices = intArrayOf(0, 2, 3, 4, 5, 6, 11, 13, 14, 16, 17)
                var bestExpIdx = -1
                var bestExpScore = 0f
                for (idx in exposedIndices) {
                    val s = output[4 + idx][i]
                    if (s > bestExpScore) {
                        bestExpScore = s
                        bestExpIdx = idx
                    }
                }
                if (bestExpIdx >= 0 && bestExpScore >= 0.14f) {
                    bestClassIdx = bestExpIdx
                    bestClassScore = bestExpScore
                }
            }

            val label = NudeLabel.fromIndex(bestClassIdx) ?: continue

            // Sensitive thresholds tuned for both static images and fast-moving video frames
            val minThreshold = when (label) {
                NudeLabel.FACE_FEMALE,
                NudeLabel.FACE_MALE -> minOf(confidenceThreshold, 0.15f)
                NudeLabel.FEMALE_BREAST_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_EXPOSED,
                NudeLabel.BUTTOCKS_EXPOSED,
                NudeLabel.ANUS_EXPOSED -> minOf(confidenceThreshold, 0.12f)
                NudeLabel.MALE_BREAST_EXPOSED,
                NudeLabel.MALE_GENITALIA_EXPOSED -> minOf(confidenceThreshold, 0.14f)
                NudeLabel.BELLY_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_COVERED,
                NudeLabel.BUTTOCKS_COVERED,
                NudeLabel.ARMPITS_EXPOSED -> minOf(confidenceThreshold, 0.14f)
                NudeLabel.FEMALE_BREAST_COVERED -> minOf(confidenceThreshold, 0.20f)
                NudeLabel.FEET_EXPOSED -> minOf(confidenceThreshold, 0.18f)
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

            if (box.width > 2f && box.height > 2f) {
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
        val upper = mutableListOf<Detection>()
        val pelvis = mutableListOf<Detection>()
        val belly = mutableListOf<Detection>()
        val others = mutableListOf<Detection>()
        val feet = mutableListOf<BoundingBox>()

        val allDetections: List<Detection> get() = faces + upper + pelvis + belly + others
        val intimateDetections: List<Detection> get() = upper + pelvis + belly + others

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
            sceneHasFemaleSpecific: Boolean = false,
            sceneHasMaleSpecific: Boolean = false
        ): ClusterGender {
            val hasFemaleFace = faces.any { it.label == NudeLabel.FACE_FEMALE }
            val hasMaleFace = faces.any { it.label == NudeLabel.FACE_MALE }

            val hasFemaleSpecificPart = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED ||
                it.label == NudeLabel.FEMALE_BREAST_COVERED ||
                it.label == NudeLabel.FEMALE_GENITALIA_COVERED
            }
            val hasMaleSpecificPart = intimateDetections.any {
                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                it.label == NudeLabel.MALE_GENITALIA_EXPOSED
            }

            if (hasFemaleSpecificPart && !hasMaleSpecificPart) return ClusterGender.FEMALE
            if (hasMaleSpecificPart && !hasFemaleSpecificPart) return ClusterGender.MALE

            if (hasFemaleFace && !hasMaleFace) return ClusterGender.FEMALE
            if (hasMaleFace && !hasFemaleFace) return ClusterGender.MALE

            val sceneHasFemale = sceneHasFemaleFace || sceneHasFemaleSpecific
            val sceneHasMale = sceneHasMaleFace || sceneHasMaleSpecific
            if (sceneHasFemale && !sceneHasMale) return ClusterGender.FEMALE
            if (sceneHasMale && !sceneHasFemale) return ClusterGender.MALE

            return ClusterGender.UNKNOWN
        }
    }

    fun filterDetections(
        detections: List<Detection>,
        genderFilter: GenderFilter = GenderFilter.BOTH,
        blurFullBody: Boolean = false
    ): FilteredDetections {
        val allFaces = detections.filter { it.label.isFace }
        val feet = detections.filter { it.label == NudeLabel.FEET_EXPOSED || it.label == NudeLabel.FEET_COVERED }

        val nsfwParts = detections.filter { det ->
            det.label in NSFW_LABELS && allFaces.none { face -> det.box.iou(face.box) > 0.55f }
        }

        if (nsfwParts.isEmpty()) {
            return FilteredDetections(
                toBlur = emptyList(),
                faces = allFaces,
                hasNsfwContent = false,
                mergedBlurBoxes = emptyList()
            )
        }

        val sceneHasFemaleFace = allFaces.any { it.label == NudeLabel.FACE_FEMALE }
        val sceneHasMaleFace = allFaces.any { it.label == NudeLabel.FACE_MALE }
        val sceneHasFemaleSpecific = nsfwParts.any { it.label.isFemale == true }
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
                val detIsStrictFemale = det.label.isFemale == true
                val detIsStrictMale = det.label.isFemale == false
                if (detIsStrictFemale && c.faces.any { it.label == NudeLabel.FACE_MALE } && c.faces.none { it.label == NudeLabel.FACE_FEMALE }) continue
                if (detIsStrictMale && c.faces.any { it.label == NudeLabel.FACE_FEMALE } && c.faces.none { it.label == NudeLabel.FACE_MALE }) continue

                val dist = kotlin.math.abs(box.centerX - c.centerX)
                val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                val allowDist = maxOf(c.width * 1.15f, box.width * 1.15f, faceW * 1.85f)
                val faceBottom = c.faces.maxOfOrNull { it.box.y2 }
                val verticalOk = faceBottom == null || box.y2 >= faceBottom - 60f

                if (verticalOk && dist < allowDist && dist < minDist) {
                    minDist = dist
                    bestCluster = c
                }
            }

            val target = bestCluster ?: PersonCluster().also { clusters.add(it) }
            when (det.label) {
                NudeLabel.FEMALE_BREAST_COVERED,
                NudeLabel.FEMALE_BREAST_EXPOSED,
                NudeLabel.MALE_BREAST_EXPOSED,
                NudeLabel.ARMPITS_EXPOSED -> target.upper.add(det)

                NudeLabel.FEMALE_GENITALIA_COVERED,
                NudeLabel.FEMALE_GENITALIA_EXPOSED,
                NudeLabel.BUTTOCKS_COVERED,
                NudeLabel.BUTTOCKS_EXPOSED,
                NudeLabel.ANUS_EXPOSED,
                NudeLabel.ANUS_COVERED,
                NudeLabel.MALE_GENITALIA_EXPOSED -> target.pelvis.add(det)

                NudeLabel.BELLY_EXPOSED -> target.belly.add(det)

                NudeLabel.FEET_EXPOSED -> {
                    target.others.add(det)
                    if (target.feet.none { it == det.box }) target.feet.add(det.box)
                }

                else -> target.others.add(det)
            }
        }

        for (foot in feet) {
            val fbox = foot.box
            var bestCluster: PersonCluster? = null
            var minDist = Float.MAX_VALUE
            for (c in clusters) {
                if (fbox.y1 >= c.minY) {
                    val d = kotlin.math.abs(fbox.centerX - c.centerX)
                    val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                    if (d < minDist && d < maxOf(c.width * 1.1f, fbox.width * 1.1f, faceW * 1.6f)) {
                        minDist = d
                        bestCluster = c
                    }
                }
            }
            bestCluster?.feet?.add(fbox)
        }

        val keptClusters = clusters.filter { c ->
            if (c.intimateDetections.isEmpty()) return@filter false

            val gender = c.inferGender(
                sceneHasFemaleFace,
                sceneHasMaleFace,
                sceneHasFemaleSpecific,
                sceneHasMaleSpecific
            )

            when (genderFilter) {
                GenderFilter.BOTH -> true
                GenderFilter.FEMALE_ONLY -> {
                    // Keep all confirmed FEMALE clusters and any unattached exposed body parts
                    // (e.g., video close-ups, belly, buttocks, cleavage, legs) unless confirmed MALE
                    gender == ClusterGender.FEMALE ||
                    (gender == ClusterGender.UNKNOWN && c.intimateDetections.none { it.label.isFemale == false })
                }
                GenderFilter.MALE_ONLY -> {
                    gender == ClusterGender.MALE ||
                    (gender == ClusterGender.UNKNOWN && c.intimateDetections.none { it.label.isFemale == true })
                }
            }
        }

        val rawToBlur = keptClusters.flatMap { it.intimateDetections }
        val shields = buildShieldsForClusters(
            keptClusters,
            allFaces,
            sceneHasFemaleFace,
            sceneHasMaleFace,
            sceneHasFemaleSpecific,
            sceneHasMaleSpecific,
            blurFullBody
        )

        val mergedShields = mergeAdjacentBoxes(shields)

        return FilteredDetections(
            toBlur = rawToBlur,
            faces = allFaces,
            hasNsfwContent = rawToBlur.isNotEmpty() || mergedShields.isNotEmpty(),
            mergedBlurBoxes = mergedShields
        )
    }

    private fun buildShieldsForClusters(
        keptClusters: List<PersonCluster>,
        allFaces: List<Detection>,
        sceneHasFemaleFace: Boolean,
        sceneHasMaleFace: Boolean,
        sceneHasFemaleSpecific: Boolean,
        sceneHasMaleSpecific: Boolean,
        blurFullBody: Boolean = false
    ): List<BoundingBox> {
        if (keptClusters.isEmpty()) return emptyList()

        val shields = mutableListOf<BoundingBox>()

        for (c in keptClusters) {
            val refCx = c.centerX
            val clusterGender = c.inferGender(sceneHasFemaleFace, sceneHasMaleFace, sceneHasFemaleSpecific, sceneHasMaleSpecific)
            val isMale = clusterGender == ClusterGender.MALE

            val matchingFace = c.faces.firstOrNull { f ->
                when (clusterGender) {
                    ClusterGender.FEMALE -> f.label == NudeLabel.FACE_FEMALE
                    ClusterGender.MALE -> f.label == NudeLabel.FACE_MALE
                    ClusterGender.UNKNOWN -> true
                }
            } ?: allFaces.firstOrNull { f ->
                val faceGenderMatch = when (clusterGender) {
                    ClusterGender.FEMALE -> f.label == NudeLabel.FACE_FEMALE
                    ClusterGender.MALE -> f.label == NudeLabel.FACE_MALE
                    ClusterGender.UNKNOWN -> true
                }
                faceGenderMatch &&
                kotlin.math.abs(refCx - f.box.centerX) < maxOf(c.width * 1.8f, f.box.width * 2.4f) &&
                f.box.y2 <= c.minY + 300f &&
                f.box.y1 <= c.maxY
            }

            val hasArmpits = c.upper.any { it.label == NudeLabel.ARMPITS_EXPOSED }
            var topY = when {
                matchingFace != null -> matchingFace.box.y2 - 8f
                hasArmpits -> {
                    val upperH = c.upper.maxOf { it.box.height }
                    (c.upper.minOf { it.box.y1 } - maxOf(upperH * 0.75f, 110f)).coerceAtLeast(0f)
                }
                c.upper.isNotEmpty() -> {
                    val upperH = c.upper.maxOf { it.box.height }
                    (c.upper.minOf { it.box.y1 } - maxOf(upperH * 0.50f, 55f)).coerceAtLeast(0f)
                }
                c.belly.isNotEmpty() -> {
                    val bellyH = c.belly.maxOf { it.box.height }
                    (c.belly.minOf { it.box.y1 } - maxOf(bellyH * 1.1f, 130f)).coerceAtLeast(0f)
                }
                c.pelvis.isNotEmpty() -> {
                    val pelvisH = c.pelvis.maxOf { it.box.height }
                    (c.pelvis.minOf { it.box.y1 } - maxOf(pelvisH * 0.85f, 100f)).coerceAtLeast(0f)
                }
                else -> (c.minY - 30f).coerceAtLeast(0f)
            }

            if (matchingFace != null) {
                topY = minOf(topY, matchingFace.box.y2 - 8f)
            }

            // Generous vertical coverage so midriff, belly, hips, and buttocks are never left exposed in videos
            val botPelvis = when {
                blurFullBody -> {
                    if (c.feet.isNotEmpty()) {
                        c.feet.maxOf { it.y2 } + 20f
                    } else if (matchingFace != null) {
                        matchingFace.box.y2 + matchingFace.box.height * 6.0f
                    } else {
                        c.maxY + maxOf(c.height * 1.8f, 450f)
                    }
                }
                c.pelvis.isNotEmpty() -> {
                    val pelvisH = c.pelvis.maxOf { it.box.height }
                    c.pelvis.maxOf { it.box.y2 } + maxOf(pelvisH * 0.45f, 55f)
                }
                c.belly.isNotEmpty() -> {
                    val bellyH = c.belly.maxOf { it.box.height }
                    c.belly.maxOf { it.box.y2 } + maxOf(bellyH * 0.85f, 110f)
                }
                c.upper.isNotEmpty() -> {
                    val upperH = c.upper.maxOf { it.box.height }
                    val extent = if (!isMale) maxOf(upperH * 1.35f, 160f) else maxOf(upperH * 0.85f, 90f)
                    c.maxY + extent
                }
                else -> c.maxY + 45f
            }

            val faceW = matchingFace?.box?.width ?: (c.faces.maxOfOrNull { it.box.width } ?: 0f)
            val padX = 28f
            val intimateMinX = c.intimateDetections.minOfOrNull { it.box.x1 } ?: c.minX
            val intimateMaxX = c.intimateDetections.maxOfOrNull { it.box.x2 } ?: c.maxX

            var torsoX1 = minOf(c.minX - padX, intimateMinX - padX).coerceAtLeast(0f)
            var torsoX2 = maxOf(c.maxX + padX, intimateMaxX + padX)

            if (matchingFace != null) {
                val span = faceW * 1.15f
                torsoX1 = minOf(torsoX1, matchingFace.box.centerX - span).coerceAtLeast(0f)
                torsoX2 = maxOf(torsoX2, matchingFace.box.centerX + span)
            }

            if (torsoX2 > torsoX1 && botPelvis > topY) {
                shields.add(BoundingBox(
                    x1 = torsoX1,
                    y1 = topY,
                    x2 = torsoX2,
                    y2 = botPelvis
                ))
            }

            // Leg shield when pelvis, belly, or feet are detected
            val hasBelly = c.belly.isNotEmpty()
            val hasPelvis = c.pelvis.isNotEmpty()
            if (!blurFullBody && (hasPelvis || hasBelly || c.feet.isNotEmpty())) {
                val legExtent = if (c.feet.isNotEmpty()) {
                    (c.feet.maxOf { it.y2 } + 20f) - botPelvis
                } else if (matchingFace != null) {
                    maxOf(c.height * 1.25f, matchingFace.box.height * 3.5f, 360f)
                } else {
                    maxOf(c.height * 1.25f, 340f)
                }
                if (legExtent > 10f) {
                    shields.add(BoundingBox(
                        x1 = torsoX1,
                        y1 = botPelvis - 8f,
                        x2 = torsoX2,
                        y2 = botPelvis + legExtent
                    ))
                }
            }

            // Guarantee every individual raw intimate detection box (with padding) is included
            for (det in c.intimateDetections) {
                shields.add(BoundingBox(
                    x1 = (det.box.x1 - 16f).coerceAtLeast(0f),
                    y1 = (det.box.y1 - 16f).coerceAtLeast(0f),
                    x2 = det.box.x2 + 16f,
                    y2 = det.box.y2 + 16f
                ))
            }
        }

        return shields
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
                    val shouldMerge = (ox >= -12f && oy >= -16f) && (iou > 0.05f || ox > minOf(curr.width, cand.width) * 0.30f)
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
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.FEMALE_BREAST_EXPOSED -> Pair(w * 0.30f, h * 0.28f)

            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED -> Pair(w * 0.30f, h * 0.28f)

            NudeLabel.BELLY_EXPOSED -> Pair(w * 0.28f, h * 0.25f)

            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED -> Pair(w * 0.28f, h * 0.26f)

            NudeLabel.ARMPITS_EXPOSED -> Pair(w * 0.22f, h * 0.22f)

            NudeLabel.MALE_BREAST_EXPOSED -> Pair(w * 0.22f, h * 0.20f)

            NudeLabel.MALE_GENITALIA_EXPOSED -> Pair(w * 0.26f, h * 0.24f)

            NudeLabel.ANUS_COVERED,
            NudeLabel.ANUS_EXPOSED -> Pair(w * 0.25f, h * 0.25f)

            NudeLabel.FACE_FEMALE,
            NudeLabel.FACE_MALE -> Pair(0f, 0f)

            else -> Pair(w * 0.15f, h * 0.15f)
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
