package com.beatraxus.app.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Video feature 20: video info panel (codec, bitrate, frame rate and more).
 *
 * Everything here is plain Kotlin, so the wording and the number formatting can be unit tested.
 * The view model fills [VideoInfoData] from the player; [VideoInfoReport] turns it into the rows the sheet shows.
 */
data class VideoStreamInfo(
    /** e.g. "video/avc". */
    val mimeType: String?,
    /** RFC 6381 codec string, e.g. "avc1.640028" or "hvc1.2.4.L153.B0". */
    val codecs: String?,
    val width: Int,
    val height: Int,
    /** Frames per second, 0 when the file does not say. */
    val frameRate: Float,
    /** Average bits per second, <= 0 when unknown. */
    val bitrate: Int,
    val peakBitrate: Int = -1,
    /** Media3 C.COLOR_TRANSFER_* value, -1 when unknown. */
    val colorTransfer: Int = -1,
    /** Media3 C.COLOR_SPACE_* value, -1 when unknown. */
    val colorSpace: Int = -1
)

data class AudioStreamInfo(
    val mimeType: String?,
    val codecs: String?,
    val channelCount: Int,
    val sampleRate: Int,
    val bitrate: Int,
    val language: String?,
    val label: String?
)

data class VideoInfoData(
    val fileName: String,
    val location: String?,
    /** Container mime type of the file ("video/x-matroska"), when known. */
    val containerMime: String?,
    val sizeBytes: Long,
    val durationMs: Long,
    val isStreamed: Boolean,
    val video: VideoStreamInfo?,
    val audio: AudioStreamInfo?,
    /** Name of the decoder the device picked, e.g. "c2.qti.avc.decoder". */
    val videoDecoder: String?,
    val audioDecoder: String?,
    val droppedFrames: Int = 0,
    val renderedFrames: Int = 0
)

data class InfoRow(val label: String, val value: String)
data class InfoSection(val title: String, val rows: List<InfoRow>)

object VideoInfoReport {
    const val UNKNOWN = "Unknown"

    // Media3 constants, written out so this file needs no Android / Media3 classes.
    private const val TRANSFER_SDR = 3
    private const val TRANSFER_ST2084 = 6
    private const val TRANSFER_HLG = 7
    private const val SPACE_BT709 = 1
    private const val SPACE_BT601 = 2
    private const val SPACE_BT2020 = 6

    fun sections(d: VideoInfoData): List<InfoSection> {
        val out = mutableListOf<InfoSection>()

        val overall = overallBitrate(d.sizeBytes, d.durationMs)
        out += InfoSection("General", buildList {
            add(InfoRow("Name", d.fileName.ifBlank { UNKNOWN }))
            d.location?.takeIf { it.isNotBlank() }?.let { add(InfoRow(if (d.isStreamed) "Source" else "Folder", it)) }
            add(InfoRow("Container", containerName(d.containerMime, d.fileName) ?: UNKNOWN))
            if (d.sizeBytes > 0) add(InfoRow("Size", formatSize(d.sizeBytes)))
            if (d.durationMs > 0) add(InfoRow("Duration", formatDuration(d.durationMs)))
            if (overall > 0) add(InfoRow("Overall bitrate", "~" + formatBitrate(overall)))
        })

        val v = d.video
        out += InfoSection("Video", if (v == null) {
            listOf(InfoRow("Stream", "No video stream"))
        } else buildList {
            add(InfoRow("Codec", videoCodecName(v.mimeType) ?: UNKNOWN))
            codecProfile(v.mimeType, v.codecs)?.let { add(InfoRow("Profile", it)) }
            if (v.width > 0 && v.height > 0) add(InfoRow("Resolution", resolutionLabel(v.width, v.height)))
            add(InfoRow("Frame rate", if (v.frameRate > 0f) formatFps(v.frameRate) else UNKNOWN))
            val audioBps = d.audio?.bitrate ?: -1
            add(InfoRow("Bitrate", videoBitrateText(v, overall, audioBps)))
            add(InfoRow("Dynamic range", dynamicRange(v.mimeType, v.colorTransfer, v.colorSpace)))
            add(InfoRow("Decoder", d.videoDecoder?.let { "$it (${decoderKind(it)})" } ?: UNKNOWN))
            if (d.renderedFrames > 0 || d.droppedFrames > 0) {
                add(InfoRow("Dropped frames", "${d.droppedFrames} of ${d.renderedFrames + d.droppedFrames}"))
            }
        })

        val a = d.audio
        out += InfoSection("Audio", if (a == null) {
            listOf(InfoRow("Stream", "No audio stream"))
        } else buildList {
            add(InfoRow("Codec", audioCodecName(a.mimeType, a.codecs) ?: UNKNOWN))
            if (a.channelCount > 0) add(InfoRow("Channels", channelLabel(a.channelCount)))
            if (a.sampleRate > 0) add(InfoRow("Sample rate", formatSampleRate(a.sampleRate)))
            add(InfoRow("Bitrate", if (a.bitrate > 0) formatBitrate(a.bitrate.toLong()) else UNKNOWN))
            val lang = listOfNotNull(a.label?.takeIf { it.isNotBlank() }, a.language?.takeIf { it.isNotBlank() && it != "und" }).joinToString(", ")
            if (lang.isNotEmpty()) add(InfoRow("Track", lang))
            d.audioDecoder?.let { add(InfoRow("Decoder", "$it (${decoderKind(it)})")) }
        })
        return out
    }

