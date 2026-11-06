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
import com.savegaze.ml.Detection
import com.savegaze.ml.FilteredDetections
import com.savegaze.ml.GenderFilter
import com.savegaze.ml.NudeNetDetector
import com.savegaze.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SaveGazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SaveGazeA11yService"

        @Volatile
        var isRunning: Boolean = false
            private set

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

    // MediaProjection resources for Android 10 (API 29)
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
    private var activeBlurBoxes: List<com.savegaze.ml.BoundingBox> = emptyList()
    private var activeFaceBoxes: List<com.savegaze.ml.BoundingBox> = emptyList()
    private var lastScreenSignature: IntArray? = null
    private var lastGenderFilter: GenderFilter? = null
    private var userJustScrolled = false
    private var lastScreenIsStatic = false

    private data class TrackedBox(
        var box: com.savegaze.ml.BoundingBox,
        var lastSeenMs: Long = 0L,
        var associatedFace: com.savegaze.ml.BoundingBox? = null,
        var faceGender: com.savegaze.ml.NudeLabel? = null
    )
    private val trackedBoxes = mutableListOf<TrackedBox>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.w(TAG, "SaveGaze Accessibility Service connected")

        isRunning = true

        preferencesManager = PreferencesManager(this)
        overlayManager = OverlayManager(this)
        overlayManager?.showOverlay()

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
            preferencesManager?.genderFilterFlow?.collect { filter ->
                if (filter != lastGenderFilter) {
                    Log.w(TAG, "Gender filter flow changed from $lastGenderFilter to $filter - clearing blur")
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
                Log.w(TAG, "NudeNetDetector loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load NudeNetDetector", e)
                return@launch
            }

            createNotificationChannel()
            showPersistentNotification()

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
                // Switching windows/apps: clear active blur and tracking from previous app immediately
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                lastScreenSignature = null
                overlayManager?.clearBlurRegions()
                serviceScope.launch {
                    val enabled = preferencesManager?.getIsEnabled() ?: true
                    if (enabled) {
                        performScan()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // User scrolled: mark scroll active and trigger scan without clearing trackedBoxes (prevents blinking)
                userJustScrolled = true
                serviceScope.launch {
                    val enabled = preferencesManager?.getIsEnabled() ?: true
                    if (enabled) {
                        performScan()
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
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
                    // In videos with active moving blur, scan at 200ms for fluid body tracking
                    val interval = if (activeBlurBoxes.isNotEmpty() && !lastScreenIsStatic) {
                        minOf(baseInterval, 200L)
                    } else {
                        baseInterval
                    }
                    delay(interval)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in scanning loop", e)
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
                        Log.e(TAG, "Screenshot failed: $errorCode")
                        scanning.set(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot threw", e)
            scanning.set(false)
        }
    }

    @Synchronized
    private fun setupMediaProjection() {
        if (mediaProjection != null && virtualDisplay != null) return

        val data = mediaProjectionData ?: run {
            Log.w(TAG, "Cannot setup MediaProjection: mediaProjectionData is null.")
            return
        }
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
        try {
            mediaProjection = mpManager.getMediaProjection(mediaProjectionResultCode, data)
            Log.w(TAG, "MediaProjection acquired successfully: $mediaProjection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getMediaProjection", e)
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
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
            object : VirtualDisplay.Callback() {
                override fun onPaused() { Log.w(TAG, "VirtualDisplay Callback: PAUSED") }
                override fun onResumed() { Log.w(TAG, "VirtualDisplay Callback: RESUMED") }
                override fun onStopped() { Log.w(TAG, "VirtualDisplay Callback: STOPPED") }
            },
            backgroundHandler
        )
        Log.w(TAG, "VirtualDisplay created with FrameListener: $screenWidth x $screenHeight @ $screenDensity dpi")
    }

    private fun handleCapturedFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        val now = System.currentTimeMillis()
        if (now - lastFrameTime < currentScanInterval || !scanning.compareAndSet(false, true)) {
            image.close()
            return
        }

        lastFrameTime = now
        Log.w(TAG, "Frame captured: ${image.width}x${image.height}")
        processImage(image)
    }

    private fun processImage(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            Log.w(TAG, "Planes: pixelStride=$pixelStride, rowStride=$rowStride, rowPadding=$rowPadding, w=$screenWidth, h=$screenHeight")
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

            // Sample 1 pixel to verify not all black
            val samplePixel = cleanBitmap.getPixel(screenWidth / 2, screenHeight / 2)
            Log.w(TAG, "Sample center pixel: 0x${Integer.toHexString(samplePixel)}")

            serviceScope.launch(Dispatchers.Default) {
                try {
                    processBitmap(cleanBitmap)
                } finally {
                    scanning.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
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
            val startTime = System.currentTimeMillis()
            val sensitivity = preferencesManager?.getSensitivity() ?: 0.25f
            currentDetector.confidenceThreshold = sensitivity

            val detections: List<Detection> = withContext(Dispatchers.Default) {
                currentDetector.detect(bitmap)
            }
            val elapsed = System.currentTimeMillis() - startTime

            val genderFilter: GenderFilter =
                preferencesManager?.getGenderFilter() ?: GenderFilter.BOTH

            if (lastGenderFilter != null && lastGenderFilter != genderFilter) {
                Log.w(TAG, "Gender filter changed from $lastGenderFilter to $genderFilter - clearing blur")
                trackedBoxes.clear()
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                overlayManager?.clearBlurRegions()
            }
            lastGenderFilter = genderFilter

            val shouldBlurFullBody = preferencesManager?.getBlurFullBody() ?: false
            val filtered = currentDetector.filterDetections(detections, genderFilter, shouldBlurFullBody)

            Log.w(TAG, "Inference in ${elapsed}ms. Detections (${detections.size}): ${detections.map { "${it.label.name}(${"%.2f".format(it.confidence)})" }}, toBlur: ${filtered.toBlur.size}, facesPreserved: ${filtered.faces.size}")

            // Compute screen signature EXCLUDING blur overlay regions so signature
            // captures the real underlying content, not the blur rectangles we painted.
            val currentSignature = computeScreenSignature(bitmap, activeBlurBoxes)
            val screenChange = analyzeScreenChange(lastScreenSignature, currentSignature)
            val isSceneCut = screenChange.isSceneCut
            lastScreenIsStatic = screenChange.isStatic
            val shouldBlurFaces = preferencesManager?.getBlurFaces() ?: false

            val candidateBoxes = mutableListOf<com.savegaze.ml.BoundingBox>()
            if (filtered.mergedBlurBoxes.isNotEmpty()) {
                candidateBoxes.addAll(filtered.mergedBlurBoxes)
            } else {
                candidateBoxes.addAll(filtered.toBlur.map { it.box })
            }

            // If Face Blurring is enabled by the user, add face boxes to candidate blur regions
            // STRICTLY respecting the active GenderFilter:
            if (shouldBlurFaces && filtered.faces.isNotEmpty()) {
                val facesToBlur = when (genderFilter) {
                    GenderFilter.BOTH -> filtered.faces
                    GenderFilter.FEMALE_ONLY -> filtered.faces.filter { it.label == com.savegaze.ml.NudeLabel.FACE_FEMALE }
                    GenderFilter.MALE_ONLY -> filtered.faces.filter { it.label == com.savegaze.ml.NudeLabel.FACE_MALE }
                }
                for (face in facesToBlur) {
                    val fw = face.box.width
                    val fh = face.box.height
                    candidateBoxes.add(com.savegaze.ml.BoundingBox(
                        x1 = (face.box.x1 - fw * 0.15f).coerceAtLeast(0f),
                        y1 = (face.box.y1 - fh * 0.20f).coerceAtLeast(0f),
                        x2 = face.box.x2 + fw * 0.15f,
                        y2 = face.box.y2 + fh * 0.10f
                    ))
                }
            }

            // Faces that must be protected via hardware clipping (never drawn over by any blur box):
            val facesToProtect = if (shouldBlurFaces) {
                when (genderFilter) {
                    GenderFilter.BOTH -> emptyList()
                    GenderFilter.FEMALE_ONLY -> filtered.faces.filter { it.label == com.savegaze.ml.NudeLabel.FACE_MALE }.map { it.box }
                    GenderFilter.MALE_ONLY -> filtered.faces.filter { it.label == com.savegaze.ml.NudeLabel.FACE_FEMALE }.map { it.box }
                }
            } else {
                filtered.faces.map { it.box }
            }

            // In FEMALE_ONLY mode: purge any candidate or tracked boxes touching male columns
            if (genderFilter == GenderFilter.FEMALE_ONLY) {
                val maleFaces = filtered.faces.filter { it.label == com.savegaze.ml.NudeLabel.FACE_MALE }
                if (maleFaces.isNotEmpty()) {
                    candidateBoxes.removeAll { c ->
                        maleFaces.any { m ->
                            val colLeft = m.box.x1 - m.box.width * 0.55f
                            val colRight = m.box.x2 + m.box.width * 0.55f
                            c.centerX in colLeft..colRight || (c.x1 < colRight && c.x2 > colLeft)
                        }
                    }
                    trackedBoxes.removeAll { t ->
                        maleFaces.any { m ->
                            val colLeft = m.box.x1 - m.box.width * 0.55f
                            val colRight = m.box.x2 + m.box.width * 0.55f
                            t.box.centerX in colLeft..colRight || (t.box.x1 < colRight && t.box.x2 > colLeft)
                        }
                    }
                }
            } else if (genderFilter == GenderFilter.MALE_ONLY) {
                val femaleFaces = filtered.faces.filter { it.label == com.savegaze.ml.NudeLabel.FACE_FEMALE }
                if (femaleFaces.isNotEmpty()) {
                    candidateBoxes.removeAll { c ->
                        femaleFaces.any { f ->
                            val colLeft = f.box.x1 - f.box.width * 0.55f
                            val colRight = f.box.x2 + f.box.width * 0.55f
                            c.centerX in colLeft..colRight || (c.x1 < colRight && c.x2 > colLeft)
                        }
                    }
                    trackedBoxes.removeAll { t ->
                        femaleFaces.any { f ->
                            val colLeft = f.box.x1 - f.box.width * 0.55f
                            val colRight = f.box.x2 + f.box.width * 0.55f
                            t.box.centerX in colLeft..colRight || (t.box.x1 < colRight && t.box.x2 > colLeft)
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()

            // Deadband-stabilized box update: until the body actually moves beyond spatial threshold,
            // the blur box strictly does not change position at all.
            val boxesToApply = updateTrackedBoxes(candidateBoxes, now, lastScreenIsStatic, isSceneCut, userJustScrolled)

            if (boxesToApply.isNotEmpty()) {
                activeBlurBoxes = boxesToApply
                activeFaceBoxes = facesToProtect
                lastScreenSignature = currentSignature
                lastBlurTime = now
                Log.w(TAG, "Active blur on ${boxesToApply.size} stabilized boxes (detections: ${filtered.toBlur.size}, protectedFaces: ${facesToProtect.size}, blurFaces: $shouldBlurFaces, genderFilter: $genderFilter).")
                overlayManager?.updateBlurRegions(boxesToApply, facesToProtect)
            } else {
                activeBlurBoxes = emptyList()
                activeFaceBoxes = emptyList()
                lastScreenSignature = currentSignature
                overlayManager?.clearBlurRegions()
                Log.w(TAG, "Screen changed or grace period timed out: cleared blur regions.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processBitmap", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun updateTrackedBoxes(
        candidateBoxes: List<com.savegaze.ml.BoundingBox>,
        now: Long,
        isStaticScreen: Boolean,
        isSceneCut: Boolean,
        didScroll: Boolean
    ): List<com.savegaze.ml.BoundingBox> {
        // When user physically scrolled, immediately clear if no new detections on the scrolled screen
        if (didScroll) {
            trackedBoxes.clear()
            userJustScrolled = false
            if (candidateBoxes.isEmpty()) {
                return emptyList()
            }
        }

        // Hard scene cut to a new scene (e.g. video switch or new page):
        // If the new scene has no candidates, clear immediately so blur never lingers on non-NSFW content!
        if (isSceneCut && candidateBoxes.isEmpty()) {
            trackedBoxes.clear()
            return emptyList()
        }

        // Phase 2: Match candidates to tracked boxes
        val matchedTrackedIndices = mutableSetOf<Int>()

        for (cand in candidateBoxes) {
            var bestIdx = -1
            var bestScore = 0f

            for (i in trackedBoxes.indices) {
                if (i in matchedTrackedIndices) continue
                val tracked = trackedBoxes[i]
                val iou = cand.iou(tracked.box)
                if (iou > 0.15f && iou > bestScore) {
                    bestScore = iou
                    bestIdx = i
                } else if (bestScore == 0f) {
                    val cxDist = kotlin.math.abs(cand.centerX - tracked.box.centerX)
                    val cyDist = kotlin.math.abs(cand.centerY - tracked.box.centerY)
                    if (cxDist < maxOf(cand.width, tracked.box.width) * 0.45f &&
                        cyDist < maxOf(cand.height, tracked.box.height) * 0.45f) {
                        bestIdx = i
                    }
                }
            }

            if (bestIdx >= 0) {
                matchedTrackedIndices.add(bestIdx)
                val tracked = trackedBoxes[bestIdx]
                tracked.lastSeenMs = now

                // Static screen (images, paused video):
                // If the candidate box expanded (e.g. side-profile breast or intimate contour newly detected),
                // smoothly expand the tracked box to cover it completely!
                if (isStaticScreen) {
                    if (cand.x1 < tracked.box.x1 - 4f || cand.x2 > tracked.box.x2 + 4f ||
                        cand.y1 < tracked.box.y1 - 4f || cand.y2 > tracked.box.y2 + 4f) {
                        tracked.box = com.savegaze.ml.BoundingBox(
                            minOf(tracked.box.x1, cand.x1),
                            minOf(tracked.box.y1, cand.y1),
                            maxOf(tracked.box.x2, cand.x2),
                            maxOf(tracked.box.y2, cand.y2)
                        )
                    }
                    continue
                }

                // Video screen: check deadband before updating position
                val cxDist = kotlin.math.abs(cand.centerX - tracked.box.centerX)
                val cyDist = kotlin.math.abs(cand.centerY - tracked.box.centerY)
                val wDiff = kotlin.math.abs(cand.width - tracked.box.width)
                val hDiff = kotlin.math.abs(cand.height - tracked.box.height)

                val deadbandX = maxOf(tracked.box.width * 0.08f, 10f)
                val deadbandY = maxOf(tracked.box.height * 0.08f, 10f)

                val bodyMoved = cxDist > deadbandX || cyDist > deadbandY || wDiff > 16f || hDiff > 16f
                if (bodyMoved) {
                    // Smooth tracking in video: lerp toward new position
                    val alpha = 0.50f
                    val newX1 = tracked.box.x1 * (1f - alpha) + cand.x1 * alpha
                    val newY1 = tracked.box.y1 * (1f - alpha) + cand.y1 * alpha
                    val newX2 = tracked.box.x2 * (1f - alpha) + cand.x2 * alpha
                    val newY2 = tracked.box.y2 * (1f - alpha) + cand.y2 * alpha
                    tracked.box = com.savegaze.ml.BoundingBox(newX1, newY1, newX2, newY2)
                }
            } else {
                trackedBoxes.add(TrackedBox(box = cand, lastSeenMs = now))
            }
        }

        // Phase 3: Expiry
        if (candidateBoxes.isEmpty()) {
            // No candidates detected in this frame:
            // Static screens maintain 10s grace period;
            // Video / motion content maintains 950ms grace period (bridges 2 inference cycles)
            // so blur NEVER blinks or flickers during video playback!
            val maxAge = if (isStaticScreen) 10000L else 950L
            trackedBoxes.removeAll { now - it.lastSeenMs > maxAge }
        } else {
            // Candidates exist: prune unmatched boxes that haven't been seen in 950ms (video) or 10000ms (static)
            val maxAge = if (isStaticScreen) 10000L else 950L
            val matchedBoxes = matchedTrackedIndices.map { trackedBoxes[it] }.toSet()
            trackedBoxes.removeAll { it !in matchedBoxes && (now - it.lastSeenMs > maxAge) }
        }

        return trackedBoxes.map { it.box }
    }

    private data class ScreenChangeResult(
        val isStatic: Boolean,
        val isSceneCut: Boolean
    )

    private fun computeScreenSignature(
        bitmap: Bitmap,
        excludeBoxes: List<com.savegaze.ml.BoundingBox> = emptyList()
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

    private fun showPersistentNotification() {
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
            .setContentText(getString(R.string.service_running))
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
    }

    private fun cleanup() {
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        trackedBoxes.clear()
        activeBlurBoxes = emptyList()
        activeFaceBoxes = emptyList()
        lastScreenSignature = null

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null

        overlayManager?.destroy()
        overlayManager = null

        try {
            detector?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detector", e)
        }
        detector = null

        serviceScope.cancel()
    }
}
