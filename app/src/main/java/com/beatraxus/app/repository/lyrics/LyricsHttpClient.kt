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
                // Only default the User-Agent: the YouTube providers must present a browser/app UA.
                val original = chain.request()
                val req = if (original.header("User-Agent") == null) {
                    original.newBuilder()
                        .header("User-Agent", "Beatraxus Music Player (https://github.com/Harbertraj/Beatraxus)")
                        .build()
                } else original
                chain.proceed(req)
            }
            .build()
    }
}
