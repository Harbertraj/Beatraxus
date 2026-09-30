package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LrcParser
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.TtmlParser
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BinilyricsProvider : LyricsProvider {
    override val id = "binilyrics"
    override val displayName = "BiniLyrics"
    override val description = "Apple Music word timings (TTML)"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val firstAttempt = fetchForTitleArtist(query, query.cleanTitle, query.primaryArtist)
        if (firstAttempt != null) return@withContext firstAttempt

        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            return@withContext fetchForTitleArtist(query, query.title, query.artist)
        }

        null
    }

    private suspend fun fetchForTitleArtist(query: LyricsQuery, title: String, artist: String): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null

        val urlBuilder = "https://lyrics-api.binimum.org/".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track", title)
            ?.addQueryParameter("artist", artist)

        if (!query.album.isNullOrBlank()) {
            urlBuilder?.addQueryParameter("album", query.album)
        }
        if (query.durationSec > 0) {
            urlBuilder?.addQueryParameter("duration", query.durationSec.toString())
        }

        val url = urlBuilder?.build() ?: return null

        val res = LyricsHttp.get(url)
        val body = res.body ?: return null

        try {
            val root = JsonParser.parseString(body).asJsonObject
            val results = root.getAsJsonArray("results") ?: return null
            if (results.size() == 0) return null

            val lyricsUrlStr = results.get(0).asJsonObject.get("lyricsUrl")?.asString ?: return null
            val lyricsUrl = lyricsUrlStr.toHttpUrlOrNull() ?: return null

            val ttmlRes = LyricsHttp.get(lyricsUrl)
            val ttml = ttmlRes.body ?: return null

            val lrc = TtmlParser.parseToEnhancedLrc(ttml) ?: return null
            val hasWordTags = LrcParser.WORD_TIME_PATTERN.matcher(lrc).find() || ttml.contains(Regex("<\\d{1,3}:\\d{2}"))
            val type = if (hasWordTags) LyricsType.WORD_BY_WORD else LyricsType.SYNCED

            return LyricsResult(type, lrc, 0.90)
        } catch (e: LyricsTransientException) {
            throw e
        } catch (e: Exception) {
            Log.w("LyricsProvider", "$id fetch failed: ${e.message}")
            return null
        }
    }
}
