package com.savegaze.ml

enum class NudeLabel(val displayName: String, val isFemale: Boolean?, val isExposed: Boolean, val isFace: Boolean) {
    FEMALE_GENITALIA_COVERED("Female Genitalia Covered", true, false, false),
    FACE_FEMALE("Face Female", true, false, true),
    BUTTOCKS_EXPOSED("Buttocks Exposed", null, true, false),
    FEMALE_BREAST_EXPOSED("Female Breast Exposed", true, true, false),
    FEMALE_GENITALIA_EXPOSED("Female Genitalia Exposed", true, true, false),
    MALE_BREAST_EXPOSED("Male Breast Exposed", false, true, false),
    ANUS_EXPOSED("Anus Exposed", null, true, false),
    FEET_EXPOSED("Feet Exposed", null, true, false),
    BELLY_COVERED("Belly Covered", null, false, false),
    FEET_COVERED("Feet Covered", null, false, false),
    ARMPITS_COVERED("Armpits Covered", null, false, false),
    ARMPITS_EXPOSED("Armpits Exposed", null, true, false),
    FACE_MALE("Face Male", false, false, true),
    BELLY_EXPOSED("Belly Exposed", null, true, false),
    MALE_GENITALIA_EXPOSED("Male Genitalia Exposed", false, true, false),
    ANUS_COVERED("Anus Covered", null, false, false),
    FEMALE_BREAST_COVERED("Female Breast Covered", true, false, false),
    BUTTOCKS_COVERED("Buttocks Covered", null, false, false);

    companion object {
        fun fromIndex(index: Int): NudeLabel? = entries.getOrNull(index)
    }
}

data class Detection(
    val label: NudeLabel,
    val confidence: Float,
    val box: BoundingBox
)

data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f

    fun iou(other: BoundingBox): Float {
        val interX1 = maxOf(x1, other.x1)
        val interY1 = maxOf(y1, other.y1)
        val interX2 = minOf(x2, other.x2)
        val interY2 = minOf(y2, other.y2)

        if (interX2 <= interX1 || interY2 <= interY1) return 0f

        val interArea = (interX2 - interX1) * (interY2 - interY1)
        val area1 = width * height
        val area2 = other.width * other.height
        val unionArea = area1 + area2 - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun scale(scaleX: Float, scaleY: Float): BoundingBox {
        return BoundingBox(x1 * scaleX, y1 * scaleY, x2 * scaleX, y2 * scaleY)
    }
}

enum class GenderFilter {
    MALE_ONLY,
    FEMALE_ONLY,
    BOTH
}

data class FilteredDetections(
    val toBlur: List<Detection>,
    val faces: List<Detection>,
    val hasNsfwContent: Boolean,
    val mergedBlurBoxes: List<BoundingBox> = emptyList()
)
