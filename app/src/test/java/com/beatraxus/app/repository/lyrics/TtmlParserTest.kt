package com.beatraxus.app.repository.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import com.beatraxus.app.repository.LrcParser

class TtmlParserTest {
    @Test
    fun testParseTtmlToEnhancedLrc() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:00:10.000" end="00:00:15.000">
                            <span begin="00:00:10.000" end="00:00:11.500">Hello </span>
                            <span begin="00:00:11.500" end="00:00:13.000">World </span>
                        </p>
                        <p begin="00:00:15.000" end="00:00:20.000">Just Line</p>
                    </div>
                </body>
            </tt>
        """.trimIndent()
        
        val lrc = TtmlParser.parseToEnhancedLrc(ttml)
        val expected = "[00:10.00]Hello  <00:11.50>World \n[00:15.00]Just Line"
        assertEquals(expected, lrc)
        
        // Round trip through LrcParser
        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].startTime)
        assertEquals("Hello  World", lines[0].text) // Double space because text values had spaces
        
        val timings = lines[0].wordTimings
        require(timings != null)
        assertEquals(2, timings.size)
        assertEquals(10000L, timings[0].startTime)
        assertEquals(1500L, timings[0].duration) // 11500 - 10000
        assertEquals("Hello", timings[0].text)
        
        assertEquals(11500L, timings[1].startTime)
        // Duration of last word will be refined by next line's start time in LrcParser
        // lines[1] starts at 15000, so World duration is 15000 - 11500 = 3500L
        assertEquals(3500L, timings[1].duration) 
        assertEquals("World", timings[1].text)

        assertEquals(15000L, lines[1].startTime)
        assertEquals("Just Line", lines[1].text)
    }
}
