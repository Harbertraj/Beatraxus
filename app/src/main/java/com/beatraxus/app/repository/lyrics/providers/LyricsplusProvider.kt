package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class LyricsplusProvider : LyricsProvider {
    override val id = "lyricsplus"
    override val displayName = "LyricsPlus"
    override val description = "External lyrics provider API"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
