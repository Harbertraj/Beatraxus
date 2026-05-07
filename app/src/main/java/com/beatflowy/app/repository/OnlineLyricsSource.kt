package com.beatflowy.app.repository

import com.beatflowy.app.model.LrcLine
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.abs
import kotlin.math.max

interface LrcLibService {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("artist_name") artist: String,
        @Query("track_name") title: String,
        @Query("album_name") album: String?,
        @Query("duration") duration: Int?
    ): LrcLibResponse

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): List<LrcLibResponse>
}

data class LrcLibResponse(
    val id: Int,
    val name: String?,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Int?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

class OnlineLyricsSource {
    private val lrcLibService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LrcLibService::class.java)

    suspend fun fetchLyrics(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val durationSec = (durationMs / 1000).toInt()
        
        // 1. Try LrcLib precise get (often the best)
        runCatching {
            val response = lrcLibService.getLyrics(artist, title, album, durationSec)
            if (isValidResponse(response)) {
                return@withContext createResultFromResponse(response)
            }
        }

        // 2. Search LrcLib with scoring
        runCatching {
            val query = "$artist $title".take(100)
            val searchResults = lrcLibService.searchLyrics(query)
            
            val bestMatch = searchResults
                .filter { isValidResponse(it) }
                .map { res -> res to calculateScore(res, artist, title, album, durationSec) }
                .filter { it.second >= 0.5 } // Minimum quality threshold
                .maxByOrNull { it.second }
                ?.first

            if (bestMatch != null) {
                return@withContext createResultFromResponse(bestMatch)
            }
        }

        null
    }

    private fun isValidResponse(res: LrcLibResponse): Boolean {
        return !res.instrumental && (!res.syncedLyrics.isNullOrBlank() || !res.plainLyrics.isNullOrBlank())
    }

    private fun createResultFromResponse(res: LrcLibResponse): LyricsResult {
        val synced = res.syncedLyrics
        val plain = res.plainLyrics ?: ""
        
        return when {
            !synced.isNullOrBlank() -> {
                val isWordByWord = synced.contains(Regex("<\\d+:\\d+[.:]\\d+>"))
                LyricsResult(
                    type = if (isWordByWord) LyricsType.WORD_BY_WORD else LyricsType.SYNCED,
                    content = synced
                )
            }
            else -> LyricsResult(LyricsType.PLAIN, plain)
        }
    }

    private fun calculateScore(
        res: LrcLibResponse,
        artist: String,
        title: String,
        album: String?,
        durationSec: Int
    ): Double {
        // 1. Reject bad matches
        val resDuration = res.duration ?: 0
        if (abs(resDuration - durationSec) > 5) return 0.0
        
        val titleSim = similarity(title, res.trackName ?: "")
        if (titleSim < 0.5) return 0.0

        // 2. Calculate weighted score
        val artistSim = similarity(artist, res.artistName ?: "")
        val albumSim = if (album != null && res.albumName != null) similarity(album, res.albumName) else 1.0
        
        val durationScore = (1.0 - (abs(resDuration - durationSec) / 5.0)).coerceAtLeast(0.0)

        // Weights: Title (40%), Artist (30%), Album (10%), Duration (20%)
        var totalScore = (titleSim * 0.4) + (artistSim * 0.3) + (albumSim * 0.1) + (durationScore * 0.2)

        // Priority bonus for better types
        if (!res.syncedLyrics.isNullOrBlank()) {
            val isWordByWord = res.syncedLyrics.contains(Regex("<\\d+:\\d+[.:]\\d+>"))
            totalScore += if (isWordByWord) 0.1 else 0.05
        }

        return totalScore
    }

    private fun similarity(s1: String, s2: String): Double {
        val n1 = normalize(s1)
        val n2 = normalize(s2)
        if (n1 == n2) return 1.0
        if (n1.isEmpty() || n2.isEmpty()) return 0.0
        
        val distance = levenshtein(n1, n2)
        val maxLen = max(n1.length, n2.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("(?i)feat\\.?|ft\\.?|remix|official|video|audio|lyrics"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }
}
