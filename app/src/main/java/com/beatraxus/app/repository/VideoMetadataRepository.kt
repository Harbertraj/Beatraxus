package com.beatraxus.app.repository

import android.content.Context
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import java.io.InterruptedIOException
import java.net.URLEncoder

class VideoMetadataRepository(context: Context) {
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

    // Persistent cache: positive results never expire, "no result" caches for 24h
    private val prefs = context.getSharedPreferences("video_poster_cache", Context.MODE_PRIVATE)

    private var consecutiveTimeouts = 0
    private var circuitBreakerResetTime = 0L

    fun resetFailureState() {
        consecutiveTimeouts = 0
        circuitBreakerResetTime = 0L
        negativeCache.clear()
    }

    fun getPosterUrlFromCache(title: String): String? {
        val key = posterKey(title)
        if (key.isBlank()) return null
        val cachedJson = prefs.getString(key, null) ?: return null
        return try {
            val json = JSONObject(cachedJson)
            val url = json.optString("url", "")
            if (url.isNotEmpty()) url else null
        } catch (e: Exception) {
            null
        }
    }

    fun parseTitle(raw: String): Triple<String, String?, Boolean> {
        // Strip leading site tags like "www.something.tld - " / "www.something.tld_-_"
        var clean = raw.replace(Regex("^(?i)(?:www\\.[a-z0-9]+\\.[a-z]+(?:\\s*-\\s*|_+-_+))"), "")
        // Strip leading [..] or (..) groups
        clean = clean.replace(Regex("^(?:\\[[^\\]]+\\]|\\([^\\)]+\\))\\s*"), "")

        // Cut at first year (19xx/20xx) or SxxExx or quality tokens
        val cutTokens = listOf(
            "\\b(?:19|20)\\d{2}\\b", // year
            "\\bS\\d{1,2}E\\d{1,3}\\b", // episode
            "\\bSeason\\b", // season word
            "\\b(?:2160p|1080p|720p|480p|WEB-DL|WEBRip|HDRip|BluRay|BRRip|x264|x265|H\\.264|HEVC|AAC|DDP|DD5\\.1|Atmos|ESub|HIN|TAM|TEL|KAN|MAL)\\b"
        )
        val cutRegex = Regex("(?i)(" + cutTokens.joinToString("|") + ")")
        
        val match = cutRegex.find(clean)
        val titlePart = if (match != null) {
            clean.substring(0, match.range.first)
        } else {
            clean
        }

        // Replace . _ - with spaces, collapse spaces
        val name = titlePart.replace(Regex("[\\._\\-]+"), " ").replace(Regex("\\s+"), " ").trim()
        
        val yearMatch = Regex("\\b((?:19|20)\\d{2})\\b").find(clean)
        val year = yearMatch?.groupValues?.get(1)

        val isSeries = Regex("(?i)\\bS\\d{1,2}E\\d{1,3}\\b|\\bSeason\\b").containsMatchIn(clean)

        return Triple(name, year, isSeries)
    }

    fun posterKey(title: String): String {
        val (name, year, isSeries) = parseTitle(title)
        return name.lowercase() + if (!isSeries && year != null) "|$year" else ""
    }

    suspend fun fetchPosterUrl(title: String): String? = withContext(Dispatchers.IO) {
        if (PlaybackGlobalState.isVideoPlayerOnScreen.value) return@withContext null

        if (apiKey.isBlank()) return@withContext null
        
        val key = posterKey(title)
        if (key.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        
        // Persistent cache check
        val cachedJson = prefs.getString(key, null)
        if (cachedJson != null) {
            try {
                val json = JSONObject(cachedJson)
                val url = json.optString("url", "")
                if (url.isNotEmpty()) return@withContext url
                val ts = json.optLong("ts", 0L)
                if (now - ts < 24 * 60 * 60 * 1000L) {
                    return@withContext null // cached "no result"
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }

        if (now < circuitBreakerResetTime) return@withContext null

        val negativeCacheTime = negativeCache[key]
        if (negativeCacheTime != null && now - negativeCacheTime < 30 * 60 * 1000L) {
            return@withContext null
        }

        // Fast path check
        inFlightRequests[key]?.let { deferred ->
            return@withContext deferred.await()
        }

        coroutineScope {
            val deferred = async {
                performFetch(title)
            }
            val existing = inFlightRequests.putIfAbsent(key, deferred)
            if (existing != null) {
                deferred.cancel()
                existing.await()
            } else {
                try {
                    val result = deferred.await()
                    if (result != null) {
                        if (result == "NO_RESULT") {
                            prefs.edit().putString(key, JSONObject().put("url", "").put("ts", now).toString()).apply()
                            negativeCache[key] = now
                            null
                        } else {
                            prefs.edit().putString(key, JSONObject().put("url", result).put("ts", now).toString()).apply()
                            result
                        }
                    } else {
                        null
                    }
                } finally {
                    inFlightRequests.remove(key)
                }
            }
        }
    }

    private fun performFetch(title: String): String? {
        val (name, year, isSeries) = parseTitle(title)
        try {
            val query = URLEncoder.encode(name, "UTF-8")
            
            // Try specific endpoint first
            val firstUrl = if (isSeries) {
                "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$query"
            } else {
                "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$query" + (if (year != null) "&year=$year" else "")
            }
            
            var result = doRequest(firstUrl)
            if (result != null) return result

            // Try fallback
            val fallbackUrl = if (isSeries) {
                "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$query"
            } else {
                "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$query"
            }
            result = doRequest(fallbackUrl)
            if (result != null) return result

            // Try multi
            val multiUrl = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$query"
            result = doRequest(multiUrl)
            if (result != null) return result

            // No result found (but HTTP succeeded)
            consecutiveTimeouts = 0
            return "NO_RESULT"

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching poster for $name", e)
            if (e is SocketTimeoutException || e is InterruptedIOException || e is IOException) {
                consecutiveTimeouts++
                if (consecutiveTimeouts >= 3) {
                    Log.w(TAG, "Circuit breaker opened after 3 network failures")
                    circuitBreakerResetTime = System.currentTimeMillis() + 2 * 60 * 1000L
                }
            }
        }
        return null
    }

    private fun doRequest(url: String): String? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP error ${response.code}")
            
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val results = json.optJSONArray("results")
            
            if (results != null && results.length() > 0) {
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val mediaType = item.optString("media_type", "")
                    // For multi search, filter out people
                    if (mediaType.isNotEmpty() && mediaType != "movie" && mediaType != "tv") continue
                    
                    val posterPath = item.optString("poster_path", "")
                    if (posterPath.isNotBlank() && posterPath != "null") {
                        return "https://image.tmdb.org/t/p/w342$posterPath"
                    }
                }
            }
            return null // Valid response but no poster found here
        }
    }
}
