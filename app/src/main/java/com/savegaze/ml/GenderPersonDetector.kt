package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.savegaze.util.toTopLeftLetterboxFloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.exp

/**
 * Dedicated fast YOLOv4-tiny person & gender detector converted from Porda AI (`pordav4x3.onnx`).
 * Class 0 = MALE, Class 1 = FEMALE. Input size: 544 x 320 (top-left letterboxed RGB 0..1).
 */
class GenderPersonDetector(
    context: Context,
    var confidenceThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "GenderPersonDetector"
        private const val MODEL_FILE = "pordav4x3.onnx"
        private const val INPUT_NAME = "images"
        const val MODEL_WIDTH = 544
        const val MODEL_HEIGHT = 320
        private const val NUM_ANCHORS_PER_HEAD = 3
        private const val NUM_CLASSES = 2
        private const val STRIDE_PER_ANCHOR = 5 + NUM_CLASSES // 7
        private const val SCALE_XY = 1.05f
        private val ANCHORS = arrayOf(
            floatArrayOf(10f, 14f),
            floatArrayOf(23f, 27f),
            floatArrayOf(37f, 58f),
            floatArrayOf(81f, 82f),
            floatArrayOf(135f, 169f),
            floatArrayOf(344f, 319f)
        )
        private val HEAD_MASKS = arrayOf(
            intArrayOf(3, 4, 5), // Head 0: 10x17
            intArrayOf(0, 1, 2)  // Head 1: 20x34
        )
        private val HEAD_NAMES = listOf("conv29_out", "conv36_out")

        private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

        /**
         * Decodes a raw YOLOv4-tiny head tensor `[21, gridH, gridW]` back into bitmap coordinates.
         * Exposed internally for unit testing.
         */
        fun decodeHead(
            headTensor: Array<FloatArray>,
            gridH: Int,
            gridW: Int,
            headIndex: Int,
            scaleX: Float,
            scaleY: Float,
            offsetY: Float,
            maxWidth: Float,
            maxHeight: Float,
            threshold: Float
        ): List<PersonGenderBox> {
            val mask = HEAD_MASKS[headIndex]
            val candidates = mutableListOf<PersonGenderBox>()
            val offsetBias = 0.5f * (SCALE_XY - 1.0f)

            for (a in 0 until NUM_ANCHORS_PER_HEAD) {
                val baseCh = a * STRIDE_PER_ANCHOR
                val anchorW = ANCHORS[mask[a]][0]
                val anchorH = ANCHORS[mask[a]][1]
                val txRow = headTensor[baseCh]
                val tyRow = headTensor[baseCh + 1]
                val twRow = headTensor[baseCh + 2]
                val thRow = headTensor[baseCh + 3]
                val objRow = headTensor[baseCh + 4]
                val cls0Row = headTensor[baseCh + 5]
                val cls1Row = headTensor[baseCh + 6]

                for (cy in 0 until gridH) {
                    val rowOffset = cy * gridW
                    for (cx in 0 until gridW) {
                        val idx = rowOffset + cx
                        val objScore = sigmoid(objRow[idx])
                        if (objScore < threshold) continue

                        val maleScore = objScore * sigmoid(cls0Row[idx])
                        val femaleScore = objScore * sigmoid(cls1Row[idx])
                        val bestScore: Float
                        val otherScore: Float
                        val gender: PersonGender
                        if (femaleScore >= maleScore) {
                            bestScore = femaleScore
                            otherScore = maleScore
                            gender = PersonGender.FEMALE
                        } else {
                            bestScore = maleScore
                            otherScore = femaleScore
                            gender = PersonGender.MALE
                        }
                        if (bestScore < threshold) continue

                        val bx = (cx + sigmoid(txRow[idx]) * SCALE_XY - offsetBias) / gridW
                        val by = (cy + sigmoid(tyRow[idx]) * SCALE_XY - offsetBias) / gridH
                        val bw = exp(twRow[idx].coerceIn(-10f, 10f)) * anchorW / MODEL_WIDTH
                        val bh = exp(thRow[idx].coerceIn(-10f, 10f)) * anchorH / MODEL_HEIGHT

                        val cxPx = bx * MODEL_WIDTH * scaleX
                        val cyPx = by * MODEL_HEIGHT * scaleY + offsetY
                        val wPx = bw * MODEL_WIDTH * scaleX
                        val hPx = bh * MODEL_HEIGHT * scaleY

                        val box = BoundingBox(
                            x1 = (cxPx - wPx / 2f).coerceIn(0f, maxWidth),
                            y1 = (cyPx - hPx / 2f).coerceIn(0f, maxHeight),
                            x2 = (cxPx + wPx / 2f).coerceIn(0f, maxWidth),
                            y2 = (cyPx + hPx / 2f).coerceIn(0f, maxHeight)
                        )
                        if (box.width >= 10f && box.height >= 10f) {
                            candidates.add(PersonGenderBox(gender, bestScore, box, otherScore))
                        }
                    }
                }
            }
            return candidates
        }

        /** Applies greedy NMS and suppresses conflicting lower-scoring opposite-gender boxes on the same person. */
        fun applyNms(detections: List<PersonGenderBox>, iouThreshold: Float = 0.45f): List<PersonGenderBox> {
            if (detections.isEmpty()) return emptyList()
            val sorted = detections.sortedByDescending { it.confidence }
            val kept = mutableListOf<PersonGenderBox>()
            for (cand in sorted) {
                val suppressed = kept.any { existing ->
                    val iou = cand.box.iou(existing.box)
                    if (existing.gender == cand.gender) {
                        iou > iouThreshold
                    } else {
                        val candInsideExisting = cand.box.centerX in existing.box.x1..existing.box.x2 &&
                            cand.box.centerY in existing.box.y1..existing.box.y2 &&
                            existing.confidence >= cand.confidence * 1.25f
                        iou > 0.28f || candInsideExisting
                    }
                }
                if (!suppressed) {
                    kept.add(cand)
                }
            }
            return kept
        }
    }

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val mutex = Mutex()
    private var isClosed = false

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        ortSession = ortEnvironment.createSession(modelBytes, sessionOptions)
        Log.d(TAG, "PordaV4x3 ONNX model loaded successfully")
    }

    suspend fun detect(bitmap: Bitmap): List<PersonGenderBox> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (isClosed) return@withContext emptyList()

            val fullW = bitmap.width.toFloat()
            val fullH = bitmap.height.toFloat()
            val aspectRatio = fullH / fullW
            val all = mutableListOf<PersonGenderBox>()

            // 1. Global letterboxed pass
            all.addAll(runInference(bitmap, 0f, fullW, fullH))

            // 2. Portrait mobile strips sized close to 544:320 aspect so multi-image grids stay sharp
            if (aspectRatio > 1.35f && fullH > 600f) {
                val stripH = (fullH * 0.36f).toInt().coerceAtLeast(1)
                val offsets = intArrayOf(
                    0,
                    (fullH * 0.22f).toInt(),
                    (fullH * 0.44f).toInt(),
                    (fullH * 0.64f).toInt()
                )
                for (yOff in offsets) {
                    val safeH = minOf(stripH, bitmap.height - yOff)
                    if (safeH <= 32) continue
                    try {
                        val tile = Bitmap.createBitmap(bitmap, 0, yOff, bitmap.width, safeH)
                        all.addAll(runInference(tile, yOff.toFloat(), fullW, fullH))
                        tile.recycle()
                    } catch (e: Exception) {
                        Log.w(TAG, "Portrait tile skipped at y=$yOff", e)
                    }
                }
            }

            applyNms(all, iouThreshold)
        }
    }

    private fun runInference(
        tile: Bitmap,
        offsetY: Float,
        fullW: Float,
        fullH: Float
    ): List<PersonGenderBox> {
        val (floatBuffer, scaleX, scaleY) = tile.toTopLeftLetterboxFloatBuffer(MODEL_WIDTH, MODEL_HEIGHT)
        val shape = longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
            val results = ortSession.run(mapOf(INPUT_NAME to inputTensor))
            val parsed = mutableListOf<PersonGenderBox>()

            HEAD_NAMES.forEachIndexed { headIndex, outputName ->
                val tensorOpt = results.get(outputName)
                if (tensorOpt.isPresent) {
                    @Suppress("UNCHECKED_CAST")
                    val raw = (tensorOpt.get().value as Array<Array<Array<FloatArray>>>)[0]
                    val gridH = raw[0].size
                    val gridW = raw[0][0].size
                    val flattened = Array(raw.size) { c ->
                        val flat = FloatArray(gridH * gridW)
                        for (y in 0 until gridH) {
                            System.arraycopy(raw[c][y], 0, flat, y * gridW, gridW)
                        }
                        flat
                    }
                    parsed.addAll(
                        decodeHead(
                            headTensor = flattened,
                            gridH = gridH,
                            gridW = gridW,
                            headIndex = headIndex,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            offsetY = offsetY,
                            maxWidth = fullW,
                            maxHeight = fullH,
                            threshold = confidenceThreshold
                        )
                    )
                }
            }
            results.close()
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "PordaV4x3 inference failed", e)
            emptyList()
        } finally {
            inputTensor?.close()
        }
    }

    fun close() {
        isClosed = true
        try {
            ortSession.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GenderPersonDetector session", e)
        }
    }
}
