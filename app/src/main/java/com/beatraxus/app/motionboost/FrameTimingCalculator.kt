package com.beatraxus.app.motionboost

import kotlin.math.roundToLong

/**
 * Pure Kotlin class for computing output presentation timestamps.
 * Handles fractional ratios correctly (e.g. 24 -> 60 fps = 2.5x).
 */
class FrameTimingCalculator {

    data class InterpolatedFrame(
        val timestampUs: Long,
        val alpha: Float // 0.0 to 1.0, position between frameA and frameB
    )

    /**
     * Calculates required output frames between two source frames.
     */
    fun calculateOutputFrames(
        frameATimestampUs: Long,
        frameBTimestampUs: Long,
        targetFps: Int
    ): List<InterpolatedFrame> {
        val durationUs = frameBTimestampUs - frameATimestampUs
        if (durationUs <= 0) return emptyList()

        val targetFrameDurationUs = 1_000_000.0 / targetFps
        val result = mutableListOf<InterpolatedFrame>()

        // Calculate all output timestamps that fall between [frameA, frameB)
        // Note: In a real streaming implementation, this would be part of a stateful scheduler.
        var currentOutputTimestampUs = frameATimestampUs.toDouble()

        while (currentOutputTimestampUs < frameBTimestampUs) {
            val alpha = ((currentOutputTimestampUs - frameATimestampUs) / durationUs).toFloat()
            result.add(InterpolatedFrame(
                timestampUs = currentOutputTimestampUs.roundToLong(),
                alpha = alpha.coerceIn(0f, 1f)
            ))
            currentOutputTimestampUs += targetFrameDurationUs
        }

        return result
    }
}
