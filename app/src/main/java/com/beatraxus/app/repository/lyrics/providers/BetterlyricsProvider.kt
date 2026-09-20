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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BetterlyricsProvider : LyricsProvider {
    override val id = "betterlyrics"
    override val displayName = "BetterLyrics"
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

        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("s", title)
            ?.addQueryParameter("a", artist)
            ?.addQueryParameter("al", query.album ?: "")
            ?.addQueryParameter("d", query.durationSec.toString())
            ?.build() ?: return null

        val res = LyricsHttp.get(url)
        val body = res.body ?: return null

        try {
            val root = JsonParser.parseString(body).asJsonObject
            val ttml = root.get("ttml")?.asString ?: return null
            val rawScore = root.get("score")?.asDouble ?: 100.0
            val score = (rawScore / 100.0).coerceIn(0.0, 1.0)

            val lrc = TtmlParser.parseToEnhancedLrc(ttml) ?: return null
            val hasWordTags = LrcParser.WORD_TIME_PATTERN.matcher(lrc).find() || ttml.contains(Regex("<\\d{1,3}:\\d{2}"))
            val type = if (hasWordTags) LyricsType.WORD_BY_WORD else LyricsType.SYNCED

            return LyricsResult(type, lrc, score)
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
    }
}
