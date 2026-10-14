package com.beatraxus.app.addons

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Mock Internet Archive responses (advancedsearch.php and /metadata/<id> shapes). No network. */
class ArchiveOrgApiTest {

    private fun messageOf(block: () -> Unit): String? = try { block(); null } catch (e: IOException) { e.message }

    // ---- search ----------------------------------------------------------------------------------------

    @Test fun queryCleaningAndTypes() {
        assertEquals("fish chips", ArchiveOrgApi.cleanQuery(" fish:  (&chips)* "))
        assertTrue(ArchiveOrgApi.searchQuery("jazz", ArchiveOrgApi.TYPE_VIDEO).contains("mediatype:(movies)"))
        assertTrue(ArchiveOrgApi.searchQuery("jazz", ArchiveOrgApi.TYPE_AUDIO).contains("mediatype:(audio OR etree)"))
        assertTrue(ArchiveOrgApi.searchQuery("jazz", ArchiveOrgApi.TYPE_ALL).contains("movies OR audio OR etree"))
        assertTrue(ArchiveOrgApi.searchQuery("jazz", "all").startsWith("(jazz) AND "))
        // Blank query browses curated collections instead of everything.
        assertTrue(ArchiveOrgApi.searchQuery("  ", "all").contains("collection:(feature_films"))
        assertTrue(ArchiveOrgApi.searchQuery("jazz", "all").contains("-access-restricted-item:true"))
    }

    @Test fun searchUrl_pagingAndSort() {
        val first = ArchiveOrgApi.searchUrl("jazz", "audio", ArchiveOrgApi.SORT_NEWEST, 0)
        assertTrue(first.startsWith("https://archive.org/advancedsearch.php?"))
        assertTrue(first.contains("page=1") && first.contains("rows=50") && first.contains("output=json"))
        assertTrue(first.contains("sort%5B%5D=publicdate+desc"))
        assertTrue(ArchiveOrgApi.searchUrl("jazz", "audio", "week", 2).contains("page=3"))
        assertTrue(ArchiveOrgApi.searchUrl("jazz", "audio", "week", 2).contains("week+desc"))
        assertTrue(ArchiveOrgApi.searchUrl("jazz", "audio", "evil", 0).contains("downloads+desc")) // unknown sort ignored
    }

    @Test fun parseSearch_mapsFields() {
        val raw = """{"response":{"numFound":120,"start":0,"docs":[
            {"identifier":"album_1","title":"Live 1975","mediatype":"etree","creator":["The Band","Other"],"year":"1975","runtime":"1:05:10",
             "licenseurl":"http://creativecommons.org/licenses/by/4.0/"},
            {"identifier":"film_1","title":"Old Film","mediatype":"movies","creator":"Studio","year":1931},
            {"title":"no identifier"}
        ]}}"""
        val page = ArchiveOrgApi.parseSearch(raw)
        assertEquals(120L, page.numFound)
        assertEquals(2, page.items.size)
        val audio = page.items[0]
        assertEquals("audio", audio.mediaType)
        assertEquals("The Band · 1975", audio.subtitle)
        assertEquals("The Band", audio.artist)
        assertEquals(3_910_000L, audio.durationMs)
        assertEquals("https://archive.org/services/img/album_1", audio.artworkUrl)
        assertEquals("https://archive.org/details/album_1", audio.pageUrl)
        assertEquals("https://creativecommons.org/licenses/by/4.0/", audio.licenseUrl)
        val video = page.items[1]
        assertEquals("video", video.mediaType)
        assertEquals("Studio · 1931", video.subtitle)
        assertEquals(0L, video.durationMs)
    }

    @Test fun parseSearch_emptyAndMalformed() {
        assertTrue(ArchiveOrgApi.parseSearch("""{"response":{"numFound":0,"docs":[]}}""").items.isEmpty())
        assertTrue(ArchiveOrgApi.parseSearch("""{"nothing":true}""").items.isEmpty())
        assertEquals(
            "Internet Archive sent a response Beatraxus couldn't read",
            messageOf { ArchiveOrgApi.parseSearch("<html>down for maintenance</html>") }
        )
    }

