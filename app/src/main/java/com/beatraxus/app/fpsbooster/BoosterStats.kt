package com.beatraxus.app.fpsbooster

/** Counters for one measuring window; filled by the renderer, reduced by [StatsAccumulator]. */
data class WindowStats(
    val durationNs: Long,
    val sourceFrames: Int,
    val presented: Int,
    val generated: Int,
    val dropped: Int,
    val avgEstimateMs: Float,
    val maxEstimateMs: Float,
    val avgDrawMs: Float
) {
    private val seconds get() = durationNs / 1e9f
    val sourceFps: Float get() = if (seconds > 0f) sourceFrames / seconds else 0f
    /** Frames actually handed to the display per second (what the user can see at most). */
    val renderedFps: Float get() = if (seconds > 0f) presented / seconds else 0f
    /** Synthesised frames per second. */
    val generatedFps: Float get() = if (seconds > 0f) generated / seconds else 0f
}

/** Live numbers shown in the settings sheet. Display refresh rate is reported separately and is NOT a frame rate we generate. */
data class BoosterStats(
    val running: Boolean = false,
    val engine: BoosterEngine = BoosterEngine.AUTO,
    val sourceFps: Float = 0f,
    val targetFps: Int = 0,
    val renderedFps: Float = 0f,
    val generatedFps: Float = 0f,
    val droppedFrames: Long = 0,
    val estimateLatencyMs: Float = 0f,
    val drawMs: Float = 0f,
    val addedDelayMs: Float = 0f,
    val workEdge: Int = 0,
    val qualityLabel: String = "",
    val fallbackReason: String? = null
)

/** Collects events for the current window and emits a [WindowStats] each time [windowNs] has elapsed. */
class StatsAccumulator(private val windowNs: Long = 1_000_000_000L) {
    private var start = 0L
    private var source = 0
    private var presented = 0
    private var generated = 0
    private var dropped = 0
    private var estSum = 0f
    private var estMax = 0f
    private var estN = 0
    private var drawSum = 0f
    private var drawN = 0

    fun onSourceFrame() { source++ }
    fun onPresented(generatedFrame: Boolean) { presented++; if (generatedFrame) generated++ }
    fun onDropped(n: Int = 1) { dropped += n }
    fun onEstimateMs(ms: Float) { estSum += ms; estN++; if (ms > estMax) estMax = ms }
    fun onDrawMs(ms: Float) { drawSum += ms; drawN++ }

    /** Returns the finished window (and starts a new one) once [windowNs] has passed since the last one. */
    fun poll(nowNs: Long): WindowStats? {
        if (start == 0L) { start = nowNs; return null }
        val elapsed = nowNs - start
        if (elapsed < windowNs) return null
        val w = WindowStats(
            durationNs = elapsed,
            sourceFrames = source,
            presented = presented,
            generated = generated,
            dropped = dropped,
            avgEstimateMs = if (estN > 0) estSum / estN else 0f,
            maxEstimateMs = estMax,
            avgDrawMs = if (drawN > 0) drawSum / drawN else 0f
        )
        reset(nowNs)
        return w
    }

    fun reset(nowNs: Long = 0L) {
        start = nowNs; source = 0; presented = 0; generated = 0; dropped = 0
        estSum = 0f; estMax = 0f; estN = 0; drawSum = 0f; drawN = 0
    }
}
