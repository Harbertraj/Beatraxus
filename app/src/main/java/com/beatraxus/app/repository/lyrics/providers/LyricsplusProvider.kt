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
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.primaryArtist
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class LyricsplusProvider : LyricsProvider {
    override val id = "lyricsplus"
    override val displayName = "LyricsPlus"
    override val description = "External lyrics provider API"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = true
    override val isConfigured = true

    private val BASE_URLS = listOf("https://lyricsplus.binimum.org")

    private data class Syl(val timeMs: Long, val text: String)

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

        for (baseUrl in BASE_URLS) {
            val url = "$baseUrl/v2/lyrics/get".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("title", title)
                ?.addQueryParameter("artist", artist)
                ?.addQueryParameter("album", query.album ?: "")
                ?.addQueryParameter("duration", query.durationSec.toString())
                ?.build() ?: continue

            val res = LyricsHttp.get(url)
            val body = res.body ?: continue

            try {
                val root = JsonParser.parseString(body).asJsonObject
                val lyricsArray = root.getAsJsonArray("lyrics") ?: run {
                    Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
                    return null
                }

                if (lyricsArray.size() == 0) continue

                val sb = StringBuilder()
                var hasWordTimings = false
                var hasLineTimings = false

                for (i in 0 until lyricsArray.size()) {
                    val lineObj = lyricsArray[i].asJsonObject
                    val lineTime = lineObj.get("time")?.asLong ?: 0L
                    if (lineTime > 0) hasLineTimings = true

                    val lineText = lineObj.get("text")?.asString ?: ""

                    val sylArray = lineObj.getAsJsonArray("syllabus") ?: lineObj.getAsJsonArray("syllables")
                    if (sylArray != null && sylArray.size() > 0) {
                        val words = groupSyllables(sylArray, lineTime)
                        if (words.isNotEmpty()) {
                            hasWordTimings = true
                            sb.append(EnhancedLrc.line(words)).append("\n")
                            continue
                        }
                    }

                    if (lineText.isNotEmpty()) {
                        sb.append("[${EnhancedLrc.formatLrcTime(lineTime)}]$lineText\n")
                    }
                }

                val resultText = sb.toString().trim()
                if (resultText.isEmpty()) continue

                val type = when {
                    hasWordTimings -> LyricsType.WORD_BY_WORD
                    hasLineTimings -> LyricsType.SYNCED
                    else -> LyricsType.PLAIN
                }

                val matchScore = LyricsMatcher.score(query.title, query.artist, query.durationMs, title, artist, query.durationMs)
                return LyricsResult(type, resultText, matchScore.coerceAtLeast(0.7))
            } catch (_: Exception) {
                Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
                return null
            }
        }

        return null
    }

    private fun groupSyllables(sylArray: JsonArray, defaultTimeMs: Long): List<Pair<Long, String>> {
        val syls = mutableListOf<Syl>()
        for (i in 0 until sylArray.size()) {
            val item = sylArray[i].asJsonObject
            val text = item.get("text")?.asString ?: ""
            val time = item.get("time")?.asLong ?: defaultTimeMs
            if (text.isNotEmpty()) {
                syls.add(Syl(time, text))
            }
        }

        if (syls.isEmpty()) return emptyList()

        val words = mutableListOf<Pair<Long, String>>()
        val currentGroup = mutableListOf<Syl>()

        for (i in syls.indices) {
            val s = syls[i]
            currentGroup.add(s)

            val endsWithWs = s.text.lastOrNull()?.isWhitespace() == true
            val nextStartsWithWs = if (i + 1 < syls.size) {
                syls[i + 1].text.firstOrNull()?.isWhitespace() == true
            } else false
            val isLast = i == syls.size - 1

            if (endsWithWs || nextStartsWithWs || isLast) {
                val startMs = currentGroup.first().timeMs
                val wordText = currentGroup.joinToString("") { it.text }.trim()
                if (wordText.isNotEmpty()) {
                    words.add(Pair(startMs, wordText))
                }
                currentGroup.clear()
            }
        }

        return words
    }
}
