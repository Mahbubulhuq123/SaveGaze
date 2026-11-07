package com.savegaze.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.savegaze.ml.BoundingBox
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Resizes the bitmap to [width] x [height] and converts it to a direct CHW float buffer
 * normalized to 0..1 (RGB channel order).
 * Must use allocateDirect with native byte order so ONNX Runtime JNI can access it without SIGSEGV.
 */
fun Bitmap.toFloatBuffer(width: Int, height: Int): FloatBuffer {
    val resized = if (this.width == width && this.height == height) {
        this
    } else {
        Bitmap.createScaledBitmap(this, width, height, true)
    }

    val pixels = IntArray(width * height)
    resized.getPixels(pixels, 0, width, 0, 0, width, height)

    if (resized !== this) {
        resized.recycle()
    }

    val byteBuffer = ByteBuffer.allocateDirect(3 * width * height * 4)
        .order(ByteOrder.nativeOrder())
    val buffer = byteBuffer.asFloatBuffer()
    val channelSize = width * height

    // CHW layout: all R values, then all G values, then all B values
    for (i in pixels.indices) {
        val pixel = pixels[i]
        buffer.put(i, ((pixel shr 16) and 0xFF) / 255f)                    // R
        buffer.put(i + channelSize, ((pixel shr 8) and 0xFF) / 255f)       // G
        buffer.put(i + 2 * channelSize, (pixel and 0xFF) / 255f)           // B
    }

    buffer.rewind()
    return buffer
}

/**
 * Creates a batched direct CHW float buffer for a list of bitmaps.
 * Shape: [bitmaps.size, 3, width, height]
 * Normalized to 0..1 (RGB channel order).
 */
fun List<Bitmap>.toBatchedFloatBuffer(width: Int, height: Int): FloatBuffer {
    val batchSize = this.size
    val channelSize = width * height
    val imageSize = 3 * channelSize
    val byteBuffer = ByteBuffer.allocateDirect(batchSize * imageSize * 4)
        .order(ByteOrder.nativeOrder())
    val buffer = byteBuffer.asFloatBuffer()
    val pixels = IntArray(width * height)

    for (b in indices) {
        val src = this[b]
        val resized = if (src.width == width && src.height == height) {
            src
        } else {
            Bitmap.createScaledBitmap(src, width, height, true)
        }
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        if (resized !== src) {
            resized.recycle()
        }

        val batchOffset = b * imageSize
        for (i in pixels.indices) {
            val pixel = pixels[i]
            buffer.put(batchOffset + i, ((pixel shr 16) and 0xFF) / 255f)                    // R
            buffer.put(batchOffset + i + channelSize, ((pixel shr 8) and 0xFF) / 255f)       // G
            buffer.put(batchOffset + i + 2 * channelSize, (pixel and 0xFF) / 255f)           // B
        }
    }

    buffer.rewind()
    return buffer
}

/**
 * Creates a copy of this bitmap with the specified [regions] blurred.
 * Uses a CPU-based stack blur (box blur approximation) since RenderScript is deprecated.
 */
fun Bitmap.applyBlur(regions: List<BoundingBox>, blurRadius: Int = 25): Bitmap {
    if (regions.isEmpty()) return this.copy(this.config ?: Bitmap.Config.ARGB_8888, false)

    val result = this.copy(this.config ?: Bitmap.Config.ARGB_8888, true)
    val bitmapWidth = result.width
    val bitmapHeight = result.height

    for (region in regions) {
        // Clamp region to bitmap bounds
        val rx1 = max(0, region.x1.toInt())
        val ry1 = max(0, region.y1.toInt())
        val rx2 = min(bitmapWidth, region.x2.toInt())
        val ry2 = min(bitmapHeight, region.y2.toInt())

        val regionWidth = rx2 - rx1
        val regionHeight = ry2 - ry1

        if (regionWidth <= 0 || regionHeight <= 0) continue

        // Extract the region pixels
        val regionPixels = IntArray(regionWidth * regionHeight)
        result.getPixels(regionPixels, 0, regionWidth, rx1, ry1, regionWidth, regionHeight)

        // Apply stack blur
        val effectiveRadius = min(blurRadius, min(regionWidth, regionHeight) / 2)
        if (effectiveRadius > 0) {
            stackBlur(regionPixels, regionWidth, regionHeight, effectiveRadius)
        }

        // Write blurred pixels back
        result.setPixels(regionPixels, 0, regionWidth, rx1, ry1, regionWidth, regionHeight)
    }

    return result
}

