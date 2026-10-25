package com.beatraxus.app.subtitles.domain

import com.beatraxus.app.subtitles.data.CachedSubtitle

/**
 * Where a video's subtitles come from, as the viewer chooses it in the subtitle sheet:
 *  - auto-generate: made from the speech in the audio track (saved in the subtitle cache under an id starting with `gen_`),
 *  - the video's own: the subtitle tracks stored inside the video file.
 *
 * Everything that reads the saved choice (the subtitle sheet and the player screen that restores a saved subtitle when a video
 * opens) read it from here, so a generated subtitle left in the cache is not put back on screen when the viewer chose
 * the video's own subtitles.
 */
object SubtitleSourcePrefs {

    /** Preference key; true = auto-generate, false = the video's own subtitles. */
    const val KEY_GENERATE = "video_subtitle_generate"
    const val GENERATED_ID_PREFIX = "gen_"

    fun generatedId(audioOrdinal: Int, targetLanguage: String?): String =
        "$GENERATED_ID_PREFIX${audioOrdinal}_${targetLanguage ?: "orig"}"

    fun isGenerated(subtitleId: String): Boolean = subtitleId.startsWith(GENERATED_ID_PREFIX)

    /** The saved subtitles that may be put on screen by themselves: generated ones only while auto-generate is chosen. */
    fun usable(cached: List<CachedSubtitle>, autoGenerate: Boolean): List<CachedSubtitle> =
        if (autoGenerate) cached else cached.filterNot { isGenerated(it.subtitleId) }
}
