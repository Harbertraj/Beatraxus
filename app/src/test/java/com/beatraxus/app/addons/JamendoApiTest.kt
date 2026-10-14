package com.beatraxus.app.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock responses only: shaped after the field list in Jamendo's API v3.0 documentation
 * (developer.jamendo.com/v3.0/tracks). No network and no real client ID are used.
 */
class JamendoApiTest {

    private val trackJson = """
        {"id":"1848357","name":"mañana será tarde","duration":272,"artist_id":"421168","artist_name":"fankel",
         "album_name":"mañana será tarde","album_id":"368084","album_image":"https://usercontent.jamendo.com?type=album&id=368084&width=300",
         "image":"https://usercontent.jamendo.com?type=album&id=368084&width=300",
         "license_ccurl":"http://creativecommons.org/licenses/by-nc-nd/3.0/",
         "audio":"https://prod-1.storage.jamendo.com/?trackid=1848357&format=mp32&from=app-devsite",
         "audiodownload":"","audiodownload_allowed":false,
         "shareurl":"https://www.jamendo.com/track/1848357"}
    """.trimIndent()

    private fun response(vararg tracks: String, code: Int = 0, message: String = "") =
        """{"headers":{"status":"${if (code == 0) "success" else "failed"}","code":$code,"error_message":"$message",
            "warnings":"","results_count":${tracks.size}},"results":[${tracks.joinToString(",")}]}"""

    private fun messageOf(block: () -> Unit): String? = try { block(); null } catch (e: Exception) { e.message }

    // ---- request building ------------------------------------------------------------------------------

    @Test fun browseUrl_usesDocumentedParametersOnly() {
        val url = JamendoApi.tracksUrl("abc123", null, JamendoApi.MODE_ANY, JamendoApi.SORT_AUTO, 0)
        assertTrue(url.startsWith("https://api.jamendo.com/v3.0/tracks/?"))
        listOf(
            "client_id=abc123", "format=json", "limit=30", "offset=0", "imagesize=300",
            "audioformat=mp32", "order=popularity_month"
        ).forEach { assertTrue("missing $it in $url", url.contains(it)) }
        assertFalse(url.contains("search=") || url.contains("namesearch="))
    }

    @Test fun searchModes() {
        val any = JamendoApi.tracksUrl("k", "daft punk", JamendoApi.MODE_ANY, JamendoApi.SORT_AUTO, 0)
        assertTrue(any.contains("search=daft+punk"))
        assertFalse(any.contains("order=")) // keeps the API's default relevance order while searching

        val title = JamendoApi.tracksUrl("k", "mañana", JamendoApi.MODE_TITLE, JamendoApi.SORT_AUTO, 0)
        assertTrue(title.contains("namesearch=ma%C3%B1ana"))
        assertFalse(title.contains("&search="))
    }

    @Test fun artistSearch_usesArtistIdsAndPopularityOrder() {
        val url = JamendoApi.tracksUrl("k", "fankel", JamendoApi.MODE_ARTIST, JamendoApi.SORT_AUTO, 30, artistIds = listOf("421168", "7"))
        assertTrue(url.contains("artist_id=421168+7"))
        assertTrue(url.contains("order=popularity_total"))
        assertTrue(url.contains("offset=30"))
        assertFalse(url.contains("search="))
    }

    @Test fun sortAndFeatured() {
        assertTrue(JamendoApi.tracksUrl("k", null, "any", "popularity_week", 0).contains("order=popularity_week"))
        val featured = JamendoApi.tracksUrl("k", null, "any", JamendoApi.SORT_FEATURED, 0)
        assertTrue(featured.contains("featured=1"))
        // An unknown sort key must not reach the API.
        assertFalse(JamendoApi.tracksUrl("k", null, "any", "'; drop", 0).contains("drop"))
    }

    @Test fun limitAndFormatAreClamped() {
        assertTrue(JamendoApi.tracksUrl("k", null, "any", "auto", -5, limit = 5000).let { it.contains("limit=200") && it.contains("offset=0") })
        assertEquals("mp32", JamendoApi.safeAudioFormat("wav"))
        assertEquals("flac", JamendoApi.safeAudioFormat("flac"))
        assertTrue(JamendoApi.tracksUrl("k", null, "any", "auto", 0, audioFormat = "bogus").contains("audioformat=mp32"))
    }

    @Test fun clientIdIsTrimmedOfPastedWhitespace() {
        assertEquals("abc123", JamendoApi.cleanClientId("  abc 123\n"))
        assertTrue(JamendoApi.probeUrl(" abc123 ").contains("client_id=abc123"))
    }

    @Test fun trackByIdAndArtistUrls() {
        assertTrue(JamendoApi.trackByIdUrl("k", " 42 ").contains("id=42"))
        val artists = JamendoApi.artistsUrl("k", "fan kel")
        assertTrue(artists.contains("/artists/?") && artists.contains("namesearch=fan+kel") && artists.contains("limit=5"))
    }

    // ---- parsing & mapping -----------------------------------------------------------------------------

    @Test fun parseTracks_mapsEveryDisplayedField() {
        val page = JamendoApi.parseTracks(response(trackJson))
        assertEquals(1, page.items.size)
        val t = page.items[0]
        assertEquals("1848357", t.id)
        assertEquals("mañana será tarde", t.title)
        assertEquals("fankel", t.artist)
        assertEquals("mañana será tarde", t.album)
        assertEquals("fankel · mañana será tarde", t.subtitle)
        assertEquals(272_000L, t.durationMs)
        assertEquals("4:32", t.durationLabel)
        assertEquals("audio", t.mediaType)
        assertTrue(t.directPlayUrl.startsWith("https://prod-1.storage.jamendo.com/?trackid=1848357"))
        assertEquals("https://usercontent.jamendo.com?type=album&id=368084&width=300", t.artworkUrl)
        assertEquals("https://www.jamendo.com/track/1848357", t.pageUrl)
        assertEquals("https://creativecommons.org/licenses/by-nc-nd/3.0/", t.licenseUrl) // http -> https
    }