    @Test fun paging() {
        assertTrue(ArchiveOrgApi.hasMore(0, 120))
        assertTrue(ArchiveOrgApi.hasMore(1, 120))
        assertFalse(ArchiveOrgApi.hasMore(2, 120)) // pages 0..2 cover 150 rows
        assertFalse(ArchiveOrgApi.hasMore(0, 50))
    }

    // ---- small helpers ---------------------------------------------------------------------------------

    @Test fun durations() {
        assertEquals(215_300L, ArchiveOrgApi.parseDurationMs("215.3"))
        assertEquals(215_000L, ArchiveOrgApi.parseDurationMs("3:35"))
        assertEquals(5_137_000L, ArchiveOrgApi.parseDurationMs("01:25:37"))
        assertEquals(0L, ArchiveOrgApi.parseDurationMs(""))
        assertEquals(0L, ArchiveOrgApi.parseDurationMs("soon"))
        assertEquals(0L, ArchiveOrgApi.parseDurationMs("1:2:3:4"))
        assertEquals(0L, ArchiveOrgApi.parseDurationMs("-5"))
    }

    @Test fun pathEncoding() {
        assertEquals("a%20b%C3%A9", ArchiveOrgApi.pathEncode("a bé"))
        assertEquals("Disc%201/track%2001.mp3", ArchiveOrgApi.pathEncode("Disc 1/track 01.mp3", keepSlash = true))
        assertEquals("a%2Fb", ArchiveOrgApi.pathEncode("a/b"))
        assertEquals("https://archive.org/download/id_1/CD%201/a%23b.mp3", ArchiveOrgApi.downloadUrl("id_1", "CD 1/a#b.mp3"))
    }

    @Test fun naturalOrder() {
        val sorted = listOf("track10.mp3", "track2.mp3", "Track1.mp3", "track02b.mp3").sortedWith(ArchiveOrgApi::naturalCompare)
        assertEquals(listOf("Track1.mp3", "track2.mp3", "track02b.mp3", "track10.mp3"), sorted)
    }

    @Test fun trackKeyMergesFormatsOfTheSameTrack() {
        assertEquals("disc1/track01", ArchiveOrgApi.trackKey("Disc1/track01_64kb.mp3"))
        assertEquals("disc1/track01", ArchiveOrgApi.trackKey("Disc1/track01_vbr.mp3"))
        assertEquals("disc1/track01", ArchiveOrgApi.trackKey("Disc1/track01.flac"))
        assertEquals("disc1/track01", ArchiveOrgApi.trackKey("Disc1/track01-128kbps.mp3"))
        assertEquals("my.song", ArchiveOrgApi.trackKey("my.song.mp3"))
    }

    // ---- playable check --------------------------------------------------------------------------------

    @Test fun requirePlayable() {
        assertTrue(messageOf { ArchiveOrgApi.requirePlayable(JSONObject("{}")) }!!.contains("removed"))
        assertTrue(messageOf { ArchiveOrgApi.requirePlayable(JSONObject("""{"is_dark":true,"files":[{"name":"a.mp3"}]}""")) }!!.contains("restricted"))
        assertTrue(
            messageOf {
                ArchiveOrgApi.requirePlayable(JSONObject("""{"metadata":{"access-restricted-item":"true"},"files":[{"name":"a.mp3"}]}"""))
            }!!.contains("borrow-only")
        )
        assertTrue(messageOf { ArchiveOrgApi.requirePlayable(JSONObject("""{"files":[],"metadata":{}}""")) }!!.contains("no downloadable files"))
        assertNull(messageOf { ArchiveOrgApi.requirePlayable(JSONObject("""{"files":[{"name":"a.mp3"}],"metadata":{}}""")) })
    }

    // ---- choosing the file to stream -------------------------------------------------------------------

    private fun meta(vararg files: String) = JSONObject("""{"files":[${files.joinToString(",")}],"metadata":{}}""")

    private fun file(name: String, format: String = "", size: Long = 0, source: String = "original", extra: String = "") =
        """{"name":"$name","format":"$format","size":"$size","source":"$source"$extra}"""

