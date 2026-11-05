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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 FFT implementation for size N=4096 with precomputed twiddle tables.
 */
class Radix2FFT(val n: Int = 4096) {
    private val logN = 31 - Integer.numberOfLeadingZeros(n)
    private val bitRev = IntArray(n) { i ->
        var rev = 0
        var temp = i
        for (j in 0 until logN) {
            rev = (rev shl 1) or (temp and 1)
            temp = temp ushr 1
        }
        rev
    }

    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = 2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    fun fft(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = false)
    }

    fun ifft(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = true)
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = bitRev[i]
            if (i < j) {
                val tempR = re[i]
                re[i] = re[j]
                re[j] = tempR
                val tempI = im[i]
                im[i] = im[j]
                im[j] = tempI
            }
        }

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            for (i in 0 until n step len) {
                for (j in 0 until halfLen) {
                    val tableIdx = j * step
                    val cosW = cosTable[tableIdx]
                    val sinW = if (inverse) sinTable[tableIdx] else -sinTable[tableIdx]

                    val reRight = re[i + j + halfLen]
                    val imRight = im[i + j + halfLen]

                    val tRe = reRight * cosW - imRight * sinW
                    val tIm = reRight * sinW + imRight * cosW

                    val uRe = re[i + j]
                    val uIm = im[i + j]

                    re[i + j] = uRe + tRe
                    im[i + j] = uIm + tIm
                    re[i + j + halfLen] = uRe - tRe
                    im[i + j + halfLen] = uIm - tIm
                }
            }
            len = len shl 1
        }

        if (inverse) {
            // Incorporate scale 1/64.0f (undoes 1/sqrt(4096) STFT scale)
            val scale = 1.0f / 64.0f
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }
}

/**
 * AI-powered Stem Separation Engine using SCNet int8 ONNX model.
 * Separates stereo audio waveform @ 44.1 kHz into 4 stems: Vocals, Drums, Bass, Other.
 * Uses ONNX Runtime with NNAPI / XNNPACK / CPU fallback, guarded by a session lock.
 */
class StemSeparator(private val context: Context) {

