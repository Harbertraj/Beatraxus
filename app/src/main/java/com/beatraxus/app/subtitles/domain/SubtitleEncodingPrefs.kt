package com.beatraxus.app.subtitles.domain

import android.content.SharedPreferences

/** The chosen text encoding for subtitle files (video feature 23). Stored as a charset name, or "AUTO". */
object SubtitleEncodingPrefs {
    private const val KEY = "video_subtitle_encoding"

    fun read(p: SharedPreferences): String {
        val saved = p.getString(KEY, SubtitleEncodings.AUTO) ?: SubtitleEncodings.AUTO
        // A saved charset this device cannot decode behaves like Auto-detect instead of breaking subtitle loading.
        return if (saved == SubtitleEncodings.AUTO || SubtitleEncodings.charsetFor(saved) != null) saved else SubtitleEncodings.AUTO
    }

    fun write(p: SharedPreferences, id: String) {
        p.edit().putString(KEY, id).apply()
    }
}
