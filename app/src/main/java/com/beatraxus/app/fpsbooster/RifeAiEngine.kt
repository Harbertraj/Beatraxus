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
class RifeAiEngine(private val context: Context) {

    companion object {
        private const val TAG = "RifeAiEngine"
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

    private var inputName: String = "input"

    @Volatile private var isInitialized = false
    @Volatile private var isNnapiSupported = false

    var modelWidth = 640
        private set
    var modelHeight = 360
        private set

    /**
     * Initializes the ONNX Runtime session and loads the RIFE 4.26 model asset.
     */
    fun initialize(quality: BoosterQuality = BoosterQuality.PERFORMANCE, useNnapi: Boolean = true): Boolean {
        if (isInitialized) return ortSession != null

        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            try {
                if (useNnapi) {
                    try {
                        sessionOptions.addNnapi()
                        isNnapiSupported = true
                        Log.i(TAG, "ONNX Runtime NNAPI acceleration enabled for RIFE AI")
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI acceleration unavailable, falling back to CPU/XNNPACK: ${e.message}")
                        isNnapiSupported = false
                    }
                } else {
                    isNnapiSupported = false
                    try {
                        sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                        Log.i(TAG, "ONNX Runtime XNNPACK acceleration enabled for RIFE AI")
                    } catch (e: Throwable) {
                        Log.w(TAG, "XNNPACK acceleration unavailable: ${e.message}")
                    }
                }
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                sessionOptions.setInterOpNumThreads(2)
                sessionOptions.setIntraOpNumThreads(4)

                when (quality) {
                    BoosterQuality.PERFORMANCE -> { modelWidth = 640; modelHeight = 360 }
                    BoosterQuality.BALANCED -> { modelWidth = 960; modelHeight = 540 }
                    BoosterQuality.QUALITY -> { modelWidth = 1280; modelHeight = 720 }
                }

                val modelFile = getOrExtractModelFile()
                if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                    val session = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
                    if (session != null) {
                        inputName = session.inputNames.firstOrNull() ?: "input"
                        Log.i(TAG, "RIFE session created. Inputs: ${session.inputInfo}, Outputs: ${session.outputInfo}")

                        val inputNodeInfo = session.inputInfo[inputName]?.info as? TensorInfo
                        val channels = inputNodeInfo?.shape?.getOrNull(1)
                        if (channels != null && channels != 7L) {
                            Log.e(TAG, "RIFE model input must have 7 channels, found $channels in input '$inputName'")
                            session.close()
                            ortSession = null
                            isInitialized = true
                            return false
                        }

                        ortSession = session
                        isInitialized = true
                        Log.i(TAG, "RIFE 4.26 ONNX model initialized successfully (${modelWidth}x${modelHeight})")
                        true
                    } else {
                        isInitialized = true
                        false
                    }
                } else {
                    Log.i(TAG, "RIFE model file not present in assets/cache")
                    isInitialized = true
                    false
                }
            } finally {
                sessionOptions.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing RIFE AI Engine: ${e.message}", e)
            false
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
    fun infer(packed: FloatBuffer, w: Int, h: Int, out: FloatBuffer): Boolean {
        if (!isInitialized && !initialize()) return false
        val session = ortSession ?: return false
        val env = ortEnv ?: return false

        return try {
            val tensorShape = longArrayOf(1, 7, h.toLong(), w.toLong())
            val tensor = OnnxTensor.createTensor(env, packed, tensorShape)
            var result: OrtSession.Result? = null
            try {
                result = session.run(mapOf(inputName to tensor))
                val outputTensor = result.get(0) as? OnnxTensor ?: return false
                val shape = outputTensor.info.shape
                if (shape.size == 4 && (shape[1] != 3L || shape[2] != h.toLong() || shape[3] != w.toLong())) {
                    Log.e(TAG, "Unexpected RIFE output shape: ${shape.contentToString()}, expected [1, 3, $h, $w]")
                    return false
                }

                val outBuf = outputTensor.floatBuffer
                out.rewind()
                out.put(outBuf)
                out.rewind()
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

    /**
     * Benchmarks current model inference latency (ms) for input size [w] x [h].
     * Warms up 2 runs, measures [iterations] runs, and returns the median latency.
     */
    fun benchmark(w: Int, h: Int, iterations: Int = 6): Float {
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

    fun release() {
        executor.execute {
            try {
                ortSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing RIFE AI session: ${e.message}")
            } finally {
                ortSession = null
                isInitialized = false
            }
        }
    }
}
