package com.beatraxus.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Looper
import android.util.Log
import com.beatraxus.app.model.StemQuality
import com.beatraxus.app.model.StemSpec
import com.beatraxus.app.model.StemSpecs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Max" stem engine: HTDemucs 6-source (htdemucs_6s) through ONNX Runtime. Splits a 44.1 kHz stereo waveform into
 * Vocals, Drums, Bass, Guitar, Piano and Other. The model is a waveform model (the STFT is inside the graph), so this
 * class only copies one 7.8 s chunk in and six stereo sources out; the chunking and cross-fade are
 * [StemOverlapAdd], shared with the Fast engine.
 *
 * The model file is `htdemucs_6s_fp16weights.onnx` (MIT, about 136 MB), installed by [StemModelDownloader] into
 * `filesDir/models/`. Tensor names and shapes are read from the session at [initialize] and checked with
 * [StemModelContract]; nothing about them is assumed. A mismatch fails cleanly with [lastError].
 *
 * RAM: the model card of the export reports about 1.1 GB of runtime memory (fp16 weights are widened at load). That
 * number is the card's, not measured here, so [minFreeRamBytes] adds headroom and the manager refuses to start below
 * it. The session is freed in [release] as soon as the song is done.
 */
class HtDemucsSeparator(private val context: Context) : StemEngine {

    companion object {
        private const val TAG = "HtDemucsSeparator"
        const val MODEL_FILENAME = "htdemucs_6s_fp16weights.onnx"

        const val SEGMENT_SAMPLES = StemModelContract.HTDEMUCS_SEGMENT_SAMPLES   // 7.8 s @ 44.1 kHz

        /**
         * 12.5 % overlap between neighbouring chunks (about 1 s), cross-faded with a trapezoid window. It was 25 %,
         * which is 14 % more model runs for the same song; the seams are a little less smoothed in exchange.
         */
        const val HOP_SAMPLES = SEGMENT_SAMPLES * 7 / 8

        /** Heuristic: the card's 1.1 GB plus headroom for the app and the separation buffers (about 90 MB). */
        const val MIN_FREE_RAM_BYTES = 1_600_000_000L

        private const val THREADS = 4

        /** Applied to every session: keep the resident memory low (the Max model sits next to a running player). */
        private fun leanOptions(opts: OrtSession.SessionOptions) {
            // No arena / planned memory block: intermediate tensors are freed after each run instead of being kept.
            try { opts.setCPUArenaAllocator(false) } catch (e: Throwable) { Log.w(TAG, "arena option: ${e.message}") }
            try { opts.setMemoryPatternOptimization(false) } catch (e: Throwable) { Log.w(TAG, "mem pattern option: ${e.message}") }
            // Initializers (the weights) are allocated once, directly, instead of through the arena plus a copy.
            try { opts.addConfigEntry("session.use_device_allocator_for_initializers", "1") } catch (e: Throwable) { Log.w(TAG, "initializer allocator option: ${e.message}") }
            // Idle worker threads sleep instead of spinning, so the audio thread keeps its CPU time.
            try { opts.addConfigEntry("session.intra_op.allow_spinning", "0") } catch (e: Throwable) { Log.w(TAG, "spin option: ${e.message}") }
        }

        /** A chunk this quiet is digital silence: skip the model. */
        private const val SILENCE_PEAK = 1e-6f

        val SPECS: List<StemSpec> = StemSpecs.MAX

        /** App stem order (Vocals, Drums, Bass, Guitar, Piano, Other) -> model source: 3, 0, 1, 4, 5, 2. */
        private val APP_TO_MODEL_SOURCE = StemSpecs.appToModelSources(SPECS, StemSpecs.HTDEMUCS_6S_MODEL_ORDER)

        fun modelFile(context: Context): File = File(File(context.filesDir, "models"), MODEL_FILENAME)

        fun isModelInstalled(context: Context?): Boolean {
            if (context == null) return false
            val f = modelFile(context)
            return f.isFile && f.length() > 0
        }
    }

    override val id: String = StemQuality.MAX.engineId
    override val specs: List<StemSpec> = SPECS
    override val segmentSamples: Int = SEGMENT_SAMPLES
    override val hopSamples: Int = HOP_SAMPLES
    override val window: FloatArray by lazy { StemOverlapAdd.trapezoidWindow(SEGMENT_SAMPLES, SEGMENT_SAMPLES - HOP_SAMPLES) }
    override val minFreeRamBytes: Long = MIN_FREE_RAM_BYTES

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private var outputName: String? = null
    private var outputIndex = 0

    private val sessionLock = Any()
    @Volatile private var closed = false
    @Volatile private var isInitialized = false
    @Volatile private var lastErrorText: String? = null

    override val lastError: String? get() = lastErrorText

    // Buffers created on first use and reused for every chunk.
    private var mixFloats: FloatBuffer? = null
    private var stemOut: Array<Array<FloatArray>>? = null

    private fun onMainThread() = Looper.getMainLooper()?.thread === Thread.currentThread()