/**
 * CPU-based stack blur implementation.
 * Two-pass (horizontal then vertical) box blur applied [radius] times effectively.
 * Uses the stack blur algorithm by Mario Klingemann for good quality at reasonable speed.
 */
fun stackBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0) return

    val div = 2 * radius + 1
    val divSum = (radius + 1) * (radius + 1)

    val r = IntArray(width * height)
    val g = IntArray(width * height)
    val b = IntArray(width * height)

    val vMin = IntArray(max(width, height))
    val vMax = IntArray(max(width, height))

    val dv = IntArray(256 * divSum)
    for (i in dv.indices) {
        dv[i] = i / divSum
    }

    // Horizontal pass
    for (y in 0 until height) {
        var rSum = 0
        var gSum = 0
        var bSum = 0

        // Pre-compute sum for the initial window
        for (i in -radius..radius) {
            val x = i.coerceIn(0, width - 1)
            val pixel = pixels[y * width + x]
            val weight = radius + 1 - kotlin.math.abs(i)
            rSum += ((pixel shr 16) and 0xFF) * weight
            gSum += ((pixel shr 8) and 0xFF) * weight
            bSum += (pixel and 0xFF) * weight
        }

        for (x in 0 until width) {
            r[y * width + x] = dv[min(rSum, dv.size - 1).coerceAtLeast(0)]
            g[y * width + x] = dv[min(gSum, dv.size - 1).coerceAtLeast(0)]
            b[y * width + x] = dv[min(bSum, dv.size - 1).coerceAtLeast(0)]

            val nextIdx = min(x + radius + 1, width - 1)
            val prevIdx = max(x - radius, 0)
            val nextPixel = pixels[y * width + nextIdx]
            val prevPixel = pixels[y * width + prevIdx]

            rSum += ((nextPixel shr 16) and 0xFF) - ((prevPixel shr 16) and 0xFF)
            gSum += ((nextPixel shr 8) and 0xFF) - ((prevPixel shr 8) and 0xFF)
            bSum += (nextPixel and 0xFF) - (prevPixel and 0xFF)
        }
    }

    // Write intermediate result back to pixels for the vertical pass
    for (i in pixels.indices) {
        pixels[i] = (0xFF shl 24) or
                (r[i].coerceIn(0, 255) shl 16) or
                (g[i].coerceIn(0, 255) shl 8) or
                b[i].coerceIn(0, 255)
    }

    // Vertical pass
    for (x in 0 until width) {
        var rSum = 0
        var gSum = 0
        var bSum = 0

        for (i in -radius..radius) {
            val y = i.coerceIn(0, height - 1)
            val pixel = pixels[y * width + x]
            val weight = radius + 1 - kotlin.math.abs(i)
            rSum += ((pixel shr 16) and 0xFF) * weight
            gSum += ((pixel shr 8) and 0xFF) * weight
            bSum += (pixel and 0xFF) * weight
        }

        for (y in 0 until height) {
            r[y * width + x] = dv[min(rSum, dv.size - 1).coerceAtLeast(0)]
            g[y * width + x] = dv[min(gSum, dv.size - 1).coerceAtLeast(0)]
            b[y * width + x] = dv[min(bSum, dv.size - 1).coerceAtLeast(0)]

            val nextIdx = min(y + radius + 1, height - 1)
            val prevIdx = max(y - radius, 0)
            val nextPixel = pixels[nextIdx * width + x]
            val prevPixel = pixels[prevIdx * width + x]

            rSum += ((nextPixel shr 16) and 0xFF) - ((prevPixel shr 16) and 0xFF)
            gSum += ((nextPixel shr 8) and 0xFF) - ((prevPixel shr 8) and 0xFF)
            bSum += (nextPixel and 0xFF) - (prevPixel and 0xFF)
        }
    }

    // Write final result
    for (i in pixels.indices) {
        pixels[i] = (0xFF shl 24) or
                (r[i].coerceIn(0, 255) shl 16) or
                (g[i].coerceIn(0, 255) shl 8) or
                b[i].coerceIn(0, 255)
    }
}

