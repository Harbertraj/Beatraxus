package com.beatraxus.app.repository.lyrics

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

object VideoIdResolver {
    private const val TAG = "VideoIdResolver"

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

    suspend fun resolve(query: LyricsQuery): String? {
        val key = "${LyricsMatcher.normalize(query.primaryArtist)}|${LyricsMatcher.normalize(query.cleanTitle)}|${query.durationSec}"
        val cached = getFromCache(key)
        if (cached != null) {
            return if (cached.isEmpty()) null else cached
        }

        try {
            val cleanTitle = query.cleanTitle
            val primaryArtist = query.primaryArtist
            val searchQuery = "$cleanTitle $primaryArtist".trim()

            val url = "https://lyrics.paxsenix.org/youtube/search".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", searchQuery)
                ?.build() ?: return null

            val res = LyricsHttp.get(url, timeoutMs = 5000L)
            val body = res.body ?: run {
                putInCache(key, null)
                return null
            }

            val array = JsonParser.parseString(body).asJsonArray
            var bestVideoId: String? = null
            var bestScore = 0.0

            for (i in 0 until array.size()) {
                val item = array[i].asJsonObject
                val vId = item.get("videoId")?.asString ?: item.get("video_id")?.asString ?: continue
                val candTitle = item.get("title")?.asString ?: ""
                val author = item.get("author")?.asString ?: ""
                val durationStr = item.get("duration")?.asString

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
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve videoId: ${e.message}")
            return null
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
