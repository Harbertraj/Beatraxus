package com.beatraxus.app.fpsbooster

import kotlin.math.hypot

/** Motion-scene classification. The ordinal is a severity ordering, not a request for more GPU search work. */
enum class MotionComplexity(val label: String) {
    /** Nothing measured yet (or right after a cut): the quality level's own settings apply unchanged. */
    UNKNOWN("Unknown"),
    STATIC("Static"),
    SLOW("Slow"),
    FAST("Fast"),
    /** Many things moving differently, occlusion or badly matching blocks: needs the strongest refinement. */
    COMPLEX("Complex"),
    /** The pair is a scene cut: the shader shows the nearest real frame, so no search effort is spent on it. */
    CUT("Scene cut");

    companion object {
        fun fromOrdinal(i: Int): MotionComplexity = entries.getOrElse(i) { UNKNOWN }
    }
}

/**
 * Reduces the two 1x1 texels the GPU already writes for every frame pair (the dominant-motion pass and the cut-detect
 * pass; see global_motion.frag / cut_detect.frag) to a [MotionComplexity]. Pure Kotlin, unit tested on the JVM.
 *
 * Thresholds are starting values chosen from the numbers documented in cut_detect.frag (real motion matches with a
 * block cost of 0.003-0.010; a cut or heavy occlusion matches far worse). They are meant to be tuned on real clips with
 * tools/fpsbooster_harness; every one is a named constant.
 *
 * Hysteresis: a more severe classification is adopted at once, an easier one only after [RELAX_AFTER] consecutive
 * easier pairs, so a single quiet pair in an action scene does not relax the processing policy.
 */
class MotionComplexityClassifier {
    var current: MotionComplexity = MotionComplexity.UNKNOWN
        private set
    private var easierStreak = 0

    fun reset() { current = MotionComplexity.UNKNOWN; easierStreak = 0 }

    /**
     * @param dominantX / [dominantY] dominant motion between the two frames in work pixels (global_motion.frag rg)
     * @param support share (0..1) of examined blocks that agree with the dominant motion (global_motion.frag b)
     * @param meanCost mean best-match cost of the coarse field (cut_detect.frag r)
     * @param poorShare share (0..1) of coarse blocks that match poorly (cut_detect.frag g)
     * @param cutVerdict cut_detect.frag b: 0 none, 1 hard cut, 2 sustained break (whip pan)
     * @param workEdge longest side of the work image in pixels (converts the vector to a share of the picture)
     * @return the complexity to plan the NEXT estimate with
     */
    fun onPair(
        dominantX: Float, dominantY: Float, support: Float,
        meanCost: Float, poorShare: Float, cutVerdict: Float, workEdge: Int
    ): MotionComplexity {
        if (!dominantX.isFinite() || !dominantY.isFinite() || !support.isFinite() || !meanCost.isFinite() ||
            !poorShare.isFinite() || !cutVerdict.isFinite() || workEdge <= 0
        ) {
            return current   // a corrupt read never changes the plan
        }
        if (cutVerdict in 0.5f..1.5f) {
            // A hard cut: this pair is skipped by the shader, and the next one starts a new scene.
            current = MotionComplexity.UNKNOWN; easierStreak = 0
            return MotionComplexity.CUT
        }
        val speed = hypot(dominantX, dominantY) / workEdge   // share of the picture per source-frame interval
        val raw = when {
            cutVerdict > 1.5f -> MotionComplexity.FAST       // whip pan: cross-fade, search wide
            poorShare > COMPLEX_POOR_SHARE || meanCost > COMPLEX_MEAN_COST ||
                (support < COMPLEX_MAX_SUPPORT && speed > SLOW_SPEED) -> MotionComplexity.COMPLEX
            speed > FAST_SPEED -> MotionComplexity.FAST
            speed < STATIC_SPEED && support >= STATIC_MIN_SUPPORT && meanCost < STATIC_MAX_COST -> MotionComplexity.STATIC
            else -> MotionComplexity.SLOW
        }
        if (current == MotionComplexity.UNKNOWN || raw.ordinal >= current.ordinal) {
            current = raw; easierStreak = 0
        } else if (++easierStreak >= RELAX_AFTER) {
            current = MotionComplexity.entries[current.ordinal - 1]   // relax one class at a time
            easierStreak = 0
        }
        return current
    }

    companion object {
        const val RELAX_AFTER = 3
        /** Dominant motion (share of the picture per source frame) below which the picture is treated as not moving. */
        const val STATIC_SPEED = 0.002f
        const val STATIC_MIN_SUPPORT = 0.85f
        const val STATIC_MAX_COST = 0.004f
        const val SLOW_SPEED = 0.004f
        /** About 19 px of a 1280 px work image per source frame. */
        const val FAST_SPEED = 0.015f
        const val COMPLEX_POOR_SHARE = 0.15f
        const val COMPLEX_MEAN_COST = 0.02f
        const val COMPLEX_MAX_SUPPORT = 0.35f
    }
}

