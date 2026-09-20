package com.beatraxus.app.repository.lyrics.providers

import android.util.Base64
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

class KugouProvider : LyricsProvider {
    override val id = "kugou"
    override val displayName = "KuGou"
    override val description = "Chinese music service (Synced)"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = query.cleanTitle
        val primaryArtist = query.primaryArtist
        if (cleanTitle.isBlank() || primaryArtist.isBlank()) return@withContext null

        val keyword = "$primaryArtist - $cleanTitle"

        val searchUrl = "http://mobileservice.kugou.com/api/v3/lyric/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("version", "9.1.0")
            ?.addQueryParameter("keyword", keyword)
            ?.addQueryParameter("duration", query.durationMs.toString())
            ?.build() ?: return@withContext null

        val searchRes = LyricsHttp.get(searchUrl)
        val body = searchRes.body ?: return@withContext null

        val root = JsonParser.parseString(body).asJsonObject
        if (root.get("errcode")?.asInt != 0) return@withContext null

        val data = root.getAsJsonObject("data") ?: return@withContext null
        val infoArray = data.getAsJsonArray("info") ?: return@withContext null
        if (infoArray.size() == 0) return@withContext null

        var chosenId: String? = null
        var chosenAccessKey: String? = null
        var bestMatchScore = 0.0

        val maxCheck = minOf(5, infoArray.size())
        for (i in 0 until maxCheck) {
            val item = infoArray.get(i).asJsonObject
            val rawDuration = item.get("duration")?.asLong ?: 0L
            val candDurationMs = if (rawDuration < 10000L) rawDuration * 1000L else rawDuration

            val durationDiffSec = abs(query.durationMs - candDurationMs) / 1000.0
            if (durationDiffSec > 8.0) continue

            val songName = item.get("songname")?.asString ?: ""
            val singerName = item.get("singername")?.asString ?: ""

            val score = LyricsMatcher.score(
                queryTitle = query.title,
                queryArtist = query.artist,
                queryDurationMs = query.durationMs,
                candTitle = songName,
                candArtist = singerName,
                candDurationMs = candDurationMs
            )

            if (score >= 0.7 && score > bestMatchScore) {
                bestMatchScore = score
                chosenId = item.get("id")?.asString
                chosenAccessKey = item.get("accesskey")?.asString
            }
        }

        if (chosenId == null || chosenAccessKey == null) return@withContext null

        val downloadUrl = "http://lyrics.kugou.com/download".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("ver", "1")
            ?.addQueryParameter("client", "pc")
            ?.addQueryParameter("id", chosenId)
            ?.addQueryParameter("accesskey", chosenAccessKey)
            ?.addQueryParameter("fmt", "lrc")
            ?.addQueryParameter("charset", "utf8")
            ?.build() ?: return@withContext null

        val dlRes = LyricsHttp.get(downloadUrl)
        val dlBody = dlRes.body ?: return@withContext null

        val dlRoot = JsonParser.parseString(dlBody).asJsonObject
        if (dlRoot.get("status")?.asInt != 200) return@withContext null

        val b64Content = dlRoot.get("content")?.asString ?: return@withContext null
        val lrcText = String(Base64.decode(b64Content, Base64.DEFAULT))

        val cleanText = lrcText.lines()
            .filter { !it.matches(Regex("\\[(ti|ar|al|by|offset):.*?\\]")) }
            .joinToString("\n")
            .trim()

        if (cleanText.isEmpty()) return@withContext null

        LyricsResult(LyricsType.SYNCED, cleanText, bestMatchScore)
    }
}
