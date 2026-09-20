package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.LyricsResult

interface LyricsProvider {
    val id: String
    val displayName: String
    val description: String
    val granularity: LyricsGranularity
    val requiresVideoId: Boolean
    val experimental: Boolean
    val isConfigured: Boolean get() = true

    suspend fun fetch(query: LyricsQuery): LyricsResult?
}
