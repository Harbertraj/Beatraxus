package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * AI-powered Stem Separation Engine using SCNet int8 ONNX model.
 * Separates stereo audio waveform @ 44.1 kHz into 4 stems: Vocals, Drums, Bass, Other.
 * Uses ONNX Runtime with XNNPACK / CPU fallback, guarded by a session lock.
 *
 * The FFT maths lives in [StemDsp]. One instance keeps its big work buffers (about 15 MB of input spectrogram, the
 * per-chunk stem arrays) for its whole life instead of allocating them again for every 11 second chunk.
 */
class StemSeparator(private val context: Context) {

    companion object {
        private const val TAG = "StemSeparator"
        private const val MODEL_FILENAME = "scnet.onnx"
        private const val ASSET_PATH = "models/$MODEL_FILENAME"

        const val SAMPLE_RATE = 44100
        const val N_FFT = StemDsp.N_FFT
        const val HOP_SIZE = StemDsp.HOP_SIZE
        const val N_FRAMES = StemDsp.N_FRAMES
        const val SEGMENT_SAMPLES = StemDsp.SEGMENT_SAMPLES // 11 s @ 44.1 kHz
        const val PAD_SAMPLES = StemDsp.PAD_SAMPLES
        const val PADDED_SAMPLES = StemDsp.PADDED_SAMPLES // 2048 + 485100 + 2048 extended to fit 476 frames

        /**
         * Distance between the starts of two model chunks: 75 % of a chunk, so neighbours overlap by 25 % (2.75 s)
         * and are cross-faded with a Hann weight. It used to be 25 % of a chunk (75 % overlap), which ran the model
         * four times over every second of music. This costs a third of that and is the usual overlap of
         * Demucs-style separators. A smaller number is slower and a little smoother at the chunk seams.
         */
        const val HOP_FRAMES = SEGMENT_SAMPLES * 3 / 4

        /** A chunk this quiet is digital silence: skip the model (it also keeps a divide-by-silence out of it). */
        private const val SILENCE_PEAK = 1e-6f

        /** More non-finite spectrogram bins than this share in one model run means the model output is garbage. */
        private const val MAX_BAD_BIN_RATIO = 0.05

        /** App stem order (Vocals, Drums, Bass, Other) -> model source order (Drums, Bass, Other, Vocals). */
        private val APP_TO_MODEL_SOURCE = intArrayOf(3, 0, 1, 2)

        fun isModelInstalled(context: Context?): Boolean {
            if (context == null) return false
            val fileInFiles = File(context.filesDir, "models/$MODEL_FILENAME")
            if (fileInFiles.exists() && fileInFiles.length() > 0) return true

            val fileInFilesRoot = File(context.filesDir, MODEL_FILENAME)
            if (fileInFilesRoot.exists() && fileInFilesRoot.length() > 0) return true

            try {
                val assets = context.assets ?: return false
                try {
                    assets.open(ASSET_PATH).use { return true }
                } catch (_: Throwable) {
                    assets.open(MODEL_FILENAME).use { return true }
                }
            } catch (_: Throwable) {}
            return false
        }

        fun processOverlapAdd(
            inputPcm: FloatArray,
            chunkSizeFrames: Int = SEGMENT_SAMPLES,
            hopSizeFrames: Int = HOP_FRAMES,
            progressCallback: ((Float) -> Unit)? = null,
            isCancelled: (() -> Boolean)? = null,
            processChunk: (inChunk: Array<FloatArray>, numFrames: Int) -> Array<Array<FloatArray>>?
        ): Array<FloatArray>? {
            val totalFrames = inputPcm.size / 2
            if (totalFrames == 0) return null

            val numStems = 4
            val outAccum = Array(numStems) { Array(2) { FloatArray(totalFrames) } }
            val weightAccum = FloatArray(totalFrames)

            val hann = FloatArray(chunkSizeFrames) { i ->
                sin(PI * (i + 0.5) / chunkSizeFrames).let { (it * it).toFloat() }
            }

            var framePos = 0
            while (framePos < totalFrames) {
                if (isCancelled?.invoke() == true) return null

                val framesInChunk = minOf(chunkSizeFrames, totalFrames - framePos)

                val inChunk = Array(2) { FloatArray(chunkSizeFrames) }
                for (i in 0 until framesInChunk) {
                    val idx = (framePos + i) * 2
                    inChunk[0][i] = inputPcm[idx]
                    inChunk[1][i] = inputPcm[idx + 1]
                }

                val chunkStems = processChunk(inChunk, chunkSizeFrames) ?: return null

                for (s in 0 until numStems) {
                    for (ch in 0..1) {
                        val stemCh = chunkStems[s][ch]
                        val outCh = outAccum[s][ch]
                        for (i in 0 until framesInChunk) {
                            val targetFrame = framePos + i
                            val w = hann[i]
                            outCh[targetFrame] += stemCh[i] * w
                        }
                    }
                }
                for (i in 0 until framesInChunk) {
                    weightAccum[framePos + i] += hann[i]
                }

                // The last chunk reached the end of the song: another one would only re-run the tail.
                if (framePos + chunkSizeFrames >= totalFrames) break
                framePos += hopSizeFrames
                progressCallback?.invoke((framePos.toFloat() / totalFrames).coerceIn(0f, 1f))
            }

            val resultStems = Array(numStems) { FloatArray(totalFrames * 2) }
            for (f in 0 until totalFrames) {
                val w = if (weightAccum[f] > 1e-30f) weightAccum[f] else 1.0f
                val invW = 1.0f / w
                for (s in 0 until numStems) {
                    val stemOut = resultStems[s]
                    val idx = f * 2
                    stemOut[idx] = outAccum[s][0][f] * invW
                    stemOut[idx + 1] = outAccum[s][1][f] * invW
                }
            }

            return resultStems
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private val sessionLock = Any()
    @Volatile private var closed = false
    @Volatile private var isInitialized = false

    /** Why the last [inferChunk] returned null (shown to the user), or null. */
    @Volatile var lastError: String? = null
        private set

    private val radix2Fft = Radix2FFT(N_FFT)

    // Work buffers, created on first use and reused for every chunk (see class comment).
    private val reBuf = FloatArray(N_FFT)
    private val imBuf = FloatArray(N_FFT)
    private val outL = FloatArray(PADDED_SAMPLES)
    private val outR = FloatArray(PADDED_SAMPLES)
    private var mixFloats: FloatBuffer? = null
    private var stemOut: Array<Array<FloatArray>>? = null

    private fun obtainMix(): FloatBuffer {
        mixFloats?.let { return it }
        val fb = ByteBuffer.allocateDirect(4 * StemDsp.BINS * N_FRAMES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        mixFloats = fb
        return fb
    }

    private fun obtainStemOut(chunkSize: Int): Array<Array<FloatArray>> {
        val cur = stemOut
        if (cur != null && cur[0][0].size == chunkSize) return cur
        val fresh = Array(4) { Array(2) { FloatArray(chunkSize) } }
        stemOut = fresh
        return fresh
    }

    fun initialize(): Boolean = synchronized(sessionLock) { initializeLocked() }

    private fun initializeLocked(): Boolean {
        if (closed) return false
        if (isInitialized) return ortSession != null

        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelFile = getOrExtractModelFile()
            if (modelFile == null || !modelFile.exists() || modelFile.length() <= 0) {
                Log.w(TAG, "SCNet model file not present in filesDir or assets")
                isInitialized = true
                return false
            }

            var session: OrtSession? = null

            // NNAPI is skipped on purpose: it can silently mis-run large int8 spectrogram models.
            // 1. XNNPACK
            if (session == null) {
                try {
                    val opts = OrtSession.SessionOptions()
                    opts.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    session = ortEnv?.createSession(modelFile.absolutePath, opts)
                    Log.i(TAG, "ONNX Runtime session created with XNNPACK")
                } catch (e: Throwable) {
                    Log.w(TAG, "XNNPACK acceleration unavailable for SCNet, trying CPU fallback: ${e.message}")
                }
            }

            // 2. CPU fallback
            if (session == null) {
                try {
                    val opts = OrtSession.SessionOptions()
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    opts.setIntraOpNumThreads(4)
                    session = ortEnv?.createSession(modelFile.absolutePath, opts)
                    Log.i(TAG, "ONNX Runtime session created with CPU fallback")
                } catch (e: Throwable) {
                    Log.e(TAG, "CPU fallback failed for SCNet: ${e.message}", e)
                }
            }

            if (session != null) {
                // Verify contract
                val mixSpecInfo = session.inputInfo["mix_spec"]?.info as? TensorInfo
                val stemsSpecInfo = session.outputInfo["stems_spec"]?.info as? TensorInfo

                val expectedInputShape = longArrayOf(1, 4, 2049, 476)
                val expectedOutputShape = longArrayOf(1, 16, 2049, 476)

                if (mixSpecInfo == null || stemsSpecInfo == null ||
                    !mixSpecInfo.shape.contentEquals(expectedInputShape) ||
                    !stemsSpecInfo.shape.contentEquals(expectedOutputShape)
                ) {
                    val actualIn = mixSpecInfo?.shape?.contentToString() ?: "missing"
                    val actualOut = stemsSpecInfo?.shape?.contentToString() ?: "missing"
                    Log.e(TAG, "SCNet model contract mismatch: input mix_spec $actualIn (expected [1, 4, 2049, 476]), output stems_spec $actualOut (expected [1, 16, 2049, 476])")
                    session.close()
                    isInitialized = true
                    return false
                }

                Log.i(TAG, "SCNet model contract verified successfully. Inputs: ${session.inputInfo}, Outputs: ${session.outputInfo}")
                ortSession = session
                isInitialized = true
                true
            } else {
                isInitialized = true
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SCNet StemSeparator: ${e.message}", e)
            false
        }
    }

    private fun getOrExtractModelFile(): File? {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val targetFile = File(modelsDir, MODEL_FILENAME)
        if (targetFile.exists() && targetFile.length() > 0) return targetFile

        val rootFile = File(context.filesDir, MODEL_FILENAME)
        if (rootFile.exists() && rootFile.length() > 0) return rootFile

        try {
            val assets = context.assets ?: return null
            val assetStream = try {
                assets.open(ASSET_PATH)
            } catch (_: Throwable) {
                assets.open(MODEL_FILENAME)
            }

            assetStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.exists() && targetFile.length() > 0) return targetFile
        } catch (e: Throwable) {
            Log.w(TAG, "Could not extract model asset: ${e.message}")
        }
        return null
    }

    /**
     * Infer single chunk [2][chunkSize]. Returns [4][2][chunkSize] output stems.
     * App stem order: Vocals (0), Drums (1), Bass (2), Other (3).
     *
     * The returned arrays are reused by the next call: read them before calling again.
     * Every returned sample is finite and within +-[StemDsp.MAX_ABS]. A model run whose output is mostly
     * NaN / Infinity returns null with [lastError] set, rather than silent or noisy stems.
     */
    fun inferChunk(inChunk: Array<FloatArray>, chunkSize: Int): Array<Array<FloatArray>>? = synchronized(sessionLock) {
        if (closed) return@synchronized null
        if (!isInitialized && !initializeLocked()) return@synchronized null
        val session = ortSession ?: return@synchronized null
        val env = ortEnv ?: return@synchronized null
        lastError = null

        try {
            val t0 = System.nanoTime()
            val inLen = minOf(chunkSize, SEGMENT_SAMPLES, inChunk[0].size, inChunk[1].size)
            val out = obtainStemOut(chunkSize)

            if (StemDsp.peak(inChunk[0], inLen) < SILENCE_PEAK && StemDsp.peak(inChunk[1], inLen) < SILENCE_PEAK) {
                for (s in 0 until 4) for (ch in 0..1) java.util.Arrays.fill(out[s][ch], 0f)
                return@synchronized out
            }

            // 1. STFT forward pass into the reused tensor buffer "mix_spec" [1, 4, 2049, 476]
            val mix = obtainMix()
            StemDsp.forward(radix2Fft, inChunk[0], inChunk[1], inLen, mix, reBuf, imBuf)
            mix.rewind()
            val tPre = System.nanoTime()

            val inputTensor = OnnxTensor.createTensor(env, mix, longArrayOf(1, 4, StemDsp.BINS.toLong(), N_FRAMES.toLong()))
            var result: OrtSession.Result? = null

            try {
                result = session.run(mapOf("mix_spec" to inputTensor))
                val tRun = System.nanoTime()
                val outputTensor = result.get(0) as? OnnxTensor ?: return@synchronized null
                val stemsBuf = outputTensor.floatBuffer
                stemsBuf.rewind()

                if (stemsBuf.capacity() < 16 * StemDsp.BINS * N_FRAMES) {
                    lastError = "Unexpected model output size ${stemsBuf.capacity()}"
                    Log.e(TAG, "Unexpected SCNet output size ${stemsBuf.capacity()}")
                    return@synchronized null
                }

                // Model sources: 0=DRUMS, 1=BASS, 2=OTHER, 3=VOCALS; the app wants 0=VOCALS, 1=DRUMS, 2=BASS, 3=OTHER.
                val copyLen = minOf(chunkSize, SEGMENT_SAMPLES)
                var badBins = 0
                var badSamples = 0
                for (appStem in 0 until 4) {
                    badBins += StemDsp.inverseSource(
                        radix2Fft, stemsBuf, APP_TO_MODEL_SOURCE[appStem], outL, outR, reBuf, imBuf
                    )
                    val dstL = out[appStem][0]
                    val dstR = out[appStem][1]
                    // Remove 2048 center padding
                    System.arraycopy(outL, PAD_SAMPLES, dstL, 0, copyLen)
                    System.arraycopy(outR, PAD_SAMPLES, dstR, 0, copyLen)
                    if (chunkSize > copyLen) {
                        java.util.Arrays.fill(dstL, copyLen, chunkSize, 0f)
                        java.util.Arrays.fill(dstR, copyLen, chunkSize, 0f)
                    }
                    badSamples += StemDsp.sanitize(dstL, 0, chunkSize) + StemDsp.sanitize(dstR, 0, chunkSize)
                }

                val totalBins = 4L * StemDsp.BINS * N_FRAMES
                if (badBins > totalBins * MAX_BAD_BIN_RATIO) {
                    lastError = "The separation model returned invalid audio ($badBins bad values)"
                    Log.e(TAG, "SCNet output is mostly non-finite: $badBins of $totalBins bins. Input peak " +
                        "${StemDsp.peak(inChunk[0], inLen)} / ${StemDsp.peak(inChunk[1], inLen)}")
                    return@synchronized null
                }
                if (badBins > 0 || badSamples > 0) {
                    Log.w(TAG, "SCNet output had $badBins non-finite bins and $badSamples non-finite samples; zeroed")
                }

                val tPost = System.nanoTime()
                Log.i(
                    TAG,
                    "chunk done: stft ${(tPre - t0) / 1_000_000} ms, model ${(tRun - tPre) / 1_000_000} ms, " +
                        "istft ${(tPost - tRun) / 1_000_000} ms"
                )
                out
            } finally {
                inputTensor.close()
                result?.close()
            }
        } catch (e: OutOfMemoryError) {
            lastError = "Out of memory while separating"
            Log.e(TAG, "Out of memory running SCNet inference chunk", e)
            null
        } catch (e: Exception) {
            lastError = e.message ?: "Inference failed"
            Log.e(TAG, "Error running SCNet inference chunk: ${e.message}", e)
            null
        }
    }

    suspend fun separate(
        inputPcm: FloatArray,
        progressCallback: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Array<FloatArray>? = withContext(Dispatchers.Default) {
        if (!initialize()) {
            Log.e(TAG, "Cannot separate: StemSeparator initialization failed")
            return@withContext null
        }

        processOverlapAdd(
            inputPcm = inputPcm,
            chunkSizeFrames = SEGMENT_SAMPLES,
            hopSizeFrames = HOP_FRAMES,
            progressCallback = progressCallback,
            isCancelled = isCancelled
        ) { inChunk, numFrames ->
            inferChunk(inChunk, numFrames)
        }
    }

    fun release() {
        if (closed) return
        closed = true
        synchronized(sessionLock) {
            try {
                ortSession?.close()
                ortEnv?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing StemSeparator ONNX session: ${e.message}")
            } finally {
                ortSession = null
                ortEnv = null
                isInitialized = false
                mixFloats = null
                stemOut = null
            }
        }
    }
}
