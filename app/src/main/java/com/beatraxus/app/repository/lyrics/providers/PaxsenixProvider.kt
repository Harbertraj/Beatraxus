package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class PaxsenixProvider : LyricsProvider {
    override val id = "paxsenix"
    override val displayName = "PaxSenix (Apple Music)"
    override val description = "Apple Music word timings proxy"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO: Need the exact path and JSON structure for lyrics.paxsenix.org

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
