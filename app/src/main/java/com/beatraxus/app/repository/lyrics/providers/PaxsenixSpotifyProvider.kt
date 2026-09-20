package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class PaxsenixSpotifyProvider : LyricsProvider {
    override val id = "paxsenix_spotify"
    override val displayName = "PaxSenix (Spotify)"
    override val description = "Spotify synced lyrics proxy"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO: Need the exact path and JSON structure for lyrics.paxsenix.org

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
