package com.beatraxus.app.subtitles.generate

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
     */
    suspend fun generate(
        audio: AudioChunkProvider,
        mediaDurationMs: Long,
        targetLanguageCode: String?,
        onProgress: (Int, Int?) -> Unit = { _, _ -> }
    ): GeneratedSubtitle {
        val track = AutoSubtitleGenerator(chunkLengthMs).transcribe(audio, transcriber, mediaDurationMs, null, onProgress)
        val spoken = track.languageCode

        val wantsTranslation = targetLanguageCode != null && !WhisperLanguages.sameLanguage(spoken, targetLanguageCode)
        if (!wantsTranslation) {
            return GeneratedSubtitle(
                srt = SrtWriter.toSrt(track.cues),
                languageCode = spoken ?: "und",
                spokenLanguageCode = spoken,
                translated = false,
                untranslatedLines = 0,
                cueCount = track.cues.size
            )
        }

        val t = translator ?: throw GenerationException(GenerationException.Kind.NOT_CONFIGURED, "No translation service is configured")
        val result = SubtitleTranslator(t).translate(
            cues = track.cues,
            fromLanguageName = WhisperLanguages.displayName(spoken),
            toLanguageName = WhisperLanguages.displayName(targetLanguageCode),
            toLanguageCode = targetLanguageCode
        )
        return GeneratedSubtitle(
            srt = SrtWriter.toSrt(result.cues),
            languageCode = targetLanguageCode!!,
            spokenLanguageCode = spoken,
            translated = true,
            untranslatedLines = result.untranslated,
            cueCount = result.cues.size
        )
    }
}
