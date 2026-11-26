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
import kotlin.math.abs
import kotlin.math.hypot

class NudeNetDetector(
    context: Context,
    var confidenceThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f
) {
    enum class ClusterGender {
        FEMALE,
        MALE,
        UNKNOWN
    }

    private class PersonCluster {
        val personBoxes = mutableListOf<PersonGenderBox>()
        val faces = mutableListOf<Detection>()
        val intimateDetections = mutableListOf<Detection>()

        val allBoxes: List<BoundingBox>
            get() = personBoxes.map { it.box } + faces.map { it.box } + intimateDetections.map { it.box }
        val minX: Float get() = allBoxes.minOfOrNull { it.x1 } ?: 0f
        val maxX: Float get() = allBoxes.maxOfOrNull { it.x2 } ?: 0f
        val minY: Float get() = allBoxes.minOfOrNull { it.y1 } ?: 0f
        val maxY: Float get() = allBoxes.maxOfOrNull { it.y2 } ?: 0f
        val width: Float get() = maxOf(maxX - minX, 1f)
        val height: Float get() = maxOf(maxY - minY, 1f)
        val centerX: Float get() = if (allBoxes.isEmpty()) 0f else allBoxes.map { it.centerX }.average().toFloat()

        val primaryPersonBox: PersonGenderBox?
            get() = personBoxes.maxByOrNull { it.confidence }

        fun inferGender(): ClusterGender {
            val hasStrongFemaleGen = intimateDetections.any {
                it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED && it.confidence >= 0.42f
            }
            val hasBareFemaleBreast = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_EXPOSED && it.confidence >= 0.36f
            }
            val hasMaleAnatomy = intimateDetections.any {
                it.label == NudeLabel.MALE_GENITALIA_EXPOSED ||
                    (it.label == NudeLabel.MALE_BREAST_EXPOSED && it.confidence >= 0.25f)
            }
            val hasFemaleFace = faces.any { it.label == NudeLabel.FACE_FEMALE }
            val hasMaleFace = faces.any { it.label == NudeLabel.FACE_MALE }

            // 1. Confirmed male chest / genitalia takes strict precedence (unless explicit female genitalia)
            if (hasMaleAnatomy && !hasStrongFemaleGen) return ClusterGender.MALE
            if (hasStrongFemaleGen && !hasMaleAnatomy) return ClusterGender.FEMALE

            // 2. Hybrid PordaV4x3 person/gender detector takes precedence over noisy NudeNet breast hallucinations
            val bestPerson = primaryPersonBox
            if (bestPerson != null && bestPerson.confidence >= 0.24f) {
                val margin = bestPerson.confidence - bestPerson.oppositeConfidence
                val isCloseTie = bestPerson.oppositeConfidence > 0f && margin < 0.14f && bestPerson.confidence < 0.48f
                if (bestPerson.gender == PersonGender.MALE) {
                    val hasStrongFemaleCovered = intimateDetections.any {
                        (it.label == NudeLabel.FEMALE_BREAST_COVERED || it.label == NudeLabel.FEMALE_GENITALIA_COVERED) &&
                            it.confidence >= 0.42f
                    }
                    val contradictedByFemale = hasBareFemaleBreast ||
                        (hasFemaleFace && !hasMaleFace && (isCloseTie || bestPerson.confidence < 0.45f)) ||
                        (hasStrongFemaleCovered && !hasMaleFace && !hasMaleAnatomy && (isCloseTie || bestPerson.confidence < 0.42f))
                    if (!contradictedByFemale) {
                        return ClusterGender.MALE
                    }
                } else {
                    // Guard against near-tie PordaV4x3 FEMALE predictions contradicted by FACE_MALE
                    if (hasMaleFace && !hasFemaleFace && (isCloseTie || bestPerson.confidence < 0.42f)) {
                        return ClusterGender.MALE
                    }
                    if (bestPerson.oppositeConfidence > 0f && margin < 0.06f && bestPerson.confidence < 0.45f) {
                        // Too close to call by Porda alone; fall through to face & anatomy cues
                    } else {
                        return ClusterGender.FEMALE
                    }
                }
            }

            // 3. Fallback to NudeNet face classification
            if (hasMaleFace && !hasFemaleFace) return ClusterGender.MALE
            if (hasFemaleFace && !hasMaleFace) return ClusterGender.FEMALE

            // 4. Bare female breast fallback when no male face contradicts
            if (hasBareFemaleBreast && !hasMaleAnatomy && !hasMaleFace) {
                return ClusterGender.FEMALE
            }

            // 5. Covered female swimwear/lingerie cue when no male contradiction exists in this cluster
            val hasFemaleCovered = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_COVERED ||
                    it.label == NudeLabel.FEMALE_GENITALIA_COVERED
            }
            if (hasFemaleCovered && !hasMaleFace && !hasMaleAnatomy) {
                if (faces.isEmpty() && personBoxes.isEmpty()) return ClusterGender.UNKNOWN
                return ClusterGender.FEMALE
            }

            return ClusterGender.UNKNOWN
        }
    }

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

        private fun padFaceBox(box: BoundingBox): BoundingBox {
            val w = box.width
            val h = box.height
            return BoundingBox(
                x1 = (box.x1 - w * 0.18f).coerceAtLeast(0f),
                y1 = (box.y1 - h * 0.24f).coerceAtLeast(0f),
                x2 = box.x2 + w * 0.18f,
                y2 = box.y2 + h * 0.14f
            )
        }

        private fun estimateHeadBoxFromPerson(person: BoundingBox): BoundingBox {
            val pw = person.width
            val ph = person.height
            val insetX = pw * 0.10f
            // If box is nearly square (head/bust crop from YOLOv4), cover upper portrait; if tall full body, cover head crown.
            val headBottom = if (ph <= pw * 1.45f) {
                person.y1 + ph * 0.72f
            } else {
                person.y1 + minOf(ph * 0.36f, pw * 0.90f)
            }
            return BoundingBox(
                x1 = (person.x1 + insetX).coerceAtLeast(0f),
                y1 = (person.y1 - ph * 0.04f).coerceAtLeast(0f),
                x2 = (person.x2 - insetX).coerceAtLeast(person.x1 + 8f),
                y2 = maxOf(person.y1 + 12f, headBottom)
            )
        }

        /**
         * Pure hybrid filtering & whole-body / face blurring pipeline usable directly in unit tests.
         *
         * Behavior:
         * - Hybrid gender classification merges fast PordaV4x3 person boxes with NudeNet faces & bare anatomy.
         * - Semi-nude / nude body detection always blurs the whole person body (no fragmented individual parts).
         * - When [blurFaces] is enabled, all target-gender faces are thoroughly blurred regardless of clothing,
         *   and combined cleanly with whole-body blur when semi-nude.
         */
        fun filterDetectionsPure(
            detections: List<Detection>,
            personDetections: List<PersonGenderBox> = emptyList(),
            genderFilter: GenderFilter = GenderFilter.BOTH,
            blurFaces: Boolean = false,
            bitmap: Bitmap? = null
        ): FilteredDetections {
            val allFaces = detections.filter { it.label.isFace }

            val rawNsfwParts = detections.filter { det ->
                det.label in NSFW_LABELS && allFaces.none { face -> det.box.iou(face.box) > 0.35f }
            }

            // Skin chrominance gate eliminates modest shirts / non-skin false positives
            val validNsfwParts = if (bitmap != null) {
                rawNsfwParts.filter { det ->
                    when (det.label) {
                        NudeLabel.FEMALE_BREAST_COVERED -> det.confidence >= 0.34f || bitmap.getSkinFraction(det.box) >= 0.10f
                        NudeLabel.MALE_BREAST_EXPOSED -> bitmap.getSkinFraction(det.box) >= 0.22f
                        else -> true
                    }
                }
            } else {
                rawNsfwParts
            }

            // 1. Seed PersonClusters with fast PordaV4x3 person detections, merging overlapping duplicate boxes only
            val clusters = mutableListOf<PersonCluster>()
            for (person in personDetections.sortedByDescending { it.confidence }) {
                val overlapping = clusters.firstOrNull { c ->
                    c.personBoxes.any { pb ->
                        val minW = minOf(person.box.width, pb.box.width)
                        val closeCenter = abs(person.box.centerX - pb.box.centerX) <= minW * 0.40f
                        val inside = person.box.centerX in pb.box.x1..pb.box.x2 &&
                            person.box.centerY in pb.box.y1..pb.box.y2 &&
                            pb.confidence >= person.confidence * 1.20f &&
                            closeCenter
                        person.box.iou(pb.box) > 0.35f || inside
                    }
                }
                if (overlapping != null) {
                    overlapping.personBoxes.add(person)
                } else {
                    clusters.add(PersonCluster().apply { personBoxes.add(person) })
                }
            }

            // 2. Attach NudeNet faces to matching person box, or create orphan face clusters
            for (face in allFaces) {
                val matchingCluster = clusters.firstOrNull { c ->
                    c.personBoxes.any { pb ->
                        val insideX = face.box.centerX in (pb.box.x1 - pb.box.width * 0.15f)..(pb.box.x2 + pb.box.width * 0.15f)
                        val insideTop = face.box.centerY in (pb.box.y1 - pb.box.height * 0.15f)..(pb.box.y2)
                        face.box.iou(pb.box) > 0.05f || (insideX && insideTop)
                    }
                }
                if (matchingCluster != null) {
                    matchingCluster.faces.add(face)
                } else {
                    clusters.add(PersonCluster().apply { faces.add(face) })
                }
            }

            // 3. Assign semi-nude/nude body parts to nearest matching person/face cluster without cross-card stealing
            for (det in validNsfwParts) {
                val box = det.box
                var bestCluster: PersonCluster? = null
                var bestScore = Float.MAX_VALUE

                for (c in clusters) {
                    val isStrictMaleDet = det.label == NudeLabel.MALE_BREAST_EXPOSED || det.label == NudeLabel.MALE_GENITALIA_EXPOSED
                    val isStrictFemaleDet = det.label == NudeLabel.FEMALE_GENITALIA_EXPOSED && det.confidence >= 0.42f
                    val clusterIsConfirmedFemale = c.primaryPersonBox?.let { it.gender == PersonGender.FEMALE && it.confidence >= 0.50f } == true ||
                        c.faces.any { it.label == NudeLabel.FACE_FEMALE }
                    val clusterIsConfirmedMale = c.primaryPersonBox?.let { it.gender == PersonGender.MALE && it.confidence >= 0.50f } == true ||
                        c.faces.any { it.label == NudeLabel.FACE_MALE }
                    if (isStrictMaleDet && clusterIsConfirmedFemale) continue
                    if (isStrictFemaleDet && clusterIsConfirmedMale) continue

                    val insidePerson = c.personBoxes.any { pb ->
                        box.iou(pb.box) > 0.05f ||
                            (box.centerX in (pb.box.x1 - pb.box.width * 0.15f)..(pb.box.x2 + pb.box.width * 0.15f) &&
                                box.centerY in (pb.box.y1 - 16f)..(pb.box.y2 + minOf(pb.box.height * 0.15f, 40f)))
                    }
                    val clusterCenterY = (c.minY + c.maxY) / 2f
                    val dist2d = hypot(box.centerX - c.centerX, (box.centerY - clusterCenterY) * 1.35f)
                    if (insidePerson) {
                        if (dist2d < bestScore) {
                            bestScore = dist2d
                            bestCluster = c
                        }
                        continue
                    }

                    val faceW = c.faces.maxOfOrNull { it.box.width } ?: c.width
                    val faceBottom = c.faces.maxOfOrNull { it.box.y2 } ?: 0f
                    val faceTop = c.faces.minOfOrNull { it.box.y1 } ?: 0f
                    val faceH = c.faces.maxOfOrNull { it.box.height } ?: c.height

                    // Only reach outside known personBoxes when this cluster has no personBox yet
                    if (c.personBoxes.isEmpty()) {
                        if (c.faces.isNotEmpty()) {
                            val vertDist = box.y1 - faceBottom
                            val isBelowFace = box.y1 >= faceTop - 20f
                            val isWithinTorsoReach = vertDist <= faceH * 4.5f
                            val allowHorizDist = maxOf(faceW * 1.6f, box.width * 1.2f)
                            val dx = abs(box.centerX - c.centerX)

                            if (isBelowFace && isWithinTorsoReach && dx < allowHorizDist && dist2d < bestScore) {
                                bestScore = dist2d
                                bestCluster = c
                            }
                        } else if (c.intimateDetections.isNotEmpty()) {
                            // Group vertically adjacent orphan body parts belonging to the same headless person/thumbnail
                            val vertGap = maxOf(box.y1 - c.maxY, c.minY - box.y2, 0f)
                            val maxReachY = maxOf(c.height, box.height) * 0.9f
                            val allowHorizDist = maxOf(c.width, box.width) * 0.95f
                            val dx = abs(box.centerX - c.centerX)
                            if (vertGap <= maxReachY && dx <= allowHorizDist && dist2d < bestScore) {
                                bestScore = dist2d
                                bestCluster = c
                            }
                        }
                    }
                }

                val target = bestCluster ?: PersonCluster().also { clusters.add(it) }
                target.intimateDetections.add(det)
            }

            // Compute resolved gender per cluster
            val clusterGenderMap = clusters.associateWith { c -> c.inferGender() }

            // Correct all face labels using hybrid cluster gender
            val faceCorrectionMap = mutableMapOf<Detection, Detection>()
            for (c in clusters) {
                val g = clusterGenderMap[c] ?: ClusterGender.UNKNOWN
                val updated = c.faces.map { f ->
                    val corrected = when (g) {
                        ClusterGender.MALE -> if (f.label != NudeLabel.FACE_MALE) f.copy(label = NudeLabel.FACE_MALE) else f
                        ClusterGender.FEMALE -> if (f.label != NudeLabel.FACE_FEMALE) f.copy(label = NudeLabel.FACE_FEMALE) else f
                        ClusterGender.UNKNOWN -> f
                    }
                    faceCorrectionMap[f] = corrected
                    corrected
                }
                c.faces.clear()
                c.faces.addAll(updated)
            }

            val correctedFaces = allFaces.map { faceCorrectionMap[it] ?: it }
            val updatedMaleFaces = correctedFaces.filter { it.label == NudeLabel.FACE_MALE }
            val updatedFemaleFaces = correctedFaces.filter { it.label == NudeLabel.FACE_FEMALE }

            // 4. Identify face blur boxes & opposite-gender protected faces (A4)
            val faceBlurBoxes = mutableListOf<BoundingBox>()
            if (blurFaces) {
                for (c in clusters) {
                    val g = clusterGenderMap[c] ?: ClusterGender.UNKNOWN
                    val matchesFilter = when (genderFilter) {
                        GenderFilter.BOTH -> g == ClusterGender.FEMALE || g == ClusterGender.MALE
                        GenderFilter.FEMALE_ONLY -> g == ClusterGender.FEMALE
                        GenderFilter.MALE_ONLY -> g == ClusterGender.MALE
                    }
                    if (!matchesFilter) continue

                    if (c.faces.isNotEmpty()) {
                        c.faces.forEach { f -> faceBlurBoxes.add(padFaceBox(f.box)) }
                    } else if (c.primaryPersonBox != null && c.primaryPersonBox!!.confidence >= 0.34f) {
                        faceBlurBoxes.add(estimateHeadBoxFromPerson(c.primaryPersonBox!!.box))
                    }
                }
            }

            val sceneHasFemaleFace = updatedFemaleFaces.isNotEmpty()
            val sceneHasMaleFace = updatedMaleFaces.isNotEmpty()

            // 5. Filter semi-nude / nude clusters matching target gender
            val keptBodyClusters = clusters.filter { c ->
                if (c.intimateDetections.isEmpty()) return@filter false

                // Reject modest ordinary shirts & isolated bare feet when no semi-nude cue exists
                val hasSemiOrNudeCue = c.intimateDetections.any {
                    it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                        it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED ||
                        it.label == NudeLabel.MALE_GENITALIA_EXPOSED ||
                        it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                        it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                        it.label == NudeLabel.ANUS_EXPOSED ||
                        it.label == NudeLabel.FEMALE_GENITALIA_COVERED ||
                        it.label == NudeLabel.BUTTOCKS_COVERED ||
                        it.label == NudeLabel.ANUS_COVERED ||
                        it.label == NudeLabel.BELLY_EXPOSED ||
                        (it.label == NudeLabel.ARMPITS_EXPOSED && it.confidence >= 0.28f) ||
                        (it.label == NudeLabel.FEMALE_BREAST_COVERED && (it.confidence >= 0.28f || bitmap != null))
                }
                if (!hasSemiOrNudeCue) return@filter false

                val g = clusterGenderMap[c] ?: ClusterGender.UNKNOWN
                val hasMaleFace = c.faces.any { it.label == NudeLabel.FACE_MALE }
                val hasFemaleFace = c.faces.any { it.label == NudeLabel.FACE_FEMALE }

                when (genderFilter) {
                    GenderFilter.BOTH -> {
                        if (g == ClusterGender.MALE || (hasMaleFace && !hasFemaleFace)) {
                            c.intimateDetections.any {
                                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                                    it.label == NudeLabel.BELLY_EXPOSED ||
                                    it.label == NudeLabel.ARMPITS_EXPOSED ||
                                    it.label == NudeLabel.BUTTOCKS_COVERED ||
                                    it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                                    it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                            }
                        } else {
                            true
                        }
                    }
                    GenderFilter.FEMALE_ONLY -> {
                        if (g == ClusterGender.MALE || (hasMaleFace && !hasFemaleFace)) return@filter false
                        if (g == ClusterGender.FEMALE || hasFemaleFace) {
                            return@filter c.intimateDetections.any {
                                it.label != NudeLabel.MALE_BREAST_EXPOSED &&
                                    it.label != NudeLabel.MALE_GENITALIA_EXPOSED
                            }
                        }
                        // Unknown cluster (e.g., cropped/grid thumbnail without visible face): keep semi-nude female cues,
                        // while still rejecting shirtless/shorts males without female anatomy.
                        val hasFemaleAnatomy = c.intimateDetections.any {
                            it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                                it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED ||
                                (it.label == NudeLabel.FEMALE_BREAST_COVERED && it.confidence >= 0.30f) ||
                                (it.label == NudeLabel.FEMALE_GENITALIA_COVERED && it.confidence >= 0.28f)
                        }
                        val hasStrongExplicitFemale = c.intimateDetections.any {
                            (it.label == NudeLabel.FEMALE_BREAST_EXPOSED && it.confidence >= 0.34f) ||
                                (it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED && it.confidence >= 0.30f)
                        }
                        val hasMaleAnat = c.intimateDetections.any {
                            it.label == NudeLabel.MALE_BREAST_EXPOSED || it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                        }
                        if (hasMaleAnat) return@filter false
                        if (sceneHasMaleFace && !sceneHasFemaleFace && !hasFemaleAnatomy) return@filter false

                        val semiCueCount = c.intimateDetections.count {
                            it.label == NudeLabel.BELLY_EXPOSED ||
                                it.label == NudeLabel.ARMPITS_EXPOSED ||
                                it.label == NudeLabel.FEMALE_BREAST_COVERED ||
                                it.label == NudeLabel.FEMALE_GENITALIA_COVERED ||
                                it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                                it.label == NudeLabel.BUTTOCKS_COVERED
                        }
                        hasStrongExplicitFemale || hasFemaleAnatomy || c.intimateDetections.any {
                            (it.label == NudeLabel.BUTTOCKS_EXPOSED && it.confidence >= 0.32f) ||
                                (it.label == NudeLabel.BUTTOCKS_COVERED && it.confidence >= 0.38f)
                        } || (semiCueCount >= 2 && c.intimateDetections.any { it.label == NudeLabel.FEMALE_BREAST_COVERED || it.label == NudeLabel.BUTTOCKS_COVERED || it.label == NudeLabel.BUTTOCKS_EXPOSED })
                    }
                    GenderFilter.MALE_ONLY -> {
                        if (g == ClusterGender.FEMALE) return@filter false
                        if (hasFemaleFace && !hasMaleFace && g != ClusterGender.MALE) return@filter false
                        val maleCue = c.intimateDetections.any {
                            it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                                it.label == NudeLabel.BELLY_EXPOSED ||
                                it.label == NudeLabel.ARMPITS_EXPOSED ||
                                it.label == NudeLabel.BUTTOCKS_COVERED ||
                                it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                                it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                        }
                        val hasMaleAnat = c.intimateDetections.any {
                            it.label == NudeLabel.MALE_BREAST_EXPOSED || it.label == NudeLabel.MALE_GENITALIA_EXPOSED
                        }
                        maleCue && (g == ClusterGender.MALE || hasMaleFace || hasMaleAnat || !hasFemaleFace)
                    }
                }
            }

            val keptBodyFaces = keptBodyClusters.flatMap { it.faces }.toSet()
            val protectedFaces = when {
                !blurFaces -> correctedFaces.map { f ->
                    if (f in keptBodyFaces) {
                        // Protect eyes/nose/upper face while letting body blur cover throat/neck up to the jawline
                        val jawBottom = (f.box.y2 - f.box.height * 0.14f)
                            .coerceAtLeast(f.box.y1 + f.box.height * 0.72f)
                        BoundingBox(f.box.x1, f.box.y1, f.box.x2, jawBottom)
                    } else {
                        f.box
                    }
                }
                genderFilter == GenderFilter.FEMALE_ONLY -> updatedMaleFaces.map { it.box }
                genderFilter == GenderFilter.MALE_ONLY -> updatedFemaleFaces.map { it.box }
                else -> emptyList()
            }

            // Strip any intimate part directly under an opposite-gender face or opposite-gender person cluster
            val oppositeGenderClusters = when (genderFilter) {
                GenderFilter.FEMALE_ONLY -> clusters.filter { clusterGenderMap[it] == ClusterGender.MALE }
                GenderFilter.MALE_ONLY -> clusters.filter { clusterGenderMap[it] == ClusterGender.FEMALE }
                GenderFilter.BOTH -> emptyList()
            }
            val filteredParts = keptBodyClusters.flatMap { it.intimateDetections }.filter { det ->
                !isUnderOppositeFace(det.box, genderFilter, updatedFemaleFaces, updatedMaleFaces)
            }

            // 6. Build INSTANT WHOLE-BODY BLUR BOXES for semi-nude/nude target-gender persons (A3)
            val bodyBoxes = if (filteredParts.isNotEmpty()) {
                keptBodyClusters.mapNotNull { c ->
                    val partsInCluster = c.intimateDetections.filter { it in filteredParts }
                    if (partsInCluster.isEmpty()) return@mapNotNull null

                    val pBox = c.primaryPersonBox?.box
                    val partMinX = partsInCluster.minOf { it.box.x1 }
                    val partMaxX = partsInCluster.maxOf { it.box.x2 }
                    val partMinY = partsInCluster.minOf { it.box.y1 }
                    val partMaxY = partsInCluster.maxOf { it.box.y2 }
                    val clusterBounds = BoundingBox(
                        minOf(partMinX, pBox?.x1 ?: partMinX),
                        minOf(partMinY, pBox?.y1 ?: partMinY),
                        maxOf(partMaxX, pBox?.x2 ?: partMaxX),
                        maxOf(partMaxY, pBox?.y2 ?: partMaxY)
                    )

                    val face = c.faces.minByOrNull { it.box.y1 }
                    val rawBox = if (face != null) {
                        val faceW = face.box.width
                        val faceH = face.box.height
                        val personCenterX = if (pBox != null) pBox.centerX else (face.box.centerX + (partMinX + partMaxX) / 2f) / 2f
                        val halfW = maxOf(faceW * 1.50f, (pBox?.width ?: clusterBounds.width) * 0.54f, (partMaxX - partMinX) * 0.62f)
                        val left = minOf((personCenterX - halfW).coerceAtLeast(0f), partMinX - 10f).coerceAtLeast(0f)
                        val right = maxOf(personCenterX + halfW, partMaxX + 10f)

                        // A4: When face blurring is ON, cover head & face; otherwise reach smoothly up through the throat/neck to the jaw line
                        val topY = if (blurFaces) {
                            minOf(padFaceBox(face.box).y1, pBox?.y1 ?: face.box.y1)
                        } else {
                            (face.box.y2 - faceH * 0.14f)
                                .coerceAtLeast(face.box.y1 + faceH * 0.72f)
                                .coerceAtLeast(0f)
                        }

                        val hasLower = partsInCluster.any {
                            it.label == NudeLabel.FEET_EXPOSED || it.box.y2 > face.box.y2 + faceH * 3.8f
                        }
                        val naturalBottomY = maxOf(
                            partMaxY + 25f,
                            pBox?.y2 ?: 0f,
                            face.box.y2 + faceH * (if (hasLower) 6.2f else 3.4f)
                        )
                        val containerBottom = bitmap?.findMediaContainerBottom(partMaxY, clusterBounds) ?: Float.MAX_VALUE
                        val bottomY = maxOf(partMaxY + 15f, minOf(naturalBottomY, containerBottom))
                        BoundingBox(left, topY, right, bottomY)
                    } else if (pBox != null) {
                        val padX = maxOf(pBox.width * 0.06f, 12f)
                        val left = minOf(pBox.x1 - padX, partMinX - 12f).coerceAtLeast(0f)
                        val right = maxOf(pBox.x2 + padX, partMaxX + 12f)
                        val topY = if (blurFaces) {
                            (pBox.y1 - pBox.height * 0.05f).coerceAtLeast(0f)
                        } else {
                            minOf(pBox.y1, partMinY - 12f).coerceAtLeast(0f)
                        }
                        val naturalBottomY = maxOf(pBox.y2 + 12f, partMaxY + 24f)
                        val containerBottom = bitmap?.findMediaContainerBottom(partMaxY, clusterBounds) ?: Float.MAX_VALUE
                        val bottomY = maxOf(partMaxY + 15f, minOf(naturalBottomY, containerBottom))
                        BoundingBox(left, topY, right, bottomY)
                    } else {
                        val partW = maxOf(partMaxX - partMinX, 1f)
                        val partH = maxOf(partMaxY - partMinY, 1f)
                        val topPart = partsInCluster.minByOrNull { it.box.y1 }!!
                        val topPartH = maxOf(topPart.box.height, 1f)
                        val padX = maxOf(partW * 0.32f, 24f)
                        val left = (partMinX - padX).coerceAtLeast(0f)
                        val right = partMaxX + padX
                        val hasBacksideOnly = partsInCluster.any {
                            it.label == NudeLabel.BUTTOCKS_EXPOSED ||
                                it.label == NudeLabel.BUTTOCKS_COVERED ||
                                it.label == NudeLabel.ANUS_EXPOSED
                        }
                        val hasUpperChest = partsInCluster.any {
                            it.label == NudeLabel.FEMALE_BREAST_COVERED ||
                                it.label == NudeLabel.FEMALE_BREAST_EXPOSED ||
                                it.label == NudeLabel.MALE_BREAST_EXPOSED ||
                                it.label == NudeLabel.ARMPITS_EXPOSED
                        }
                        val topY = when {
                            hasBacksideOnly -> (partMinY - topPartH * 2.2f).coerceAtLeast(0f)
                            blurFaces && hasUpperChest -> (partMinY - topPartH * 1.45f).coerceAtLeast(0f)
                            hasUpperChest -> (partMinY - topPartH * 0.78f).coerceAtLeast(0f)
                            else -> (partMinY - topPartH * 1.25f).coerceAtLeast(0f)
                        }
                        val naturalBottomY = partMaxY + partH * 0.45f
                        val containerBottom = bitmap?.findMediaContainerBottom(partMaxY, clusterBounds) ?: Float.MAX_VALUE
                        val bottomY = maxOf(partMaxY + 15f, minOf(naturalBottomY, containerBottom))
                        BoundingBox(left, topY, right, bottomY)
                    }

                    clampAwayFromOppositeGender(
                        box = rawBox,
                        subjectCenterX = clusterBounds.centerX,
                        protectedMinX = partMinX,
                        protectedMaxX = partMaxX,
                        oppositeClusters = oppositeGenderClusters,
                        oppositeFaces = if (genderFilter == GenderFilter.FEMALE_ONLY) updatedMaleFaces else updatedFemaleFaces
                    )
                }.filter { it.width > 8f && it.height > 8f }
            } else {
                emptyList()
            }

            // Combine whole-body semi-nude boxes + always-on target face boxes
            val allCandidateBlurBoxes = mergeAdjacentBoxes(bodyBoxes + faceBlurBoxes)

            return FilteredDetections(
                toBlur = filteredParts,
                faces = correctedFaces,
                hasNsfwContent = allCandidateBlurBoxes.isNotEmpty(),
                mergedBlurBoxes = allCandidateBlurBoxes,
                faceBlurBoxes = faceBlurBoxes,
                protectedFaces = protectedFaces
            )
        }

        private fun clampAwayFromOppositeGender(
            box: BoundingBox,
            subjectCenterX: Float,
            protectedMinX: Float,
            protectedMaxX: Float,
            oppositeClusters: List<PersonCluster>,
            oppositeFaces: List<Detection>
        ): BoundingBox {
            if (oppositeClusters.isEmpty() && oppositeFaces.isEmpty()) return box
            var x1 = box.x1
            var x2 = box.x2
            val oppCenters = oppositeClusters.filter { oc ->
                oc.maxY >= box.y1 && oc.minY <= box.y2 && abs(oc.centerX - subjectCenterX) > 25f
            }.map { it.centerX } + oppositeFaces.filter { of ->
                of.box.y2 + of.box.height * 5f >= box.y1 && of.box.y1 <= box.y2 && abs(of.box.centerX - subjectCenterX) > 25f
            }.map { it.box.centerX }

            for (oppCx in oppCenters) {
                val midpoint = (subjectCenterX + oppCx) / 2f
                if (oppCx < subjectCenterX && midpoint < protectedMinX) {
                    x1 = maxOf(x1, midpoint + 2f)
                } else if (oppCx > subjectCenterX && midpoint > protectedMaxX) {
                    x2 = minOf(x2, midpoint - 2f)
                }
            }
            return BoundingBox(x1, box.y1, maxOf(x2, x1 + 8f), box.y2)
        }

        private fun isUnderOppositeFace(
            box: BoundingBox,
            genderFilter: GenderFilter,
            femaleFaces: List<Detection>,
            maleFaces: List<Detection>
        ): Boolean {
            val (oppFaces, sameFaces) = when (genderFilter) {
                GenderFilter.FEMALE_ONLY -> maleFaces to femaleFaces
                GenderFilter.MALE_ONLY -> femaleFaces to maleFaces
                GenderFilter.BOTH -> return false
            }
            return oppFaces.any { opp ->
                val bodyTop = opp.box.y1 - 10f
                val bodyBottom = opp.box.y2 + opp.box.height * 5.0f
                val colLeft = opp.box.centerX - opp.box.width * 1.2f
                val colRight = opp.box.centerX + opp.box.width * 1.2f
                val closerSame = sameFaces.any { same ->
                    hypot(same.box.centerX - box.centerX, same.box.y2 - box.y1) <
                        hypot(opp.box.centerX - box.centerX, opp.box.y2 - box.y1)
                }
                !closerSame && box.centerY in bodyTop..bodyBottom && box.centerX in colLeft..colRight
            }
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
                        val shouldMerge = (ox > -12f && oy > -16f) &&
                            (iou > 0.08f || (ox > minOf(curr.width, cand.width) * 0.35f && oy > -16f))
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
            val aspectRatio = originalHeight / originalWidth
            val allDetections = mutableListOf<Detection>()

            // 1. Global pass
            allDetections.addAll(runInference(bitmap, 0f, 0f, originalWidth, originalHeight))

            // 2. Overlapping 2x2 quadrant tiles on tall mobile screens so multi-column image grids stay crisp
            if (aspectRatio > 1.30f && originalHeight > 600f) {
                val quadW = (originalWidth * 0.58f).toInt().coerceAtLeast(1)
                val rightX = (originalWidth * 0.42f).toInt().coerceIn(0, bitmap.width - 1)
                val rightW = (bitmap.width - rightX).coerceAtLeast(1)
                val quadH = (originalHeight * 0.54f).toInt().coerceAtLeast(1)
                val botY = (originalHeight * 0.46f).toInt().coerceIn(0, bitmap.height - 1)
                val botH = (bitmap.height - botY).coerceAtLeast(1)

                val tiles = listOf(
                    intArrayOf(0, 0, quadW, quadH),
                    intArrayOf(rightX, 0, rightW, quadH),
                    intArrayOf(0, botY, quadW, botH),
                    intArrayOf(rightX, botY, rightW, botH)
                )
                for (t in tiles) {
                    val xOff = t[0]
                    val yOff = t[1]
                    val w = minOf(t[2], bitmap.width - xOff)
                    val h = minOf(t[3], bitmap.height - yOff)
                    if (w <= 32 || h <= 32) continue
                    try {
                        val tile = Bitmap.createBitmap(bitmap, xOff, yOff, w, h)
                        allDetections.addAll(runInference(tile, xOff.toFloat(), yOff.toFloat(), w.toFloat(), h.toFloat()))
                        tile.recycle()
                    } catch (e: Exception) {
                        Log.w(TAG, "Tile inference skipped at x=$xOff,y=$yOff", e)
                    }
                }
            }

            val consolidated = nms(allDetections)
            val allFaces = consolidated.filter { it.label.isFace }

            consolidated.filter { det ->
                if (det.label.isFace) {
                    true
                } else if (det.label.isExposed) {
                    val hasNearFace = allFaces.any { f ->
                        abs(f.box.centerX - det.box.centerX) < maxOf(f.box.width * 2.5f, det.box.width * 1.5f) &&
                            det.box.y1 >= f.box.y1 - 10f && det.box.y1 <= f.box.y2 + f.box.height * 6.0f
                    }
                    bitmap.hasHumanSkin(
                        det.box,
                        minFraction = if (hasNearFace) 0.05f else 0.15f,
                        hasNearFace = hasNearFace
                    )
                } else {
                    if (allFaces.isEmpty()) {
                        bitmap.hasHumanSkin(det.box, minFraction = 0.08f, hasNearFace = false)
                    } else {
                        true
                    }
                }
            }
        }
    }

    private fun runInference(
        tileBitmap: Bitmap,
        offsetX: Float,
        offsetY: Float,
        tileWidth: Float,
        tileHeight: Float
    ): List<Detection> {
        val floatBuffer = tileBitmap.toFloatBuffer(MODEL_WIDTH, MODEL_HEIGHT)
        val shape = longArrayOf(1, NUM_CHANNELS.toLong(), MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
            val results = ortSession.run(mapOf(INPUT_NAME to inputTensor))
            val outputTensor = results.get(OUTPUT_NAME)
            if (!outputTensor.isPresent) {
                results.close()
                return emptyList()
            }

            @Suppress("UNCHECKED_CAST")
            val rawOutput = outputTensor.get().value as Array<Array<FloatArray>>
            val detections = postProcess(rawOutput[0], tileWidth, tileHeight, offsetX, offsetY)
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
        tileWidth: Float,
        tileHeight: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): List<Detection> {
        val scaleX = tileWidth / MODEL_WIDTH
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

            // Conservative fallback adjustment for NudeNet face bias
            val femaleFaceScore = output[4 + 1][i]
            val maleFaceScore = output[4 + 12][i]
            if (bestClassIdx == 1 && (maleFaceScore >= femaleFaceScore * 0.55f || maleFaceScore >= 0.14f)) {
                bestClassIdx = 12
                bestClassScore = maxOf(maleFaceScore, bestClassScore)
            }

            val label = NudeLabel.fromIndex(bestClassIdx) ?: continue
            val minThreshold = when (label) {
                NudeLabel.FACE_FEMALE -> 0.18f
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
            val box = BoundingBox(
                x1 = ((cx - w / 2f - padW) * scaleX + offsetX).coerceAtLeast(0f),
                y1 = ((cy - h / 2f - padH) * scaleY + offsetY).coerceAtLeast(0f),
                x2 = ((cx + w / 2f + padW) * scaleX + offsetX).coerceAtLeast(0f),
                y2 = ((cy + h / 2f + padH) * scaleY + offsetY).coerceAtLeast(0f)
            )
            if (box.width > 4f && box.height > 4f) {
                candidates.add(Detection(label, bestClassScore, box))
            }
        }
        return candidates
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        return detections.groupBy { it.label }.values.flatMap { classDets ->
            val sorted = classDets.sortedByDescending { it.confidence }.toMutableList()
            val kept = mutableListOf<Detection>()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { other -> best.box.iou(other.box) > iouThreshold }
            }
            kept
        }
    }

    fun filterDetections(
        detections: List<Detection>,
        personDetections: List<PersonGenderBox> = emptyList(),
        genderFilter: GenderFilter = GenderFilter.BOTH,
        blurFaces: Boolean = false,
        bitmap: Bitmap? = null
    ): FilteredDetections = filterDetectionsPure(
        detections = detections,
        personDetections = personDetections,
        genderFilter = genderFilter,
        blurFaces = blurFaces,
        bitmap = bitmap
    )

    fun mergeAdjacentBoxes(boxes: List<BoundingBox>): List<BoundingBox> =
        NudeNetDetector.mergeAdjacentBoxes(boxes)

    private fun getLabelPadding(label: NudeLabel, w: Float, h: Float): Pair<Float, Float> {
        return when (label) {
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.FEMALE_BREAST_EXPOSED -> Pair(w * 0.25f, h * 0.30f)
            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED -> Pair(w * 0.20f, h * 0.22f)
            NudeLabel.BELLY_EXPOSED -> Pair(w * 0.35f, h * 0.35f)
            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED -> Pair(w * 0.22f, h * 0.25f)
            NudeLabel.ARMPITS_EXPOSED -> Pair(w * 0.65f, h * 0.60f)
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
    }
}
