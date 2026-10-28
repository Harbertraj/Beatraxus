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
import com.beatraxus.app.repository.lyrics.jArr
import com.beatraxus.app.repository.lyrics.jLong
import com.beatraxus.app.repository.lyrics.jStr
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
                ?.apply {
                    // Unknown album / duration are left out instead of being sent as "" / 0.
                    if (!query.album.isNullOrBlank()) addQueryParameter("album", query.album)
                    if (query.durationSec > 0) addQueryParameter("duration", query.durationSec.toString())
                }
                ?.build() ?: continue

            val res = LyricsHttp.get(url)
            val body = res.body ?: continue

            try {
                val root = JsonParser.parseString(body)
                val lyricsArray = root.jArr("lyrics") ?: run {
                    Log.w("LyricsProvider", "$id parse failed: ${body.take(300)}")
                    return null
                }

                if (lyricsArray.size() == 0) continue

                val candDurationMs = root.jLong("duration")?.let { it * 1000L }

                val sb = StringBuilder()
                val plainSb = StringBuilder()
                var hasWordTimings = false
                var hasLineTimings = false

                for (el in lyricsArray) {
                    if (!el.isJsonObject) continue
                    val lineTime = el.jLong("time") ?: 0L
                    if (lineTime > 0) hasLineTimings = true

                    val lineText = el.jStr("text") ?: ""

                    val sylArray = el.jArr("syllabus") ?: el.jArr("syllables")
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
                        plainSb.append(lineText).append("\n")
                    }
                }

                val timed = hasWordTimings || hasLineTimings
                // Untimed lyrics used to be returned as a wall of "[00:00.00]" tags.
                val resultText = (if (timed) sb else plainSb).toString().trim()
                if (resultText.isEmpty()) continue

                val type = when {
                    hasWordTimings -> LyricsType.WORD_BY_WORD
                    hasLineTimings -> LyricsType.SYNCED
                    else -> LyricsType.PLAIN
                }

                val matchScore = LyricsMatcher.score(query.title, query.artist, query.durationMs, title, artist, candDurationMs)
                return LyricsResult(type, resultText, matchScore)
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
            val item = sylArray[i]
            if (!item.isJsonObject) continue
            val text = item.jStr("text") ?: ""
            val time = item.jLong("time") ?: defaultTimeMs
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
