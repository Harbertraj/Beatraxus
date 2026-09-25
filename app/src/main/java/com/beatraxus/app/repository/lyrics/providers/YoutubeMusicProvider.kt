package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class YoutubeMusicProvider : LyricsProvider {
    override val id = "youtube_music"
    override val displayName = "YouTube Music"
    override val description = "Synced lyrics from YouTube (Disabled)"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = true
    override val experimental = false
    
    // Paxsenix removed their YouTube endpoints, disabling for now.
    // InnerTube lyrics support would require `next` and `browse` calls.
    override val isConfigured = false

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val videoId = query.videoId
        if (videoId.isNullOrBlank()) return@withContext null

        val url = "https://lyrics.paxsenix.org/youtube/lyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("id", videoId)
            ?.build() ?: return@withContext null

        val res = LyricsHttp.get(url)
        val body = res.body ?: return@withContext null
        if (body.isBlank()) return@withContext null

        val timestampRegex = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
        val distinctTimestamps = timestampRegex.findAll(body).map { it.value }.toSet()
        if (distinctTimestamps.size < 2) return@withContext null

        LyricsResult(LyricsType.SYNCED, body.trim(), 0.85)
    }
}
