package com.savegaze.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.savegaze.MainActivity
import com.savegaze.R
import com.savegaze.data.BlurStyle
import com.savegaze.data.PreferencesManager
import com.savegaze.guard.ChallengeActivity
import com.savegaze.guard.ContentGuardLists
import com.savegaze.guard.ContentGuardRules
import com.savegaze.ml.AudioIntimateDetector
import com.savegaze.ml.AudioIntimateResult
import com.savegaze.ml.BoundingBox
import com.savegaze.ml.Detection
import com.savegaze.ml.GenderFilter
import com.savegaze.ml.GenderPersonDetector
import com.savegaze.ml.IntimateSceneDetector
import com.savegaze.ml.IntimateSceneRules
import com.savegaze.ml.NudeNetDetector
import com.savegaze.ml.PersonGenderBox
import com.savegaze.ml.SceneSafetyScores
import com.savegaze.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

class SaveGazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SaveGazeA11yService"
        private const val OVERLAY_ERROR = "Screen overlay could not be displayed."

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: SaveGazeAccessibilityService? = null

        private val _health = MutableStateFlow(ProtectionHealth())
        val healthFlow: StateFlow<ProtectionHealth> = _health.asStateFlow()

        private fun publishHealth(transform: (ProtectionHealth) -> ProtectionHealth) {
            _health.update(transform)
        }

        @Volatile
        private var mediaProjectionData: Intent? = null
        private var mediaProjectionResultCode: Int = 0

        @Volatile
        private var onMediaProjectionGranted: (() -> Unit)? = null

        fun setMediaProjectionResult(resultCode: Int, data: Intent) {
            mediaProjectionResultCode = resultCode
            mediaProjectionData = data
            onMediaProjectionGranted?.invoke()
        }

        fun hasMediaProjection(): Boolean = mediaProjectionData != null
        fun isServiceActive(): Boolean = isRunning

        fun clearLockoutUi() {
            instance?.overlayManager?.hideLockoutOverlay()
        }

        fun refreshAudioCapture() {
            instance?.serviceScope?.launch(Dispatchers.Main) {
                if (instance?.mediaProjection == null && mediaProjectionData != null) {
                    instance?.setupMediaProjection()
                } else {
                    instance?.startAudioCaptureIfReady()
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var detector: NudeNetDetector? = null
    private var genderPersonDetector: GenderPersonDetector? = null
    private var intimateSceneDetector: IntimateSceneDetector? = null
    private var audioIntimateDetector: AudioIntimateDetector? = null
    private var overlayManager: OverlayManager? = null
    private var preferencesManager: PreferencesManager? = null

    private var scanJob: Job? = null
    private val scanning = AtomicBoolean(false)
    private val intimateActionInProgress = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2340
    private var screenDensity: Int = 480
    private var lastFrameTime = 0L
    private var currentSensitivity: Float = 0.25f
    private var activeBlurBoxes: List<BoundingBox> = emptyList()
    private var lastScreenSignature: IntArray? = null
    private var lastGenderFilter: GenderFilter? = null
    private var lastForegroundPackage: String? = null
    private var userJustScrolled = false
    private var lastScrollTimestamp = 0L
    private var lastContentTriggerMs = 0L
    private var lastGuardCheckMs = 0L
    private var lastGuardBlockMs = 0L
    private var lastScreenIsStatic = false
    private var notificationShown = false

    // Cached ContentGuard state for ultra-fast synchronous event checks
    @Volatile private var cgWebsiteEnabled = true
    @Volatile private var cgKeywordEnabled = true
    @Volatile private var cgAppEnabled = true
    @Volatile private var cgTamperEnabled = false
    @Volatile private var cgActiveDomains: Set<String> = ContentGuardLists.DEFAULT_ADULT_DOMAINS
    @Volatile private var cgCustomKeywords: Set<String> = emptySet()
    @Volatile private var cgBlockedApps: Set<String> = emptySet()
    @Volatile private var cgLockoutUntilMs = 0L
    @Volatile private var cgTamperUnlockUntilMs = 0L
    @Volatile private var cgHasMasterPassword = false

    // Intimate Video & Audio Guard state
    @Volatile private var isProtectionEnabled = true
    @Volatile private var intimateGuardEnabled = true
    @Volatile private var intimateAudioEnabled = true
    @Volatile private var intimateActionMode = IntimateActionMode.SMART_CONTEXT
    @Volatile private var drmAppPolicy = DrmAppPolicy.WARN_ONLY
    @Volatile private var lastTreeHints: Set<String> = emptySet()
    @Volatile private var lastHasSeekBarOrPlayer = false
    @Volatile private var lastSceneSafety: SceneSafetyScores? = null
    @Volatile private var lastAudioResult = AudioIntimateResult(0f, "", 0f, false)
    @Volatile private var lastAudioScoredMs = 0L
    private var consecutiveIntimateHits = 0
    private var consecutiveAudioHits = 0
    private var consecutiveSkipsOnSameVideo = 0
    private var lastIntimateActionMs = 0L
    private val drmWarnedPackages = mutableSetOf<String>()

    private data class TrackedBox(
        var box: BoundingBox,
        var lastSeenMs: Long = 0L,
        var associatedFace: BoundingBox? = null
    )
    private val trackedBoxes = mutableListOf<TrackedBox>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        publishHealth { ProtectionHealth(serviceRunning = true) }

        preferencesManager = PreferencesManager(this)
        overlayManager = OverlayManager(this).also { manager ->
            manager.setOnVisibilityChangedListener { visible ->
                publishHealth { health ->
                    health.copy(
                        overlayActive = visible,
                        lastError = if (visible && health.lastError == OVERLAY_ERROR) null
                        else if (visible) health.lastError
                        else OVERLAY_ERROR
                    )
                }
            }
            manager.showOverlay()
        }

        observePreferences()

        onMediaProjectionGranted = {
            serviceScope.launch(Dispatchers.Main) {
                setupMediaProjection()
            }
        }

        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    genderPersonDetector = runCatching {
                        GenderPersonDetector(this@SaveGazeAccessibilityService)
                    }.getOrNull()
                    intimateSceneDetector = runCatching {
                        IntimateSceneDetector(this@SaveGazeAccessibilityService)
                    }.getOrNull()
                    audioIntimateDetector = runCatching {
                        AudioIntimateDetector(this@SaveGazeAccessibilityService)
                    }.getOrNull()
                    detector = NudeNetDetector(this@SaveGazeAccessibilityService)
                }
                publishHealth { it.copy(detectorStatus = DetectorStatus.READY) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load detectors", e)
                publishHealth {
                    it.copy(
                        detectorStatus = DetectorStatus.FAILED,
                        lastError = "AI detector failed to load: ${e.message ?: "unknown error"}"
                    )
                }
                return@launch
            }

            createNotificationChannel()
            val protectionEnabled = preferencesManager?.getIsEnabled() ?: true
            showPersistentNotification(paused = !protectionEnabled)

            if (mediaProjectionData != null) {
                setupMediaProjection()
            }
            startScanningLoop()
        }
    }

    private fun observePreferences() {
        val pm = preferencesManager ?: return
        serviceScope.launch {
            pm.sensitivityFlow.collect { s ->
                currentSensitivity = s
                detector?.confidenceThreshold = s
                genderPersonDetector?.confidenceThreshold = (s + 0.02f).coerceIn(0.22f, 0.45f)
            }
        }
        serviceScope.launch {
            pm.blurStyleFlow.collect { style ->
                overlayManager?.setStyleAndOpacity(style, pm.getBlurOpacity())
            }
        }
        serviceScope.launch {
            pm.blurOpacityFlow.collect { opacity ->
                overlayManager?.setStyleAndOpacity(pm.getBlurStyle(), opacity)
            }
        }
        serviceScope.launch {
            pm.blurFacesFlow.collect { enabled ->
                overlayManager?.setBlurFaces(enabled)
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                overlayManager?.clearBlurRegions()
                if (pm.getIsEnabled() && detector != null) performScan()
            }
        }
        serviceScope.launch {
            pm.isEnabledFlow.collect { enabled ->
                isProtectionEnabled = enabled
                if (!enabled) {
                    trackedBoxes.clear()
                    activeBlurBoxes = emptyList()
                    lastScreenSignature = null
                    overlayManager?.clearBlurRegions()
                } else if (detector != null) {
                    performScan()
                }
                updateNotification(paused = !enabled)
            }
        }
        serviceScope.launch {
            pm.genderFilterFlow.collect { filter ->
                if (filter != lastGenderFilter) {
                    lastGenderFilter = filter
                    trackedBoxes.clear()
                    activeBlurBoxes = emptyList()
                    overlayManager?.clearBlurRegions()
                    if (pm.getIsEnabled() && detector != null) performScan()
                }
            }
        }
        serviceScope.launch { pm.websiteBlockerEnabledFlow.collect { cgWebsiteEnabled = it } }
        serviceScope.launch { pm.keywordBlockerEnabledFlow.collect { cgKeywordEnabled = it } }
        serviceScope.launch { pm.appBlockerEnabledFlow.collect { cgAppEnabled = it } }
        serviceScope.launch { pm.tamperGuardEnabledFlow.collect { cgTamperEnabled = it } }
        serviceScope.launch { pm.customKeywordsFlow.collect { cgCustomKeywords = it } }
        serviceScope.launch { pm.blockedAppsFlow.collect { cgBlockedApps = it } }
        serviceScope.launch { pm.tamperUnlockUntilMsFlow.collect { cgTamperUnlockUntilMs = it } }
        serviceScope.launch { pm.masterPasswordHashFlow.collect { cgHasMasterPassword = !it.isNullOrBlank() } }
        serviceScope.launch {
            pm.customWebsitesFlow.collect { custom ->
                cgActiveDomains = ContentGuardRules.buildActiveDomains(custom, pm.getBlockSocialDistractions())
            }
        }
        serviceScope.launch {
            pm.blockSocialDistractionsFlow.collect { social ->
                cgActiveDomains = ContentGuardRules.buildActiveDomains(pm.getCustomWebsites(), social)
            }
        }
        serviceScope.launch {
            pm.lockoutUntilMsFlow.collect { until ->
                cgLockoutUntilMs = until
                syncLockoutOverlay()
            }
        }
        serviceScope.launch { pm.intimateGuardEnabledFlow.collect { intimateGuardEnabled = it } }
        serviceScope.launch {
            pm.intimateAudioEnabledFlow.collect { enabled ->
                intimateAudioEnabled = enabled
                if (!enabled) {
                    audioIntimateDetector?.stop()
                } else {
                    startAudioCaptureIfReady()
                }
            }
        }
        serviceScope.launch { pm.intimateActionModeFlow.collect { intimateActionMode = it } }
        serviceScope.launch { pm.drmAppPolicyFlow.collect { drmAppPolicy = it } }
    }

    private fun syncLockoutOverlay() {
        val now = System.currentTimeMillis()
        if (cgLockoutUntilMs > now) {
            overlayManager?.showLockoutOverlay(
                lockoutUntilMs = cgLockoutUntilMs,
                hasMasterPassword = cgHasMasterPassword,
                onUnlockClick = {
                    val intent = Intent(this, ChallengeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(ChallengeActivity.EXTRA_MODE, ChallengeActivity.MODE_UNLOCK_LOCKOUT)
                        putExtra(ChallengeActivity.EXTRA_TITLE, "Unlock Auto-Lockout")
                    }
                    startActivity(intent)
                },
                onLockoutExpired = {
                    serviceScope.launch { preferencesManager?.clearLockout() }
                }
            )
        } else {
            overlayManager?.hideLockoutOverlay()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // Keep Auto-Lockout enforced across everything except our ChallengeActivity
        if (cgLockoutUntilMs > System.currentTimeMillis() && pkg != packageName) {
            syncLockoutOverlay()
        }

        if (pkg == "com.android.systemui" || pkg == packageName) return

        val now = System.currentTimeMillis()

        // 1. Instant App Blocker, DRM Streaming Guard & Settings Tamper Guard
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (cgAppEnabled && pkg in cgBlockedApps) {
                triggerGuardBlock("App Blocked", "Blocked access to $pkg", goHome = true)
                return
            }
            if (pkg in ContentGuardLists.DRM_PROTECTED_PACKAGES) {
                when (drmAppPolicy) {
                    DrmAppPolicy.AUTO_BLOCK -> {
                        triggerGuardBlock(
                            "DRM Streaming App Blocked",
                            "Blocked $pkg (screen/audio scan disabled by DRM)",
                            goHome = true
                        )
                        return
                    }
                    DrmAppPolicy.WARN_ONLY -> {
                        if (drmWarnedPackages.add(pkg)) {
                            overlayManager?.showBlockBanner(
                                "DRM Notice ($pkg)",
                                "Android blocks screen & audio scanning inside DRM apps. Intimate auto-skip won't run here."
                            )
                        }
                    }
                    DrmAppPolicy.OFF -> Unit
                }
            }
            checkContentGuardWindow(pkg, forceCheck = true)

            val isIme = pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true) ||
                pkg.contains("gboard", ignoreCase = true)
            if (!isIme && pkg.isNotBlank() && pkg != lastForegroundPackage) {
                val switchedApp = lastForegroundPackage != null
                lastForegroundPackage = pkg
                if (switchedApp) {
                    trackedBoxes.clear()
                    activeBlurBoxes = emptyList()
                    lastScreenSignature = null
                    consecutiveIntimateHits = 0
                    consecutiveAudioHits = 0
                    consecutiveSkipsOnSameVideo = 0
                    lastTreeHints = emptySet()
                    lastHasSeekBarOrPlayer = false
                    overlayManager?.clearBlurRegions()
                }
            }
            serviceScope.launch {
                if (preferencesManager?.getIsEnabled() == true) performScan()
            }
            return
        }

        // 2. Content & URL inspection + Instant AI Vision scan on scroll / page changes
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                userJustScrolled = true
                lastScrollTimestamp = now
                if (now - lastGuardCheckMs >= 450L) {
                    checkContentGuardWindow(pkg, forceCheck = false)
                }
                serviceScope.launch {
                    if (preferencesManager?.getIsEnabled() == true) performScan()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastGuardCheckMs >= 450L) {
                    checkContentGuardWindow(pkg, forceCheck = false)
                }
                if (now - lastContentTriggerMs >= 160L) {
                    lastContentTriggerMs = now
                    serviceScope.launch {
                        if (preferencesManager?.getIsEnabled() == true) performScan()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (now - lastGuardCheckMs >= 200L) {
                    checkContentGuardWindow(pkg, forceCheck = true)
                }
            }
        }
    }

    private fun checkContentGuardWindow(pkg: String, forceCheck: Boolean) {
        val now = System.currentTimeMillis()
        if (!forceCheck && now - lastGuardCheckMs < 450L) return
        lastGuardCheckMs = now

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val urls = mutableListOf<String>()
            val searchTexts = mutableListOf<String>()
            val treeHints = mutableSetOf<String>()
            val hasPlayerFlag = booleanArrayOf(false)
            val texts = StringBuilder()
            collectNodeInfo(root, urls, searchTexts, texts, treeHints, hasPlayerFlag, depth = 0, visited = intArrayOf(0))
            lastTreeHints = treeHints
            lastHasSeekBarOrPlayer = hasPlayerFlag[0]
            val allText = texts.toString()

            // A. Tamper / Settings Shield
            if (cgTamperEnabled && now > cgTamperUnlockUntilMs && pkg in ContentGuardLists.SETTINGS_PACKAGES) {
                if (ContentGuardRules.isTamperSettingsScreen(pkg, allText)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    overlayManager?.showBlockBanner(
                        "SaveGaze Protection Shield",
                        "Pass Master Password & 40s Challenge in Guard tab before changing Settings or Uninstall."
                    )
                    return
                }
            }

            // B. App Blocker fallback (if another launcher/split window hosts blocked package)
            if (cgAppEnabled && pkg in cgBlockedApps) {
                triggerGuardBlock("App Blocked", "Blocked app: $pkg", goHome = true)
                return
            }

            // C. Browser URL Bar, Search Input (e.g. Facebook/Social/Search), & Visible Text Blocker
            val skipTextGuard = pkg in ContentGuardLists.SETTINGS_PACKAGES ||
                pkg in ContentGuardLists.SKIP_TEXT_GUARD_PACKAGES ||
                pkg.contains("launcher", ignoreCase = true) ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true) ||
                pkg.contains("gboard", ignoreCase = true)
            if ((cgWebsiteEnabled || cgKeywordEnabled) && !skipTextGuard) {
                val activeDomains = if (cgWebsiteEnabled) cgActiveDomains else emptySet()
                for (u in urls) {
                    val res = ContentGuardRules.checkUrl(
                        rawUrl = u,
                        blockedDomains = activeDomains,
                        customKeywords = cgCustomKeywords,
                        keywordBlockerEnabled = cgKeywordEnabled
                    )
                    if (res.blocked) {
                        triggerGuardBlock(res.reason, res.matchedItem, goHome = false)
                        return
                    }
                }

                for (queryText in searchTexts) {
                    val res = ContentGuardRules.checkSearchOrInputText(
                        rawInput = queryText,
                        blockedDomains = activeDomains,
                        customKeywords = cgCustomKeywords,
                        keywordBlockerEnabled = cgKeywordEnabled
                    )
                    if (res.blocked) {
                        triggerGuardBlock(res.reason, res.matchedItem, goHome = false)
                        return
                    }
                }

                if (cgKeywordEnabled && allText.isNotEmpty()) {
                    val threshold = if (pkg in ContentGuardLists.BROWSER_PACKAGES) 3 else 2
                    val textRes = ContentGuardRules.checkVisibleScreenText(
                        screenText = allText,
                        customKeywords = cgCustomKeywords,
                        blockedDomains = activeDomains,
                        threshold = threshold
                    )
                    if (textRes.blocked) {
                        triggerGuardBlock(textRes.reason, textRes.matchedItem, goHome = false)
                        return
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun collectNodeInfo(
        node: AccessibilityNodeInfo?,
        urls: MutableList<String>,
        searchTexts: MutableList<String>,
        texts: StringBuilder,
        treeHints: MutableSet<String>,
        hasPlayerFlag: BooleanArray,
        depth: Int,
        visited: IntArray
    ) {
        if (node == null || depth > 22 || visited[0] >= 400 || texts.length > 20_000) return
        visited[0]++
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val clsName = node.className?.toString()?.lowercase() ?: ""
        if (viewId.isNotEmpty() && treeHints.size < 80) {
            treeHints.add(viewId)
        }
        if (clsName.contains("seekbar") || viewId.contains("player") || viewId.contains("exo_") || viewId.contains("seek")) {
            hasPlayerFlag[0] = true
        }
        if (!desc.isNullOrBlank()) {
            val ld = desc.lowercase()
            if (ld.contains("reel") || ld.contains("shorts")) treeHints.add(ld.take(40))
            if (ld.contains("fast forward") || ld.contains("skip forward")) hasPlayerFlag[0] = true
        }
        val isSearchOrInput = node.isEditable || ContentGuardLists.SEARCH_BAR_ID_HINTS.any { viewId.contains(it) }

        if (!text.isNullOrBlank()) {
            val looksLikeUrlBar = ContentGuardLists.URL_BAR_ID_HINTS.any { viewId.contains(it) } ||
                text.contains("http://") || text.contains("https://") ||
                (text.contains('.') && !text.contains(' ') && text.length in 4..180)
            if (looksLikeUrlBar) {
                urls.add(text)
            } else if (isSearchOrInput && text.length in 2..200) {
                searchTexts.add(text)
            }
            if (texts.isNotEmpty()) texts.append(' ')
            texts.append(text)
        }
        if (!desc.isNullOrBlank() && desc != text) {
            if (isSearchOrInput && desc.length in 2..200) {
                searchTexts.add(desc)
            }
            if (texts.isNotEmpty()) texts.append(' ')
            texts.append(desc)
        }

        val count = minOf(node.childCount, 50)
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectNodeInfo(child, urls, searchTexts, texts, treeHints, hasPlayerFlag, depth + 1, visited)
            @Suppress("DEPRECATION")
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    private fun triggerGuardBlock(reason: String, detail: String, goHome: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastGuardBlockMs < 1_800L) return
        lastGuardBlockMs = now

        if (goHome) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        overlayManager?.showBlockBanner(reason, "Blocked: $detail")

        serviceScope.launch {
            val update = preferencesManager?.recordCrossBlockAndMaybeLockout(now)
            if (update?.triggeredLockout == true) {
                cgLockoutUntilMs = update.lockoutUntilMs
                syncLockoutOverlay()
            }
        }
    }

    override fun onInterrupt() {
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun startScanningLoop() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            while (isActive) {
                try {
                    val enabled = preferencesManager?.getIsEnabled() ?: true
                    if (enabled) {
                        performScan()
                    } else {
                        overlayManager?.clearBlurRegions()
                    }

                    val baseInterval = preferencesManager?.getScanInterval() ?: 250L
                    val interval = if (activeBlurBoxes.isNotEmpty() && !lastScreenIsStatic) {
                        minOf(baseInterval, 130L)
                    } else {
                        minOf(baseInterval, 220L)
                    }
                    delay(interval)
                } catch (_: Exception) {
                    delay(250L)
                }
            }
        }
    }

    private fun performScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!scanning.compareAndSet(false, true)) return
            performNativeScreenshot()
        } else if (virtualDisplay == null) {
            setupMediaProjection()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun performNativeScreenshot() {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        serviceScope.launch {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                hwBuffer.close()
                                if (hwBitmap != null) {
                                    val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                    if (softBitmap != null) {
                                        processBitmap(softBitmap)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing screenshot", e)
                            } finally {
                                scanning.set(false)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        scanning.set(false)
                    }
                }
            )
        } catch (_: Exception) {
            scanning.set(false)
        }
    }

    @Synchronized
    private fun setupMediaProjection() {
        val data = mediaProjectionData ?: return
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return

        // Android 10–17 requires foregroundServiceType=mediaProjection active before getMediaProjection()
        showPersistentNotification(paused = !isProtectionEnabled, requireProjectionFgs = true)

        if (mediaProjection == null) {
            mediaProjection = try {
                mpManager.getMediaProjection(mediaProjectionResultCode, data)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get MediaProjection", e)
                return
            }
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Start playback audio capture on Android 10+ regardless of whether VirtualDisplay is needed
        startAudioCaptureIfReady()

        // Android 11+ uses AccessibilityService.takeScreenshot() for display capture; VirtualDisplay is only for API < 30
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || virtualDisplay != null) return

        if (handlerThread == null) {
            handlerThread = HandlerThread("SaveGazeCaptureThread").apply { start() }
            backgroundHandler = Handler(handlerThread!!.looper)
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader -> handleCapturedFrame(reader) }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SaveGazeVirtualDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )
    }

    private fun startAudioCaptureIfReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!intimateGuardEnabled || !intimateAudioEnabled) return
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) return

        val mp = mediaProjection ?: return
        val audioDetector = audioIntimateDetector ?: return
        if (audioDetector.isCapturing()) return

        val started = audioDetector.start(mp, serviceScope) { result ->
            lastAudioResult = result
            lastAudioScoredMs = System.currentTimeMillis()
            handleAudioResult(result)
        }
        Log.i(TAG, "Audio playback capture started=$started")
    }

    private fun handleAudioResult(result: AudioIntimateResult) {
        if (!intimateGuardEnabled || !intimateAudioEnabled) return
        if (intimateActionInProgress.get()) return
        val pkg = lastForegroundPackage ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg in ContentGuardLists.SETTINGS_PACKAGES) return

        if (result.score >= 0.45f) {
            consecutiveAudioHits++
            if (consecutiveAudioHits >= 2) {
                consecutiveAudioHits = 0
                serviceScope.launch(Dispatchers.Main) {
                    val signals = com.savegaze.ml.IntimateSignals(
                        triggered = true,
                        instant = true,
                        reason = if (result.topLabel.isNotBlank()) "Intimate Audio (${result.topLabel})" else "Intimate Audio",
                        confidence = result.score,
                        isKissOrBedCue = false
                    )
                    triggerIntimateAction(signals)
                }
            }
        } else {
            consecutiveAudioHits = 0
        }
    }

    private fun handleCapturedFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < 140L || !scanning.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameTime = now
        processImage(image)
    }

    private fun processImage(image: Image) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cleanBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            serviceScope.launch(Dispatchers.Default) {
                try {
                    processBitmap(cleanBitmap)
                } finally {
                    scanning.set(false)
                }
            }
        } catch (_: Exception) {
            image.close()
            scanning.set(false)
        }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        val currentDetector = detector ?: run {
            bitmap.recycle()
            return
        }

        try {
            if (preferencesManager?.getIsEnabled() == false) {
                overlayManager?.clearBlurRegions()
                return
            }

            val bWidth = bitmap.width.toFloat()
            val bHeight = bitmap.height.toFloat()
            val sensitivity = preferencesManager?.getSensitivity() ?: 0.25f
            currentDetector.confidenceThreshold = sensitivity
            genderPersonDetector?.confidenceThreshold = (sensitivity + 0.02f).coerceIn(0.22f, 0.45f)

            // Run PordaV4x3 person/gender + NudeNet + SwiftFormer Scene Safety concurrently on Dispatchers.Default
            val runSceneClassifier = intimateGuardEnabled &&
                (!lastScreenIsStatic || activeBlurBoxes.isNotEmpty() || (System.currentTimeMillis() - lastAudioScoredMs < 2_000L && lastAudioResult.score >= 0.18f))

            val (detections: List<Detection>, personBoxes: List<PersonGenderBox>, sceneSafety: SceneSafetyScores?) = withContext(Dispatchers.Default) {
                val nudeJob = async { currentDetector.detect(bitmap) }
                val genderJob = async { genderPersonDetector?.detect(bitmap) ?: emptyList() }
                val safetyJob = async { if (runSceneClassifier) intimateSceneDetector?.classify(bitmap) else null }
                Triple(nudeJob.await(), genderJob.await(), safetyJob.await())
            }
            if (sceneSafety != null) {
                lastSceneSafety = sceneSafety
            }

            val genderFilter = preferencesManager?.getGenderFilter() ?: GenderFilter.BOTH
            if (lastGenderFilter != null && lastGenderFilter != genderFilter) {
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                overlayManager?.clearBlurRegions()
            }
            lastGenderFilter = genderFilter

            val shouldBlurFaces = preferencesManager?.getBlurFaces() ?: false
            val filtered = currentDetector.filterDetections(
                detections = detections,
                personDetections = personBoxes,
                genderFilter = genderFilter,
                blurFaces = shouldBlurFaces,
                bitmap = bitmap
            )

            val allExcluded = activeBlurBoxes + trackedBoxes.map { it.box }
            val currentSignature = computeScreenSignature(bitmap, allExcluded)
            val screenChange = analyzeScreenChange(lastScreenSignature, currentSignature)
            lastScreenIsStatic = screenChange.isStatic

            val now = System.currentTimeMillis()
            val didScrollRecently = userJustScrolled && (now - lastScrollTimestamp < 450L)
            if (userJustScrolled && !didScrollRecently) {
                userJustScrolled = false
            }

            val matchingFaces = when (genderFilter) {
                GenderFilter.BOTH -> filtered.faces
                GenderFilter.FEMALE_ONLY -> filtered.faces.filter { it.label.isFemale == true }
                GenderFilter.MALE_ONLY -> filtered.faces.filter { it.label.isFemale == false }
            }
            val oppositeFaces = when (genderFilter) {
                GenderFilter.BOTH -> emptyList()
                GenderFilter.FEMALE_ONLY -> filtered.faces.filter { it.label.isFemale == false }
                GenderFilter.MALE_ONLY -> filtered.faces.filter { it.label.isFemale == true }
            }

            val boxesToApply = updateTrackedBoxes(
                candidateBoxes = filtered.mergedBlurBoxes,
                matchingFaces = matchingFaces,
                oppositeFaces = oppositeFaces,
                screenW = bWidth,
                screenH = bHeight,
                now = now,
                isStaticScreen = lastScreenIsStatic,
                isSceneCut = screenChange.isSceneCut,
                didScroll = userJustScrolled,
                shouldBlurFaces = shouldBlurFaces
            )

            if (preferencesManager?.getIsEnabled() != true) {
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                lastScreenSignature = currentSignature
                overlayManager?.clearBlurRegions()
            } else if (boxesToApply.isNotEmpty()) {
                activeBlurBoxes = boxesToApply
                if (currentSignature.count { it != -1 } >= 8) {
                    lastScreenSignature = currentSignature
                }
                overlayManager?.updateBlurRegions(boxesToApply, filtered.protectedFaces)
            } else {
                activeBlurBoxes = emptyList()
                lastScreenSignature = currentSignature
                overlayManager?.clearBlurRegions()
            }

            // Evaluate Intimate Video / Kissing / Bed Scene / Audio Auto-Action ("blur first, then act")
            val activePkg = lastForegroundPackage ?: ""
            val skipIntimateExit = activePkg.isBlank() ||
                activePkg == packageName ||
                activePkg == "com.android.systemui" ||
                activePkg in ContentGuardLists.SETTINGS_PACKAGES ||
                activePkg in ContentGuardLists.SKIP_TEXT_GUARD_PACKAGES ||
                activePkg.contains("launcher", ignoreCase = true)

            if (intimateGuardEnabled && !skipIntimateExit && !intimateActionInProgress.get()) {
                val freshAudioScore = if (now - lastAudioScoredMs < 2_000L && intimateAudioEnabled) lastAudioResult.score else 0f
                val freshAudioLabel = if (now - lastAudioScoredMs < 2_000L && intimateAudioEnabled) lastAudioResult.topLabel else ""
                val evalSafety = sceneSafety ?: if (!lastScreenIsStatic) null else lastSceneSafety
                val signals = IntimateSceneRules.evaluate(
                    detections = detections,
                    personBoxes = personBoxes,
                    sceneSafety = evalSafety,
                    audioScore = freshAudioScore,
                    audioLabel = freshAudioLabel,
                    screenW = bWidth,
                    screenH = bHeight,
                    sensitivity = sensitivity
                )
                if (signals.triggered) {
                    consecutiveIntimateHits++
                    val neededHits = if (signals.instant) 1 else 2
                    if (consecutiveIntimateHits >= neededHits && (now - lastIntimateActionMs >= 2_200L)) {
                        consecutiveIntimateHits = 0
                        serviceScope.launch(Dispatchers.Main) {
                            triggerIntimateAction(signals)
                        }
                    }
                } else {
                    consecutiveIntimateHits = 0
                }
            }

            publishHealth {
                it.copy(
                    lastFrameAtMillis = System.currentTimeMillis(),
                    lastError = if (it.overlayActive) null else it.lastError
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processBitmap", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun updateTrackedBoxes(
        candidateBoxes: List<BoundingBox>,
        matchingFaces: List<Detection>,
        oppositeFaces: List<Detection>,
        screenW: Float,
        screenH: Float,
        now: Long,
        isStaticScreen: Boolean,
        isSceneCut: Boolean,
        didScroll: Boolean,
        shouldBlurFaces: Boolean
    ): List<BoundingBox> {
        if (oppositeFaces.isNotEmpty()) {
            trackedBoxes.removeAll { tracked ->
                oppositeFaces.any { opp ->
                    val touchesFace = tracked.box.iou(opp.box) > 0.08f
                    val bodyTop = opp.box.y1
                    val bodyBottom = opp.box.y2 + opp.box.height * 5.0f
                    val colLeft = opp.box.centerX - opp.box.width * 1.0f
                    val colRight = opp.box.centerX + opp.box.width * 1.0f
                    val underOpp = tracked.box.centerY in bodyTop..bodyBottom && tracked.box.centerX in colLeft..colRight
                    val hasCloserSame = matchingFaces.any { mf ->
                        hypot(mf.box.centerX - tracked.box.centerX, (mf.box.y2 - tracked.box.y1).coerceAtLeast(0f)) <
                            hypot(opp.box.centerX - tracked.box.centerX, (opp.box.y2 - tracked.box.y1).coerceAtLeast(0f))
                    }
                    touchesFace || (!hasCloserSame && underOpp)
                }
            }
        }

        if (candidateBoxes.isEmpty() && matchingFaces.isEmpty()) {
            if (isSceneCut) {
                userJustScrolled = false
                trackedBoxes.clear()
                return emptyList()
            }
            if (isStaticScreen && !didScroll && oppositeFaces.isEmpty() && trackedBoxes.isNotEmpty()) {
                trackedBoxes.forEach { it.lastSeenMs = now }
                return trackedBoxes.map { it.box }
            }
            val holdMs = when {
                didScroll -> 380L
                isStaticScreen -> 2200L
                else -> 900L
            }
            trackedBoxes.removeAll { now - it.lastSeenMs > holdMs }
            return trackedBoxes.map { it.box }
        }

        if (isSceneCut && candidateBoxes.isEmpty()) {
            userJustScrolled = false
            trackedBoxes.clear()
            return emptyList()
        }

        val matched = mutableSetOf<Int>()
        for (cand in candidateBoxes) {
            var bestIdx = -1
            var bestScore = 0f
            for (i in trackedBoxes.indices) {
                if (i in matched) continue
                val t = trackedBoxes[i]
                val iou = cand.iou(t.box)
                if (iou > 0.10f && iou > bestScore) {
                    bestScore = iou
                    bestIdx = i
                } else if (bestScore == 0f) {
                    val dx = abs(cand.centerX - t.box.centerX)
                    val dy = abs(cand.centerY - t.box.centerY)
                    if (dx < maxOf(cand.width, t.box.width) * 0.55f && dy < maxOf(cand.height, t.box.height) * 0.55f) {
                        bestIdx = i
                    }
                }
            }

            val nearFace = matchingFaces.firstOrNull { f ->
                abs(f.box.centerX - cand.centerX) < maxOf(cand.width, f.box.width * 1.8f) && f.box.y2 <= cand.y2 + 40f
            }?.box

            if (bestIdx >= 0) {
                matched.add(bestIdx)
                val t = trackedBoxes[bestIdx]
                t.lastSeenMs = now
                if (nearFace != null) t.associatedFace = nearFace
                val clampedCand = BoundingBox(
                    cand.x1.coerceIn(0f, screenW),
                    cand.y1.coerceIn(0f, screenH),
                    cand.x2.coerceIn(0f, screenW),
                    cand.y2.coerceIn(0f, screenH)
                )
                val deadband = if (isStaticScreen) 12f else 6f
                val withinDeadband =
                    abs(clampedCand.x1 - t.box.x1) <= deadband &&
                    abs(clampedCand.y1 - t.box.y1) <= deadband &&
                    abs(clampedCand.x2 - t.box.x2) <= deadband &&
                    abs(clampedCand.y2 - t.box.y2) <= deadband
                // Never shrink aggressively on a static screen when our own opaque overlay hides parts of the subject
                val isOccludedShrink = isStaticScreen && !didScroll && !isSceneCut &&
                    (clampedCand.width < t.box.width * 0.85f || clampedCand.height < t.box.height * 0.85f)
                if (!withinDeadband && !isOccludedShrink) {
                    val alpha = if (isStaticScreen) 0.30f else 0.65f
                    val cx = t.box.centerX * (1f - alpha) + clampedCand.centerX * alpha
                    val cy = t.box.centerY * (1f - alpha) + clampedCand.centerY * alpha
                    val w = t.box.width * (1f - alpha) + clampedCand.width * alpha
                    val h = t.box.height * (1f - alpha) + clampedCand.height * alpha
                    t.box = BoundingBox(
                        (cx - w / 2f).coerceIn(0f, screenW),
                        (cy - h / 2f).coerceIn(0f, screenH),
                        (cx + w / 2f).coerceIn(0f, screenW),
                        (cy + h / 2f).coerceIn(0f, screenH)
                    )
                }
            } else {
                trackedBoxes.add(
                    TrackedBox(
                        BoundingBox(
                            cand.x1.coerceIn(0f, screenW),
                            cand.y1.coerceIn(0f, screenH),
                            cand.x2.coerceIn(0f, screenW),
                            cand.y2.coerceIn(0f, screenH)
                        ),
                        now,
                        nearFace
                    )
                )
                matched.add(trackedBoxes.lastIndex)
            }
        }

        for (i in trackedBoxes.indices) {
            if (i in matched) continue
            val t = trackedBoxes[i]
            if (isStaticScreen && !didScroll && !isSceneCut && oppositeFaces.isEmpty()) {
                t.lastSeenMs = now
            }
        }

        val holdTimeMs = when {
            didScroll -> 220L
            isStaticScreen -> 2200L
            matchingFaces.isNotEmpty() || (trackedBoxes.isNotEmpty() && shouldBlurFaces) -> 850L
            else -> 550L
        }
        trackedBoxes.removeAll { now - it.lastSeenMs > holdTimeMs }
        return trackedBoxes.map { it.box }
    }

    private data class ScreenChangeResult(val isStatic: Boolean, val isSceneCut: Boolean)

    private fun computeScreenSignature(bitmap: Bitmap, excludeBoxes: List<BoundingBox>): IntArray {
        val sig = IntArray(196)
        val w = bitmap.width
        val h = bitmap.height
        var idx = 0
        for (row in 1..14) {
            for (col in 1..14) {
                val x = (w * col / 15).coerceIn(0, w - 1)
                val y = (h * row / 15).coerceIn(0, h - 1)
                val inside = excludeBoxes.any { b ->
                    x.toFloat() in (b.x1 - 6f)..(b.x2 + 6f) && y.toFloat() in (b.y1 - 6f)..(b.y2 + 6f)
                }
                sig[idx++] = if (inside) -1 else bitmap.getPixel(x, y)
            }
        }
        return sig
    }

    private fun analyzeScreenChange(sig1: IntArray?, sig2: IntArray?): ScreenChangeResult {
        if (sig1 == null || sig2 == null || sig1.size != sig2.size) {
            return ScreenChangeResult(isStatic = false, isSceneCut = false)
        }
        var totalValid = 0
        var totalMatches = 0
        for (i in sig1.indices) {
            val c1 = sig1[i]
            val c2 = sig2[i]
            if (c1 == -1 || c2 == -1) continue
            totalValid++
            val diff = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)) +
                abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)) +
                abs((c1 and 0xFF) - (c2 and 0xFF))
            if (diff < 36) totalMatches++
        }
        if (totalValid < 8) return ScreenChangeResult(isStatic = true, isSceneCut = false)
        val rate = totalMatches.toFloat() / totalValid.toFloat()
        return ScreenChangeResult(isStatic = rate >= 0.88f, isSceneCut = rate < 0.30f)
    }

    private fun triggerIntimateAction(signals: com.savegaze.ml.IntimateSignals) {
        val now = System.currentTimeMillis()
        if (now - lastIntimateActionMs < 2_200L) return
        if (!intimateActionInProgress.compareAndSet(false, true)) return

        val pkg = lastForegroundPackage ?: ""
        val (freshHints, freshSeekBar) = inspectCurrentMediaHints()
        val combinedHints = lastTreeHints + freshHints
        val hasSeekOrPlayer = lastHasSeekBarOrPlayer || freshSeekBar
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val mediaContext = IntimateExitRules.classifyContext(
            packageName = pkg,
            treeHints = combinedHints,
            hasSeekBarOrPlayerControls = hasSeekOrPlayer,
            isLandscape = isLandscape
        )
        val action = IntimateExitRules.chooseAction(
            context = mediaContext,
            mode = intimateActionMode,
            consecutiveSkipsOnSameVideo = consecutiveSkipsOnSameVideo
        )
        val actionDetail = when (action) {
            ExitActionType.NEXT_REEL_SWIPE -> "Blurring first & swiping to next reel..."
            ExitActionType.SKIP_SCENE_FORWARD -> "Blurring first & skipping scene forward..."
            ExitActionType.PAUSE_AND_BACK -> "Blurring first & exiting video..."
        }

        // Step 1: BLUR FIRST immediately across full screen so nothing stays visible
        val (w, h) = currentDisplaySize()
        overlayManager?.showIntimateShieldOverlay(signals.reason, actionDetail)
        overlayManager?.updateBlurRegions(listOf(BoundingBox(0f, 0f, w.toFloat(), h.toFloat())), emptyList())

        serviceScope.launch(Dispatchers.Main) {
            try {
                // Keep shield drawn before navigation/skip gesture fires
                delay(240L)

                when (action) {
                    ExitActionType.NEXT_REEL_SWIPE -> {
                        consecutiveSkipsOnSameVideo++
                        performSwipeNextReel(w, h)
                        delay(780L)
                    }
                    ExitActionType.SKIP_SCENE_FORWARD -> {
                        consecutiveSkipsOnSameVideo++
                        val clickedNode = tryClickForwardNode()
                        if (!clickedNode) {
                            performSkipSceneForward(w, h)
                        }
                        delay(1_150L)
                    }
                    ExitActionType.PAUSE_AND_BACK -> {
                        consecutiveSkipsOnSameVideo = 0
                        performPauseAndBack(signals.reason)
                        delay(650L)
                    }
                }
            } finally {
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                lastScreenSignature = null
                overlayManager?.clearBlurRegions()
                overlayManager?.hideIntimateShieldOverlay()
                lastIntimateActionMs = System.currentTimeMillis()
                intimateActionInProgress.set(false)
            }
        }
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    private fun inspectCurrentMediaHints(): Pair<Set<String>, Boolean> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return emptySet<String>() to false
        val hints = mutableSetOf<String>()
        val hasPlayer = booleanArrayOf(false)
        try {
            val urls = mutableListOf<String>()
            val searches = mutableListOf<String>()
            val sb = StringBuilder()
            collectNodeInfo(root, urls, searches, sb, hints, hasPlayer, depth = 0, visited = intArrayOf(0))
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
        return hints to hasPlayer[0]
    }

    private fun performSwipeNextReel(w: Int, h: Int) {
        val x = w * 0.50f
        val path = Path().apply {
            moveTo(x, h * 0.78f)
            lineTo(x, h * 0.20f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 180L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun tryClickForwardNode(): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        return try {
            findAndClickForward(root, depth = 0)
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun findAndClickForward(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 14) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val matchesForward = (desc.contains("forward") || desc.contains("skip forward") ||
            desc.contains("10 seconds") || desc.contains("15 seconds") || desc.contains("30 seconds") ||
            text.contains("forward") || viewId.contains("fast_forward") || viewId.contains("ffwd")) &&
            node.isClickable
        if (matchesForward) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until minOf(node.childCount, 30)) {
            val child = node.getChild(i) ?: continue
            val hit = findAndClickForward(child, depth + 1)
            @Suppress("DEPRECATION")
            try { child.recycle() } catch (_: Exception) {}
            if (hit) return true
        }
        return false
    }

    private fun performSkipSceneForward(w: Int, h: Int) {
        val tapX = w * 0.82f
        val tapY = h * 0.45f
        val builder = GestureDescription.Builder()
        // 3 rapid right-side taps trigger +10s/+20s seek in YouTube, ExoPlayer, Nuvio, Stremio, VLC & HTML5 players
        for (i in 0..2) {
            val p = Path().apply { moveTo(tapX, tapY) }
            builder.addStroke(GestureDescription.StrokeDescription(p, i * 115L, 25L))
        }
        dispatchGesture(builder.build(), null, null)
    }

    private fun performPauseAndBack(reason: String) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                am.requestAudioFocus(req)
                am.abandonAudioFocusRequest(req)
            }
        } catch (_: Exception) {
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        overlayManager?.showBlockBanner("Exited Intimate Scene", reason)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.accessibility_service_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showPersistentNotification(paused: Boolean = false, requireProjectionFgs: Boolean = false) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.service_paused else R.string.service_running))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !requireProjectionFgs) {
            getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID, notification)
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        Constants.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(Constants.NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID, notification)
            }
        }
        notificationShown = true
    }

    private fun updateNotification(paused: Boolean) {
        if (notificationShown) showPersistentNotification(paused = paused)
    }

    private fun cleanup() {
        instance = null
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        trackedBoxes.clear()
        activeBlurBoxes = emptyList()
        lastScreenSignature = null
        notificationShown = false

        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        handlerThread?.quitSafely(); handlerThread = null
        backgroundHandler = null

        overlayManager?.setOnVisibilityChangedListener(null)
        overlayManager?.destroy()
        overlayManager = null

        try { detector?.close() } catch (e: Exception) { Log.e(TAG, "Error closing NudeNet", e) }
        detector = null
        try { genderPersonDetector?.close() } catch (e: Exception) { Log.e(TAG, "Error closing GenderPersonDetector", e) }
        genderPersonDetector = null
        try { intimateSceneDetector?.close() } catch (e: Exception) { Log.e(TAG, "Error closing IntimateSceneDetector", e) }
        intimateSceneDetector = null
        try { audioIntimateDetector?.close() } catch (e: Exception) { Log.e(TAG, "Error closing AudioIntimateDetector", e) }
        audioIntimateDetector = null

        serviceScope.cancel()
        publishHealth { ProtectionHealth() }
    }
}
