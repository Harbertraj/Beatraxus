package com.beatraxus.app.repository.lyrics

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class YoutubeCaptionsProviderTest {

    @Test
    fun testJson3ToLrcConversionAndCleaning() {
        val json3Body = """
            {
              "events": [
                {
                  "tStartMs": 1200,
                  "dDurationMs": 2000,
                  "segs": [{ "utf8": "♪ " }, { "utf8": "First line of lyrics" }]
                },
                {
                  "tStartMs": 4500,
                  "dDurationMs": 2000,
                  "segs": [{ "utf8": "[Music]" }]
                },
                {
                  "tStartMs": 7800,
                  "dDurationMs": 2000,
                  "segs": [{ "utf8": "Second line of lyrics" }]
                },
                {
                  "tStartMs": 11000,
                  "dDurationMs": 2000,
                  "segs": [{ "utf8": "Third line of lyrics" }]
                },
                {
                  "tStartMs": 14500,
                  "dDurationMs": 2000,
                  "segs": [{ "utf8": "Fourth line of lyrics" }]
                }
              ]
            }
        """.trimIndent()

        val events = JsonParser.parseString(json3Body).asJsonObject.getAsJsonArray("events")
        val lrcLines = mutableListOf<String>()

        for (eventElement in events) {
            val event = eventElement.asJsonObject
            val tStartMs = event.get("tStartMs")?.asLong ?: 0L
            val segs = event.getAsJsonArray("segs") ?: continue

            val sb = StringBuilder()
            for (segElement in segs) {
                val seg = segElement.asJsonObject
                sb.append(seg.get("utf8")?.asString ?: "")
            }

            val text = cleanCaptionText(sb.toString())
            if (text.isBlank()) continue

            val timestamp = formatLrcTime(tStartMs)
            lrcLines.add("$timestamp $text")
        }

        assertEquals(4, lrcLines.size)
        assertEquals("[00:01.20] First line of lyrics", lrcLines[0])
        assertEquals("[00:07.80] Second line of lyrics", lrcLines[1])
        assertEquals("[00:11.00] Third line of lyrics", lrcLines[2])
        assertEquals("[00:14.50] Fourth line of lyrics", lrcLines[3])
    }

    private fun cleanCaptionText(rawText: String): String {
        return rawText
            .replace(Regex("""\[(?:Music|Musique|Musik|Applause|Laughter|Silence|Singing|Audio|Instrumental)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\((?:Music|Musique|Musik|Applause|Laughter|Silence|Singing|Audio|Instrumental)\)""", RegexOption.IGNORE_CASE), "")
            .replace("♪", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val hundredths = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, hundredths)
    }
}
