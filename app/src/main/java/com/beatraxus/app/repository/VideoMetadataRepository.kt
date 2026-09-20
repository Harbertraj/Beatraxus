package com.beatraxus.app.repository

import android.util.Log
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.util.PlaybackGlobalState
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import java.io.InterruptedIOException

class VideoMetadataRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val TAG = "VideoMetadataRepository"
    
    // TMDb API Key should be provided in build.gradle.kts / local.properties
    // If missing, we skip online search.
    private val apiKey = BuildConfig.TMDB_API_KEY

    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String?>>()
    private val negativeCache = ConcurrentHashMap<String, Long>()

    private var consecutiveTimeouts = 0
    private var circuitBreakerResetTime = 0L

    fun cleanTitleForSearch(rawTitle: String): String {
        // e.g. "The.Matrix.1999.1080p.BluRay" -> "The Matrix 1999"
        val regex = Regex("^(.*?)(?:[\\.\\s_\\-]*(19\\d{2}|20\\d{2}))")
        val match = regex.find(rawTitle)
        if (match != null) {
            val titlePart = match.groupValues[1].replace(Regex("[\\.\\s_\\-]+"), " ").trim()
            val yearPart = match.groupValues[2]
            return "$titlePart $yearPart".trim()
        }
        // Fallback: just replace dots/underscores with spaces
        return rawTitle.replace(Regex("[\\._\\-]+"), " ").trim()
    }

    suspend fun fetchPosterUrl(title: String): String? = withContext(Dispatchers.IO) {
        if (PlaybackGlobalState.isVideoPlayerOnScreen.value) return@withContext null

        val cleanedTitle = cleanTitleForSearch(title)
        if (apiKey.isBlank() || cleanedTitle.isBlank()) return@withContext null
        
        val now = System.currentTimeMillis()
        if (now < circuitBreakerResetTime) return@withContext null

        val negativeCacheTime = negativeCache[cleanedTitle]
        if (negativeCacheTime != null && now - negativeCacheTime < 30 * 60 * 1000L) {
            return@withContext null
        }

        // Fast path check
        inFlightRequests[cleanedTitle]?.let { deferred ->
            return@withContext deferred.await()
        }

        coroutineScope {
            val deferred = async {
                performFetch(cleanedTitle)
            }
            val existing = inFlightRequests.putIfAbsent(cleanedTitle, deferred)
            if (existing != null) {
                deferred.cancel()
                existing.await()
            } else {
                try {
                    val result = deferred.await()
                    if (result == null) {
                        negativeCache[cleanedTitle] = System.currentTimeMillis()
                    }
                    result
                } finally {
                    inFlightRequests.remove(cleanedTitle)
                }
            }
        }
    }

    private fun performFetch(cleanedTitle: String): String? {
        try {
            val query = java.net.URLEncoder.encode(cleanedTitle, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$query"
            
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                consecutiveTimeouts = 0
                if (!response.isSuccessful) return null
                
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                
                if (results != null && results.length() > 0) {
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val posterPath = item.optString("poster_path", "")
                        if (posterPath.isNotBlank() && posterPath != "null") {
                            return "https://image.tmdb.org/t/p/w500$posterPath"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching poster for $cleanedTitle", e)
            if (e is SocketTimeoutException || e is InterruptedIOException) {
                consecutiveTimeouts++
                if (consecutiveTimeouts >= 3) {
                    Log.w(TAG, "Circuit breaker opened after 3 timeouts")
                    circuitBreakerResetTime = System.currentTimeMillis() + 10 * 60 * 1000L
                }
            }
        }
        return null
    }
}
