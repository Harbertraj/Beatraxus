package com.beatraxus.app.cast

import java.util.Locale

/**
 * Pure helpers for casting a video to a Chromecast (feature 26): how a video reaches the TV, which MIME type to
 * announce, and how to answer the TV's HTTP Range requests when the phone serves the file itself.
 */
object VideoCastSupport {

    /** How the TV gets the bytes. */
    enum class Route {
        /** content:// or file:// on this phone: served to the TV by [LocalCastServer]. */
        LOCAL_SERVER,

        /** A plain http(s) address (network stream, IPTV channel): the TV opens it itself. */
        DIRECT,

        /** tgvideo:// (Telegram) and cloudvideo:// (cloud drives) need this app's credentials: not castable. */
        UNSUPPORTED
    }

    fun routeFor(scheme: String?): Route = when (scheme?.lowercase(Locale.ROOT)) {
        "content", "file" -> Route.LOCAL_SERVER
        "http", "https" -> Route.DIRECT
        else -> Route.UNSUPPORTED
    }

    const val MIME_HLS = "application/x-mpegURL"
    const val MIME_DASH = "application/dash+xml"
    const val MIME_DEFAULT = "video/mp4"

    /**
     * Content type announced to the receiver.
     *
     * @param mimeType   what the library / MediaStore reported, may be blank.
     * @param nameOrPath file name or address, used for the extension (and to spot playlists in a query string).
     */
    fun contentType(mimeType: String?, nameOrPath: String?): String {
        val text = nameOrPath.orEmpty().lowercase(Locale.ROOT)
        val ext = text.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        if (ext == "m3u8" || text.contains("m3u8")) return MIME_HLS
        if (ext == "mpd") return MIME_DASH

        val declared = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (declared.startsWith("video/")) {
            // Receivers know MP4, not Apple's container name.
            return if (declared == "video/quicktime") MIME_DEFAULT else declared
        }
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            else -> MIME_DEFAULT // mp4, m4v, mov and anything unknown
        }
    }

    /** Result of reading a `Range` request header against a file of known size. */
    sealed class RangeResult {
        /** No (usable) range: send the whole file with 200. */
        object Full : RangeResult()

        /** Send bytes [start]..[end] (both inclusive) with 206. */
        data class Partial(val start: Long, val end: Long) : RangeResult() {
            val length: Long get() = end - start + 1
        }

        /** Nothing in the file matches: answer 416. */
        object NotSatisfiable : RangeResult()
    }

    /**
     * @param header the header value, with or without the "bytes=" prefix ("bytes=0-", "100-199", "-500").
     * @param total  file size in bytes; <= 0 when unknown (then the whole file is sent).
     */
    fun parseRange(header: String?, total: Long): RangeResult {
        val spec = header?.trim()?.removePrefix("bytes=")?.substringBefore(',')?.trim().orEmpty()
        if (spec.isEmpty() || total <= 0) return RangeResult.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Full

        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()

        if (first.isEmpty()) { // "-N": the last N bytes
            val n = last.toLongOrNull() ?: return RangeResult.Full
            if (n <= 0) return RangeResult.NotSatisfiable
            return RangeResult.Partial((total - n).coerceAtLeast(0L), total - 1)
        }

        val start = first.toLongOrNull() ?: return RangeResult.Full
        if (start >= total) return RangeResult.NotSatisfiable
        val end = last.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (end < start) return RangeResult.NotSatisfiable
        return RangeResult.Partial(start, end)
    }
}

/** What the video player wants loaded on the TV once a Cast session is ready. */
data class VideoCastRequest(val video: com.beatraxus.app.model.Video, val startPositionMs: Long)

/** The video player's side of a Cast session. Registered with [CastManager.videoHost] while the player is open. */
interface VideoCastHost {
    /**
     * A Cast session is ready. Return what to load on the TV (and stop local playback), or null when the current
     * video can't be cast, in which case the session is closed again.
     */
    fun onCastSessionReady(deviceName: String?): VideoCastRequest?

    /** The session ended (stopped by the user or lost). Carry on locally from [positionMs] (<= 0 when unknown). */
    fun onCastSessionEnded(positionMs: Long)
}
