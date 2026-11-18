package com.beatraxus.app.fpsbooster

import java.nio.FloatBuffer

/** Execution providers an [InterpolationBackend] can run on. Order of [DEFAULT_ORDER] is the fallback order. */
enum class InferenceProvider(val label: String) {
    /** ONNX Runtime XNNPACK execution provider (optimized CPU kernels). */
    XNNPACK("XNNPACK CPU"),

    /** ONNX Runtime default CPU provider. */
    CPU("CPU"),

    /** Android NNAPI. Opt-in only: it must beat CPU in a benchmark on the device before being preferred. */
    NNAPI("NNAPI");

    companion object {
        /**
         * Default provider order. NNAPI is deliberately not here: the RIFE graph uses GridSample and ConvTranspose,
         * which NNAPI cannot run, so the graph is split and usually slower than plain CPU. Benchmark before enabling it.
         */
        val DEFAULT_ORDER: List<InferenceProvider> = listOf(XNNPACK, CPU)
    }
}

/**
 * Inference runtime abstraction so the interpolation model is not tied to one provider or library.
 *
 * Tensor contract (RIFE): input `[1, 7, H, W]` float32 NCHW, planes = img0 RGB (0..1), img1 RGB (0..1), timestep
 * plane (0..1); output `[1, 3, H, W]` float32 RGB (0..1).
 */
interface InterpolationBackend {
    /** Provider the session is actually running on, or null before a successful [initialize]. */
    val activeProvider: InferenceProvider?

    /** Median latency of the most recent [infer] calls in milliseconds, or -1 when nothing has run yet. */
    val lastLatencyMs: Float

    /**
     * Creates the session, trying [providers] in order and falling back to the next one when a provider cannot be
     * configured or the session cannot be created with it. Returns true when a session is ready.
     */
    fun initialize(providers: List<InferenceProvider> = InferenceProvider.DEFAULT_ORDER): Boolean

    /** Runs one inference. Returns false on any failure; callers must then present the original frame. */
    fun infer(packed: FloatBuffer, w: Int, h: Int, out: FloatBuffer): Boolean

    /** Measures median latency (ms) at [w] x [h]; -1 when inference fails. */
    fun benchmark(w: Int, h: Int, iterations: Int = 6): Float

    /** Releases native resources. Safe to call more than once and from any thread. */
    fun release()
}
