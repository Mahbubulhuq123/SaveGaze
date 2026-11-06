package com.savegaze.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.savegaze.MainActivity
import com.savegaze.R
import com.savegaze.data.PreferencesManager
import com.savegaze.ml.BoundingBox
import com.savegaze.ml.Detection
import com.savegaze.ml.GenderFilter
import com.savegaze.ml.NudeLabel
import com.savegaze.ml.NudeNetDetector
import com.savegaze.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class SaveGazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SaveGazeA11yService"
        private const val OVERLAY_ERROR = "Screen overlay could not be displayed."

        @Volatile
        var isRunning: Boolean = false
            private set

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var detector: NudeNetDetector? = null
    private var overlayManager: OverlayManager? = null
    private var preferencesManager: PreferencesManager? = null

    private var scanJob: Job? = null
    private val scanning = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2340
    private var screenDensity: Int = 480
    private var lastFrameTime = 0L
    private var currentScanInterval: Long = Constants.DEFAULT_SCAN_INTERVAL_MS
    private var currentSensitivity: Float = 0.25f
    private var lastBlurTime = 0L
    private var activeBlurBoxes: List<BoundingBox> = emptyList()
    private var activeFaceBoxes: List<BoundingBox> = emptyList()
    private var lastScreenSignature: IntArray? = null
    private var lastGenderFilter: GenderFilter? = null
    private var userJustScrolled = false
    private var lastScrollTimestamp = 0L
    private var lastScreenIsStatic = false
    private var notificationShown = false

    private data class TrackedBox(
        var box: BoundingBox,
        var lastSeenMs: Long = 0L,
        var associatedFace: BoundingBox? = null,
        var faceGender: NudeLabel? = null
    )
    private val trackedBoxes = mutableListOf<TrackedBox>()

    override fun onServiceConnected() {
        super.onServiceConnected()
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

        serviceScope.launch {
            preferencesManager?.scanIntervalFlow?.collect { interval ->
                currentScanInterval = interval
            }
        }
        serviceScope.launch {
            preferencesManager?.sensitivityFlow?.collect { sensitivity ->
                currentSensitivity = sensitivity
                detector?.confidenceThreshold = sensitivity
            }
        }
        serviceScope.launch {
            preferencesManager?.blurStyleFlow?.collect { style ->
                val opacity = preferencesManager?.getBlurOpacity() ?: 100
                overlayManager?.setStyleAndOpacity(style, opacity)
            }
        }
        serviceScope.launch {
            preferencesManager?.blurOpacityFlow?.collect { opacity ->
                val style = preferencesManager?.getBlurStyle() ?: com.savegaze.data.BlurStyle.SOLID_BLACK
                overlayManager?.setStyleAndOpacity(style, opacity)
            }
        }
        serviceScope.launch {
            preferencesManager?.blurFacesFlow?.collect { blurFaces ->
                overlayManager?.setBlurFaces(blurFaces)
            }
        }
        serviceScope.launch {
            preferencesManager?.isEnabledFlow?.collect { enabled ->
                if (!enabled) {
                    trackedBoxes.clear()
                    activeBlurBoxes = emptyList()
                    activeFaceBoxes = emptyList()
                    lastScreenSignature = null
                    overlayManager?.clearBlurRegions()
                } else if (detector != null) {
                    performScan()
                }
                updateNotification(paused = !enabled)
            }
        }
        serviceScope.launch {
            preferencesManager?.genderFilterFlow?.collect { filter ->
                if (filter != lastGenderFilter) {
                    lastGenderFilter = filter
                    trackedBoxes.clear()
                    activeBlurBoxes = emptyList()
                    activeFaceBoxes = emptyList()
                    overlayManager?.clearBlurRegions()
                }
            }
        }

        onMediaProjectionGranted = {
            if (mediaProjection == null) {
                serviceScope.launch(Dispatchers.Main) {
                    setupMediaProjection()
                }
            }
        }

        serviceScope.launch {
            try {
                detector = withContext(Dispatchers.IO) {
                    NudeNetDetector(this@SaveGazeAccessibilityService)
                }
                publishHealth { it.copy(detectorStatus = DetectorStatus.READY) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load NudeNetDetector", e)
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startScanningLoop()
            } else {
                if (mediaProjectionData != null) {
                    setupMediaProjection()
                }
                startScanningLoop()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                if (pkg == "com.android.systemui") return
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                lastScreenSignature = null
                overlayManager?.clearBlurRegions()
                serviceScope.launch {
                    if (preferencesManager?.getIsEnabled() == true) {
                        performScan()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                userJustScrolled = true
                lastScrollTimestamp = System.currentTimeMillis()
                serviceScope.launch {
                    if (preferencesManager?.getIsEnabled() == true) {
                        performScan()
                    }
                }
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

                    val baseInterval = preferencesManager?.getScanInterval()
                        ?: Constants.DEFAULT_SCAN_INTERVAL_MS
                    val interval = if (activeBlurBoxes.isNotEmpty() && !lastScreenIsStatic) {
                        minOf(baseInterval, 160L)
                    } else {
                        minOf(baseInterval, 300L)
                    }
                    delay(interval)
                } catch (e: Exception) {
                    delay(Constants.DEFAULT_SCAN_INTERVAL_MS)
                }
            }
        }
    }

    private fun performScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!scanning.compareAndSet(false, true)) return
            performNativeScreenshot()
        } else {
            if (virtualDisplay == null) {
                setupMediaProjection()
            }
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
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                hardwareBuffer.close()

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
        } catch (e: Exception) {
            scanning.set(false)
        }
    }

    @Synchronized
    private fun setupMediaProjection() {
        if (mediaProjection != null && virtualDisplay != null) return
        val data = mediaProjectionData ?: return
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
        try {
            mediaProjection = mpManager.getMediaProjection(mediaProjectionResultCode, data)
        } catch (e: Exception) {
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        if (handlerThread == null) {
            handlerThread = HandlerThread("SaveGazeCaptureThread").apply { start() }
            backgroundHandler = Handler(handlerThread!!.looper)
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            handleCapturedFrame(reader)
        }, backgroundHandler)

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SaveGazeVirtualDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            flags,
            imageReader?.surface,
            null,
            backgroundHandler
        )
    }

    private fun handleCapturedFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        val now = System.currentTimeMillis()
        if (now - lastFrameTime < 150L || !scanning.compareAndSet(false, true)) {
            image.close()
            return
        }

        lastFrameTime = now
        processImage(image)
    }

    private fun processImage(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
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
            } else bitmap

            serviceScope.launch(Dispatchers.Default) {
                try {
                    processBitmap(cleanBitmap)
                } finally {
                    scanning.set(false)
                }
            }
        } catch (e: Exception) {
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

            val sensitivity = preferencesManager?.getSensitivity() ?: 0.25f
            currentDetector.confidenceThreshold = sensitivity

            val detections: List<Detection> = withContext(Dispatchers.Default) {
                currentDetector.detect(bitmap)
            }

            val genderFilter: GenderFilter =
                preferencesManager?.getGenderFilter() ?: GenderFilter.BOTH

            if (lastGenderFilter != null && lastGenderFilter != genderFilter) {
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                overlayManager?.clearBlurRegions()
            }
            lastGenderFilter = genderFilter

            val shouldBlurFullBody = preferencesManager?.getBlurFullBody() ?: false
            val filtered = currentDetector.filterDetections(detections, genderFilter, shouldBlurFullBody)

            // Exclude both current and tracked blur boxes when computing the unblurred screen signature
            val allExcluded = activeBlurBoxes + trackedBoxes.map { it.box }
            val currentSignature = computeScreenSignature(bitmap, allExcluded)
            val screenChange = analyzeScreenChange(lastScreenSignature, currentSignature)
            val isSceneCut = screenChange.isSceneCut
            lastScreenIsStatic = screenChange.isStatic
            val shouldBlurFaces = preferencesManager?.getBlurFaces() ?: false

            val candidateBoxes = mutableListOf<BoundingBox>()
            if (filtered.mergedBlurBoxes.isNotEmpty()) {
                candidateBoxes.addAll(filtered.mergedBlurBoxes)
            } else {
                candidateBoxes.addAll(filtered.toBlur.map { it.box })
            }

            val matchingGenderFaces = when (genderFilter) {
                GenderFilter.BOTH -> filtered.faces
                GenderFilter.FEMALE_ONLY -> filtered.faces.filter { it.label == NudeLabel.FACE_FEMALE }
                GenderFilter.MALE_ONLY -> filtered.faces.filter { it.label == NudeLabel.FACE_MALE }
            }

            if (shouldBlurFaces && matchingGenderFaces.isNotEmpty()) {
                for (face in matchingGenderFaces) {
                    val fw = face.box.width
                    val fh = face.box.height
                    candidateBoxes.add(BoundingBox(
                        x1 = (face.box.x1 - fw * 0.15f).coerceAtLeast(0f),
                        y1 = (face.box.y1 - fh * 0.20f).coerceAtLeast(0f),
                        x2 = face.box.x2 + fw * 0.15f,
                        y2 = face.box.y2 + fh * 0.10f
                    ))
                }
            }

            val facesToProtect = if (shouldBlurFaces) {
                when (genderFilter) {
                    GenderFilter.BOTH -> emptyList()
                    GenderFilter.FEMALE_ONLY -> filtered.faces.filter { it.label == NudeLabel.FACE_MALE }.map { it.box }
                    GenderFilter.MALE_ONLY -> filtered.faces.filter { it.label == NudeLabel.FACE_FEMALE }.map { it.box }
                }
            } else {
                filtered.faces.map { it.box }
            }

            val now = System.currentTimeMillis()
            val didScrollRecently = userJustScrolled && (now - lastScrollTimestamp < 450L)
            if (userJustScrolled && !didScrollRecently) {
                userJustScrolled = false
            }

            val boxesToApply = updateTrackedBoxes(
                candidateBoxes = candidateBoxes,
                matchingFaces = matchingGenderFaces,
                now = now,
                isStaticScreen = lastScreenIsStatic,
                isSceneCut = isSceneCut,
                didScroll = userJustScrolled
            )

            val protectionEnabled = preferencesManager?.getIsEnabled() ?: true
            if (!protectionEnabled) {
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                lastScreenSignature = currentSignature
                overlayManager?.clearBlurRegions()
            } else if (boxesToApply.isNotEmpty()) {
                activeBlurBoxes = boxesToApply
                activeFaceBoxes = facesToProtect
                // Only update baseline signature when unblurred points exist
                if (currentSignature.count { it != -1 } >= 8) {
                    lastScreenSignature = currentSignature
                }
                lastBlurTime = now
                overlayManager?.updateBlurRegions(boxesToApply, facesToProtect)
            } else {
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                lastScreenSignature = currentSignature
                overlayManager?.clearBlurRegions()
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
        now: Long,
        isStaticScreen: Boolean,
        isSceneCut: Boolean,
        didScroll: Boolean
    ): List<BoundingBox> {
        if (didScroll) {
            userJustScrolled = false
            if (isSceneCut && candidateBoxes.isEmpty()) {
                trackedBoxes.clear()
                return emptyList()
            }
        }

        // Only clear on a true scene cut when no matching person face AND no candidate boxes remain
        if (isSceneCut && candidateBoxes.isEmpty() && matchingFaces.isEmpty()) {
            trackedBoxes.clear()
            return emptyList()
        }

        val matchedTrackedIndices = mutableSetOf<Int>()

        // 1. Match new candidate boxes to existing tracked boxes
        for (cand in candidateBoxes) {
            var bestIdx = -1
            var bestScore = 0f

            for (i in trackedBoxes.indices) {
                if (i in matchedTrackedIndices) continue
                val tracked = trackedBoxes[i]
                val iou = cand.iou(tracked.box)
                if (iou > 0.08f && iou > bestScore) {
                    bestScore = iou
                    bestIdx = i
                } else if (bestScore == 0f) {
                    val cxDist = kotlin.math.abs(cand.centerX - tracked.box.centerX)
                    val cyDist = kotlin.math.abs(cand.centerY - tracked.box.centerY)
                    if (cxDist < maxOf(cand.width, tracked.box.width) * 0.65f &&
                        cyDist < maxOf(cand.height, tracked.box.height) * 0.65f) {
                        bestIdx = i
                    }
                }
            }

            val nearFace = matchingFaces.firstOrNull { f ->
                kotlin.math.abs(f.box.centerX - cand.centerX) < maxOf(cand.width, f.box.width * 2f) &&
                f.box.y2 <= cand.y2 + 60f
            }?.box

            if (bestIdx >= 0) {
                matchedTrackedIndices.add(bestIdx)
                val tracked = trackedBoxes[bestIdx]
                tracked.lastSeenMs = now
                if (nearFace != null) tracked.associatedFace = nearFace

                if (isStaticScreen) {
                    // On static screens, only union/expand so coverage never shrinks or jitters
                    tracked.box = BoundingBox(
                        minOf(tracked.box.x1, cand.x1),
                        minOf(tracked.box.y1, cand.y1),
                        maxOf(tracked.box.x2, cand.x2),
                        maxOf(tracked.box.y2, cand.y2)
                    )
                } else {
                    // In videos: expand fast (0.85f) to cover newly exposed body parts immediately,
                    // but shrink very slowly (0.12f) so self-occluded body parts stay covered!
                    val newX1 = if (cand.x1 < tracked.box.x1) tracked.box.x1 * 0.15f + cand.x1 * 0.85f else tracked.box.x1 * 0.88f + cand.x1 * 0.12f
                    val newY1 = if (cand.y1 < tracked.box.y1) tracked.box.y1 * 0.15f + cand.y1 * 0.85f else tracked.box.y1 * 0.88f + cand.y1 * 0.12f
                    val newX2 = if (cand.x2 > tracked.box.x2) tracked.box.x2 * 0.15f + cand.x2 * 0.85f else tracked.box.x2 * 0.88f + cand.x2 * 0.12f
                    val newY2 = if (cand.y2 > tracked.box.y2) tracked.box.y2 * 0.15f + cand.y2 * 0.85f else tracked.box.y2 * 0.88f + cand.y2 * 0.12f
                    tracked.box = BoundingBox(newX1, newY1, newX2, newY2)
                }
            } else {
                trackedBoxes.add(
                    TrackedBox(
                        box = cand,
                        lastSeenMs = now,
                        associatedFace = nearFace
                    )
                )
                matchedTrackedIndices.add(trackedBoxes.lastIndex)
            }
        }

        // 2. Self-Occlusion Protection:
        // For any tracked box that wasn't directly matched (because our opaque blur shield is covering the body!),
        // check if:
        // (a) The screen is static -> keep locked indefinitely (refresh lastSeenMs = now)
        // (b) In video -> if a matching-gender face is still right above/near the shield, anchor the shield to that face and refresh lastSeenMs = now!
        for (i in trackedBoxes.indices) {
            if (i in matchedTrackedIndices) continue
            val tracked = trackedBoxes[i]

            if (isStaticScreen && !didScroll && !isSceneCut) {
                // Static image/paused video: the blur shield itself is occluding the underlying image.
                // Never expire while the surrounding screen is static!
                tracked.lastSeenMs = now
                continue
            }

            // Video face-anchor tracking:
            val anchorFace = matchingFaces.firstOrNull { f ->
                val horizDist = kotlin.math.abs(f.box.centerX - tracked.box.centerX)
                val vertDist = kotlin.math.abs(f.box.y2 - tracked.box.y1)
                horizDist < maxOf(tracked.box.width * 0.85f, f.box.width * 2.2f) &&
                vertDist < maxOf(tracked.box.height * 0.65f, f.box.height * 2.5f)
            }?.box

            if (anchorFace != null) {
                val prevFace = tracked.associatedFace
                if (prevFace != null) {
                    val dx = (anchorFace.centerX - prevFace.centerX) * 0.65f
                    val dy = (anchorFace.y2 - prevFace.y2) * 0.65f
                    tracked.box = BoundingBox(
                        x1 = (tracked.box.x1 + dx).coerceAtLeast(0f),
                        y1 = (tracked.box.y1 + dy).coerceAtLeast(0f),
                        x2 = tracked.box.x2 + dx,
                        y2 = tracked.box.y2 + dy
                    )
                }
                tracked.associatedFace = anchorFace
                tracked.lastSeenMs = now
            }
        }

        // 3. Prune expired tracks only when screen is actually changing and hold time elapsed
        val holdTimeMs = if (didScroll) 650L else 2200L
        trackedBoxes.removeAll { now - it.lastSeenMs > holdTimeMs }
        return trackedBoxes.map { it.box }
    }

    private data class ScreenChangeResult(
        val isStatic: Boolean,
        val isSceneCut: Boolean
    )

    private fun computeScreenSignature(
        bitmap: Bitmap,
        excludeBoxes: List<BoundingBox> = emptyList()
    ): IntArray {
        val sig = IntArray(100)
        val w = bitmap.width
        val h = bitmap.height
        var idx = 0
        for (row in 1..10) {
            for (col in 1..10) {
                val x = (w * col / 11).coerceIn(0, w - 1)
                val y = (h * row / 11).coerceIn(0, h - 1)
                val insideExcluded = excludeBoxes.any { b ->
                    x.toFloat() >= b.x1 && x.toFloat() <= b.x2 &&
                    y.toFloat() >= b.y1 && y.toFloat() <= b.y2
                }
                sig[idx++] = if (insideExcluded) -1 else bitmap.getPixel(x, y)
            }
        }
        return sig
    }

    private fun analyzeScreenChange(sig1: IntArray?, sig2: IntArray?): ScreenChangeResult {
        if (sig1 == null || sig2 == null || sig1.size != sig2.size) {
            return ScreenChangeResult(isStatic = false, isSceneCut = true)
        }
        var totalValid = 0
        var totalMatches = 0

        for (i in sig1.indices) {
            val c1 = sig1[i]
            val c2 = sig2[i]
            if (c1 == -1 || c2 == -1) continue
            totalValid++

            val dr = kotlin.math.abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
            val dg = kotlin.math.abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
            val db = kotlin.math.abs((c1 and 0xFF) - (c2 and 0xFF))
            if (dr + dg + db < 45) {
                totalMatches++
            }
        }

        if (totalValid < 10) return ScreenChangeResult(isStatic = false, isSceneCut = true)

        val matchRate = totalMatches.toFloat() / totalValid.toFloat()
        // Scene cut: less than 20% of valid screen points match → switched app or scrolled completely past
        val isSceneCut = matchRate < 0.20f
        val isStatic = matchRate >= 0.65f

        return ScreenChangeResult(isStatic = isStatic, isSceneCut = isSceneCut)
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

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showPersistentNotification(paused: Boolean = false) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(if (paused) R.string.service_paused else R.string.service_running)
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(Constants.NOTIFICATION_ID, notification)
            Log.w(TAG, "Persistent notification posted via NotificationManager (Android 14+)")
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
                Log.w(TAG, "startForeground completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed startForeground, falling back to manager.notify", e)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(Constants.NOTIFICATION_ID, notification)
            }
        }
        notificationShown = true
    }

    private fun updateNotification(paused: Boolean) {
        if (notificationShown) {
            showPersistentNotification(paused = paused)
        }
    }

    private fun cleanup() {
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        trackedBoxes.clear()
        activeBlurBoxes = emptyList()
        activeFaceBoxes = emptyList()
        lastScreenSignature = null
        notificationShown = false

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null

        overlayManager?.setOnVisibilityChangedListener(null)
        overlayManager?.destroy()
        overlayManager = null

        try {
            detector?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detector", e)
        }
        detector = null

        serviceScope.cancel()
        publishHealth { ProtectionHealth() }
    }
}
