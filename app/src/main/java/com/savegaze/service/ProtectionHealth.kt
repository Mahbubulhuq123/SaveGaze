package com.savegaze.service

/** Lifecycle of the on-device detector inside the accessibility service. */
enum class DetectorStatus {
    LOADING,
    READY,
    FAILED
}

/**
 * A snapshot of the accessibility service's real operating state.
 *
 * The UI uses this so that "Protection Active" reflects working components
 * (detector, overlay and frame pipeline) rather than only the system toggle.
 */
data class ProtectionHealth(
    val serviceRunning: Boolean = false,
    val detectorStatus: DetectorStatus = DetectorStatus.LOADING,
    val overlayActive: Boolean = false,
    val lastFrameAtMillis: Long = 0L,
    val lastError: String? = null
) {
    val isFullyOperational: Boolean
        get() = serviceRunning &&
            detectorStatus == DetectorStatus.READY &&
            overlayActive
}
