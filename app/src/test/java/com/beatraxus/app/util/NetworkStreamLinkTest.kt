package com.beatraxus.app.util

import com.beatraxus.app.util.NetworkStreamLink.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 27: pasted network / stream links. Pure JVM; nothing here touches Android classes. */
class NetworkStreamLinkTest {

    private fun valid(raw: String): String {
        val r = NetworkStreamLink.parse(raw)
        assertTrue("expected Valid for <$raw> but got $r", r is Result.Valid)
        return (r as Result.Valid).url
    }

    private fun invalid(raw: String): String {
        val r = NetworkStreamLink.parse(raw)
        assertTrue("expected Invalid for <$raw> but got $r", r is Result.Invalid)
        return (r as Result.Invalid).message
    }

    @Test
    fun plainHttpAndHttpsLinksAreAccepted() {
        assertEquals("https://example.com/video.mp4", valid("https://example.com/video.mp4"))
        assertEquals("http://example.com/live/index.m3u8", valid("  http://example.com/live/index.m3u8\n"))
    }

    @Test
    fun wrappersAndSurroundingTextAreStripped() {
        assertEquals("https://a.com/x.mp4", valid("\"https://a.com/x.mp4\""))
        assertEquals("https://a.com/x.mp4", valid("<https://a.com/x.mp4>"))
        assertEquals("https://a.com/x.mp4", valid("watch this https://a.com/x.mp4, it's good"))
        assertEquals("https://a.com/x.mp4", valid("https://a.com/x.mp4."))
    }

    @Test
    fun queryStringsAreKeptIntact() {
        val url = "https://cdn.example.com/play?id=42&token=abc.def&fmt=m3u8"
        assertEquals(url, valid(url))
    }

    @Test
    fun missingSchemeDefaultsToHttpsForDomainsAndHttpForLanAddresses() {
        assertEquals("https://example.com/live.m3u8", valid("example.com/live.m3u8"))
        assertEquals("http://192.168.1.20:8080/movie.mkv", valid("192.168.1.20:8080/movie.mkv"))
    }

    @Test
    fun unsupportedSchemesGetAClearMessage() {
        assertTrue(invalid("rtsp://camera.local/stream").contains("RTSP"))
        assertTrue(invalid("rtmp://live.example.com/app/key").contains("RTMP"))
        assertTrue(invalid("ftp://files.example.com/a.mp4").contains("FTP"))
        assertTrue(invalid("file:///sdcard/a.mp4").isNotBlank())
    }

    @Test
    fun junkIsRejected() {
        invalid("")
        invalid("   ")
        invalid("hello")
        invalid("hello world, no link here")
        invalid("https://")
        invalid("http://user:secret@example.com/a.mp4")
        invalid("https://example.com/" + "a".repeat(NetworkStreamLink.MAX_LENGTH))
    }

    @Test
    fun titleComesFromTheFileNameElseTheHost() {
        assertEquals("My Great Film 2020", NetworkStreamLink.titleFor("https://cdn.example.com/Movies/My_Great.Film.2020.mp4"))
        assertEquals("My Video", NetworkStreamLink.titleFor("https://a.com/My%20Video.mp4"))
        assertEquals("live.example.com", NetworkStreamLink.titleFor("https://live.example.com/hls/index.m3u8"))
        assertEquals("example.com", NetworkStreamLink.titleFor("https://example.com/"))
    }

    @Test
    fun mimeHintOnlyForManifestsThatHideTheirType() {
        assertNull(NetworkStreamLink.mimeTypeFor("https://a.com/live/stream.m3u8?token=abc"))
        assertNull(NetworkStreamLink.mimeTypeFor("https://a.com/manifest.mpd"))
        assertNull(NetworkStreamLink.mimeTypeFor("https://a.com/movie.mp4"))
        assertEquals("application/x-mpegURL", NetworkStreamLink.mimeTypeFor("https://a.com/play?id=1&fmt=m3u8"))
        assertEquals("application/dash+xml", NetworkStreamLink.mimeTypeFor("https://a.com/get?file=a.mpd"))
    }

    @Test
    fun sameLinkAlwaysGetsTheSameVideoId() {
        val a = NetworkStreamLink.videoIdFor("https://a.com/x.mp4")
        assertEquals(a, NetworkStreamLink.videoIdFor("https://a.com/x.mp4"))
        assertNotEquals(a, NetworkStreamLink.videoIdFor("https://a.com/y.mp4"))
        assertTrue(a.startsWith("net_"))
    }
}
