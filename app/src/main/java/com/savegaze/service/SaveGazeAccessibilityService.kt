package com.savegaze.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
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
import android.os.Looper
import android.os.PowerManager
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
import com.savegaze.ml.ClipIntimateScore
import com.savegaze.ml.Detection
import com.savegaze.ml.GenderFilter
import com.savegaze.ml.GenderPersonDetector
import com.savegaze.ml.IntimateCaptionRules
import com.savegaze.ml.IntimateSceneDetector
import com.savegaze.ml.IntimateSceneRules
import com.savegaze.ml.IntimateSignals
import com.savegaze.ml.MobileClipIntimateDetector
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
import java.util.concurrent.atomic.AtomicInteger
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

        fun stopScreenCastIfOptional() {
            mediaProjectionData = null
            mediaProjectionResultCode = 0
            instance?.serviceScope?.launch(Dispatchers.Main) {
                instance?.releaseMediaProjectionAudioOnly()
            }
        }

        fun onMovieCastPromptResolved(granted: Boolean, restorePackage: String = "") {
            val svc = instance ?: return
            svc.projectionPromptOpen = false
            if (granted) {
                svc.lastCastGrantedMs = System.currentTimeMillis()
                svc.stopCastDebounceJob?.cancel()
                if (restorePackage.isNotBlank() && !svc.isIdleOrSystemPackage(restorePackage)) {
                    svc.lastForegroundPackage = restorePackage
                }
            } else {
                svc.lastCastExitMs = System.currentTimeMillis()
            }
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
    private var mobileClipDetector: MobileClipIntimateDetector? = null
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
    @Volatile private var intimateAudioEnabled = false
    @Volatile private var autoMovieAudioCastEnabled = true
    @Volatile private var projectionPromptOpen = false
    @Volatile private var lastCastExitMs = 0L
    @Volatile private var lastCastGrantedMs = 0L
    private var stopCastDebounceJob: Job? = null
    @Volatile private var intimateActionMode = IntimateActionMode.SMART_CONTEXT
    @Volatile private var drmAppPolicy = DrmAppPolicy.WARN_ONLY
    @Volatile private var lastTreeHints: Set<String> = emptySet()
    @Volatile private var lastHasSeekBarOrPlayer = false
    @Volatile private var lastSceneSafety: SceneSafetyScores? = null
    @Volatile private var lastClipScore: ClipIntimateScore? = null
    @Volatile private var lastClipScoredMs = 0L
    private var lastEvaluatedClipMs = 0L
    private var lastClipLaunchMs = 0L
    private val clipInferenceInFlight = AtomicBoolean(false)
    private val clipGeneration = AtomicInteger(0)
    @Volatile private var lastAudioResult = AudioIntimateResult(0f, "", 0f, false)
    @Volatile private var lastAudioScoredMs = 0L
    private var lastFullInferenceMs = 0L
    private var changedFrameCounter = 0
    private var consecutiveIntimateHits = 0
    private var consecutiveAudioHits = 0
    private var consecutiveSkipsOnSameVideo = 0
    private var cleanFramesSinceSkip = 0
    private var lastIntimateActionMs = 0L
    private val drmWarnedPackages = mutableSetOf<String>()
    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null
    private var cachedAudioManager: AudioManager? = null

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
        powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        cachedAudioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
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
                    }.onFailure { Log.e(TAG, "AudioIntimateDetector failed to load", it) }.getOrNull()
                    mobileClipDetector = runCatching {
                        MobileClipIntimateDetector(this@SaveGazeAccessibilityService)
                    }.onFailure { Log.e(TAG, "MobileClipIntimateDetector failed to load", it) }.getOrNull()
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
                if (!enabled && !autoMovieAudioCastEnabled) {
                    audioIntimateDetector?.stop()
                    releaseMediaProjectionAudioOnly()
                } else if (enabled) {
                    startAudioCaptureIfReady()
                }
            }
        }
        serviceScope.launch {
            pm.autoMovieAudioCastEnabledFlow.collect { enabled ->
                autoMovieAudioCastEnabled = enabled
                if (!enabled && !intimateAudioEnabled) {
                    stopCastDebounceJob?.cancel()
                    audioIntimateDetector?.stop()
                    releaseMediaProjectionAudioOnly()
                } else if (enabled) {
                    evaluateAutoMovieCast()
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

        val now = System.currentTimeMillis()
        if (pkg == "com.android.systemui" || pkg == packageName || pkg.contains("permissioncontroller", ignoreCase = true)) {
            val clsName = event.className?.toString() ?: ""
            val isOverlayPromptOrRecentDismiss = projectionPromptOpen ||
                (now - lastCastGrantedMs < 3_500L) ||
                clsName.contains("ProjectionPromptActivity") ||
                clsName.contains("ChallengeActivity")
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == packageName && !isOverlayPromptOrRecentDismiss) {
                lastForegroundPackage = pkg
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                lastScreenSignature = null
                overlayManager?.clearBlurRegions()
                evaluateAutoMovieCast()
            }
            return
        }

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
                    cleanFramesSinceSkip = 0
                    lastTreeHints = emptySet()
                    lastHasSeekBarOrPlayer = false
                    overlayManager?.clearBlurRegions()
                }
            }
            evaluateAutoMovieCast()
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
                    evaluateAutoMovieCast()
                }
                serviceScope.launch {
                    if (preferencesManager?.getIsEnabled() == true) performScan()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if ((lastForegroundPackage.isNullOrBlank() || lastForegroundPackage == packageName) &&
                    pkg.isNotBlank() && !isIdleOrSystemPackage(pkg)
                ) {
                    lastForegroundPackage = pkg
                }
                if (now - lastGuardCheckMs >= 450L) {
                    checkContentGuardWindow(pkg, forceCheck = false)
                    evaluateAutoMovieCast()
                }
                if (now - lastContentTriggerMs >= 320L) {
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

                // Zero-cast subtitle/caption intimate scene detection
                if (intimateGuardEnabled && isProtectionEnabled && !intimateActionInProgress.get() && allText.isNotEmpty()) {
                    val captionHit = IntimateCaptionRules.detectIntimateCaption(allText)
                    if (captionHit != null && (System.currentTimeMillis() - lastIntimateActionMs >= 2_500L)) {
                        triggerIntimateAction(
                            IntimateSignals(
                                triggered = true,
                                instant = true,
                                reason = "Intimate Caption ($captionHit)",
                                confidence = 0.85f,
                                isKissOrBedCue = false
                            )
                        )
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
            if (ld.contains("comment on reel") || ld.contains("like reel") ||
                ld.contains("share reel") || ld.contains("shorts player") ||
                ld.contains("fb_shorts")
            ) {
                treeHints.add(ld.take(40))
            }
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

    private fun isScreenAwakeAndUnlocked(): Boolean {
        val screenOn = powerManager?.isInteractive ?: true
        if (!screenOn) return false
        val locked = keyguardManager?.isKeyguardLocked ?: false
        return !locked
    }

    private fun isIdleOrSystemPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg == packageName ||
            pkg == "com.android.systemui" ||
            pkg in ContentGuardLists.SETTINGS_PACKAGES ||
            pkg in ContentGuardLists.SKIP_TEXT_GUARD_PACKAGES ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("keyboard", ignoreCase = true) ||
            pkg.contains("gboard", ignoreCase = true)
    }

    private fun releaseMediaProjectionAudioOnly() {
        // On Android 11+, screenshots use AccessibilityService.takeScreenshot(); stop MediaProjection cleanly so Screen Cast ends immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stopCastDebounceJob?.cancel()
            audioIntimateDetector?.stop()
            mediaProjection?.stop()
            mediaProjection = null
            mediaProjectionData = null
            mediaProjectionResultCode = 0
            ScreenCastForegroundService.stop(this)
            Log.i(TAG, "Screen Cast stopped cleanly (auto-stop or toggle off)")
        }
    }

    private fun evaluateAutoMovieCast() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!isProtectionEnabled || !intimateGuardEnabled || !autoMovieAudioCastEnabled) return
        val now = System.currentTimeMillis()
        if (!isScreenAwakeAndUnlocked()) {
            if (mediaProjection != null && !intimateAudioEnabled) {
                releaseMediaProjectionAudioOnly()
            }
            return
        }
        if (projectionPromptOpen) return

        val pkg = lastForegroundPackage ?: ""
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isMediaPlaying = cachedAudioManager?.isMusicActive == true
        val shouldCastNow = IntimateExitRules.shouldAutoCastAudio(
            packageName = pkg,
            treeHints = lastTreeHints,
            hasSeekBarOrPlayerControls = lastHasSeekBarOrPlayer,
            isLandscape = isLandscape,
            isMediaPlaying = isMediaPlaying
        )

        if (shouldCastNow) {
            stopCastDebounceJob?.cancel()
            if (mediaProjection == null && (now - lastCastExitMs >= 2_500L) && (now - lastCastGrantedMs >= 4_000L)) {
                projectionPromptOpen = true
                val promptIntent = Intent(this, ProjectionPromptActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(ProjectionPromptActivity.EXTRA_TARGET_PACKAGE, pkg)
                }
                startActivity(promptIntent)
            } else if (mediaProjection != null && audioIntimateDetector?.isCapturing() != true) {
                startAudioCaptureIfReady()
            }
        } else if (!intimateAudioEnabled && mediaProjection != null && (now - lastCastGrantedMs >= 4_000L) && stopCastDebounceJob?.isActive != true) {
            stopCastDebounceJob = serviceScope.launch {
                delay(3_200L)
                val checkNow = System.currentTimeMillis()
                val rePkg = lastForegroundPackage ?: ""
                val reLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val rePlaying = cachedAudioManager?.isMusicActive == true
                val stillNeeded = IntimateExitRules.shouldAutoCastAudio(
                    packageName = rePkg,
                    treeHints = lastTreeHints,
                    hasSeekBarOrPlayerControls = lastHasSeekBarOrPlayer,
                    isLandscape = reLandscape,
                    isMediaPlaying = rePlaying
                ) || (rePlaying && (rePkg.contains("youtube") || rePkg in ContentGuardLists.BROWSER_PACKAGES))
                if (!stillNeeded && !intimateAudioEnabled && !projectionPromptOpen && mediaProjection != null && (checkNow - lastCastGrantedMs >= 4_000L)) {
                    releaseMediaProjectionAudioOnly()
                    overlayManager?.showBlockBanner(
                        "Screen Cast Stopped",
                        "Exited video player — Screen Cast turned off to save battery"
                    )
                }
            }
        }
    }

    private fun startScanningLoop() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (!isScreenAwakeAndUnlocked()) {
                        if (activeBlurBoxes.isNotEmpty()) {
                            trackedBoxes.clear()
                            activeBlurBoxes = emptyList()
                            overlayManager?.clearBlurRegions()
                        }
                        delay(1_500L)
                        continue
                    }

                    val enabled = isProtectionEnabled
                    evaluateAutoMovieCast()
                    if (enabled && !isIdleOrSystemPackage(lastForegroundPackage)) {
                        performScan()
                    } else {
                        if (activeBlurBoxes.isNotEmpty()) {
                            trackedBoxes.clear()
                            activeBlurBoxes = emptyList()
                            overlayManager?.clearBlurRegions()
                        }
                    }

                    val baseInterval = preferencesManager?.getScanInterval() ?: 250L
                    val verifyingPostSkip = consecutiveSkipsOnSameVideo in 1..11 &&
                        (System.currentTimeMillis() - lastIntimateActionMs <= 5_500L)
                    val interval = when {
                        !enabled || isIdleOrSystemPackage(lastForegroundPackage) -> 1_100L
                        verifyingPostSkip -> 140L
                        lastScreenIsStatic -> 950L
                        activeBlurBoxes.isNotEmpty() -> minOf(maxOf(baseInterval, 240L), 300L)
                        else -> minOf(maxOf(baseInterval, 260L), 340L)
                    }
                    delay(interval)
                } catch (_: Exception) {
                    delay(400L)
                }
            }
        }
    }

    private fun performScan() {
        if (!isScreenAwakeAndUnlocked() || isIdleOrSystemPackage(lastForegroundPackage)) return
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

        if (mediaProjection == null) {
            mediaProjection = try {
                mpManager.getMediaProjection(mediaProjectionResultCode, data)?.also { mp ->
                    mp.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.i(TAG, "MediaProjection.Callback.onStop() invoked")
                            audioIntimateDetector?.stop()
                            mediaProjection = null
                            mediaProjectionData = null
                            mediaProjectionResultCode = 0
                            ScreenCastForegroundService.stop(this@SaveGazeAccessibilityService)
                        }
                    }, Handler(Looper.getMainLooper()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get MediaProjection", e)
                ScreenCastForegroundService.stop(this)
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
        if (!intimateGuardEnabled || (!intimateAudioEnabled && !autoMovieAudioCastEnabled)) return
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
        if (!isProtectionEnabled || !intimateGuardEnabled || (!intimateAudioEnabled && !autoMovieAudioCastEnabled)) return
        if (intimateActionInProgress.get()) return
        val pkg = lastForegroundPackage ?: return
        if (isIdleOrSystemPackage(pkg)) return

        val now = System.currentTimeMillis()
        val freshClipScore = lastClipScore?.takeIf { now - lastClipScoredMs < 2_500L }
        val freshSafety = lastSceneSafety?.takeIf { now - lastFullInferenceMs < 2_500L }
        val hasFreshVisual = (freshClipScore != null) || (freshSafety != null)
        val visualUnclear = IntimateSceneRules.isVisualUnclearForAudio(
            sceneSafety = freshSafety,
            hasSemiNude = activeBlurBoxes.isNotEmpty(),
            kissOrBedCue = false,
            clipKissProb = freshClipScore?.intimateProb ?: 0f,
            clipMargin = freshClipScore?.margin ?: 0f,
            hasFreshVisual = hasFreshVisual
        )
        if (result.score >= 0.35f && result.topLabel.isNotBlank()) {
            Log.i(TAG, "Audio score=${result.score} label=${result.topLabel} unclear=$visualUnclear pkg=$pkg")
        }
        if (!visualUnclear) {
            consecutiveAudioHits = 0
            return
        }

        val requiredAudioHits = if (consecutiveSkipsOnSameVideo in 1..11 && (now - lastIntimateActionMs <= 5_500L)) 2 else 3
        if (result.score >= 0.55f && result.topLabel.isNotBlank()) {
            consecutiveAudioHits++
            if (consecutiveAudioHits >= requiredAudioHits) {
                consecutiveAudioHits = 0
                serviceScope.launch(Dispatchers.Main) {
                    val signals = IntimateSignals(
                        triggered = true,
                        instant = true,
                        reason = "Intimate Audio (${result.topLabel})",
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

            val now = System.currentTimeMillis()
            val didScrollRecently = userJustScrolled && (now - lastScrollTimestamp < 450L)
            if (userJustScrolled && !didScrollRecently) {
                userJustScrolled = false
            }

            // Compute screen change BEFORE running heavy ONNX networks so static screens cost ~0% CPU
            val allExcluded = activeBlurBoxes + trackedBoxes.map { it.box }
            val currentSignature = computeScreenSignature(bitmap, allExcluded)
            val screenChange = analyzeScreenChange(lastScreenSignature, currentSignature)
            val isActiveVideoPlayback = IntimateExitRules.classifyContext(
                lastForegroundPackage ?: "",
                lastTreeHints,
                lastHasSeekBarOrPlayer,
                bitmap.width > bitmap.height
            ) == MediaAppContext.MOVIE_SERIES_PLAYER
            lastScreenIsStatic = screenChange.isStatic

            if (screenChange.isStatic && !isActiveVideoPlayback && !didScrollRecently && (now - lastFullInferenceMs < 2_500L)) {
                publishHealth {
                    it.copy(
                        lastFrameAtMillis = now,
                        lastError = if (it.overlayActive) null else it.lastError
                    )
                }
                return
            }
            lastFullInferenceMs = now

            val bWidth = bitmap.width.toFloat()
            val bHeight = bitmap.height.toFloat()
            val sensitivity = currentSensitivity
            currentDetector.confidenceThreshold = sensitivity
            genderPersonDetector?.confidenceThreshold = (sensitivity + 0.02f).coerceIn(0.22f, 0.45f)

            val runQuadrantTiles = didScrollRecently || screenChange.isSceneCut || (changedFrameCounter++ % 4 == 0)
            val recentAudioActive = now - lastAudioScoredMs < 2_000L && lastAudioResult.score >= 0.25f

            // Run NudeNet + PordaV4x3 + SwiftFormer concurrently on Dispatchers.Default
            val runSceneClassifier = intimateGuardEnabled && (!screenChange.isStatic || isActiveVideoPlayback || activeBlurBoxes.isNotEmpty() || recentAudioActive)
            val (detections: List<Detection>, personBoxes: List<PersonGenderBox>, sceneSafety: SceneSafetyScores?) = withContext(Dispatchers.Default) {
                val nudeJob = async { currentDetector.detect(bitmap, includeQuadrants = runQuadrantTiles) }
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
                val clipDetector = mobileClipDetector
                val isLandscapeFrame = bWidth > bHeight
                val activeContext = IntimateExitRules.classifyContext(
                    activePkg, lastTreeHints, lastHasSeekBarOrPlayer, isLandscapeFrame
                )
                val isSocialFeedBrowse = IntimateExitRules.isSocialFeedApp(activePkg) &&
                    activeContext == MediaAppContext.BROWSER_OR_GENERAL
                if (clipDetector != null &&
                    (!screenChange.isStatic || isActiveVideoPlayback) &&
                    !isSocialFeedBrowse &&
                    (activeContext != MediaAppContext.BROWSER_OR_GENERAL || lastHasSeekBarOrPlayer || isLandscapeFrame) &&
                    now - lastClipLaunchMs >= 400L &&
                    clipInferenceInFlight.compareAndSet(false, true)
                ) {
                    lastClipLaunchMs = now
                    val launchGen = clipGeneration.get()
                    val clipFocusBoxes = filtered.faces.map { it.box }.ifEmpty { personBoxes.map { it.box } }
                    val clipCrop = createCenterSquareClipCrop(bitmap, clipFocusBoxes)
                    if (clipCrop != null) {
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                val score = clipDetector.classify(clipCrop)
                                if (score != null && clipGeneration.get() == launchGen) {
                                    lastClipScore = score
                                    lastClipScoredMs = System.currentTimeMillis()
                                    if (score.intimateProb >= 0.45f) {
                                        Log.i(TAG, "MobileCLIP score prob=${score.intimateProb} margin=${score.margin} pkg=$activePkg")
                                    }
                                }
                            } finally {
                                if (!clipCrop.isRecycled) clipCrop.recycle()
                                clipInferenceInFlight.set(false)
                            }
                        }
                    } else {
                        clipInferenceInFlight.set(false)
                    }
                }

                val audioActive = intimateAudioEnabled || (autoMovieAudioCastEnabled && mediaProjection != null)
                val freshAudioScore = if (now - lastAudioScoredMs < 2_000L && audioActive) lastAudioResult.score else 0f
                val freshAudioLabel = if (now - lastAudioScoredMs < 2_000L && audioActive) lastAudioResult.topLabel else ""
                val evalSafety = sceneSafety ?: if (!lastScreenIsStatic && !isActiveVideoPlayback) null else lastSceneSafety
                val freshClip = if (!isSocialFeedBrowse) lastClipScore?.takeIf { now - lastClipScoredMs < 2_200L } else null
                val hasFreshVisual = (evalSafety != null) || (freshClip != null)
                val continuingIntimateScene = (activeContext == MediaAppContext.MOVIE_SERIES_PLAYER) &&
                    consecutiveSkipsOnSameVideo in 1..11 &&
                    (now - lastIntimateActionMs <= 5_500L)
                val rawSignals = IntimateSceneRules.evaluate(
                    detections = detections,
                    personBoxes = personBoxes,
                    sceneSafety = evalSafety,
                    audioScore = freshAudioScore,
                    audioLabel = freshAudioLabel,
                    screenW = bWidth,
                    screenH = bHeight,
                    sensitivity = sensitivity,
                    clipKissProb = freshClip?.intimateProb ?: 0f,
                    clipMargin = freshClip?.margin ?: 0f,
                    allowDarkClipFallback = (activeContext == MediaAppContext.MOVIE_SERIES_PLAYER),
                    continuingIntimateScene = continuingIntimateScene,
                    hasFreshVisual = hasFreshVisual
                )
                // On social home feeds (reading text/posts), blur boxes still protect media; never auto-scroll/back unless explicit nudity
                val signals = if (isSocialFeedBrowse && rawSignals.triggered && !rawSignals.reason.contains("Explicit")) {
                    rawSignals.copy(triggered = false, instant = false)
                } else {
                    rawSignals
                }
                if (signals.triggered) {
                    val isClipTrigger = signals.reason.contains("CLIP")
                    val isDuplicateClipSample = !signals.instant && isClipTrigger && freshClip != null && lastClipScoredMs == lastEvaluatedClipMs
                    if (!isDuplicateClipSample) {
                        if (isClipTrigger && freshClip != null) {
                            lastEvaluatedClipMs = lastClipScoredMs
                        }
                        consecutiveIntimateHits++
                        val neededHits = if (signals.instant) 1 else 2
                        if (consecutiveIntimateHits >= neededHits && (now - lastIntimateActionMs >= 1_400L)) {
                            Log.i(TAG, "Triggering intimate action: ${signals.reason} (conf=${signals.confidence}, pkg=$activePkg)")
                            consecutiveIntimateHits = 0
                            cleanFramesSinceSkip = 0
                            serviceScope.launch(Dispatchers.Main) {
                                triggerIntimateAction(signals)
                            }
                        }
                    }
                } else {
                    consecutiveIntimateHits = (consecutiveIntimateHits - 1).coerceAtLeast(0)
                    if (consecutiveSkipsOnSameVideo > 0 && (freshClip != null || sceneSafety != null)) {
                        cleanFramesSinceSkip++
                    }
                    if ((cleanFramesSinceSkip >= 2 && (now - lastIntimateActionMs >= 1_800L)) || (now - lastIntimateActionMs >= 6_000L)) {
                        consecutiveSkipsOnSameVideo = 0
                        cleanFramesSinceSkip = 0
                    }
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
                    val alpha = when {
                        isStaticScreen -> 0.45f
                        isSceneCut || bestScore < 0.35f -> 1.0f
                        else -> 0.85f
                    }
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
            if (diff < 18) totalMatches++
        }
        if (totalValid < 8) return ScreenChangeResult(isStatic = true, isSceneCut = false)
        val rate = totalMatches.toFloat() / totalValid.toFloat()
        return ScreenChangeResult(isStatic = rate >= 0.96f, isSceneCut = rate < 0.30f)
    }

    private fun createCenterSquareClipCrop(source: Bitmap, faceBoxes: List<BoundingBox> = emptyList()): Bitmap? {
        return try {
            val w = source.width
            val h = source.height
            // On widescreen cinema (e.g. 21:9 movies), trim horizontal top/bottom letterbox bars before cropping
            val activeTop = if (w >= h * 1.45f) (h * 0.10f).toInt() else 0
            val activeH = (h - activeTop * 2).coerceAtLeast(1)
            val cropSize = minOf(w, activeH).coerceAtLeast(1)
            val primaryBoxes = if (faceBoxes.size > 2) {
                faceBoxes.sortedByDescending { it.width * it.height }.take(2)
            } else {
                faceBoxes
            }
            val cropX = if (primaryBoxes.isNotEmpty() && w > cropSize) {
                val centerFocus = primaryBoxes.map { it.centerX }.average().toFloat()
                (centerFocus - cropSize / 2f).toInt().coerceIn(0, w - cropSize)
            } else {
                ((w - cropSize) / 2).coerceAtLeast(0)
            }
            val cropY = (activeTop + (activeH - cropSize) / 2).coerceIn(0, (h - cropSize).coerceAtLeast(0))
            val square = Bitmap.createBitmap(source, cropX, cropY, cropSize, cropSize)
            if (cropSize == 256) {
                square
            } else {
                val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
                square.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun triggerIntimateAction(signals: com.savegaze.ml.IntimateSignals) {
        val now = System.currentTimeMillis()
        if (now - lastIntimateActionMs < 1_400L) return
        if (!intimateActionInProgress.compareAndSet(false, true)) return

        val pkg = lastForegroundPackage ?: ""
        val (freshHints, freshSeekBar) = inspectCurrentMediaHints()
        val combinedHints = lastTreeHints + freshHints
        val hasSeekOrPlayer = lastHasSeekBarOrPlayer || freshSeekBar || (mediaProjection != null && !intimateAudioEnabled)
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
                            dispatchMediaFastForward()
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
                overlayManager?.clearBlurRegions()
                overlayManager?.hideIntimateShieldOverlay()
                audioIntimateDetector?.resetWindow()
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                lastScreenSignature = null
                clipGeneration.incrementAndGet()
                lastClipScore = null
                lastClipScoredMs = 0L
                lastEvaluatedClipMs = 0L
                lastClipLaunchMs = 0L
                lastSceneSafety = null
                lastAudioScoredMs = 0L
                consecutiveIntimateHits = 0
                consecutiveAudioHits = 0
                cleanFramesSinceSkip = 0
                try {
                    delay(250L)
                } finally {
                    lastIntimateActionMs = System.currentTimeMillis()
                    intimateActionInProgress.set(false)
                }
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
            desc.contains("seek forward") || desc.contains("+10") || desc.contains("10s") ||
            desc.contains("10 seconds") || desc.contains("15 seconds") || desc.contains("30 seconds") ||
            text.contains("forward") || text.contains("+10") ||
            viewId.contains("fast_forward") || viewId.contains("ffwd") || viewId.contains("forward_10")) &&
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

    private fun dispatchMediaFastForward() {
        try {
            val am = cachedAudioManager ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        } catch (_: Exception) {
        }
    }

    private fun performSkipSceneForward(w: Int, h: Int) {
        val tapX = w * 0.82f
        val tapY = h * 0.48f
        val builder = GestureDescription.Builder()
        // Crisp right-side double-tap triggers +10s/+15s forward seek in YouTube, ExoPlayer, Nuvio, Stremio, VLC & web players
        for (i in 0..1) {
            val p = Path().apply { moveTo(tapX, tapY) }
            builder.addStroke(GestureDescription.StrokeDescription(p, i * 85L, 25L))
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

    private fun showPersistentNotification(paused: Boolean = false) {
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

        getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID, notification)
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
        ScreenCastForegroundService.stop(this)
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
        try { mobileClipDetector?.close() } catch (e: Exception) { Log.e(TAG, "Error closing MobileClipIntimateDetector", e) }
        mobileClipDetector = null

        serviceScope.cancel()
        publishHealth { ProtectionHealth() }
    }
}
