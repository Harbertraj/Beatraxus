package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.data.SubtitleDelayShifter
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDelayShifterTest {

    @Test
    fun testPositiveDelayShift() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nHello\n"
        val shifted = SubtitleDelayShifter.shiftDelay(srt, 2500L) // +2.5 seconds

        assertTrue(shifted.contains("00:00:03,500 --> 00:00:06,500"))
        assertTrue(shifted.contains("Hello"))
    }

    @Test
    fun testNegativeDelayShift() {
        val srt = "1\n00:00:05,000 --> 00:00:08,000\nHello\n"
        val shifted = SubtitleDelayShifter.shiftDelay(srt, -2000L) // -2.0 seconds

        assertTrue(shifted.contains("00:00:03,000 --> 00:00:06,000"))
    }

    @Test
    fun testNegativeDelayClampsAtZero() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nHello\n"
        val shifted = SubtitleDelayShifter.shiftDelay(srt, -5000L) // -5.0 seconds

        assertTrue(shifted.contains("00:00:00,000 --> 00:00:00,000"))
    }
}
