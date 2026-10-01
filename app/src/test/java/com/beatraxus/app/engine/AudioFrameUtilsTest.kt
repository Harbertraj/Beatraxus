package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameUtilsTest {

    @Test
    fun testCalculateAlignedSampleCount() {
        // 5.1 audio (6 channels): 1024 raw samples -> 170 full frames * 6 = 1020 samples
        assertEquals(1020, AudioFrameUtils.calculateAlignedSampleCount(1024, 6))

        // Stereo (2 channels): 1024 raw samples -> 1024 samples
        assertEquals(1024, AudioFrameUtils.calculateAlignedSampleCount(1024, 2))

        // 7.1 audio (8 channels): 1024 raw samples -> 128 full frames * 8 = 1024 samples
        assertEquals(1024, AudioFrameUtils.calculateAlignedSampleCount(1024, 8))

        // Edge cases
        assertEquals(0, AudioFrameUtils.calculateAlignedSampleCount(3, 6))
        assertEquals(0, AudioFrameUtils.calculateAlignedSampleCount(-10, 2))
    }

    @Test
    fun testCalculateBatchSamplesToRead() {
        // Batch size 1024 for 6 channels -> 170 frames * 6 = 1020
        assertEquals(1020, AudioFrameUtils.calculateBatchSamplesToRead(1024, 6))

        // Batch size 1024 for 2 channels -> 1024
        assertEquals(1024, AudioFrameUtils.calculateBatchSamplesToRead(1024, 2))
    }

    @Test
    fun testCalculateDriftAndShouldResync() {
        // exo = 1000ms, engine = 1050ms, latency = 50ms -> trueAudible = 1000ms -> drift = 0ms
        val drift0 = AudioFrameUtils.calculateDriftMs(exoPosMs = 1000L, engineAudioPosMs = 1050L, latencyMs = 50L)
        assertEquals(0L, drift0)
        assertFalse(AudioFrameUtils.shouldResync(drift0, 80L))

        // exo = 1200ms, engine = 1050ms, latency = 50ms -> trueAudible = 1000ms -> drift = +200ms
        val drift200 = AudioFrameUtils.calculateDriftMs(exoPosMs = 1200L, engineAudioPosMs = 1050L, latencyMs = 50L)
        assertEquals(200L, drift200)
        assertTrue(AudioFrameUtils.shouldResync(drift200, 80L))

        // Negative drift -90ms -> should resync
        assertTrue(AudioFrameUtils.shouldResync(-90L, 80L))
        // Small drift 40ms -> should not resync
        assertFalse(AudioFrameUtils.shouldResync(40L, 80L))
    }
}