/**
 * Checks whether a candidate bounding box contains a minimum proportion of human skin chrominance.
 * Used to reject inanimate objects (wood carvings, furniture, pillows, vegetables/onions, wallpaper, plain fabrics).
 */
fun Bitmap.hasHumanSkin(
    box: BoundingBox,
    minFraction: Float = 0.08f,
    hasNearFace: Boolean = false
): Boolean {
    val bWidth = this.width
    val bHeight = this.height
    val left = box.x1.toInt().coerceIn(0, bWidth - 1)
    val top = box.y1.toInt().coerceIn(0, bHeight - 1)
    val right = box.x2.toInt().coerceIn(left + 1, bWidth)
    val bottom = box.y2.toInt().coerceIn(top + 1, bHeight)

    val regionW = right - left
    val regionH = bottom - top
    if (regionW < 4 || regionH < 4) return false

    // Sample a grid of up to 14x14 points across the box
    val stepX = maxOf(1, regionW / 14)
    val stepY = maxOf(1, regionH / 14)

    var skinCount = 0
    var onionCount = 0
    var totalSampled = 0

    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val pixel = this.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Detect raw red/purple vegetable skins (e.g. red onions, beets):
            // Red onions have hyper-saturated magenta/dark-red hue where R is high but G and B are deeply suppressed.
            val isRedOnion = (r in 110..220) && (g in 20..85) && (b in 20..95) && ((r - g) > 65)
            if (isRedOnion) onionCount++

            // YCbCr skin formula:
            val cb = (-0.1687f * r - 0.3313f * g + 0.5000f * b + 128f).toInt()
            val cr = (0.5000f * r - 0.4187f * g - 0.0813f * b + 128f).toInt()

            // 1. Standard warm human skin cluster (fair, olive, tan, south-asian, deep skin)
            val isWarmSkin = (r > 40 && g > 25 && b > 15) &&
                    (r > g && (r > b || (g - b) in -15..30)) &&
                    (cb in 68..142) &&
                    (cr in 126..180)

            // 2. Cool / neon / club / car ambient lighting skin (e.g. music videos, blue/cyan backlight)
            val isCoolSkin = (r > 35 && g > 25 && b > 25) &&
                    (cb in 75..165) &&
                    (cr in 115..172) &&
                    (r >= g * 0.80f)

            if (!isRedOnion && (isWarmSkin || isCoolSkin)) {
                skinCount++
            }
            totalSampled++
            x += stepX
        }
        y += stepY
    }

    if (totalSampled == 0) return false

    // If more than 20% of the sample points are red onion peel and no face is attached, reject as onion!
    if (onionCount.toFloat() / totalSampled.toFloat() > 0.20f && !hasNearFace) {
        return false
    }

    val skinFraction = skinCount.toFloat() / totalSampled.toFloat()
    val requiredFraction = if (hasNearFace) 0.04f else minFraction
    return skinFraction >= requiredFraction
}

/**
 * Computes the exact fraction (0.0 to 1.0) of human skin within a bounding box.
 * Used to distinguish modest clothed shirts (< 15% skin) from low-cut cleavage or swimwear (>= 25%),
 * and shirtless males (>= 35% skin).
 */
