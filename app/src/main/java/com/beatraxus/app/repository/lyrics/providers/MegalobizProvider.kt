package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class MegalobizProvider : LyricsProvider {
    override val id = "megalobiz"
    override val displayName = "Megalobiz"
    override val description = "Community synced lyrics (HTML Scraping)"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = true
    override val isConfigured = false // ENDPOINT_TODO: Scraping is brittle. Please provide stable tags to parse if you want this active.

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
