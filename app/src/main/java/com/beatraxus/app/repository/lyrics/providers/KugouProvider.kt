package com.beatraxus.app.repository.lyrics.providers

import android.util.Base64
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttpClient
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class KugouProvider : LyricsProvider {
    override val id = "kugou"
    override val displayName = "KuGou"
    override val description = "Chinese music service (Synced)"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        try {
            if (query.title.isBlank() || query.artist.isBlank()) return@withContext null

            val keyword = "${query.artist} - ${query.title}"
            val searchUrl = "http://mobileservice.kugou.com/api/v3/lyric/search?version=9.1.0&keyword=$keyword&duration=${query.durationMs}"
            
            val req = Request.Builder().url(searchUrl).build()
            val resp = LyricsHttpClient.client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null

            val body = resp.body?.string() ?: return@withContext null
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("errcode")?.asInt != 0) return@withContext null

            val data = root.getAsJsonObject("data") ?: return@withContext null
            val infoArray = data.getAsJsonArray("info") ?: return@withContext null
            if (infoArray.size() == 0) return@withContext null

            val first = infoArray.get(0).asJsonObject
            val kId = first.get("id")?.asString ?: return@withContext null
            val accesskey = first.get("accesskey")?.asString ?: return@withContext null

            val downloadUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$kId&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val dlReq = Request.Builder().url(downloadUrl).build()
            val dlResp = LyricsHttpClient.client.newCall(dlReq).execute()
            if (!dlResp.isSuccessful) return@withContext null

            val dlBody = dlResp.body?.string() ?: return@withContext null
            val dlRoot = JsonParser.parseString(dlBody).asJsonObject
            if (dlRoot.get("status")?.asInt != 200) return@withContext null
            val b64Content = dlRoot.get("content")?.asString ?: return@withContext null

            val lrcText = String(Base64.decode(b64Content, Base64.DEFAULT))
            
            // Strip header tags
            val cleanText = lrcText.lines().filter { !it.matches(Regex("\\[(ti|ar|al|by|offset):.*?\\]")) }.joinToString("\n").trim()
            if (cleanText.isEmpty()) return@withContext null

            LyricsResult(LyricsType.SYNCED, cleanText, 1.0)
        } catch (e: Exception) {
            null
        }
    }
}
