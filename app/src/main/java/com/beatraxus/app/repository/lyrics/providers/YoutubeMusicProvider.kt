package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.InnerTube
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.ytArr
import com.beatraxus.app.repository.lyrics.ytAt
import com.beatraxus.app.repository.lyrics.ytStr
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lyrics shown in the YouTube Music app, fetched straight from InnerTube (the Paxsenix YouTube
 * endpoint this provider used to call no longer exists):
 *   1. `next` for the song's videoId  -> the id of its "Lyrics" tab (browseId "MPLYt...")
 *   2. `browse` for that id           -> a musicDescriptionShelfRenderer holding the lyric text
 * YouTube Music lyrics are unsynced, so the result is always PLAIN.
 */
class YoutubeMusicProvider : LyricsProvider {
    override val id = "youtube_music"
    override val displayName = "YouTube Music"
    override val description = "Plain lyrics from YouTube Music"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = true
    override val experimental = false
    override val isConfigured = true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val videoId = query.videoId
        if (videoId.isNullOrBlank()) return@withContext null

        val browseId = findLyricsBrowseId(videoId) ?: return@withContext null
        val text = fetchLyricsText(browseId) ?: return@withContext null

        LyricsResult(LyricsType.PLAIN, text, 0.75)
    }

    private suspend fun findLyricsBrowseId(videoId: String): String? {
        val body = JsonObject().apply {
            add("context", InnerTube.webRemixContext())
            addProperty("videoId", videoId)
            addProperty("isAudioOnly", true)
        }
        val raw = InnerTube.postMusic("next", body).body ?: return null

        return try {
            val root = JsonParser.parseString(raw)
            val tabs = root.ytArr(
                "contents", "singleColumnMusicWatchNextResultsRenderer",
                "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs"
            ) ?: return null

            // The lyrics tab is normally index 1, but identify it by its browse id rather than position.
            // A song without lyrics has the tab marked "unselectable" and no browse endpoint.
            tabs.asSequence()
                .mapNotNull { it.ytStr("tabRenderer", "endpoint", "browseEndpoint", "browseId") }
                .firstOrNull { it.startsWith("MPLY") }
        } catch (e: Exception) {
            Log.w("LyricsProvider", "$id next parse failed: ${raw.take(300)}")
            null
        }
    }

    private suspend fun fetchLyricsText(browseId: String): String? {
        val body = JsonObject().apply {
            add("context", InnerTube.webRemixContext())
            addProperty("browseId", browseId)
        }
        val raw = InnerTube.postMusic("browse", body).body ?: return null

        return try {
            val shelf = findDescriptionShelf(JsonParser.parseString(raw)) ?: return null
            val runs = shelf.ytArr("description", "runs") ?: return null
            val sb = StringBuilder()
            for (run in runs) sb.append(run.ytStr("text").orEmpty())
            sb.toString()
                .replace("\r\n", "\n")
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("LyricsProvider", "$id browse parse failed: ${raw.take(300)}")
            null
        }
    }

    /** The shelf's position in the response changes between layouts, so search for it by name. */
    private fun findDescriptionShelf(el: JsonElement?): JsonElement? {
        when {
            el == null || el.isJsonNull -> return null
            el.isJsonObject -> {
                val obj = el.asJsonObject
                obj.ytAt("musicDescriptionShelfRenderer")?.let { return it }
                for (entry in obj.entrySet()) findDescriptionShelf(entry.value)?.let { return it }
            }
            el.isJsonArray -> for (child in el.asJsonArray) findDescriptionShelf(child)?.let { return it }
        }
        return null
    }
}
