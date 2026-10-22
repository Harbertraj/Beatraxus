package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.SubtitleSiblingMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSiblingMatcherTest {

    private fun names(video: String, files: List<String>, prefs: List<String> = emptyList()) =
        SubtitleSiblingMatcher.rank(video, files, prefs).map { it.fileName }

    @Test
    fun exactNameMatchesEveryReadableFormat() {
        val files = listOf("Movie.srt", "Movie.vtt", "Movie.ass", "Movie.ssa")
        assertEquals(setOf("Movie.srt", "Movie.vtt", "Movie.ass", "Movie.ssa"), names("Movie.mkv", files).toSet())
    }

    @Test
    fun unsupportedAndUnrelatedFilesAreIgnored() {
        val files = listOf("Movie.sub", "Movie.idx", "Movie.txt", "Movie.nfo", "Movie.mkv", "Other.srt", "readme.srt")
        assertTrue(names("Movie.mkv", files).isEmpty())
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(listOf("MOVIE.SRT"), names("movie.MP4", listOf("MOVIE.SRT")))
        assertEquals(listOf("movie.en.Srt"), names("Movie.mkv", listOf("movie.en.Srt")))
    }

    @Test
    fun languageTailsAreRecognised() {
        val c = SubtitleSiblingMatcher.rank(
            "Movie.2020.1080p.mkv",
            listOf("Movie.2020.1080p.en.srt", "Movie.2020.1080p.eng.srt", "Movie.2020.1080p_English.srt", "Movie.2020.1080p - hindi.srt")
        )
        assertEquals(4, c.size)
        assertEquals(setOf("en", "hi"), c.mapNotNull { it.languageCode }.toSet())
        assertEquals(3, c.count { it.languageCode == "en" })
    }

    @Test
    fun tailWithUnknownWordsDoesNotMatch() {
        // "Movie 2" and "Movie Trailer" are other videos or other things, not this video's subtitle.
        assertTrue(names("Movie.mkv", listOf("Movie 2.srt", "Movie Trailer.srt", "Movie.Directors.Cut.srt")).isEmpty())
    }

    @Test
    fun nameThatContinuesWithoutSeparatorDoesNotMatch() {
        assertTrue(names("Movie.mkv", listOf("Movie2.srt", "Movieland.srt", "MovieEn.srt")).isEmpty())
    }

    @Test
    fun shorterNameDoesNotMatch() {
        assertTrue(names("Movie.Part.One.mkv", listOf("Movie.srt", "Movie.Part.srt")).isEmpty())
    }

    @Test
    fun exactBeatsLanguageTagWhenNoPreference() {
        assertEquals(listOf("Movie.srt", "Movie.en.srt"), names("Movie.mkv", listOf("Movie.en.srt", "Movie.srt")))
    }

    @Test
    fun preferredLanguageWins() {
        val files = listOf("Movie.srt", "Movie.fr.srt", "Movie.en.srt", "Movie.hi.srt")
        assertEquals("Movie.hi.srt", names("Movie.mkv", files, listOf("hi", "en")).first())
        assertEquals("Movie.en.srt", names("Movie.mkv", files, listOf("en", "hi")).first())
    }

    @Test
    fun preferredLanguageAcceptsNamesAndRegions() {
        val files = listOf("Movie.fr.srt", "Movie.en.srt")
        assertEquals("Movie.en.srt", names("Movie.mkv", files, listOf("English")).first())
        assertEquals("Movie.en.srt", names("Movie.mkv", files, listOf("en-US")).first())
        assertEquals("Movie.fr.srt", names("Movie.mkv", files, listOf("fra")).first())
    }

    @Test
    fun taggedWrongLanguageRanksBelowUntaggedWhenAPreferenceExists() {
        val files = listOf("Movie.fr.srt", "Movie.srt")
        assertEquals(listOf("Movie.srt", "Movie.fr.srt"), names("Movie.mkv", files, listOf("en")))
    }

    @Test
    fun forcedTracksRankLowAndAreNotAutoPicked() {
        val files = listOf("Movie.en.forced.srt", "Movie.en.srt")
        assertEquals(listOf("Movie.en.srt", "Movie.en.forced.srt"), names("Movie.mkv", files, listOf("en")))
        assertEquals("Movie.en.srt", SubtitleSiblingMatcher.pickForAutoLoad("Movie.mkv", files, listOf("en"))?.fileName)
        // A forced-only folder yields nothing to auto-load, but the track is still listed for a manual pick.
        assertNull(SubtitleSiblingMatcher.pickForAutoLoad("Movie.mkv", listOf("Movie.en.forced.srt")))
        assertEquals(1, names("Movie.mkv", listOf("Movie.en.forced.srt")).size)
    }

    @Test
    fun hearingImpairedVersionRanksBelowPlain() {
        val files = listOf("Movie.en.sdh.srt", "Movie.en.srt")
        assertEquals("Movie.en.srt", names("Movie.mkv", files, listOf("en")).first())
        val sdh = SubtitleSiblingMatcher.rank("Movie.mkv", files).first { it.fileName.contains("sdh") }
        assertTrue(sdh.isHearingImpaired)
    }

    @Test
    fun hiIsHindiAloneButHearingImpairedNextToALanguage() {
        val alone = SubtitleSiblingMatcher.rank("Movie.mkv", listOf("Movie.hi.srt")).single()
        assertEquals("hi", alone.languageCode)
        assertFalse(alone.isHearingImpaired)

        val withLang = SubtitleSiblingMatcher.rank("Movie.mkv", listOf("Movie.en.hi.srt")).single()
        assertEquals("en", withLang.languageCode)
        assertTrue(withLang.isHearingImpaired)
    }

    @Test
    fun noiseWordsAndEncodingTagsAreAccepted() {
        assertEquals(1, names("Movie.mkv", listOf("Movie.en.subs.srt")).size)
        assertEquals(1, names("Movie.mkv", listOf("Movie.eng.UTF-8.srt")).size)
        assertEquals(1, names("Movie.mkv", listOf("Movie.utf8.srt")).size)
        assertEquals("en", SubtitleSiblingMatcher.rank("Movie.mkv", listOf("Movie.eng.UTF-8.srt")).single().languageCode)
    }

    @Test
    fun srtIsPreferredOverOtherFormatsOfTheSameTrack() {
        val files = listOf("Movie.ass", "Movie.vtt", "Movie.srt")
        assertEquals(listOf("Movie.srt", "Movie.vtt", "Movie.ass"), names("Movie.mkv", files))
    }

    @Test
    fun resultDoesNotDependOnListingOrder() {
        val files = listOf("Movie.en.srt", "Movie.fr.srt", "Movie.srt", "Movie.de.srt")
        assertEquals(names("Movie.mkv", files), names("Movie.mkv", files.reversed()))
        assertEquals(names("Movie.mkv", files), names("Movie.mkv", files.shuffled(java.util.Random(7))))
    }

    @Test
    fun videoNameWithDotsAndNoRealExtension() {
        assertEquals("Movie.2020", SubtitleSiblingMatcher.stemOf("Movie.2020"))
        assertEquals("Show.S01E01", SubtitleSiblingMatcher.stemOf("Show.S01E01.mkv"))
        assertEquals("Movie", SubtitleSiblingMatcher.stemOf("Movie.mp4"))
        assertEquals("Movie", SubtitleSiblingMatcher.stemOf("Movie"))
        assertEquals(".hidden", SubtitleSiblingMatcher.stemOf(".hidden"))
    }

    @Test
    fun episodeNamesDoNotCrossMatch() {
        val files = listOf("Show.S01E01.srt", "Show.S01E02.srt", "Show.S01E10.srt")
        assertEquals(listOf("Show.S01E01.srt"), names("Show.S01E01.mkv", files))
        assertEquals(listOf("Show.S01E02.srt"), names("Show.S01E02.mkv", files))
    }

    @Test
    fun nonAsciiNamesWork() {
        assertEquals(listOf("வீடியோ.ta.srt"), names("வீடியோ.mp4", listOf("வீடியோ.ta.srt")))
    }

    @Test
    fun extensionHelpers() {
        assertEquals("srt", SubtitleSiblingMatcher.extensionOf("a.b.SRT"))
        assertNull(SubtitleSiblingMatcher.extensionOf("noext"))
        assertNull(SubtitleSiblingMatcher.extensionOf("trailingdot."))
        assertTrue(SubtitleSiblingMatcher.isSubtitleFileName("x.VTT"))
        assertFalse(SubtitleSiblingMatcher.isSubtitleFileName("x.sub"))
    }

    @Test
    fun normalizeLanguageHandlesCodesNamesAndRegions() {
        assertEquals("en", SubtitleSiblingMatcher.normalizeLanguage("EN"))
        assertEquals("en", SubtitleSiblingMatcher.normalizeLanguage("eng"))
        assertEquals("pt", SubtitleSiblingMatcher.normalizeLanguage("pt-BR"))
        assertEquals("de", SubtitleSiblingMatcher.normalizeLanguage("German"))
        assertEquals("xx", SubtitleSiblingMatcher.normalizeLanguage("xx"))
        assertNull(SubtitleSiblingMatcher.normalizeLanguage("  "))
        assertNotNull(SubtitleSiblingMatcher.normalizeLanguage("ta"))
    }
}
