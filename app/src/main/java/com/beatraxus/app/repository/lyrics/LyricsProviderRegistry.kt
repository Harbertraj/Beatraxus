package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.repository.lyrics.providers.*

object LyricsProviderRegistry {
    val providers: List<LyricsProvider> = listOf(
        PaxsenixProvider(),
        BetterlyricsProvider(),
        LyricsplusProvider(),
        UnisonProvider(),
        SimpmusicProvider(),
        YoutubeMusicProvider(),
        LrclibProvider(),
        KugouProvider(),
        MegalobizProvider(),
        BinilyricsProvider(),
        BetterlyricsPortatoProvider(),
        YoutubeCaptionsProvider()
    )

    val defaultOrder: List<String> = listOf(
        "lrclib",
        "paxsenix",
        "betterlyrics",
        "lyricsplus",
        "unison",
        "simpmusic",
        "youtube_music",
        "kugou",
        "megalobiz",
        "binilyrics",
        "betterlyrics_portato",
        "paxsenix_spotify",
        "youtube_captions"
    )

    val defaultEnabled: Set<String> = setOf(
        "paxsenix",
        "betterlyrics",
        "lyricsplus",
        "unison",
        "simpmusic",
        "youtube_music",
        "lrclib",
        "kugou"
    )
}
