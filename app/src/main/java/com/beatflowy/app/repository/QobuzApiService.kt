package com.beatflowy.app.repository

import android.webkit.CookieManager
import com.google.gson.annotations.SerializedName
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface QobuzApiService {

    @GET("api/get-music")
    suspend fun searchMusic(
        @Query("q") query: String,
        @Query("offset") offset: Int
    ): Response<QobuzResponse<QobuzSearchData>>

    @GET("api/get-music")
    suspend fun getAlbumTracks(
        @Query("album_id") albumId: String
    ): Response<QobuzResponse<QobuzSearchData>>

    companion object {
        const val BASE_URL = "https://qobuz.squid.wtf/"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private var okHttpClient: OkHttpClient? = null

        fun getOkHttpClient(): OkHttpClient {
            return okHttpClient ?: synchronized(this) {
                okHttpClient ?: buildOkHttpClient().also { okHttpClient = it }
            }
        }

        private fun buildOkHttpClient(): OkHttpClient {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            
            return OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .cookieJar(WebViewCookieJar())
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Origin", "https://qobuz.squid.wtf")
                        .header("Referer", "https://qobuz.squid.wtf/")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}

class WebViewCookieJar : CookieJar {
    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        for (cookie in cookies) {
            cookieManager.setCookie(urlString, cookie.toString())
        }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val urlString = url.toString()
        val cookiesString = cookieManager.getCookie(urlString)
        if (cookiesString.isNullOrEmpty()) return emptyList()

        return cookiesString.split(";").mapNotNull {
            val cookiePart = it.trim()
            if (cookiePart.isEmpty()) return@mapNotNull null
            
            // Try parsing as a full cookie string first
            Cookie.parse(url, cookiePart) ?: run {
                // If parse fails, handle simple name=value pairs
                val parts = cookiePart.split("=", limit = 2)
                if (parts.size == 2) {
                    try {
                        Cookie.Builder()
                            .name(parts[0].trim())
                            .value(parts[1].trim())
                            .domain(url.host)
                            .build()
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
        }
    }
}

data class QobuzResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T?,
    @SerializedName("error") val error: String?
)

data class QobuzSearchData(
    @SerializedName("tracks") val tracks: QobuzItems<QobuzTrack>?,
    @SerializedName("albums") val albums: QobuzItems<QobuzAlbum>?
)

data class QobuzItems<T>(
    @SerializedName("items") val items: List<T>
)

data class QobuzTrack(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("performer") val performer: QobuzPerformer?,
    @SerializedName("album") val album: QobuzAlbumShort,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("media_number") val trackNumber: Int?,
    @SerializedName("maximum_bit_depth") val maxBitDepth: Int?,
    @SerializedName("maximum_sampling_rate") val maxSampleRate: Float?
)

data class QobuzAlbum(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: QobuzPerformer?,
    @SerializedName("image") val image: QobuzImage?,
    @SerializedName("tracks_count") val tracksCount: Int?
)

data class QobuzAlbumShort(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("image") val image: QobuzImage?
)

data class QobuzPerformer(
    @SerializedName("name") val name: String?
)

data class QobuzImage(
    @SerializedName("small") val small: String?,
    @SerializedName("thumbnail") val thumbnail: String?,
    @SerializedName("large") val large: String?
)
