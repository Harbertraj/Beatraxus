package com.beatflowy.app.repository

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

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
    val name: String,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Int?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

class OnlineLyricsSource {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(LrcLibService::class.java)

    suspend fun fetchLyrics(artist: String, title: String, album: String?, durationMs: Long): String? {
        val durationSec = (durationMs / 1000).toInt()
        
        // Try precise "get" first - most accurate
        try {
            val response = service.getLyrics(artist, title, album, durationSec)
            if (!response.instrumental && (response.syncedLyrics != null || response.plainLyrics != null)) {
                return response.syncedLyrics ?: response.plainLyrics
            }
        } catch (e: Exception) {
            // Precise get failed, move to search
        }

        // Fallback to "search" with cleaned title and better matching logic
        return try {
            val cleanTitle = title.replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("(?i)official (video|audio|lyrics)"), "")
                .replace(Regex("(?i)ft\\.?|feat\\.?"), "")
                .trim()
            
            val query = "$artist $cleanTitle"
            val searchResults = service.searchLyrics(query)
            
            // Find best match in search results by checking duration and name similarity
            val bestMatch = searchResults
                .filter { !it.instrumental && (it.syncedLyrics != null || it.plainLyrics != null) }
                .minByOrNull { result ->
                    // Calculate a "penalty" score - lower is better
                    var score = 0
                    
                    // Penalty for duration difference (very important for sync)
                    val durationDiff = if (result.duration != null) Math.abs(result.duration - durationSec) else 100
                    score += durationDiff * 10
                    
                    // Penalty for name mismatch (Check Artist and Album as requested)
                    val artistMatch = result.artistName?.contains(artist, ignoreCase = true) == true
                    val albumMatch = album?.let { result.albumName?.contains(it, ignoreCase = true) } ?: false
                    
                    if (!artistMatch) score += 100
                    if (!albumMatch) score += 50 // Album is secondary but helps identify correct version
                    if (result.trackName?.contains(cleanTitle, ignoreCase = true) != true) score += 100
                    
                    // Prefer synced lyrics
                    if (result.syncedLyrics == null) score += 30
                    
                    score
                }

            // Only accept if it's a reasonably good match
            val isGoodMatch = bestMatch != null && (
                (bestMatch.artistName?.equals(artist, ignoreCase = true) == true && 
                 bestMatch.trackName?.contains(cleanTitle, ignoreCase = true) == true) ||
                (bestMatch.duration != null && Math.abs(bestMatch.duration - durationSec) < 5)
            )

            if (isGoodMatch) {
                bestMatch?.syncedLyrics ?: bestMatch?.plainLyrics
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
