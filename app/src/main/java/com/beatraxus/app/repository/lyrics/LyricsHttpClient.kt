package com.beatraxus.app.repository.lyrics

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object LyricsHttpClient {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "Beatraxus Music Player (https://github.com/Harbertraj/Beatraxus)")
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}
