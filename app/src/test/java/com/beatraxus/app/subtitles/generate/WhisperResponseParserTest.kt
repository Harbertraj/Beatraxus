package com.beatraxus.app.subtitles.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WhisperResponseParserTest {

    private val verbose = """
        {
          "task": "transcribe",
          "language": "hindi",
          "duration": 12.5,
          "text": "नमस्ते दोस्तों आप कैसे हैं",
          "words": [
            {"word": "नमस्ते", "start": 0.52, "end": 1.1},
            {"word": "दोस्तों", "start": 1.1, "end": 1.7},
            {"word": "आप", "start": 2.4, "end": 2.6}
          ],
          "segments": [
            {"id": 1, "start": 2.0, "end": 4.0, "text": " आप कैसे हैं", "avg_logprob": -0.3, "compression_ratio": 1.2, "no_speech_prob": 0.01},
            {"id": 0, "start": 0.0, "end": 1.9, "text": " नमस्ते दोस्तों"}
          ]
        }
    """.trimIndent()

    @Test
    fun readsLanguageSegmentsAndWordsInTimeOrder() {
        val t = WhisperResponseParser.parse(verbose)
        assertEquals("hindi", t.languageName)
        assertEquals(2, t.segments.size)
        assertEquals(0L, t.segments[0].startMs)
        assertEquals(1_900L, t.segments[0].endMs)
        assertEquals("नमस्ते दोस्तों", t.segments[0].text)
        assertEquals(2_000L, t.segments[1].startMs)
        assertEquals(-0.3, t.segments[1].avgLogprob!!, 1e-9)
        assertNull(t.segments[0].noSpeechProb)
        assertEquals(3, t.words.size)
        assertEquals(520L, t.words[0].startMs)
    }

    @Test
    fun responseWithoutWordsStillParses() {
        val t = WhisperResponseParser.parse("""{"language":"english","text":"hi","segments":[{"start":0,"end":1.5,"text":"hi"}]}""")
        assertTrue(t.words.isEmpty())
        assertEquals(1_500L, t.segments.single().endMs)
    }

    @Test
    fun silenceIsAnEmptyTranscriptNotAnError() {
        val t = WhisperResponseParser.parse("""{"language":"english","text":"","segments":[]}""")
        assertTrue(t.segments.isEmpty())
    }

    @Test
    fun textWithoutTimestampsIsRejected() {
        try {
            WhisperResponseParser.parse("""{"text":"hello there"}""")
            fail("expected a BAD_RESPONSE")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun nonJsonIsRejected() {
        try {
            WhisperResponseParser.parse("<html>502 Bad Gateway</html>")
            fail("expected a BAD_RESPONSE")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun languageNamesMapToCodes() {
        assertEquals("hi", WhisperLanguages.codeFor("hindi"))
        assertEquals("hi", WhisperLanguages.codeFor("Hindi"))
        assertEquals("en", WhisperLanguages.codeFor(" english "))
        assertEquals("zh-cn", WhisperLanguages.codeFor("zh-CN"))
        assertNull(WhisperLanguages.codeFor("klingon"))
        assertNull(WhisperLanguages.codeFor(null))
        assertTrue(WhisperLanguages.sameLanguage("zh-CN", "zh"))
        assertFalse(WhisperLanguages.sameLanguage("hi", "en"))
        assertFalse(WhisperLanguages.sameLanguage(null, "en"))
        assertFalse(WhisperLanguages.usesSpaces("ja"))
        assertTrue(WhisperLanguages.usesSpaces("hi"))
        assertEquals("Hindi", WhisperLanguages.displayName("hi"))
    }
}