    /** All rows as plain text, for the copy button. */
    fun toPlainText(sections: List<InfoSection>): String = sections.joinToString("\n\n") { s ->
        s.title + "\n" + s.rows.joinToString("\n") { "${it.label}: ${it.value}" }
    }

    // ---- codec names ---------------------------------------------------------------------------------------------

    fun videoCodecName(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4 Part 2"
        "video/3gpp" -> "H.263"
        "video/dolby-vision" -> "Dolby Vision"
        "video/wvc1", "video/x-ms-wmv" -> "VC-1"
        "video/mjpeg" -> "Motion JPEG"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }

    fun audioCodecName(mime: String?, codecs: String? = null): String? = when (mime?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "audio/mp4a-latm" -> when {
            codecs?.startsWith("mp4a.40.5") == true -> "AAC (HE-AAC)"
            codecs?.startsWith("mp4a.40.29") == true -> "AAC (HE-AAC v2)"
            else -> "AAC"
        }
        "audio/ac3" -> "Dolby Digital (AC-3)"
        "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"
        "audio/eac3-joc" -> "Dolby Digital Plus with Atmos"
        "audio/ac4" -> "Dolby AC-4"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/vnd.dts.hd;profile=lbr" -> "DTS Express"
        "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2" -> "MP2"
        "audio/alac" -> "ALAC"
        "audio/raw" -> "PCM"
        "audio/amr-wb" -> "AMR-WB"
        "audio/3gpp" -> "AMR-NB"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }

    /** "High, level 4.0" from an "avc1.640028" or "hvc1.2.4.L153.B0" codec string; null when it cannot be read. */
    fun codecProfile(mime: String?, codecs: String?): String? {
        if (codecs.isNullOrBlank()) return null
        val c = codecs.trim()
        return when (mime?.lowercase(Locale.ROOT)) {
            "video/avc" -> avcProfile(c)
            "video/hevc" -> hevcProfile(c)
            else -> null
        }
    }

