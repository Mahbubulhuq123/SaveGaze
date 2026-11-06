package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.savegaze.util.toFloatBuffer
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NudeNetDetector(
    context: Context,
    var confidenceThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.5f
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
        private const val ROW_SIZE = 22 // 4 bbox + 18 class scores

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
            if (isClosed) {
                Log.w(TAG, "Detector is closed, returning empty list")
                return@withContext emptyList()
            }

            val originalWidth = bitmap.width.toFloat()
            val originalHeight = bitmap.height.toFloat()

            // Run clean single-frame full image inference directly without artificial tile seams.
            val floatBuffer = bitmap.toFloatBuffer(MODEL_WIDTH, MODEL_HEIGHT)
            val shape = longArrayOf(1, NUM_CHANNELS.toLong(), MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
            var inputTensor: OnnxTensor? = null
            try {
                inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
                val inputs = mapOf(INPUT_NAME to inputTensor)
                val results = ortSession.run(inputs)

                val outputTensor = results.get(OUTPUT_NAME)
                if (!outputTensor.isPresent) {
                    Log.e(TAG, "Output tensor '$OUTPUT_NAME' not found")
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
        var globalMaxScore = 0f
        var globalMaxClass = ""

        for (i in 0 until NUM_PREDICTIONS) {
            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            // Find best class
            var bestClassIdx = 0
            var bestClassScore = output[4][i]
            for (c in 1 until NUM_CLASSES) {
                val score = output[4 + c][i]
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClassIdx = c
                }
            }

            // Calibrate face classification for clean-shaved males with long hair / sunglasses:
            // NudeLabel indices: FACE_FEMALE is 1, FACE_MALE is 12
            val femaleFaceScore = output[4 + 1][i]
            val maleFaceScore = output[4 + 12][i]
            val isMaleFaceCalibrated = maleFaceScore > femaleFaceScore ||
                    (femaleFaceScore < 0.35f && maleFaceScore >= 0.035f && maleFaceScore >= femaleFaceScore * 0.25f)

            val adjustedBestClassIdx = if (bestClassIdx == 1 && isMaleFaceCalibrated) {
                12 // classify as FACE_MALE!
            } else {
                bestClassIdx
            }
            // Retain the strong face detection score so the male face is never discarded by minThreshold
            val finalScore = bestClassScore

            if (finalScore > globalMaxScore) {
                globalMaxScore = finalScore
                globalMaxClass = NudeLabel.fromIndex(adjustedBestClassIdx)?.name ?: "UNKNOWN"
            }

            val label = NudeLabel.fromIndex(adjustedBestClassIdx) ?: continue

            // Sensitive thresholds for faces and exposed/intimate areas to ensure
            // boobs, breasts, side-profile cleavage, exposed back, exposed belly, and buttocks
            // are reliably detected without dropping subtle angles or side poses.
            // IMPORTANT: FEMALE_BREAST_COVERED requires high confidence (>= 0.35f) so normal
            // shirts and t-shirt folds are NEVER falsely detected as bikini tops/bras!
            val minThreshold = when (label) {
                NudeLabel.FACE_FEMALE,
                NudeLabel.FACE_MALE -> minOf(confidenceThreshold, 0.16f)
                NudeLabel.ARMPITS_EXPOSED -> minOf(confidenceThreshold, 0.20f)
                NudeLabel.FEMALE_BREAST_EXPOSED,
                NudeLabel.MALE_BREAST_EXPOSED,
                NudeLabel.FEMALE_GENITALIA_EXPOSED,
                NudeLabel.MALE_GENITALIA_EXPOSED,
                NudeLabel.BUTTOCKS_EXPOSED -> minOf(confidenceThreshold, 0.14f)
                NudeLabel.BUTTOCKS_COVERED,
                NudeLabel.FEMALE_GENITALIA_COVERED,
                NudeLabel.BELLY_EXPOSED,
                NudeLabel.FEET_EXPOSED -> minOf(confidenceThreshold, 0.18f)
                NudeLabel.FEMALE_BREAST_COVERED -> maxOf(confidenceThreshold, 0.35f)
                else -> confidenceThreshold
            }
            if (finalScore < minThreshold) continue

            val (padW, padH) = getLabelPadding(label, w, h)

            // Convert center-format (cx, cy, w, h) to corner-format (x1, y1, x2, y2) with tile offsetY
            val x1 = (cx - w / 2f - padW) * scaleX
            val y1 = (cy - h / 2f - padH) * scaleY + offsetY
            val x2 = (cx + w / 2f + padW) * scaleX
            val y2 = (cy + h / 2f + padH) * scaleY + offsetY

            // Clamp to image bounds
            val box = BoundingBox(
                x1 = x1.coerceIn(0f, originalWidth),
                y1 = y1.coerceAtLeast(0f),
                x2 = x2.coerceIn(0f, originalWidth),
                y2 = y2.coerceAtLeast(0f)
            )

            if (box.width > 0f && box.height > 0f) {
                candidates.add(Detection(label, bestClassScore, box))
            }
        }

        if (globalMaxScore > 0.01f) {
            Log.d(TAG, "postProcess: maxScore = $globalMaxScore ($globalMaxClass, offsetY = $offsetY), candidates: ${candidates.size}")
        }
        return candidates
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        // Group by class for per-class NMS
        val grouped = detections.groupBy { it.label }
        val result = mutableListOf<Detection>()

        for ((_, classDets) in grouped) {
            val sorted = classDets.sortedByDescending { it.confidence }.toMutableList()
            val kept = mutableListOf<Detection>()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)

                sorted.removeAll { other ->
                    best.box.iou(other.box) > iouThreshold
                }
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

            // 1. Anatomical intimate parts have the HIGHEST authority (shirtless male, female breasts):
            val hasFemaleExposed = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_EXPOSED || it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED
            }
            val hasMaleSpecific = intimateDetections.any { it.label.isFemale == false }

            // Shirtless male or male genitalia is 100% male, regardless of face hair length
            if (hasMaleSpecific && !hasFemaleExposed) return ClusterGender.MALE
            if (hasFemaleExposed && !hasMaleSpecific) return ClusterGender.FEMALE

            // 2. Confirmed male face (calibrated for long-haired clean-shaved males)
            if (hasMaleFace && !hasFemaleFace) return ClusterGender.MALE

            // 3. Confirmed female face
            if (hasFemaleFace && !hasMaleFace) return ClusterGender.FEMALE

            val hasFemaleCovered = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_COVERED || it.label == NudeLabel.FEMALE_GENITALIA_COVERED
            }
            if (hasFemaleCovered && !hasMaleSpecific) {
                if (sceneHasMaleFace && !sceneHasFemaleFace) return ClusterGender.MALE
                return ClusterGender.FEMALE
            }

            // 4. Cluster has NO face and NO gender-specific body parts:
            val sceneHasMale = sceneHasMaleFace || sceneHasMaleSpecific
            val sceneHasFemale = sceneHasFemaleFace || sceneHasFemaleSpecific
            if (sceneHasMale && !sceneHasFemale) return ClusterGender.MALE
            if (sceneHasFemale && !sceneHasMale) return ClusterGender.FEMALE

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

        // Filter by NSFW relevance and exclude any body parts that heavily overlap a face
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

        val sceneHasFemaleFace = allFaces.any { it.label == NudeLabel.FACE_FEMALE }
        val sceneHasMaleFace = allFaces.any { it.label == NudeLabel.FACE_MALE }
        val sceneHasFemaleSpecific = nsfwParts.any { it.label.isFemale == true }
        val sceneHasMaleSpecific = nsfwParts.any { it.label.isFemale == false }

        val clusters = mutableListOf<PersonCluster>()

        // 1. Initialize a person cluster for each detected face
        for (face in allFaces) {
            clusters.add(PersonCluster().apply { faces.add(face) })
        }

        // 2. Assign intimate parts to clusters based on vertical and horizontal alignment
        for (det in nsfwParts) {
            val box = det.box
            var bestCluster: PersonCluster? = null
            var minDist = Float.MAX_VALUE

            for (c in clusters) {
                // Strict anatomy gender incompatibility check:
                // Only truly exposed anatomical parts (not covered clothing false positives) are mutually exclusive across genders
                val detIsStrictFemale = det.label == NudeLabel.FEMALE_BREAST_EXPOSED || det.label == NudeLabel.FEMALE_GENITALIA_EXPOSED
                val detIsStrictMale = det.label == NudeLabel.MALE_BREAST_EXPOSED || det.label == NudeLabel.MALE_GENITALIA_EXPOSED
                if (detIsStrictFemale && (c.faces.any { it.label == NudeLabel.FACE_MALE } || c.intimateDetections.any { it.label.isFemale == false })) continue
                if (detIsStrictMale && (c.faces.any { it.label == NudeLabel.FACE_FEMALE } || c.intimateDetections.any { it.label.isFemale == true })) continue

                val dist = kotlin.math.abs(box.centerX - c.centerX)
                val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                val allowDist = maxOf(c.width * 0.60f, box.width * 0.60f, faceW * 0.90f)
                val faceBottom = c.faces.maxOfOrNull { it.box.y2 }
                val verticalOk = faceBottom == null || box.y2 >= faceBottom - 30f

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
                NudeLabel.MALE_GENITALIA_EXPOSED -> target.pelvis.add(det)

                NudeLabel.BELLY_EXPOSED -> target.belly.add(det)

                NudeLabel.FEET_EXPOSED -> {
                    target.others.add(det)
                    if (target.feet.none { it == det.box }) target.feet.add(det.box)
                }

                else -> target.others.add(det)
            }
        }

        // 3. Assign feet to clusters
        for (foot in feet) {
            val fbox = foot.box
            var bestCluster: PersonCluster? = null
            var minDist = Float.MAX_VALUE
            for (c in clusters) {
                if (fbox.y1 >= c.minY) {
                    val d = kotlin.math.abs(fbox.centerX - c.centerX)
                    val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                    if (d < minDist && d < maxOf(c.width * 0.60f, fbox.width * 0.60f, faceW * 0.90f)) {
                        minDist = d
                        bestCluster = c
                    }
                }
            }
            bestCluster?.feet?.add(fbox)
        }

        // 4. Filter clusters by GenderFilter & reject animal/object false positives:
        val keptClusters = clusters.filter { c ->
            if (c.intimateDetections.isEmpty()) return@filter false

            // Animal & Inanimate Object Rejection Filter:
            // A cluster with NO human face is only valid if it exhibits confirmed human anatomy:
            if (c.faces.isEmpty()) {
                val hasStrictExposedAnatomy = c.intimateDetections.any {
                    it.label in setOf(
                        NudeLabel.FEMALE_BREAST_EXPOSED,
                        NudeLabel.FEMALE_GENITALIA_EXPOSED,
                        NudeLabel.MALE_GENITALIA_EXPOSED,
                        NudeLabel.MALE_BREAST_EXPOSED,
                        NudeLabel.BUTTOCKS_EXPOSED
                    )
                }
                val hasMultiPartHumanBody = c.intimateDetections.size >= 2
                val hasStrongConfidence = c.intimateDetections.any { it.confidence >= 0.40f }

                val isConfirmedHumanBody = hasStrictExposedAnatomy || hasMultiPartHumanBody || hasStrongConfidence
                if (!isConfirmedHumanBody) {
                    return@filter false // Discard animal/object false positive!
                }
            }

            // Normal Clothed Person False-Positive Filter:
            // A person with a face who has NO exposed intimate skin
            // (no cleavage/bare breasts, no exposed belly, no exposed buttocks/pelvis, no exposed genitalia)
            // and NO confirmed swimwear/panties (FEMALE_GENITALIA_COVERED, BUTTOCKS_COVERED):
            // if their only chest detection is FEMALE_BREAST_COVERED < 0.38f, they are wearing ordinary clothing (shirt/blouse/suit).
            // Do NOT blur!
            val hasExposedIntimate = c.intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                it.label == NudeLabel.BELLY_EXPOSED ||
                it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED ||
                it.label == NudeLabel.MALE_GENITALIA_EXPOSED ||
                it.label == NudeLabel.FEMALE_GENITALIA_COVERED ||
                it.label == NudeLabel.BUTTOCKS_COVERED ||
                (it.label == NudeLabel.ARMPITS_EXPOSED && it.confidence >= 0.25f)
            }

            val onlyCoveredChestWithoutExposedSkin = c.faces.isNotEmpty() &&
                    !hasExposedIntimate &&
                    c.upper.all { it.label == NudeLabel.FEMALE_BREAST_COVERED && it.confidence < 0.38f }

            if (onlyCoveredChestWithoutExposedSkin) {
                return@filter false // Discard ordinary shirt/clothing false positive!
            }

            val gender = c.inferGender(
                sceneHasFemaleFace,
                sceneHasMaleFace,
                sceneHasFemaleSpecific,
                sceneHasMaleSpecific
            )
            when (genderFilter) {
                GenderFilter.BOTH -> true
                GenderFilter.FEMALE_ONLY -> {
                    // Strictly blur confirmed FEMALE clusters!
                    // If cluster has NO face and NO male parts, and scene has NO male face:
                    // treat headless exposed intimate/back areas as FEMALE so women viewed from behind are fully protected!
                    gender == ClusterGender.FEMALE ||
                    (gender == ClusterGender.UNKNOWN && !sceneHasMaleFace && !sceneHasMaleSpecific)
                }
                GenderFilter.MALE_ONLY -> {
                    // Strictly blur confirmed MALE clusters and neutral headless intimate parts
                    // when no female face is present in the scene.
                    gender == ClusterGender.MALE ||
                    (gender == ClusterGender.UNKNOWN && !sceneHasFemaleFace && !sceneHasFemaleSpecific)
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

        val maleFaces = allFaces.filter { it.label == NudeLabel.FACE_MALE }
        val femaleFaces = allFaces.filter { it.label == NudeLabel.FACE_FEMALE }

        val finalShields = when (genderFilter) {
            GenderFilter.FEMALE_ONLY -> {
                if (maleFaces.isNotEmpty()) {
                    shields.mapNotNull { s ->
                        var clamped = s
                        for (m in maleFaces) {
                            val bodyTop = m.box.y1 - 10f
                            val bodyBottom = m.box.y2 + m.box.height * 5.0f
                            if (s.y2 < bodyTop || s.y1 > bodyBottom) continue

                            val colLeft = m.box.x1 - m.box.width * 0.35f
                            val colRight = m.box.x2 + m.box.width * 0.35f
                            if (clamped.centerX in colLeft..colRight) return@mapNotNull null
                            if (clamped.x1 < colRight && clamped.x2 > colLeft) {
                                clamped = if (clamped.centerX > m.box.centerX) {
                                    BoundingBox(maxOf(clamped.x1, colRight + 4f), clamped.y1, clamped.x2, clamped.y2)
                                } else {
                                    BoundingBox(clamped.x1, clamped.y1, minOf(clamped.x2, colLeft - 4f), clamped.y2)
                                }
                            }
                        }
                        if (clamped.width <= 10f || clamped.height <= 10f) null else clamped
                    }
                } else shields
            }
            GenderFilter.MALE_ONLY -> {
                if (femaleFaces.isNotEmpty()) {
                    shields.mapNotNull { s ->
                        var clamped = s
                        for (f in femaleFaces) {
                            val bodyTop = f.box.y1 - 10f
                            val bodyBottom = f.box.y2 + f.box.height * 5.0f
                            if (s.y2 < bodyTop || s.y1 > bodyBottom) continue

                            val colLeft = f.box.x1 - f.box.width * 0.35f
                            val colRight = f.box.x2 + f.box.width * 0.35f
                            if (clamped.centerX in colLeft..colRight) return@mapNotNull null
                            if (clamped.x1 < colRight && clamped.x2 > colLeft) {
                                clamped = if (clamped.centerX > f.box.centerX) {
                                    BoundingBox(maxOf(clamped.x1, colRight + 4f), clamped.y1, clamped.x2, clamped.y2)
                                } else {
                                    BoundingBox(clamped.x1, clamped.y1, minOf(clamped.x2, colLeft - 4f), clamped.y2)
                                }
                            }
                        }
                        if (clamped.width <= 10f || clamped.height <= 10f) null else clamped
                    }
                } else shields
            }
            GenderFilter.BOTH -> shields
        }

        val finalToBlur = when (genderFilter) {
            GenderFilter.FEMALE_ONLY -> {
                if (maleFaces.isNotEmpty()) {
                    rawToBlur.filterNot { b ->
                        maleFaces.any { m ->
                            val bodyTop = m.box.y1 - 10f
                            val bodyBottom = m.box.y2 + m.box.height * 5.0f
                            val inY = b.box.y1 < bodyBottom && b.box.y2 > bodyTop
                            val colLeft = m.box.x1 - m.box.width * 0.45f
                            val colRight = m.box.x2 + m.box.width * 0.45f
                            inY && b.box.centerX in colLeft..colRight
                        }
                    }
                } else rawToBlur
            }
            GenderFilter.MALE_ONLY -> {
                if (femaleFaces.isNotEmpty()) {
                    rawToBlur.filterNot { b ->
                        femaleFaces.any { f ->
                            val bodyTop = f.box.y1 - 10f
                            val bodyBottom = f.box.y2 + f.box.height * 5.0f
                            val inY = b.box.y1 < bodyBottom && b.box.y2 > bodyTop
                            val colLeft = f.box.x1 - f.box.width * 0.45f
                            val colRight = f.box.x2 + f.box.width * 0.45f
                            inY && b.box.centerX in colLeft..colRight
                        }
                    }
                } else rawToBlur
            }
            GenderFilter.BOTH -> rawToBlur
        }

        return FilteredDetections(
            toBlur = finalToBlur,
            faces = allFaces,
            hasNsfwContent = finalToBlur.isNotEmpty(),
            mergedBlurBoxes = finalShields
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

            // Strictly align upper torso shield with a face of MATCHING gender:
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
                kotlin.math.abs(refCx - f.box.centerX) < maxOf(c.width * 1.6f, f.box.width * 2.0f) * 0.75f &&
                f.box.y2 <= c.minY + 250f &&
                f.box.y1 <= c.maxY
            }

            // 1. Determine upper torso boundary (neckline / cleavage / chest / back):
            val hasArmpits = c.upper.any { it.label == NudeLabel.ARMPITS_EXPOSED }
            var topY = when {
                matchingFace != null -> matchingFace.box.y2 - 6f
                hasArmpits -> {
                    val upperH = c.upper.maxOf { it.box.height }
                    (c.upper.minOf { it.box.y1 } - maxOf(upperH * 0.70f, 100f)).coerceAtLeast(0f)
                }
                c.upper.isNotEmpty() -> {
                    val upperH = c.upper.maxOf { it.box.height }
                    (c.upper.minOf { it.box.y1 } - maxOf(upperH * 0.40f, 40f)).coerceAtLeast(0f)
                }
                c.belly.isNotEmpty() -> {
                    val bellyH = c.belly.maxOf { it.box.height }
                    (c.belly.minOf { it.box.y1 } - maxOf(bellyH * 0.80f, 100f)).coerceAtLeast(0f)
                }
                c.pelvis.any { it.label == NudeLabel.BUTTOCKS_EXPOSED } -> {
                    val pelvisH = c.pelvis.maxOf { it.box.height }
                    (c.pelvis.minOf { it.box.y1 } - maxOf(pelvisH * 0.70f, 90f)).coerceAtLeast(0f)
                }
                c.pelvis.isNotEmpty() -> {
                    val pelvisH = c.pelvis.maxOf { it.box.height }
                    (c.pelvis.minOf { it.box.y1 } - maxOf(pelvisH * 0.40f, 50f)).coerceAtLeast(0f)
                }
                c.feet.isNotEmpty() -> {
                    val feetH = c.feet.maxOf { it.height }
                    (c.feet.minOf { it.y1 } - maxOf(feetH * 3.5f, 350f)).coerceAtLeast(0f)
                }
                else -> (c.minY - 20f).coerceAtLeast(0f)
            }

            if (matchingFace != null) {
                topY = minOf(topY, matchingFace.box.y2 - 6f)
            }

            // 2. Determine lower boundary (pelvis / full body):
            val botPelvis = when {
                blurFullBody -> {
                    if (c.feet.isNotEmpty()) {
                        c.feet.maxOf { it.y2 } + 15f
                    } else if (matchingFace != null) {
                        matchingFace.box.y2 + matchingFace.box.height * 5.5f
                    } else {
                        c.maxY + maxOf(c.height * 1.5f, 400f)
                    }
                }
                c.pelvis.isNotEmpty() -> {
                    val pelvisH = c.pelvis.maxOf { it.box.height }
                    c.pelvis.maxOf { it.box.y2 } + minOf(pelvisH * 0.20f, 20f)
                }
                c.belly.isNotEmpty() -> {
                    val bellyH = c.belly.maxOf { it.box.height }
                    c.belly.maxOf { it.box.y2 } + minOf(bellyH * 0.45f, 55f)
                }
                c.upper.isNotEmpty() -> {
                    val upperH = c.upper.maxOf { it.box.height }
                    val extent = if (hasArmpits) maxOf(upperH * 1.8f, 240f) else minOf(upperH * 0.35f, 40f)
                    c.maxY + extent
                }
                c.feet.isNotEmpty() -> {
                    c.feet.maxOf { it.y2 } + 15f
                }
                else -> c.maxY + 15f
            }

            // 3. SNUG torso width with multi-person protection:
            val personCenterX = matchingFace?.box?.centerX ?: refCx
            val faceW = matchingFace?.box?.width ?: (c.faces.maxOfOrNull { it.box.width } ?: 0f)

            // Multiple people protection: do not bleed across the midpoint to an adjacent person
            val otherCenters = keptClusters.filter { it !== c }.map { it.centerX } +
                    allFaces.map { it.box.centerX }.filter { kotlin.math.abs(it - personCenterX) > 25f }
            val leftNeighbor = otherCenters.filter { it < personCenterX - 15f }.maxOrNull()
            val rightNeighbor = otherCenters.filter { it > personCenterX + 15f }.minOrNull()

            val maxLeft = if (leftNeighbor != null) (personCenterX + leftNeighbor) / 2f + 4f else 0f
            val maxRight = if (rightNeighbor != null) (personCenterX + rightNeighbor) / 2f - 4f else Float.MAX_VALUE

            val hasBelly = c.belly.isNotEmpty()
            val hasPelvis = c.pelvis.isNotEmpty()

            // Snug, natural body contour that strictly encompasses side-profile breasts and cleavage:
            val padX = if (hasArmpits) 24f else 18f
            val intimateMinX = c.intimateDetections.minOfOrNull { it.box.x1 } ?: c.minX
            val intimateMaxX = c.intimateDetections.maxOfOrNull { it.box.x2 } ?: c.maxX

            var torsoX1 = minOf(c.minX - padX, intimateMinX - padX).coerceAtLeast(0f)
            var torsoX2 = maxOf(c.maxX + padX, intimateMaxX + padX)

            if (matchingFace != null) {
                val span = if (hasArmpits) faceW * 0.95f else faceW * 0.85f
                torsoX1 = minOf(torsoX1, matchingFace.box.centerX - span).coerceAtLeast(0f)
                torsoX2 = maxOf(torsoX2, matchingFace.box.centerX + span)
            }

            // Ensure intimate parts are NEVER clipped:
            torsoX1 = minOf(torsoX1, intimateMinX - 10f).coerceAtLeast(0f)
            torsoX2 = maxOf(torsoX2, intimateMaxX + 10f)

            // Clamp strictly to multi-person midpoint, but never cut into intimate detections
            if (leftNeighbor != null && maxLeft < intimateMinX) {
                torsoX1 = maxOf(torsoX1, maxLeft)
            }
            if (rightNeighbor != null && maxRight > intimateMaxX) {
                torsoX2 = minOf(torsoX2, maxRight)
            }

            if (torsoX2 > torsoX1 && botPelvis > topY) {
                shields.add(BoundingBox(
                    x1 = torsoX1,
                    y1 = topY,
                    x2 = torsoX2,
                    y2 = botPelvis
                ))
            }

            // 4. Leg Shield: ONLY generated when lower body / pelvis / exposed belly is present on screen
            // NEVER generate downward leg shields on upper-body portrait crops or formal dresses!
            if (!blurFullBody) {
                if (isMale && (hasBelly || hasPelvis || c.feet.isNotEmpty())) {
                    val maleLegExtent = if (c.feet.isNotEmpty()) {
                        (c.feet.maxOf { it.y2 } + 10f) - botPelvis
                    } else if (hasPelvis) {
                        minOf(c.height * 0.60f, 200f)
                    } else if (hasBelly && matchingFace != null) {
                        matchingFace.box.height * 2.2f
                    } else {
                        minOf(c.height * 0.40f, 150f)
                    }
                    if (maleLegExtent > 10f) {
                        shields.add(BoundingBox(
                            x1 = torsoX1 + 4f,
                            y1 = botPelvis - 4f,
                            x2 = torsoX2 - 4f,
                            y2 = botPelvis + maleLegExtent
                        ))
                    }
                } else if (!isMale && (hasPelvis || hasBelly || c.feet.isNotEmpty())) {
                    // Female in bikini / swimwear / shorts / naked legs (pelvis, belly, or feet detected)
                    val femaleLegExtent = if (c.feet.isNotEmpty()) {
                        (c.feet.maxOf { it.y2 } + 15f) - botPelvis
                    } else if (matchingFace != null) {
                        maxOf(c.height * 1.2f, matchingFace.box.height * 3.5f, 380f)
                    } else {
                        maxOf(c.height * 1.2f, 350f)
                    }
                    if (femaleLegExtent > 10f) {
                        val legX1 = if (c.feet.isNotEmpty()) minOf(torsoX1, c.feet.minOf { it.x1 } - 10f) else torsoX1 + 4f
                        val legX2 = if (c.feet.isNotEmpty()) maxOf(torsoX2, c.feet.maxOf { it.x2 } + 10f) else torsoX2 - 4f
                        shields.add(BoundingBox(
                            x1 = maxOf(legX1, maxLeft),
                            y1 = botPelvis - 4f,
                            x2 = minOf(legX2, maxRight),
                            y2 = botPelvis + femaleLegExtent
                        ))
                    }
                }
            }

            // Add any other isolated intimate boxes
            for (o in c.others) {
                shields.add(o.box)
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
                    // Merge if overlapping with IoU > 0.10, or if vertically adjacent/overlapping with substantial horizontal overlap (> 40%)
                    val shouldMerge = (ox > 0 && oy >= -8f) && (iou > 0.10f || ox > minOf(curr.width, cand.width) * 0.40f)
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
            NudeLabel.FEMALE_BREAST_EXPOSED -> Pair(w * 0.22f, h * 0.20f)

            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED -> Pair(w * 0.25f, h * 0.22f)

            NudeLabel.BELLY_EXPOSED -> Pair(w * 0.20f, h * 0.18f)

            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED -> Pair(w * 0.22f, h * 0.20f)

            NudeLabel.ARMPITS_EXPOSED -> Pair(w * 0.15f, h * 0.15f)

            NudeLabel.MALE_BREAST_EXPOSED -> Pair(w * 0.18f, h * 0.16f)

            NudeLabel.MALE_GENITALIA_EXPOSED -> Pair(w * 0.22f, h * 0.20f)

            NudeLabel.ANUS_COVERED,
            NudeLabel.ANUS_EXPOSED -> Pair(w * 0.20f, h * 0.18f)

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
        Log.d(TAG, "NudeNetDetector closed")
    }
}
