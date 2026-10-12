package com.beatraxus.app.addons

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SubsonicApiTest {

    @Test fun md5_matchesSubsonicDocsExample() {
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicApi.md5Hex("sesame" + "c19b2d"))
        val (salt, token) = SubsonicApi.makeCredentials("sesame", "c19b2d")
        assertEquals("c19b2d", salt)
        assertEquals("26719a1196d2a940705a59634eb18eab", token)
    }

    @Test fun normalizeUrl() {
        assertEquals("http://navidrome.local:4533", SubsonicApi.normalizeUrl("navidrome.local:4533"))
        assertEquals("https://m.example.com", SubsonicApi.normalizeUrl(" https://m.example.com/ "))
        assertEquals("https://m.example.com", SubsonicApi.normalizeUrl("https://m.example.com/rest/"))
        assertEquals("https://example.com/navidrome", SubsonicApi.normalizeUrl("https://example.com/navidrome"))
        assertEquals("", SubsonicApi.normalizeUrl("  "))
    }

    @Test fun buildUrl_carriesTokenAuthAndNeverThePassword() {
        val (salt, token) = SubsonicApi.makeCredentials("sesame", "c19b2d")
        val url = SubsonicApi.buildUrl("http://h:4533", "stream", "joe bloggs", salt, token, mapOf("id" to "al-1/x", "format" to "raw"))
        assertTrue(url.startsWith("http://h:4533/rest/stream.view?"))
        assertTrue(url.contains("u=joe+bloggs") && url.contains("t=$token") && url.contains("s=c19b2d"))
        assertTrue(url.contains("v=1.16.1") && url.contains("c=beatraxus") && url.contains("f=json"))
        assertTrue(url.contains("id=al-1%2Fx") && url.contains("format=raw"))
        assertFalse(url.contains("sesame"))
    }

    @Test fun checkResponse() {
        val ok = SubsonicApi.checkResponse("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
        assertEquals("ok", ok.optString("status"))
        assertEquals(
            "Wrong username or password",
            messageOf { SubsonicApi.checkResponse("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"x"}}}""") }
        )
        assertEquals(
            "Song not found",
            messageOf { SubsonicApi.checkResponse("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Song not found"}}}""") }
        )
        assertEquals("That doesn't look like a Subsonic server", messageOf { SubsonicApi.checkResponse("<html>nope</html>") })
        assertEquals("That doesn't look like a Subsonic server", messageOf { SubsonicApi.checkResponse("""{"hello":1}""") })
    }

    @Test fun parseSongs() {
        val container = JSONObject(
            """{"song":[
                {"id":"s1","title":"Song One","artist":"Artist A","album":"Album X","duration":215,"coverArt":"al-9"},
                {"id":"s2","title":"","artist":"","album":"","duration":0},
                {"title":"no id"}
            ]}"""
        )
        val songs = SubsonicApi.parseSongs(container) { "COVER:$it" }
        assertEquals(2, songs.size)
        assertEquals("s1", songs[0].id)
        assertEquals("Artist A · Album X", songs[0].subtitle)
        assertEquals(215_000L, songs[0].durationMs)
        assertEquals("COVER:al-9", songs[0].artworkUrl)
        assertEquals("audio", songs[0].mediaType)
        assertEquals("Unknown", songs[1].title)
        assertNull(songs[1].subtitle)
        assertNull(songs[1].artworkUrl)

        val single = SubsonicApi.parseSongs(JSONObject("""{"song":{"id":"only","title":"T"}}""")) { it }
        assertEquals(listOf("only"), single.map { it.id })
        assertEquals(0, SubsonicApi.parseSongs(null) { it }.size)
    }

    private fun messageOf(block: () -> Unit): String? = try { block(); null } catch (e: IOException) { e.message }
}

class EmbyApiTest {

    @Test fun normalizeUrl() {
        assertEquals("http://192.168.1.20:8096/emby", EmbyApi.normalizeUrl("192.168.1.20:8096/"))
        assertEquals("https://e.example.com/emby", EmbyApi.normalizeUrl("https://e.example.com/emby"))
        assertEquals("", EmbyApi.normalizeUrl(""))
    }

    @Test fun urls() {
        val browse = EmbyApi.itemsUrl("http://h/emby", "u1", null)
        assertTrue(browse.startsWith("http://h/emby/Users/u1/Items?"))
        assertTrue(browse.contains("SortBy=DateCreated") && !browse.contains("SearchTerm"))
        val search = EmbyApi.itemsUrl("http://h/emby", "u1", "  daft punk ")
        assertTrue(search.contains("SearchTerm=daft+punk") && !search.contains("SortBy"))
        assertEquals("http://h/emby/Audio/42/stream?static=true&api_key=tok", EmbyApi.streamUrl("http://h/emby", "tok", "42", "audio"))
        assertEquals("http://h/emby/Videos/42/stream?static=true&api_key=tok", EmbyApi.streamUrl("http://h/emby", "tok", "42", "video"))
    }

    @Test fun parseItems() {
        val items = EmbyApi.parseItems(
            JSONObject(
                """{"Items":[
                    {"Id":"1","Name":"Track","Type":"Audio","Artists":["Daft Punk"],"Album":"Discovery","RunTimeTicks":2400000000},
                    {"Id":"2","Name":"A Movie","Type":"Movie","ProductionYear":1999,"RunTimeTicks":60000000000},
                    {"Id":"3","Name":"Pilot","Type":"Episode","SeriesName":"Some Show"},
                    {"Name":"no id","Type":"Movie"}
                ]}"""
            ),
            "http://h/emby"
        )
        assertEquals(3, items.size)
        assertEquals("Daft Punk · Discovery", items[0].subtitle)
        assertEquals("audio", items[0].mediaType)
        assertEquals(240_000L, items[0].durationMs)
        assertEquals("1999", items[1].subtitle)
        assertEquals("video", items[1].mediaType)
        assertEquals("Some Show", items[2].subtitle)
        assertEquals("http://h/emby/Items/1/Images/Primary?maxHeight=400&maxWidth=400", items[0].artworkUrl)
        assertEquals(0, EmbyApi.parseItems(JSONObject("{}"), "http://h").size)
    }
}
