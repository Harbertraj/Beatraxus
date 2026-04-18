package com.beatflowy.app.repository

import com.beatflowy.app.model.Song
import kotlinx.coroutines.delay

class LyricsRepository {
    suspend fun fetchLyrics(song: Song): List<Pair<Long, String>> {
        // Simulate a delay (e.g., from network or complex parsing)
        delay(300)
        
        // This is a simulated fetch. In a real app, you'd parse an .lrc file or fetch from an API.
        return listOf(
            0L to "Lyrics for ${song.title}",
            5000L to "Performed by ${song.artist}",
            10000L to "From the album: ${song.album}",
            15000L to "Beatraxus - Premium Audio",
            20000L to "Floating in pure hi-res sound",
            25000L to "Feel the rhythm of the waves",
            30000L to "Music is the language of life",
            40000L to "--- Lyric End ---"
        )
    }
}