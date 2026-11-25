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
    val avgDrawMs: Float,
    /** Source frames whose motion estimate was not started because the GPU was still busy with the previous one. */
    val skippedEstimates: Int = 0,
    /** Vsyncs between two presented frames (0 = unknown) and the panel rate it was computed for: see [CadenceGovernor]. */
    val cadenceVsyncs: Int = 0,
    val displayHz: Float = 0f,
    /** Presented frames that were a decoded source frame shown for the first time (see [FrameKind.ORIGINAL]). */
    val original: Int = 0,
    /** Presented frames that repeated the source frame already on screen (see [FrameKind.REPEATED]). */
    val repeated: Int = 0,
    /** Dominant [MotionComplexity] (ordinal) of the pairs estimated in this window; 0 = unknown / not measured. */
    val motionComplexity: Int = 0,
    /** Presented frames that were shown as a cheap real frame because the deadline was already missed (see [FrameDeadlinePolicy]). */
    val shed: Int = 0,
    /** Vsyncs by which due output frames were late, summed over the window (a frame presented k vsyncs late adds k). */
    val missedVsyncs: Int = 0
) {
    private val seconds get() = durationNs / 1e9f
    val sourceFps: Float get() = if (seconds > 0f) sourceFrames / seconds else 0f
    /** Frames actually handed to the display per second (what the user can see at most). */
    val renderedFps: Float get() = if (seconds > 0f) presented / seconds else 0f
    /** Synthesised frames per second. */
    val generatedFps: Float get() = if (seconds > 0f) generated / seconds else 0f
    /** Real source frames shown for the first time, per second. */
    val originalFps: Float get() = if (seconds > 0f) original / seconds else 0f
    /** Repeated source frames per second: presented, but no new picture. */
    val repeatedFps: Float get() = if (seconds > 0f) repeated / seconds else 0f
    /** Share (0..100) of presented frames that were really synthesised. */
    val interpolationPct: Float get() = if (presented > 0) 100f * generated / presented else 0f
    /** Output rate the cadence grid allows: panel rate / vsyncs per frame (0 = unknown). */
    val cadenceFps: Float get() = if (cadenceVsyncs > 0 && displayHz > 0f) displayHz / cadenceVsyncs else 0f
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
    val fallbackReason: String? = null,
    /** Vsyncs each output frame is held (1 = every vsync) and the panel rate; 0 / 0 when not measured yet. */
    val cadenceVsyncs: Int = 0,
    val displayHz: Float = 0f,
    /** False when the presenter had to use a rate other than the target to keep the cadence even. */
    val cadenceExact: Boolean = true,
    /** Real source frames shown for the first time per second, and repeats of an already shown frame per second. */
    val originalFps: Float = 0f,
    val repeatedFps: Float = 0f,
    /** Share (0..100) of presented frames that were synthesised: generated / presented, never inflated by repeats. */
    val interpolationPct: Float = 0f,
    /** Dominant motion complexity of the last window (label of [MotionComplexity]); empty when not measured. */
    val motionComplexity: String = "",
    /** Output frames that were due but not presented, summed over the session (never negative, never estimated from the target). */
    val missedVsyncs: Long = 0,
    /** Frames shown as a cheap real frame to catch up after a missed deadline, summed over the session. */
    val deadlineShedFrames: Long = 0,
    /** Thermal state the quality controller acted on (OS status combined with the forecast): None / Light / Moderate / Severe. */
    val thermalState: String = "",
    /** Look-ahead thermal headroom (1.0 = throttling expected); negative when the device does not report it. */
    val thermalHeadroom: Float = -1f,
    /** What the user selected (manual) or the ceiling Auto may reach; [targetFps] is what is actually running now. */
    val requestedTargetFps: Int = 0,
    /** True in Auto mode: the target is chosen by the device's measured headroom. */
    val autoTarget: Boolean = false,
    /** Why the running target differs from the requested one (null when it does not), or what Auto last did. */
    val targetNote: String? = null
) {
    /** Output rate the cadence grid allows: panel rate / vsyncs per frame (0 = unknown). */
    val cadenceFps: Float get() = if (cadenceVsyncs > 0 && displayHz > 0f) displayHz / cadenceVsyncs else 0f
}

