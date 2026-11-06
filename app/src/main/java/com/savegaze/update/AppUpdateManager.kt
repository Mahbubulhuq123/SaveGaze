package com.savegaze.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.savegaze.data.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateStatus()
    data class DownloadCompleted(val apkFile: File, val info: UpdateInfo) : UpdateStatus()
    data class PermissionRequired(val apkFile: File, val info: UpdateInfo) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class AppUpdateManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager = PreferencesManager(context)
) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val GITHUB_OWNER = "Mahbubulhuq123"
        private const val GITHUB_REPO = "SaveGaze"
        private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        private const val FALLBACK_VERSION_URL = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var downloadJob: Job? = null

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (_status.value is UpdateStatus.Checking || _status.value is UpdateStatus.Downloading) {
            return
        }

        _status.value = UpdateStatus.Checking

        scope.launch {
            try {
                if (!manual) {
                    val autoCheck = preferencesManager.getAutoCheckUpdates()
                    if (!autoCheck) {
                        Log.d(TAG, "Auto check for updates is disabled by user settings")
                        _status.value = UpdateStatus.Idle
                        return@launch
                    }
                }

                val updateInfo = withContext(Dispatchers.IO) {
                    fetchLatestReleaseInfo()
                }

                if (updateInfo != null) {
                    val currentVersion = getCurrentVersionName()
                    val currentCode = getCurrentVersionCode()

                    val isNewer = UpdateInfo.isNewerVersion(
                        remoteVersion = updateInfo.versionName,
                        currentVersion = currentVersion,
                        remoteCode = updateInfo.versionCode,
                        currentCode = currentCode
                    )

                    if (isNewer) {
                        if (!manual) {
                            // Check if user chose "Remind Me Later"
                            val remindUntil = preferencesManager.getRemindMeLaterTimestamp()
                            val now = System.currentTimeMillis()
                            if (remindUntil > now) {
                                Log.d(TAG, "Update v${updateInfo.versionName} postponed until $remindUntil (now=$now)")
                                _status.value = UpdateStatus.Idle
                                return@launch
                            }

                            // Check if user previously cancelled with "Don't show this again"
                            val dismissedVersion = preferencesManager.getDismissedUpdateVersion()
                            if (dismissedVersion == updateInfo.versionName) {
                                Log.d(TAG, "Update v${updateInfo.versionName} was previously dismissed with 'Don't show again'")
                                _status.value = UpdateStatus.Idle
                                return@launch
                            }
                        }

                        _status.value = UpdateStatus.UpdateAvailable(updateInfo)
                    } else {
                        _status.value = if (manual) UpdateStatus.UpToDate else UpdateStatus.Idle
                    }
                } else {
                    _status.value = if (manual) UpdateStatus.UpToDate else UpdateStatus.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                if (manual) {
                    _status.value = UpdateStatus.Error(
                        e.message?.takeIf { it.isNotBlank() } ?: "Could not connect to update server."
                    )
                } else {
                    _status.value = UpdateStatus.Idle
                }
            }
        }
    }

    private fun fetchLatestReleaseInfo(): UpdateInfo? {
        // 1. Try GitHub Releases API first
        try {
            val conn = openConnectionWithRedirects(
                GITHUB_RELEASES_URL,
                headers = mapOf("Accept" to "application/vnd.github.v3+json")
            )
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(jsonStr)
                val info = parseGitHubRelease(json)
                if (info != null) return info
            } else {
                conn.disconnect()
                Log.w(TAG, "GitHub releases returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch from GitHub API, checking fallback version.json", e)
        }

        // 2. Try raw fallback version.json
        try {
            val fallbackConn = openConnectionWithRedirects(FALLBACK_VERSION_URL)
            val code = fallbackConn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val jsonStr = fallbackConn.inputStream.bufferedReader().use { it.readText() }
                fallbackConn.disconnect()
                val json = JSONObject(jsonStr)
                return parseFallbackVersion(json)
            } else {
                fallbackConn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch fallback version.json", e)
        }

        return null
    }

    private fun parseGitHubRelease(json: JSONObject): UpdateInfo? {
        val tagName = json.optString("tag_name", "").trim()
        val versionName = if (tagName.startsWith("v", ignoreCase = true)) tagName.substring(1) else tagName
        if (versionName.isBlank()) return null

        val releaseNotes = json.optString("body", "Bug fixes and performance improvements.").trim()
        val releaseUrl = json.optString("html_url", "")
        val assets = json.optJSONArray("assets") ?: JSONArray()

        val supportedAbis = Build.SUPPORTED_ABIS
        var downloadUrl = ""
        var apkSize = 0L
        var assetName = ""

        // Preferred match: user's primary ABI
        for (abi in supportedAbis) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.contains(abi, ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    assetName = name
                    break
                }
            }
            if (downloadUrl.isNotEmpty()) break
        }

        // Fallback match: Universal APK or any .apk in release
        if (downloadUrl.isEmpty()) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.equals("SaveGaze.apk", ignoreCase = true) || name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    assetName = name
                    break
                }
            }
        }

        if (downloadUrl.isEmpty()) {
            Log.w(TAG, "Release found ($versionName) but no matching .apk asset available")
            return null
        }

        return UpdateInfo(
            versionName = versionName,
            versionCode = 0,
            releaseNotes = releaseNotes,
            downloadUrl = downloadUrl,
            apkSize = apkSize,
            releaseUrl = releaseUrl,
            assetName = assetName,
            publishedAt = json.optString("published_at", "")
        )
    }

    private fun parseFallbackVersion(json: JSONObject): UpdateInfo? {
        val versionName = json.optString("versionName", "").trim()
        if (versionName.isBlank()) return null

        val versionCode = json.optInt("versionCode", 0)
        val releaseNotes = json.optString("releaseNotes", "Bug fixes and improvements.")
        val downloadUrl = json.optString("downloadUrl", "")
        val apkSize = json.optLong("apkSize", 0L)

        if (downloadUrl.isBlank()) return null

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseNotes = releaseNotes,
            downloadUrl = downloadUrl,
            apkSize = apkSize,
            assetName = downloadUrl.substringAfterLast("/"),
            publishedAt = json.optString("publishedAt", "")
        )
    }

    fun startDownload(info: UpdateInfo) {
        if (_status.value is UpdateStatus.Downloading) return

        _status.value = UpdateStatus.Downloading(0f, 0L, info.apkSize)

        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            val updateDir = File(baseDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val tempFile = File(updateDir, "SaveGaze-${info.versionName}.apk.tmp")
            val targetFile = File(updateDir, "SaveGaze-${info.versionName}.apk")

            try {
                if (targetFile.exists() && targetFile.length() > 0L && (info.apkSize <= 0L || targetFile.length() == info.apkSize)) {
                    // Already downloaded!
                    withContext(Dispatchers.Main) {
                        _status.value = UpdateStatus.DownloadCompleted(targetFile, info)
                        handlePostDownload(targetFile, info)
                    }
                    return@launch
                }

                if (tempFile.exists()) tempFile.delete()

                val conn = openConnectionWithRedirects(info.downloadUrl)
                val totalBytes = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.apkSize

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead = 0L
                        var count: Int
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { count = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, count)
                            bytesRead += count

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 150L || bytesRead == totalBytes) {
                                lastEmitTime = now
                                val progress = if (totalBytes > 0) {
                                    (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else 0f

                                withContext(Dispatchers.Main) {
                                    _status.value = UpdateStatus.Downloading(progress, bytesRead, totalBytes)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)

                withContext(Dispatchers.Main) {
                    _status.value = UpdateStatus.DownloadCompleted(targetFile, info)
                    handlePostDownload(targetFile, info)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled by user")
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) {
                    _status.value = UpdateStatus.UpdateAvailable(info)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) {
                    _status.value = UpdateStatus.Error("Download failed: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _status.value = UpdateStatus.Idle
    }

    fun dismissUpdate() {
        _status.value = UpdateStatus.Idle
    }

    fun remindMeLater(delayMillis: Long) {
        scope.launch {
            val remindUntil = if (delayMillis <= 0L) {
                0L
            } else {
                System.currentTimeMillis() + delayMillis
            }
            preferencesManager.setRemindMeLaterTimestamp(remindUntil)
        }
        _status.value = UpdateStatus.Idle
    }

    fun resetUpdatePreferences() {
        scope.launch {
            preferencesManager.clearDismissedUpdateVersion()
            preferencesManager.clearRemindMeLaterTimestamp()
        }
    }

    fun dismissUpdateWithDontShowAgain(versionName: String) {
        scope.launch {
            preferencesManager.setDismissedUpdateVersion(versionName)
        }
        _status.value = UpdateStatus.Idle
    }

    private fun handlePostDownload(apkFile: File, info: UpdateInfo) {
        if (canInstallUnknownApps()) {
            installApk(apkFile)
        } else {
            _status.value = UpdateStatus.PermissionRequired(apkFile, info)
        }
    }

    fun canInstallUnknownApps(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install: APK file does not exist or is empty")
            return false
        }

        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _status.value = UpdateStatus.Error("Failed to open package installer: ${e.message}")
            false
        }
    }

    @Throws(IOException::class)
    private fun openConnectionWithRedirects(
        initialUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects < 5) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "SaveGaze-App-Updater")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.instanceFollowRedirects = true

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("HTTP redirect without Location header")
                }
                currentUrl = location
                redirects++
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects: $redirects")
    }
}
