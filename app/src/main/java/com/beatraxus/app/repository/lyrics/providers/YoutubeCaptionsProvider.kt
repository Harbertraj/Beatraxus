package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class YoutubeCaptionsProvider : LyricsProvider {
    override val id = "youtube_captions"
    override val displayName = "YouTube Captions"
    override val description = "Auto-generated or custom captions"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = true
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
