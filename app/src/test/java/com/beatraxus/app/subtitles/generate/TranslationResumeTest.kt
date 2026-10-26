package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class TranslationResumeTest {

    private val cues = (0 until 6).map { SubtitleCue(it * 1_000L, it * 1_000L + 900, "line $it") }

    /** Translates everything, but throws a rate limit once [failAtCall] calls were made. */
    private class LimitedTranslator(private val failAtCall: Int = Int.MAX_VALUE) : TextTranslator {
        val asked = mutableListOf<Int>()
        private var calls = 0
        override suspend fun translate(lines: List<IndexedLine>, fromLanguageName: String?, toLanguageName: String): Map<Int, String> {
            if (++calls >= failAtCall) throw GenerationException(GenerationException.Kind.RATE_LIMITED, "limit")
            asked += lines.map { it.index }
            return lines.associate { it.index to "T(${it.text})" }
        }
    }

    @Test
    fun rateLimitStopsTheRunButKeepsFinishedBatches() = runBlocking {
        val saved = HashMap<Int, String>()
        val t = LimitedTranslator(failAtCall = 2)
        try {
            SubtitleTranslator(t, batchSize = 2).translate(cues, "Hindi", "Tamil", "ta", onBatchDone = { saved += it })
            fail("the rate limit should stop the run")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.RATE_LIMITED, e.kind)
        }
        // The first batch (lines 0 and 1) was finished and handed over before the limit hit.
        assertEquals(setOf(0, 1), saved.keys)
    }

    @Test
    fun resumedRunOnlyAsksForTheLinesThatAreMissing() = runBlocking {
        val resume = mapOf(0 to "T(line 0)", 1 to "T(line 1)")
        val t = LimitedTranslator()
        val progress = mutableListOf<Int>()
        val result = SubtitleTranslator(t, batchSize = 2).translate(
            cues, "Hindi", "Tamil", "ta", resume = resume, onProgress = { done, _ -> progress += done }
        )
        assertEquals(listOf(2, 3, 4, 5), t.asked)
        assertEquals(0, result.untranslated)
        assertEquals("T(line 0)", result.cues[0].text)
        assertEquals("T(line 5)", result.cues[5].text)
        // Progress starts at the part it continues from (the second batch), not at part 1.
        assertEquals(1, progress.first())
        assertEquals(cues.map { it.startMs }, result.cues.map { it.startMs })
    }

    @Test
    fun checkpointRoundTripAndSourceChange() {
        val file = File.createTempFile("tr", ".ckpt").also { it.delete() }
        try {
            val sig = TranslationCheckpointStore.signature(cues)
            TranslationCheckpointStore.append(file, sig, mapOf(0 to "a\tb", 1 to "multi\nline"))
            TranslationCheckpointStore.append(file, sig, mapOf(2 to "c"))
            assertEquals(mapOf(0 to "a\tb", 1 to "multi\nline", 2 to "c"), TranslationCheckpointStore.load(file, sig))

            // A different source subtitle must not reuse the saved lines.
            val other = TranslationCheckpointStore.signature(cues.drop(1))
            assertTrue(TranslationCheckpointStore.load(file, other).isEmpty())

            // A write that was cut short loses only the unfinished last line.
            file.appendText("3\tcut off", Charsets.UTF_8)
            assertEquals(setOf(0, 1, 2), TranslationCheckpointStore.load(file, sig).keys)

            TranslationCheckpointStore.clear(file)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }
}
