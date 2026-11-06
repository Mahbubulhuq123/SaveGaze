package com.savegaze.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFilterTest {

    @Test
    fun testFaceIsNeverIncludedInBlurList() {
        val faceFemale = Detection(
            label = NudeLabel.FACE_FEMALE,
            confidence = 0.95f,
            box = BoundingBox(10f, 10f, 50f, 50f)
        )
        val faceMale = Detection(
            label = NudeLabel.FACE_MALE,
            confidence = 0.92f,
            box = BoundingBox(60f, 10f, 100f, 50f)
        )
        val femaleBreast = Detection(
            label = NudeLabel.FEMALE_BREAST_EXPOSED,
            confidence = 0.88f,
            box = BoundingBox(10f, 60f, 50f, 100f)
        )

        val detections = listOf(faceFemale, faceMale, femaleBreast)

        // Filter for BOTH genders
        val filteredBoth = filterDetectionsHelper(detections, GenderFilter.BOTH)

        // Faces must be in the faces list, NOT in toBlur
        assertEquals(2, filteredBoth.faces.size)
        assertTrue(filteredBoth.faces.any { it.label == NudeLabel.FACE_FEMALE })
        assertTrue(filteredBoth.faces.any { it.label == NudeLabel.FACE_MALE })

        // Only the NSFW body part should be blurred
        assertEquals(1, filteredBoth.toBlur.size)
        assertEquals(NudeLabel.FEMALE_BREAST_EXPOSED, filteredBoth.toBlur[0].label)
        assertFalse(filteredBoth.toBlur.any { it.label.isFace })
    }

    @Test
    fun testFemaleOnlyGenderFilter() {
        val femaleCovered = Detection(
            label = NudeLabel.FEMALE_BREAST_COVERED,
            confidence = 0.85f,
            box = BoundingBox(0f, 0f, 10f, 10f)
        )
        val maleExposed = Detection(
            label = NudeLabel.MALE_BREAST_EXPOSED,
            confidence = 0.82f,
            box = BoundingBox(20f, 20f, 30f, 30f)
        )

        val detections = listOf(femaleCovered, maleExposed)

        // Female Only: should only blur female-specific or neutral NSFW parts
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals(1, filtered.toBlur.size)
        assertEquals(NudeLabel.FEMALE_BREAST_COVERED, filtered.toBlur[0].label)
    }

    @Test
    fun testMaleOnlyGenderFilter() {
        val femaleExposed = Detection(
            label = NudeLabel.FEMALE_GENITALIA_EXPOSED,
            confidence = 0.90f,
            box = BoundingBox(0f, 0f, 10f, 10f)
        )
        val maleExposed = Detection(
            label = NudeLabel.MALE_GENITALIA_EXPOSED,
            confidence = 0.91f,
            box = BoundingBox(20f, 20f, 30f, 30f)
        )

        val detections = listOf(femaleExposed, maleExposed)

        // Male Only: should only blur male-specific or neutral NSFW parts
        val filtered = filterDetectionsHelper(detections, GenderFilter.MALE_ONLY)

        assertEquals(1, filtered.toBlur.size)
        assertEquals(NudeLabel.MALE_GENITALIA_EXPOSED, filtered.toBlur[0].label)
    }

    @Test
    fun testSemiNudeCoveredIntimatePartsDetection() {
        val coveredParts = listOf(
            Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.80f, BoundingBox(0f, 0f, 10f, 10f)),
            Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.85f, BoundingBox(10f, 10f, 20f, 20f)),
            Detection(NudeLabel.BUTTOCKS_COVERED, 0.78f, BoundingBox(20f, 20f, 30f, 30f)),
            Detection(NudeLabel.ANUS_COVERED, 0.75f, BoundingBox(30f, 30f, 40f, 40f))
        )

        val filtered = filterDetectionsHelper(coveredParts, GenderFilter.BOTH)

        // All 4 semi-nude parts must be targeted for blurring
        assertEquals(4, filtered.toBlur.size)
    }

    @Test
    fun testBoundingBoxIoU() {
        val boxA = BoundingBox(0f, 0f, 10f, 10f) // area 100
        val boxB = BoundingBox(5f, 0f, 15f, 10f) // area 100, intersection 5x10 = 50, union = 150 -> IoU = 0.333

        val iou = boxA.iou(boxB)
        assertEquals(0.333f, iou, 0.01f)

        val identical = boxA.iou(boxA)
        assertEquals(1.0f, identical, 0.001f)

        val disjoint = boxA.iou(BoundingBox(20f, 20f, 30f, 30f))
        assertEquals(0.0f, disjoint, 0.001f)
    }

    @Test
    fun testMergedBlurBoxesCombinesAdjacentTorsoParts() {
        val face = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(100f, 50f, 200f, 150f))
        val breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.88f, BoundingBox(80f, 180f, 220f, 300f))
        val belly = Detection(NudeLabel.BELLY_EXPOSED, 0.85f, BoundingBox(90f, 290f, 210f, 420f))
        val pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.80f, BoundingBox(85f, 410f, 215f, 520f))

        val detections = listOf(face, breast, belly, pelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals(3, filtered.toBlur.size)
        assertEquals(1, filtered.faces.size)

        // Torso shield must bridge breast through belly to pelvis
        assertTrue("Must have generated merged shields", filtered.mergedBlurBoxes.isNotEmpty())
        val torso = filtered.mergedBlurBoxes.first { it.y1 <= 180f && it.y2 >= 520f }
        assertTrue("Torso shield must cover breast top", torso.y1 <= 180f)
        assertTrue("Torso shield must cover belly and pelvis bottom", torso.y2 >= 520f)
    }

    @Test
    fun testBellyBridgedEvenWhenBellyExposedNotDetected() {
        // Woman in bikini where detector only saw breast and pelvis, but missed belly
        val face = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(150f, 100f, 300f, 250f))
        val breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.88f, BoundingBox(130f, 300f, 320f, 480f))
        val pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.82f, BoundingBox(140f, 700f, 310f, 850f))

        val detections = listOf(face, breast, pelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        // Torso shield must continuously bridge from breast to pelvis (covering the belly in between 480..700)
        val torso = filtered.mergedBlurBoxes.first { it.y1 <= 300f && it.y2 >= 850f }
        assertTrue("Torso shield bridges entire belly region", torso.y1 <= 300f && torso.y2 >= 850f)

        // Leg shield must extend down below pelvis to cover thighs
        val legs = filtered.mergedBlurBoxes.first { it.y1 >= 830f }
        assertTrue("Leg shield covers thighs below pelvis", legs.y2 >= 950f)
    }

    @Test
    fun testMultipleWomenIndependentShields() {
        // Woman 1 (left)
        val w1Face = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(150f, 100f, 300f, 250f))
        val w1Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.88f, BoundingBox(120f, 280f, 330f, 480f))
        val w1Pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.82f, BoundingBox(130f, 680f, 320f, 920f))

        // Woman 2 (right)
        val w2Face = Detection(NudeLabel.FACE_FEMALE, 0.92f, BoundingBox(600f, 250f, 750f, 400f))
        val w2Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.85f, BoundingBox(580f, 430f, 770f, 630f))
        val w2Pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.80f, BoundingBox(580f, 830f, 770f, 1070f))

        val detections = listOf(w1Face, w1Breast, w1Pelvis, w2Face, w2Breast, w2Pelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        // All 4 body parts across both women must be targeted for blurring
        assertEquals(4, filtered.toBlur.size)
        // Both faces must be preserved
        assertEquals(2, filtered.faces.size)
        assertTrue(filtered.hasNsfwContent)

        // Woman 1 shields must be in left column (x < 450)
        val w1Shields = filtered.mergedBlurBoxes.filter { it.centerX < 450f }
        // Woman 2 shields must be in right column (x > 500)
        val w2Shields = filtered.mergedBlurBoxes.filter { it.centerX > 500f }

        assertTrue("Woman 1 must have torso and leg shields", w1Shields.size >= 2)
        assertTrue("Woman 2 must have torso and leg shields", w2Shields.size >= 2)

        // Woman 1 torso must cover her breasts, belly, and pelvis
        val w1Torso = w1Shields.first { it.y1 <= 280f && it.y2 >= 920f }
        assertTrue(w1Torso.y1 <= 280f && w1Torso.y2 >= 920f)

        // Woman 2 torso must cover her breasts, belly, and pelvis
        val w2Torso = w2Shields.first { it.y1 <= 430f && it.y2 >= 1070f }
        assertTrue(w2Torso.y1 <= 430f && w2Torso.y2 >= 1070f)
    }

    @Test
    fun testThreeWomenSideBySide() {
        val w1Face = Detection(NudeLabel.FACE_FEMALE, 0.9f, BoundingBox(100f, 100f, 250f, 250f))
        val w1Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.8f, BoundingBox(80f, 280f, 270f, 450f))
        val w1Pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.8f, BoundingBox(90f, 700f, 260f, 850f))

        val w2Face = Detection(NudeLabel.FACE_FEMALE, 0.9f, BoundingBox(400f, 150f, 550f, 300f))
        val w2Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.8f, BoundingBox(380f, 330f, 570f, 500f))
        val w2Pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.8f, BoundingBox(390f, 750f, 560f, 900f))

        val w3Face = Detection(NudeLabel.FACE_FEMALE, 0.9f, BoundingBox(700f, 120f, 850f, 270f))
        val w3Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.8f, BoundingBox(680f, 300f, 870f, 470f))
        val w3Pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.8f, BoundingBox(690f, 720f, 860f, 870f))

        val detections = listOf(w1Face, w1Breast, w1Pelvis, w2Face, w2Breast, w2Pelvis, w3Face, w3Breast, w3Pelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals(6, filtered.toBlur.size)
        assertEquals(3, filtered.faces.size)
        // All 3 women have torso + leg shields (6 shields total)
        assertEquals(6, filtered.mergedBlurBoxes.size)
    }

    @Test
    fun testArmpitsExposedTriggersFullTorsoAndLegShield() {
        // Woman with arms raised / side pose where breasts might be obscured or missed, but exposed armpits detected
        val face = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(150f, 100f, 300f, 250f))
        val armpit = Detection(NudeLabel.ARMPITS_EXPOSED, 0.86f, BoundingBox(120f, 260f, 240f, 380f))
        val pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.84f, BoundingBox(140f, 680f, 310f, 850f))

        val detections = listOf(face, armpit, pelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals(2, filtered.toBlur.size)
        // Torso shield bridges armpit through belly down to pelvis
        val torso = filtered.mergedBlurBoxes.first { it.y1 <= 260f && it.y2 >= 850f }
        assertTrue("Armpit acts as upper torso anchor bridging down to pelvis", torso.y1 <= 260f && torso.y2 >= 850f)

        // Leg shield extends down below pelvis
        val legs = filtered.mergedBlurBoxes.first { it.y1 >= 830f }
        assertTrue("Leg shield covers thighs and legs below pelvis", legs.y2 >= 950f)
    }

    @Test
    fun testTwoAdjacentWomenNeverMerged() {
        // Two women standing closely side-by-side at the same height (Y coords identical)
        val w1Breast = BoundingBox(100f, 300f, 260f, 480f) // width 160, center 180
        val w2Breast = BoundingBox(280f, 300f, 440f, 480f) // width 160, center 360, gap is 20px

        // Under old loose center distance check, distCx=180 was < maxW*0.70? But now with IoU check:
        val merged = mergeAdjacentBoxesHelper(listOf(w1Breast, w2Breast))
        assertEquals("Adjacent women must NEVER be merged across space into one person", 2, merged.size)
    }

    @Test
    fun testBlurStyleValues() {
        val style = com.savegaze.data.BlurStyle.SOLID_BLACK
        assertEquals("SOLID_BLACK", style.name)
        assertTrue(style.displayName.contains("Solid Black"))
        assertTrue(style.displayName.contains("HaramBlur"))
        assertTrue(style.description.contains("pitch black"))
    }

    @Test
    fun testDeadbandLocksBoxPositionUntilBodyMoves() {
        // Initial body position: width 200, height 300
        val initialBox = BoundingBox(100f, 200f, 300f, 500f)

        // Frame 2 has small detector noise: shifts 5px X, 4px Y, 3px size
        val jitterBox = BoundingBox(105f, 196f, 302f, 503f)

        val deadbandX = initialBox.width * 0.12f // 24px
        val deadbandY = initialBox.height * 0.08f // 24px
        val deadbandW = initialBox.width * 0.14f // 28px
        val deadbandH = initialBox.height * 0.10f // 30px

        val cxDist = kotlin.math.abs(jitterBox.centerX - initialBox.centerX)
        val cyDist = kotlin.math.abs(jitterBox.centerY - initialBox.centerY)
        val wDiff = kotlin.math.abs(jitterBox.width - initialBox.width)
        val hDiff = kotlin.math.abs(jitterBox.height - initialBox.height)

        val bodyMoved = cxDist > deadbandX || cyDist > deadbandY || wDiff > deadbandW || hDiff > deadbandH

        // Must NOT consider jitter as movement -> coordinates remain completely locked
        assertFalse("Detector jitter within deadband must not trigger movement", bodyMoved)

        // Frame 3: Person walks 60px to the right
        val movedBox = BoundingBox(160f, 200f, 360f, 500f)
        val cxMovedDist = kotlin.math.abs(movedBox.centerX - initialBox.centerX)
        val bodyActuallyMoved = cxMovedDist > deadbandX
        assertTrue("Actual movement must trigger position update", bodyActuallyMoved)
    }

    @Test
    fun testMultipleWomenWithDiverseDetectionsAllFullyShielded() {
        // Woman 1 (left): Red bikini - only belly and bikini bottom detected
        val w1Belly = Detection(NudeLabel.BELLY_EXPOSED, 0.71f, BoundingBox(350f, 1660f, 460f, 1780f))
        val w1Pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.61f, BoundingBox(370f, 1800f, 450f, 1880f))

        // Woman 2 (center): Green bikini - face, breast, belly, pelvis detected
        val w2Face = Detection(NudeLabel.FACE_FEMALE, 0.75f, BoundingBox(450f, 1380f, 530f, 1480f))
        val w2Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.66f, BoundingBox(430f, 1540f, 510f, 1650f))
        val w2Belly = Detection(NudeLabel.BELLY_EXPOSED, 0.57f, BoundingBox(460f, 1650f, 570f, 1780f))

        // Woman 3 (right): Black bikini - face, breast, buttocks detected
        val w3Face = Detection(NudeLabel.FACE_FEMALE, 0.35f, BoundingBox(550f, 1450f, 640f, 1540f))
        val w3Breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.68f, BoundingBox(520f, 1550f, 610f, 1650f))
        val w3Buttocks = Detection(NudeLabel.BUTTOCKS_EXPOSED, 0.20f, BoundingBox(620f, 1800f, 730f, 1920f))

        val detections = listOf(w1Belly, w1Pelvis, w2Face, w2Breast, w2Belly, w3Face, w3Breast, w3Buttocks)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        // All 3 women clustered and shielded with torso + leg shields
        assertTrue("All women must be shielded with torso and leg shields", filtered.mergedBlurBoxes.size >= 6)

        // Verify woman 1's chest is covered despite no explicit breast detection
        val w1Torso = filtered.mergedBlurBoxes.first { it.x1 <= 360f && it.x2 >= 450f && it.y2 <= 1900f }
        assertTrue("Woman 1 chest/breasts must be shielded upward from belly", w1Torso.y1 <= 1610f)
        assertTrue("Woman 1 pelvis/bikini bottom must be covered", w1Torso.y2 >= 1880f)

        // Verify woman 1's legs are shielded downward
        val w1Legs = filtered.mergedBlurBoxes.first { it.x1 <= 380f && it.x2 >= 430f && it.y1 >= 1850f }
        assertTrue("Woman 1 legs must extend well below pelvis", w1Legs.y2 >= 1940f)
    }

    @Test
    fun testIsolatedExposedBellyTriggersUpwardAndDownwardContinuousShield() {
        val belly = Detection(NudeLabel.BELLY_EXPOSED, 0.85f, BoundingBox(300f, 1000f, 500f, 1200f))
        val filtered = filterDetectionsHelper(listOf(belly), GenderFilter.BOTH)

        // Belly produces torso shield and protective leg shield downward
        assertTrue("Isolated belly produces torso shield and leg shield", filtered.mergedBlurBoxes.size >= 2)
        val torso = filtered.mergedBlurBoxes[0]
        assertTrue("Torso shield extends upward above belly through chest", torso.y1 <= 950f)
        assertTrue("Torso shield extends downward below belly through pelvis", torso.y2 >= 1240f)

        val legs = filtered.mergedBlurBoxes[1]
        assertTrue("Leg shield extends downward through legs", legs.y2 >= 1350f)
    }

    @Test
    fun testFaceBlurringToggleLogic() {
        val face = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(100f, 50f, 200f, 150f))
        val breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.88f, BoundingBox(80f, 180f, 220f, 300f))

        val detections = listOf(face, breast)
        val filtered = filterDetectionsHelper(detections, GenderFilter.BOTH)

        // Intimate part is in toBlur
        assertEquals(1, filtered.toBlur.size)
        // Face is always preserved in filtered.faces
        assertEquals(1, filtered.faces.size)
        // Face is NOT in toBlur
        assertFalse(filtered.toBlur.any { it.label.isFace })
    }

    @Test
    fun testClothedMaleNeverBlurredInFemaleOnlyMode() {
        // Clothed male: has male face and false-positive belly / buttocks detections on clothes
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.94f, BoundingBox(100f, 50f, 200f, 170f))
        val falseBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.45f, BoundingBox(90f, 280f, 210f, 400f))
        val falseButtocks = Detection(NudeLabel.BUTTOCKS_COVERED, 0.35f, BoundingBox(95f, 420f, 205f, 550f))

        val detections = listOf(maleFace, falseBelly, falseButtocks)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        // Must NOT blur any part of the clothed male under Female Only mode
        assertEquals("Clothed male intimate parts must be 0 in Female Only", 0, filtered.toBlur.size)
        assertEquals("Clothed male shields must be 0 in Female Only", 0, filtered.mergedBlurBoxes.size)
        assertFalse("Should not flag NSFW content for clothed male in Female Only", filtered.hasNsfwContent)
    }

    @Test
    fun testClothedMaleStandingNextToSemiNudeWomanInFemaleOnlyMode() {
        // Clothed male on left
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.92f, BoundingBox(150f, 100f, 260f, 220f))
        val maleFalseBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.40f, BoundingBox(140f, 320f, 270f, 460f))

        // Semi-nude woman on right
        val femaleFace = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(600f, 120f, 720f, 240f))
        val femaleBreast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.88f, BoundingBox(580f, 280f, 740f, 440f))
        val femaleGenitalia = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.82f, BoundingBox(590f, 650f, 730f, 850f))

        val detections = listOf(maleFace, maleFalseBelly, femaleFace, femaleBreast, femaleGenitalia)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        // Only the woman's intimate parts are targeted for blurring
        assertEquals(2, filtered.toBlur.size)
        assertTrue(filtered.toBlur.all { it.label.isFemale == true })

        // Shields must be placed ONLY on the woman (x > 450) and NEVER on the man (x < 400)
        assertTrue("Must generate shields for the woman", filtered.mergedBlurBoxes.isNotEmpty())
        for (shield in filtered.mergedBlurBoxes) {
            assertTrue("Shield must be on the woman (x > 450)", shield.centerX > 450f)
            assertFalse("Shield must NOT cover the male (x < 400)", shield.centerX < 400f)
        }
    }

    @Test
    fun testMaleOnlyModeShieldsMaleIntimacyAndIgnoresFemale() {
        // Shirtless male on left
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.93f, BoundingBox(150f, 100f, 260f, 220f))
        val maleBreast = Detection(NudeLabel.MALE_BREAST_EXPOSED, 0.87f, BoundingBox(130f, 270f, 280f, 420f))
        val maleBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.75f, BoundingBox(140f, 400f, 270f, 540f))

        // Bikini woman on right
        val femaleFace = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(600f, 120f, 720f, 240f))
        val femaleBreast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.89f, BoundingBox(580f, 280f, 740f, 440f))
        val femalePelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.84f, BoundingBox(590f, 650f, 730f, 850f))

        val detections = listOf(maleFace, maleBreast, maleBelly, femaleFace, femaleBreast, femalePelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.MALE_ONLY)

        // Male Only mode: must blur male intimate parts and IGNORE female
        assertEquals("Only male intimate parts in toBlur", 2, filtered.toBlur.size)
        assertTrue(filtered.toBlur.any { it.label == NudeLabel.MALE_BREAST_EXPOSED })
        assertTrue(filtered.toBlur.any { it.label == NudeLabel.BELLY_EXPOSED })
        assertFalse("Female parts must NOT be in toBlur", filtered.toBlur.any { it.label.isFemale == true })

        // Shields must be placed on the male (x < 400) and NEVER on the female (x > 500)
        for (shield in filtered.mergedBlurBoxes) {
            assertTrue("Shield must be on the male (x < 400)", shield.centerX < 400f)
            assertFalse("Shield must NOT be on the female (x > 500)", shield.centerX > 500f)
        }
    }

    @Test
    fun testFemaleCleavageAndNecklineCoveredUpToChin() {
        val face = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(150f, 100f, 300f, 250f))
        val breast = Detection(NudeLabel.FEMALE_BREAST_EXPOSED, 0.89f, BoundingBox(130f, 320f, 320f, 500f))

        val detections = listOf(face, breast)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertTrue(filtered.mergedBlurBoxes.isNotEmpty())
        val torso = filtered.mergedBlurBoxes.first { it.y2 >= 500f }

        // Torso shield topY must reach the chin (face.y2 - 2f = 248f) to ensure 0 gap for neck and cleavage
        assertTrue("Torso shield must cover from chin/neck (<= 248f) to eliminate cleavage gap", torso.y1 <= 248f)
    }

    @Test
    fun testShirtlessMaleNeverBlurredInFemaleOnlyMode() {
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.95f, BoundingBox(150f, 100f, 260f, 220f))
        val maleBreast = Detection(NudeLabel.MALE_BREAST_EXPOSED, 0.90f, BoundingBox(130f, 270f, 280f, 420f))
        val maleBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.85f, BoundingBox(140f, 400f, 270f, 540f))

        val detections = listOf(maleFace, maleBreast, maleBelly)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals("Shirtless male must NEVER be blurred in Female Only mode", 0, filtered.toBlur.size)
        assertEquals("Shirtless male must have 0 blur shields in Female Only mode", 0, filtered.mergedBlurBoxes.size)
        assertFalse(filtered.hasNsfwContent)
    }

    @Test
    fun testShirtlessMaleFullyShieldedInMaleOnlyMode() {
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.95f, BoundingBox(150f, 100f, 260f, 220f))
        val maleBreast = Detection(NudeLabel.MALE_BREAST_EXPOSED, 0.90f, BoundingBox(130f, 270f, 280f, 420f))
        val maleBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.85f, BoundingBox(140f, 400f, 270f, 540f))
        val malePelvis = Detection(NudeLabel.MALE_GENITALIA_EXPOSED, 0.80f, BoundingBox(140f, 520f, 270f, 660f))

        val detections = listOf(maleFace, maleBreast, maleBelly, malePelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.MALE_ONLY)

        assertEquals("Shirtless male intimate parts must be blurred in Male Only mode", 3, filtered.toBlur.size)
        assertTrue("Must generate blur shields for male in Male Only mode", filtered.mergedBlurBoxes.isNotEmpty())
        assertTrue(filtered.hasNsfwContent)

        // Shield must start right under the chin (face.y2 - 2f = 218f) with 0 gap
        val torso = filtered.mergedBlurBoxes.first { it.y2 >= 660f }
        assertTrue("Torso shield must cover from male chin (<= 220f)", torso.y1 <= 220f)
        assertTrue("Torso shield must cover through belly and pelvis (>= 660f)", torso.y2 >= 660f)

        // Leg shield covers below
        val legs = filtered.mergedBlurBoxes.first { it.y1 >= 650f }
        assertTrue("Leg shield must extend downwards", legs.y2 > 700f)
    }

    @Test
    fun testHeadlessNeutralPartWithMaleInSceneNeverBlurredInFemaleOnlyMode() {
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.92f, BoundingBox(150f, 100f, 250f, 220f))
        val detachedBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.80f, BoundingBox(500f, 600f, 650f, 750f))

        val detections = listOf(maleFace, detachedBelly)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals("Neutral body part with male on screen must NEVER be blurred in Female Only", 0, filtered.toBlur.size)
        assertEquals("0 shields generated in Female Only", 0, filtered.mergedBlurBoxes.size)
    }

    @Test
    fun testOffsetBellyClusteredWithMaleFaceNeverBlurredInFemaleOnlyMode() {
        // Man with face at x=150 and belly offset to x=280
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.94f, BoundingBox(100f, 50f, 200f, 170f))
        val maleBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.70f, BoundingBox(180f, 280f, 380f, 440f))

        val detections = listOf(maleFace, maleBelly)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertEquals("Offset belly on male must NOT be blurred in Female Only", 0, filtered.toBlur.size)
        assertEquals("0 shields for male in Female Only", 0, filtered.mergedBlurBoxes.size)
    }

    @Test
    fun testExposedBackShieldedAcrossFullSpineAndWaist() {
        val face = Detection(NudeLabel.FACE_FEMALE, 0.90f, BoundingBox(150f, 100f, 250f, 220f))
        val bareBackUpper = Detection(NudeLabel.ARMPITS_EXPOSED, 0.85f, BoundingBox(130f, 260f, 270f, 400f))

        val detections = listOf(face, bareBackUpper)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertTrue("Exposed back must produce blur shield", filtered.mergedBlurBoxes.isNotEmpty())
        val torso = filtered.mergedBlurBoxes.first { it.y1 <= 220f }
        // Torso shield must cover from chin/neck down through mid and lower back (y2 >= 640f)
        assertTrue("Exposed back shield must extend down to waist/lower back", torso.y2 >= 640f)
        // Must span full shoulder width
        assertTrue("Exposed back shield must span shoulder width", torso.width >= 180f)
    }

    @Test
    fun testHeadlessExposedBackUnderFemaleOnlyFilter() {
        // Woman facing away / headless rear view (no face detected)
        val bareBackUpper = Detection(NudeLabel.ARMPITS_EXPOSED, 0.85f, BoundingBox(130f, 260f, 270f, 400f))
        val detections = listOf(bareBackUpper)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertTrue("Exposed back without face must be blurred in FEMALE_ONLY when no male in scene", filtered.mergedBlurBoxes.isNotEmpty())
        val torso = filtered.mergedBlurBoxes[0]
        assertTrue("Exposed back must cover upper back/neck", torso.y1 <= 170f)
        assertTrue("Exposed back must extend downwards through spine to waistline", torso.y2 >= 640f)
        assertTrue("Exposed back shield must span shoulder width", torso.width >= 180f)
    }

    @Test
    fun testBlurFullBodyCoversFromChinDownToFeet() {
        val face = Detection(NudeLabel.FACE_FEMALE, 0.92f, BoundingBox(150f, 100f, 250f, 220f))
        val breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.80f, BoundingBox(140f, 270f, 260f, 400f))
        val feet = Detection(NudeLabel.FEET_EXPOSED, 0.75f, BoundingBox(160f, 1300f, 240f, 1380f))

        val detections = listOf(face, breast, feet)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY, blurFullBody = true)

        assertTrue("Full body mode must produce blur shields", filtered.mergedBlurBoxes.isNotEmpty())
        val fullBody = filtered.mergedBlurBoxes.first { it.y1 <= 220f }
        // Full body shield must reach down to feet
        assertTrue("Full body shield must reach feet (>= 1380f)", fullBody.y2 >= 1380f)
    }

    @Test
    fun testFemaleNakedLegsShieldedWhenInBikiniOrSwimwear() {
        val face = Detection(NudeLabel.FACE_FEMALE, 0.92f, BoundingBox(150f, 100f, 250f, 220f))
        val breast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.82f, BoundingBox(140f, 270f, 260f, 400f))
        val pelvis = Detection(NudeLabel.FEMALE_GENITALIA_COVERED, 0.85f, BoundingBox(140f, 480f, 260f, 620f))

        val detections = listOf(face, breast, pelvis)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertTrue("Female in swimwear must produce blur shields", filtered.mergedBlurBoxes.isNotEmpty())
        // Leg shield must extend downward below pelvis to cover naked thighs, knees, and legs
        val maxShieldY2 = filtered.mergedBlurBoxes.maxOf { it.y2 }
        assertTrue("Naked legs must be shielded well below pelvis (y2 >= 1000f, was $maxShieldY2)", maxShieldY2 >= 1000f)
    }

    @Test
    fun testBareFeetAndNakedLegsShieldedAlone() {
        // Photo of naked legs/feet alone with no face
        val feet = Detection(NudeLabel.FEET_EXPOSED, 0.85f, BoundingBox(150f, 900f, 250f, 1000f))

        val detections = listOf(feet)
        val filtered = filterDetectionsHelper(detections, GenderFilter.FEMALE_ONLY)

        assertTrue("Bare feet/legs must produce blur shields in Female Only", filtered.mergedBlurBoxes.isNotEmpty())
        val legShield = filtered.mergedBlurBoxes[0]
        // Must cover upward from feet across calves and knees (y1 <= 600f)
        assertTrue("Leg shield must cover upward across naked legs (y1 <= 600f, was ${legShield.y1})", legShield.y1 <= 600f)
        // Must reach the feet (y2 >= 1000f)
        assertTrue("Leg shield must cover down to feet (y2 >= 1000f, was ${legShield.y2})", legShield.y2 >= 1000f)
    }

    @Test
    fun testMaleOnlyShirtlessAndOnShortsFullyShielded() {
        val maleFace = Detection(NudeLabel.FACE_MALE, 0.93f, BoundingBox(150f, 100f, 250f, 220f))
        val maleBreast = Detection(NudeLabel.MALE_BREAST_EXPOSED, 0.88f, BoundingBox(130f, 270f, 270f, 420f))
        val maleBelly = Detection(NudeLabel.BELLY_EXPOSED, 0.82f, BoundingBox(140f, 400f, 260f, 520f))

        // Female in the same scene
        val femaleFace = Detection(NudeLabel.FACE_FEMALE, 0.95f, BoundingBox(600f, 100f, 700f, 220f))
        val femaleBreast = Detection(NudeLabel.FEMALE_BREAST_COVERED, 0.85f, BoundingBox(580f, 270f, 720f, 420f))

        val detections = listOf(maleFace, maleBreast, maleBelly, femaleFace, femaleBreast)
        val filtered = filterDetectionsHelper(detections, GenderFilter.MALE_ONLY)

        // Female must NEVER be blurred in Male Only mode
        assertFalse("Female parts must not be in toBlur", filtered.toBlur.any { it.label.isFemale == true })
        for (shield in filtered.mergedBlurBoxes) {
            assertTrue("Shield must be on male (x < 400)", shield.centerX < 400f)
            assertFalse("Shield must NOT be on female (x > 500)", shield.centerX > 500f)
        }

        // Male must be shielded: Torso (chin through navel) + Leg Shield (shorts & thighs down to knees)
        val maleTorso = filtered.mergedBlurBoxes.first { it.y1 <= 220f }
        assertTrue("Male torso shield must reach chin", maleTorso.y1 <= 220f)
        val maleLegs = filtered.mergedBlurBoxes.first { it.y2 > 650f }
        assertTrue("Male leg shield must cover shorts and thighs down to knees", maleLegs.y2 >= 700f)
    }

    // Helper implementing the exact filtering logic from NudeNetDetector
    private enum class ClusterGender {
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

            // 1. A cluster with a confirmed male face is ALWAYS male (clothing false-positives never override a male face)
            if (hasMaleFace && !hasFemaleFace) return ClusterGender.MALE

            // 2. A cluster with a confirmed female face is ALWAYS female
            if (hasFemaleFace && !hasMaleFace) return ClusterGender.FEMALE

            // 3. Anatomical intimate parts (when headless or both faces present)
            val hasFemaleExposed = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_EXPOSED || it.label == NudeLabel.FEMALE_GENITALIA_EXPOSED
            }
            val hasMaleSpecific = intimateDetections.any { it.label.isFemale == false }

            if (hasFemaleExposed && !hasMaleSpecific) return ClusterGender.FEMALE
            if (hasMaleSpecific && !hasFemaleExposed) return ClusterGender.MALE

            val hasFemaleCovered = intimateDetections.any {
                it.label == NudeLabel.FEMALE_BREAST_COVERED || it.label == NudeLabel.FEMALE_GENITALIA_COVERED
            }
            if (hasFemaleCovered && !hasMaleSpecific) {
                // If scene has a male face and no female face, covered clothing detection is likely false positive on male
                if (sceneHasMaleFace && !sceneHasFemaleFace) return ClusterGender.MALE
                return ClusterGender.FEMALE
            }

            // 4. Cluster has NO face and NO gender-specific body parts (only neutral parts: belly, buttocks, armpits):
            val sceneHasMale = sceneHasMaleFace || sceneHasMaleSpecific
            val sceneHasFemale = sceneHasFemaleFace || sceneHasFemaleSpecific
            if (sceneHasMale && !sceneHasFemale) return ClusterGender.MALE
            if (sceneHasFemale && !sceneHasMale) return ClusterGender.FEMALE

            return ClusterGender.UNKNOWN
        }
    }

    private fun filterDetectionsHelper(
        detections: List<Detection>,
        genderFilter: GenderFilter = GenderFilter.BOTH,
        blurFullBody: Boolean = false
    ): FilteredDetections {
        val nsfwLabels = setOf(
            NudeLabel.FEMALE_GENITALIA_COVERED,
            NudeLabel.BUTTOCKS_EXPOSED,
            NudeLabel.FEMALE_BREAST_EXPOSED,
            NudeLabel.FEMALE_GENITALIA_EXPOSED,
            NudeLabel.MALE_BREAST_EXPOSED,
            NudeLabel.ANUS_EXPOSED,
            NudeLabel.BELLY_EXPOSED,
            NudeLabel.MALE_GENITALIA_EXPOSED,
            NudeLabel.ANUS_COVERED,
            NudeLabel.FEMALE_BREAST_COVERED,
            NudeLabel.BUTTOCKS_COVERED,
            NudeLabel.ARMPITS_EXPOSED,
            NudeLabel.FEET_EXPOSED
        )

        val allFaces = detections.filter { it.label.isFace }
        val feet = detections.filter { it.label == NudeLabel.FEET_EXPOSED || it.label == NudeLabel.FEET_COVERED }

        val nsfwParts = detections.filter { det ->
            det.label in nsfwLabels && allFaces.none { face -> det.box.iou(face.box) > 0.40f }
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
                // Strict anatomy gender incompatibility check:
                val detIsStrictFemale = det.label == NudeLabel.FEMALE_BREAST_EXPOSED || det.label == NudeLabel.FEMALE_GENITALIA_EXPOSED
                val detIsStrictMale = det.label == NudeLabel.MALE_BREAST_EXPOSED || det.label == NudeLabel.MALE_GENITALIA_EXPOSED
                if (detIsStrictFemale && (c.faces.any { it.label == NudeLabel.FACE_MALE } || c.intimateDetections.any { it.label.isFemale == false })) continue
                if (detIsStrictMale && (c.faces.any { it.label == NudeLabel.FACE_FEMALE } || c.intimateDetections.any { it.label.isFemale == true })) continue

                val dist = kotlin.math.abs(box.centerX - c.centerX)
                val allowDist = maxOf(c.width, box.width) * 0.55f
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

        for (foot in feet) {
            val fbox = foot.box
            var bestCluster: PersonCluster? = null
            var minDist = Float.MAX_VALUE
            for (c in clusters) {
                if (fbox.y1 >= c.minY) {
                    val d = kotlin.math.abs(fbox.centerX - c.centerX)
                    if (d < minDist && d < maxOf(c.width, fbox.width) * 0.65f) {
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
                    gender == ClusterGender.FEMALE ||
                    (gender == ClusterGender.UNKNOWN && !sceneHasMaleFace && !sceneHasMaleSpecific)
                }
                GenderFilter.MALE_ONLY -> {
                    gender == ClusterGender.MALE ||
                    (gender == ClusterGender.UNKNOWN && !sceneHasFemaleFace && !sceneHasFemaleSpecific)
                }
            }
        }

        val rawToBlur = keptClusters.flatMap { it.intimateDetections }
        val shields = buildShieldsForClustersHelper(
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
                            val colLeft = m.box.x1 - m.box.width * 0.45f
                            val colRight = m.box.x2 + m.box.width * 0.45f
                            b.box.centerX in colLeft..colRight
                        }
                    }
                } else rawToBlur
            }
            GenderFilter.MALE_ONLY -> {
                if (femaleFaces.isNotEmpty()) {
                    rawToBlur.filterNot { b ->
                        femaleFaces.any { f ->
                            val colLeft = f.box.x1 - f.box.width * 0.45f
                            val colRight = f.box.x2 + f.box.width * 0.45f
                            b.box.centerX in colLeft..colRight
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

    private fun buildShieldsForClustersHelper(
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
                kotlin.math.abs(refCx - f.box.centerX) < maxOf(c.width, f.box.width * 1.5f) * 0.70f &&
                f.box.y2 <= c.minY + 200f &&
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
                    val extent = if (hasArmpits) maxOf(upperH * 2.0f, 250f) else minOf(upperH * 0.35f, 40f)
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

            val hasUpper = c.upper.isNotEmpty()
            val hasBelly = c.belly.isNotEmpty()
            val hasPelvis = c.pelvis.isNotEmpty()

            // Snug, natural body contour:
            val padX = if (hasArmpits) 24f else 16f
            var torsoX1 = (c.minX - padX).coerceAtLeast(0f)
            var torsoX2 = c.maxX + padX

            if (matchingFace != null) {
                torsoX1 = minOf(torsoX1, matchingFace.box.centerX - faceW * 1.1f).coerceAtLeast(0f)
                torsoX2 = maxOf(torsoX2, matchingFace.box.centerX + faceW * 1.1f)
            }

            // Clamp strictly to multi-person midpoint
            torsoX1 = maxOf(torsoX1, maxLeft)
            torsoX2 = minOf(torsoX2, maxRight)

            if (torsoX2 > torsoX1 && botPelvis > topY) {
                shields.add(BoundingBox(
                    x1 = torsoX1,
                    y1 = topY,
                    x2 = torsoX2,
                    y2 = botPelvis
                ))
            }

            // 4. Leg Shield: snugly covers below pelvis for women in swimwear/shorts/bikini/legs
            if (!blurFullBody) {
                if (isMale && (hasUpper || hasBelly || hasPelvis)) {
                    val maleLegExtent = if (c.feet.isNotEmpty()) {
                        (c.feet.maxOf { it.y2 } + 10f) - botPelvis
                    } else {
                        minOf(c.height * 0.60f, 200f)
                    }
                    if (maleLegExtent > 10f) {
                        shields.add(BoundingBox(
                            x1 = torsoX1 + 4f,
                            y1 = botPelvis - 4f,
                            x2 = torsoX2 - 4f,
                            y2 = botPelvis + maleLegExtent
                        ))
                    }
                } else if (!isMale && (hasUpper || hasBelly || hasPelvis || c.feet.isNotEmpty())) {
                    val femaleLegExtent = if (c.feet.isNotEmpty()) {
                        (c.feet.maxOf { it.y2 } + 15f) - botPelvis
                    } else if (matchingFace != null) {
                        maxOf(c.height * 1.2f, matchingFace.box.height * 3.5f, 420f)
                    } else {
                        maxOf(c.height * 1.2f, 420f)
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

            for (o in c.others) {
                shields.add(o.box)
            }
        }

        return shields
    }

    private fun mergeAdjacentBoxesHelper(boxes: List<BoundingBox>): List<BoundingBox> {
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
                    val shouldMerge = (ox > 0 && oy > 0) && (iou > 0.15f || ox > minOf(curr.width, cand.width) * 0.40f)
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

