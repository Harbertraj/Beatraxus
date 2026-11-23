package com.beatraxus.app.engine

import kotlin.math.max
import kotlin.math.min

/**
 * One video decoder as the first-run probe ([VideoCapabilityProbe]) saw it.
 *
 * Frame rates are what the decoder reports for 1080p and 2160p frames; 0 means that size is not supported at all.
 */
data class ProbedDecoder(
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps1080: Int,
    val maxFps2160: Int,
    /** Highest bitrate (bits per second) the decoder reports; 0 when it does not say. */
    val maxBitrate: Int,
    /** True when the decoder lists a 10-bit profile (HEVC Main10, VP9 Profile 2, AV1 Main10, AVC High 10). */
    val tenBit: Boolean
)

/** What this phone can decode and show. Built once by [VideoCapabilityProbe] and kept until a system update. */
data class DeviceVideoCaps(
    /** Decoders by (lower case) video mime type, e.g. "video/hevc". */
    val decoders: Map<String, List<ProbedDecoder>>,
    /** HDR formats of the screen: "Dolby Vision", "HDR10", "HLG", "HDR10+". Empty = standard screen. */
    val displayHdr: Set<String>,
    val dolbyVisionDecoder: Boolean
)

/** What the player knows about one video stream. */
data class VideoFacts(
    val mimeType: String?,
    val width: Int,
    val height: Int,
    /** 0 or less when the file does not say. */
    val frameRate: Float,
    /** Bits per second, 0 or less when unknown. */
    val bitrate: Int,
    val tenBit: Boolean,
    val isHdr: Boolean,
    val dolbyVisionProfile: Int?
)

enum class VideoFit {
    /** A hardware decoder of this phone covers size, frame rate, bitrate and bit depth. */
    FULL_HARDWARE,

    /** The hardware decoder is the best this phone has, but the file asks for more than it reports. */
    HARDWARE_BEST_EFFORT,

    /** No hardware decoder for this codec; only software can play it, which cannot show HDR. */
    SOFTWARE_ONLY,

    /** Nothing on this phone can decode it. */
    UNSUPPORTED
}

data class VideoCompat(
    val fit: VideoFit,
    /** One line for the info sheet. */
    val summary: String,
    /** A line worth showing the viewer before they wonder why the picture looks off; null when all is well. */
    val note: String?
)

/** Pure maths on the probe result and the stream facts, so it can be unit tested without a device. */
object VideoCompatibility {
    const val HEVC = "video/hevc"
    const val AVC = "video/avc"
    const val VP9 = "video/x-vnd.on2.vp9"
    const val AV1 = "video/av01"
    const val DOLBY_VISION = "video/dolby-vision"

    /**
     * The mime type of the decoder that will really run: a Dolby Vision file is decoded by a Dolby Vision decoder when
     * the phone has one, otherwise by the decoder of its base layer (HEVC for profiles 4, 5, 7 and 8, AVC for 9, AV1 for 10).
     */
    fun decoderMime(mime: String?, dolbyVisionProfile: Int?, caps: DeviceVideoCaps): String? {
        val m = mime?.lowercase() ?: return null
        if (m != DOLBY_VISION) return m
        if (caps.dolbyVisionDecoder && caps.displayHdr.contains("Dolby Vision")) return DOLBY_VISION
        return when (dolbyVisionProfile) {
            9 -> AVC
            10 -> AV1
            else -> HEVC
        }
    }

