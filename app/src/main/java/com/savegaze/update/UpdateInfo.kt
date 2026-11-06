package com.savegaze.update

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val releaseUrl: String? = null,
    val assetName: String = "",
    val publishedAt: String = ""
) {
    companion object {
        /**
         * Compares remote version with current app version.
         * Returns true if remote version is strictly newer than current version.
         */
        fun isNewerVersion(
            remoteVersion: String,
            currentVersion: String,
            remoteCode: Int = 0,
            currentCode: Int = 0
        ): Boolean {
            // If both version codes are positive, prioritize version code
            if (remoteCode > 0 && currentCode > 0) {
                return remoteCode > currentCode
            }

            // Semantic version name comparison: "v1.2.0" -> [1, 2, 0]
            val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

            if (cleanRemote.isBlank() || cleanCurrent.isBlank()) {
                return false
            }

            val remoteParts = cleanRemote.split("-", "+")[0].split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split("-", "+")[0].split(".").mapNotNull { it.toIntOrNull() }

            if (remoteParts.isEmpty() || currentParts.isEmpty()) {
                return cleanRemote != cleanCurrent
            }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }

            return false
        }
    }
}
