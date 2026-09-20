package com.beatraxus.app.repository.lyrics

import kotlin.math.roundToInt

val LyricsQuery.cleanTitle: String
    get() = LyricsMatcher.cleanTitle(title)

val LyricsQuery.primaryArtist: String
    get() = LyricsMatcher.primaryArtists(artist).firstOrNull() ?: artist.trim()

val LyricsQuery.durationSec: Int
    get() = (durationMs / 1000.0).roundToInt()
