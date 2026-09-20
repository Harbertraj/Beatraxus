package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class BetterlyricsPortatoProvider : LyricsProvider {
    override val id = "betterlyrics_portato"
    override val displayName = "BetterLyrics (Portato)"
    override val description = "External lyrics provider API"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
