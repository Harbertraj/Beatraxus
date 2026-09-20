package com.beatraxus.app.subtitles.api

import com.beatraxus.app.BuildConfig

data class SubtitleApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val appName: String = "BeatRaxus",
    val userAgent: String = "BeatRaxus v${BuildConfig.VERSION_NAME}",
    val apiKeyProvider: () -> String = { BuildConfig.OPENSUBTITLES_API_KEY }
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1/"
    }
}
