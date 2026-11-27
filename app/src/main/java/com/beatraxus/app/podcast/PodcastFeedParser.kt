package com.beatraxus.app.podcast

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class PodcastFeed(
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val episodes: List<PodcastEpisode>
)

data class PodcastEpisode(
    val id: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val mimeType: String?,
    val durationMs: Long?,
    val publishedAt: String?
)

/**
 * Bounded RSS 2.0 parser for podcast feeds. Network fetching, caching, subscription persistence,
 * UI, and playback queue integration remain the responsibility of the calling layer.
 */
object PodcastFeedParser {
    const val MAX_FEED_BYTES = 2 * 1024 * 1024
    const val MAX_EPISODES = 500

    fun parse(xml: ByteArray): PodcastFeed {
        require(xml.isNotEmpty()) { "Podcast feed is empty" }
        require(xml.size <= MAX_FEED_BYTES) { "Podcast feed exceeds the 2 MiB limit" }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val channel = document.getElementsByTagName("channel").item(0) as? Element
            ?: throw IllegalArgumentException("RSS channel element is missing")

        val title = channel.childText("title").orEmpty().trim()
        require(title.isNotBlank()) { "Podcast title is missing" }
        val description = channel.childText("description")?.cleanText()
        val imageUrl = channel.childElement("image")?.childText("url")?.let(::safeHttpUrl)

        val itemNodes = channel.getElementsByTagName("item")
        require(itemNodes.length <= MAX_EPISODES) { "Podcast feed contains too many episodes" }
        val episodes = buildList {
            for (index in 0 until itemNodes.length) {
                val item = itemNodes.item(index) as? Element ?: continue
                val enclosure = item.childElement("enclosure") ?: continue
                val audioUrl = safeHttpUrl(enclosure.getAttribute("url")) ?: continue
                val mime = enclosure.getAttribute("type").trim().ifBlank { null }
                if (mime != null && !mime.startsWith("audio/", ignoreCase = true)) continue

                val guid = item.childText("guid")?.trim()?.takeIf { it.isNotBlank() }
                val episodeTitle = item.childText("title")?.cleanText().orEmpty()
                if (episodeTitle.isBlank()) continue
                val duration = item.childText("duration")?.let(::parseDuration)
                add(
                    PodcastEpisode(
                        id = guid ?: audioUrl,
                        title = episodeTitle,
                        description = (item.childText("description") ?: item.childText("encoded"))?.cleanText(),
                        audioUrl = audioUrl,
                        mimeType = mime,
                        durationMs = duration,
                        publishedAt = item.childText("pubDate")?.trim()?.ifBlank { null }
                    )
                )
            }
        }
        return PodcastFeed(title, description, imageUrl, episodes)
    }

    private fun parseDuration(value: String): Long? {
        val text = value.trim()
        val seconds = text.toLongOrNull()
        if (seconds != null) return seconds.takeIf { it >= 0 }?.times(1000L)
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        val totalSeconds = when (numbers.size) {
            2 -> numbers[0] * 60L + numbers[1]
            else -> numbers[0] * 3600L + numbers[1] * 60L + numbers[2]
        }
        return totalSeconds.takeIf { it <= Long.MAX_VALUE / 1000L }?.times(1000L)
    }

    private fun safeHttpUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            require(!uri.host.isNullOrBlank() && uri.userInfo == null)
            uri.toString()
        } else null
    }.getOrNull()

    private fun Element.childElement(name: String): Element? {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE &&
                (child.localName ?: child.nodeName.substringAfter(':')) == name) return child as Element
        }
        return null
    }

    private fun Element.childText(name: String): String? =
        childElement(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.cleanText(): String = trim().take(20_000)
}

/** Chapter metadata shared by audiobook importers and the existing single-player queue. */
data class AudiobookChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long?
)

/** Playback bookmark stored independently from music shuffle/repeat state. */
data class AudiobookProgress(
    val bookId: String,
    val positionMs: Long,
    val updatedAtEpochMs: Long
)

/** Small, deterministic helpers for chapter navigation and resume UI. */
object AudiobookChapterIndex {
    fun chapterAt(chapters: List<AudiobookChapter>, positionMs: Long): Int {
        if (chapters.isEmpty()) return -1
        val position = positionMs.coerceAtLeast(0L)
        return chapters.indexOfLast { it.startMs <= position }.coerceAtLeast(0)
    }

    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble())
            .coerceIn(0.0, 1.0).toFloat()
    }

    fun validate(chapters: List<AudiobookChapter>): List<AudiobookChapter> {
        var previousStart = -1L
        chapters.forEachIndexed { index, chapter ->
            require(chapter.index == index) { "Chapter indexes must be contiguous and zero-based" }
            require(chapter.startMs >= 0L && chapter.startMs > previousStart) {
                "Chapter start times must be non-negative and strictly increasing"
            }
            require(chapter.endMs == null || chapter.endMs > chapter.startMs) {
                "Chapter end must be after its start"
            }
            previousStart = chapter.startMs
        }
        return chapters
    }
}
