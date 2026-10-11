package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackTuningTest {

    @Test
    fun defaultDecoderModeIsHardwareWithSoftwareFallback() {
        assertEquals(VideoDecoderMode.HW_PLUS, VideoDecoderMode.DEFAULT)
        assertEquals(VideoDecoderMode.DEFAULT, VideoPlaybackTuning().decoderMode)
    }

    @Test
    fun decoderModeRoundTripsThroughItsSavedName() {
        for (mode in VideoDecoderMode.entries) {
            assertEquals(mode, VideoDecoderMode.fromName(mode.name))
        }
    }

    @Test
    fun missingOrUnknownSavedDecoderModeFallsBackToDefault() {
        assertEquals(VideoDecoderMode.DEFAULT, VideoDecoderMode.fromName(null))
        assertEquals(VideoDecoderMode.DEFAULT, VideoDecoderMode.fromName(""))
        assertEquals(VideoDecoderMode.DEFAULT, VideoDecoderMode.fromName("HARDWARE_ONLY"))
    }

    @Test
    fun audioDelayIsClampedToTheSupportedRange() {
        val tuning = VideoPlaybackTuning()
        tuning.audioDelayMs = 999_999
        assertEquals(VideoPlaybackTuning.MAX_AUDIO_DELAY_MS, tuning.audioDelayMs)
        tuning.audioDelayMs = -999_999
        assertEquals(VideoPlaybackTuning.MIN_AUDIO_DELAY_MS, tuning.audioDelayMs)
        tuning.audioDelayMs = 250
        assertEquals(250, tuning.audioDelayMs)
    }

    @Test
    fun initialAudioDelayIsClampedToo() {
        assertEquals(VideoPlaybackTuning.MAX_AUDIO_DELAY_MS, VideoPlaybackTuning(audioDelayMs = 60_000).audioDelayMs)
    }
}
