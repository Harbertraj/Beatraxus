package com.beatraxus.app.repository.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertNotNull(lrc)
        val nonNullLrc = lrc!!

        // Round trip through LrcParser
        val lines = LrcParser.parse(nonNullLrc)
        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].startTime)
        assertEquals("Hello World", lines[0].text)

        val timings = lines[0].wordTimings
        assertNotNull(timings)
        assertEquals(2, timings!!.size)
        assertEquals("Hello", timings[0].text)
        assertEquals("World", timings[1].text)

        assertEquals(15000L, lines[1].startTime)
        assertEquals("Just Line", lines[1].text)
    }

    @Test
    fun testEnglishSyllablesCombineToOneWord() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:00:05.000">
                            <span begin="00:00:05.000" end="00:00:05.500">Sha</span>
                            <span begin="00:00:05.500" end="00:00:06.000">pe </span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val lrc = TtmlParser.parseToEnhancedLrc(ttml)
        assertNotNull(lrc)

        val lines = LrcParser.parse(lrc!!)
        assertEquals(1, lines.size)
        assertEquals("Shape", lines[0].text)

        val wordTimings = lines[0].wordTimings
        assertNotNull(wordTimings)
        assertEquals(1, wordTimings!!.size)
        assertEquals("Shape", wordTimings[0].text)
    }

    @Test
    fun testTamilSyllablesAndTranslationIgnored() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body>
                    <div>
                        <p begin="00:00:10.000">
                            <span begin="00:00:10.000" end="00:00:10.500">மர</span>
                            <span begin="00:00:10.500" end="00:00:11.000">ண</span>
                            <span begin="00:00:11.000" end="00:00:12.000"> மாஸ்</span>
                            <span ttm:role="x-translation" begin="00:00:10.000" end="00:00:12.000">Marana Mass</span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val lrc = TtmlParser.parseToEnhancedLrc(ttml)
        assertNotNull(lrc)
        assertFalse(lrc!!.contains("Marana Mass"))

        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals("மரண மாஸ்", lines[0].text)

        val wordTimings = lines[0].wordTimings
        assertNotNull(wordTimings)
        assertEquals(2, wordTimings!!.size)
        assertEquals("மரண", wordTimings[0].text)
        assertEquals("மாஸ்", wordTimings[1].text)
    }
}
