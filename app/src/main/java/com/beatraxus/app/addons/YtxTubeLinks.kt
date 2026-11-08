package com.beatraxus.app.addons

/**
 * What the YTXTube app (https://github.com/Koustubh12345/YTXTube) accepts from other apps. Kept free of Android
 * classes so it can be unit-tested on a plain JVM.
 *
 * YTXTube opens YouTube links (watch / shorts / live / embed, youtu.be, vnd.youtube:ID). It has no text-search
 * intent, so a plain search phrase can only open the app, not run the search.
 */
object YtxTubeLinks {
    const val PACKAGE = "com.tensei.ytxtube"
    const val RELEASES_URL = "https://github.com/Koustubh12345/YTXTube/releases/latest"

    // Same pattern YTXTube's own MainActivity uses to read an incoming link.
    private val linkRegex = Regex(
        """(?i)\b(?:https?://|vnd\.youtube:)?(?:(?:www\.|m\.)?youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/|v/)|youtu\.be/)([\w-]{11})"""
    )

    // vnd.youtube:ID (or vnd.youtube://ID): YTXTube's own parser does not read it, so it is turned into a watch link here.
    private val vndRegex = Regex("""(?i)\bvnd\.youtube:(?://)?([\w-]{11})(?![\w-])""")

    /**
     * A link YTXTube will open for [query], or null when [query] holds no YouTube video link.
     * Shorts keep their /shorts/ form (YTXTube plays them in the Shorts view); everything else becomes a watch link.
     */
    fun videoLink(query: String?): String? {
        val text = query?.trim().orEmpty()
        if (text.isEmpty()) return null
        val m = linkRegex.find(text)
        if (m == null) {
            val v = vndRegex.find(text) ?: return null
            return "https://www.youtube.com/watch?v=${v.groupValues[1]}"
        }
        val id = m.groupValues[1]
        return if (text.contains("/shorts/", ignoreCase = true)) "https://www.youtube.com/shorts/$id"
        else "https://www.youtube.com/watch?v=$id"
    }
}
