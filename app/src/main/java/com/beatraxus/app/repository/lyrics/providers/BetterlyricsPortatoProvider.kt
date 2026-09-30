package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

/**
 * Portato BetterLyrics Provider.
 *
 * Web research conducted:
 * - Searched GitHub repositories and forks for "Portato", "BetterLyrics Portato", "lyrics-api", "boidu", and "portato lyrics api".
 * - Checked Beatraxus README and codebase for references or documentation.
 * - No verified public base URL or endpoint specification for "Portato" is publicly available.
 *
 * To enable this provider, please provide:
 * 1. Public base URL / endpoint (e.g., https://portato.example.com/api/getLyrics)
 * 2. Query parameter schema
 * 3. Sample JSON or TTML response structure
 */
class BetterlyricsPortatoProvider : LyricsProvider {
    override val id = "betterlyrics_portato"
    override val displayName = "BetterLyrics (Portato)"
    override val description = "External lyrics provider API (Endpoint unconfigured)"
    override val granularity = LyricsGranularity.WORD
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // Awaiting verified public endpoint details

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
