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

        return SubtitleSearchQuery(
            query = queryText,
            movieHash = hash,
            languages = preferredLanguages,
            type = mediaType
        )
    }
}
