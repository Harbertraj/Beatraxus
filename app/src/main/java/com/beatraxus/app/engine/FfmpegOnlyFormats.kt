package com.beatraxus.app.engine

import java.util.Locale

/**
 * Formats that Android's MediaCodec/MediaExtractor cannot decode (or decodes unreliably)
 * and that therefore always go through the FFmpeg decoder.
 *
 * Kept in one place so [DecoderFactory], [FfmpegAlacDecoder] and the library scanners agree.
 */
internal object FfmpegOnlyFormats {

    /** File extensions (lowercase, no dot) that must be decoded by FFmpeg. */
    val EXTENSIONS: Set<String> = setOf(
        "ape",   // Monkey's Audio
        "wv",    // WavPack
        "tta",   // True Audio
        "mpc",   // Musepack
        "mka",   // Matroska audio
        "dsf",   // DSD (DSF container)
        "dff",   // DSD (DSDIFF container)
        "dsd"
    )

    /** Display names used in Song.format for the formats above. */
    val FORMAT_NAMES: Set<String> = setOf("APE", "WV", "TTA", "MPC", "MKA", "DSD", "DSF", "DFF")

    /**
     * Demuxer names for `-f`, used when FFmpeg reads from a pipe or an extension-less cache
     * file and cannot sniff the container from the file name. Matroska is left to probing.
     */
    private val DEMUXER_HINTS: Map<String, String> = mapOf(
        "ape" to "ape",
        "wv" to "wv",
        "tta" to "tta",
        "mpc" to "mpc"
    )

    fun isFfmpegOnly(extension: String, format: String): Boolean {
        val ext = extension.lowercase(Locale.US)
        val fmt = format.uppercase(Locale.US)
        return ext in EXTENSIONS || fmt in FORMAT_NAMES
    }

    fun demuxerHint(extension: String, format: String): String? {
        val ext = extension.lowercase(Locale.US)
        DEMUXER_HINTS[ext]?.let { return it }
        return when (format.uppercase(Locale.US)) {
            "APE" -> "ape"
            "WV" -> "wv"
            "TTA" -> "tta"
            "MPC" -> "mpc"
            else -> null
        }
    }
}
