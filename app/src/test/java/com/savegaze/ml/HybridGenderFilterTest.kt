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

        // Face Blurring OFF -> whole body below chin (y >= ~218..930), face protected
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
        assertTrue("Starts below chin when face blurring OFF", bodyShield.y1 >= 210f)
        assertEquals(1, bodyOnly.protectedFaces.size)

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
}