    @Test fun parseTracks_singleHasNoAlbum() {
        val single = """{"id":"9","name":"Solo","duration":61,"artist_name":"Someone","album_name":"","album_id":"","album_image":"",
            "image":"https://x.example/i.jpg","audio":"https://a.example/9"}"""
        val t = JamendoApi.parseTracks(response(single)).items.single()
        assertNull(t.album)
        assertEquals("Someone", t.subtitle)
        assertEquals("1:01", t.durationLabel)
    }

    @Test fun parseTracks_missingOptionalMetadataIsHandled() {
        // No artwork, no duration, no licence, no page URL, JSON nulls — must never show the text "null".
        val bare = """{"id":"5","name":null,"artist_name":null,"audio":"https://a.example/5","image":null,"duration":null}"""
        val t = JamendoApi.parseTracks(response(bare)).items.single()
        assertEquals("Untitled", t.title)
        assertNull(t.artist)
        assertNull(t.subtitle)
        assertNull(t.artworkUrl)
        assertNull(t.licenseUrl)
        assertNull(t.pageUrl)
        assertEquals(0L, t.durationMs)
        assertNull(t.durationLabel)
    }

    @Test fun parseTracks_fallsBackToAlbumImage() {
        val j = """{"id":"6","name":"A","audio":"https://a.example/6","album_image":"https://x.example/album.jpg"}"""
        assertEquals("https://x.example/album.jpg", JamendoApi.parseTracks(response(j)).items.single().artworkUrl)
    }

    @Test fun parseTracks_skipsUnplayableButCountsThem() {
        val noAudio = """{"id":"7","name":"No stream","audio":""}"""
        val noId = """{"name":"No id","audio":"https://a.example/x"}"""
        val page = JamendoApi.parseTracks(response(trackJson, noAudio, noId))
        assertEquals(listOf("1848357"), page.items.map { it.id })
        assertEquals(3, page.rawCount) // paging uses what the API returned, not what was shown
    }

    @Test fun emptyResults() {
        val page = JamendoApi.parseTracks(response())
        assertTrue(page.items.isEmpty())
        assertFalse(JamendoApi.hasMore(page.rawCount))
        assertTrue(JamendoApi.parseTracks("""{"headers":{"status":"success","code":0},"results":[]}""").items.isEmpty())
        assertTrue(JamendoApi.parseTracks("""{"headers":{"status":"success","code":0}}""").items.isEmpty())
    }

    @Test fun pagination() {
        assertTrue(JamendoApi.hasMore(JamendoApi.PAGE_SIZE))
        assertFalse(JamendoApi.hasMore(JamendoApi.PAGE_SIZE - 1))
    }

    @Test fun parseArtists() {
        val raw = """{"headers":{"status":"success","code":0,"results_count":3},"results":[
            {"id":"421168","name":"fankel","image":"http://i.example/a.jpg","website":"https://fankel.example","shareurl":"https://www.jamendo.com/artist/421168"},
            {"id":"2","name":"No Image"},
            {"id":"","name":"Dropped"}]}"""
        val artists = JamendoApi.parseArtists(raw)
        assertEquals(listOf("421168", "2"), artists.map { it.id })
        assertEquals("https://i.example/a.jpg", artists[0].imageUrl)
        assertNull(artists[1].imageUrl)
    }

    // ---- errors ----------------------------------------------------------------------------------------

    @Test fun apiErrorCodes() {
        val bad = messageOf { JamendoApi.parseResults(response(code = 5, message = "invalid client_id abc123")) }!!
        assertTrue(bad.contains("client ID"))
        assertFalse("the key must never be echoed", bad.contains("abc123"))
        assertTrue(messageOf { JamendoApi.parseResults(response(code = 6)) }!!.contains("limit"))
        assertTrue(messageOf { JamendoApi.parseResults(response(code = 11)) }!!.contains("suspended"))
        assertEquals("Jamendo error: Oops", messageOf { JamendoApi.parseResults(response(code = 1, message = "Oops")) })
        assertEquals("Jamendo returned an error (code 4)", messageOf { JamendoApi.parseResults(response(code = 4)) })
        // An API message carrying a URL (which could hold the key) is dropped.
        assertEquals(
            "Jamendo returned an error (code 3)",
            messageOf { JamendoApi.parseResults(response(code = 3, message = "see https://api.jamendo.com/?client_id=abc123")) }
        )
    }

    @Test fun errorCodeIsExposed() {
        val e = try { JamendoApi.parseResults(response(code = 5)); null } catch (e: JamendoApi.JamendoException) { e }
        assertEquals(5, e?.code)
    }

    @Test fun malformedResponses() {
        assertEquals("Jamendo sent a response Beatraxus couldn't read", messageOf { JamendoApi.parseResults("<html>") })
        assertEquals("That doesn't look like a Jamendo response", messageOf { JamendoApi.parseResults("""{"hello":1}""") })
        // No numeric code but status "failed" is still an error.
        assertTrue(messageOf { JamendoApi.parseResults("""{"headers":{"status":"failed"}}""") } != null)
    }

    @Test fun httpFailureMessages() {
        assertTrue(JamendoApi.httpMessage(429).contains("limit"))
        assertTrue(JamendoApi.httpMessage(503).contains("try again later"))
        assertEquals("Jamendo answered with an error (HTTP 404)", JamendoApi.httpMessage(404))
    }
}
