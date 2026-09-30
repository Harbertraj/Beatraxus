package com.beatraxus.app.repository

import com.beatraxus.app.model.LyricSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun testParseWordTimingsWithInitialTimestamp() {
        // Line starts immediately with a word timestamp
        val lrc = "[00:10.00]<00:10.00>Hello <00:10.50>World <00:11.00]"
        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("Hello World", line.text)
        assertNotNull(line.wordTimings)
        assertEquals(2, line.wordTimings?.size)

        val firstWord = line.wordTimings!![0]
        assertEquals("Hello", firstWord.text)
        assertEquals(10000L, firstWord.startTime)
    }

    @Test
    fun testDurationCalculationWithBlankMarker() {
        val lrc = """
            [00:10.00] Line 1
            [00:15.00]
            [00:30.00] Line 2
        """.trimIndent()
        val lines = LrcParser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals("Line 1", lines[0].text)
        assertEquals(10000L, lines[0].startTime)
        assertEquals(5000L, lines[0].duration) // Bounded by blank marker at 15s

        assertEquals("Line 2", lines[1].text)
        assertEquals(30000L, lines[1].startTime)
        assertEquals(5000L, lines[1].duration) // Bounded by end of song, 5s fallback
    }

    @Test
    fun testBlankMarker10SecCap() {
        val lrc = """
            [00:10.00] Line 1
            [00:40.00]
            [01:00.00] Line 2
        """.trimIndent()
        val lines = LrcParser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals("Line 1", lines[0].text)
        // 40s - 10s = 30s raw gap, but capped at 10000ms max because next line is blank marker!
        assertEquals(10000L, lines[0].duration)
    }

    @Test
    fun testMultipleTimestampsOnSingleLine() {
        val lrc = "[00:12.00][01:05.00] chorus text"
        val lines = LrcParser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals(12000L, lines[0].startTime)
        assertEquals("chorus text", lines[0].text)

        assertEquals(65000L, lines[1].startTime)
        assertEquals("chorus text", lines[1].text)
    }

    @Test
    fun testOffsetTagHeader() {
        val lrcPositive = """
            [offset:+500]
            [00:10.00] Line 1
        """.trimIndent()
        val linesPositive = LrcParser.parse(lrcPositive)
        assertEquals(1, linesPositive.size)
        // Positive offset means lyrics appear 500ms earlier -> 10000 - 500 = 9500
        assertEquals(9500L, linesPositive[0].startTime)

        val lrcNegative = """
            [offset:-500]
            [00:10.00] Line 1
        """.trimIndent()
        val linesNegative = LrcParser.parse(lrcNegative)
        assertEquals(1, linesNegative.size)
        // Negative offset means lyrics appear 500ms later -> 10000 - (-500) = 10500
        assertEquals(10500L, linesNegative[0].startTime)
    }

    @Test
    fun testSpeakerDetectionPositiveCases() {
        val lrc = """
            [00:10.00] [Male] Line 1
            [00:15.00] (Female) Line 2
            [00:20.00] Duet: Line 3
            [00:25.00] [Chorus] Line 4
            [00:30.00] ஆண்: வணக்கம்
            [00:35.00] महिला: नमस्ते
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(6, lines.size)

        assertEquals(LyricSpeaker.MALE, lines[0].speaker)
        assertEquals("Line 1", lines[0].text)

        assertEquals(LyricSpeaker.FEMALE, lines[1].speaker)
        assertEquals("Line 2", lines[1].text)

        assertEquals(LyricSpeaker.DUET_BOTH, lines[2].speaker)
        assertEquals("Line 3", lines[2].text)

        assertEquals(LyricSpeaker.CHORUS, lines[3].speaker)
        assertEquals("Line 4", lines[3].text)

        assertEquals(LyricSpeaker.MALE, lines[4].speaker)
        assertEquals("வணக்கம்", lines[4].text)

        assertEquals(LyricSpeaker.FEMALE, lines[5].speaker)
        assertEquals("नमस्ते", lines[5].text)
    }

    @Test
    fun testChorusSpeakerResetsOnGapOver6Sec() {
        val lrc = """
            [00:10.00] [Chorus] Chorus line 1
            [00:15.00] Chorus line 2
            [00:25.00] Chorus line 3
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)

        assertEquals(LyricSpeaker.CHORUS, lines[0].speaker)
        assertEquals(LyricSpeaker.CHORUS, lines[1].speaker) // Gap = 5s <= 6s -> carried forward
        assertEquals(LyricSpeaker.NONE, lines[2].speaker)   // Gap = 10s > 6s -> CHORUS reset!
    }

    @Test
    fun testMaleSpeakerCarriesOverGap6Sec() {
        val lrc = """
            [00:10.00] [Male] Male line 1
            [00:25.00] Male line 2
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)

        assertEquals(LyricSpeaker.MALE, lines[0].speaker)
        assertEquals(LyricSpeaker.MALE, lines[1].speaker) // MALE carries over gap > 6s until next tag or blank
    }

    @Test
    fun testUntimedPlainLyricsFallbackWithSongDuration() {
        val plainLrc = """
            Line 1
            Line 2
            Line 3
            Line 4
        """.trimIndent()

        val songDurationMs = 200000L // 200 seconds
        val lines = LrcParser.parse(plainLrc, songDurationMs)

        assertEquals(4, lines.size)
        assertFalse(lines[0].isTimed)
        assertEquals(0L, lines[0].startTime)
        assertEquals(50000L, lines[0].duration)

        assertEquals(50000L, lines[1].startTime)
        assertEquals(50000L, lines[1].duration)
    }
}
