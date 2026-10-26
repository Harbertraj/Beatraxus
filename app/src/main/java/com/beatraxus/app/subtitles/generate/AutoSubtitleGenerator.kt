package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

/** One finished chunk: what was heard in it, on the video timeline (before the clean-up across chunks). */
data class ChunkResult(val index: Int, val cues: List<SubtitleCue>, val languageCode: String?)

/** What an earlier, interrupted run already recognised, so a new run only has to do the rest. */
data class GenerationCheckpoint(
    val doneChunks: Set<Int>,
    val cues: List<SubtitleCue>,
    val languageCode: String?
)

/**
 * Generates subtitles from audio: cuts it into chunks, recognises each, and merges the results on the video timeline.
 *
 * Chunks are cut at exact multiples of [chunkLengthMs], so a cue found at `t` seconds inside chunk `n` is at
 * `n * chunkLengthMs + t` in the video. That single rule is what keeps the subtitles in step with the audio, and it
 * is also why the chunks may be recognised in any order: they start where the viewer is watching and wrap round to
 * the beginning, so the part being watched is ready first.
 */
class AutoSubtitleGenerator(private val chunkLengthMs: Long = DEFAULT_CHUNK_MS) {

    /**
     * @param mediaDurationMs length of the video, or 0 when unknown (then chunks are read from the start until the audio runs out).
     * @param startFromMs the part containing this position is recognised first, then the parts after it, then the ones before it.
     * @param resume work an earlier run finished; those chunks are not recognised again.
     * @param onProgress called with (chunks finished, chunks in total or null when unknown).
     * @param onChunkStats time spent cutting and recognising one chunk, to find out where a slow run spends its time.
     * @param onChunkDone called with every chunk as soon as it is recognised, so it can be saved for a later resume.
     * @param onPartial called after every chunk that produced speech, with the cleaned-up cues of everything recognised
     *   so far (video timeline), the spoken language, the chunks finished, the chunks in total (null when unknown) and
     *   which chunks those are. This is what lets the player show subtitles long before the whole video is done.
     */
    suspend fun transcribe(
        provider: AudioChunkProvider,
        transcriber: SpeechTranscriber,
        mediaDurationMs: Long,
        languageHint: String? = null,
        startFromMs: Long = 0L,
        resume: GenerationCheckpoint? = null,
        onProgress: (done: Int, total: Int?) -> Unit = { _, _ -> },
        onChunkStats: (index: Int, extractMs: Long, recogniseMs: Long) -> Unit = { _, _, _ -> },
        onChunkDone: suspend (ChunkResult) -> Unit = {},
        onPartial: suspend (cues: List<SubtitleCue>, languageCode: String?, done: Int, total: Int?, doneChunks: Set<Int>) -> Unit =
            { _, _, _, _, _ -> }
    ): GeneratedTrack {
        val total = if (mediaDurationMs > 0) ((mediaDurationMs + chunkLengthMs - 1) / chunkLengthMs).toInt() else null
        val raw = ArrayList<SubtitleCue>(resume?.cues.orEmpty())
        val done = LinkedHashSet<Int>(resume?.doneChunks.orEmpty())
        var detected: String? = resume?.languageCode
        var language: String? = languageHint ?: detected
        var sawAudio = done.isNotEmpty()

        // With a known length the order is: the viewer's part, everything after it, then everything before it.
        val first = if (total != null) (startFromMs.coerceAtLeast(0L) / chunkLengthMs).toInt().coerceIn(0, total - 1) else 0
        var step = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val index = if (total != null) {
                if (step >= total) break
                (first + step) % total
            } else {
                step
            }
            step++
            if (index in done) continue

            val start = index * chunkLengthMs
            onProgress(done.size, total)

            val extractStarted = System.nanoTime()
            val file = provider.extract(start, chunkLengthMs)
            val extractMs = (System.nanoTime() - extractStarted) / 1_000_000L
            if (file == null) {
                // Past the end of the audio. With an unknown length that is the end of the video; with a known one
                // this part simply has nothing to hear and the others still need doing.
                if (total == null) break
                done += index
                continue
            }

            val recogniseStarted = System.nanoTime()
            val chunkCues = try {
                val transcript = transcriber.transcribe(file, language)
                if (detected == null && transcript.segments.isNotEmpty()) {
                    detected = WhisperLanguages.codeFor(transcript.languageName)
                    // Later chunks are told the language so one noisy chunk cannot flip it.
                    if (language == null) language = detected
                }
                GeneratedCueBuilder.build(transcript, start, language ?: detected)
            } finally {
                file.delete()
            }
            val recogniseMs = (System.nanoTime() - recogniseStarted) / 1_000_000L

            sawAudio = true
            raw += chunkCues
            done += index
            onChunkStats(index, extractMs, recogniseMs)
            onChunkDone(ChunkResult(index, chunkCues, language ?: detected))

            val soFar = GeneratedCueBuilder.polish(raw, language ?: detected)
            if (soFar.isNotEmpty()) onPartial(soFar, language ?: detected, done.size, total, done.toSet())
        }
        onProgress(done.size, total ?: done.size)

