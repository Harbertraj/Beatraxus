package com.beatraxus.app.repository

import android.util.Log
import com.beatraxus.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GenreApiService {
    private val client = OkHttpClient()
    private val TAG = "GenreApiService"
    private val apiKey = BuildConfig.LASTFM_API_KEY

    // Standard genres for organization
    companion object {
        val STANDARD_GENRES = listOf(
            "Pop", "Rock", "Hip-Hop", "R&B", "Electronic", "Dance", "EDM",
            "Classical", "Jazz", "Blues", "Country", "Folk", "Reggae",
            "Metal", "Alternative", "Indie", "Soul", "Funk", "Latin",
            "Soundtrack", "Instrumental", "Lo-Fi", "Ambient", "Tamil", "Hindi", "Telugu", "Malayalam",
            "K-Pop", "J-Pop", "Phonk", "Synthwave", "Disco", "Punk", "Gospel", "New Age",
            "House", "Techno", "Trance", "Dubstep", "Grime", "Trap", "Rap", "Hardstyle",
            "Heavy Metal", "Death Metal", "Black Metal", "Progressive Rock", "Hard Rock",
            "Soft Rock", "Psych Rock", "Experimental", "Avant-Garde", "Global", "Afrobeats"
        )
        
        private val GENRE_MAP = mapOf(
            "progressive rock" to "Rock",
            "hard rock" to "Rock",
            "soft rock" to "Rock",
            "classic rock" to "Rock",
            "heavy metal" to "Metal",
            "thrash metal" to "Metal",
            "death metal" to "Metal",
            "black metal" to "Metal",
            "nu metal" to "Metal",
            "synthpop" to "Pop",
            "dream pop" to "Pop",
            "indie pop" to "Indie",
            "indie rock" to "Indie",
            "post-rock" to "Rock",
            "shoegaze" to "Rock",
            "techno" to "Electronic",
            "house" to "Dance",
            "deep house" to "Dance",
            "tech house" to "Dance",
            "progressive house" to "Dance",
            "trance" to "Electronic",
            "dubstep" to "EDM",
            "drum and bass" to "Electronic",
            "dnb" to "Electronic",
            "rap" to "Hip-Hop",
            "trap" to "Hip-Hop",
            "gangsta rap" to "Hip-Hop",
            "lo-fi hip hop" to "Lo-Fi",
            "contemporary r&b" to "R&B",
            "soul" to "Soul",
            "neo-soul" to "Soul",
            "bluegrass" to "Country",
            "americana" to "Folk",
            "world" to "Global",
            "soundtrack" to "Soundtrack",
            "ost" to "Soundtrack",
            "movie" to "Soundtrack",
            "bollywood" to "Hindi",
            "kollywood" to "Tamil",
            "tollywood" to "Telugu",
            "mollywood" to "Malayalam",
            "carnatic" to "Classical",
            "hindustani" to "Classical",
            "chill" to "Lo-Fi",
            "chillout" to "Lo-Fi",
            "lo-fi" to "Lo-Fi",
            "vaporwave" to "Synthwave",
            "chillwave" to "Synthwave"
        )
    }

    suspend fun fetchAccurateGenre(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (artist.isBlank()) return@withContext null
        
        try {
            // 1. Try track tags first (most accurate for the specific song)
            if (title.isNotBlank()) {
                val trackUrl = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&api_key=$apiKey&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}&track=${java.net.URLEncoder.encode(title, "UTF-8")}&format=json"
                val trackGenre = fetchFromUrl(trackUrl)
                if (trackGenre != null) return@withContext trackGenre
            }

            // 2. Fallback to album tags if available (conceptually similar)
            // (Skipping for now as we don't always have album info here)

            // 3. Fallback to artist tags (general but better than nothing)
            val artistUrl = "https://ws.audioscrobbler.com/2.0/?method=artist.getInfo&api_key=$apiKey&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}&format=json"
            return@withContext fetchFromUrl(artistUrl)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching genre from online service", e)
        }
        return@withContext null
    }

    private fun fetchFromUrl(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Beatraxus/1.0")
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            
            // Handle both track and artist responses
            val root = json.optJSONObject("track") ?: json.optJSONObject("artist")
            val toptags = root?.optJSONObject("toptags") ?: root?.optJSONObject("tags")
            val tags = toptags?.optJSONArray("tag")
            
            if (tags != null && tags.length() > 0) {
                val rawTags = mutableListOf<String>()
                for (i in 0 until tags.length()) {
                    val tagName = tags.getJSONObject(i).optString("name").lowercase()
                    if (isValidGenre(tagName)) {
                        rawTags.add(tagName)
                    }
                }
                
                return organizeGenre(rawTags)
            }
        }
        return null
    }

    private fun organizeGenre(tags: List<String>): String? {
        if (tags.isEmpty()) return null
        
        // 1. Check for exact matches in standard genres first (strongest matches)
        for (tag in tags) {
            if (STANDARD_GENRES.any { it.equals(tag, ignoreCase = true) }) {
                return STANDARD_GENRES.first { it.equals(tag, ignoreCase = true) }
            }
        }
        
        // 2. Check mapping for common sub-genres
        for (tag in tags) {
            GENRE_MAP[tag]?.let { return it }
        }
        
        // 3. Check for partial matches in standard genres
        for (tag in tags) {
            for (standard in STANDARD_GENRES) {
                if (tag.contains(standard.lowercase()) || standard.lowercase().contains(tag)) {
                    // Avoid too generic matches
                    if (tag.length > 3) return standard
                }
            }
        }
        
        // 4. If no standard matches, take the first valid tag and clean it
        return tags.firstOrNull()?.split(" ")?.joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }


    private fun isValidGenre(tag: String): Boolean {
        val blacklist = listOf(
            "seen live", "favorites", "favourite", "awesome", "cool", 
            "beautiful", "amazing", "love", "best", "check this out",
            "female vocalists", "male vocalists", "under 2000 listeners",
            "american", "british", "singer-songwriter", "classic",
            "80s", "90s", "00s", "70s", "60s", "2000s", "2010s", "2020s",
            "scrobble", "last.fm", "spotify", "apple music"
        )
        return !blacklist.any { tag.lowercase().contains(it) }
    }
}
