package com.beatraxus.app.fpsbooster

import kotlin.math.max
import kotlin.math.min

/** Sizes of the stored frame and of the motion-search image for a quality level (pure Kotlin, unit tested). */
object FrameSizing {
    /** Stored frame size: the source scaled down so its longest side is at most [frameEdge], even, at least 16. */
    fun frame(width: Int, height: Int, frameEdge: Int): Pair<Int, Int> {
        val s = min(1f, frameEdge.toFloat() / max(width, height))
        return max(16, (width * s).toInt() and 1.inv()) to max(16, (height * s).toInt() and 1.inv())
    }

    /** Motion-search (luma) image size: longest side at most [workEdge], even, at least 32. */
    fun work(width: Int, height: Int, workEdge: Int): Pair<Int, Int> {
        val s = min(1f, workEdge.toFloat() / max(width, height))
        return max(32, (width * s).toInt() and 1.inv()) to max(32, (height * s).toInt() and 1.inv())
    }

    /**
     * True when moving from [from] to [to] only changes the motion search: the stored frame keeps its size, so the
     * renderer can keep its frames and queue and replace just the analysis data.
     */
    fun analysisOnlyChange(width: Int, height: Int, from: QualityLevel, to: QualityLevel): Boolean =
        frame(width, height, from.frameEdge) == frame(width, height, to.frameEdge)
}
