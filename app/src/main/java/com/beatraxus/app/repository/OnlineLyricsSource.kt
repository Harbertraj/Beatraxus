package com.beatraxus.app.repository

import android.util.Log
import androidx.annotation.Keep
import com.beatraxus.app.model.LrcLine
import com.google.gson.annotations.SerializedName
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Keep
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

@Keep
data class LrcLibResponse(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("trackName") val trackName: String? = null,
    @SerializedName("artistName") val artistName: String? = null,
    @SerializedName("albumName") val albumName: String? = null,
    @SerializedName("duration") val duration: Double? = null,
    @SerializedName("instrumental") val instrumental: Boolean = false,
    @SerializedName("plainLyrics") val plainLyrics: String? = null,
    @SerializedName("syncedLyrics") val syncedLyrics: String? = null
)

class OnlineLyricsSource {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Beatraxus Music Player (https://github.com/beatraxus/beatraxus)")
                .build()
            chain.proceed(request)
        }
        .build()

    private val lrcLibService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LrcLibService::class.java)

    suspend fun fetchLyrics(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val durationSec = (durationMs / 1000.0).roundToInt()

        // We'll collect all candidates with their scores and pick the absolute best.
        val candidates = mutableListOf<Pair<LrcLibResponse, Double>>()

        // 1. Try precise "get" request
        if (durationSec > 0) {
            runCatching {
                val response = lrcLibService.getLyrics(artist, title, album, durationSec)
                if (isValidResponse(response)) {
                    val score = calculateScore(response, artist, title, album, durationSec)
                    if (score > 0.7) candidates.add(response to score)
                }
            }.onFailure { Log.e("OnlineLyricsSource", "Precise fetch failed: ${it.message}") }
        }

        // 2. Try search-based request (broader)
        runCatching {
            val query = "${normalize(artist)} ${normalize(title)}".take(80)
            val searchResults = lrcLibService.searchLyrics(query)
            searchResults.filter { isValidResponse(it) }.forEach { res ->
                val score = calculateScore(res, artist, title, album, durationSec)
                if (score > 0.65) candidates.add(res to score)
            }
        }.onFailure { Log.e("OnlineLyricsSource", "Search failed: ${it.message}") }

        // Pick the candidate with the highest score.
        // If scores are very close, synced lyrics are preferred (handled via bonus in calculateScore).
        candidates.maxByOrNull { it.second }?.first?.let { 
            createResultFromResponse(it) 
        }
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
        val resDuration = res.duration?.roundToInt() ?: 0
        
        // Stricter duration matching: different recordings (live, radio edit) usually 
        // differ by more than 2-3 seconds.
        val durationDiff = abs(resDuration - durationSec)
        if (durationDiff > 4 && durationSec > 0) return 0.0
        
        val titleSim = similarity(title, res.trackName ?: "")
        if (titleSim < 0.7) return 0.0

        val artistSim = similarity(artist, res.artistName ?: "")
        if (artistSim < 0.5) return 0.0
        
        val albumSim = if (album != null && res.albumName != null) similarity(album, res.albumName) else 0.8
        
        // Scoring formula
        val durationScore = (1.0 - (durationDiff / 5.0)).coerceAtLeast(0.0)
        
        var totalScore = (titleSim * 0.4) + (artistSim * 0.3) + (albumSim * 0.1) + (durationScore * 0.2)

        // Type Bonus: We strongly prefer synced lyrics IF they match the song well.
        if (!res.syncedLyrics.isNullOrBlank()) {
            val isWordByWord = res.syncedLyrics.contains(Regex("<\\d+:\\d+[.:]\\d+>"))
            totalScore += if (isWordByWord) 0.3 else 0.15
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
            .replace(Regex("\\(.*?\\)"), "") // Remove anything in parentheses
            .replace(Regex("\\[.*?\\]"), "") // Remove anything in brackets
            .replace(Regex("(?i)\\b(remastered|remaster|live|radio edit|official|video|audio|lyrics|feat\\.?|ft\\.?)\\b"), "")
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
