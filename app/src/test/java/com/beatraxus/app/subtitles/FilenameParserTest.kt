package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserTest {

    @Test
    fun testMovie2012() {
        val parsed = FilenameParser.parse("2012 (2009) [1080p] [BluRay] [x264] [YIFY].mp4")
        assertEquals("2012", parsed.cleanTitle)
        assertEquals(2009, parsed.year)
        assertFalse(parsed.isEpisode)
        assertTrue(parsed.releaseTokens.contains("1080p"))
        assertTrue(parsed.releaseTokens.contains("bluray"))
    }

    @Test
    fun testMovie1917() {
        val parsed = FilenameParser.parse("1917.2019.1080p.BluRay.x264.mkv")
        assertEquals("1917", parsed.cleanTitle)
        assertEquals(2019, parsed.year)
        assertFalse(parsed.isEpisode)
        assertTrue(parsed.releaseTokens.contains("1080p"))
    }

    @Test
    fun testMovieSe7en() {
        val parsed = FilenameParser.parse("Se7en.1995.1080p.BluRay.x264.mkv")
        assertEquals("Se7en", parsed.cleanTitle)
        assertEquals(1995, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testMovieBladeRunner2049() {
        val parsed = FilenameParser.parse("Blade.Runner.2049.2017.2160p.UHD.HDR.x265.mkv")
        assertEquals("Blade Runner 2049", parsed.cleanTitle)
        assertEquals(2017, parsed.year)
        assertFalse(parsed.isEpisode)
        assertTrue(parsed.releaseTokens.contains("2160p"))
        assertTrue(parsed.releaseTokens.contains("uhd"))
    }

    @Test
    fun testMovieDistrict9() {
        val parsed = FilenameParser.parse("District.9.2009.720p.HDTV.x264.mkv")
        assertEquals("District 9", parsed.cleanTitle)
        assertEquals(2009, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testMovie12YearsASlave() {
        val parsed = FilenameParser.parse("12.Years.a.Slave.2013.1080p.BluRay.x264.mkv")
        assertEquals("12 Years a Slave", parsed.cleanTitle)
        assertEquals(2013, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testSeriesS02E05() {
        val parsed = FilenameParser.parse("Breaking.Bad.S02E05.Breakage.1080p.WEB-DL.x264.mkv")
        assertEquals("Breaking Bad", parsed.cleanTitle)
        assertTrue(parsed.isEpisode)
        assertNotNull(parsed.episodeInfo)
        assertEquals(2, parsed.episodeInfo!!.season)
        assertEquals(5, parsed.episodeInfo!!.episode)
    }

    @Test
    fun testSeries1x03() {
        val parsed = FilenameParser.parse("Game.of.Thrones.1x03.Lord.Snow.720p.HDTV.x264.mkv")
        assertEquals("Game of Thrones", parsed.cleanTitle)
        assertTrue(parsed.isEpisode)
        assertNotNull(parsed.episodeInfo)
        assertEquals(1, parsed.episodeInfo!!.season)
        assertEquals(3, parsed.episodeInfo!!.episode)
    }

    @Test
    fun testSeriesSeason1Episode1() {
        val parsed = FilenameParser.parse("The.Office.Season.1.Episode.1.Pilot.DVDRip.x264.mkv")
        assertEquals("The Office", parsed.cleanTitle)
        assertTrue(parsed.isEpisode)
        assertEquals(1, parsed.episodeInfo!!.season)
        assertEquals(1, parsed.episodeInfo!!.episode)
    }

    @Test
    fun testAnimeStyleDashEpisode() {
        val parsed = FilenameParser.parse("[AnimeSub] Attack on Titan - 05 [1080p].mkv")
        assertEquals("Attack on Titan", parsed.cleanTitle)
        assertTrue(parsed.isEpisode)
        assertEquals(1, parsed.episodeInfo!!.season)
        assertEquals(5, parsed.episodeInfo!!.episode)
    }

    @Test
    fun testDottedMovieTitle() {
        val parsed = FilenameParser.parse("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv")
        assertEquals("The Matrix", parsed.cleanTitle)
        assertEquals(1999, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testUnderscoredTitle() {
        val parsed = FilenameParser.parse("Interstellar_2014_1080p_WEB-DL_x264.mp4")
        assertEquals("Interstellar", parsed.cleanTitle)
        assertEquals(2014, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testBracketedTitle() {
        val parsed = FilenameParser.parse("[ReleaseGroup] Inception (2010) [720p] [AAC].mkv")
        assertEquals("Inception", parsed.cleanTitle)
        assertEquals(2010, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testSimpleMovieNoYear() {
        val parsed = FilenameParser.parse("Avatar.mkv")
        assertEquals("Avatar", parsed.cleanTitle)
        assertNull(parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun testMovieWithExtraSpacesAndDashes() {
        val parsed = FilenameParser.parse(" The - Dark - Knight - 2008 - 1080p - BluRay .mkv")
        assertEquals("The Dark Knight", parsed.cleanTitle)
        assertEquals(2008, parsed.year)
        assertFalse(parsed.isEpisode)
    }
}
