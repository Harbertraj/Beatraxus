package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.VideoIdResolver
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

class YoutubeCaptionsProvider : LyricsProvider {
    override val id = "youtube_captions"
    override val displayName = "YouTube Captions"
    override val description = "Auto-generated or custom captions"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = true
    override val experimental = false
    override val isConfigured = true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val videoId = query.videoId ?: VideoIdResolver.resolve(query)
        if (videoId.isNullOrBlank()) return@withContext null

        try {
            val playerUrl = "https://www.youtube.com/youtubei/v1/player".toHttpUrlOrNull() ?: return@withContext null
            val jsonBody = """
                {
                  "context": {
                    "client": {
                      "clientName": "WEB_REMIX",
                      "clientVersion": "1.20240101.01.00"
                    }
                  },
                  "videoId": "$videoId"
                }
            """.trimIndent()

            val mediaType = "application/json".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)

            val res = LyricsHttp.post(playerUrl, requestBody, timeoutMs = 8000L)
            val body = res.body ?: return@withContext null

            val root = JsonParser.parseString(body).asJsonObject
            val captionTracks = root.getAsJsonObject("captions")
                ?.getAsJsonObject("playerCaptionsTracklistRenderer")
                ?.getAsJsonArray("captionTracks") ?: return@withContext null

            if (captionTracks.size() == 0) return@withContext null

            var selectedTrackUrl: String? = null
            var isManual = false

            // First pass: find manual caption track (kind != "asr")
            for (trackElement in captionTracks) {
                if (!trackElement.isJsonObject) continue
                val track = trackElement.asJsonObject
                val url = track.get("baseUrl")?.asString ?: continue
                val kind = track.get("kind")?.asString ?: ""

                if (kind != "asr") {
                    selectedTrackUrl = url
                    isManual = true
                    break
                }
            }

            // Fallback pass: take first ASR track if no manual track found
            if (selectedTrackUrl == null) {
                for (trackElement in captionTracks) {
                    if (!trackElement.isJsonObject) continue
                    val track = trackElement.asJsonObject
                    val url = track.get("baseUrl")?.asString ?: continue
                    selectedTrackUrl = url
                    isManual = false
                    break
                }
            }

            val baseUrl = selectedTrackUrl ?: return@withContext null
            val json3Url = baseUrl.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("fmt", "json3")
                ?.build() ?: return@withContext null

            val json3Res = LyricsHttp.get(json3Url, timeoutMs = 8000L)
            val json3Body = json3Res.body ?: return@withContext null

            val events = JsonParser.parseString(json3Body).asJsonObject.getAsJsonArray("events") ?: return@withContext null
            val lrcLines = mutableListOf<String>()

            for (eventElement in events) {
                if (!eventElement.isJsonObject) continue
                val event = eventElement.asJsonObject
                val tStartMs = event.get("tStartMs")?.asLong ?: 0L
                val segs = event.getAsJsonArray("segs") ?: continue

                val sb = StringBuilder()
                for (segElement in segs) {
                    if (!segElement.isJsonObject) continue
                    val seg = segElement.asJsonObject
                    val utf8 = seg.get("utf8")?.asString ?: ""
                    sb.append(utf8)
                }

                val cleanText = cleanCaptionText(sb.toString())
                if (cleanText.isBlank()) continue

                val timestamp = formatLrcTime(tStartMs)
                lrcLines.add("$timestamp $cleanText")
            }

            if (lrcLines.size < 4) return@withContext null

            val lrcContent = lrcLines.joinToString("\n")
            val score = if (isManual) 0.80 else 0.50

            return@withContext LyricsResult(LyricsType.SYNCED, lrcContent, score)
        } catch (e: LyricsTransientException) {
            throw e
        } catch (e: Exception) {
            Log.w("YoutubeCaptionsProvider", "Failed to fetch captions for $videoId: ${e.message}")
            return@withContext null
        }
    }

    private fun cleanCaptionText(rawText: String): String {
        return rawText
            .replace(Regex("""\[(?:Music|Musique|Musik|Applause|Laughter|Silence|Singing|Audio|Instrumental)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\((?:Music|Musique|Musik|Applause|Laughter|Silence|Singing|Audio|Instrumental)\)""", RegexOption.IGNORE_CASE), "")
            .replace("♪", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val hundredths = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, hundredths)
    }
}
