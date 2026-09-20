package com.beatraxus.app.repository

import androidx.annotation.Keep
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.providers.LrclibProvider
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

@Keep
interface LrcLibService {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("artist_name") artist: String?,
        @Query("track_name") title: String?,
        @Query("album_name") album: String?,
        @Query("duration") duration: Int?
    ): Response<LrcLibResponse>

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("track_name") trackName: String? = null,
        @Query("artist_name") artistName: String? = null,
        @Query("q") query: String? = null
    ): Response<List<LrcLibResponse>>
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
    private val lrclibProvider = LrclibProvider()

    suspend fun fetchLyrics(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long
    ): LyricsResult? {
        return lrclibProvider.fetch(
            LyricsQuery(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs
            )
        )
    }
}
