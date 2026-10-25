package com.beatraxus.app.subtitles.generate

import com.beatraxus.app.subtitles.domain.SubtitleCue

/** Finished subtitles: SRT text in the language of [languageCode], ready for the subtitle cache. */
data class GeneratedSubtitle(
    val srt: String,
    /** Language the subtitle text is written in (the target language when translated). */
    val languageCode: String,
    /** Spoken language of the audio, when the service detected it. */
    val spokenLanguageCode: String?,
    val translated: Boolean,
    val untranslatedLines: Int,
    val cueCount: Int
)

/** Subtitles for the part of the video recognised so far, already in the final language. */
data class PartialSubtitle(
    val srt: String,
    val languageCode: String,
    /** End of the last cue: the video is covered up to here. */
    val coveredUntilMs: Long,
    val chunksDone: Int,
    val chunksTotal: Int?
)

private data class CueKey(val startMs: Long, val text: String)

/**
 * The whole "audio in, subtitles out" flow: recognise the speech, then translate it when a different language was
 * asked for. Translation keeps each cue's start and end, so the translated subtitle stays in step with the audio.
 */
class GenerationService(
    private val transcriber: SpeechTranscriber,
    private val translator: TextTranslator?,
    private val chunkLengthMs: Long = AutoSubtitleGenerator.DEFAULT_CHUNK_MS
) {

    /**
     * @param targetLanguageCode the language to write the subtitles in, or null to keep the spoken language.
     * @param onPartial called after each recognised chunk with the subtitles of everything recognised so far. When a
     *   translation is wanted, only cues not translated yet are sent to the translator, so each line is translated once.
     */
    suspend fun generate(
        audio: AudioChunkProvider,
        mediaDurationMs: Long,
        targetLanguageCode: String?,
        onProgress: (Int, Int?) -> Unit = { _, _ -> },
        onPartial: suspend (PartialSubtitle) -> Unit = {}
    ): GeneratedSubtitle {
        val translations = HashMap<CueKey, String>()
        var untranslated = 0

        /** The cues in the language the viewer asked for, and whether they were translated. */
        suspend fun render(cues: List<SubtitleCue>, spoken: String?): Pair<List<SubtitleCue>, Boolean> {
            val wantsTranslation = targetLanguageCode != null && !WhisperLanguages.sameLanguage(spoken, targetLanguageCode)
            if (!wantsTranslation) return cues to false

            val t = translator ?: throw GenerationException(GenerationException.Kind.NOT_CONFIGURED, "No translation service is configured")
            val fresh = cues.filter { CueKey(it.startMs, it.text) !in translations }
            if (fresh.isNotEmpty()) {
                val result = SubtitleTranslator(t).translate(
                    cues = fresh,
                    fromLanguageName = WhisperLanguages.displayName(spoken),
                    toLanguageName = WhisperLanguages.displayName(targetLanguageCode),
                    toLanguageCode = targetLanguageCode
                )
                fresh.forEachIndexed { i, cue -> translations[CueKey(cue.startMs, cue.text)] = result.cues[i].text }
                untranslated += result.untranslated
            }
            return cues.map { it.copy(text = translations.getValue(CueKey(it.startMs, it.text))) } to true
        }

        val track = AutoSubtitleGenerator(chunkLengthMs).transcribe(
            provider = audio,
            transcriber = transcriber,
            mediaDurationMs = mediaDurationMs,
            languageHint = null,
            onProgress = onProgress
        ) { cues, spokenSoFar, done, total ->
            val (shown, translated) = render(cues, spokenSoFar)
            onPartial(
                PartialSubtitle(
                    srt = SrtWriter.toSrt(shown),
                    languageCode = if (translated) targetLanguageCode!! else spokenSoFar ?: "und",
                    coveredUntilMs = shown.last().endMs,
                    chunksDone = done,
                    chunksTotal = total
                )
            )
        }
        val spoken = track.languageCode

        val (finalCues, translated) = render(track.cues, spoken)
        return GeneratedSubtitle(
            srt = SrtWriter.toSrt(finalCues),
            languageCode = if (translated) targetLanguageCode!! else spoken ?: "und",
            spokenLanguageCode = spoken,
            translated = translated,
            untranslatedLines = untranslated,
            cueCount = finalCues.size
        )
    }
}
