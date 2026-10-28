package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.EnhancedLrc
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.jArr
import com.beatraxus.app.repository.lyrics.jBool
import com.beatraxus.app.repository.lyrics.jLong
import com.beatraxus.app.repository.lyrics.jStr
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

class PaxsenixProvider : LyricsProvider {
    override val id = "paxsenix"
    override val displayName = "PaxSenix"
    override val description = "Apple Music word timings proxy"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = true

    private data class iTunesMatch(val trackId: Long, val score: Double)
    private data class PartObj(val text: String, val timestamp: Long, val isPart: Boolean)

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.PAXSENIX_API_KEY
        val firstAttempt = fetchForTitleArtist(query, query.cleanTitle, query.primaryArtist, apiKey)
        if (firstAttempt != null) return@withContext firstAttempt

        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            return@withContext fetchForTitleArtist(query, query.title, query.artist, apiKey)
        }

        null
    }

    private suspend fun fetchForTitleArtist(query: LyricsQuery, title: String, artist: String, apiKey: String): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null

        // A rate limit / server error from the keyed API used to abort the whole provider, so the
        // free iTunes + legacy path below was never even tried. Fall through to it instead, and only
        // re-raise the transient error afterwards if that path finds nothing either.
        var transient: LyricsTransientException? = null
        if (apiKey.isNotEmpty()) {
            try {
                fetchWithNewApi(query, title, artist, apiKey)?.let { return it }
            } catch (e: LyricsTransientException) {
                transient = e
            }
        }

        val legacy = try {
            fetchLegacy(query, title, artist)
        } catch (e: LyricsTransientException) {
            throw transient ?: e
        }
        if (legacy != null) return legacy
        if (transient != null) throw transient
        return null
    }

    private suspend fun fetchLegacy(query: LyricsQuery, title: String, artist: String): LyricsResult? {
        // The two storefront searches are independent; they ran one after the other (up to 10 s).
        // One storefront timing out must not discard the other storefront's answer.
        val searches = coroutineScope {
            listOf("IN", "US")
                .map { country -> async { runCatching { searchiTunes(title, artist, country, query) } } }
                .map { it.await() }
        }
        val match = searches.firstNotNullOfOrNull { it.getOrNull() }
        if (match == null) {
            searches.firstNotNullOfOrNull { it.exceptionOrNull() as? LyricsTransientException }?.let { throw it }
            return null
        }

        val lyricsUrl = "https://lyrics.paxsenix.org/apple-music/lyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("id", match.trackId.toString())
            ?.build() ?: return null

        val res = LyricsHttp.get(lyricsUrl, timeoutMs = 8000L)
        val body = res.body ?: return null

        try {
            return parseLegacyLyrics(body, match.score)
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
    }

    private suspend fun fetchWithNewApi(query: LyricsQuery, title: String, artist: String, apiKey: String): LyricsResult? {
        val term = "$title $artist".trim()
        val searchUrl = "https://api.paxsenix.org/apple-music/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", term)
            ?.build() ?: return null

        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val searchRes = LyricsHttp.get(searchUrl, headers = headers, timeoutMs = 6000L)
        val searchBody = searchRes.body ?: return null
        
        var trackId: String? = null
        var bestScore = 0.0

        try {
            // The search answer is a bare array, but tolerate {"results"|"data"|"songs": [...]} too.
            val parsed = JsonParser.parseString(searchBody)
            val items = (if (parsed.isJsonArray) parsed.asJsonArray else null)
                ?: parsed.jArr("results") ?: parsed.jArr("data") ?: parsed.jArr("songs")
                ?: JsonArray()
            for (item in items) {
                if (!item.isJsonObject) continue
                val candId = item.jStr("id") ?: item.jStr("trackId") ?: continue
                val candTitle = item.jStr("name") ?: item.jStr("title") ?: ""

                val candArtist = item.jStr("artistName") ?: ""
                val candDurationMs = item.jLong("durationInMillis") ?: 0L

                val isConfident = LyricsMatcher.isConfidentMatch(query.title, query.artist, query.durationMs, candTitle, candArtist, candDurationMs)
                val score = LyricsMatcher.score(query.title, query.artist, query.durationMs, candTitle, candArtist, candDurationMs)
                if (isConfident && score > bestScore) {
                    bestScore = score
                    trackId = candId
                }
            }
        } catch (_: Exception) {}

        if (trackId == null) return null

        val lyricsUrl = "https://api.paxsenix.org/lyrics/applemusic".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("id", trackId)
            ?.build() ?: return null

        val lyricsRes = LyricsHttp.get(lyricsUrl, headers = headers, timeoutMs = 6000L)
        val lyricsBody = lyricsRes.body ?: return null

        try {
            return parseLegacyLyrics(lyricsBody, bestScore)
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id new api parse failed: ${lyricsBody.take(300)}")
            return null
        }
    }

    private fun parseLegacyLyrics(body: String, matchScore: Double): LyricsResult? {
        val root = JsonParser.parseString(body)

        val contentArray = root.jArr("content")
        if (contentArray != null && contentArray.size() > 0) {
            val sb = StringBuilder()
            var hasNonZeroTime = false

            for (lineObj in contentArray) {
                if (!lineObj.isJsonObject) continue
                val lineTime = lineObj.jLong("timestamp") ?: 0L
                if (lineTime > 0) hasNonZeroTime = true

                val mainTextArray = lineObj.jArr("text")
                if (mainTextArray != null && mainTextArray.size() > 0) {
                    val mainWords = parsePartsToWords(mainTextArray, lineTime)
                    if (mainWords.isNotEmpty()) {
                        sb.append(EnhancedLrc.line(mainWords)).append("\n")
                    }
                }

                val bgTextArray = lineObj.jArr("backgroundText")
                if (bgTextArray != null && bgTextArray.size() > 0) {
                    val bgWords = parsePartsToWords(bgTextArray, lineTime)
                    if (bgWords.isNotEmpty()) {
                        val bgFirstTime = bgWords.first().first
                        val bgLineText = EnhancedLrc.line(bgWords)
                        val header = "[${EnhancedLrc.formatLrcTime(bgFirstTime)}]"
                        val textPart = if (bgLineText.startsWith(header)) {
                            bgLineText.substring(header.length)
                        } else {
                            bgLineText
                        }
                        sb.append("$header($textPart)\n")
                    }
                }
            }

            val lrcResultStr = sb.toString().trim()
            if (hasNonZeroTime && lrcResultStr.isNotEmpty()) {
                return LyricsResult(LyricsType.WORD_BY_WORD, lrcResultStr, matchScore)
            }
        }

        val lrc = root.jStr("lrc")
        if (!lrc.isNullOrBlank()) {
            return LyricsResult(LyricsType.SYNCED, lrc, matchScore)
        }

        val plain = root.jStr("plain")
        if (!plain.isNullOrBlank()) {
            return LyricsResult(LyricsType.PLAIN, plain, matchScore)
        }

        return null
    }

    private suspend fun searchiTunes(title: String, artist: String, country: String, query: LyricsQuery): iTunesMatch? {
        val term = "$title $artist".trim()
        val url = "https://itunes.apple.com/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("term", term)
            ?.addQueryParameter("entity", "song")
            ?.addQueryParameter("limit", "10")
            ?.addQueryParameter("country", country)
            ?.build() ?: return null

        val res = LyricsHttp.get(url, timeoutMs = 5000L)
        val body = res.body ?: return null

        try {
            val results = JsonParser.parseString(body).jArr("results") ?: return null

            var bestMatch: iTunesMatch? = null
            var bestScore = 0.0

            for (i in 0 until results.size()) {
                val item = results[i]
                if (!item.isJsonObject) continue
                val trackId = item.jLong("trackId") ?: continue
                val trackName = item.jStr("trackName") ?: ""
                val artistName = item.jStr("artistName") ?: ""
                val trackTimeMs = item.jLong("trackTimeMillis")

                if (trackTimeMs != null && query.durationMs > 0 && abs(query.durationMs - trackTimeMs) > 12000) {
                    continue
                }

                val score = LyricsMatcher.score(query.title, query.artist, query.durationMs, trackName, artistName, trackTimeMs)
                val isConfident = LyricsMatcher.isConfidentMatch(query.title, query.artist, query.durationMs, trackName, artistName, trackTimeMs)

                if (isConfident && score > bestScore) {
                    bestScore = score
                    bestMatch = iTunesMatch(trackId, score)
                }
            }
            return bestMatch
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id itunes parse failed: ${body.take(300)}")
            return null
        }
    }

    private fun parsePartsToWords(partsArray: JsonArray, lineDefaultTime: Long): List<Pair<Long, String>> {
        val parts = mutableListOf<PartObj>()
        for (i in 0 until partsArray.size()) {
            val item = partsArray[i]
            if (!item.isJsonObject) continue
            val text = item.jStr("text") ?: ""
            val timestamp = item.jLong("timestamp") ?: lineDefaultTime
            val isPart = item.jBool("part") ?: false
            if (text.isNotEmpty()) {
                parts.add(PartObj(text, timestamp, isPart))
            }
        }

        if (parts.isEmpty()) return emptyList()

        val words = mutableListOf<Pair<Long, String>>()
        val currentPartGroup = mutableListOf<PartObj>()

        for (i in parts.indices) {
            val p = parts[i]
            currentPartGroup.add(p)

            val isLast = i == parts.size - 1
            val wordEnds = !p.isPart || p.text.endsWith(" ") || isLast

            if (wordEnds) {
                val startMs = currentPartGroup.first().timestamp
                val wordText = currentPartGroup.joinToString("") { it.text }.trim()
                if (wordText.isNotEmpty()) {
                    words.add(Pair(startMs, wordText))
                }
                currentPartGroup.clear()
            }
        }

        return words
    }
}
