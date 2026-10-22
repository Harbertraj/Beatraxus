package com.beatraxus.app.cast

import com.beatraxus.app.cast.VideoCastSupport.RangeResult
import com.beatraxus.app.cast.VideoCastSupport.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCastSupportTest {

    @Test
    fun routeDependsOnTheUriScheme() {
        assertEquals(Route.LOCAL_SERVER, VideoCastSupport.routeFor("content"))
        assertEquals(Route.LOCAL_SERVER, VideoCastSupport.routeFor("FILE"))
        assertEquals(Route.DIRECT, VideoCastSupport.routeFor("https"))
        assertEquals(Route.DIRECT, VideoCastSupport.routeFor("http"))
        assertEquals(Route.UNSUPPORTED, VideoCastSupport.routeFor("tgvideo"))
        assertEquals(Route.UNSUPPORTED, VideoCastSupport.routeFor("cloudvideo"))
        assertEquals(Route.UNSUPPORTED, VideoCastSupport.routeFor(null))
    }

    @Test
    fun contentTypeUsesTheLibraryMimeWhenItIsAVideoType() {
        assertEquals("video/x-matroska", VideoCastSupport.contentType("video/x-matroska", "a.mkv"))
        assertEquals("video/mp4", VideoCastSupport.contentType("video/quicktime", "a.mov"))
        assertEquals("video/webm", VideoCastSupport.contentType("VIDEO/WEBM", "a.webm"))
    }

    @Test
    fun contentTypeFallsBackToTheExtension() {
        assertEquals("video/x-matroska", VideoCastSupport.contentType(null, "Movie.2024.mkv"))
        assertEquals("video/webm", VideoCastSupport.contentType("", "clip.webm"))
        assertEquals("video/mp2t", VideoCastSupport.contentType("application/octet-stream", "rec.ts"))
        assertEquals(VideoCastSupport.MIME_DEFAULT, VideoCastSupport.contentType(null, "noextension"))
        assertEquals(VideoCastSupport.MIME_DEFAULT, VideoCastSupport.contentType(null, null))
    }

    @Test
    fun playlistsAreRecognisedEvenInTheQueryString() {
        assertEquals(VideoCastSupport.MIME_HLS, VideoCastSupport.contentType(null, "https://x.tv/live/index.m3u8?token=1"))
        assertEquals(VideoCastSupport.MIME_HLS, VideoCastSupport.contentType("video/mp4", "https://x.tv/get?file=a.m3u8"))
        assertEquals(VideoCastSupport.MIME_DASH, VideoCastSupport.contentType(null, "https://x.tv/v/manifest.mpd"))
    }

    @Test
    fun rangeWithoutHeaderOrSizeSendsEverything() {
        assertEquals(RangeResult.Full, VideoCastSupport.parseRange(null, 1000))
        assertEquals(RangeResult.Full, VideoCastSupport.parseRange("", 1000))
        assertEquals(RangeResult.Full, VideoCastSupport.parseRange("bytes=0-99", 0))
        assertEquals(RangeResult.Full, VideoCastSupport.parseRange("garbage", 1000))
    }

    @Test
    fun openEndedAndBoundedRanges() {
        assertEquals(RangeResult.Partial(0, 999), VideoCastSupport.parseRange("bytes=0-", 1000))
        assertEquals(RangeResult.Partial(100, 199), VideoCastSupport.parseRange("bytes=100-199", 1000))
        assertEquals(RangeResult.Partial(100, 199), VideoCastSupport.parseRange("100-199", 1000))
        // End past the file is clamped, as the HTTP spec says.
        assertEquals(RangeResult.Partial(900, 999), VideoCastSupport.parseRange("bytes=900-5000", 1000))
        assertEquals(100L, (VideoCastSupport.parseRange("bytes=100-199", 1000) as RangeResult.Partial).length)
    }

    @Test
    fun suffixRangeMeansTheLastBytes() {
        assertEquals(RangeResult.Partial(500, 999), VideoCastSupport.parseRange("bytes=-500", 1000))
        assertEquals(RangeResult.Partial(0, 999), VideoCastSupport.parseRange("bytes=-5000", 1000))
        assertEquals(RangeResult.NotSatisfiable, VideoCastSupport.parseRange("bytes=-0", 1000))
    }

    @Test
    fun rangesOutsideTheFileAreNotSatisfiable() {
        assertEquals(RangeResult.NotSatisfiable, VideoCastSupport.parseRange("bytes=1000-", 1000))
        assertEquals(RangeResult.NotSatisfiable, VideoCastSupport.parseRange("bytes=500-100", 1000))
    }

    @Test
    fun onlyTheFirstOfSeveralRangesIsServed() {
        assertEquals(RangeResult.Partial(0, 9), VideoCastSupport.parseRange("bytes=0-9, 20-29", 1000))
    }
}
