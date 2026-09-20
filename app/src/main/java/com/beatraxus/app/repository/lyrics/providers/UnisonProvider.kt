package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LrcParser
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.TtmlParser
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UnisonProvider : LyricsProvider {
    override val id = "unison"
    override val displayName = "Unison"
    override val description = "Metadata lookup for synced lyrics"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        if (query.videoId != null) {
            val vUrl = "https://unison.boidu.dev/lyrics".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("v", query.videoId)
                ?.build()
            if (vUrl != null) {
                val vRes = fetchFromUrl(vUrl)
                if (vRes != null) return@withContext vRes
            }
        }

        val firstAttempt = fetchForTitleArtist(query, query.cleanTitle, query.primaryArtist)
        if (firstAttempt != null) return@withContext firstAttempt

        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            return@withContext fetchForTitleArtist(query, query.title, query.artist)
        }

        null
    }

    private suspend fun fetchForTitleArtist(query: LyricsQuery, title: String, artist: String): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null

        val url = "https://unison.boidu.dev/lyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("song", title)
            ?.addQueryParameter("artist", artist)
            ?.addQueryParameter("album", query.album ?: "")
            ?.addQueryParameter("duration", query.durationSec.toString())
            ?.build() ?: return null

        return fetchFromUrl(url)
    }

    private suspend fun fetchFromUrl(url: HttpUrl): LyricsResult? {
        val res = LyricsHttp.get(url)
        val body = res.body ?: return null

        try {
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("success")?.asBoolean != true) return null

            val data = root.getAsJsonObject("data") ?: return null
            val rawLyrics = data.get("lyrics")?.asString ?: return null
            val format = data.get("format")?.asString?.lowercase() ?: "plain"
            val syncType = data.get("syncType")?.asString?.lowercase() ?: "plain"
            val confidence = data.get("confidence")?.asString?.lowercase() ?: "medium"

            val convertedLyrics = when (format) {
                "ttml" -> TtmlParser.parseToEnhancedLrc(rawLyrics) ?: return null
                else -> rawLyrics
            }

            val hasWordTags = LrcParser.WORD_TIME_PATTERN.matcher(convertedLyrics).find()
            val type = when {
                syncType == "richsync" || hasWordTags -> LyricsType.WORD_BY_WORD
                syncType == "linesync" -> LyricsType.SYNCED
                else -> LyricsType.PLAIN
            }

            val score = when (confidence) {
                "high" -> 0.95
                "medium" -> 0.85
                "low" -> 0.70
                else -> 0.80
            }

            return LyricsResult(type, convertedLyrics, score)
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
    }
}
