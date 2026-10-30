package com.beatraxus.app.fpsbooster

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Pure scheduling and confidence maths for [HybridRenderer].
 * No Android dependencies; fully unit-tested.
 */
object HybridScheduling {

    /** Mean luma difference (0..1) between two model-size frames above which a pair is treated as a scene cut. */
    const val CUT_MEAN_DIFF = 0.12f

    /**
     * RIFE-first cascade: may the AI engine deliver a middle frame for every pair? Only when its measured time fits in
     * 90 % of one source interval. Not measured yet (0): try, the renderer's deadline protects playback.
     */
    fun canInjectMid(avgInferenceNs: Long, sourceIntervalNs: Long): Boolean {
        if (avgInferenceNs <= 0L || sourceIntervalNs <= 0L) return true
        return avgInferenceNs * 10 <= sourceIntervalNs * 9
    }

    /** Timestamp of the AI middle frame of real frames at [aTsNs] and [bTsNs]. */
    fun midTimestampNs(aTsNs: Long, bTsNs: Long): Long = aTsNs + (bTsNs - aTsNs) / 2

    /**
     * Mean absolute luma difference of two RGBA8 frames of [w] x [h], sampled on a [grid] x [grid] lattice
     * (absolute reads, the buffers' positions are untouched). A cut makes RIFE invent a picture: skip it there.
     */
    fun meanLumaDiff(a: ByteBuffer, b: ByteBuffer, w: Int, h: Int, grid: Int = 24): Float {
        if (w <= 0 || h <= 0 || a.capacity() < w * h * 4 || b.capacity() < w * h * 4) return 0f
        var sum = 0f
        var n = 0
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val x = ((gx + 0.5f) * w / grid).toInt().coerceIn(0, w - 1)
            val y = ((gy + 0.5f) * h / grid).toInt().coerceIn(0, h - 1)
            val i = (y * w + x) * 4
            sum += abs(lumaAt(a, i) - lumaAt(b, i))
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    private fun lumaAt(buf: ByteBuffer, i: Int): Float =
        (0.299f * (buf.get(i).toInt() and 0xFF) + 0.587f * (buf.get(i + 1).toInt() and 0xFF) +
            0.114f * (buf.get(i + 2).toInt() and 0xFF)) / 255f

    /**
     * Calculates the RIFE execution stride based on average inference duration and source frame interval.
     * Stride 1: run RIFE on every pair.
     * Stride 2: run RIFE on every 2nd pair (inference time > 1x source interval).
     * Stride 3: run RIFE on every 3rd pair (inference time > 2x source interval).
     */
    fun computeRifeStride(avgInferenceNs: Long, sourceIntervalNs: Long): Int {
        if (avgInferenceNs <= 0L || sourceIntervalNs <= 0L) return 1
        if (avgInferenceNs <= sourceIntervalNs) return 1
        if (avgInferenceNs <= 2 * sourceIntervalNs) return 2
        return 3
    }

    /**
     * Determines if a RIFE synthesis result for [synthForTs] is valid for target frame pair [bTsNs].
     */
    fun isRifeValid(
        synthForTs: Long,
        bTsNs: Long,
        generation: Int,
        activeGeneration: Int,
        isCut: Boolean = false
    ): Boolean {
        if (isCut) return false
        if (generation != activeGeneration) return false
        if (synthForTs == 0L || bTsNs == 0L) return false
        return synthForTs == bTsNs
    }

    /**
     * Per-pixel hallucination guard confidence:
     * Returns 1.0 (pure RIFE) if [rifeRgb] is within [minVal, maxVal] of real frames A and B.
     * Returns < 1.0 (smooth fallback to optical flow) as [rifeRgb] strays outside [minVal, maxVal].
     */
    fun hallucinationGuardConfidence(
        rifeRgb: FloatArray,
        aRgb: FloatArray,
        bRgb: FloatArray,
        tolerance: Float = 0.02f,
        maxErrRange: Float = 0.08f
    ): Float {
        var maxErr = 0f
        for (i in 0..2) {
            val minC = minOf(aRgb[i], bRgb[i]) - tolerance
            val maxC = maxOf(aRgb[i], bRgb[i]) + tolerance
            val errBelow = (minC - rifeRgb[i]).coerceAtLeast(0f)
            val errAbove = (rifeRgb[i] - maxC).coerceAtLeast(0f)
            maxErr = maxOf(maxErr, maxOf(errBelow, errAbove))
        }
        if (maxErr <= 0f) return 1f
        if (maxErr >= maxErrRange) return 0f
        return 1f - (maxErr / maxErrRange)
    }
}
