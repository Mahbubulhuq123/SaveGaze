package com.savegaze.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridGenderFilterTest {

    @Test
    fun hybridPordaPersonCorrectsBiasedFemaleFaceOnShirtlessMale() {
        val personMale = PersonGenderBox(
            gender = PersonGender.MALE,
            confidence = 0.78f,
            box = BoundingBox(100f, 50f, 350f, 700f)
        )
        // Simulate NudeNet misclassifying clean-shaven male face as FACE_FEMALE
        val wrongFace = Detection(NudeLabel.FACE_FEMALE, 0.45f, BoundingBox(170f, 70f, 280f, 190f))
        val maleChest = Detection(NudeLabel.BELLY_EXPOSED, 0.60f, BoundingBox(150f, 220f, 300f, 400f))

        val filtered = NudeNetDetector.filterDetectionsPure(
            detections = listOf(wrongFace, maleChest),
            personDetections = listOf(personMale),
            genderFilter = GenderFilter.FEMALE_ONLY,
            blurFaces = true
        )

        // Man must NOT be blurred in FEMALE_ONLY mode, and his face must be corrected & protected
        assertFalse(filtered.hasNsfwContent)
        assertTrue(filtered.mergedBlurBoxes.isEmpty())
        assertTrue(filtered.faces.any { it.label == NudeLabel.FACE_MALE })
        assertEquals(1, filtered.protectedFaces.size)
    }

    @Test
    fun clothedTargetGenderFaceBlurredAlwaysWhenFaceBlurringIsOn() {
        val woman = PersonGenderBox(
            gender = PersonGender.FEMALE,
            confidence = 0.82f,
            box = BoundingBox(50f, 80f, 320f, 760f)
        )
        val femaleFace = Detection(NudeLabel.FACE_FEMALE, 0.80f, BoundingBox(130f, 100f, 240f, 220f))

        val withFacesOn = NudeNetDetector.filterDetectionsPure(
            detections = listOf(femaleFace),
            personDetections = listOf(woman),
            genderFilter = GenderFilter.FEMALE_ONLY,
            blurFaces = true
        )
        assertTrue(withFacesOn.hasNsfwContent)
        assertEquals(1, withFacesOn.faceBlurBoxes.size)
        val faceBlur = withFacesOn.mergedBlurBoxes.first()
        // Generous padding around face, while NOT covering lower clothed body (< y=300)
        assertTrue(faceBlur.x1 < 130f && faceBlur.x2 > 240f)
        assertTrue(faceBlur.y1 < 100f && faceBlur.y2 > 220f && faceBlur.y2 < 320f)
        assertTrue(withFacesOn.protectedFaces.isEmpty())

        val withFacesOff = NudeNetDetector.filterDetectionsPure(
            detections = listOf(femaleFace),
            personDetections = listOf(woman),
            genderFilter = GenderFilter.FEMALE_ONLY,
            blurFaces = false
        )
        assertFalse(withFacesOff.hasNsfwContent)
        assertEquals(1, withFacesOff.protectedFaces.size)
    }

    @Test
    fun semiNudeTriggersInstantWholeBodyBlurIncludingFaceOnlyWhenToggled() {
        val woman = PersonGenderBox(
            gender = PersonGender.FEMALE,
            confidence = 0.85f,
            box = BoundingBox(100f, 80f, 380f, 920f)
        )
        val face = Detection(NudeLabel.FACE_FEMALE, 0.88f, BoundingBox(180f, 100f, 300f, 220f))
        val cleavage = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.65f, BoundingBox(170f, 280f, 310f, 400f))

        // Face Blurring OFF -> whole body from neck/jawline down (y ~ 203..930), upper face protected
        val bodyOnly = NudeNetDetector.filterDetectionsPure(
            detections = listOf(face, cleavage),
            personDetections = listOf(woman),
            genderFilter = GenderFilter.FEMALE_ONLY,
            blurFaces = false
        )
        assertTrue(bodyOnly.hasNsfwContent)
        assertEquals(1, bodyOnly.mergedBlurBoxes.size)
        val bodyShield = bodyOnly.mergedBlurBoxes.first()
        assertTrue("Whole body covers torso + legs", bodyShield.y2 >= 900f)
        assertTrue("Reaches neck/jawline while keeping upper face clear", bodyShield.y1 in 186f..212f)
        assertEquals(1, bodyOnly.protectedFaces.size)
        assertTrue("Protected face clip ends at jawline so throat stays blurred", bodyOnly.protectedFaces.first().y2 <= 212f)

        // Face Blurring ON -> unified seamless blur covering forehead + face + whole body
        val bodyAndFace = NudeNetDetector.filterDetectionsPure(
            detections = listOf(face, cleavage),
            personDetections = listOf(woman),
            genderFilter = GenderFilter.FEMALE_ONLY,
            blurFaces = true
        )
        assertTrue(bodyAndFace.hasNsfwContent)
        assertEquals(1, bodyAndFace.mergedBlurBoxes.size)
        val fullShield = bodyAndFace.mergedBlurBoxes.first()
        assertTrue("Covers forehead above face y=100", fullShield.y1 <= 85f)
        assertTrue("Covers full legs", fullShield.y2 >= 900f)
        assertTrue("No face clipping on target female when blurFaces=true", bodyAndFace.protectedFaces.isEmpty())
    }

    @Test
    fun decodeYoloHeadExtractsCorrectGenderAndCoordinates() {
        val gridH = 10
        val gridW = 17
        val tensor = Array(21) { FloatArray(gridH * gridW) { -10f } }
        // Anchor 0 of Head 0 at cell (cx=8, cy=5) -> class 1 (FEMALE) high confidence
        val idx = 5 * gridW + 8
        tensor[0][idx] = 0.0f // sigmoid(0)=0.5
        tensor[1][idx] = 0.0f // sigmoid(0)=0.5
        tensor[2][idx] = 0.0f // exp(0)=1 * anchorW(81) / 544
        tensor[3][idx] = 0.0f // exp(0)=1 * anchorH(82) / 320
        tensor[4][idx] = 5.0f // obj ~0.993
        tensor[5][idx] = -5.0f // male ~0.006
        tensor[6][idx] = 5.0f // female ~0.993

        val decoded = GenderPersonDetector.decodeHead(
            headTensor = tensor,
            gridH = gridH,
            gridW = gridW,
            headIndex = 0,
            scaleX = 1f,
            scaleY = 1f,
            offsetY = 0f,
            maxWidth = 544f,
            maxHeight = 320f,
            threshold = 0.25f
        )

        assertEquals(1, decoded.size)
        assertEquals(PersonGender.FEMALE, decoded[0].gender)
        assertTrue(decoded[0].confidence > 0.95f)
        assertEquals(81f, decoded[0].box.width, 1.5f)
        assertEquals(82f, decoded[0].box.height, 1.5f)
    }

    @Test
    fun multiImageGridAndMultiPersonSemiNudeAreAllBlurredInFemaleOnlyMode() {
        // 2x2 grid: top-left male avatar, top-right headless female bikini, bottom-left & bottom-right women
        val maleAvatar = PersonGenderBox(PersonGender.MALE, 0.72f, BoundingBox(20f, 50f, 480f, 550f), 0.12f)
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.85f, BoundingBox(170f, 80f, 300f, 210f))

        // Top-right cropped semi-nude woman (no face, lower-conf grid detection)
        val topRightCleavage = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.33f, BoundingBox(600f, 180f, 760f, 290f))
        val topRightBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.42f, BoundingBox(610f, 295f, 750f, 410f))

        // Bottom-left woman where Porda marginally tilted male (0.28 vs 0.24) despite FEMALE_BREAST_EXPOSED
        val botLeftMislabel = PersonGenderBox(PersonGender.MALE, 0.28f, BoundingBox(30f, 700f, 470f, 1300f), 0.24f)
        val botLeftBreast = Detection(NudeLabel.FEMALE_BREAST_EXPOSED, 0.45f, BoundingBox(150f, 850f, 320f, 970f))

        // Bottom-right semi-nude woman cleanly detected
        val botRightWoman = PersonGenderBox(PersonGender.FEMALE, 0.78f, BoundingBox(560f, 700f, 1020f, 1300f), 0.10f)
        val botRightFace = Detection(NudeLabel.FACE_FEMALE, 0.80f, BoundingBox(710f, 730f, 840f, 850f))
        val botRightCleavage = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.55f, BoundingBox(680f, 900f, 880f, 1040f))

        val filtered = NudeNetDetector.filterDetectionsPure(
            detections = listOf(maleFace, topRightCleavage, topRightBelly, botLeftBreast, botRightFace, botRightCleavage),
            personDetections = listOf(maleAvatar, botLeftMislabel, botRightWoman),
            genderFilter = GenderFilter.FEMALE_ONLY,
            blurFaces = true
        )

        assertTrue(filtered.hasNsfwContent)
        // All 3 female thumbnails/subjects must produce separate blur coverage, while male avatar stays clear
        assertTrue("Top-right cropped semi-nude must be blurred", filtered.mergedBlurBoxes.any { it.centerX > 520f && it.centerY < 600f })
        assertTrue("Bottom-left misclassified semi-nude woman must be blurred", filtered.mergedBlurBoxes.any { it.centerX < 480f && it.centerY > 650f })
        assertTrue("Bottom-right woman must be blurred", filtered.mergedBlurBoxes.any { it.centerX > 520f && it.centerY > 650f })
        assertFalse("Top-left male avatar must NOT be blurred", filtered.mergedBlurBoxes.any { it.centerX < 480f && it.centerY < 600f })
    }
}