    @Test fun video_prefersBestDerivativeAndSkipsSamplesAndPrivate() {
        val m = meta(
            file("film.mkv", "Matroska", 5_000_000_000),
            file("film.mp4", "h.264", 3_000_000_000),
            file("film_512kb.mp4", "512Kb MPEG4", 90_000_000, "derivative"),
            file("film_hd.mp4", "h.264 HD", 1_200_000_000, "derivative"),
            file("film_sample.mp4", "h.264 HD", 9_000_000_000, "derivative"),
            file("film_secret.mp4", "h.264 HD", 9_000_000_000, "derivative", ""","private":"true""""),
            file("__ia_thumb.jpg", "Item Tile")
        )
        assertEquals("film_hd.mp4", ArchiveOrgApi.pickStreamFile(m, wantVideo = true, quality = "standard"))
    }

    @Test fun video_fallsBackToAudioAndAudioPrefersAudio() {
        val onlyAudio = meta(file("talk.mp3", "VBR MP3", 1_000_000, "derivative"))
        assertEquals("talk.mp3", ArchiveOrgApi.pickStreamFile(onlyAudio, wantVideo = true, quality = "standard"))
        val both = meta(file("a.mp4", "MPEG4", 1_000_000), file("a.mp3", "VBR MP3", 500_000))
        assertEquals("a.mp3", ArchiveOrgApi.pickStreamFile(both, wantVideo = false, quality = "standard"))
        assertEquals("a.mp4", ArchiveOrgApi.pickStreamFile(both, wantVideo = true, quality = "standard"))
        assertNull(ArchiveOrgApi.pickStreamFile(meta(file("notes.txt")), wantVideo = true, quality = "standard"))
    }

    @Test fun audio_qualityChoice() {
        val m = meta(
            file("t_64kb.mp3", "64Kbps MP3", 1_000_000, "derivative"),
            file("t_vbr.mp3", "VBR MP3", 4_000_000, "derivative"),
            file("t.flac", "Flac", 30_000_000)
        )
        assertEquals("t_vbr.mp3", ArchiveOrgApi.pickStreamFile(m, wantVideo = false, quality = "standard"))
        assertEquals("t.flac", ArchiveOrgApi.pickStreamFile(m, wantVideo = false, quality = "lossless"))
        // Lossless asked for but only MP3 exists: still plays.
        val mp3Only = meta(file("t_vbr.mp3", "VBR MP3", 4_000_000, "derivative"))
        assertEquals("t_vbr.mp3", ArchiveOrgApi.pickStreamFile(mp3Only, wantVideo = false, quality = "lossless"))
    }

    @Test fun tiers() {
        assertTrue(ArchiveOrgApi.videoTier("h.264 HD") > ArchiveOrgApi.videoTier("h.264"))
        assertTrue(ArchiveOrgApi.videoTier("h.264") > ArchiveOrgApi.videoTier("MPEG4"))
        assertTrue(ArchiveOrgApi.videoTier("MPEG4") > ArchiveOrgApi.videoTier("512Kb MPEG4"))
        assertTrue(ArchiveOrgApi.audioTier("VBR MP3") > ArchiveOrgApi.audioTier("128Kbps MP3"))
        assertTrue(ArchiveOrgApi.audioTier("128Kbps MP3") > ArchiveOrgApi.audioTier("64Kbps MP3"))
    }

    // ---- album track lists -----------------------------------------------------------------------------

    private fun albumMeta(): JSONObject = JSONObject(
        """{"metadata":{"creator":"The Band","licenseurl":"http://creativecommons.org/licenses/by/4.0/"},"files":[
            {"name":"d1/t02_vbr.mp3","format":"VBR MP3","source":"derivative","size":"4000000","track":"2","title":"Second Song","length":"3:35"},
            {"name":"d1/t02_64kb.mp3","format":"64Kbps MP3","source":"derivative","size":"1000000","track":"2","title":"Second Song","length":"215.3"},
            {"name":"d1/t02.flac","format":"Flac","source":"original","size":"30000000","track":"2","title":"Second Song","length":"215.3"},
            {"name":"d1/t01_vbr.mp3","format":"VBR MP3","source":"derivative","size":"5000000","track":"1","title":"First Song","length":"250.0"},
            {"name":"d1/t01.flac","format":"Flac","source":"original","size":"40000000","track":"1","title":"First Song","length":"250.0"},
            {"name":"d1/t10_vbr.mp3","format":"VBR MP3","source":"derivative","size":"3000000","track":"10/12","length":"61"},
            {"name":"cover.jpg","format":"JPEG"},
            {"name":"d1/t02_sample.mp3","format":"VBR MP3","track":"2"}
        ]}"""
    )

    @Test fun album_mergesFormatsOrdersAndFillsMetadata() {
        val tracks = ArchiveOrgApi.audioTracks(albumMeta(), "band_live", "Live 1975", "standard")
        assertEquals(listOf("First Song", "Second Song", "t10"), tracks.map { it.title })
        assertEquals(listOf(1, 2, 10), tracks.map { it.trackNumber })
        assertEquals(listOf(250_000L, 215_000L, 61_000L), tracks.map { it.durationMs })
        val first = tracks[0]
        assertEquals("band_live/d1/t01_vbr.mp3", first.id)
        assertEquals("https://archive.org/download/band_live/d1/t01_vbr.mp3", first.directPlayUrl)
        assertEquals("The Band", first.artist)
        assertEquals("Live 1975", first.album)
        assertEquals("The Band · Live 1975", first.subtitle)
        assertEquals("https://archive.org/services/img/band_live", first.artworkUrl)
        assertEquals("https://archive.org/details/band_live", first.pageUrl)
        assertEquals("https://creativecommons.org/licenses/by/4.0/", first.licenseUrl)
        assertEquals("audio", first.mediaType)
    }

    @Test fun album_losslessPicksFlacWhereAvailable() {
        val tracks = ArchiveOrgApi.audioTracks(albumMeta(), "band_live", "Live 1975", "lossless")
        assertEquals("band_live/d1/t01.flac", tracks[0].id)
        assertEquals("band_live/d1/t02.flac", tracks[1].id)
        assertEquals("band_live/d1/t10_vbr.mp3", tracks[2].id) // no FLAC for this one: falls back to MP3
    }

    @Test fun album_withoutTrackNumbersUsesNaturalFileOrder() {
        val m = JSONObject(
            """{"metadata":{},"files":[{"name":"track10.mp3","format":"VBR MP3"},{"name":"track2.mp3","format":"VBR MP3"},{"name":"track1.mp3","format":"VBR MP3"}]}"""
        )
        val tracks = ArchiveOrgApi.audioTracks(m, "x", "Album", "standard")
        assertEquals(listOf("track1", "track2", "track10"), tracks.map { it.title })
        assertEquals(listOf(1, 2, 3), tracks.map { it.trackNumber })
    }

    @Test fun album_singleFileTakesTheItemTitle_andNoAudioMeansEmpty() {
        val one = JSONObject("""{"metadata":{},"files":[{"name":"recording_vbr.mp3","format":"VBR MP3"}]}""")
        assertEquals("My Talk", ArchiveOrgApi.audioTracks(one, "talk", "My Talk", "standard").single().title)
        val none = JSONObject("""{"metadata":{},"files":[{"name":"movie.mp4","format":"MPEG4"},{"name":"cover.jpg"}]}""")
        assertTrue(ArchiveOrgApi.audioTracks(none, "m", "Movie", "standard").isEmpty())
        assertTrue(ArchiveOrgApi.audioTracks(JSONObject("{}"), "m", "Movie", "standard").isEmpty())
    }

    @Test fun album_idsNeverContainCommas() {
        val m = JSONObject(
            """{"metadata":{},"files":[{"name":"01 - Hello, World.mp3","format":"VBR MP3"},{"name":"02 - Bye.mp3","format":"VBR MP3"}]}"""
        )
        val tracks = ArchiveOrgApi.audioTracks(m, "x", "Album", "standard")
        assertTrue(tracks.none { ',' in it.id })
        // The stream URL still points at the real file name.
        assertEquals("https://archive.org/download/x/01%20-%20Hello%2C%20World.mp3", tracks[0].directPlayUrl)
    }
}
