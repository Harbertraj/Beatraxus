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

        val cleanQueryTitle = parsedMedia.cleanTitle.lowercase(Locale.ROOT)
        val featureTitleLower = (result.featureTitle ?: "").lowercase(Locale.ROOT)
        val releaseNameLower = (result.releaseName ?: result.fileName).lowercase(Locale.ROOT)
        val fileNameLower = result.fileName.lowercase(Locale.ROOT)

        val isTitleMatch = cleanQueryTitle.isNotBlank() &&
                (featureTitleLower.contains(cleanQueryTitle) ||
                 releaseNameLower.contains(cleanQueryTitle) ||
                 fileNameLower.contains(cleanQueryTitle))

        val isExactTitleMatch = cleanQueryTitle.isNotBlank() &&
                (featureTitleLower == cleanQueryTitle ||
                 releaseNameLower.startsWith(cleanQueryTitle))

        if (isExactTitleMatch) {
            points += 400f
        } else if (isTitleMatch) {
            points += 200f
        }

        var isExactEpisodeMatch = false
        var isWrongEpisode = false

        if (parsedMedia.isEpisode && parsedMedia.episodeInfo != null) {
            val epInfo = parsedMedia.episodeInfo
            val epPattern1 = "s%02de%02d".format(epInfo.season, epInfo.episode)
            val epPattern2 = "%dx%02d".format(epInfo.season, epInfo.episode)
            val epPattern3 = "e%02d".format(epInfo.episode)

            if (releaseNameLower.contains(epPattern1) || releaseNameLower.contains(epPattern2) || releaseNameLower.contains(epPattern3) ||
                fileNameLower.contains(epPattern1) || fileNameLower.contains(epPattern2) || fileNameLower.contains(epPattern3)) {
                isExactEpisodeMatch = true
                points += 800f
            } else {
                val otherEpMatch = Regex("(?i)[sS](\\d{1,2})[eE](\\d{1,2})|(\\d{1,2})x(\\d{1,2})").find(releaseNameLower)
                    ?: Regex("(?i)[sS](\\d{1,2})[eE](\\d{1,2})|(\\d{1,2})x(\\d{1,2})").find(fileNameLower)
                if (otherEpMatch != null) {
                    val sStr = otherEpMatch.groupValues[1].ifEmpty { otherEpMatch.groupValues[3] }
                    val eStr = otherEpMatch.groupValues[2].ifEmpty { otherEpMatch.groupValues[4] }
                    val s = sStr.toIntOrNull()
                    val e = eStr.toIntOrNull()
                    if (s != null && e != null && (s != epInfo.season || e != epInfo.episode)) {
                        isWrongEpisode = true
                        points -= 1000f
                    }
                }
            }
        }

        val isYearMatch = parsedMedia.year != null && result.year == parsedMedia.year
        if (isYearMatch) {
            points += 200f
        } else if (parsedMedia.year != null && result.year != null) {
            points -= 200f
        }

        if (isHashMatch) {
            points += 600f
        }

        val combinedText = "${result.releaseName ?: ""} ${result.fileName}".lowercase(Locale.ROOT)
        var tokenOverlapCount = 0
        parsedMedia.releaseTokens.forEach { token ->
            if (token.length > 1 && combinedText.contains(token.lowercase(Locale.ROOT))) {
                tokenOverlapCount++
            }
        }
        points += tokenOverlapCount * 15f

        val langIndex = preferences.preferredLanguages.indexOf(result.language)
        if (langIndex >= 0) {
            points += (50f - (langIndex * 10f)).coerceAtLeast(10f)
        }

        if (result.isHearingImpaired == preferences.preferHearingImpaired) {
            points += 20f
        }

        val downloadsScore = ln((result.downloadCount + 1).toFloat()) * 2f
        val ratingScore = result.rating * 2f
        points += downloadsScore + ratingScore

        if (result.isMachineTranslated || result.isAiTranslated) {
            points -= 30f
        }

        val isBestMatch = !isWrongEpisode && points >= 500f &&
                (isHashMatch || isExactEpisodeMatch || (isYearMatch && isTitleMatch))

        return ScoredSubtitle(
            subtitle = result,
            score = points,
            isBestMatch = isBestMatch
        )
    }
}
