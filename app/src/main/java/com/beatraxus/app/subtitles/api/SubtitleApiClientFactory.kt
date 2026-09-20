package com.beatraxus.app.subtitles.api

import com.beatraxus.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object SubtitleApiClientFactory {

    fun createOkHttpClient(config: SubtitleApiConfig = SubtitleApiConfig()): OkHttpClient {
        val headerInterceptor = Interceptor { chain ->
            val apiKey = config.apiKeyProvider()
            val requestBuilder = chain.request().newBuilder()
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Api-Key", apiKey)
            }

            chain.proceed(requestBuilder.build())
        }

        val ioRetryInterceptor = Interceptor { chain ->
            val request = chain.request()
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                chain.proceed(request)
            }
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(headerInterceptor)
            .addInterceptor(ioRetryInterceptor)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Api-Key")
                redactHeader("Authorization")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    fun createApi(
        config: SubtitleApiConfig = SubtitleApiConfig(),
        okHttpClient: OkHttpClient = createOkHttpClient(config)
    ): OpenSubtitlesApi {
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenSubtitlesApi::class.java)
    }
}
