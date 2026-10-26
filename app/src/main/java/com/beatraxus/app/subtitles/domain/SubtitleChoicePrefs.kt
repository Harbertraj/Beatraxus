package com.beatraxus.app.subtitles.domain

import android.content.SharedPreferences
import com.beatraxus.app.subtitles.data.CachedSubtitle

/**
 * The subtitle the viewer picked for a video, remembered so that opening the video again (or coming back to it in the
 * queue) puts the same one back instead of falling back to the video's own subtitle.
 *
 * The choice is either the id of a saved subtitle (downloaded, picked from a file, auto-generated or translated) or
 * [EMBEDDED] when the viewer picked one of the video's own tracks. No entry means the viewer never chose, and the
 * default applies: the first saved downloaded/imported subtitle, if there is one.
 */
object SubtitleChoicePrefs {

    /** Stored instead of a subtitle id when the viewer picked the video's own subtitle. */
    const val EMBEDDED = "@embedded"

    private fun key(mediaKey: String) = "video_subtitle_choice_$mediaKey"

    fun recall(prefs: SharedPreferences, mediaKey: String): String? =
        prefs.getString(key(mediaKey), null)?.takeIf { it.isNotBlank() }

    fun remember(prefs: SharedPreferences, mediaKey: String, choice: String) {
        prefs.edit().putString(key(mediaKey), choice).apply()
    }

    /**
     * The saved subtitle to put on screen when a video opens, or null when none should be loaded.
     * A remembered subtitle wins (generated and translated ones included, since the viewer picked it); otherwise the
     * first downloaded/imported one is used, as before. A remembered choice of the video's own subtitle loads nothing.
     */
    fun pickToRestore(cached: List<CachedSubtitle>, choice: String?): CachedSubtitle? = when {
        choice == EMBEDDED -> null
        choice != null -> cached.firstOrNull { it.subtitleId == choice }
            ?: SubtitleSourcePrefs.usable(cached, false).firstOrNull()
        else -> SubtitleSourcePrefs.usable(cached, false).firstOrNull()
    }
}
