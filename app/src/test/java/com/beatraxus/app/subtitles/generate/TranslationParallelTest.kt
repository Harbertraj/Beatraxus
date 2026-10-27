package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranslationParallelTest {

    private val cues = (0 until 16).map { SubtitleCue(it * 1_000L, it * 1_000L + 900, "line $it") }

    /** Takes a little time per request and records how many requests were in flight together. */
    private class SlowTranslator(private val failOnLine: Int? = null) : TextTranslator {
        private var inFlight = 0
        var maxInFlight = 0
        override suspend fun translate(lines: List<IndexedLine>, fromLanguageName: String?, toLanguageName: String): Map<Int, String> {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(40)
                if (failOnLine != null && lines.any { it.index == failOnLine }) {
                    throw GenerationException(GenerationException.Kind.AUTH, "bad key")
                }
                return lines.associate { it.index to "T(${it.text})" }
            } finally {
                inFlight--
            }
        }
    }

    @Test
    fun batchesRunTogetherAndEveryLineKeepsItsPlaceAndTiming() = runBlocking {
        val t = SlowTranslator()
        val saved = HashMap<Int, String>()
        val progress = mutableListOf<Int>()
        val result = SubtitleTranslator(t, batchSize = 2, parallelism = 4).translate(
            cues, "Hindi", "Tamil", "ta",
            onBatchDone = { saved += it },
            onProgress = { done, _ -> progress += done }
        )

        assertEquals(4, t.maxInFlight)
        assertEquals(0, result.untranslated)
        // Whatever order the batches finished in, each line is back at its own position with its own times.
        cues.forEachIndexed { i, cue ->
            assertEquals("T(line $i)", result.cues[i].text)
            assertEquals(cue.startMs, result.cues[i].startMs)
            assertEquals(cue.endMs, result.cues[i].endMs)
        }
        // Everything was handed over for saving, and progress only ever moves forward to the total.
        assertEquals((0 until 16).toSet(), saved.keys)
        assertEquals(progress.sorted(), progress)
        assertEquals(8, progress.last())
    }

    @Test
    fun parallelismOfOneAsksOneAtATime() = runBlocking {
        val t = SlowTranslator()
        SubtitleTranslator(t, batchSize = 2, parallelism = 1).translate(cues, "Hindi", "Tamil", "ta")
        assertEquals(1, t.maxInFlight)
    }

    @Test
    fun savingNeverOverlapsEvenWhenBatchesFinishTogether() = runBlocking {
        var saving = 0
        var maxSaving = 0
        SubtitleTranslator(SlowTranslator(), batchSize = 2, parallelism = 8).translate(
            cues, "Hindi", "Tamil", "ta",
            onBatchDone = {
                saving++
                maxSaving = maxOf(maxSaving, saving)
                delay(5)
                saving--
            }
        )
        assertEquals(1, maxSaving)
    }

    @Test
    fun aFailingBatchStopsTheRunAndNothingUnfinishedIsSaved() = runBlocking {
        val saved = HashMap<Int, String>()
        try {
            SubtitleTranslator(SlowTranslator(failOnLine = 5), batchSize = 2, parallelism = 4).translate(
                cues, "Hindi", "Tamil", "ta", onBatchDone = { saved += it }
            )
            fail("the failure should stop the run")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.AUTH, e.kind)
        }
        // The batch holding line 5 (lines 4 and 5) never finished, so it was not handed over as done.
        assertTrue(4 !in saved.keys && 5 !in saved.keys)
    }
}
