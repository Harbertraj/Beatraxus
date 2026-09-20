package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.lyrics.providers.*

object LyricsProviderRegistry {
    val providers: List<LyricsProvider> = listOf(
        BinilyricsProvider(),
        BetterlyricsProvider(),
        BetterlyricsPortatoProvider(),
        PaxsenixProvider(),
        PaxsenixSpotifyProvider(),
        LyricsplusProvider(),
        SimpmusicProvider(),
        UnisonProvider(),
        YoutubeCaptionsProvider(),
        YoutubeMusicProvider(),
        MegalobizProvider(),
        KugouProvider(),
        LrclibProvider()
    )

    val defaultOrder: List<String> = listOf(
        "binilyrics",
        "betterlyrics",
        "betterlyrics_portato",
        "paxsenix",
        "paxsenix_spotify",
        "lyricsplus",
        "simpmusic",
        "unison",
        "youtube_captions",
        "youtube_music",
        "megalobiz",
        "kugou",
        "lrclib"
    )

    val defaultEnabled: Set<String> = setOf("lrclib")
}
