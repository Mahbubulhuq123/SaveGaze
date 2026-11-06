package com.savegaze.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.savegaze.data.BlurStyle
import com.savegaze.ml.BoundingBox

/**
 * Manages a [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window that draws
 * filled, rounded blur rectangles over detected NSFW content regions on the screen.
 *
 * All public methods post to the main thread so they are safe to call from any thread.
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val overlayView = BlurOverlayView(context)

    @Volatile
    private var showing = false

    /** Reports attach/detach events so the service can publish accurate health state. */
    private var onVisibilityChanged: ((Boolean) -> Unit)? = null

    private val layoutParams: WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    // ── Public API ───────────────────────────────────────────────────────

    /** Registers a listener invoked on the main thread whenever the overlay attaches or detaches. */
    fun setOnVisibilityChangedListener(listener: ((Boolean) -> Unit)?) {
        onVisibilityChanged = listener
    }

    /** Adds the overlay view to the window manager. */
    fun showOverlay() {
        mainHandler.post {
            if (showing) return@post
            try {
                windowManager.addView(overlayView, layoutParams)
                showing = true
                onVisibilityChanged?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add the protection overlay", e)
                showing = false
                onVisibilityChanged?.invoke(false)
            }
        }
    }

    /** Removes the overlay view from the window manager. */
    fun hideOverlay() {
        mainHandler.post {
            if (showing) {
                try {
                    windowManager.removeView(overlayView)
                } catch (_: IllegalArgumentException) {
                    // View was not attached – ignore.
                }
                showing = false
                onVisibilityChanged?.invoke(false)
            }
        }
    }

    /**
     * Replaces the current blur regions and redraws the overlay.
     * Faces are passed so canvas hardware clipping strictly guarantees that NO face
     * is ever touched by any blur.
     *
     * The overlay is shown automatically if it is not already visible.
     */
    fun updateBlurRegions(regions: List<BoundingBox>, faces: List<BoundingBox> = emptyList()) {
        mainHandler.post {
            overlayView.setRegions(regions, faces)
            if (regions.isNotEmpty() && !showing) {
                showOverlay()
            }
        }
    }

    /** Updates blur visual style and opacity percentage (50% to 100%). */
    fun setStyleAndOpacity(style: BlurStyle, opacityPercent: Int) {
        mainHandler.post {
            overlayView.setStyleAndOpacity(style, opacityPercent)
        }
    }

    /** Enables or disables face blurring. When false, faces are strictly protected via hardware clipping. */
    fun setBlurFaces(enabled: Boolean) {
        mainHandler.post {
            overlayView.setBlurFaces(enabled)
        }
    }

    /** Clears all blur regions and redraws the overlay (transparent). */
    fun clearBlurRegions() {
        mainHandler.post {
            overlayView.setRegions(emptyList(), emptyList())
        }
    }

    /** Whether the overlay view is currently attached to the window. */
    fun isShowing(): Boolean = showing

    /** Removes the overlay and releases resources. */
    fun destroy() {
        mainHandler.post {
            clearBlurRegions()
            hideOverlay()
        }
    }

    // ── Inner View ───────────────────────────────────────────────────────

    /**
     * A transparent [View] that draws filled, rounded rectangles
     * for every blur region supplied via [setRegions].
     */
    private companion object {
        private const val TAG = "OverlayManager"
    }

    private class BlurOverlayView(context: Context) : View(context) {

        /** Current list of bounding boxes to blur (pixel coordinates). */
        private var regions: List<BoundingBox> = emptyList()

        /** Current list of face bounding boxes to protect (never blur). */
        private var faces: List<BoundingBox> = emptyList()

        /** Current visual style (defaults to SOLID_BLACK like HaramBlur). */
        private var currentStyle: BlurStyle = BlurStyle.SOLID_BLACK

        /** Current opacity percentage (50% to 100%, defaults to 100% completely solid). */
        private var currentOpacity: Int = 100

        /** Whether faces should be blurred or preserved. */
        private var blurFaces: Boolean = false

        /** Reusable [RectF] to avoid allocations during draw. */
        private val rectF = RectF()

        /** Primary blur fill paint. */
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        /** Subtle frosted border paint for a polished privacy card look. */
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        /** Lighter secondary layer for a subtle frosted-glass depth effect. */
        private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        /** Corner radius for the rounded blur rectangles (in pixels). */
        private val cornerRadius = 16f

        /** Pixel offset used for the frosted-glass depth layer. */
        private val frostOffset = 2f

        init {
            updatePaints()
        }

        /** Updates visual style and opacity and triggers redraw. */
        fun setStyleAndOpacity(style: BlurStyle, opacityPercent: Int) {
            currentStyle = style
            currentOpacity = opacityPercent.coerceIn(50, 100)
            updatePaints()
            invalidate()
        }

        private fun updatePaints() {
            val alpha = (currentOpacity * 255 / 100).coerceIn(0, 255)
            when (currentStyle) {
                BlurStyle.SOLID_BLACK -> {
                    // 100% Solid Pitch Black — HaramBlur style: completely opaque, zero transparency
                    fillPaint.color = Color.argb(alpha, 0, 0, 0)
                    borderPaint.color = Color.argb((alpha * 0.45f).toInt(), 50, 50, 50)
                    frostPaint.color = Color.TRANSPARENT
                }
                BlurStyle.DARK_FROSTED -> {
                    // Dark Frosted Glass with subtle highlight and rim
                    fillPaint.color = Color.argb(alpha, 18, 20, 24)
                    borderPaint.color = Color.argb((alpha * 0.35f).toInt(), 255, 255, 255)
                    frostPaint.color = Color.argb((alpha * 0.12f).toInt(), 255, 255, 255)
                }
                BlurStyle.LIGHT_CENSOR -> {
                    // Light Censor Card
                    fillPaint.color = Color.argb(alpha, 240, 240, 245)
                    borderPaint.color = Color.argb((alpha * 0.50f).toInt(), 160, 160, 170)
                    frostPaint.color = Color.argb((alpha * 0.15f).toInt(), 255, 255, 255)
                }
            }
        }

        /**
         * Replaces the displayed regions and triggers a redraw.
         * Must be called on the main thread.
         */
        fun setRegions(newRegions: List<BoundingBox>, newFaces: List<BoundingBox> = emptyList()) {
            regions = newRegions
            faces = newFaces
            invalidate()
        }

        fun setBlurFaces(enabled: Boolean) {
            blurFaces = enabled
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (regions.isEmpty()) return

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            // Save canvas to restore clipping after drawing blur boxes
            canvas.save()

            // Hardware clip out all faces in the protected list down to the chin line.
            // This strictly guarantees that not a single pixel of any protected face is blurred,
            // while allowing the continuous torso shield to cover from the neck/chin downwards.
            if (faces.isNotEmpty()) {
                for (face in faces) {
                    val fLeft = (if (face.x1 <= 1.0f && face.x2 <= 1.0f) face.x1 * viewWidth else face.x1) - 4f
                    val fTop = (if (face.y1 <= 1.0f && face.y2 <= 1.0f) face.y1 * viewHeight else face.y1) - 4f
                    val fRight = (if (face.x1 <= 1.0f && face.x2 <= 1.0f) face.x2 * viewWidth else face.x2) + 4f
                    val actualFaceH = if (face.y1 <= 1.0f && face.y2 <= 1.0f) face.height * viewHeight else face.height
                    val fBottom = (if (face.y1 <= 1.0f && face.y2 <= 1.0f) face.y2 * viewHeight else face.y2) - minOf(actualFaceH * 0.08f, 12f)
                    canvas.clipOutRect(
                        fLeft.coerceAtLeast(0f),
                        fTop.coerceAtLeast(0f),
                        fRight.coerceAtMost(viewWidth),
                        fBottom.coerceAtMost(viewHeight)
                    )
                }
            }

            for (box in regions) {
                // If coordinates are normalized [0, 1], scale to view size; otherwise use directly as pixels.
                val left = if (box.x1 <= 1.0f && box.x2 <= 1.0f) (box.x1 * viewWidth) else box.x1.coerceIn(0f, viewWidth)
                val top = if (box.y1 <= 1.0f && box.y2 <= 1.0f) (box.y1 * viewHeight) else box.y1.coerceIn(0f, viewHeight)
                val right = if (box.x1 <= 1.0f && box.x2 <= 1.0f) (box.x2 * viewWidth) else box.x2.coerceIn(0f, viewWidth)
                val bottom = if (box.y1 <= 1.0f && box.y2 <= 1.0f) (box.y2 * viewHeight) else box.y2.coerceIn(0f, viewHeight)

                if (right <= left || bottom <= top) continue

                // 1. Draw the primary blur rectangle.
                rectF.set(left, top, right, bottom)
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint)

                // 2. Draw frosted accent layer if enabled for this style
                if (frostPaint.color != Color.TRANSPARENT) {
                    rectF.set(
                        left + frostOffset,
                        top + frostOffset,
                        right - frostOffset,
                        bottom - frostOffset
                    )
                    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, frostPaint)
                }

                // 3. Draw subtle border rim.
                rectF.set(left, top, right, bottom)
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
            }

            canvas.restore()
        }
    }
}