/** Collects events for the current window and emits a [WindowStats] each time [windowNs] has elapsed. */
class StatsAccumulator(private val windowNs: Long = 1_000_000_000L) {
    private var start = 0L
    private var source = 0
    private var presented = 0
    private var generated = 0
    private var original = 0
    private var repeated = 0
    private var dropped = 0
    private var estSum = 0f
    private var estMax = 0f
    private var estN = 0
    private var drawSum = 0f
    private var drawN = 0
    private var skipped = 0
    private var cadenceVsyncs = 0
    private var displayHz = 0f
    private val complexityCount = IntArray(MotionComplexity.entries.size)
    private var shed = 0
    private var missedVsyncs = 0

    fun onSourceFrame() { source++ }
    /** Latest cadence of the presenter (see [CadenceGovernor]); reported with every window. */
    fun onCadence(vsyncsPerFrame: Int, hz: Float) { cadenceVsyncs = vsyncsPerFrame; displayHz = hz }
    /** Legacy two-way form: true = generated, false = a real (original) frame. Prefer the [FrameKind] overload. */
    fun onPresented(generatedFrame: Boolean) { onPresented(if (generatedFrame) FrameKind.GENERATED else FrameKind.ORIGINAL) }
    /** One presented output frame, classified by [FrameClassifier]: generated, original or repeated. */
    fun onPresented(kind: FrameKind) {
        presented++
        when (kind) {
            FrameKind.GENERATED -> generated++
            FrameKind.ORIGINAL -> original++
            FrameKind.REPEATED -> repeated++
        }
    }
    /** Complexity the motion estimate of one pair was planned with; the window reports the most frequent known one. */
    fun onComplexity(c: MotionComplexity) { complexityCount[c.ordinal]++ }
    /** A frame that the deadline policy showed as a cheap real frame (it is also counted by [onPresented]). */
    fun onShed() { shed++ }
    /** A due frame that was presented [n] vsyncs after it was due. Frames deliberately not re-presented are NOT misses. */
    fun onMissedVsyncs(n: Int) { missedVsyncs += n }
    fun onDropped(n: Int = 1) { dropped += n }
    fun onEstimateMs(ms: Float) { estSum += ms; estN++; if (ms > estMax) estMax = ms }
    fun onDrawMs(ms: Float) { drawSum += ms; drawN++ }
    /** A pair arrived while the GPU was still busy with the previous estimate: the pair is shown as a real frame. */
    fun onEstimateSkipped() { skipped++ }

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
            avgDrawMs = if (drawN > 0) drawSum / drawN else 0f,
            skippedEstimates = skipped,
            cadenceVsyncs = cadenceVsyncs,
            displayHz = displayHz,
            original = original,
            repeated = repeated,
            motionComplexity = dominantComplexity(),
            shed = shed,
            missedVsyncs = missedVsyncs
        )
        reset(nowNs)
        return w
    }

    /** Most frequent complexity of the window, ignoring UNKNOWN and CUT (ties go to the harder class); UNKNOWN when none was measured. */
    private fun dominantComplexity(): Int {
        var best = MotionComplexity.UNKNOWN.ordinal
        var bestN = 0
        for (i in 1 until MotionComplexity.CUT.ordinal) if (complexityCount[i] > 0 && complexityCount[i] >= bestN) { best = i; bestN = complexityCount[i] }
        return best
    }

    fun reset(nowNs: Long = 0L) {
        complexityCount.fill(0)
        shed = 0
        missedVsyncs = 0
        start = nowNs; source = 0; presented = 0; generated = 0; original = 0; repeated = 0; dropped = 0
        estSum = 0f; estMax = 0f; estN = 0; drawSum = 0f; drawN = 0; skipped = 0
    }
}

/**
 * Smoothed latency (ns) of the motion estimate: submit -> GPU finished, one sample per estimated pair. Rises
 * quickly (a slow estimate must be covered by the hold at once) and decays slowly (one fast pair is no reason to
 * shrink the hold). Pure; the renderer feeds it from the fence poll and reads it for [HoldController.update].
 */
class EstimateLatency {
    var smoothedNs: Long = 0L
        private set
    private var seeded = false

    fun reset() { smoothedNs = 0L; seeded = false }

    fun onSample(ns: Long) {
        val v = ns.coerceAtLeast(0L)
        if (!seeded) { smoothedNs = v; seeded = true; return }
        smoothedNs += if (v > smoothedNs) (v - smoothedNs) / RISE_DIV else (v - smoothedNs) / FALL_DIV
    }

    companion object {
        const val RISE_DIV = 2L
        const val FALL_DIV = 8L
    }
}
