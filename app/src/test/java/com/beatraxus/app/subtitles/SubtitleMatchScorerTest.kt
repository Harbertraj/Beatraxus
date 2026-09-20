package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.FilenameParser
import com.beatraxus.app.subtitles.domain.SubtitleMatchScorer
import com.beatraxus.app.subtitles.domain.SubtitlePreferences
import com.beatraxus.app.subtitles.domain.SubtitleResult
import com.beatraxus.app.subtitles.domain.SubtitleSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMatchScorerTest {

    @Test
    fun testHashMatchScoresHighestAndIsBestMatch() {
        val parsed = FilenameParser.parse("The.Matrix.1999.1080p.mkv")
        val query = SubtitleSearchQuery(query = "The Matrix", movieHash = "8e245d9679d31e12")

        val matchHash = SubtitleResult(
            id = "1",
            fileId = 1001,
            fileName = "The.Matrix.1999.srt",
            releaseName = "The.Matrix.1999.1080p",
            language = "en",
            languageName = "English",
            downloadCount = 1000,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 8.5f,
            uploaderName = "Uploader",
            fps = 23.976f,
            featureTitle = "The Matrix",
            year = 1999,
            imdbId = 133093,
            tmdbId = 603
        )

        val nonHash = SubtitleResult(
            id = "2",
            fileId = 1002,
            fileName = "The.Matrix.srt",
            releaseName = "The.Matrix.DVDRip",
            language = "en",
            languageName = "English",
            downloadCount = 10,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 5.0f,
            uploaderName = "Uploader",
            fps = 23.976f,
            featureTitle = "The Matrix",
            year = 1999,
            imdbId = 133093,
            tmdbId = 603
        )

        val scored = SubtitleMatchScorer.scoreAndSort(listOf(nonHash, matchHash), query, parsed)

        assertEquals("1", scored.first().subtitle.id)
        assertTrue(scored.first().score > 1000f)
        assertTrue(scored.first().isBestMatch)
    }

    @Test
    fun testExactEpisodeMatchScoresHigh() {
        val parsed = FilenameParser.parse("Breaking.Bad.S05E14.Ozymandias.1080p.mkv")
        val query = SubtitleSearchQuery(query = "Breaking Bad", type = "episode")

        val epSubtitle = SubtitleResult(
            id = "101",
            fileId = 2001,
            fileName = "Breaking.Bad.S05E14.1080p.WEB-DL.srt",
            releaseName = "Breaking.Bad.S05E14.Ozymandias",
            language = "en",
            languageName = "English",
            downloadCount = 500,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 9.0f,
            uploaderName = "BBFan",
            fps = 23.976f,
            featureTitle = "Breaking Bad",
            year = 2013,
            imdbId = 2301451,
            tmdbId = 1396
        )

        val wrongEpSubtitle = SubtitleResult(
            id = "102",
            fileId = 2002,
            fileName = "Breaking.Bad.S05E01.srt",
            releaseName = "Breaking.Bad.S05E01",
            language = "en",
            languageName = "English",
            downloadCount = 100,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 8.0f,
            uploaderName = "BBFan",
            fps = 23.976f,
            featureTitle = "Breaking Bad",
            year = 2013,
            imdbId = 2301451,
            tmdbId = 1396
        )

        val scored = SubtitleMatchScorer.scoreAndSort(listOf(wrongEpSubtitle, epSubtitle), query, parsed)

        assertEquals("101", scored.first().subtitle.id)
        assertTrue(scored.first().isBestMatch)
        assertFalse(scored.last().isBestMatch)
    }

    @Test
    fun testMachineTranslatedGetsPenalty() {
        val parsed = FilenameParser.parse("Inception.2010.mkv")
        val query = SubtitleSearchQuery(query = "Inception", type = "movie")

        val humanSub = SubtitleResult(
            id = "1",
            fileId = 3001,
            fileName = "Inception.srt",
            releaseName = "Inception.2010",
            language = "en",
            languageName = "English",
            downloadCount = 50,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 7.0f,
            uploaderName = "Human",
            fps = 24.0f,
            featureTitle = "Inception",
            year = 2010,
            imdbId = 1375666,
            tmdbId = 27205
        )

        val aiSub = SubtitleResult(
            id = "2",
            fileId = 3002,
            fileName = "Inception.srt",
            releaseName = "Inception.2010",
            language = "en",
            languageName = "English",
            downloadCount = 50,
            isHearingImpaired = false,
            isMachineTranslated = true,
            isAiTranslated = true,
            rating = 7.0f,
            uploaderName = "Bot",
            fps = 24.0f,
            featureTitle = "Inception",
            year = 2010,
            imdbId = 1375666,
            tmdbId = 27205
        )

        val scored = SubtitleMatchScorer.scoreAndSort(listOf(aiSub, humanSub), query, parsed)

        assertEquals("1", scored.first().subtitle.id)
        assertTrue(scored.first().score > scored.last().score)
    }

    @Test
    fun testLanguagePreferenceApplied() {
        val parsed = FilenameParser.parse("Interstellar.2014.mkv")
        val query = SubtitleSearchQuery(query = "Interstellar")
        val prefs = SubtitlePreferences(preferredLanguages = listOf("es", "en"))

        val esSub = SubtitleResult(
            id = "es",
            fileId = 4001,
            fileName = "Interstellar.es.srt",
            releaseName = "Interstellar.2014",
            language = "es",
            languageName = "Spanish",
            downloadCount = 100,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 8.0f,
            uploaderName = "SpanishSub",
            fps = 24.0f,
            featureTitle = "Interstellar",
            year = 2014,
            imdbId = 816692,
            tmdbId = 157336
        )

        val frSub = SubtitleResult(
            id = "fr",
            fileId = 4002,
            fileName = "Interstellar.fr.srt",
            releaseName = "Interstellar.2014",
            language = "fr",
            languageName = "French",
            downloadCount = 100,
            isHearingImpaired = false,
            isMachineTranslated = false,
            isAiTranslated = false,
            rating = 8.0f,
            uploaderName = "FrenchSub",
            fps = 24.0f,
            featureTitle = "Interstellar",
            year = 2014,
            imdbId = 816692,
            tmdbId = 157336
        )

        val scored = SubtitleMatchScorer.scoreAndSort(listOf(frSub, esSub), query, parsed, prefs)

        assertEquals("es", scored.first().subtitle.id)
    }
}
