package com.beatraxus.app.fpsbooster

/** What one presented output frame really is. Only [GENERATED] frames may be reported as interpolated. */
enum class FrameKind {
    /** Synthesised from two source frames at a phase strictly between them. */
    GENERATED,
    /** A decoded source frame shown for the first time (including a warp at phase ~0 or ~1). */
    ORIGINAL,
    /** A decoded source frame that was already shown by the previous presented frame (a repeat, not new content). */
    REPEATED
}

/**
 * Pure, JVM-testable truth about what was put on screen, so the statistics cannot be inflated:
 *  - a frame is GENERATED only when motion-compensated warping was really applied ([warp]) at a phase inside
 *    ([GENERATED_MIN], [GENERATED_MAX]) between two different source frames;
 *  - anything else shows a real source frame: ORIGINAL the first time that frame is shown, REPEATED afterwards.
 *
 * One instance per renderer; call [classify] once per presented frame, in presentation order. [reset] on seek / restart.
 */
class FrameClassifier {
    private var lastRealTsNs = Long.MIN_VALUE

    fun reset() { lastRealTsNs = Long.MIN_VALUE }

    /**
     * @param warp true when the draw call actually used the motion field (false: the shader shows the nearest real frame).
     * @param alpha phase between source frame A (0) and B (1).
     * @param aTsNs / [bTsNs] presentation timestamps of the two source frames used (equal when a single frame is shown).
     */
    fun classify(warp: Boolean, alpha: Float, aTsNs: Long, bTsNs: Long): FrameKind {
        if (warp && aTsNs != bTsNs && alpha > GENERATED_MIN && alpha < GENERATED_MAX) return FrameKind.GENERATED
        // The real frame the viewer sees: with no usable warp (or a phase at the edge) it is the nearest source frame.
        val shownTs = if (aTsNs == bTsNs || alpha < 0.5f) aTsNs else bTsNs
        val kind = if (shownTs == lastRealTsNs) FrameKind.REPEATED else FrameKind.ORIGINAL
        lastRealTsNs = shownTs
        return kind
    }

    companion object {
        /** Phase window treated as "synthesised"; outside it the picture is a real source frame (see FramePacing.OutputFrame). */
        const val GENERATED_MIN = 0.02f
        const val GENERATED_MAX = 0.98f
    }
}
