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
