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
import kotlin.math.cos

/**
 * AI-powered Stem Separation Engine using SCNet ONNX model.
 * Separates stereo audio waveform @ 44.1 kHz into 4 stems: Vocals, Drums, Bass, Other.
 * Uses ONNX Runtime with NNAPI / XNNPACK / CPU fallback, guarded by a session lock.
 */
class StemSeparator(private val context: Context) {

    companion object {
        private const val TAG = "StemSeparator"
        private const val MODEL_FILENAME = "scnet.onnx"
        private const val ASSET_PATH = "models/$MODEL_FILENAME"

        /** Sample rate required by SCNet model (44.1 kHz). */
        const val SAMPLE_RATE = 44100

        /**
         * Returns whether the SCNet ONNX model file exists in app filesDir or assets.
         */
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

        /**
         * Process an input stereo PCM array with chunk overlap-add (COLA) using Hann window crossfade.
         * [inputPcm]: Interleaved stereo float array (length = 2 * N).
         * [chunkSizeFrames]: Number of audio frames per chunk (default 441000 = 10 sec @ 44.1 kHz).
         * [hopSizeFrames]: Hop size in frames for 50% overlap (default 220500).
         * [processChunk]: Lambda processing one chunk [2, chunkSizeFrames] into 4 stems [4, 2, chunkSizeFrames].
         * Returns 4 stems array of FloatArrays (each length = 2 * N, interleaved stereo float).
         */
        fun processOverlapAdd(
            inputPcm: FloatArray,
            chunkSizeFrames: Int = 441000,
            hopSizeFrames: Int = 220500,
            progressCallback: ((Float) -> Unit)? = null,
            isCancelled: (() -> Boolean)? = null,
            processChunk: (inChunk: Array<FloatArray>, numFrames: Int) -> Array<Array<FloatArray>>?
        ): Array<FloatArray>? {
            val totalFrames = inputPcm.size / 2
            if (totalFrames == 0) return null

            val numStems = 4
            // Accumulator for 4 stems: [stem][channel][frame]
            val outAccum = Array(numStems) { Array(2) { FloatArray(totalFrames) } }
            val weightAccum = FloatArray(totalFrames)

            // Pre-calculate Hann window of length chunkSizeFrames
            val hann = FloatArray(chunkSizeFrames) { i ->
                0.5f * (1.0f - cos(2.0 * PI * i / chunkSizeFrames).toFloat())
            }

            var framePos = 0
            while (framePos < totalFrames) {
                if (isCancelled?.invoke() == true) return null

                val framesInChunk = minOf(chunkSizeFrames, totalFrames - framePos)

                // Build chunk input [2][chunkSizeFrames] (de-interleave + zero pad)
                val inChunk = Array(2) { FloatArray(chunkSizeFrames) }
                for (i in 0 until framesInChunk) {
                    val idx = (framePos + i) * 2
                    inChunk[0][i] = inputPcm[idx]
                    inChunk[1][i] = inputPcm[idx + 1]
                }

                // Run inference on chunk
                val chunkStems = processChunk(inChunk, chunkSizeFrames) ?: return null

                // Overlap-add with Hann window
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

            // Normalize by accumulated weight to preserve constant signals and exact energy
            val resultStems = Array(numStems) { FloatArray(totalFrames * 2) }
            for (f in 0 until totalFrames) {
                val w = if (weightAccum[f] > 1e-6f) weightAccum[f] else 1.0f
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

    /**
     * Guards native ONNX session creation, inference, and release to prevent concurrent access or crash during close.
     */
    private val sessionLock = Any()

    @Volatile private var closed = false
    @Volatile private var isInitialized = false

    private var inputName: String = "input"
    private var outputName: String = "output"
    private var isInputRank3 = true // true if [1, 2, T], false if 4D spectrogram [1, 2, F, T]

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

            // 1. Try NNAPI acceleration
            try {
                val opts = OrtSession.SessionOptions()
                opts.addNnapi()
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                session = ortEnv?.createSession(modelFile.absolutePath, opts)
                Log.i(TAG, "ONNX Runtime session created with NNAPI")
            } catch (e: Throwable) {
                Log.w(TAG, "NNAPI acceleration unavailable for SCNet, trying XNNPACK: ${e.message}")
            }

            // 2. Try XNNPACK acceleration
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

            // 3. Fallback to CPU
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
                inputName = session.inputNames.firstOrNull() ?: "input"
                outputName = session.outputNames.firstOrNull() ?: "output"

                val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
                val shape = inputInfo?.shape
                isInputRank3 = shape == null || shape.size == 3

                Log.i(TAG, "SCNet model initialized. Inputs: ${session.inputInfo}, Outputs: ${session.outputInfo}")
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
     */
    fun inferChunk(inChunk: Array<FloatArray>, chunkSize: Int): Array<Array<FloatArray>>? = synchronized(sessionLock) {
        if (closed) return@synchronized null
        if (!isInitialized && !initializeLocked()) return@synchronized null
        val session = ortSession ?: return@synchronized null
        val env = ortEnv ?: return@synchronized null

        try {
            // Flatten inChunk into float buffer: Left channel first, then Right channel (shape [1, 2, chunkSize])
            val inBuf = ByteBuffer.allocateDirect(2 * chunkSize * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            inBuf.put(inChunk[0], 0, chunkSize)
            inBuf.put(inChunk[1], 0, chunkSize)
            inBuf.rewind()

            val tensorShape = longArrayOf(1, 2, chunkSize.toLong())
            val tensor = OnnxTensor.createTensor(env, inBuf, tensorShape)
            var result: OrtSession.Result? = null

            try {
                result = session.run(mapOf(inputName to tensor))
                val outputTensor = result.get(0) as? OnnxTensor ?: return@synchronized null
                val outFloatBuf = outputTensor.floatBuffer

                val outStems = Array(4) { Array(2) { FloatArray(chunkSize) } }
                outFloatBuf.rewind()

                // Output tensor is [1, 4, 2, chunkSize]: 4 stems, 2 channels each, chunkSize floats each
                for (s in 0 until 4) {
                    for (ch in 0..1) {
                        outFloatBuf.get(outStems[s][ch], 0, chunkSize)
                    }
                }
                outStems
            } finally {
                tensor.close()
                result?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running SCNet inference chunk: ${e.message}", e)
            null
        }
    }

    /**
     * Separates complete song audio into 4 stems on background thread.
     * [inputPcm]: Interleaved stereo float array @ 44.1 kHz.
     */
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
            chunkSizeFrames = 441000,
            hopSizeFrames = 220500,
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
