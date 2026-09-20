package com.beatraxus.app.subtitles.domain

data class MediaEpisodeInfo(
    val seriesName: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String? = null,
    val year: Int? = null
)

data class ParsedMedia(
    val rawTitle: String,
    val cleanTitle: String,
    val year: Int? = null,
    val episodeInfo: MediaEpisodeInfo? = null,
    val releaseTokens: Set<String> = emptySet()
) {
    val isEpisode: Boolean get() = episodeInfo != null
}
