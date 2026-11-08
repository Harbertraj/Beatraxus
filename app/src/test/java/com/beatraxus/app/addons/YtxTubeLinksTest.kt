package com.beatraxus.app.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtxTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun watchLinksAreKept() {
        assertEquals("https://www.youtube.com/watch?v=$id", YtxTubeLinks.videoLink("https://www.youtube.com/watch?v=$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YtxTubeLinks.videoLink("https://m.youtube.com/watch?feature=share&v=$id"))
    }

    @Test fun shortLinksAndSharedTextAreRecognised() {
        assertEquals("https://www.youtube.com/watch?v=$id", YtxTubeLinks.videoLink("https://youtu.be/$id?si=abc"))
        assertEquals("https://www.youtube.com/watch?v=$id", YtxTubeLinks.videoLink("Listen to this https://youtu.be/$id now"))
        assertEquals("https://www.youtube.com/watch?v=$id", YtxTubeLinks.videoLink("vnd.youtube:$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YtxTubeLinks.videoLink("vnd.youtube://$id"))
    }

    @Test fun shortsKeepTheirForm() {
        assertEquals("https://www.youtube.com/shorts/$id", YtxTubeLinks.videoLink("https://www.youtube.com/shorts/$id"))
    }

    @Test fun plainSearchTextIsNotALink() {
        assertNull(YtxTubeLinks.videoLink("local boys ethir neechal"))
        assertNull(YtxTubeLinks.videoLink("Bohemian123"))   // 11 characters, but not an ID without a link
        assertNull(YtxTubeLinks.videoLink("   "))
        assertNull(YtxTubeLinks.videoLink(null))
    }
}
