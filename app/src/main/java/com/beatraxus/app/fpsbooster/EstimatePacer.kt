package com.beatraxus.app.fpsbooster

/**
 * How many motion-estimate steps the renderer may submit on this vsync (pure Kotlin, unit tested on the JVM).
 *
 * The estimate of a frame pair is a chain of small GPU passes that is spread over the vsyncs between two source frames
 * (see `OpticalFlowRenderer.beginEstimate`). The passes run on the same GPU queue as the presenting draw, so a heavy
 * slice submitted right before a vsync can make the next frame late. This keeps the analysis polite towards the
 * presenter: while presenting is late or slow it submits fewer steps (the estimate takes longer, which only costs
 * latency and is covered by the hold and the A/V compensation), and it gives the full slice back after a run of clean
 * vsyncs. It never stops completely: at most one vsync in a row is skipped, so an estimate always finishes.
 */
class EstimatePacer {
    private var factor = 1.0
    private var clean = 0
    private var skippedLast = false

    /** Share (0.25..1) of the planned slice that is submitted right now. */
    val share: Double get() = factor

    /**
     * Reports one presented frame. [late]: it was drawn a whole vsync or more after it was due. [drawMs]: CPU time of
     * the draw and the swap; [vsyncMs]: the vsync period. Either being high means the presenter is under pressure.
     */
    fun onPresented(late: Boolean, drawMs: Float, vsyncMs: Float) {
        val slow = vsyncMs > 0f && drawMs > vsyncMs * SLOW_DRAW
        if (late || slow) {
            factor = (factor * 0.5).coerceAtLeast(MIN_SHARE)
            clean = 0
        } else if (++clean >= RECOVER_AFTER) {
            factor = (factor + 0.25).coerceAtMost(1.0)
            clean = 0
        }
    }

    /** Steps to submit now, from the [planned] slice; 0 only when the previous vsync already submitted nothing... never twice in a row. */
    fun slice(planned: Int): Int {
        if (planned <= 0) return 0
        val n = Math.floor(planned * factor + 1e-9).toInt()
        if (n >= 1) { skippedLast = false; return n }
        if (skippedLast) { skippedLast = false; return 1 }
        skippedLast = true
        return 0
    }

    fun reset() { factor = 1.0; clean = 0; skippedLast = false }

    companion object {
        const val MIN_SHARE = 0.25
        const val RECOVER_AFTER = 8
        /** A draw + swap that takes more than this share of a vsync leaves the GPU queue too full to add work. */
        const val SLOW_DRAW = 0.6f
    }
}
