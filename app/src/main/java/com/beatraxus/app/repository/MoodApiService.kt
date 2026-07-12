package com.beatraxus.app.repository

import android.util.Log
import com.beatraxus.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MoodApiService {
    private val client = OkHttpClient()
    private val TAG = "MoodApiService"
    private val apiKey = BuildConfig.LASTFM_API_KEY

    companion object {
        // Must match the 15 moods used in MainScreen.kt
        val STANDARD_MOODS = listOf(
            "Sleep", "Calm", "Focus", "Energetic", "Workout", "Happy", "Sad",
            "Romantic", "Party", "Motivational", "Aggressive", "Meditation",
            "Emotional", "Epic", "Dark"
        )

        // Raw Last.fm tag -> our mood vocabulary. A single tag list can map to MANY moods.
        private val MOOD_TAG_MAP = mapOf(
            "chill" to "Calm", "chillout" to "Calm", "relax" to "Calm", "relaxing" to "Calm",
            "mellow" to "Calm", "soft" to "Calm", "acoustic" to "Calm",
            "sleep" to "Sleep", "ambient" to "Sleep", "lullaby" to "Sleep",
            "study" to "Focus", "concentration" to "Focus", "instrumental" to "Focus", "lo-fi" to "Focus",
            "energetic" to "Energetic", "upbeat" to "Energetic", "power" to "Energetic",
            "workout" to "Workout", "gym" to "Workout", "running" to "Workout", "cardio" to "Workout",
            "happy" to "Happy", "feel good" to "Happy", "uplifting" to "Happy", "fun" to "Happy",
            "sad" to "Sad", "melancholy" to "Sad", "melancholic" to "Sad", "heartbreak" to "Sad", "breakup" to "Sad",
            "romantic" to "Romantic", "love" to "Romantic", "love songs" to "Romantic", "sensual" to "Romantic",
            "party" to "Party", "dance" to "Party", "club" to "Party", "clubbing" to "Party",
            "motivational" to "Motivational", "inspirational" to "Motivational", "inspiring" to "Motivational", "workout motivation" to "Motivational",
            "aggressive" to "Aggressive", "angry" to "Aggressive", "intense" to "Aggressive", "hardcore" to "Aggressive",
            "meditation" to "Meditation", "yoga" to "Meditation", "spiritual" to "Meditation", "zen" to "Meditation",
            "emotional" to "Emotional", "moody" to "Emotional", "bittersweet" to "Emotional",
            "epic" to "Epic", "cinematic" to "Epic", "soundtrack" to "Epic", "orchestral" to "Epic",
            "dark" to "Dark", "gothic" to "Dark", "industrial" to "Dark", "haunting" to "Dark"
        )

        private val BLACKLIST = listOf(
            "seen live", "favorites", "favourite", "awesome", "cool", "beautiful",
            "amazing", "love this song", "best", "female vocalists", "male vocalists",
            "80s", "90s", "00s", "70s", "60s", "2000s", "2010s", "2020s", "spotify", "apple music"
        )
    }

    /** Returns ALL moods this track matches, based on Last.fm's community tags. */
    suspend fun fetchAccurateMoodTags(artist: String, title: String): List<String> = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext emptyList()
        try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&api_key=$apiKey" +
                    "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                    "&track=${java.net.URLEncoder.encode(title, "UTF-8")}&format=json"
            fetchMoodsFromUrl(url)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching mood tags", e)
            emptyList()
        }
    }

    private fun fetchMoodsFromUrl(url: String): List<String> {
        val request = Request.Builder().url(url).header("User-Agent", "Beatraxus/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val root = json.optJSONObject("track") ?: return emptyList()
            val tags = root.optJSONObject("toptags")?.optJSONArray("tag") ?: return emptyList()

            val matched = linkedSetOf<String>()
            for (i in 0 until tags.length()) {
                val tagName = tags.getJSONObject(i).optString("name").lowercase().trim()
                if (BLACKLIST.any { tagName.contains(it) }) continue
                MOOD_TAG_MAP[tagName]?.let { matched.add(it) }
            }
            return matched.toList()
        }
    }
}