/** Motion-search settings for one frame pair. */
data class SearchPlan(val searchRange: Int, val subpixel: Boolean)

/**
 * Turns a [MotionComplexity] into the search settings of the next estimate. Applied per pair, so it costs no texture
 * reallocation (unlike a change of the quality level).
 *
 * The range is the radius at the COARSEST pyramid level (about 40 px wide, see [FlowPyramid]), so one unit there is
 * work / 40 work pixels: even the reduced range of a static scene still follows a moving subject across ~15 % of the
 * picture per source frame. A plan never goes below that, and never exceeds [MAX_RANGE].
 */
object MotionPlan {
    const val MIN_RANGE = 6
    const val MAX_RANGE = 16
    const val FAST_EXTRA_RANGE = 4
    /** Complex scenes are ambiguous; keep their coarse search bounded instead of expanding it to the FAST range. */
    const val COMPLEX_MAX_RANGE = 8

    fun plan(c: MotionComplexity, base: QualityLevel, gpuHeadroom: Boolean): SearchPlan = when (c) {
        // Static: the shader already short-circuits unchanged pixels; the search only needs to confirm that.
        MotionComplexity.STATIC -> SearchPlan(minOf(base.searchRange, MIN_RANGE), false)
        MotionComplexity.FAST -> SearchPlan((base.searchRange + FAST_EXTRA_RANGE).coerceAtMost(MAX_RANGE), base.subpixel)
        // Ambiguous motion: bound exhaustive search and never add refinement beyond the preset. Under GPU pressure,
        // omit even the preset's subpixel pass; this keeps the expensive path from growing when matches are uncertain.
        MotionComplexity.COMPLEX -> SearchPlan(
            minOf(base.searchRange, COMPLEX_MAX_RANGE),
            base.subpixel && gpuHeadroom
        )
        // Slow / unknown / cut: exactly what the quality level says (CUT pairs are not interpolated at all).
        MotionComplexity.SLOW, MotionComplexity.UNKNOWN, MotionComplexity.CUT -> SearchPlan(base.searchRange, base.subpixel)
    }
}

/** Per-output-frame time budget of a target rate. The motion estimate is budgeted per SOURCE frame; the draw is per OUTPUT frame. */
object TargetBudget {
    /** Targets above this are "high rate": their per-frame budget is too small for the 60 FPS tolerances. */
    const val HIGH_RATE_FPS = 60.5f
    /** Share of an output period the draw + swap may take before the GPU queue is considered too full. Matches [EstimatePacer.SLOW_DRAW]. */
    const val DRAW_SHARE = 0.6f

    fun isHighRate(expectedFps: Float): Boolean = expectedFps > HIGH_RATE_FPS

    fun periodMs(expectedFps: Float): Float = if (expectedFps > 1f) 1000f / expectedFps else 0f

    /** Draw + swap time (ms) above which a high-rate target is overloaded; 0 for rates that keep the original policy. */
    fun drawBudgetMs(expectedFps: Float): Float = if (isHighRate(expectedFps)) periodMs(expectedFps) * DRAW_SHARE else 0f
}

/**
 * Decides, per presented frame, whether the picture pass runs in its cheap "lite" form (no edge-aware vector vote, no
 * bicubic resample, no dominant-motion fill-in, earlier fade to the plain blend). Pure Kotlin, unit tested on the JVM.
 *
 * Why: static and slow scenes are cheap to draw because the shader skips unchanged pixels, so the full-quality path was
 * only ever paid for a few pixels. In complex motion (blur, dark flat areas, rack focus, many movers) nearly EVERY pixel
 * takes the expensive path at the panel's full output rate, the draw gets late, [EstimatePacer] then starves the motion
 * estimate, the estimate misses its source interval and the pair is shown as real frames: the rate collapses to the source
 * rate right when the scene is hardest. Those are also the pixels whose vectors are least reliable, so the expensive
 * refinement bought nothing there but glitches.
 *
 * Lite is entered at once (a late frame is the worse outcome) and left only after [EXIT_AFTER] consecutive frames that
 * are neither complex nor under pressure, so it cannot flicker between two looks from one frame to the next.
 */
class DrawDetailPolicy {
    var lite: Boolean = false
        private set
    private var calm = 0

    fun reset() { lite = false; calm = 0 }

    /**
     * @param complexity classification of the pair being drawn
     * @param pacerShare [EstimatePacer.share]: below 1.0 means the presenter was recently late or slow
     */
    fun update(complexity: MotionComplexity, pacerShare: Double): Boolean {
        val wants = complexity == MotionComplexity.COMPLEX || pacerShare < 1.0
        if (wants) { lite = true; calm = 0 }
        else if (lite && ++calm >= EXIT_AFTER) { lite = false; calm = 0 }
        return lite
    }

    companion object {
        /** Frames (about half a second at 90 FPS) of calm before the full-quality picture pass is used again. */
        const val EXIT_AFTER = 45
    }
}
