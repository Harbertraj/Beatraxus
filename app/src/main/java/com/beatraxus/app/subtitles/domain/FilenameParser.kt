package com.beatraxus.app.subtitles.domain

import java.util.Locale

object FilenameParser {

    private val EXTENSION_REGEX = Regex("\\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|srt|sub|ass)$", RegexOption.IGNORE_CASE)

    // Episode regexes
    private val S_E_REGEX = Regex("(?<series>.+?)[\\._\\-\\s]+[sS](\\d{1,2})[\\._\\-\\s]*[eE](\\d{1,2})(?:[\\._\\-\\s]+(?<title>.+))?", RegexOption.IGNORE_CASE)
    private val SEASON_EP_REGEX = Regex("(?<series>.+?)[\\._\\-\\s]+season[\\._\\-\\s]*(\\d{1,2})[\\._\\-\\s]+episode[\\._\\-\\s]*(\\d{1,2})(?:[\\._\\-\\s]+(?<title>.+))?", RegexOption.IGNORE_CASE)
    private val X_EP_REGEX = Regex("(?<series>.+?)[\\._\\-\\s]+(\\d{1,2})x(\\d{1,2})(?:[\\._\\-\\s]+(?<title>.+))?", RegexOption.IGNORE_CASE)
    private val ANIME_EP_REGEX = Regex("(?<series>.+?)[\\._\\-\\s]+\\-[\\._\\-\\s]+(\\d{1,3})(?:[\\._\\-\\s]+(?<title>.+))?", RegexOption.IGNORE_CASE)

    // Year regex
    private val YEAR_REGEX = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d|p|i)")

    // Known release tag keywords (lowercase)
    private val KNOWN_TAGS = setOf(
        "480p", "576p", "720p", "1080p", "1080i", "2160p", "4k", "8k", "uhd", "fhd", "hd",
        "web-dl", "webdl", "webrip", "web", "bluray", "bdrip", "brrip", "hdtv", "dvdrip", "dvd", "cam", "ts", "tc", "r5", "remux",
        "x264", "x265", "h264", "h265", "hevc", "av1", "xvid", "divx", "10bit", "10-bit",
        "aac", "ddp5.1", "dd5.1", "ddp", "dts", "dts-hd", "atmos", "truehd", "ac3", "5.1", "7.1", "flac", "mp3",
        "hdr", "hdr10", "hdr10plus", "dv", "dolbyvision", "proper", "repack", "extended", "unrated"
    )

    private val RELEASE_GROUP_REGEX = Regex("-[a-zA-Z0-9]+$")

    fun parse(displayName: String): ParsedMedia {
        val nameWithoutExt = displayName.replace(EXTENSION_REGEX, "").trim()
        val tokens = extractReleaseTokens(nameWithoutExt)

        // Try episode patterns
        val epMatch = S_E_REGEX.find(nameWithoutExt)
            ?: SEASON_EP_REGEX.find(nameWithoutExt)
            ?: X_EP_REGEX.find(nameWithoutExt)

        if (epMatch != null) {
            val seriesGroup = epMatch.groups["series"]?.value ?: ""
            val season = epMatch.groupValues[2].toIntOrNull() ?: 1
            val episode = epMatch.groupValues[3].toIntOrNull() ?: 1
            val episodeTitleRaw = epMatch.groups["title"]?.value

            val cleanSeries = cleanTitleString(seriesGroup)
            val year = extractYear(nameWithoutExt)
            val cleanEpTitle = episodeTitleRaw?.let { cleanTitleString(it) }?.takeIf { it.isNotBlank() }

            val epInfo = MediaEpisodeInfo(
                seriesName = cleanSeries,
                season = season,
                episode = episode,
                episodeTitle = cleanEpTitle,
                year = year
            )

            return ParsedMedia(
                rawTitle = displayName,
                cleanTitle = cleanSeries,
                year = year,
                episodeInfo = epInfo,
                releaseTokens = tokens
            )
        }

        // Check anime dash episode pattern if series has brackets or hyphen
        val animeMatch = ANIME_EP_REGEX.find(nameWithoutExt)
        if (animeMatch != null && (nameWithoutExt.startsWith("[") || nameWithoutExt.contains(" - "))) {
            val seriesGroup = animeMatch.groups["series"]?.value ?: ""
            val epNum = animeMatch.groupValues[2].toIntOrNull() ?: 1
            val cleanSeries = cleanTitleString(seriesGroup)
            val year = extractYear(nameWithoutExt)

            val epInfo = MediaEpisodeInfo(
                seriesName = cleanSeries,
                season = 1,
                episode = epNum,
                year = year
            )

            return ParsedMedia(
                rawTitle = displayName,
                cleanTitle = cleanSeries,
                year = year,
                episodeInfo = epInfo,
                releaseTokens = tokens
            )
        }

        // Movie parsing
        val year = extractYear(nameWithoutExt)
        val cleanMovieTitle = cleanMovieTitleString(nameWithoutExt, year)

        return ParsedMedia(
            rawTitle = displayName,
            cleanTitle = cleanMovieTitle,
            year = year,
            episodeInfo = null,
            releaseTokens = tokens
        )
    }

    private fun extractYear(text: String): Int? {
        val matches = YEAR_REGEX.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        if (matches.isEmpty()) return null

        val parenYear = Regex("\\((19\\d{2}|20\\d{2})\\)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (parenYear != null) return parenYear

        if (matches.size > 1) {
            return matches.last()
        }

        return matches.firstOrNull()
    }

    private fun extractReleaseTokens(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("[._\\[\\]()\\-]+"), " ")

        normalized.split(" ").forEach { token ->
            if (token in KNOWN_TAGS) {
                tokens.add(token)
            }
        }

        val groupMatch = RELEASE_GROUP_REGEX.find(text)
        if (groupMatch != null) {
            tokens.add(groupMatch.value.removePrefix("-").lowercase(Locale.ROOT))
        }

        return tokens
    }

    private fun cleanTitleString(text: String): String {
        var clean = text.replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[._\\-]+"), " ")
            .trim()

        KNOWN_TAGS.forEach { tag ->
            clean = clean.replace(Regex("(?i)\\b${Regex.escape(tag)}\\b"), "")
        }

        return clean.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanMovieTitleString(text: String, releaseYear: Int?): String {
        var clean = text.replace(Regex("\\[.*?\\]"), " ")

        if (releaseYear != null) {
            clean = clean.replace("($releaseYear)", " ")
                .replace("[$releaseYear]", " ")
        }

        clean = clean.replace(Regex("[._\\-]+"), " ")

        KNOWN_TAGS.forEach { tag ->
            clean = clean.replace(Regex("(?i)\\b${Regex.escape(tag)}\\b"), "")
        }

        if (releaseYear != null && clean.endsWith(releaseYear.toString())) {
            clean = clean.substringBeforeLast(releaseYear.toString())
        }

        clean = clean.replace(Regex("\\s+"), " ").trim()

        return clean.ifBlank { text.substringBefore(".") }
    }
}
