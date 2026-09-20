package com.beatraxus.app.subtitles.domain

import java.util.Locale
import kotlin.math.ln

data class SubtitlePreferences(
    val preferredLanguages: List<String> = listOf("en"),
    val preferHearingImpaired: Boolean = false
)

data class ScoredSubtitle(
    val subtitle: SubtitleResult,
    val score: Float,
    val isBestMatch: Boolean
)

object SubtitleMatchScorer {

    fun scoreAndSort(
        results: List<SubtitleResult>,
        query: SubtitleSearchQuery,
        parsedMedia: ParsedMedia,
        preferences: SubtitlePreferences = SubtitlePreferences()
    ): List<ScoredSubtitle> {
        return results.map { score(it, query, parsedMedia, preferences) }
            .sortedByDescending { it.score }
    }

    fun score(
        result: SubtitleResult,
        query: SubtitleSearchQuery,
        parsedMedia: ParsedMedia,
        preferences: SubtitlePreferences = SubtitlePreferences()
    ): ScoredSubtitle {
        var points = 0f

        val isHashMatch = !query.movieHash.isNullOrBlank()

        if (isHashMatch) {
            points += 1000f
        }

        val isTitleMatch = !parsedMedia.cleanTitle.isBlank() &&
                ((result.featureTitle?.contains(parsedMedia.cleanTitle, ignoreCase = true) == true) ||
                 (result.releaseName?.contains(parsedMedia.cleanTitle, ignoreCase = true) == true) ||
                 (result.fileName.contains(parsedMedia.cleanTitle, ignoreCase = true)))

        if (isTitleMatch) {
            points += 150f
        }

        var isExactEpisodeMatch = false
        if (parsedMedia.isEpisode) {
            val epInfo = parsedMedia.episodeInfo!!
            val releaseNameLower = (result.releaseName ?: result.fileName).lowercase(Locale.ROOT)
            val epPattern1 = "s%02de%02d".format(epInfo.season, epInfo.episode)
            val epPattern2 = "%dx%02d".format(epInfo.season, epInfo.episode)
            val epPattern3 = "e%02d".format(epInfo.episode)

            if (releaseNameLower.contains(epPattern1) || releaseNameLower.contains(epPattern2) || releaseNameLower.contains(epPattern3)) {
                isExactEpisodeMatch = true
                points += 500f
            }
        }

        val isYearMatch = parsedMedia.year != null && result.year == parsedMedia.year
        if (isYearMatch) {
            points += 100f
        }

        val combinedText = "${result.releaseName ?: ""} ${result.fileName}".lowercase(Locale.ROOT)
        var tokenOverlapCount = 0
        parsedMedia.releaseTokens.forEach { token ->
            if (token.length > 1 && combinedText.contains(token.lowercase(Locale.ROOT))) {
                tokenOverlapCount++
            }
        }
        points += tokenOverlapCount * 20f

        val langIndex = preferences.preferredLanguages.indexOf(result.language)
        if (langIndex >= 0) {
            points += (50f - (langIndex * 10f)).coerceAtLeast(10f)
        }

        if (result.isHearingImpaired == preferences.preferHearingImpaired) {
            points += 30f
        }

        val downloadsScore = ln((result.downloadCount + 1).toFloat()) * 3f
        val ratingScore = result.rating * 2f
        points += downloadsScore + ratingScore

        if (result.isMachineTranslated || result.isAiTranslated) {
            points -= 30f
        }

        val isBestMatch = points >= 500f && (isHashMatch || isExactEpisodeMatch || (isYearMatch && isTitleMatch))

        return ScoredSubtitle(
            subtitle = result,
            score = points,
            isBestMatch = isBestMatch
        )
    }
}