    companion object {
        private const val TAG = "StemSeparator"
        private const val MODEL_FILENAME = "scnet.onnx"
        private const val ASSET_PATH = "models/$MODEL_FILENAME"

        const val SAMPLE_RATE = 44100
        const val N_FFT = 4096
        const val HOP_SIZE = 1024
        const val N_FRAMES = 476
        const val SEGMENT_SAMPLES = 485100 // 11 s @ 44.1 kHz
        const val PAD_SAMPLES = 2048
        const val PADDED_SAMPLES = 490496 // 2048 + 485100 + 2048 extended to fit 476 frames

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
            chunkSizeFrames: Int = 485100,
            hopSizeFrames: Int = 121275,
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

    private val radix2Fft = Radix2FFT(N_FFT)

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
     * Reflected sample lookup for STFT padding.
     */
    private fun getReflectSample(idx: Int, n: Int, pcm: FloatArray): Float {
        if (n == 0) return 0f
        var p = idx
        if (p < 0) p = -p
        if (p >= n) p = 2 * n - 2 - p
        p = p.coerceIn(0, n - 1)
        return pcm[p]
    }

    /**
     * Infer single chunk [2][chunkSize]. Returns [4][2][chunkSize] output stems.
     * App stem order: Vocals (0), Drums (1), Bass (2), Other (3).
     */
    fun inferChunk(inChunk: Array<FloatArray>, chunkSize: Int): Array<Array<FloatArray>>? = synchronized(sessionLock) {
        if (closed) return@synchronized null
        if (!isInitialized && !initializeLocked()) return@synchronized null
        val session = ortSession ?: return@synchronized null
        val env = ortEnv ?: return@synchronized null

        try {
            val inLen = minOf(chunkSize, SEGMENT_SAMPLES)

            // 1. STFT forward pass: prepare float buffer for tensor "mix_spec" [1, 4, 2049, 476]
            val totalMixFloats = 1 * 4 * 2049 * N_FRAMES
            val mixBuf = ByteBuffer.allocateDirect(totalMixFloats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            val reBuf = FloatArray(N_FFT)
            val imBuf = FloatArray(N_FFT)
            val scale = 1.0f / 64.0f // 1 / sqrt(4096)

            for (ch in 0..1) {
                val pcm = inChunk[ch]
                val chPcmLen = minOf(pcm.size, inLen)

                for (t in 0 until N_FRAMES) {
                    val frameStart = t * HOP_SIZE - PAD_SAMPLES

                    for (n in 0 until N_FFT) {
                        val sampleIdx = frameStart + n
                        reBuf[n] = getReflectSample(sampleIdx, chPcmLen, pcm)
                        imBuf[n] = 0.0f
                    }

                    radix2Fft.fft(reBuf, imBuf)

                    val baseReCh = if (ch == 0) 0 else 2
                    val baseImCh = if (ch == 0) 1 else 3

                    for (f in 0..2048) {
                        val reIdx = (baseReCh * 2049 + f) * N_FRAMES + t
                        val imIdx = (baseImCh * 2049 + f) * N_FRAMES + t
                        mixBuf.put(reIdx, reBuf[f] * scale)
                        mixBuf.put(imIdx, imBuf[f] * scale)
                    }
                }
            }

            mixBuf.rewind()

            val tensorShape = longArrayOf(1, 4, 2049, N_FRAMES.toLong())
            val inputTensor = OnnxTensor.createTensor(env, mixBuf, tensorShape)
            var result: OrtSession.Result? = null

            try {
                result = session.run(mapOf("mix_spec" to inputTensor))
                val outputTensor = result.get(0) as? OnnxTensor ?: return@synchronized null
                val stemsBuf = outputTensor.floatBuffer
                stemsBuf.rewind()

                if (stemsBuf.capacity() < 16 * 2049 * N_FRAMES) {
                    Log.e(TAG, "Unexpected SCNet output size ${stemsBuf.capacity()}")
                    return@synchronized null
                }

                val chunkStems = Array(4) { Array(2) { FloatArray(chunkSize) } }
                val outAccum = FloatArray(PADDED_SAMPLES)
                val winSqAccum = FloatArray(PADDED_SAMPLES)

                // Model sources: 0=DRUMS, 1=BASS, 2=OTHER, 3=VOCALS
                // Remap to app stem cache order: 0=VOCALS, 1=DRUMS, 2=BASS, 3=OTHER
                for (appStem in 0 until 4) {
                    val modelSource = when (appStem) {
                        0 -> 3 // VOCALS
                        1 -> 0 // DRUMS
                        2 -> 1 // BASS
                        3 -> 2 // OTHER
                        else -> 0
                    }

                    for (ch in 0..1) {
                        val reCh = modelSource * 4 + (if (ch == 0) 0 else 2)
                        val imCh = modelSource * 4 + (if (ch == 0) 1 else 3)

                        outAccum.fill(0f)
                        winSqAccum.fill(0f)

                        for (t in 0 until N_FRAMES) {
                            for (f in 0..2048) {
                                val reVal = stemsBuf.get((reCh * 2049 + f) * N_FRAMES + t)
                                val imVal = stemsBuf.get((imCh * 2049 + f) * N_FRAMES + t)
                                reBuf[f] = reVal
                                imBuf[f] = imVal
                            }

                            for (f in 1 until 2048) {
                                reBuf[N_FFT - f] = reBuf[f]
                                imBuf[N_FFT - f] = -imBuf[f]
                            }
                            imBuf[0] = 0f
                            imBuf[2048] = 0f

                            radix2Fft.ifft(reBuf, imBuf)

                            val offset = t * HOP_SIZE
                            for (n in 0 until N_FFT) {
                                if (offset + n < PADDED_SAMPLES) {
                                    outAccum[offset + n] += reBuf[n]
                                    winSqAccum[offset + n] += 1.0f
                                }
                            }
                        }

                        // Divide by window square envelope
                        for (i in 0 until PADDED_SAMPLES) {
                            val w = winSqAccum[i]
                            if (w > 1e-6f) outAccum[i] /= w
                        }

                        // Remove 2048 center padding
                        val dst = chunkStems[appStem][ch]
                        val copyLen = minOf(chunkSize, SEGMENT_SAMPLES)
                        for (i in 0 until copyLen) {
                            dst[i] = outAccum[PAD_SAMPLES + i]
                        }
                    }
                }

                chunkStems
            } finally {
                inputTensor.close()
                result?.close()
            }
        } catch (e: Exception) {
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
            chunkSizeFrames = 485100,
            hopSizeFrames = 121275,
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
            }
        }
    }
}
