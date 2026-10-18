package com.beatraxus.app.livetv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Whether the user has added at least one playlist. The video home screen shows its playlists card only while this is
 * true, so a fresh install shows nothing about it until the user adds a link or an M3U file.
 */
object LiveTvStore {

    private val _hasPlaylists = MutableStateFlow(false)
    val hasPlaylists: StateFlow<Boolean> = _hasPlaylists.asStateFlow()

    /** Reads the saved playlists once at start-up (a tiny SharedPreferences read). */
    fun init(context: Context) {
        _hasPlaylists.value = try {
            JSONArray(context.applicationContext.getSharedPreferences("livetv", Context.MODE_PRIVATE)
                .getString("playlists", "[]")).length() > 0
        } catch (e: Exception) {
            false
        }
    }

    internal fun set(hasPlaylists: Boolean) {
        _hasPlaylists.value = hasPlaylists
    }
}
