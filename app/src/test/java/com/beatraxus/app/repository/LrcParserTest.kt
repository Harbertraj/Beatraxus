package com.beatraxus.app.repository

import com.beatraxus.app.model.LyricSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun testDurationCalculation() {
        val lrc = """
            [00:10.00] Line 1
            [00:15.00] Line 2
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        
        assertEquals(2, lines.size)
        assertEquals(5000L, lines[0].duration)
        assertEquals(5000L, lines[1].duration) // Last line gets 5s fallback
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
    fun testSpeakerDetectionFalsePositives() {
        val lrc = """
            [00:10.00] Male chorus line
            [00:15.00] I am a male singer
            [00:20.00] [Verse 1] Hello world
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)

        assertEquals(LyricSpeaker.NONE, lines[0].speaker)
        assertEquals("Male chorus line", lines[0].text)

        assertEquals(LyricSpeaker.NONE, lines[1].speaker)
        assertEquals("I am a male singer", lines[1].text)

        assertEquals(LyricSpeaker.NONE, lines[2].speaker)
        assertEquals("[Verse 1] Hello world", lines[2].text)
    }

    @Test
    fun testMarkerStrippingWithWordTimings() {
        val lrc = "[00:10.00] [Male] <00:10.00>Hello <00:10.50>World <00:11.00]"
        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(LyricSpeaker.MALE, line.speaker)
        assertEquals("Hello World", line.text)
        assertNotNull(line.wordTimings)
        assertEquals(2, line.wordTimings?.size)
        assertEquals("Hello", line.wordTimings?.get(0)?.text)
        assertEquals("World", line.wordTimings?.get(1)?.text)
    }

    @Test
    fun testStickySpeakerCarryOver() {
        val lrc = """
            [00:10.00] [Male] Line 1
            [00:15.00] Line 2
            [00:20.00] (Female) Line 3
            [00:25.00] Line 4
            [00:30.00] ♪
            [00:35.00] Line 5
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(5, lines.size) // Instrumental line "♪" filtered out

        assertEquals(LyricSpeaker.MALE, lines[0].speaker)
        assertEquals("Line 1", lines[0].text)

        assertEquals(LyricSpeaker.MALE, lines[1].speaker) // Carried forward from Line 1
        assertEquals("Line 2", lines[1].text)

        assertEquals(LyricSpeaker.FEMALE, lines[2].speaker)
        assertEquals("Line 3", lines[2].text)

        assertEquals(LyricSpeaker.FEMALE, lines[3].speaker) // Carried forward from Line 3
        assertEquals("Line 4", lines[3].text)

        assertEquals(LyricSpeaker.NONE, lines[4].speaker) // Reset by instrumental line
        assertEquals("Line 5", lines[4].text)
    }

    @Test
    fun testTimestampVsSpeakerMarkerDistinction() {
        val lrc = "[00:12.30][Male] Hello world"
        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(12300L, line.startTime)
        assertEquals(LyricSpeaker.MALE, line.speaker)
        assertEquals("Hello world", line.text)
    }
}
