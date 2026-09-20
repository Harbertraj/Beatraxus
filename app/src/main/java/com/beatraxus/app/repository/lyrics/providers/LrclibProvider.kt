package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LrcLibResponse
import com.beatraxus.app.repository.LrcLibService
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsHttpClient
import com.beatraxus.app.repository.lyrics.LyricsMatcher
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.cleanTitle
import com.beatraxus.app.repository.lyrics.durationSec
import com.beatraxus.app.repository.lyrics.primaryArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.Locale

class LrclibProvider : LyricsProvider {
    override val id = "lrclib"
    override val displayName = "LRCLIB"
    override val description = "Whole lines only, and always up"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false

    private val lrcLibService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .client(LyricsHttpClient.client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LrcLibService::class.java)

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = query.cleanTitle
        val primaryArtist = query.primaryArtist
        val durationSec = query.durationSec

        // (1) api/get with primaryArtist + cleanTitle + album + durationSec
        val getRes1 = executeApi {
            lrcLibService.getLyrics(primaryArtist, cleanTitle, query.album, durationSec)
        }
        if (getRes1 != null && isValidResponse(getRes1)) {
            return@withContext createResultFromResponse(getRes1, 1.0)
        }

        // (2) api/get with album=null
        if (query.album != null) {
            val getRes2 = executeApi {
                lrcLibService.getLyrics(primaryArtist, cleanTitle, null, durationSec)
            }
            if (getRes2 != null && isValidResponse(getRes2)) {
                return@withContext createResultFromResponse(getRes2, 1.0)
            }
        }

        // (3) api/search with track_name + artist_name
        val searchList1 = executeApiList {
            lrcLibService.searchLyrics(trackName = cleanTitle, artistName = primaryArtist)
        }
        val candidate1 = pickBestCandidate(query, searchList1)
        if (candidate1 != null) return@withContext candidate1

        // (4) api/search with q="$cleanTitle $primaryArtist"
        val qStr = normalizeForSearch("$cleanTitle $primaryArtist")
        if (qStr.isNotBlank()) {
            val searchList2 = executeApiList {
                lrcLibService.searchLyrics(query = qStr)
            }
            val candidate2 = pickBestCandidate(query, searchList2)
            if (candidate2 != null) return@withContext candidate2
        }

        null
    }

    private suspend fun <T> executeApi(call: suspend () -> Response<T>): T? {
        try {
            val resp = call()
            val code = resp.code()
            if (code == 404 || code == 401 || code == 204 || code == 422) return null
            if (code == 429 || code >= 500) throw LyricsTransientException("HTTP $code")
            if (resp.isSuccessful) return resp.body()
            return null
        } catch (e: LyricsTransientException) {
            throw e
        } catch (e: IOException) {
            throw LyricsTransientException(e.message, e)
        } catch (e: Exception) {
            if (e is HttpException) {
                val code = e.code()
                if (code == 404 || code == 401 || code == 204 || code == 422) return null
                if (code == 429 || code >= 500) throw LyricsTransientException("HTTP $code", e)
            }
            throw e
        }
    }

    private suspend fun <T> executeApiList(call: suspend () -> Response<List<T>>): List<T> {
        return executeApi(call) ?: emptyList()
    }

    private fun pickBestCandidate(query: LyricsQuery, results: List<LrcLibResponse>): LyricsResult? {
        var bestRes: LrcLibResponse? = null
        var bestScore = 0.0

        for (item in results) {
            if (!isValidResponse(item)) continue
            val candTrack = item.trackName ?: item.name ?: ""
            val candArtist = item.artistName ?: ""
            val candDurationMs = item.duration?.let { (it * 1000).toLong() }

            val score = LyricsMatcher.score(query.title, query.artist, query.durationMs, candTrack, candArtist, candDurationMs)
            val isConfident = LyricsMatcher.isConfidentMatch(query.title, query.artist, query.durationMs, candTrack, candArtist, candDurationMs)

            if (isConfident && score > bestScore) {
                bestScore = score
                bestRes = item
            }
        }

        return bestRes?.let { createResultFromResponse(it, bestScore) }
    }

    private fun isValidResponse(res: LrcLibResponse): Boolean {
        return !res.instrumental && (!res.syncedLyrics.isNullOrBlank() || !res.plainLyrics.isNullOrBlank())
    }

    private fun createResultFromResponse(res: LrcLibResponse, score: Double): LyricsResult {
        val synced = res.syncedLyrics
        val plain = res.plainLyrics ?: ""

        val type = when {
            !synced.isNullOrBlank() -> LyricsType.SYNCED
            else -> LyricsType.PLAIN
        }

        return LyricsResult(type, synced ?: plain, score)
    }

    private fun normalizeForSearch(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
