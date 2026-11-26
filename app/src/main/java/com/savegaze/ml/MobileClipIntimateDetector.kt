package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.savegaze.util.toFloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ClipIntimateScore(
    val intimateProb: Float,
    val margin: Float,
    val maxPosSim: Float,
    val maxNegSim: Float
)

/**
 * Pure cosine-similarity & softmax scoring engine for MobileCLIP2-S0 prompt matching.
 */
object MobileClipIntimateRules {

    fun score(
        imageEmbedding: FloatArray,
        promptEmbeddings: Array<FloatArray>,
        positiveCount: Int,
        logitScale: Float = 85f
    ): ClipIntimateScore {
        if (promptEmbeddings.isEmpty() || positiveCount <= 0 || positiveCount >= promptEmbeddings.size) {
            return ClipIntimateScore(0f, 0f, 0f, 0f)
        }
        var normSq = 0f
        for (v in imageEmbedding) normSq += v * v
        val invNorm = if (normSq > 1e-8f) 1f / sqrt(normSq) else 1f

        val sims = FloatArray(promptEmbeddings.size)
        var maxPos = Float.NEGATIVE_INFINITY
        var maxNeg = Float.NEGATIVE_INFINITY
        var maxAll = Float.NEGATIVE_INFINITY

        for (i in promptEmbeddings.indices) {
            val p = promptEmbeddings[i]
            val len = min(imageEmbedding.size, p.size)
            var dot = 0f
            for (k in 0 until len) {
                dot += (imageEmbedding[k] * invNorm) * p[k]
            }
            sims[i] = dot
            if (dot > maxAll) maxAll = dot
            if (i < positiveCount) {
                if (dot > maxPos) maxPos = dot
            } else {
                if (dot > maxNeg) maxNeg = dot
            }
        }

        var sumPosExp = 0.0
        var sumNegExp = 0.0
        for (i in sims.indices) {
            val e = exp(((sims[i] - maxAll) * logitScale).toDouble().coerceIn(-40.0, 40.0))
            if (i < positiveCount) sumPosExp += e else sumNegExp += e
        }
        val total = sumPosExp + sumNegExp
        val prob = if (total > 0.0) (sumPosExp / total).toFloat() else 0f

        return ClipIntimateScore(
            intimateProb = prob,
            margin = maxPos - maxNeg,
            maxPosSim = maxPos,
            maxNegSim = maxNeg
        )
    }
}

/**
 * On-device MobileCLIP2-S0 vision encoder (`mobileclip_vision_q.onnx`) using precomputed
 * text prompt embeddings (`clip_intimate_prompts.json`) to detect clothed kissing,
 * embracing, and bed scenes in widescreen movies (Nuvio, Stremio, CloudStream, etc.).
 * Evaluated asynchronously in background so it adds zero latency to real-time blurring.
 */
class MobileClipIntimateDetector(context: Context) {

    companion object {
        private const val TAG = "MobileClipDetector"
        private const val VISION_MODEL_FILE = "mobileclip_vision.onnx"
        private const val PROMPTS_FILE = "clip_intimate_prompts.json"
        private const val INPUT_SIZE = 256
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val mutex = Mutex()
    private val promptEmbeddings: Array<FloatArray>
    private val positiveCount: Int

    init {
        val jsonStr = context.assets.open(PROMPTS_FILE).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        positiveCount = root.getInt("positive_count")
        val embArray = root.getJSONArray("embeddings")
        promptEmbeddings = Array(embArray.length()) { r ->
            val row = embArray.getJSONArray(r)
            FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
        }

        val modelBytes = context.assets.open(VISION_MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
        Log.i(TAG, "MobileCLIP FP32 vision loaded (${promptEmbeddings.size} prompts, positive=$positiveCount)")
    }

    suspend fun classify(bitmap: Bitmap): ClipIntimateScore? = withContext(Dispatchers.Default) {
        try {
            // Center square crop prevents 21:9 widescreen cinema movies from squashing or including black letterbox bars
            val cropSize = min(bitmap.width, bitmap.height).coerceAtLeast(1)
            val cropX = ((bitmap.width - cropSize) / 2).coerceAtLeast(0)
            val cropY = ((bitmap.height - cropSize) / 2).coerceAtLeast(0)
            val centerSquare = if (bitmap.width == bitmap.height) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, cropX, cropY, cropSize, cropSize)
            }
            val buffer = centerSquare.toFloatBuffer(INPUT_SIZE, INPUT_SIZE)
            if (centerSquare !== bitmap) {
                centerSquare.recycle()
            }

            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val embedding = mutex.withLock {
                val inputName = session.inputNames.firstOrNull() ?: "pixel_values"
                val tensor = OnnxTensor.createTensor(env, buffer, shape)
                val out = session.run(mapOf(inputName to tensor))
                try {
                    val arr = out[0].value as? Array<*> ?: return@withLock null
                    arr.firstOrNull() as? FloatArray
                } finally {
                    out.close()
                    tensor.close()
                }
            } ?: return@withContext null

            MobileClipIntimateRules.score(embedding, promptEmbeddings, positiveCount)
        } catch (e: Exception) {
            Log.w(TAG, "MobileCLIP inference error", e)
            null
        }
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MobileCLIP session", e)
        }
    }
}