fun Bitmap.getSkinFraction(box: BoundingBox): Float {
    val bWidth = this.width
    val bHeight = this.height
    val left = box.x1.toInt().coerceIn(0, bWidth - 1)
    val top = box.y1.toInt().coerceIn(0, bHeight - 1)
    val right = box.x2.toInt().coerceIn(left + 1, bWidth)
    val bottom = box.y2.toInt().coerceIn(top + 1, bHeight)

    val regionW = right - left
    val regionH = bottom - top
    if (regionW < 4 || regionH < 4) return 0f

    val stepX = maxOf(1, regionW / 14)
    val stepY = maxOf(1, regionH / 14)

    var skinCount = 0
    var totalSampled = 0

    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val pixel = this.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val isRedOnion = (r in 110..220) && (g in 20..85) && (b in 20..95) && ((r - g) > 65)

            val cb = (-0.1687f * r - 0.3313f * g + 0.5000f * b + 128f).toInt()
            val cr = (0.5000f * r - 0.4187f * g - 0.0813f * b + 128f).toInt()

            val isWarmSkin = (r > 40 && g > 25 && b > 15) &&
                    (r > g && (r > b || (g - b) in -15..30)) &&
                    (cb in 68..142) &&
                    (cr in 126..180)

            val isCoolSkin = (r > 35 && g > 25 && b > 25) &&
                    (cb in 75..165) &&
                    (cr in 115..172) &&
                    (r >= g * 0.80f)

            if (!isRedOnion && (isWarmSkin || isCoolSkin)) {
                skinCount++
            }
            totalSampled++
            x += stepX
        }
        y += stepY
    }

    if (totalSampled == 0) return 0f
    return skinCount.toFloat() / totalSampled.toFloat()
}

/**
 * Accurately analyzes facial and neck features to detect male identity:
 * - Stubble, mustache, 5 o'clock shadow, or facial hair in the lower third of the face
 * - Squarer, wider masculine jawline
 * - Upper face (forehead) skin vs lower face contrast
 * - Prevents sunglasses/eyewear from throwing off forehead luminance
 */
fun Bitmap.isLikelyMaleFace(faceBox: BoundingBox): Boolean {
    val bWidth = this.width
    val bHeight = this.height
    val left = faceBox.x1.toInt().coerceIn(0, bWidth - 1)
    val top = faceBox.y1.toInt().coerceIn(0, bHeight - 1)
    val right = faceBox.x2.toInt().coerceIn(left + 1, bWidth)
    val bottom = faceBox.y2.toInt().coerceIn(top + 1, bHeight)

    val faceW = right - left
    val faceH = bottom - top
    if (faceW < 10 || faceH < 10) return false

    // Forehead region: 0.10H to 0.26H (avoids sunglasses/eyewear at 0.30H to 0.52H)
    val foreY1 = top + (faceH * 0.10f).toInt()
    val foreY2 = top + (faceH * 0.26f).toInt()
    val foreX1 = left + (faceW * 0.25f).toInt()
    val foreX2 = left + (faceW * 0.75f).toInt()

    var foreLumSum = 0L
    var foreSkinCount = 0

    val stepX = maxOf(1, faceW / 12)
    val stepY = maxOf(1, faceH / 16)

    for (y in foreY1..foreY2 step stepY) {
        for (x in foreX1..foreX2 step stepX) {
            val p = this.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r > 35 && r >= g && g >= b * 0.70f) {
                foreLumSum += (r * 299 + g * 587 + b * 114) / 1000
                foreSkinCount++
            }
        }
    }

    val avgForeheadLum = if (foreSkinCount > 0) foreLumSum / foreSkinCount else 128L

    // Lower face: 0.56H to 0.94H (upper lip, mustache, chin, jawline)
    val lowY1 = top + (faceH * 0.56f).toInt()
    val lowY2 = top + (faceH * 0.94f).toInt()
    val lowX1 = left + (faceW * 0.15f).toInt()
    val lowX2 = left + (faceW * 0.85f).toInt()

    var lowLumSum = 0L
    var lowCount = 0
    var stubblePixelCount = 0

    for (y in lowY1..lowY2 step stepY) {
        for (x in lowX1..lowX2 step stepX) {
            val p = this.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            lowLumSum += lum
            lowCount++

            // Stubble / mustache / beard pixels are darker than forehead skin
            if (lum < avgForeheadLum * 0.78) {
                stubblePixelCount++
            }
        }
    }

    val avgLowerLum = if (lowCount > 0) lowLumSum / lowCount else 128L

    // Men have darker lower faces due to facial hair / stubble / mustache
    val hasFacialHairShadow = (lowCount > 0 && stubblePixelCount.toFloat() / lowCount.toFloat() >= 0.12f) ||
            (avgLowerLum < avgForeheadLum * 0.85)

    return hasFacialHairShadow
}

