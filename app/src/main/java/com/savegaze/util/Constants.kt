package com.savegaze.util

object Constants {
    const val MODEL_FILE_NAME = "best_320s.onnx"
    const val MODEL_INPUT_SIZE = 320
    const val NUM_CLASSES = 18
    const val NUM_PREDICTIONS = 2100

    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f
    const val DEFAULT_IOU_THRESHOLD = 0.5f
    const val DEFAULT_BLUR_RADIUS = 25
    const val DEFAULT_SCAN_INTERVAL_MS = 500L

    const val NOTIFICATION_CHANNEL_ID = "savegaze_protection"
    const val NOTIFICATION_ID = 1001

    // Labels that should trigger blur when detected (NSFW body parts)
    // Includes both exposed AND covered intimate parts for semi-nude detection
    val NSFW_LABELS = setOf(
        "FEMALE_GENITALIA_COVERED",
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED",
        "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED",
        "ARMPITS_EXPOSED",
        "FEET_EXPOSED"
    )

    // Labels for faces (excluded from blur)
    val FACE_LABELS = setOf("FACE_FEMALE", "FACE_MALE")

    // Female-specific body part labels
    val FEMALE_LABELS = setOf(
        "FEMALE_GENITALIA_COVERED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "FEMALE_BREAST_COVERED"
    )

    // Male-specific body part labels
    val MALE_LABELS = setOf(
        "MALE_BREAST_EXPOSED",
        "MALE_GENITALIA_EXPOSED"
    )

    // Gender-neutral labels (apply to both genders)
    val NEUTRAL_LABELS = setOf(
        "BUTTOCKS_EXPOSED",
        "ANUS_EXPOSED",
        "BELLY_EXPOSED",
        "ANUS_COVERED",
        "BUTTOCKS_COVERED",
        "ARMPITS_EXPOSED",
        "FEET_EXPOSED"
    )
}
