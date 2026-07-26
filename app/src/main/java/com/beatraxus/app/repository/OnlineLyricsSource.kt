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

        // Keep the best *plain* candidate around in case we never find a synced match,
        // but never settle for plain lyrics while a synced version might still be found.
        var bestPlainCandidate: LyricsResult? = null

        // 1. Direct/exact match by artist+title(+album+duration)
        runCatching {
            val response = lrcLibService.getLyrics(artist, title, album, durationSec)
            if (isValidResponse(response)) {
                if (isSyncedResponse(response)) {
                    return@withContext createResultFromResponse(response, 1.0)
                }
                bestPlainCandidate = createResultFromResponse(response, 1.0)
            }
        }

        // 2. Search fallback — scan ALL results and prefer any synced match over plain ones,
        //    instead of blindly taking the first "valid" (possibly unsynced) result.
        runCatching {
            val query = normalizeForSearch("$artist $title")
            val results = lrcLibService.searchLyrics(query)

            val syncedMatch = results.firstOrNull { isValidResponse(it) && isSyncedResponse(it) }
            if (syncedMatch != null) {
                return@withContext createResultFromResponse(syncedMatch, 0.8)
            }

            if (bestPlainCandidate == null) {
                results.firstOrNull { isValidResponse(it) }?.let {
                    bestPlainCandidate = createResultFromResponse(it, 0.8)
                }
            }
        }

        // 3. Nothing synced found anywhere — fall back to the best plain-text match, if any.
        bestPlainCandidate
    }

    private fun isValidResponse(res: LrcLibResponse): Boolean {
        return !res.instrumental && (!res.syncedLyrics.isNullOrBlank() || !res.plainLyrics.isNullOrBlank())
    }

    private fun isSyncedResponse(res: LrcLibResponse): Boolean {
        return !res.syncedLyrics.isNullOrBlank()
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
            .replace(Regex("""[^a-z0-9\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}