package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Cuts the audio of the chosen track into pieces for the speech service. */
interface AudioChunkProvider {
    /**
     * Audio from exactly [startMs] to [startMs] + [lengthMs] of the video's timeline, as a mono WAV file the caller
     * deletes. Returns null when there is no audio at or after [startMs] (the end of the video was reached).
     */
    suspend fun extract(startMs: Long, lengthMs: Long): File?
}

interface SpeechTranscriber {
    /** [languageCode] is a hint once the language is known; null lets the service detect it. */
    suspend fun transcribe(audio: File, languageCode: String?): WhisperTranscript
}

data class IndexedLine(val index: Int, val text: String)

interface TextTranslator {
    /** Translates each line. A line the service did not answer for is simply missing from the map. */
    suspend fun translate(lines: List<IndexedLine>, fromLanguageName: String?, toLanguageName: String): Map<Int, String>
}

data class GeneratedTrack(
    /** ISO code of the spoken language, or null when the service did not say. */
    val languageCode: String?,
    val cues: List<SubtitleCue>
)

/**
 * Generates subtitles from audio: cuts it into chunks, recognises each, and merges the results on the video timeline.
 *
 * Chunks are cut at exact multiples of [chunkLengthMs], so a cue found at `t` seconds inside chunk `n` is at
 * `n * chunkLengthMs + t` in the video. That single rule is what keeps the subtitles in step with the audio.
 */
class AutoSubtitleGenerator(private val chunkLengthMs: Long = DEFAULT_CHUNK_MS) {

    /**
     * @param mediaDurationMs length of the video, or 0 when unknown (then chunks are read until the audio runs out).
     * @param onProgress called with (chunks finished, chunks in total or null when unknown).
     */
    suspend fun transcribe(
        provider: AudioChunkProvider,
        transcriber: SpeechTranscriber,
        mediaDurationMs: Long,
        languageHint: String? = null,
        onProgress: (done: Int, total: Int?) -> Unit = { _, _ -> }
    ): GeneratedTrack {
        val total = if (mediaDurationMs > 0) ((mediaDurationMs + chunkLengthMs - 1) / chunkLengthMs).toInt() else null
        val raw = ArrayList<SubtitleCue>()
        var language: String? = languageHint
        var detected: String? = null
        var chunks = 0
        var start = 0L

        while (true) {
            currentCoroutineContext().ensureActive()
            if (mediaDurationMs > 0 && start >= mediaDurationMs) break
            val file = provider.extract(start, chunkLengthMs) ?: break
            try {
                onProgress(chunks, total)
                val transcript = transcriber.transcribe(file, language)
                if (detected == null && transcript.segments.isNotEmpty()) {
                    detected = WhisperLanguages.codeFor(transcript.languageName)
                    // Later chunks are told the language so one noisy chunk cannot flip it.
                    if (language == null) language = detected
                }
                raw += GeneratedCueBuilder.build(transcript, start, language ?: detected)
            } finally {
                file.delete()
            }
            chunks++
            start += chunkLengthMs
        }
        onProgress(chunks, total ?: chunks)

        if (chunks == 0) throw GenerationException(GenerationException.Kind.NO_AUDIO, "No audio found in the selected track")
        val finalLanguage = detected ?: languageHint
        val cues = GeneratedCueBuilder.polish(raw, finalLanguage)
        if (cues.isEmpty()) throw GenerationException(GenerationException.Kind.NO_SPEECH, "No speech was found in the audio")
        return GeneratedTrack(finalLanguage, cues)
    }

    companion object {
        /**
         * 10 minutes of 16 kHz mono 16-bit audio is about 19 MB, which stays under the 25 MB upload limit of
         * the OpenAI-style transcription endpoints.
         */
        const val DEFAULT_CHUNK_MS = 600_000L
    }
}

data class TranslationResult(
    val cues: List<SubtitleCue>,
    /** Lines the service never answered for; they keep their original text. */
    val untranslated: Int
)

/**
 * Translates the text of cues and nothing else. Start and end times are copied from the source cue, so the
 * translated subtitle is exactly as well in sync with the audio as the subtitle it came from.
 */
class SubtitleTranslator(
    private val translator: TextTranslator,
    private val batchSize: Int = DEFAULT_BATCH
) {

    suspend fun translate(
        cues: List<SubtitleCue>,
        fromLanguageName: String?,
        toLanguageName: String,
        toLanguageCode: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): TranslationResult {
        // A line is translated as one sentence even when it was wrapped over two lines.
        val lines = cues.mapIndexed { i, c -> IndexedLine(i, c.text.replace('\n', ' ').trim()) }
        val translated = HashMap<Int, String>()
        val batches = lines.chunked(batchSize.coerceAtLeast(1))

        batches.forEachIndexed { b, batch ->
            currentCoroutineContext().ensureActive()
            onProgress(b, batches.size)
            translateBatch(batch, translated, fromLanguageName, toLanguageName)
        }
        onProgress(batches.size, batches.size)

        val spaced = WhisperLanguages.usesSpaces(toLanguageCode)
        var untranslated = 0
        val result = cues.mapIndexed { i, cue ->
            val text = translated[i]
            if (text == null) {
                untranslated++
                cue
            } else {
                // Same start and end as the source cue: this is what keeps the translation in sync.
                cue.copy(text = if (spaced) GeneratedCueBuilder.wrap(text) else text.replace('\n', ' ').trim())
            }
        }
        return TranslationResult(result, untranslated)
    }

    /**
     * Asks for [batch]; whatever comes back missing is asked for again in a smaller batch, down to single lines.
     * A line that still fails is left in the original language rather than failing the whole subtitle.
     */
    private suspend fun translateBatch(
        batch: List<IndexedLine>,
        out: MutableMap<Int, String>,
        from: String?,
        to: String
    ) {
        if (batch.isEmpty()) return
        val answer: Map<Int, String> = try {
            translator.translate(batch, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenerationException) {
            // A wrong key, no network or a rate limit will not get better with smaller batches.
            if (e.kind != GenerationException.Kind.SERVICE && e.kind != GenerationException.Kind.BAD_RESPONSE) throw e
            emptyMap()
        }

        for (line in batch) {
            answer[line.index]?.trim()?.takeIf { it.isNotEmpty() }?.let { out[line.index] = it }
        }

        val missing = batch.filter { it.index !in out }
        when {
            missing.isEmpty() -> return
            missing.size < batch.size -> translateBatch(missing, out, from, to)
            batch.size == 1 -> return
            else -> {
                val half = batch.size / 2
                translateBatch(batch.subList(0, half), out, from, to)
                translateBatch(batch.subList(half, batch.size), out, from, to)
            }
        }
    }

    companion object {
        const val DEFAULT_BATCH = 40
    }
}