    private fun obtainMix(): FloatBuffer {
        mixFloats?.let { return it }
        val fb = ByteBuffer.allocateDirect(2 * SEGMENT_SAMPLES * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        mixFloats = fb
        return fb
    }

    private fun obtainStemOut(chunkSize: Int): Array<Array<FloatArray>> {
        val cur = stemOut
        if (cur != null && cur[0][0].size == chunkSize) return cur
        val fresh = Array(SPECS.size) { Array(2) { FloatArray(chunkSize) } }
        stemOut = fresh
        return fresh
    }

    override fun initialize(): Boolean = synchronized(sessionLock) { initializeLocked() }

    private fun initializeLocked(): Boolean {
        if (closed) return false
        if (isInitialized) return ortSession != null
        if (onMainThread()) {
            lastErrorText = "Separation must not run on the main thread"
            return false
        }
        lastErrorText = null

        return try {
            val modelFile = modelFile(context)
            if (!modelFile.isFile || modelFile.length() <= 0) {
                Log.w(TAG, "HTDemucs model file not present: ${modelFile.absolutePath}")
                lastErrorText = "The Max separation model is not installed"
                isInitialized = true
                return false
            }

            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            var session: OrtSession? = null
            val t0 = System.nanoTime()

            // CPU provider only. XNNPACK pre-packs the weights into a second copy, which on a 136 MB fp16 model that
            // is widened at load was the biggest avoidable part of the memory peak that got the app killed.
            val level = StemLoadProfile.level(context)
            if (StemLoadProfile.isMaxBlocked(level)) {
                lastErrorText = "Max separation was stopped by Android on this phone more than once (not enough memory). Use Fast."
                isInitialized = true
                return false
            }
            try {
                val opts = OrtSession.SessionOptions()
                leanOptions(opts)
                opts.setOptimizationLevel(
                    if (StemLoadProfile.noGraphOptimisation(level)) OrtSession.SessionOptions.OptLevel.NO_OPT
                    else OrtSession.SessionOptions.OptLevel.BASIC_OPT
                )
                opts.setIntraOpNumThreads(StemLoadProfile.threads(level))
                opts.setInterOpNumThreads(1)
                Log.i(TAG, "Loading HTDemucs: load level $level, ${StemLoadProfile.threads(level)} threads, CPU provider")
                session = env.createSession(modelFile.absolutePath, opts)
                Log.i(TAG, "ONNX Runtime session created (CPU)")
            } catch (e: Throwable) {
                Log.e(TAG, "HTDemucs session failed: ${e.message}", e)
                lastErrorText = "The Max separation model could not be loaded (${e.message ?: "unknown error"})"
            }
            if (session == null) {
                isInitialized = true
                return false
            }
            Log.i(TAG, "HTDemucs session ready in ${(System.nanoTime() - t0) / 1_000_000} ms")

            // Tensor names and shapes come from the session, never from assumptions.
            val inputs = session.inputInfo
            val outputs = session.outputInfo
            Log.i(TAG, "HTDemucs model I/O: inputs ${describe(inputs)}, outputs ${describe(outputs)}")

            if (inputs.size != 1) {
                return failContract(session, "model has ${inputs.size} inputs (${inputs.keys}), this build feeds exactly one")
            }
            val inName = inputs.keys.first()
            val inShape = (inputs[inName]?.info as? TensorInfo)?.shape

            // Pick the output whose shape is the 6-source one.
            var chosen: String? = null
            var firstProblem: String? = null
            for ((name, node) in outputs) {
                val problem = StemModelContract.verifyHtDemucs6s(inShape, (node.info as? TensorInfo)?.shape)
                if (problem == null) { chosen = name; break }
                if (firstProblem == null) firstProblem = problem
            }
            if (chosen == null) {
                return failContract(session, firstProblem ?: "model has no outputs")
            }

            inputName = inName
            outputName = chosen
            outputIndex = outputs.keys.indexOf(chosen)
            ortSession = session
            isInitialized = true
            Log.i(TAG, "HTDemucs model contract verified: input '$inName' ${inShape?.contentToString()}, output '$chosen'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HtDemucsSeparator: ${e.message}", e)
            lastErrorText = e.message ?: "The Max separation model could not be started"
            false
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory loading the HTDemucs model", e)
            lastErrorText = "Out of memory while loading the Max separation model. Close other apps or use Fast."
            closeSessionLocked()
            false
        }
    }

    private fun describe(info: Map<String, ai.onnxruntime.NodeInfo>): String =
        info.entries.joinToString { (n, node) -> "$n=${(node.info as? TensorInfo)?.shape?.contentToString() ?: "?"}" }

    private fun failContract(session: OrtSession, problem: String): Boolean {
        Log.e(TAG, "HTDemucs model contract mismatch: $problem")
        lastErrorText = "The Max model file is not the htdemucs_6s model this app needs: $problem"
        try { session.close() } catch (_: Throwable) {}
        isInitialized = true
        return false
    }

    /**
     * Separates one 7.8 s chunk. [inChunk] is [2][chunkSize]; returns [6][2][chunkSize] in [specs] order.
     * The returned arrays are reused by the next call. Every sample is finite and within +-[StemDsp.MAX_ABS];
     * if more than 5 % of the model output was NaN / Infinity the chunk fails with [lastError] set.
     */
    override fun inferChunk(inChunk: Array<FloatArray>, chunkSize: Int): Array<Array<FloatArray>>? = synchronized(sessionLock) {
        if (closed) return@synchronized null
        if (onMainThread()) {
            lastErrorText = "Separation must not run on the main thread"
            return@synchronized null
        }
        if (!isInitialized && !initializeLocked()) return@synchronized null
        val session = ortSession ?: return@synchronized null
        val env = ortEnv ?: return@synchronized null
        val inName = inputName ?: return@synchronized null
        if (chunkSize != SEGMENT_SAMPLES) {
            lastErrorText = "The Max model needs chunks of $SEGMENT_SAMPLES frames, got $chunkSize"
            return@synchronized null
        }
        lastErrorText = null

        try {
            val t0 = System.nanoTime()
            val out = obtainStemOut(chunkSize)
            val badIn = StemDsp.sanitize(inChunk[0], 0, chunkSize, StemOverlapAdd.INPUT_LIMIT) +
                StemDsp.sanitize(inChunk[1], 0, chunkSize, StemOverlapAdd.INPUT_LIMIT)
            if (badIn > 0) Log.w(TAG, "HTDemucs input had $badIn non-finite or huge samples; fixed")

            if (StemDsp.peak(inChunk[0], chunkSize) < SILENCE_PEAK && StemDsp.peak(inChunk[1], chunkSize) < SILENCE_PEAK) {
                for (s in out) for (ch in s) java.util.Arrays.fill(ch, 0f)
                Log.i(TAG, "chunk skipped: silent")
                return@synchronized out
            }

            val mix = obtainMix()
            mix.clear()
            mix.put(inChunk[0], 0, chunkSize)
            mix.put(inChunk[1], 0, chunkSize)
            mix.rewind()
            val tIn = System.nanoTime()

            val inputTensor = OnnxTensor.createTensor(env, mix, longArrayOf(1, 2, chunkSize.toLong()))
            var result: OrtSession.Result? = null
            try {
                result = session.run(mapOf(inName to inputTensor))
                val tRun = System.nanoTime()
                val outputTensor = result.get(outputIndex) as? OnnxTensor
                    ?: run {
                        lastErrorText = "The Max model returned no audio tensor"
                        return@synchronized null
                    }
                val stems = outputTensor.floatBuffer
                val need = SPECS.size * 2 * chunkSize
                if (stems.capacity() < need) {
                    lastErrorText = "Unexpected model output size ${stems.capacity()} (need $need)"
                    Log.e(TAG, "Unexpected HTDemucs output size ${stems.capacity()}, need $need")
                    return@synchronized null
                }

                var bad = 0L
                for (appStem in SPECS.indices) {
                    val src = APP_TO_MODEL_SOURCE[appStem]
                    for (ch in 0..1) {
                        stems.position((src * 2 + ch) * chunkSize)
                        stems.get(out[appStem][ch], 0, chunkSize)
                        bad += StemDsp.sanitize(out[appStem][ch], 0, chunkSize)
                    }
                }
                val total = need.toLong()
                if (StemModelContract.tooManyBad(bad, total)) {
                    lastErrorText = "The separation model returned invalid audio ($bad bad values)"
                    Log.e(TAG, "HTDemucs output is mostly non-finite: $bad of $total values")
                    return@synchronized null
                }
                if (bad > 0) Log.w(TAG, "HTDemucs output had $bad non-finite values; zeroed")

                val tOut = System.nanoTime()
                Log.i(
                    TAG,
                    "chunk done: copy-in ${(tIn - t0) / 1_000_000} ms, model ${(tRun - tIn) / 1_000_000} ms, " +
                        "copy-out ${(tOut - tRun) / 1_000_000} ms"
                )
                out
            } finally {
                inputTensor.close()
                result?.close()
            }
        } catch (e: OutOfMemoryError) {
            lastErrorText = "Out of memory while separating. Close other apps or use Fast."
            Log.e(TAG, "Out of memory running HTDemucs inference chunk", e)
            null
        } catch (e: Exception) {
            lastErrorText = e.message ?: "Inference failed"
            Log.e(TAG, "Error running HTDemucs inference chunk: ${e.message}", e)
            null
        }
    }

    private fun closeSessionLocked() {
        try {
            ortSession?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing HTDemucs session: ${e.message}")
        }
        try {
            ortEnv?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing ONNX environment: ${e.message}")
        }
        ortSession = null
        ortEnv = null
        isInitialized = false
        mixFloats = null
        stemOut = null
    }

    /** Frees the ONNX session and the chunk buffers (the manager calls this as soon as the song is done). */
    override fun release() {
        if (closed) return
        closed = true
        synchronized(sessionLock) { closeSessionLocked() }
    }
}
