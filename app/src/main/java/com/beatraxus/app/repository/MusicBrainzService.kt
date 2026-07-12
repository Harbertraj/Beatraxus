package com.beatraxus.app.repository

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicBrainzService {
    private val client = OkHttpClient()
    private val TAG = "MusicBrainzService"
    // MusicBrainz's API usage policy requires a descriptive User-Agent.
    private val userAgent = "Beatraxus/1.0 ( https://github.com/Harbertraj/Beatraxus )"

    suspend fun fetchReleaseYear(artist: String, title: String, album: String?): Int? = withContext(Dispatchers.IO) {
        if (artist.isBlank()) return@withContext null
        try {
            // 1. Prefer album/release lookup — closer to the true original release year
            if (!album.isNullOrBlank()) {
                fetchYearFromRelease(artist, album)?.let { return@withContext it }
            }
            // 2. Fallback to recording (song-level) lookup
            fetchYearFromRecording(artist, title)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching year for $artist - $title", e)
            null
        }
    }

    private fun fetchYearFromRelease(artist: String, album: String): Int? {
        val query = "release:\"${escape(album)}\" AND artist:\"${escape(artist)}\""
        val url = "https://musicbrainz.org/ws/2/release/?query=${java.net.URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=5"
        val body = get(url) ?: return null
        val releases = JSONObject(body).optJSONArray("releases") ?: return null
        var bestYear: Int? = null
        for (i in 0 until releases.length()) {
            parseYear(releases.getJSONObject(i).optString("date", ""))?.let { year ->
                if (bestYear == null || year < bestYear!!) bestYear = year // earliest = original release
            }
        }
        return bestYear
    }

    private fun fetchYearFromRecording(artist: String, title: String): Int? {
        val query = "recording:\"${escape(title)}\" AND artist:\"${escape(artist)}\""
        val url = "https://musicbrainz.org/ws/2/recording/?query=${java.net.URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=5"
        val body = get(url) ?: return null
        val recordings = JSONObject(body).optJSONArray("recordings") ?: return null
        var bestYear: Int? = null
        for (i in 0 until recordings.length()) {
            val rec = recordings.getJSONObject(i)
            parseYear(rec.optString("first-release-date", ""))?.let { year ->
                if (bestYear == null || year < bestYear!!) bestYear = year
            }
            rec.optJSONArray("releases")?.let { releases ->
                for (j in 0 until releases.length()) {
                    parseYear(releases.getJSONObject(j).optString("date", ""))?.let { year ->
                        if (bestYear == null || year < bestYear!!) bestYear = year
                    }
                }
            }
        }
        return bestYear
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun parseYear(dateStr: String): Int? {
        if (dateStr.length < 4) return null
        return dateStr.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
    }

    private fun escape(s: String) = s.replace("\"", "\\\"")
}
