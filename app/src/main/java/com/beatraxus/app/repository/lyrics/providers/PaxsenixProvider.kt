package com.beatraxus.app.repository.lyrics.providers

import android.util.Log
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.EnhancedLrc
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
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
        val firstAttempt = fetchForTitleArtist(query, query.cleanTitle, query.primaryArtist)
        if (firstAttempt != null) return@withContext firstAttempt

        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            return@withContext fetchForTitleArtist(query, query.title, query.artist)
        }

        null
    }

    private suspend fun fetchForTitleArtist(query: LyricsQuery, title: String, artist: String): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null

        var match = searchiTunes(title, artist, "IN", query)
        if (match == null) {
            match = searchiTunes(title, artist, "US", query)
        }
        if (match == null) return null

        val lyricsUrl = "https://lyrics.paxsenix.org/apple-music/lyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("id", match.trackId.toString())
            ?.build() ?: return null

        val res = LyricsHttp.get(lyricsUrl, timeoutMs = 10000L)
        val body = res.body ?: return null

        try {
            val root = JsonParser.parseString(body).asJsonObject

            val contentArray = root.getAsJsonArray("content")
            if (contentArray != null && contentArray.size() > 0) {
                val sb = StringBuilder()
                var hasNonZeroTime = false

                for (i in 0 until contentArray.size()) {
                    val lineObj = contentArray[i].asJsonObject
                    val lineTime = lineObj.get("timestamp")?.asLong ?: 0L
                    if (lineTime > 0) hasNonZeroTime = true

                    val mainTextArray = lineObj.getAsJsonArray("text")
                    if (mainTextArray != null && mainTextArray.size() > 0) {
                        val mainWords = parsePartsToWords(mainTextArray, lineTime)
                        if (mainWords.isNotEmpty()) {
                            sb.append(EnhancedLrc.line(mainWords)).append("\n")
                        }
                    }

                    val bgTextArray = lineObj.getAsJsonArray("backgroundText")
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
                    return LyricsResult(LyricsType.WORD_BY_WORD, lrcResultStr, match.score)
                }
            }

            val lrc = root.get("lrc")?.asString
            if (!lrc.isNullOrBlank()) {
                return LyricsResult(LyricsType.SYNCED, lrc, match.score)
            }

            val plain = root.get("plain")?.asString
            if (!plain.isNullOrBlank()) {
                return LyricsResult(LyricsType.PLAIN, plain, match.score)
            }

            return null
        } catch (_: Exception) {
            Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
            return null
        }
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
            val root = JsonParser.parseString(body).asJsonObject
            val results = root.getAsJsonArray("results") ?: return null

            var bestMatch: iTunesMatch? = null
            var bestScore = 0.0

            for (i in 0 until results.size()) {
                val item = results[i].asJsonObject
                val trackId = item.get("trackId")?.asLong ?: continue
                val trackName = item.get("trackName")?.asString ?: ""
                val artistName = item.get("artistName")?.asString ?: ""
                val trackTimeMs = item.get("trackTimeMillis")?.asLong

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
            val item = partsArray[i].asJsonObject
            val text = item.get("text")?.asString ?: ""
            val timestamp = item.get("timestamp")?.asLong ?: lineDefaultTime
            val isPart = item.get("part")?.asBoolean ?: false
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
