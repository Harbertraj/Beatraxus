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
import com.beatraxus.app.repository.lyrics.isSuccessful
import com.beatraxus.app.repository.lyrics.obj
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.beatraxus.app.repository.lyrics.str
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
            ?.apply {
                // An empty album / zero duration is "unknown", not a value to match against.
                if (!query.album.isNullOrBlank()) addQueryParameter("album", query.album)
                if (query.durationSec > 0) addQueryParameter("duration", query.durationSec.toString())
            }
            ?.build() ?: return null

        return fetchFromUrl(url)
    }

    private suspend fun fetchFromUrl(url: HttpUrl): LyricsResult? {
        val res = LyricsHttp.get(url)
        val body = res.body ?: return null

        try {
            val root = JsonParser.parseString(body).asJsonObject
            if (!root.isSuccessful()) return null

            // Null-safe reads: a `null` field used to throw and discard an otherwise valid result.
            val data = root.obj("data") ?: return null
            val rawLyrics = data.str("lyrics")?.takeIf { it.isNotBlank() } ?: return null
            val format = data.str("format")?.lowercase() ?: "plain"
            val syncType = data.str("syncType")?.lowercase() ?: "plain"
            val confidence = data.str("confidence")?.lowercase() ?: "medium"

            val looksLikeTtml = format == "ttml" || rawLyrics.trimStart().startsWith("<tt")
            val convertedLyrics = if (looksLikeTtml) {
                TtmlParser.parseToEnhancedLrc(rawLyrics) ?: return null
            } else {
                rawLyrics
            }

            // Decide the type from the content itself; relying only on the syncType label made timed
            // LRC with an unexpected label show up as plain, unsynced text.
            val hasWordTags = LrcParser.WORD_TIME_PATTERN.matcher(convertedLyrics).find()
            val hasLineTimes = syncType != "plain" && LrcParser.parse(convertedLyrics).any { it.isTimed }
            val type = when {
                hasWordTags -> LyricsType.WORD_BY_WORD
                hasLineTimes -> LyricsType.SYNCED
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
