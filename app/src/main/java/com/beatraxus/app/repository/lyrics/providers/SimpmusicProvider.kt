package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.TtmlParser
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

class SimpmusicProvider : LyricsProvider {
    override val id = "simpmusic"
    override val displayName = "SimpMusic"
    override val description = "External lyrics provider API"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = true
    override val experimental = false
    override val isConfigured = true

    companion object {
        private val mutex = Mutex()
        private var lastCallTime = 0L

        private suspend fun throttle() {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val elapsed = now - lastCallTime
                if (elapsed < 2100) {
                    delay(2100 - elapsed)
                }
                lastCallTime = System.currentTimeMillis()
            }
        }
    }

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        // Step A: Direct lookup by videoId
        val videoId = query.videoId
        if (!videoId.isNullOrBlank()) {
            val directUrl = "https://api-lyrics.simpmusic.org/v1/$videoId".toHttpUrlOrNull()
            if (directUrl != null) {
                throttle()
                val directRes = LyricsHttp.get(directUrl)
                val directBody = directRes.body
                if (directBody != null) {
                    val result = parseDirectResponse(query, directBody)
                    if (result != null) return@withContext result
                }
            }
        }

        // Step B: Search with cleanTitle + primaryArtist
        val firstSearch = searchAndPick(query, query.cleanTitle, query.primaryArtist)
        if (firstSearch != null) return@withContext firstSearch

        // Retry with original title + artist
        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            return@withContext searchAndPick(query, query.title, query.artist)
        }

        null
    }

    private fun parseDirectResponse(query: LyricsQuery, body: String): LyricsResult? {
        try {
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("success")?.asBoolean != true) return null

            val data = root.getAsJsonArray("data") ?: return null
            if (data.size() == 0) return null

            val item = data[0].asJsonObject
            val durSec = item.get("durationSeconds")?.asInt ?: 0
            if (durSec > 0 && query.durationSec > 0 && abs(query.durationSec - durSec) > 8) {
                return null
            }

            return extractResultFromItem(item, 0.90)
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
    }

    private suspend fun searchAndPick(query: LyricsQuery, title: String, artist: String): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null

        val url = "https://api-lyrics.simpmusic.org/v1/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", "$title $artist".trim())
            ?.addQueryParameter("limit", "5")
            ?.build() ?: return null

        throttle()
        val res = LyricsHttp.get(url)
        val body = res.body ?: return null

        try {
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("success")?.asBoolean != true) return null

            val data = root.getAsJsonArray("data") ?: return null
            if (data.size() == 0) return null

            var bestItem: JsonObject? = null
            var bestScore = 0.0

            for (i in 0 until data.size()) {
                val item = data[i].asJsonObject
                val durSec = item.get("durationSeconds")?.asInt ?: 0
                if (durSec > 0 && query.durationSec > 0 && abs(query.durationSec - durSec) > 8) {
                    continue
                }

                val candTitle = item.get("songTitle")?.asString ?: ""
                val candArtist = item.get("artistName")?.asString ?: ""

                val score = LyricsMatcher.score(query.title, query.artist, query.durationMs, candTitle, candArtist, durSec * 1000L)
                if (score >= 0.7 && score > bestScore) {
                    bestScore = score
                    bestItem = item
                }
            }

            return bestItem?.let { extractResultFromItem(it, bestScore) }
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
    }

    private fun extractResultFromItem(item: JsonObject, defaultScore: Double): LyricsResult? {
        val richSync = item.get("richSyncLyrics")?.asString
        if (!richSync.isNullOrBlank()) {
            val converted = if (richSync.trim().startsWith("<")) {
                TtmlParser.parseToEnhancedLrc(richSync) ?: richSync
            } else {
                richSync
            }
            return LyricsResult(LyricsType.WORD_BY_WORD, converted, defaultScore)
        }

        val synced = item.get("syncedLyrics")?.asString
        if (!synced.isNullOrBlank()) {
            return LyricsResult(LyricsType.SYNCED, synced, defaultScore.coerceAtMost(0.85))
        }

        val plain = item.get("plainLyric")?.asString
        if (!plain.isNullOrBlank()) {
            return LyricsResult(LyricsType.PLAIN, plain, defaultScore.coerceAtMost(0.70))
        }

        return null
    }
}
