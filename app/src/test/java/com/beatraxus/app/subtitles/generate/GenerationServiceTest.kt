package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import com.beatraxus.app.subtitles.domain.SubtitleCueParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Serves a fixed number of chunks; each chunk is an empty file, the transcriber ignores it. */
private class FakeChunks(private val chunks: Int) : AudioChunkProvider {
    val requested = mutableListOf<Long>()
    override suspend fun extract(startMs: Long, lengthMs: Long): File? {
        requested += startMs
        val index = (startMs / lengthMs).toInt()
        if (index >= chunks) return null
        return File.createTempFile("chunk", ".wav").apply { deleteOnExit() }
    }
}

/** Returns the Hindi transcript for chunk 0 and a second spoken line for chunk 1. */
private class FakeHindiTranscriber(private val chunkLengthMs: Long) : SpeechTranscriber {
    var calls = 0
    override suspend fun transcribe(audio: File, languageCode: String?): WhisperTranscript {
        val chunk = calls++
        // Each chunk's times are relative to that chunk, as a real service reports them.
        val seg = if (chunk == 0) {
            WhisperSegment(1_000, 3_000, "नमस्ते दोस्तों")
        } else {
            WhisperSegment(2_000, 4_500, "आप कैसे हैं")
        }
        return WhisperTranscript("hindi", listOf(seg), emptyList())
    }
}

/** Translates by prefixing, so the test can see which lines were translated. */
private class FakeTranslator : TextTranslator {
    val seen = mutableListOf<String>()
    override suspend fun translate(lines: List<IndexedLine>, fromLanguageName: String?, toLanguageName: String): Map<Int, String> {
        assertEquals("Hindi", fromLanguageName)
        assertEquals("English", toLanguageName)
        seen += lines.map { it.text }
        return lines.associate { it.index to "EN: ${it.text.length} chars" }
    }
}

class GenerationServiceTest {

    private val chunk = 10_000L

    @Test
    fun hindiAudioWithoutTranslationStaysHindiAndSyncedToVideoTime() = kotlinx.coroutines.runBlocking {
        val service = GenerationService(FakeHindiTranscriber(chunk), translator = null, chunkLengthMs = chunk)
        val result = service.generate(FakeChunks(2), mediaDurationMs = 20_000, targetLanguageCode = null)

        assertEquals("hi", result.languageCode)
        assertEquals("hi", result.spokenLanguageCode)
        assertTrue(!result.translated)
        val cues = SubtitleCueParser.parse(result.srt)
        // Chunk 1 starts at 10 s, so its line is at 12 s in the video.
        assertEquals(listOf(1_000L, 12_000L), cues.map { it.startMs })
        assertEquals(listOf(3_000L, 14_500L), cues.map { it.endMs })
        assertEquals(listOf("नमस्ते दोस्तों", "आप कैसे हैं"), cues.map { it.text })
    }

    @Test
    fun hindiAudioTranslatedToEnglishKeepsEveryTimestamp() = kotlinx.coroutines.runBlocking {
        val translator = FakeTranslator()
        val service = GenerationService(FakeHindiTranscriber(chunk), translator, chunkLengthMs = chunk)
        val original = GenerationService(FakeHindiTranscriber(chunk), null, chunkLengthMs = chunk)
            .generate(FakeChunks(2), 20_000, null)
        val result = service.generate(FakeChunks(2), 20_000, targetLanguageCode = "en")

        assertTrue(result.translated)
        assertEquals("en", result.languageCode)
        assertEquals(0, result.untranslatedLines)
        assertEquals(2, translator.seen.size)

        val before = SubtitleCueParser.parse(original.srt)
        val after = SubtitleCueParser.parse(result.srt)
        assertEquals(before.map { it.startMs }, after.map { it.startMs })
        assertEquals(before.map { it.endMs }, after.map { it.endMs })
        assertTrue(after.all { it.text.startsWith("EN:") })
    }

    @Test
    fun sameSpokenAndTargetLanguageIsNotTranslated() = kotlinx.coroutines.runBlocking {
        val translator = FakeTranslator()
        val service = GenerationService(FakeHindiTranscriber(chunk), translator, chunkLengthMs = chunk)
        val result = service.generate(FakeChunks(2), 20_000, targetLanguageCode = "hi")
        assertTrue(!result.translated)
        assertEquals(0, translator.seen.size)
    }

    @Test
    fun translatingWithoutAServiceFailsClearly() = kotlinx.coroutines.runBlocking {
        val service = GenerationService(FakeHindiTranscriber(chunk), translator = null, chunkLengthMs = chunk)
        try {
            service.generate(FakeChunks(2), 20_000, targetLanguageCode = "en")
            throw AssertionError("expected NOT_CONFIGURED")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.NOT_CONFIGURED, e.kind)
        }
    }

    @Test
    fun chunksAreCutAtExactOffsets() = kotlinx.coroutines.runBlocking {
        val chunks = FakeChunks(3)
        GenerationService(FakeHindiTranscriber(chunk), null, chunkLengthMs = chunk)
            .generate(chunks, 25_000, null)
        assertEquals(listOf(0L, 10_000L, 20_000L), chunks.requested)
    }
}
