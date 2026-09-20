package com.beatraxus.app.subtitles.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface OpenSubtitlesApi {

    @POST("login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>

    @GET("subtitles")
    suspend fun searchSubtitles(
        @Query("query") query: String? = null,
        @Query("imdb_id") imdbId: Long? = null,
        @Query("tmdb_id") tmdbId: Long? = null,
        @Query("moviehash") movieHash: String? = null,
        @Query("languages") languages: String? = null,
        @Query("type") type: String? = null,
        @Query("order_by") orderBy: String? = null,
        @Query("order_direction") orderDirection: String? = null,
        @Query("page") page: Int? = null
    ): Response<SubtitlesResponse>

    @POST("download")
    suspend fun download(
        @Header("Authorization") authorization: String? = null,
        @Body request: DownloadRequest
    ): Response<DownloadResponse>

    @GET("infos/languages")
    suspend fun getLanguages(): Response<LanguagesResponse>
}
