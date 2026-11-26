package com.savegaze.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.FloatBuffer
import kotlin.math.sqrt

data class AudioIntimateResult(
    val score: Float,
    val topLabel: String,
    val rms: Float,
    val distractorDominant: Boolean
)

/**
 * Pure AudioSet / YAMNet 521-class scoring logic for intimate vocalizations
 * (moaning, groaning, panting, gasping, sighing, heavy breathing) while suppressing
 * regular speech, music, singing, laughter, and crying.
 */
object AudioIntimateRules {

    // Exact AudioSet indices verified against yamnet_class_map.csv
    const val IDX_SPEECH = 0
    const val IDX_WHISPER = 12
    const val IDX_LAUGHTER = 13
    const val IDX_CRY_SOB = 19
    const val IDX_WAIL_MOAN = 22
    const val IDX_SIGH = 23
    const val IDX_SINGING = 24
    const val IDX_GROAN = 33
    const val IDX_GRUNT = 34
    const val IDX_BREATHING = 36
    const val IDX_GASP = 39
    const val IDX_PANT = 40
    const val IDX_MUSIC = 132
    const val IDX_VOCAL_MUSIC = 249

    fun scoreFrame(probs: FloatArray, rms: Float): AudioIntimateResult {
        if (probs.size < 250 || rms < 0.008f) {
            return AudioIntimateResult(0f, "", rms, false)
        }

        val moan = probs[IDX_WAIL_MOAN]
        val groan = probs[IDX_GROAN]
        val pant = probs[IDX_PANT]
        val gasp = probs[IDX_GASP]
        val sigh = probs[IDX_SIGH]
        val breath = probs[IDX_BREATHING]
        val grunt = probs[IDX_GRUNT]
        val whisper = probs[IDX_WHISPER]

        val weighted = moan * 1.40f +
            groan * 1.25f +
            pant * 1.30f +
            gasp * 0.80f +
            sigh * 0.65f +
            breath * 0.55f +
            grunt * 0.50f +
            whisper * 0.35f

        val maxPrimary = maxOf(moan, groan, pant)
        val distractor = maxOf(
            probs[IDX_SPEECH],
            probs[IDX_MUSIC],
            probs[IDX_SINGING],
            probs[IDX_VOCAL_MUSIC],
            probs[IDX_LAUGHTER],
            probs[IDX_CRY_SOB]
        )

        val label = when {
            moan >= groan && moan >= pant && moan >= 0.08f -> "Moan"
            groan >= pant && groan >= 0.08f -> "Groan"
            pant >= 0.08f -> "Pant / Breathing"
            gasp >= 0.12f || breath >= 0.14f -> "Heavy Breathing"
            else -> ""
        }

        // Prevent sad crying, dialogue, pop singing, or laughter from false-triggering
        val cryingHigher = probs[IDX_CRY_SOB] > maxPrimary * 1.35f && probs[IDX_CRY_SOB] >= 0.22f
        val distractorDominant = cryingHigher || (distractor >= 0.50f && maxPrimary < 0.20f)

        val finalScore = when {
            label.isEmpty() -> 0f
            cryingHigher -> (weighted * 0.15f).coerceIn(0f, 1f)
            distractorDominant -> (weighted * 0.25f).coerceIn(0f, 1f)
            else -> weighted.coerceIn(0f, 1f)
        }

        return AudioIntimateResult(
            score = finalScore,
            topLabel = label,
            rms = rms,
            distractorDominant = distractorDominant
        )
    }
}

/**
 * Captures internal playback audio on Android 10+ (API 29+) via AudioPlaybackCaptureConfiguration
 * without using the device microphone, and evaluates 0.975s sliding windows via YAMNet ONNX.
 */
class AudioIntimateDetector(private val appContext: Context) {

