package com.beatraxus.app.fpsbooster

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shape of the motion-search pyramid, shared by [OpticalFlowRenderer], [BoosterBenchmarkRunner] and the desktop
 * harness (tools/fpsbooster_harness/new_engine.py: keep `level_sizes`, `global_samples` and the constants there in
 * sync). Pure Kotlin so it is unit tested on the JVM.
 *
 * Depth rule: halve while that brings the longest edge closer to [COARSEST_EDGE] (in the log sense), i.e. while the
 * edge is above [COARSEST_EDGE] * sqrt(2). Every preset therefore ends at a similar coarsest size instead of
 * whatever its work size happens to divide into (640 -> 40, 960 -> 30, 1280 -> 40; the old fixed "stop below 64"
 * rule gave 40 / 60 / 40, so Balanced alone searched on a coarser, shorter pyramid and lost fast pans).
 * Going lower than ~30 px aliases the picture away; stopping at 60 reaches only half as far.
 */
object FlowPyramid {
    /** Preferred longest edge (px) of the coarsest level. */
    const val COARSEST_EDGE = 40
    const val MAX_LEVELS = 6
    /** Integer search radius (px) at the coarsest level. The grid there is tiny, so a wide range is cheap. */
    const val COARSEST_RANGE = 10
    /** Block pitch of the search (matches flow_search.frag). */
    const val PITCH = 4

    /** Dominant-motion lattice (global_motion.frag examines at most this many blocks per axis). */
    const val GLOBAL_SAMPLES_X = 12
    const val GLOBAL_SAMPLES_Y = 8
    /** Blocks matched worse than this (mean luma error, 0..1) do not vote for the dominant motion. */
    const val GLOBAL_MAX_COST = 0.08f

    private val STOP_EDGE: Double = COARSEST_EDGE * sqrt(2.0)

    /** Level sizes from the work size (level 0) down to the coarsest level. */
    class Levels(val width: IntArray, val height: IntArray) {
        val count: Int get() = width.size
        val coarsest: Int get() = width.size - 1
        val gridW: IntArray = IntArray(width.size) { (width[it] + PITCH - 1) / PITCH }
        val gridH: IntArray = IntArray(height.size) { (height[it] + PITCH - 1) / PITCH }

        /** This level's size over the coarsest level's: scale of the dominant motion, which is found there. */
        fun globalScale(level: Int): Float = width[level].toFloat() / width[coarsest]

        /** Only the finest and the coarsest level's match cost is read by later passes (consistency, cut, dominant motion). */
        fun needsExactCost(level: Int): Boolean = level == 0 || level == coarsest
    }

    fun levels(workW: Int, workH: Int): Levels {
        val w = ArrayList<Int>().apply { add(workW) }
        val h = ArrayList<Int>().apply { add(workH) }
        while (max(w.last(), h.last()) > STOP_EDGE && w.size < MAX_LEVELS) {
            w.add(max(8, (w.last() + 1) / 2))
            h.add(max(8, (h.last() + 1) / 2))
        }
        return Levels(w.toIntArray(), h.toIntArray())
    }

    /** Lattice for global_motion.frag on a field of [gridW] x [gridH] blocks. */
    fun globalSamples(gridW: Int, gridH: Int): Pair<Int, Int> =
        max(1, min(GLOBAL_SAMPLES_X, gridW)) to max(1, min(GLOBAL_SAMPLES_Y, gridH))
}
