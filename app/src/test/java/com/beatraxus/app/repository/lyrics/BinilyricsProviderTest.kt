package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.LrcParser
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BinilyricsProviderTest {

    @Test
    fun testBiniLyricsResponseParsing() {
        val jsonResponse = """
            {
              "results": [
                {
                  "lyricsUrl": "https://lyrics-api.binimum.org/ttml/12345"
                }
              ]
            }
        """.trimIndent()

        val root = JsonParser.parseString(jsonResponse).asJsonObject
        val results = root.getAsJsonArray("results")
        assertNotNull(results)
        assertEquals(1, results.size())

        val lyricsUrl = results.get(0).asJsonObject.get("lyricsUrl")?.asString
        assertEquals("https://lyrics-api.binimum.org/ttml/12345", lyricsUrl)
    }

    @Test
    fun testTtmlParsingToWordByWordLrc() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:00:02.000" end="00:00:06.000">
                            <span begin="00:00:02.000" end="00:00:03.500">Uptown </span>
                            <span begin="00:00:03.500" end="00:00:05.000">Funk </span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val lrc = TtmlParser.parseToEnhancedLrc(ttml)
        assertNotNull(lrc)
        val lines = LrcParser.parse(lrc!!)
        assertEquals(1, lines.size)
        assertEquals("Uptown Funk", lines[0].text)
        assertNotNull(lines[0].wordTimings)
        assertEquals(2, lines[0].wordTimings?.size)
    }
}
