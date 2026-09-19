package com.beatraxus.app.repository

import android.util.Log
import com.beatraxus.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoMetadataRepository {
    private val client = OkHttpClient()
    private val TAG = "VideoMetadataRepository"
    
    // TMDb API Key should be provided in build.gradle.kts / local.properties
    // If missing, we skip online search.
    private val apiKey = BuildConfig.TMDB_API_KEY

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
        val cleanedTitle = cleanTitleForSearch(title)
        if (apiKey.isBlank() || cleanedTitle.isBlank()) return@withContext null
        
        try {
            val query = java.net.URLEncoder.encode(cleanedTitle, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$query"
            
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                
                if (results != null && results.length() > 0) {
                    // Pick the first result that has a poster_path
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val posterPath = item.optString("poster_path", "")
                        if (posterPath.isNotBlank() && posterPath != "null") {
                            return@withContext "https://image.tmdb.org/t/p/w500$posterPath"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching poster for $title", e)
        }
        null
    }
}