    fun check(caps: DeviceVideoCaps, f: VideoFacts): VideoCompat {
        val mime = decoderMime(f.mimeType, f.dolbyVisionProfile, caps)
            ?: return VideoCompat(VideoFit.FULL_HARDWARE, "Unknown", null)

        val all = caps.decoders[mime].orEmpty()
        val hardware = all.filter { it.hardware }
        val label = codecLabel(mime)

        if (hardware.isEmpty()) {
            val hdrWarning = if (f.isHdr) ". Software decoding cannot show HDR" else ""
            return if (all.isNotEmpty()) {
                VideoCompat(
                    VideoFit.SOFTWARE_ONLY,
                    "Software only ($label)",
                    "This phone has no hardware $label decoder$hdrWarning"
                )
            } else {
                // The FFmpeg fallback may still play it; the player will find out.
                VideoCompat(
                    VideoFit.SOFTWARE_ONLY,
                    "No $label decoder in the system",
                    "This phone has no $label decoder, so it falls back to software$hdrWarning"
                )
            }
        }

        val best = hardware.minByOrNull { shortfalls(it, f).size }!!
        val missing = shortfalls(best, f)

        val extra = buildList {
            if (f.dolbyVisionProfile == 5 && mime != DOLBY_VISION) {
                add("Dolby Vision profile 5 has no HDR10 layer, so colours will look wrong on this phone")
            } else if (f.dolbyVisionProfile != null && mime != DOLBY_VISION) {
                add("Dolby Vision plays as HDR10 here")
            }
            if (f.isHdr && caps.displayHdr.isEmpty()) {
                add("The screen has no HDR, so Android converts it to SDR")
            }
        }

        return if (missing.isEmpty()) {
            VideoCompat(
                VideoFit.FULL_HARDWARE,
                "Full hardware playback",
                extra.takeIf { it.isNotEmpty() }?.joinToString(". ")
            )
        } else {
            val reasons = missing.joinToString(", ")
            VideoCompat(
                VideoFit.HARDWARE_BEST_EFFORT,
                "Beyond hardware limits: $reasons",
                "Beyond this phone's $label decoder ($reasons). It plays on the hardware decoder as far as it can, " +
                    "so frames may drop" + extra.joinToString("") { ". $it" }
            )
        }
    }

    /** What the file asks for that [d] does not report it can do. Empty = nothing missing. */
    internal fun shortfalls(d: ProbedDecoder, f: VideoFacts): List<String> {
        val out = mutableListOf<String>()
        val longSide = max(f.width, f.height)
        val shortSide = min(f.width, f.height)
        if (longSide > 0 && shortSide > 0 &&
            (longSide > max(d.maxWidth, d.maxHeight) || shortSide > min(d.maxWidth, d.maxHeight))
        ) {
            out += "${sizeLabel(f.width, f.height)} is larger than it supports"
        } else if (longSide > 0) {
            val limit = if (longSide > 2560) d.maxFps2160 else d.maxFps1080
            if (limit == 0) {
                out += "${sizeLabel(f.width, f.height)} is not listed"
            } else if (f.frameRate > 0f && f.frameRate > limit + 0.5f) {
                out += "${sizeLabel(f.width, f.height)} at ${fpsLabel(f.frameRate)} fps (limit about $limit fps)"
            }
        }
        if (f.bitrate > 0 && d.maxBitrate > 0 && f.bitrate > d.maxBitrate) {
            out += "bitrate ${f.bitrate / 1_000_000} Mbps (limit about ${d.maxBitrate / 1_000_000} Mbps)"
        }
        if (f.tenBit && !d.tenBit) out += "10-bit video"
        return out
    }

    private fun sizeLabel(w: Int, h: Int): String {
        val longSide = max(w, h)
        return when {
            longSide >= 7680 -> "8K"
            longSide >= 3840 -> "4K"
            longSide >= 2560 -> "1440p"
            longSide >= 1920 -> "1080p"
            else -> "${min(w, h)}p"
        }
    }

    private fun fpsLabel(fps: Float): String =
        if (fps % 1f < 0.05f) fps.toInt().toString() else String.format(java.util.Locale.US, "%.2f", fps)

    private fun codecLabel(mime: String): String = when (mime) {
        HEVC -> "HEVC"
        AVC -> "H.264"
        VP9 -> "VP9"
        AV1 -> "AV1"
        DOLBY_VISION -> "Dolby Vision"
        else -> mime.removePrefix("video/")
    }
}
