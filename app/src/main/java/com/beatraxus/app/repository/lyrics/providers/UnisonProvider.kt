package com.beatraxus.app.repository.lyrics.providers

import com.beatraxus.app.repository.LyricsResult
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsQuery

class UnisonProvider : LyricsProvider {
    override val id = "unison"
    override val displayName = "Unison"
    override val description = "Metadata lookup for synced lyrics"
    override val granularity = LyricsGranularity.LINE
    override val requiresVideoId = false
    override val experimental = false
    override val isConfigured = false // ENDPOINT_TODO: Need the exact path and JSON structure for unison.boidu.dev

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        return null
    }
}
