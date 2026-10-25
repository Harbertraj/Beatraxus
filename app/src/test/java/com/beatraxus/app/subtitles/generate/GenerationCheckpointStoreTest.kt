package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenerationCheckpointStoreTest {

    private fun tempFile(): File = File.createTempFile("ckpt", ".txt").apply { delete(); deleteOnExit() }

    @Test
    fun savedPartsComeBackExactlyIncludingAwkwardText() {
        val file = tempFile()
        val tricky = "line one\nline two\twith a tab and a \\ backslash and \\n text"
        GenerationCheckpointStore.append(file, ChunkResult(3, listOf(SubtitleCue(1_000, 2_500, "नमस्ते"), SubtitleCue(3_000, 4_000, tricky)), "hi"))
        GenerationCheckpointStore.append(file, ChunkResult(5, listOf(SubtitleCue(900_000, 902_000, "after")), "hi"))
        GenerationCheckpointStore.append(file, ChunkResult(6, emptyList(), "hi"))

        val loaded = GenerationCheckpointStore.load(file)!!
        assertEquals(setOf(3, 5, 6), loaded.doneChunks)
        assertEquals("hi", loaded.languageCode)
        assertEquals(listOf("नमस्ते", tricky, "after"), loaded.cues.map { it.text })
        assertEquals(listOf(1_000L, 3_000L, 900_000L), loaded.cues.map { it.startMs })
    }

    @Test
    fun aWriteThatWasCutShortIsIgnoredAndTheRestIsKept() {
        val file = tempFile()
        GenerationCheckpointStore.append(file, ChunkResult(0, listOf(SubtitleCue(1_000, 2_000, "kept")), "hi"))
        // Promises two cues but only one arrived, and the last line has no newline.
        file.appendText("chunk\t1\thi\t2\ncue\t10000\t11000\tlost\ncue\t12000\t13000\ttrunc", Charsets.UTF_8)

        val loaded = GenerationCheckpointStore.load(file)!!
        assertEquals(setOf(0), loaded.doneChunks)
        assertEquals(listOf("kept"), loaded.cues.map { it.text })
    }

    @Test
    fun nothingSavedMeansNothingToResume() {
        assertNull(GenerationCheckpointStore.load(tempFile()))
    }
}

class PartialSubtitleSchedulerTest {

    private val chunk = 180_000L

    @Test
    fun firstReadyPartIsShownWhereverItIs() {
        assertTrue(PartialSubtitleScheduler.shouldShow(setOf(40), emptySet(), positionMs = 7_300_000, chunkLengthMs = chunk))
    }

    @Test
    fun nothingNewMeansNothingToShow() {
        assertFalse(PartialSubtitleScheduler.shouldShow(setOf(40), setOf(40), 7_300_000, chunk))
        assertFalse(PartialSubtitleScheduler.shouldShow(emptySet(), emptySet(), 0, chunk))
    }

    @Test
    fun partsFarAwayFromThePlayingPositionDoNotInterruptThePicture() {
        // Viewer in part 5; part 40 is ready but 5 and its neighbours are on screen already.
        assertFalse(PartialSubtitleScheduler.shouldShow(setOf(5, 6, 40), setOf(5, 6), 5 * chunk + 10_000, chunk))
    }

    @Test
    fun theViewersPartIsShownAsSoonAsItIsReady() {
        // Seeked into part 12, which finished after the last swap.
        assertTrue(PartialSubtitleScheduler.shouldShow(setOf(5, 12), setOf(5), 12 * chunk + 1_000, chunk))
    }

    @Test
    fun theNextPartIsSwappedInBeforePlaybackReachesIt() {
        // 20 s before the end of part 5, part 6 is ready but not shown.
        assertTrue(PartialSubtitleScheduler.shouldShow(setOf(5, 6), setOf(5), 6 * chunk - 20_000, chunk))
        // Much earlier in part 5 it can wait.
        assertFalse(PartialSubtitleScheduler.shouldShow(setOf(5, 6), setOf(5), 5 * chunk + 20_000, chunk))
    }
}

class RateLimitWaitParsingTest {

    @Test
    fun readsMinutesAndSecondsFromTheServiceMessage() {
        val body = """{"error":{"message":"Rate limit reached for model whisper. Limit 7200, Used 7200. Please try again in 7m12.5s."}}"""
        assertEquals(432_500L, OpenAiStyle.parseTryAgainMs(body))
    }

    @Test
    fun readsHoursAndMilliseconds() {
        assertEquals(3_723_000L, OpenAiStyle.parseTryAgainMs("Please try again in 1h2m3s"))
        assertEquals(350L, OpenAiStyle.parseTryAgainMs("Please try again in 350ms"))
    }

    @Test
    fun noWaitInTheMessageMeansNull() {
        assertNull(OpenAiStyle.parseTryAgainMs("""{"error":{"message":"Too many requests"}}"""))
    }
}
