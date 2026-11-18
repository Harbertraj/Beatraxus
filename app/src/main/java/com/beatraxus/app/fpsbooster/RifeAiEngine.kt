package com.beatraxus.app.fpsbooster

import android.content.Context
import android.util.Log
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * AI-Powered Frame Interpolation Engine implementing RIFE 4.26 (Real-Time Intermediate Flow Estimation)
 * neural motion synthesis for BeatRaxus.
 *
 * RIFE 4.26 estimates intermediate optical flow fields and soft fusion masks between consecutive frames
 * A and B at arbitrary timesteps t in (0, 1), producing high-quality motion interpolation.
 *
 * The model input contract (`rife_v426.onnx`) is a single float32 tensor named `input` of shape
 * `[batch, 7, height, width]` (img0 RGB, img1 RGB, timestep plane, float 0..1, NCHW), producing
 * a single float32 output tensor named `output` of shape `[batch, 3, height, width]`.
 */
class RifeAiEngine(private val context: Context) : InterpolationBackend {

    companion object {
        private const val TAG = "RifeAiEngine"
        private const val LATENCY_WINDOW = 16
        private val MODEL_ASSET_NAMES = listOf(
            "models/rife_v4.26.onnx",
            "models/rife_v426.onnx",
            "models/rife_lite.onnx",
            "models/rife.onnx"
        )
        private val CACHE_FILE_NAMES = listOf(
            "rife_v4.26.onnx",
            "rife_v426.onnx",
            "rife_lite.onnx",
            "rife.onnx"
        )

        /**
         * Returns whether a valid RIFE ONNX model file exists in app assets or cache.
         */
        fun isModelInstalled(context: Context?): Boolean {
            if (context == null) return false
            val cacheDir = try { context.cacheDir } catch (_: Throwable) { null }
            if (cacheDir != null) {
                for (name in CACHE_FILE_NAMES) {
                    val file = File(cacheDir, name)
                    if (file.exists() && file.length() > 0) return true
                }
            }
            try {
                val assets = context.assets ?: return false
                for (assetName in MODEL_ASSET_NAMES) {
                    try {
                        assets.open(assetName).use { return true }
                    } catch (_: Throwable) {
                        // check next asset name
                    }
                }
            } catch (_: Throwable) {
                // context.assets threw (e.g. unattached ContextWrapper in tests)
            }
            return false
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Guards the native ONNX session. [infer], [initialize] and the close in [release] never overlap: closing a session
     * while `OrtSession.run` is still executing on another thread dereferences freed native memory (SIGSEGV in
     * libonnxruntime.so when the booster target was changed or the booster switched off).
     */
    private val sessionLock = Any()

    /** Set by [release]; once true the engine never creates or uses a session again. */
    @Volatile private var closed = false

    private var inputName: String = "input"

    @Volatile private var isInitialized = false
    @Volatile private var provider: InferenceProvider? = null

    /** Provider the session is running on (null until [initialize] succeeds). */
    override val activeProvider: InferenceProvider? get() = provider

    private val latencyRing = FloatArray(LATENCY_WINDOW)
    private var latencyCount = 0
    private var latencyHead = 0

    @Volatile private var latestLatencyMs = -1f
    override val lastLatencyMs: Float get() = latestLatencyMs

    var modelWidth = 640
        private set
    var modelHeight = 360
        private set

    /**
     * Initializes the session using the default provider order (XNNPACK, then CPU). NNAPI is never part of the
     * default: see [InferenceProvider.DEFAULT_ORDER].
     */
    override fun initialize(providers: List<InferenceProvider>): Boolean =
        synchronized(sessionLock) { initializeLocked(providers) }

    /**
     * Legacy entry point: [quality] sets the nominal model size; [useNnapi] puts NNAPI first in the provider order
     * (CPU providers remain as fallbacks). Defaults to no NNAPI.
     */
    fun initialize(quality: BoosterQuality, useNnapi: Boolean = false): Boolean {
        when (quality) {
            BoosterQuality.PERFORMANCE -> { modelWidth = 640; modelHeight = 360 }
            BoosterQuality.BALANCED -> { modelWidth = 960; modelHeight = 540 }
            BoosterQuality.QUALITY -> { modelWidth = 1280; modelHeight = 720 }
        }
        val order = if (useNnapi) listOf(InferenceProvider.NNAPI) + InferenceProvider.DEFAULT_ORDER
        else InferenceProvider.DEFAULT_ORDER
        return initialize(order)
    }

    private fun initializeLocked(providers: List<InferenceProvider>): Boolean {
        if (closed) return false
        if (isInitialized) return ortSession != null

        return try {
            val modelFile = getOrExtractModelFile()
            if (modelFile == null || !modelFile.exists() || modelFile.length() <= 0) {
                Log.i(TAG, "RIFE model file not present in assets/cache")
                isInitialized = true
                return false
            }
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            // Try each provider in order. A provider that cannot be configured, or whose session cannot be
            // created, is skipped; the plain CPU provider is always the last resort.
            val order = if (providers.contains(InferenceProvider.CPU)) providers else providers + InferenceProvider.CPU
            for (candidate in order) {
                val session = createSession(env, modelFile, candidate) ?: continue
                inputName = session.inputNames.firstOrNull() ?: "input"
                Log.i(TAG, "RIFE session created on ${candidate.label}. Inputs: ${session.inputInfo}, Outputs: ${session.outputInfo}")

                val channels = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape?.getOrNull(1)
                if (channels != null && channels != 7L) {
                    Log.e(TAG, "RIFE model input must have 7 channels, found $channels in input '$inputName'")
                    session.close()
                    isInitialized = true
                    return false
                }
                ortSession = session
                provider = candidate
                isInitialized = true
                return true
            }
            Log.e(TAG, "No inference provider could create a RIFE session")
            isInitialized = true
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing RIFE AI Engine: ${e.message}", e)
            false
        }
    }

    /** Creates a session for [candidate], or returns null (and logs) when that provider fails. */
    private fun createSession(env: OrtEnvironment, modelFile: File, candidate: InferenceProvider): OrtSession? {
        val options = OrtSession.SessionOptions()
        return try {
            when (candidate) {
                InferenceProvider.NNAPI -> {
                    options.addNnapi()
                    options.setIntraOpNumThreads(4)
                }
                InferenceProvider.XNNPACK -> {
                    // XNNPACK runs its own thread pool; keep the session pool at 1 to avoid oversubscribing cores.
                    options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                    options.setIntraOpNumThreads(1)
                }
                InferenceProvider.CPU -> options.setIntraOpNumThreads(4)
            }
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setInterOpNumThreads(1)
            env.createSession(modelFile.absolutePath, options)
        } catch (e: Throwable) {
            Log.w(TAG, "${candidate.label} unavailable, trying next provider: ${e.message}")
            null
        } finally {
            options.close()
        }
    }

    private fun getOrExtractModelFile(): File? {
        val cacheDir = try { context.cacheDir } catch (_: Throwable) { null }
        if (cacheDir != null) {
            for (name in CACHE_FILE_NAMES) {
                val file = File(cacheDir, name)
                if (file.exists() && file.length() > 0) return file
            }
        }

        try {
            val assets = context.assets
            if (assets != null) {
                for (assetName in MODEL_ASSET_NAMES) {
                    try {
                        assets.open(assetName).use { input ->
                            val fileName = assetName.substringAfterLast('/')
                            val cacheFile = File(cacheDir ?: context.cacheDir, fileName)
                            FileOutputStream(cacheFile).use { output ->
                                input.copyTo(output)
                            }
                            if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile
                        }
                    } catch (_: Throwable) {
                        // Try next asset
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Packed single-tensor inference entry point.
     * [packed]: direct FloatBuffer of 7*w*h floats (img0 RGB, img1 RGB, timestep plane 0..1), NCHW.
     * [out]: direct FloatBuffer receiving 3*w*h floats (interpolated RGB).
     */
    override fun infer(packed: FloatBuffer, w: Int, h: Int, out: FloatBuffer): Boolean = synchronized(sessionLock) {
        if (closed) return@synchronized false
        if (!isInitialized && !initializeLocked(InferenceProvider.DEFAULT_ORDER)) return@synchronized false
        val session = ortSession ?: return@synchronized false
        val env = ortEnv ?: return@synchronized false

        try {
            val tensorShape = longArrayOf(1, 7, h.toLong(), w.toLong())
            val tensor = OnnxTensor.createTensor(env, packed, tensorShape)
            var result: OrtSession.Result? = null
            val startNs = System.nanoTime()
            try {
                result = session.run(mapOf(inputName to tensor))
                val outputTensor = result.get(0) as? OnnxTensor ?: return@synchronized false
                val shape = outputTensor.info.shape
                if (shape.size == 4 && (shape[1] != 3L || shape[2] != h.toLong() || shape[3] != w.toLong())) {
                    Log.e(TAG, "Unexpected RIFE output shape: ${shape.contentToString()}, expected [1, 3, $h, $w]")
                    return@synchronized false
                }

                val outBuf = outputTensor.floatBuffer
                out.rewind()
                out.put(outBuf)
                out.rewind()
                recordLatency((System.nanoTime() - startNs) / 1e6f)
                true
            } finally {
                tensor.close()
                result?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RIFE float buffer inference error: ${e.message}", e)
            false
        }
    }

    /** Called under [sessionLock]: keeps the last [LATENCY_WINDOW] inference times and publishes their median. */
    private fun recordLatency(ms: Float) {
        latencyRing[latencyHead] = ms
        latencyHead = (latencyHead + 1) % LATENCY_WINDOW
        if (latencyCount < LATENCY_WINDOW) latencyCount++
        val sorted = latencyRing.copyOf(latencyCount).also { it.sort() }
        latestLatencyMs = sorted[latencyCount / 2]
    }

    /**
     * Benchmarks current model inference latency (ms) for input size [w] x [h].
     * Warms up 2 runs, measures [iterations] runs, and returns the median latency.
     */
    override fun benchmark(w: Int, h: Int, iterations: Int): Float {
        val packed = ByteBuffer.allocateDirect(7 * w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(3 * w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        RifePacking.fillPlane(packed, 6 * w * h, w, h, 0.5f)

        // Warmup runs
        infer(packed, w, h, out)
        infer(packed, w, h, out)

        val times = ArrayList<Float>(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val ok = infer(packed, w, h, out)
            val durMs = (System.nanoTime() - start) / 1e6f
            if (ok) times.add(durMs)
        }

        if (times.isEmpty()) return -1f
        times.sort()
        val medianMs = times[times.size / 2]
        Log.i(TAG, "Benchmark ${w}x${h} median latency: $medianMs ms over ${times.size} runs")
        return medianMs
    }

    /**
     * Closes the session once any inference in flight has finished (it waits on [sessionLock] on the engine's own
     * thread, so the caller is never blocked), and makes every later [infer] / [initialize] a no-op.
     */
    override fun release() {
        if (closed) return
        closed = true
        try {
            executor.execute {
                synchronized(sessionLock) {
                    try {
                        ortSession?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing RIFE AI session: ${e.message}")
                    } finally {
                        ortSession = null
                        provider = null
                        isInitialized = false
                    }
                }
            }
        } catch (_: Exception) {
            // executor already shut down
        } finally {
            executor.shutdown()
        }
    }
}
