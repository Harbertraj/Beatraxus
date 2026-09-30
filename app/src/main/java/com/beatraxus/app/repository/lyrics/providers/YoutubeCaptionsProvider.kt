package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.LyricsValidator
import com.beatraxus.app.repository.lyrics.VideoIdResolver
import com.beatraxus.app.repository.lyrics.durationSec
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import kotlin.math.abs

class YoutubeCaptionsProvider : LyricsProvider {
    override val id = "youtube_captions"
    override val displayName = "YouTube Captions"
    override val description = "Auto-generated or custom captions"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = true
    override val experimental = false
    override val isConfigured = true

    private data class ClientConfig(val name: String, val version: String)

    private val CLIENTS = listOf(
        ClientConfig("WEB_REMIX", "1.20240101.01.00"),
        ClientConfig("ANDROID_MUSIC", "6.20.51"),
        ClientConfig("IOS", "19.09.3"),
        ClientConfig("ANDROID", "19.09.37")
    )

    private data class ParsedEvent(val startTimeMs: Long, val text: String)

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val videoId = query.videoId ?: VideoIdResolver.resolve(query)
        if (videoId.isNullOrBlank()) return@withContext null

        for (client in CLIENTS) {
            try {
                val playerUrl = "https://www.youtube.com/youtubei/v1/player".toHttpUrlOrNull() ?: continue
                val jsonBody = """
                    {
                      "context": {
                        "client": {
                          "clientName": "${client.name}",
                          "clientVersion": "${client.version}"
                        }
                      },
                      "videoId": "$videoId"
                    }
                """.trimIndent()

                val mediaType = "application/json".toMediaType()
                val requestBody = jsonBody.toRequestBody(mediaType)

                val res = LyricsHttp.post(playerUrl, requestBody, timeoutMs = 8000L)
                if (res.code == 403) continue // try next client or clean miss
                val body = res.body ?: continue

                val root = JsonParser.parseString(body).asJsonObject

                // Duration check: require <= 3s duration difference
                val videoDetails = root.getAsJsonObject("videoDetails")
                val videoLengthSec = videoDetails?.get("lengthSeconds")?.asLong ?: 0L
                if (query.durationSec > 0 && videoLengthSec > 0) {
                    val diff = abs(videoLengthSec - query.durationSec)
                    if (diff > 3L) {
                        Log.d("YoutubeCaptionsProvider", "Video duration mismatch: $videoLengthSec vs song ${query.durationSec}")
                        continue
                    }
                }

                val captionTracks = root.getAsJsonObject("captions")
                    ?.getAsJsonObject("playerCaptionsTracklistRenderer")
                    ?.getAsJsonArray("captionTracks") ?: continue

                if (captionTracks.size() == 0) continue

                var selectedTrackUrl: String? = null
                var isManual = false

                // Prefer manual track matching song language, non-auto-translated
                for (trackElement in captionTracks) {
                    if (!trackElement.isJsonObject) continue
                    val track = trackElement.asJsonObject
                    val url = track.get("baseUrl")?.asString ?: continue
                    val kind = track.get("kind")?.asString ?: ""
                    val vssId = track.get("vssId")?.asString ?: ""

                    // Ignore translated or auto-translated tracks
                    if (vssId.startsWith("a.") || vssId.contains(".en") && !vssId.startsWith("a.")) {
                        if (kind == "asr") continue
                    }

                    if (kind != "asr") {
                        selectedTrackUrl = url
                        isManual = true
                        break
                    }
                }

                // Fallback pass: take first non-translated ASR track if no manual track found
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

                val baseUrl = selectedTrackUrl ?: continue
                val json3Url = baseUrl.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("fmt", "json3")
                    ?.build() ?: continue

                val json3Res = LyricsHttp.get(json3Url, timeoutMs = 8000L)
                val json3Body = json3Res.body ?: continue

                val events = JsonParser.parseString(json3Body).asJsonObject.getAsJsonArray("events") ?: continue
                val rawEvents = mutableListOf<ParsedEvent>()

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
                    rawEvents.add(ParsedEvent(tStartMs, cleanText))
                }

                val deduplicated = deduplicateEvents(rawEvents)
                if (deduplicated.size < 4) continue

                val lrcLines = deduplicated.map { "${formatLrcTime(it.startTimeMs)} ${it.text}" }
                val lrcContent = lrcLines.joinToString("\n")

                val validation = LyricsValidator.validate(lrcContent, query.durationMs, LyricsType.SYNCED)
                if (!validation.isValid) continue

                val baseScore = if (isManual) 0.80 else 0.50
                val finalScore = maxOf(0.0, baseScore - validation.penalty)

                return@withContext LyricsResult(LyricsType.SYNCED, lrcContent, finalScore)
            } catch (e: LyricsTransientException) {
                throw e
            } catch (e: Exception) {
                Log.w("YoutubeCaptionsProvider", "Failed client ${client.name} for $videoId: ${e.message}")
            }
        }

        null
    }

    private fun deduplicateEvents(events: List<ParsedEvent>): List<ParsedEvent> {
        if (events.isEmpty()) return emptyList()

        val result = mutableListOf<ParsedEvent>()
        for (event in events) {
            if (result.isEmpty()) {
                result.add(event)
                continue
            }

            val lastIdx = result.size - 1
            val last = result[lastIdx]

            when {
                event.text == last.text -> {
                    // Exact duplicate, skip
                    continue
                }
                event.text.startsWith(last.text) -> {
                    // Event expands last line (ASR rolling caption), replace last
                    result[lastIdx] = event
                }
                last.text.startsWith(event.text) -> {
                    // Prefix duplicate, skip
                    continue
                }
                else -> {
                    result.add(event)
                }
            }
        }

        return result
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
