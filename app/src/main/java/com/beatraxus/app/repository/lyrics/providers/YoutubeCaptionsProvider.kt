package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.InnerTube
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.LyricsValidator
import com.beatraxus.app.repository.lyrics.VideoIdResolver
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.ytArr
import com.beatraxus.app.repository.lyrics.ytLong
import com.beatraxus.app.repository.lyrics.ytStr
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // Captions need the *video* version of a song (the audio-only "Topic" upload the shared resolver
    // returns has none), so this provider resolves its own candidates inside fetch().
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = true

    private class ClientConfig(
        val name: String,
        val version: String,
        val headerId: String,
        val userAgent: String,
        val extra: Map<String, String> = emptyMap()
    )

    private val CLIENTS = listOf(
        ClientConfig(
            "ANDROID_MUSIC", "6.20.51", "21",
            "com.google.android.apps.youtube.music/6.20.51 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to "30", "osName" to "Android", "osVersion" to "11")
        ),
        ClientConfig(
            "ANDROID", "19.09.37", "3",
            "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to "30", "osName" to "Android", "osVersion" to "11")
        ),
        ClientConfig(
            "IOS", "19.09.3", "5",
            "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "17.5.1.21F90")
        ),
        ClientConfig("WEB_REMIX", InnerTube.webRemixVersion(), "67", InnerTube.WEB_USER_AGENT)
    )

    private data class ParsedEvent(val startTimeMs: Long, val text: String)

    private sealed interface Outcome {
        /** This client failed or returned nothing usable - try the next client. */
        object Retry : Outcome
        /** The video itself is unusable (wrong length / no captions) - other clients will agree. */
        object GiveUp : Outcome
        class Hit(val result: LyricsResult) : Outcome
    }

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        for (videoId in candidateVideoIds(query)) {
            for (client in CLIENTS) {
                val outcome = try {
                    tryClient(client, videoId, query)
                } catch (e: LyricsTransientException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("YoutubeCaptionsProvider", "Failed client ${client.name} for $videoId: ${e.message}")
                    Outcome.Retry
                }
                when (outcome) {
                    is Outcome.Hit -> return@withContext outcome.result
                    Outcome.GiveUp -> break
                    Outcome.Retry -> Unit
                }
            }
        }
        null
    }

    /** Video version first (it is the one that has captions), then the song version, then any given id. */
    private suspend fun candidateVideoIds(query: LyricsQuery): List<String> = coroutineScope {
        val video = async { runCatchingNonCancel { VideoIdResolver.resolve(query, preferVideo = true) } }
        val song = async { runCatchingNonCancel { VideoIdResolver.resolve(query) } }
        listOfNotNull(video.await(), song.await(), query.videoId)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun tryClient(client: ClientConfig, videoId: String, query: LyricsQuery): Outcome {
        val playerBody = JsonObject().apply {
            add("context", JsonObject().apply {
                add("client", JsonObject().apply {
                    addProperty("clientName", client.name)
                    addProperty("clientVersion", client.version)
                    addProperty("hl", "en")
                    addProperty("gl", "US")
                    client.extra.forEach { (k, v) -> if (k == "androidSdkVersion") addProperty(k, v.toInt()) else addProperty(k, v) }
                })
            })
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }
        val headers = buildMap {
            put("User-Agent", client.userAgent)
            put("X-YouTube-Client-Name", client.headerId)
            put("X-YouTube-Client-Version", client.version)
            if (client.name == "WEB_REMIX") {
                put("Origin", InnerTube.MUSIC_ORIGIN)
                put("Referer", "${InnerTube.MUSIC_ORIGIN}/")
            }
        }

        val playerUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false".toHttpUrlOrNull()
            ?: return Outcome.Retry
        val res = LyricsHttp.post(
            playerUrl,
            playerBody.toString().toRequestBody("application/json".toMediaType()),
            headers,
            timeoutMs = 5000L
        )
        val body = res.body ?: return Outcome.Retry // 403 etc.: try the next client

        val root = JsonParser.parseString(body)
        val status = root.ytStr("playabilityStatus", "status")
        if (status != null && status != "OK") {
            Log.d("YoutubeCaptionsProvider", "${client.name} $videoId playability=$status")
            return Outcome.Retry
        }

        // Duration check: require <= 3s difference so the timestamps line up with the local file.
        val videoLengthSec = root.ytLong("videoDetails", "lengthSeconds") ?: 0L
        if (query.durationSec > 0 && videoLengthSec > 0) {
            if (abs(videoLengthSec - query.durationSec) > 3L) {
                Log.d("YoutubeCaptionsProvider", "Video duration mismatch: $videoLengthSec vs song ${query.durationSec}")
                return Outcome.GiveUp
            }
        }

        val tracks = root.ytArr("captions", "playerCaptionsTracklistRenderer", "captionTracks")
            ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            .orEmpty()
        if (tracks.isEmpty()) return Outcome.GiveUp

        // Prefer a hand-made track over auto-generated speech recognition (kind == "asr").
        val manual = tracks.firstOrNull { it.ytStr("kind") != "asr" && !it.ytStr("baseUrl").isNullOrBlank() }
        val chosen = manual ?: tracks.firstOrNull { !it.ytStr("baseUrl").isNullOrBlank() } ?: return Outcome.GiveUp
        val isManual = manual != null

        val json3Url = chosen.ytStr("baseUrl")?.toHttpUrlOrNull()
            ?.newBuilder()
            ?.setQueryParameter("fmt", "json3") // replace, not append: baseUrl may already carry fmt
            ?.build() ?: return Outcome.Retry

        val json3Body = LyricsHttp.get(json3Url, mapOf("User-Agent" to client.userAgent), timeoutMs = 5000L).body
        // An empty 200 is how YouTube refuses a caption URL that is not accepted for this client.
        if (json3Body.isNullOrBlank()) return Outcome.Retry

        val events = JsonParser.parseString(json3Body).ytArr("events") ?: return Outcome.Retry
        val rawEvents = mutableListOf<ParsedEvent>()

        for (eventElement in events) {
            if (!eventElement.isJsonObject) continue
            val event = eventElement.asJsonObject
            val tStartMs = event.ytLong("tStartMs") ?: 0L
            val segs = event.ytArr("segs") ?: continue

            val sb = StringBuilder()
            for (seg in segs) sb.append(seg.ytStr("utf8").orEmpty())

            val cleanText = cleanCaptionText(sb.toString())
            if (cleanText.isBlank()) continue
            rawEvents.add(ParsedEvent(tStartMs, cleanText))
        }

        val deduplicated = deduplicateEvents(rawEvents)
        if (deduplicated.size < 4) return Outcome.GiveUp

        val lrcContent = deduplicated.joinToString("\n") { "${formatLrcTime(it.startTimeMs)} ${it.text}" }

        val validation = LyricsValidator.validate(lrcContent, query.durationMs, LyricsType.SYNCED)
        if (!validation.isValid) return Outcome.GiveUp

        val baseScore = if (isManual) 0.80 else 0.50
        val finalScore = maxOf(0.0, baseScore - validation.penalty)
        return Outcome.Hit(LyricsResult(LyricsType.SYNCED, lrcContent, finalScore))
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
