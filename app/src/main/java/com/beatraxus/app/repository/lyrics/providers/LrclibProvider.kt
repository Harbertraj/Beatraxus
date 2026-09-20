package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LrcLibResponse
import com.beatraxus.app.repository.LrcLibService
import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class LrclibProvider : LyricsProvider {
    override val id = "lrclib"
    override val displayName = "LRCLIB"
    override val description = "Whole lines only, and always up"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val durationSec = (query.durationMs / 1000.0).roundToInt()

        var bestPlainCandidate: LyricsResult? = null

        runCatching {
            val response = lrcLibService.getLyrics(query.artist, query.title, query.album, durationSec)
            if (isValidResponse(response)) {
                if (isSyncedResponse(response)) {
                    return@withContext createResultFromResponse(response, 1.0)
                }
                bestPlainCandidate = createResultFromResponse(response, 1.0)
            }
        }

        runCatching {
            val searchQuery = normalizeForSearch("${query.artist} ${query.title}")
            val results = lrcLibService.searchLyrics(searchQuery)

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
