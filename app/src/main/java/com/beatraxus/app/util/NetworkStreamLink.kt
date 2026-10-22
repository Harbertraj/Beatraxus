package com.beatraxus.app.util

import android.net.Uri
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Feature 27: open a pasted network or stream link.
 *
 * Turns whatever the user pasted (a bare address, an address wrapped in quotes or `<>`, a line of copied text that
 * contains one) into a playable [Video]. Only plain http(s) addresses are accepted: that covers progressive files
 * (mp4, mkv, webm ...), HLS (.m3u8) and DASH (.mpd), which is everything the player's media sources can open.
 * Everything here is pure Kotlin/JVM so it can be unit-tested without a device (see NetworkStreamLinkTest).
 */
object NetworkStreamLink {

    /** Longest address accepted; real stream links with long tokens stay well below this. */
    const val MAX_LENGTH = 4000

    sealed interface Result {
        /** [url] is the cleaned address that will be played. */
        data class Valid(val url: String) : Result

        /** [message] is user-facing and shown under the text field. */
        data class Invalid(val message: String) : Result
    }

    private val urlInText = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://\S+""")
    private val unsupportedSchemes = setOf("rtsp", "rtmp", "rtmps", "rtmpt", "mms", "mmsh", "udp", "rtp", "srt", "ftp", "sftp", "smb")

    /** Characters people leave around a link when they copy it from chat or a document. */
    private val wrapperChars = charArrayOf('"', '\'', '<', '>', '(', ')', '[', ']', '{', '}', '`', ',', ';')

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid("Paste a link to a video or stream.")

        val candidate = extractCandidate(text)
            ?: return Result.Invalid("That doesn't look like a link. It should start with http:// or https://")

        val scheme = candidate.substringBefore("://").lowercase(Locale.ROOT)
        if (scheme in unsupportedSchemes) {
            return Result.Invalid("${scheme.uppercase(Locale.ROOT)} links aren't supported yet. Use an http(s) link to a file, HLS (.m3u8) or DASH (.mpd) stream.")
        }
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("Only http:// and https:// links can be opened.")
        }
        if (candidate.length > MAX_LENGTH) return Result.Invalid("That link is too long.")

        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return Result.Invalid("That link has characters it can't contain. Check it for spaces or typos.")
        if (uri.host.isNullOrBlank()) return Result.Invalid("The link has no address after http(s)://")
        if (uri.userInfo != null) {
            return Result.Invalid("Links with a username and password in them aren't supported.")
        }
        return Result.Valid(candidate)
    }

    /** The first http(s)-looking address in [text]: the whole text when it is one token, else the first match inside it. */
    private fun extractCandidate(text: String): String? {
        val whole = text.trim(*wrapperChars).trim()
        val token = if (whole.none { it.isWhitespace() }) whole else {
            urlInText.find(text)?.value?.trim(*wrapperChars) ?: return null
        }
        // Without a scheme ("example.com/live.m3u8") assume https, the way browsers do. A bare LAN / NAS address
        // such as 192.168.1.20:8080/movie.mkv assumes http, because home servers rarely have a certificate.
        val clean = token.trimEnd('.', '!', '?')
        return when {
            token.contains("://") -> clean
            looksLikeBareHost(token) -> (if (isIpv4(hostOf(token))) "http://" else "https://") + clean
            else -> null
        }
    }

    private fun hostOf(token: String): String =
        token.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')

    private fun isIpv4(host: String): Boolean =
        host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

    private fun looksLikeBareHost(token: String): Boolean {
        val host = hostOf(token)
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return false
        return isIpv4(host) || host.substringAfterLast('.').length >= 2
    }

    /** Stable id for [url], so resume position is remembered when the same link is opened again. */
    fun videoIdFor(url: String): String = "net_" + com.beatraxus.app.livetv.shortHash(url)

    /** A readable title: the file name without extension, else the host. */
    fun titleFor(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val last = uri?.rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        val name = decoded.substringBeforeLast('.', decoded).replace('_', ' ').replace('.', ' ').trim()
        val generic = name.isEmpty() || name.lowercase(Locale.ROOT) in setOf("index", "playlist", "master", "manifest", "stream", "video", "live", "chunklist")
        return if (generic) (uri?.host ?: "Network stream") else name
    }

    /** Container hint from the address. Null lets the player sniff it (works for plain files). */
    fun mimeTypeFor(url: String): String? {
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> null // the player recognises these by extension
            lower.contains("m3u8") -> "application/x-mpegURL"
            lower.contains(".mpd") -> "application/dash+xml"
            else -> null
        }
    }

    private fun fileMimeFor(url: String): String =
        when (url.lowercase(Locale.ROOT).substringBefore('?').substringBefore('#').substringAfterLast('.', "")) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            else -> "video/*"
        }

    /** The pasted link as a [Video] the existing player can queue. Not saved to the library. */
    fun toVideo(url: String): Video = Video(
        id = videoIdFor(url),
        uri = Uri.parse(url),
        title = titleFor(url),
        displayName = titleFor(url),
        folderPath = "network",
        durationMs = 0L,
        sizeBytes = 0L,
        resolutionWidth = 0,
        resolutionHeight = 0,
        mimeType = fileMimeFor(url),
        dateAdded = System.currentTimeMillis(),
        source = VideoSource.NETWORK,
        sourceId = Uri.parse(url).host
    )
}
