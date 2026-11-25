package com.savegaze.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.savegaze.data.BlurStyle
import com.savegaze.ml.BoundingBox

/**
 * Manages accessibility overlay windows:
 * 1. Continuous blur overlay (`BlurOverlayView`) for AI body & face blurring
 * 2. Transient block notification banner (`showBlockBanner`)
 * 3. Unclosable full-screen 5-minute Auto-Lockout window (`showLockoutOverlay`)
 */
class OverlayManager(private val context: Context) {

    private companion object {
        private const val TAG = "OverlayManager"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val overlayView = BlurOverlayView(context)
    private var bannerView: LinearLayout? = null
    private var lockoutView: LinearLayout? = null
    private var lockoutCountdownText: TextView? = null
    private var lockoutUnlockButton: Button? = null
    private var lockoutTicker: Runnable? = null

    @Volatile
    private var showing = false

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

    fun setOnVisibilityChangedListener(listener: ((Boolean) -> Unit)?) {
        onVisibilityChanged = listener
    }

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

    fun hideOverlay() {
        mainHandler.post {
            if (showing) {
                try {
                    windowManager.removeView(overlayView)
                } catch (_: IllegalArgumentException) {
                }
                showing = false
                onVisibilityChanged?.invoke(false)
            }
        }
    }

    fun updateBlurRegions(regions: List<BoundingBox>, faces: List<BoundingBox> = emptyList()) {
        mainHandler.post {
            overlayView.setRegions(regions, faces)
            if (regions.isNotEmpty() && !showing) {
                showOverlay()
            }
        }
    }

    fun setStyleAndOpacity(style: BlurStyle, opacityPercent: Int) {
        mainHandler.post {
            overlayView.setStyleAndOpacity(style, opacityPercent)
        }
    }

    fun setBlurFaces(enabled: Boolean) {
        mainHandler.post {
            overlayView.setBlurFaces(enabled)
        }
    }

    fun clearBlurRegions() {
        mainHandler.post {
            overlayView.setRegions(emptyList(), emptyList())
        }
    }

    fun isShowing(): Boolean = showing

    /** Displays a transient high-contrast block notice card when URL/Keyword/App is blocked. */
    fun showBlockBanner(title: String, detail: String) {
        mainHandler.post {
            removeBannerImmediate()
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 34, 44, 34)
                setBackgroundColor(Color.argb(242, 15, 23, 42))
            }
            val titleTv = TextView(context).apply {
                text = title
                setTextColor(Color.rgb(244, 63, 94))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            val detailTv = TextView(context).apply {
                text = detail
                setTextColor(Color.rgb(226, 232, 240))
                textSize = 13f
                setPadding(0, 8, 0, 0)
            }
            container.addView(titleTv)
            container.addView(detailTv)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 110
            }

            try {
                windowManager.addView(container, lp)
                bannerView = container
                mainHandler.postDelayed({ removeBannerImmediate() }, 2400L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show block banner", e)
            }
        }
    }

    private fun removeBannerImmediate() {
        bannerView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        bannerView = null
    }

    /**
     * Displays an unclosable full-screen 5-minute Auto-Lockout overlay matching ContentGuard.
     */
    fun showLockoutOverlay(
        lockoutUntilMs: Long,
        hasMasterPassword: Boolean,
        onUnlockClick: () -> Unit,
        onLockoutExpired: () -> Unit
    ) {
        mainHandler.post {
            if (lockoutView != null) {
                lockoutUnlockButton?.visibility = if (hasMasterPassword) View.VISIBLE else View.GONE
                return@post
            }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 80, 64, 80)
                setBackgroundColor(Color.rgb(15, 23, 42))
                isClickable = true
                isFocusable = true
            }

