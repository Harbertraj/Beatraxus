package com.beatraxus.app.livetv

import java.util.Locale

/**
 * Parses standard M3U playlists:
 *
 * ```
 * #EXTINF:-1 tvg-id="BBCNews.uk@SD" tvg-logo="https://..." group-title="News",BBC News (720p) [Not 24/7]
 * #EXTVLCOPT:http-referrer=https://example.com/
 * #EXTVLCOPT:http-user-agent=Mozilla/5.0
 * https://example.com/live/master.m3u8
 * ```
 *
 * Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 */
internal object M3uParser {

    private val ATTRIBUTE = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val QUALITY = Regex("""\((\d{3,4})p\)""")
    private val TAG = Regex("""\s*\[[^\]]*]""")
    private val QUALITY_STRIP = Regex("""\s*\(\d{3,4}p\)""")

    fun parse(text: String): List<LiveChannel> {
        val found = ArrayList<LiveChannel>()
        var attrs: Map<String, String>? = null
        var title = ""
        var referrer: String? = null
        var userAgent: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    attrs = ATTRIBUTE.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    // The display name is whatever follows the last comma that is outside the quoted attributes.
                    val afterAttributes = line.substring(ATTRIBUTE.findAll(line).lastOrNull()?.range?.last?.plus(1) ?: 0)
                    title = afterAttributes.substringAfter(',', "").trim()
                    referrer = null
                    userAgent = null
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase(Locale.ROOT)
                    val value = option.substringAfter('=', "").trim().trim('"')
                    when (key) {
                        "http-referrer", "http-referer" -> referrer = value.ifBlank { null }
                        "http-user-agent" -> userAgent = value.ifBlank { null }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val a = attrs
                    if (a != null && (line.startsWith("http://") || line.startsWith("https://"))) {
                        found += channel(a, title, line, referrer, userAgent)
                    }
                    attrs = null
                    title = ""
                    referrer = null
                    userAgent = null
                }
            }
        }
        return dedupe(found)
    }

    private fun channel(
        attrs: Map<String, String>,
        rawTitle: String,
        url: String,
        referrer: String?,
        userAgent: String?
    ): LiveChannel {
        val name = rawTitle.ifBlank { attrs["tvg-name"].orEmpty() }
        val tvgId = attrs["tvg-id"].orEmpty().trim()
        val height = QUALITY.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cleanName = name.replace(TAG, "").replace(QUALITY_STRIP, "").trim().ifBlank { "Channel" }
        // "BBCNews.uk@SD" -> id "BBCNews.uk", country "UK"
        val idCore = tvgId.substringBefore('@')
        val country = idCore.substringAfterLast('.', "").takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
        return LiveChannel(
            id = idCore.ifBlank { "u" + Integer.toHexString(url.hashCode()) },
            name = cleanName,
            streamUrl = url,
            logoUrl = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
            group = attrs["group-title"].orEmpty().substringBefore(';').trim(),
            countryCode = country,
            qualityHeight = height,
            isGeoBlocked = name.contains("[Geo-blocked]", ignoreCase = true),
            isNot247 = name.contains("[Not 24/7]", ignoreCase = true),
            referrer = referrer,
            userAgent = userAgent
        )
    }

    /**
     * A channel often has several feeds (SD / HD / mirrors). Keep one entry per channel id — the best-looking feed:
     * not geo-blocked, 24/7, then the highest resolution — and return the list sorted by name.
     */
    private fun dedupe(channels: List<LiveChannel>): List<LiveChannel> {
        val best = LinkedHashMap<String, LiveChannel>()
        for (c in channels) {
            val current = best[c.id]
            if (current == null || score(c) > score(current)) best[c.id] = c
        }
        return best.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun score(c: LiveChannel): Int =
        (if (c.isGeoBlocked) 0 else 100_000) + (if (c.isNot247) 0 else 50_000) + c.qualityHeight
}
