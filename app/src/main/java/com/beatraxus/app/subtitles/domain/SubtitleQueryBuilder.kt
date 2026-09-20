package com.beatraxus.app.subtitles.domain

import com.beatraxus.app.model.Video

object SubtitleQueryBuilder {

    fun build(
        video: Video,
        hash: String? = null,
        preferredLanguages: List<String> = listOf("en")
    ): SubtitleSearchQuery {
        val parsed = FilenameParser.parse(video.displayName.ifBlank { video.title })

        val queryText = parsed.cleanTitle.ifBlank { video.title }
        val mediaType = if (parsed.isEpisode) "episode" else "movie"
        val season = parsed.episodeInfo?.season
        val episode = parsed.episodeInfo?.episode
        val year = parsed.year

        return SubtitleSearchQuery(
            query = queryText,
            movieHash = hash,
            seasonNumber = season,
            episodeNumber = episode,
            year = year,
            languages = preferredLanguages,
            type = mediaType
        )
    }
}
