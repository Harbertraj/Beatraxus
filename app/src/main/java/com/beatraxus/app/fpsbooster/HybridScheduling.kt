package com.beatraxus.app.fpsbooster

/**
 * Pure scheduling and confidence maths for [HybridRenderer].
 * No Android dependencies; fully unit-tested.
 */
object HybridScheduling {

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