            val badge = TextView(context).apply {
                text = "LOCKDOWN ACTIVE"
                setTextColor(Color.rgb(244, 63, 94))
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val desc = TextView(context).apply {
                text = "Multiple blocked access attempts were detected.\nScreen access is temporarily locked for discipline recovery."
                setTextColor(Color.rgb(203, 213, 225))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 36)
            }
            val countdown = TextView(context).apply {
                text = "05:00"
                setTextColor(Color.rgb(250, 204, 21))
                textSize = 48f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 40)
            }
            val unlockBtn = Button(context).apply {
                text = "Unlock Early with Master Password"
                visibility = if (hasMasterPassword) View.VISIBLE else View.GONE
                setBackgroundColor(Color.rgb(225, 29, 72))
                setTextColor(Color.WHITE)
                setPadding(36, 22, 36, 22)
                setOnClickListener { onUnlockClick() }
            }

            root.addView(badge)
            root.addView(desc)
            root.addView(countdown)
            root.addView(unlockBtn)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            try {
                windowManager.addView(root, lp)
                lockoutView = root
                lockoutCountdownText = countdown
                lockoutUnlockButton = unlockBtn

                val ticker = object : Runnable {
                    override fun run() {
                        val remMs = lockoutUntilMs - System.currentTimeMillis()
                        if (remMs <= 0L) {
                            hideLockoutOverlay()
                            onLockoutExpired()
                            return
                        }
                        val totalSec = (remMs / 1000L).toInt()
                        val mins = totalSec / 60
                        val secs = totalSec % 60
                        countdown.text = String.format("%02d:%02d", mins, secs)
                        mainHandler.postDelayed(this, 1000L)
                    }
                }
                lockoutTicker = ticker
                mainHandler.post(ticker)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach lockout overlay", e)
            }
        }
    }

    fun hideLockoutOverlay() {
        mainHandler.post {
            lockoutTicker?.let { mainHandler.removeCallbacks(it) }
            lockoutTicker = null
            lockoutView?.let { v ->
                try {
                    windowManager.removeView(v)
                } catch (_: Exception) {
                }
            }
            lockoutView = null
            lockoutCountdownText = null
            lockoutUnlockButton = null
        }
    }

    fun destroy() {
        mainHandler.post {
            removeBannerImmediate()
            hideLockoutOverlay()
            clearBlurRegions()
            hideOverlay()
        }
    }

    private class BlurOverlayView(context: Context) : View(context) {

        private var regions: List<BoundingBox> = emptyList()
        private var faces: List<BoundingBox> = emptyList()
        private var currentStyle: BlurStyle = BlurStyle.SOLID_BLACK
        private var currentOpacity: Int = 100
        private var blurFaces: Boolean = false

        private val rectF = RectF()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val cornerRadius = 16f
        private val frostOffset = 2f

        init {
            updatePaints()
        }

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
                    fillPaint.color = Color.argb(alpha, 0, 0, 0)
                    borderPaint.color = Color.TRANSPARENT
                    borderPaint.strokeWidth = 0f
                    frostPaint.color = Color.TRANSPARENT
                }
                BlurStyle.DARK_FROSTED -> {
                    fillPaint.color = Color.argb(alpha, 18, 20, 24)
                    borderPaint.color = Color.argb((alpha * 0.35f).toInt(), 255, 255, 255)
                    frostPaint.color = Color.argb((alpha * 0.12f).toInt(), 255, 255, 255)
                }
                BlurStyle.LIGHT_CENSOR -> {
                    fillPaint.color = Color.argb(alpha, 240, 240, 245)
                    borderPaint.color = Color.argb((alpha * 0.50f).toInt(), 160, 160, 170)
                    frostPaint.color = Color.argb((alpha * 0.15f).toInt(), 255, 255, 255)
                }
            }
        }

        fun setRegions(newRegions: List<BoundingBox>, newFaces: List<BoundingBox> = emptyList()) {
            if (regions == newRegions && faces == newFaces) return
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
            canvas.save()

            // Hardware clip strictly protects only faces in `faces` (none for target gender when blurFaces=true).
            if (faces.isNotEmpty()) {
                for (face in faces) {
                    val fLeft = (if (face.x1 <= 1.0f && face.x2 <= 1.0f) face.x1 * viewWidth else face.x1) - 4f
                    val fTop = (if (face.y1 <= 1.0f && face.y2 <= 1.0f) face.y1 * viewHeight else face.y1) - 4f
                    val fRight = (if (face.x1 <= 1.0f && face.x2 <= 1.0f) face.x2 * viewWidth else face.x2) + 4f
                    val fBottom = if (face.y1 <= 1.0f && face.y2 <= 1.0f) face.y2 * viewHeight else face.y2
                    canvas.clipOutRect(
                        fLeft.coerceAtLeast(0f),
                        fTop.coerceAtLeast(0f),
                        fRight.coerceAtMost(viewWidth),
                        fBottom.coerceAtMost(viewHeight)
                    )
                }
            }

            for (box in regions) {
                val left = if (box.x1 <= 1.0f && box.x2 <= 1.0f) (box.x1 * viewWidth) else box.x1.coerceIn(0f, viewWidth)
                val top = if (box.y1 <= 1.0f && box.y2 <= 1.0f) (box.y1 * viewHeight) else box.y1.coerceIn(0f, viewHeight)
                val right = if (box.x1 <= 1.0f && box.x2 <= 1.0f) (box.x2 * viewWidth) else box.x2.coerceIn(0f, viewWidth)
                val bottom = if (box.y1 <= 1.0f && box.y2 <= 1.0f) (box.y2 * viewHeight) else box.y2.coerceIn(0f, viewHeight)
                if (right <= left || bottom <= top) continue

                rectF.set(left, top, right, bottom)
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint)

                if (frostPaint.color != Color.TRANSPARENT) {
                    rectF.set(left + frostOffset, top + frostOffset, right - frostOffset, bottom - frostOffset)
                    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, frostPaint)
                }

                if (borderPaint.color != Color.TRANSPARENT && borderPaint.strokeWidth > 0f) {
                    rectF.set(left, top, right, bottom)
                    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
                }
            }

            canvas.restore()
        }
    }
}