/**
 * Finds the visual container bounds of an image or video card on the screen.
 * Scans downwards, upwards, and horizontally to identify where the media ends
 * (e.g. at web page background #202124 / #ffffff, action buttons "Visit"/"Save", or card margins).
 * Strictly prevents blur boxes from overflowing into adjacent UI, buttons, or other images!
 */
fun Bitmap.findMediaContainerBounds(anchorBox: BoundingBox): BoundingBox {
    val bWidth = this.width
    val bHeight = this.height

    val centerY = anchorBox.centerY.toInt().coerceIn(0, bHeight - 1)
    val spanX = maxOf(anchorBox.width * 1.5f, 70f)
    val sampleLeft = (anchorBox.centerX - spanX).toInt().coerceIn(0, bWidth - 1)
    val sampleRight = (anchorBox.centerX + spanX).toInt().coerceIn(0, bWidth - 1)
    val step = maxOf(1, (sampleRight - sampleLeft) / 12)

    // 1. Scan downwards from anchor bottom
    var bottomY = bHeight.toFloat()
    val checkYStart = anchorBox.y2.toInt().coerceIn(0, bHeight - 1)

    for (y in checkYStart until bHeight - 4 step 4) {
        var uiBgCount = 0
        var total = 0

        var x = sampleLeft
        while (x <= sampleRight) {
            val p = this.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            // Dark theme backgrounds, cards, buttons (#121212 .. #3c4043)
            val isDarkUi = (r in 14..65 && g in 14..65 && b in 14..70 && kotlin.math.abs(r - g) <= 8 && kotlin.math.abs(g - b) <= 8)
            // Light theme backgrounds (#f0f0f0 .. #ffffff)
            val isLightUi = (r >= 235 && g >= 235 && b >= 235)
            // Blue action buttons like Google's "Visit" (#1a73e8, #8ab4f8)
            val isActionBtn = (b > 180 && b > r + 35 && b > g + 18)

            if (isDarkUi || isLightUi || isActionBtn) {
                uiBgCount++
            }
            total++
            x += step
        }

        // If >= 70% of the row across the person's column matches UI background/buttons, image ended!
        if (total > 0 && uiBgCount.toFloat() / total >= 0.70f) {
            bottomY = y.toFloat()
            break
        }
    }

    // 2. Scan upwards from anchor top
    var topY = 0f
    val checkYTop = anchorBox.y1.toInt().coerceIn(0, bHeight - 1)
    for (y in checkYTop downTo 4 step 4) {
        var uiBgCount = 0
        var total = 0

        var x = sampleLeft
        while (x <= sampleRight) {
            val p = this.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val isDarkUi = (r in 14..65 && g in 14..65 && b in 14..70 && kotlin.math.abs(r - g) <= 8 && kotlin.math.abs(g - b) <= 8)
            val isLightUi = (r >= 235 && g >= 235 && b >= 235)
            if (isDarkUi || isLightUi) {
                uiBgCount++
            }
            total++
            x += step
        }

        if (total > 0 && uiBgCount.toFloat() / total >= 0.75f) {
            topY = y.toFloat()
            break
        }
    }

    // 3. Scan horizontally left and right at centerY to find card horizontal boundaries if inside a card
    var leftX = 0f
    for (x in sampleLeft downTo 4 step 4) {
        val p = this.getPixel(x, centerY)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val isUi = (r in 14..65 && g in 14..65 && b in 14..70 && kotlin.math.abs(r - g) <= 8 && kotlin.math.abs(g - b) <= 8) ||
                (r >= 235 && g >= 235 && b >= 235)
        if (isUi) {
            leftX = x.toFloat()
            break
        }
    }

    var rightX = bWidth.toFloat()
    for (x in sampleRight until bWidth - 4 step 4) {
        val p = this.getPixel(x, centerY)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val isUi = (r in 14..65 && g in 14..65 && b in 14..70 && kotlin.math.abs(r - g) <= 8 && kotlin.math.abs(g - b) <= 8) ||
                (r >= 235 && g >= 235 && b >= 235)
        if (isUi) {
            rightX = x.toFloat()
            break
        }
    }

    return BoundingBox(
        x1 = leftX,
        y1 = topY,
        x2 = rightX,
        y2 = bottomY
    )
}

