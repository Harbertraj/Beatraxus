package com.beatraxus.app.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PodcastFeedParserTest {
    @Test
    fun parsesRssEpisodesAndDuration() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Example Show</title><description>Weekly audio</description>
              <image><url>https://example.com/cover.jpg</url></image>
              <item><guid>ep-1</guid><title>Episode One</title>
                <description>First episode</description>
                <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="1234"/>
                <duration>01:02:03</duration><pubDate>Tue, 01 Jan 2025 00:00:00 GMT</pubDate>
              </item>
            </channel></rss>
        """.trimIndent().toByteArray()

        val feed = PodcastFeedParser.parse(xml)
        assertEquals("Example Show", feed.title)
        assertEquals("https://example.com/cover.jpg", feed.imageUrl)
        assertEquals(1, feed.episodes.size)
        assertEquals("ep-1", feed.episodes.single().id)
        assertEquals(3_723_000L, feed.episodes.single().durationMs)
    }

    @Test
    fun skipsNonAudioAndUnsafeEnclosures() {
        val xml = """
            <rss version="2.0"><channel><title>Show</title>
              <item><title>Video</title><enclosure url="https://example.com/a.mp4" type="video/mp4"/></item>
              <item><title>Local</title><enclosure url="file:///tmp/a.mp3" type="audio/mpeg"/></item>
              <item><title>Good</title><enclosure url="https://example.com/a.mp3" type="audio/mpeg"/></item>
            </channel></rss>
        """.trimIndent().toByteArray()
        assertEquals(listOf("Good"), PodcastFeedParser.parse(xml).episodes.map { it.title })
    }

    @Test
    fun rejectsDoctypeAndOversizedFeed() {
        val xxe = """<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        assertThrows(Exception::class.java) { PodcastFeedParser.parse(xxe.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            PodcastFeedParser.parse(ByteArray(PodcastFeedParser.MAX_FEED_BYTES + 1))
        }
    }

    @Test
    fun handlesMissingDuration() {
        val xml = """<rss><channel><title>Show</title><item><title>Episode</title><enclosure url="https://e.test/a.mp3" type="audio/mpeg"/></item></channel></rss>"""
        assertNull(PodcastFeedParser.parse(xml.toByteArray()).episodes.single().durationMs)
    }
}