    private fun avcProfile(c: String): String? {
        val hex = c.substringAfter('.', "")
        if (!(c.startsWith("avc1") || c.startsWith("avc3")) || hex.length < 6) return null
        val profileIdc = hex.substring(0, 2).toIntOrNull(16) ?: return null
        val levelIdc = hex.substring(4, 6).toIntOrNull(16) ?: return null
        val name = when (profileIdc) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4"
            else -> return null
        }
        return "$name, level ${levelIdc / 10}.${levelIdc % 10}"
    }

    private fun hevcProfile(c: String): String? {
        if (!(c.startsWith("hvc1") || c.startsWith("hev1"))) return null
        val parts = c.split('.')
        val profileIdc = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val name = when (profileIdc) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still Picture"
            4 -> "Range Extensions"
            else -> return null
        }
        val level = parts.getOrNull(3)?.takeIf { it.startsWith("L") }?.drop(1)?.toIntOrNull()
        // HEVC level_idc is 30 x the level number (L120 = 4.0, L153 = 5.1)
        return if (level != null) "$name, level ${level / 30}.${(level % 30) / 3}" else name
    }

    fun containerName(containerMime: String?, fileName: String): String? {
        val byMime = when (containerMime?.lowercase(Locale.ROOT)) {
            "video/mp4", "audio/mp4", "application/mp4" -> "MP4"
            "video/x-matroska", "audio/x-matroska" -> "Matroska (MKV)"
            "video/webm", "audio/webm" -> "WebM"
            "video/quicktime" -> "QuickTime (MOV)"
            "video/mp2t", "video/mp2ts" -> "MPEG-TS"
            "video/mpeg" -> "MPEG-PS"
            "video/x-msvideo", "video/avi" -> "AVI"
            "video/3gpp" -> "3GP"
            "video/x-flv" -> "FLV"
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "HLS stream"
            "application/dash+xml" -> "DASH stream"
            else -> null
        }
        if (byMime != null) return byMime
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "" -> null
            "mkv" -> "Matroska (MKV)"
            "mp4", "m4v" -> "MP4"
            "mov" -> "QuickTime (MOV)"
            "ts", "m2ts", "mts" -> "MPEG-TS"
            "avi" -> "AVI"
            "wmv" -> "WMV"
            "flv" -> "FLV"
            "3gp" -> "3GP"
            "webm" -> "WebM"
            else -> if (ext.length <= 5) ext.uppercase(Locale.ROOT) else null
        }
    }

    // ---- numbers -------------------------------------------------------------------------------------------------

    /** 23.976 -> "23.976 fps", 25.0 -> "25 fps", 29.97 -> "29.97 fps". */
    fun formatFps(fps: Float): String {
        if (fps <= 0f) return UNKNOWN
        val nearest = fps.roundToInt()
        if (abs(fps - nearest) < 0.0005f) return "$nearest fps"
        val s = String.format(Locale.US, "%.3f", fps).trimEnd('0').trimEnd('.')
        return "$s fps"
    }

    fun formatBitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond <= 0 -> UNKNOWN
        bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
        else -> "${(bitsPerSecond / 1000.0).roundToInt()} kbps"
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return UNKNOWN
        val kb = 1024.0
        return when {
            bytes >= kb * kb * kb -> String.format(Locale.US, "%.2f GB", bytes / (kb * kb * kb))
            bytes >= kb * kb -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
            else -> String.format(Locale.US, "%.0f KB", bytes / kb)
        }
    }

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 44100 -> "44.1 kHz", 48000 -> "48 kHz". */
    fun formatSampleRate(hz: Int): String {
        val khz = hz / 1000.0
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", khz)
    }

    fun channelLabel(count: Int): String = when (count) {
        1 -> "1 (mono)"
        2 -> "2 (stereo)"
        3 -> "3 (2.1)"
        4 -> "4 (quad)"
        6 -> "6 (5.1)"
        8 -> "8 (7.1)"
        else -> "$count"
    }

    /** 1920x1080 -> "1920 x 1080 (1080p)"; 3840x2160 -> "3840 x 2160 (4K)". */
    fun resolutionLabel(width: Int, height: Int): String {
        val cls = when {
            width >= 7680 || height >= 4320 -> "8K"
            width >= 3840 || height >= 2160 -> "4K"
            width >= 2560 || height >= 1440 -> "1440p"
            width >= 1920 || height >= 1080 -> "1080p"
            width >= 1280 || height >= 720 -> "720p"
            width >= 854 || height >= 480 -> "480p"
            else -> "SD"
        }
        return "$width x $height ($cls)"
    }

    /** Average bitrate over the whole file in bits per second, 0 when size or duration is unknown. */
    fun overallBitrate(sizeBytes: Long, durationMs: Long): Long =
        if (sizeBytes > 0 && durationMs > 0) sizeBytes * 8L * 1000L / durationMs else 0L

    /**
     * The video bitrate: the file's own figure when it has one, otherwise an estimate (whole file minus the audio),
     * marked "~" so nobody takes it for an exact number.
     */
    fun videoBitrateText(v: VideoStreamInfo, overallBps: Long, audioBps: Int): String {
        if (v.bitrate > 0) return formatBitrate(v.bitrate.toLong())
        if (v.peakBitrate > 0) return formatBitrate(v.peakBitrate.toLong()) + " (peak)"
        if (overallBps > 0) {
            val est = overallBps - maxOf(audioBps, 0)
            if (est > 0) return "~" + formatBitrate(est) + " (estimated)"
        }
        return UNKNOWN
    }

    fun dynamicRange(mime: String?, transfer: Int, space: Int): String = when {
        mime.equals("video/dolby-vision", ignoreCase = true) -> "Dolby Vision"
        transfer == TRANSFER_ST2084 -> "HDR10 / PQ" + if (space == SPACE_BT2020) ", BT.2020" else ""
        transfer == TRANSFER_HLG -> "HLG" + if (space == SPACE_BT2020) ", BT.2020" else ""
        transfer == TRANSFER_SDR || transfer == -1 -> "SDR" + when (space) {
            SPACE_BT709 -> ", BT.709"
            SPACE_BT601 -> ", BT.601"
            else -> ""
        }
        else -> "SDR"
    }

    /** Software decoders ship with Android or an app (Google, FFmpeg, dav1d, libvpx); everything else is vendor hardware. */
    fun decoderKind(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        val software = n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") ||
            n.contains("ffmpeg") || n.contains("libav") || n.contains("dav1d") || n.contains("libgav1") ||
            n.contains("libvpx") || n.endsWith(".sw") || n.contains(".sw.")
        return if (software) "software" else "hardware"
    }
}
