package com.beatraxus.app.engine

import kotlin.math.abs

/**
 * Unit-testable frame-math and drift calculation helpers.
 *
 * HARD RULE: NO DOWNMIX. All functions preserve the exact channel count and layout.
 */
object AudioFrameUtils {

    /**
     * Aligns a sample count to complete frames for the given channel count.
     * Prevents partial frame reads that cause channel rotation/crackling.
     */
    fun calculateAlignedSampleCount(sampleCount: Int, channels: Int): Int {
        if (channels <= 0 || sampleCount <= 0) return 0
        return (sampleCount / channels) * channels
    }

    /**
     * Calculates maximum samples to read in a batch that aligns exactly with frame boundaries.
     */
    fun calculateBatchSamplesToRead(maxBatchSamples: Int, channels: Int): Int {
        if (channels <= 0 || maxBatchSamples <= 0) return 0
        val maxFrames = maxBatchSamples / channels
        return maxFrames.coerceAtLeast(1) * channels
    }

    /**
     * Computes drift in milliseconds between video master clock (ExoPlayer) and audible engine audio position.
     */
    fun calculateDriftMs(exoPosMs: Long, engineAudioPosMs: Long, latencyMs: Long): Long {
        val trueAudibleAudioPos = (engineAudioPosMs - latencyMs).coerceAtLeast(0L)
        return exoPosMs - trueAudibleAudioPos
    }

    /**
     * Determines whether drift exceeds resync threshold (default 80 ms).
     */
    fun shouldResync(driftMs: Long, thresholdMs: Long = 80L): Boolean {
        return abs(driftMs) > thresholdMs
    }
}
