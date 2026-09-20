package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttp
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.primaryArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MegalobizProvider : LyricsProvider {
    override val id = "megalobiz"
    override val displayName = "Megalobiz"
    override val description = "Community synced lyrics (HTML Scraping)"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = true
    override val isConfigured = true

    private data class SearchCandidate(val href: String, val score: Double)

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val firstAttempt = searchAndFetch(query, query.cleanTitle, query.primaryArtist)
        if (firstAttempt != null) return@withContext firstAttempt

        if (query.cleanTitle != query.title || query.primaryArtist != query.artist) {
            return@withContext searchAndFetch(query, query.title, query.artist)
        }

        null
    }

    private suspend fun searchAndFetch(query: LyricsQuery, title: String, artist: String): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null

        val searchUrl = "https://www.megalobiz.com/search/all".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("qry", "$artist $title".trim())
            ?.build() ?: return null

        val searchRes = LyricsHttp.get(searchUrl)
        val searchBody = searchRes.body ?: return null

        val linkRegex = Regex("""href="(/lrc/maker/[^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val matches = linkRegex.findAll(searchBody).take(5).toList()
        if (matches.isEmpty()) return null

        var bestCandidate: SearchCandidate? = null

        for (match in matches) {
            val href = match.groupValues[1]
            val rawAnchor = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()

            val score = LyricsMatcher.score(query.title, query.artist, query.durationMs, rawAnchor, artist)
            if (score >= 0.7 && (bestCandidate == null || score > bestCandidate.score)) {
                bestCandidate = SearchCandidate(href, score)
            }
        }

        val chosenHref = bestCandidate?.href ?: return null
        val matchScore = bestCandidate.score

        val pageUrl = "https://www.megalobiz.com$chosenHref".toHttpUrlOrNull() ?: return null
        val pageRes = LyricsHttp.get(pageUrl)
        val pageBody = pageRes.body ?: return null

        val cleanHtml = pageBody
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

        val lines = cleanHtml.lines().map { it.trim() }
        val timestampRegex = Regex("""^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

        val currentRun = mutableListOf<String>()
        var longestRun = mutableListOf<String>()

        for (line in lines) {
            if (timestampRegex.containsMatchIn(line)) {
                currentRun.add(line)
            } else {
                if (currentRun.size > longestRun.size) {
                    longestRun = currentRun.toMutableList()
                }
                currentRun.clear()
            }
        }
        if (currentRun.size > longestRun.size) {
            longestRun = currentRun
        }

        if (longestRun.size < 5) return null

        val lastLine = longestRun.last()
        val lastMs = parseLineTimeMs(lastLine)
        if (query.durationMs > 0 && lastMs > query.durationMs + 10000L) {
            return null
        }

        val finalLrc = longestRun.joinToString("\n")
        return LyricsResult(LyricsType.SYNCED, finalLrc, matchScore)
    }

    private fun parseLineTimeMs(line: String): Long {
        val match = Regex("""^\[(?:(\d+):)?(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""").find(line) ?: return 0L
        val min = match.groupValues[2].toLongOrNull() ?: 0L
        val sec = match.groupValues[3].toLongOrNull() ?: 0L
        val msGroup = match.groupValues[4]
        val ms = when (msGroup.length) {
            1 -> msGroup.toLong() * 100
            2 -> msGroup.toLong() * 10
            3 -> msGroup.toLong()
            else -> 0L
        }
        return (min * 60 + sec) * 1000 + ms
    }
}
