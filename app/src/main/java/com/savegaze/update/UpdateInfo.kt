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
            // 1. Semantic version name comparison: "1.2" vs "1.0"
            val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

            if (cleanRemote.isNotBlank() && cleanCurrent.isNotBlank()) {
                val remoteParts = cleanRemote.split("-", "+")[0].split(".").mapNotNull { it.toIntOrNull() }
                val currentParts = cleanCurrent.split("-", "+")[0].split(".").mapNotNull { it.toIntOrNull() }

                if (remoteParts.isNotEmpty() && currentParts.isNotEmpty()) {
                    val maxLen = maxOf(remoteParts.size, currentParts.size)
                    for (i in 0 until maxLen) {
                        val r = remoteParts.getOrElse(i) { 0 }
                        val c = currentParts.getOrElse(i) { 0 }
                        if (r > c) return true
                        if (r < c) return false
                    }
                }
            }

            // 2. Fallback: If version names are identical or non-semantic, compare version codes
            if (remoteCode > 0 && currentCode > 0) {
                return remoteCode > currentCode
            }

            return false
        }
    }
}
