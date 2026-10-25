package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchTest {

    /** Answers every line except the ones listed in [dropIds]; records how many lines each call asked for. */
    private class PickyTranslator(private val dropIds: Set<Int> = emptySet()) : TextTranslator {
        val batchSizes = mutableListOf<Int>()
        override suspend fun translate(lines: List<IndexedLine>, fromLanguageName: String?, toLanguageName: String): Map<Int, String> {
            batchSizes += lines.size
            return lines.filter { it.index !in dropIds }.associate { it.index to "T(${it.text})" }
        }
    }

    private val cues = (0 until 5).map { SubtitleCue(it * 1_000L, it * 1_000L + 900, "line $it") }

    @Test
    fun everyLineTranslatedAndTimingsCopied() = kotlinx.coroutines.runBlocking {
        val result = SubtitleTranslator(PickyTranslator(), batchSize = 2).translate(cues, "Hindi", "English", "en")
        assertEquals(0, result.untranslated)
        assertEquals(cues.map { it.startMs }, result.cues.map { it.startMs })
        assertEquals(cues.map { it.endMs }, result.cues.map { it.endMs })
        assertEquals("T(line 3)", result.cues[3].text)
    }

    @Test
    fun missingLinesAreRetriedInSmallerBatches() = kotlinx.coroutines.runBlocking {
        val t = PickyTranslator(dropIds = setOf(1))
        val result = SubtitleTranslator(t, batchSize = 5).translate(cues, "Hindi", "English", "en")
        // Line 1 never comes back: it keeps its original text, everything else is translated.
        assertEquals(1, result.untranslated)
        assertEquals("line 1", result.cues[1].text)
        assertEquals("T(line 0)", result.cues[0].text)
        assertTrue("retries should shrink the batch, got $t.batchSizes", t.batchSizes.size > 1)
    }

    @Test
    fun cueTextWithNewlineIsTranslatedAsOneSentence() = kotlinx.coroutines.runBlocking {
        val two = listOf(SubtitleCue(0, 2_000, "first half\nsecond half"))
        val result = SubtitleTranslator(PickyTranslator(), batchSize = 40).translate(two, "Hindi", "English", "en")
        assertEquals("T(first half second half)", result.cues.single().text)
    }

    @Test
    fun parsesObjectAnswersWrappedInChatter() {
        val asked = listOf(IndexedLine(0, "a"), IndexedLine(1, "b"))
        val answer = "Sure!\n```json\n[{\"i\":0,\"t\":\"Hello\"},{\"i\":1,\"t\":\"World\"}]\n```"
        assertEquals(mapOf(0 to "Hello", 1 to "World"), TranslationJson.parseAnswer(answer, asked))
    }

    @Test
    fun ignoresIdsThatWereNotAsked() {
        val asked = listOf(IndexedLine(4, "a"))
        assertEquals(mapOf(4 to "A"), TranslationJson.parseAnswer("[{\"i\":4,\"t\":\"A\"},{\"i\":99,\"t\":\"X\"}]", asked))
    }

    @Test
    fun plainStringAnswerOnlyTrustedWhenCountMatches() {
        val asked = listOf(IndexedLine(0, "a"), IndexedLine(1, "b"))
        assertEquals(mapOf(0 to "x", 1 to "y"), TranslationJson.parseAnswer("[\"x\",\"y\"]", asked))
        assertTrue(TranslationJson.parseAnswer("[\"only one\"]", asked).isEmpty())
    }

    @Test
    fun garbageAnswerGivesNothing() {
        assertTrue(TranslationJson.parseAnswer("I cannot help with that.", listOf(IndexedLine(0, "a"))).isEmpty())
    }

    @Test
    fun ffmpegArgsCutTheRightWindowFromTheRightTrack() {
        val args = FfmpegAudioArgs.build("in.mkv", startMs = 600_000, lengthMs = 600_000, audioStreamOrdinal = 2, outputPath = "o.wav")
        val s = args.toList()
        assertEquals("600.000", s[s.indexOf("-ss") + 1])
        assertEquals("600.000", s[s.indexOf("-t") + 1])
        assertEquals("0:a:2", s[s.indexOf("-map") + 1])
        assertTrue(s.indexOf("-ss") < s.indexOf("-i"))
        assertEquals("aresample=async=1:first_pts=0", s[s.indexOf("-af") + 1])
    }
}
