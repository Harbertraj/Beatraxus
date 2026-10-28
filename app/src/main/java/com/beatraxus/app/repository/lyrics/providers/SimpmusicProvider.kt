package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LrcParser
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.TtmlParser
import com.beatraxus.app.repository.lyrics.VideoIdResolver
import com.beatraxus.app.repository.lyrics.arr
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.int
import com.beatraxus.app.repository.lyrics.isSuccessful
import com.beatraxus.app.repository.lyrics.obj
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.beatraxus.app.repository.lyrics.str
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import kotlin.math.abs

class SimpmusicProvider : LyricsProvider {
    override val id = "simpmusic"
    override val displayName = "SimpMusic"
    override val description = "External lyrics provider API"
    override val granularity = LyricsGranularity.WORD
    // SimpMusic also supports a plain title/artist search, so it must not be skipped when the YouTube
    // video-id lookup fails (that lookup is now an optional second chance inside fetch()).
    override val requiresVideoId = false
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
        // Step A: a video id supplied by the caller is the most precise lookup.
        query.videoId?.takeIf { it.isNotBlank() }?.let { vid ->
            lookupByVideoId(query, vid)?.let { return@withContext it }
        }

        // Step B: text search with cleaned title + primary artist, then the raw tags.
        searchAndPick(query, query.cleanTitle, query.primaryArtist)?.let { return@withContext it }
        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            searchAndPick(query, query.title, query.artist)?.let { return@withContext it }
        }

        // Step C: last chance - resolve the YouTube Music id ourselves (cached) and look it up directly.
        if (query.videoId.isNullOrBlank()) {
            val resolved = withTimeoutOrNull(3_000L) {
                runCatching { VideoIdResolver.resolve(query) }.getOrNull()
            }
            if (!resolved.isNullOrBlank()) return@withContext lookupByVideoId(query, resolved)
        }

        null
    }

    private suspend fun lookupByVideoId(query: LyricsQuery, videoId: String): LyricsResult? {
        val url = "https://api-lyrics.simpmusic.org/v1/$videoId".toHttpUrlOrNull() ?: return null
        throttle()
        val body = LyricsHttp.get(url).body ?: return null
        return parseDirectResponse(query, body)
    }

    private fun parseDirectResponse(query: LyricsQuery, body: String): LyricsResult? {
        try {
            val root = JsonParser.parseString(body).asJsonObject
            if (!root.isSuccessful()) return null

            val items = dataItems(root)
            if (items.isEmpty()) return null

            // Best usable item whose duration fits (the first item is not always the one with lyrics).
            for (item in items) {
                val durSec = item.int("durationSeconds") ?: 0
                if (durSec > 0 && query.durationSec > 0 && abs(query.durationSec - durSec) > 8) continue
                extractResultFromItem(item, 0.90, query.durationMs)?.let { return it }
            }
            return null
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
            if (!root.isSuccessful()) return null

            val items = dataItems(root)
            if (items.isEmpty()) return null

            var bestResult: LyricsResult? = null
            var bestScore = 0.0

            for (item in items) {
                val durSec = item.int("durationSeconds") ?: 0
                if (durSec > 0 && query.durationSec > 0 && abs(query.durationSec - durSec) > 8) {
                    continue
                }

                val candTitle = item.str("songTitle") ?: ""
                val candArtist = item.str("artistName") ?: ""

                val score = LyricsMatcher.score(query.title, query.artist, query.durationMs, candTitle, candArtist, durSec * 1000L)
                if (score < 0.7 || score <= bestScore) continue

                // A better-matching item that has no lyrics at all must not win over one that does.
                val extracted = extractResultFromItem(item, score, query.durationMs) ?: continue
                bestScore = score
                bestResult = extracted
            }

            return bestResult
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
    }

    /** `data` is an array on the documented endpoints but tolerate a single object too. */
    private fun dataItems(root: JsonObject): List<JsonObject> {
        val data: JsonElement = root.get("data") ?: return emptyList()
        return when {
            data.isJsonArray -> data.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            data.isJsonObject -> listOf(data.asJsonObject)
            else -> emptyList()
        }
    }

    private fun extractResultFromItem(item: JsonObject, defaultScore: Double, durationMs: Long): LyricsResult? {
        // All reads are null-safe: "richSyncLyrics": null is the normal case for songs that only have
        // line-synced lyrics, and it used to throw and drop the whole result.
        val richSync = item.str("richSyncLyrics")
        if (!richSync.isNullOrBlank()) {
            val converted = convertRichSync(richSync)
            // Only accept it if it really parses; otherwise fall through to the synced / plain text
            // instead of returning unusable content that later fails validation.
            if (converted != null && LrcParser.parse(converted, durationMs).any { it.isTimed }) {
                val type = if (LrcParser.WORD_TIME_PATTERN.matcher(converted).find()) {
                    LyricsType.WORD_BY_WORD
                } else {
                    LyricsType.SYNCED
                }
                return LyricsResult(type, converted, defaultScore)
            }
        }

        val synced = item.str("syncedLyrics")
        if (!synced.isNullOrBlank()) {
            return LyricsResult(LyricsType.SYNCED, synced, defaultScore.coerceAtMost(0.85))
        }

        val plain = item.str("plainLyric") ?: item.str("plainLyrics")
        if (!plain.isNullOrBlank()) {
            return LyricsResult(LyricsType.PLAIN, plain, defaultScore.coerceAtMost(0.70))
        }

        return null
    }

    /** richSync may arrive as TTML, as enhanced LRC, or as Musixmatch-style rich-sync JSON. */
    private fun convertRichSync(richSync: String): String? {
        val trimmed = richSync.trim()
        return when {
            trimmed.startsWith("<") -> TtmlParser.parseToEnhancedLrc(trimmed)
            trimmed.startsWith("[{") || trimmed.startsWith("[ {") -> richSyncJsonToEnhancedLrc(trimmed)
            else -> trimmed
        }
    }

    private fun richSyncJsonToEnhancedLrc(json: String): String? {
        return try {
            val lines = JsonParser.parseString(json).asJsonArray
            val sb = StringBuilder()
            for (el in lines) {
                if (!el.isJsonObject) continue
                val line = el.asJsonObject
                val start = line.get("ts")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
                val words = line.arr("l") ?: continue
                sb.append('[').append(lrcTime(start)).append(']')
                for (w in words) {
                    if (!w.isJsonObject) continue
                    val word = w.asJsonObject
                    val text = word.str("c") ?: continue
                    val offset = word.get("o")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                    if (text.isBlank()) { sb.append(text); continue }
                    sb.append('<').append(lrcTime(start + offset)).append('>').append(text)
                }
                sb.append('\n')
            }
            sb.toString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun lrcTime(seconds: Double): String {
        val totalCs = (seconds * 100).toLong().coerceAtLeast(0L)
        val m = totalCs / 6000
        val s = (totalCs / 100) % 60
        val cs = totalCs % 100
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
    }
}