    companion object {
        private const val TAG = "AudioIntimateDetector"
        private const val MODEL_FILE = "yamnet.onnx"
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SAMPLES = 15_600 // 0.975s required by YAMNet
        private const val HOP_SAMPLES = 12_800    // evaluate ~every 0.80s to minimize CPU/battery
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val mutex = Mutex()
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    var latestResult: AudioIntimateResult = AudioIntimateResult(0f, "", 0f, false)
        private set

    @Volatile
    var lastScoredAtMs: Long = 0L
        private set

    init {
        val modelBytes = appContext.assets.open(MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
    }

    fun isCapturing(): Boolean = captureJob?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(
        mediaProjection: MediaProjection,
        scope: CoroutineScope,
        onResult: (AudioIntimateResult) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (isCapturing()) return true

        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(WINDOW_SAMPLES * 2)

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            audioRecord = record
            record.startRecording()

            captureJob = scope.launch(Dispatchers.IO) {
                val ring = FloatArray(WINDOW_SAMPLES)
                var ringFill = 0
                var sinceLastHop = 0
                val readChunk = ShortArray(2048)

                try {
                    while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val screenOn = powerManager?.isInteractive ?: true
                        val mediaPlaying = audioManager?.isMusicActive ?: true
                        if (!screenOn || !mediaPlaying) {
                            ringFill = 0
                            sinceLastHop = 0
                            latestResult = AudioIntimateResult(0f, "", 0f, false)
                            delay(800L)
                            continue
                        }
                        val read = record.read(readChunk, 0, readChunk.size)
                        if (read <= 0) continue

                        val n = minOf(read, WINDOW_SAMPLES)
                        val offset = read - n
                        if (ringFill < WINDOW_SAMPLES) {
                            val space = WINDOW_SAMPLES - ringFill
                            val direct = minOf(n, space)
                            for (i in 0 until direct) {
                                ring[ringFill + i] = readChunk[offset + i] / 32768.0f
                            }
                            ringFill += direct
                            val overflow = n - direct
                            if (overflow > 0) {
                                System.arraycopy(ring, overflow, ring, 0, WINDOW_SAMPLES - overflow)
                                for (i in 0 until overflow) {
                                    ring[WINDOW_SAMPLES - overflow + i] = readChunk[offset + direct + i] / 32768.0f
                                }
                            }
                        } else {
                            if (n < WINDOW_SAMPLES) {
                                System.arraycopy(ring, n, ring, 0, WINDOW_SAMPLES - n)
                            }
                            for (i in 0 until n) {
                                ring[WINDOW_SAMPLES - n + i] = readChunk[offset + i] / 32768.0f
                            }
                        }
                        sinceLastHop += read

                        if (ringFill >= WINDOW_SAMPLES && sinceLastHop >= HOP_SAMPLES) {
                            sinceLastHop = 0
                            var sumSq = 0.0
                            for (s in ring) sumSq += s * s
                            val rms = sqrt(sumSq / ring.size).toFloat()

                            val result = if (rms < 0.008f) {
                                AudioIntimateResult(0f, "", rms, false)
                            } else {
                                val probs = runInference(ring)
                                if (probs != null) AudioIntimateRules.scoreFrame(probs, rms)
                                else AudioIntimateResult(0f, "", rms, false)
                            }
                            latestResult = result
                            lastScoredAtMs = System.currentTimeMillis()
                            onResult(result)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio capture loop terminated", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback audio capture", e)
            stop()
            false
        }
    }

    private suspend fun runInference(waveform: FloatArray): FloatArray? {
        return try {
            mutex.withLock {
                val inputName = session.inputNames.firstOrNull() ?: "waveform"
                val shape = longArrayOf(waveform.size.toLong())
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), shape)
                val out = session.run(mapOf(inputName to tensor))
                try {
                    val scores = out[0].value as? Array<*> ?: return@withLock null
                    scores.firstOrNull() as? FloatArray
                } finally {
                    out.close()
                    tensor.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YAMNet inference failed", e)
            null
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        latestResult = AudioIntimateResult(0f, "", 0f, false)
    }

    fun close() {
        stop()
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing YAMNet session", e)
        }
    }
}
