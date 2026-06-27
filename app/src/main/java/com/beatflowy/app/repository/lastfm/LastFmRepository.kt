package com.beatflowy.app.repository.lastfm

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatflowy.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest

private val Context.lastFmDataStore: DataStore<Preferences> by preferencesDataStore(name = "lastfm_settings")

class LastFmRepository(private val context: Context) {

    private val TAG = "LastFmRepository"
    private val apiKey = BuildConfig.LASTFM_API_KEY
    private val sharedSecret = BuildConfig.LASTFM_SECRET

    private val service: LastFmService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://ws.audioscrobbler.com/2.0/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LastFmService::class.java)
    }

    companion object {
        private val SESSION_KEY = stringPreferencesKey("session_key")
        private val USERNAME = stringPreferencesKey("username")
    }

    suspend fun exportPreferences(): Map<String, Any> {
        return context.lastFmDataStore.data.first().asMap().mapKeys { it.key.name }.filterValues { it != null } as Map<String, Any>
    }

    suspend fun importPreferences(map: Map<String, Any>) {
        context.lastFmDataStore.edit { preferences ->
            map.forEach { (keyName, value) ->
                when (value) {
                    is String -> preferences[stringPreferencesKey(keyName)] = value
                    is Boolean -> preferences[booleanPreferencesKey(keyName)] = value
                    is Float -> preferences[floatPreferencesKey(keyName)] = value
                    is Int -> preferences[intPreferencesKey(keyName)] = value
                }
            }
        }
    }

    val sessionKey: Flow<String?> = context.lastFmDataStore.data.map { it[SESSION_KEY] }
    val username: Flow<String?> = context.lastFmDataStore.data.map { it[USERNAME] }

    suspend fun saveSession(name: String, key: String) {
        context.lastFmDataStore.edit { prefs ->
            prefs[SESSION_KEY] = key
            prefs[USERNAME] = name
        }
    }

    suspend fun logout() {
        context.lastFmDataStore.edit { prefs ->
            prefs.remove(SESSION_KEY)
            prefs.remove(USERNAME)
        }
    }

    suspend fun fetchSession(token: String): LastFmSession? {
        val params = mutableMapOf(
            "api_key" to apiKey,
            "method" to "auth.getSession",
            "token" to token
        )
        val sig = generateSignature(params)
        return try {
            Log.d(TAG, "Fetching session for token: $token")
            val response = service.getSession(apiKey = apiKey, token = token, apiSig = sig)
            Log.d(TAG, "Session response: ${response.session}")
            response.session?.also {
                saveSession(it.name, it.key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching session", e)
            null
        }
    }

    suspend fun updateNowPlaying(artist: String, track: String, album: String? = null, durationMs: Long? = null, sessionKey: String) {
        Log.d(TAG, "Updating now playing: $artist - $track")
        val params = mutableMapOf(
            "api_key" to apiKey,
            "method" to "track.updateNowPlaying",
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track
        )
        album?.let { params["album"] = it }
        durationMs?.let { params["duration"] = (it / 1000).toString() }
        
        val sig = generateSignature(params)
        try {
            val response = service.updateNowPlaying(
                apiKey = apiKey,
                apiSig = sig,
                sessionKey = sessionKey,
                artist = artist,
                track = track,
                album = album,
                duration = durationMs?.let { it / 1000 }
            )
            Log.d(TAG, "Update now playing response: $response")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating now playing", e)
        }
    }

    suspend fun scrobble(artist: String, track: String, album: String? = null, timestamp: Long, durationMs: Long? = null, sessionKey: String) {
        Log.d(TAG, "Scrobbling: $artist - $track")
        val params = mutableMapOf(
            "api_key" to apiKey,
            "method" to "track.scrobble",
            "sk" to sessionKey,
            "artist[0]" to artist,
            "track[0]" to track,
            "timestamp[0]" to timestamp.toString()
        )
        album?.let { params["album[0]"] = it }
        durationMs?.let { params["duration[0]"] = (it / 1000).toString() }

        val sig = generateSignature(params)
        try {
            val response = service.scrobble(
                apiKey = apiKey,
                apiSig = sig,
                sessionKey = sessionKey,
                artist = artist,
                track = track,
                timestamp = timestamp,
                album = album,
                duration = durationMs?.let { it / 1000 }
            )
            Log.d(TAG, "Scrobble response: $response")
        } catch (e: Exception) {
            Log.e(TAG, "Error scrobbling", e)
        }
    }

    private fun generateSignature(params: Map<String, String>): String {
        val sortedParams = params.toSortedMap()
        val signatureBase = StringBuilder()
        for ((key, value) in sortedParams) {
            signatureBase.append(key).append(value)
        }
        signatureBase.append(sharedSecret)
        return md5(signatureBase.toString())
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    suspend fun getArtistInfo(artist: String) = try {
        service.getArtistInfo(apiKey = apiKey, artist = artist).artist
    } catch (e: Exception) {
        null
    }

    suspend fun getTrackInfo(artist: String, track: String, username: String? = null) = try {
        service.getTrackInfo(apiKey = apiKey, artist = artist, track = track, username = username).track
    } catch (e: Exception) {
        null
    }

    suspend fun getAlbumInfo(artist: String, album: String) = try {
        service.getAlbumInfo(apiKey = apiKey, artist = artist, album = album).album
    } catch (e: Exception) {
        null
    }
}
