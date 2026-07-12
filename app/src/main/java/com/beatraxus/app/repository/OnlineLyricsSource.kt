package com.beatraxus.app.repository

import android.util.Log
import com.beatraxus.app.model.LrcLine
import com.google.gson.annotations.SerializedName
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
        @Query("duration") duration: Double?
    ): LrcLibResponse

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): List<LrcLibResponse>
}

data class LrcLibResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("trackName") val trackName: String?,
    @SerializedName("artistName") val artistName: String?,
    @SerializedName("albumName") val albumName: String?,
    @SerializedName("duration") val duration: Double?,
    @SerializedName("instrumental") val instrumental: Boolean,
    @SerializedName("plainLyrics") val plainLyrics: String?,
    @SerializedName("syncedLyrics") val syncedLyrics: String?
)

class OnlineLyricsSource {

    private val client = okhttp3.OkHttpClient.Builder()
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
        val durationSec = (durationMs / 1000).toInt()
        var bestResult: LyricsResult? = null

        // ── 1. Precise get (exact metadata match by lrclib) ───────────────────────
        if (durationSec > 0) {
            runCatching {
                val response = lrcLibService.getLyrics(artist, title, album, durationSec.toDouble())
                if (isValidResponse(response)) {
                    val resDur = response.duration?.toInt() ?: 0
                    // Accept only if duration is within 3s of actual song length
                    if (abs(resDur - durationSec) <= 3) {
                        val result = createResultFromResponse(response)
                        if (result.type == LyricsType.WORD_BY_WORD) {
                            return@withContext result // Found best type, return immediately
                        }
                        bestResult = result
                    }
                }
            }
        }

        // ── 2. Search with multi-result scoring ───────────────────────────────────
        runCatching {
            val query = buildString {
                append(artist.take(40))
                append(" ")
                append(title.take(40))
            }
            val searchResults = lrcLibService.searchLyrics(query)

            val bestMatch = searchResults
                .filter { isValidResponse(it) }
                .mapNotNull { res ->
                    val score = calculateScore(res, artist, title, album, durationSec)
                    if (score >= 0.55) res to score else null   // raised threshold
                }
                .maxByOrNull { it.second }
                ?.first

            if (bestMatch != null) {
                val searchResult = createResultFromResponse(bestMatch)
                // Use search result if it's better than precise match or if precise match failed
                if (bestResult == null || searchResult.type.ordinal > bestResult!!.type.ordinal) {
                    return@withContext searchResult
                }
            }
        }.onFailure { e ->
            Log.e("OnlineLyricsSource", "Search failed: ${e.message}")
        }

        bestResult
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
        val resDuration = res.duration?.toInt() ?: 0
        // Reject if duration differs by more than 4s OR more than 3% of song length
        val maxAllowedGap = maxOf(4, (durationSec * 0.03).toInt())
        if (abs(resDuration - durationSec) > maxAllowedGap) return 0.0
        
        val titleSim = similarity(title, res.trackName ?: "")
        if (titleSim < 0.5) return 0.0

        // 2. Calculate weighted score
        val artistSim = similarity(artist, res.artistName ?: "")
        val albumSim = if (album != null && res.albumName != null) similarity(album, res.albumName) else 1.0
        
        val durationScore = (1.0 - (abs(resDuration - durationSec) / 5.0)).coerceAtLeast(0.0)

        // Weights: Title (40%), Artist (30%), Album (10%), Duration (20%)
        var totalScore = (titleSim * 0.4) + (artistSim * 0.3) + (albumSim * 0.1) + (durationScore * 0.2)

        // Priority bonus for better types (STRICT PRIORITY: ELRC > LRC > PLAIN)
        if (!res.syncedLyrics.isNullOrBlank()) {
            val isWordByWord = res.syncedLyrics.contains(Regex("<\\d+:\\d+[.:]\\d+>"))
            totalScore += if (isWordByWord) 0.5 else 0.2
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
