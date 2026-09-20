package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class BetterlyricsProvider : LyricsProvider {
    override val id = "betterlyrics"
    override val displayName = "BetterLyrics"
    override val description = "Apple Music word timings (TTML)"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO: I need the exact search path and response format for lyrics-api.boidu.dev

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
