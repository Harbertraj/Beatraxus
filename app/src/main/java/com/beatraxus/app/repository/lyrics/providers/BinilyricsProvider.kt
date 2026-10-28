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
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.TtmlParser
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.beatraxus.app.repository.lyrics.jArr
import com.beatraxus.app.repository.lyrics.jLong
import com.beatraxus.app.repository.lyrics.jStr
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BinilyricsProvider : LyricsProvider {
    private companion object {
        const val MAX_TTML_DOWNLOADS = 3
    }

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
            val results = JsonParser.parseString(body).jArr("results") ?: return null
            if (results.size() == 0) return null

            // Rank the candidates by their metadata FIRST and only download the TTML of the best few.
            // It used to download and parse the TTML of every result one after another, which blew
            // through the provider's 8 s budget whenever the server returned more than a couple.
            data class Candidate(val lyricsUrl: okhttp3.HttpUrl, val score: Double)

            val candidates = results.mapNotNull { item ->
                val lyricsUrl = item.jStr("lyricsUrl")?.toHttpUrlOrNull() ?: return@mapNotNull null
                val candTrack = item.jStr("track") ?: item.jStr("title") ?: title
                val candArtist = item.jStr("artist") ?: artist
                val candDurationMs = item.jLong("duration")?.let { it * 1000L }
                Candidate(
                    lyricsUrl,
                    LyricsMatcher.score(query.title, query.artist, query.durationMs, candTrack, candArtist, candDurationMs)
                )
            }.sortedByDescending { it.score }.take(MAX_TTML_DOWNLOADS)

            for (cand in candidates) {
                val ttml = LyricsHttp.get(cand.lyricsUrl).body ?: continue

                val lrc = TtmlParser.parseToEnhancedLrc(ttml) ?: continue
                val hasWordTags = LrcParser.WORD_TIME_PATTERN.matcher(lrc).find() || ttml.contains(Regex("<\\d{1,3}:\\d{2}"))
                val type = if (hasWordTags) LyricsType.WORD_BY_WORD else LyricsType.SYNCED

                return LyricsResult(type, lrc, cand.score)
            }

            return null
        } catch (e: LyricsTransientException) {
            throw e
        } catch (e: Exception) {
            Log.w("LyricsProvider", "$id fetch failed: ${e.message}")
            return null
        }
    }
}