        if (!sawAudio) throw GenerationException(GenerationException.Kind.NO_AUDIO, "No audio found in the selected track")
        val finalLanguage = detected ?: languageHint
        val cues = GeneratedCueBuilder.polish(raw, finalLanguage)
        if (cues.isEmpty()) throw GenerationException(GenerationException.Kind.NO_SPEECH, "No speech was found in the audio")
        return GeneratedTrack(finalLanguage, cues)
    }

    companion object {
        /**
         * 3 minutes of 16 kHz mono 16-bit audio is about 5.8 MB: far under the 25 MB upload limit of the OpenAI-style
         * transcription endpoints, quick to upload on a phone connection, and recognised in seconds, so the first subtitles
         * appear almost at once instead of after a 10-minute chunk (about 19 MB) has been sent and recognised.
         */
        const val DEFAULT_CHUNK_MS = 180_000L
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

    /**
     * @param resume lines (by position in [cues]) an earlier, interrupted run already translated; they are not asked again.
     * @param onBatchDone called with the lines of every finished batch, so they can be saved and a stopped run continues
     *   from where it stopped instead of starting at part 1 again.
     */
    suspend fun translate(
        cues: List<SubtitleCue>,
        fromLanguageName: String?,
        toLanguageName: String,
        toLanguageCode: String?,
        resume: Map<Int, String> = emptyMap(),
        onBatchDone: suspend (Map<Int, String>) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): TranslationResult {
        // A line is translated as one sentence even when it was wrapped over two lines.
        val lines = cues.mapIndexed { i, c -> IndexedLine(i, c.text.replace('\n', ' ').trim()) }
        val translated = HashMap<Int, String>()
        resume.forEach { (i, text) -> if (i in lines.indices && text.isNotBlank()) translated[i] = text }
        val batches = lines.chunked(batchSize.coerceAtLeast(1))

        batches.forEachIndexed { b, batch ->
            currentCoroutineContext().ensureActive()
            val todo = batch.filter { it.index !in translated }
            // Parts an earlier run finished are skipped, so progress starts at the part it continues from.
            if (todo.isEmpty()) return@forEachIndexed
            onProgress(b, batches.size)
            translateBatch(todo, translated, fromLanguageName, toLanguageName)
            val finished = todo.filter { it.index in translated }.associate { it.index to translated.getValue(it.index) }
            if (finished.isNotEmpty()) onBatchDone(finished)
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
        val answer: Map<Int, String> = askWithRetry(batch, from, to)

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

    /**
     * One request. A wrong key, no network or an exhausted rate limit will not get better with smaller batches, and a
     * service error that keeps coming back would only turn into hundreds of single-line requests, so those stop the run
     * (what is done is saved and continues later). Only an answer that could not be read is retried with smaller batches.
     */
    private suspend fun askWithRetry(batch: List<IndexedLine>, from: String?, to: String): Map<Int, String> {
        var failure: GenerationException? = null
        repeat(SERVICE_ATTEMPTS) { attempt ->
            try {
                return translator.translate(batch, from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenerationException) {
                when (e.kind) {
                    GenerationException.Kind.BAD_RESPONSE -> return emptyMap()
                    GenerationException.Kind.SERVICE -> {
                        failure = e
                        if (attempt < SERVICE_ATTEMPTS - 1) delay(SERVICE_RETRY_DELAY_MS * (attempt + 1))
                    }
                    else -> throw e
                }
            }
        }
        throw failure ?: GenerationException(GenerationException.Kind.SERVICE, "The translation service did not answer")
    }

    companion object {
        const val DEFAULT_BATCH = 40
        private const val SERVICE_ATTEMPTS = 3
        private const val SERVICE_RETRY_DELAY_MS = 2_000L
    }
}
