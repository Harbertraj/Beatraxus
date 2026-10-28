package com.beatraxus.app.repository.lyrics

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

object VideoIdResolver {
    private const val TAG = "VideoIdResolver"

    // YouTube Music search filters ("songs" = audio uploads, "videos" = official/lyric videos).
    private const val PARAMS_SONGS = "EgWKAQIIAWoMEAMQBBAJEA4QChAF"
    private const val PARAMS_VIDEOS = "EgWKAQIQAWoMEAMQBBAJEA4QChAF"

    private data class CacheEntry(val videoId: String?, val timestamp: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(300, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 300
        }
    }

    @Synchronized
    private fun getFromCache(key: String): String? {
        val entry = cache[key] ?: return null
        if (entry.videoId != null) return entry.videoId
        if (System.currentTimeMillis() - entry.timestamp < 10 * 60 * 1000L) {
            return "" // negative cache hit
        }
        cache.remove(key)
        return null
    }

    @Synchronized
    private fun putInCache(key: String, videoId: String?) {
        cache[key] = CacheEntry(videoId, System.currentTimeMillis())
    }

    /**
     * @param preferVideo search the "videos" filter instead of "songs". Auto-generated "Topic" audio
     * uploads (what the songs filter returns) almost never carry caption tracks, so the captions
     * provider needs the video version.
     */
    suspend fun resolve(query: LyricsQuery, preferVideo: Boolean = false): String? {
        val key = "${LyricsMatcher.normalize(query.primaryArtist)}|${LyricsMatcher.normalize(query.cleanTitle)}|${query.durationSec}" +
            if (preferVideo) "|v" else ""
        val cached = getFromCache(key)
        if (cached != null) {
            return if (cached.isEmpty()) null else cached
        }

        try {
            val cleanTitle = query.cleanTitle
            val primaryArtist = query.primaryArtist
            val searchQuery = "$cleanTitle $primaryArtist".trim()

            // Built with Gson: the old string template produced invalid JSON for any title/artist
            // containing a quote or backslash, so those songs never resolved.
            val jsonBody = com.google.gson.JsonObject().apply {
                add("context", InnerTube.webRemixContext())
                addProperty("query", searchQuery)
                addProperty("params", if (preferVideo) PARAMS_VIDEOS else PARAMS_SONGS)
            }

            val res = InnerTube.postMusic("search", jsonBody, timeoutMs = 5000L)
            val body = res.body ?: run {
                putInCache(key, null)
                return null
            }

            val root = JsonParser.parseString(body).asJsonObject
            val items = findMusicItems(root)

            var bestVideoId: String? = null
            var bestScore = 0.0

            for (item in items) {
                val vId = extractVideoId(item) ?: continue
                val candTitle = extractText(item, 0)
                val candArtistDuration = extractText(item, 1) // e.g. "Artist • Album • 3:45"
                
                val author = candArtistDuration.split(" • ").firstOrNull() ?: ""
                val durationStr = candArtistDuration.split(" • ").lastOrNull()

                val candDurationSec = parseDurationSec(durationStr) ?: continue
                val durationDiffSec = abs(candDurationSec - query.durationSec)
                if (durationDiffSec > 12) continue

                val cleanAuthor = author
                    .replace(Regex("(?i)\\s*-\\s*Topic\\b"), "")
                    .replace(Regex("(?i)\\s*VEVO\\b"), "")
                    .trim()

                val titleSim = LyricsMatcher.similarity(cleanTitle, candTitle)

                val candScore = if (titleSim >= 0.85 && durationDiffSec <= 6) {
                    titleSim
                } else {
                    LyricsMatcher.score(
                        queryTitle = query.title,
                        queryArtist = query.artist,
                        queryDurationMs = query.durationMs,
                        candTitle = candTitle,
                        candArtist = cleanAuthor,
                        candDurationMs = candDurationSec * 1000L
                    )
                }

                if (candScore >= 0.6 && candScore > bestScore) {
                    bestScore = candScore
                    bestVideoId = vId
                }
            }

            putInCache(key, bestVideoId)
            return bestVideoId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve videoId: ${e.message}")
            return null
        }
    }

    private fun findMusicItems(root: JsonElement): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        fun recurse(el: JsonElement) {
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                if (obj.has("musicResponsiveListItemRenderer")) {
                    list.add(obj.getAsJsonObject("musicResponsiveListItemRenderer"))
                }
                obj.entrySet().forEach { recurse(it.value) }
            } else if (el.isJsonArray) {
                el.asJsonArray.forEach { recurse(it) }
            }
        }
        recurse(root)
        return list
    }

    private fun extractVideoId(item: JsonObject): String? {
        return try {
            item.getAsJsonObject("playlistItemData")?.get("videoId")?.asString
        } catch (_: Exception) { null }
    }

    private fun extractText(item: JsonObject, columnIndex: Int): String {
        return try {
            val columns = item.getAsJsonArray("flexColumns") ?: return ""
            if (columnIndex >= columns.size()) return ""
            val runs = columns[columnIndex].asJsonObject
                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getAsJsonObject("text")
                ?.getAsJsonArray("runs") ?: return ""
            runs.map { it.asJsonObject.get("text")?.asString ?: "" }.joinToString("")
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseDurationSec(str: String?): Int? {
        if (str.isNullOrBlank()) return null
        return try {
            if (str.contains(":")) {
                val parts = str.trim().split(":")
                when (parts.size) {
                    2 -> parts[0].toInt() * 60 + parts[1].toInt()
                    3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                    else -> null
                }
            } else {
                str.trim().toIntOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }
}
