package com.beatraxus.app.repository

import com.beatraxus.app.model.LrcLine
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
}
