package com.beatraxus.app.subtitles.domain

import java.nio.charset.Charset

/** One choice in the text encoding picker (video feature 23). [id] is the Java charset name, or [SubtitleEncodings.AUTO]. */
data class SubtitleEncodingOption(val id: String, val label: String)

/**
 * The text encodings offered when a subtitle file shows wrong characters (usually "?" or odd symbols).
 *
 * "Auto-detect" keeps the app's normal behaviour. Any other choice is used for subtitle files the viewer loads
 * themselves (Select file, and the file next to the video). Subtitles downloaded from OpenSubtitles keep their own
 * language-based detection, because a single forced encoding would break every UTF-8 download.
 */
object SubtitleEncodings {

    const val AUTO = "AUTO"

    private val all: List<SubtitleEncodingOption> = listOf(
        SubtitleEncodingOption(AUTO, "Auto-detect"),
        SubtitleEncodingOption("UTF-8", "Unicode (UTF-8)"),
        SubtitleEncodingOption("UTF-16", "Unicode (UTF-16)"),
        SubtitleEncodingOption("UTF-16LE", "Unicode (UTF-16 little-endian)"),
        SubtitleEncodingOption("UTF-16BE", "Unicode (UTF-16 big-endian)"),
        SubtitleEncodingOption("windows-1252", "Western European (Windows-1252)"),
        SubtitleEncodingOption("ISO-8859-1", "Western European (ISO-8859-1)"),
        SubtitleEncodingOption("ISO-8859-15", "Western European (ISO-8859-15)"),
        SubtitleEncodingOption("windows-1250", "Central European (Windows-1250)"),
        SubtitleEncodingOption("ISO-8859-2", "Central European (ISO-8859-2)"),
        SubtitleEncodingOption("windows-1251", "Cyrillic (Windows-1251)"),
        SubtitleEncodingOption("ISO-8859-5", "Cyrillic (ISO-8859-5)"),
        SubtitleEncodingOption("KOI8-R", "Cyrillic (KOI8-R)"),
        SubtitleEncodingOption("windows-1253", "Greek (Windows-1253)"),
        SubtitleEncodingOption("ISO-8859-7", "Greek (ISO-8859-7)"),
        SubtitleEncodingOption("windows-1254", "Turkish (Windows-1254)"),
        SubtitleEncodingOption("ISO-8859-9", "Turkish (ISO-8859-9)"),
        SubtitleEncodingOption("windows-1255", "Hebrew (Windows-1255)"),
        SubtitleEncodingOption("windows-1256", "Arabic (Windows-1256)"),
        SubtitleEncodingOption("ISO-8859-6", "Arabic (ISO-8859-6)"),
        SubtitleEncodingOption("windows-1257", "Baltic (Windows-1257)"),
        SubtitleEncodingOption("windows-1258", "Vietnamese (Windows-1258)"),
        SubtitleEncodingOption("TIS-620", "Thai (TIS-620)"),
        SubtitleEncodingOption("GBK", "Chinese Simplified (GBK)"),
        SubtitleEncodingOption("GB18030", "Chinese Simplified (GB18030)"),
        SubtitleEncodingOption("Big5", "Chinese Traditional (Big5)"),
        SubtitleEncodingOption("Shift_JIS", "Japanese (Shift_JIS)"),
        SubtitleEncodingOption("EUC-JP", "Japanese (EUC-JP)"),
        SubtitleEncodingOption("EUC-KR", "Korean (EUC-KR)")
    )

    /** The choices to show: every entry whose charset this device can actually decode. Auto-detect is always first. */
    fun options(): List<SubtitleEncodingOption> = all.filter { it.id == AUTO || isSupported(it.id) }

    /** The charset to force for [id], or null for Auto-detect, an unknown id or a charset this device lacks. */
    fun charsetFor(id: String?): Charset? {
        if (id == null || id == AUTO) return null
        return try {
            Charset.forName(id)
        } catch (_: Exception) {
            null
        }
    }

    /** Name shown for [id] in settings; an unrecognised id falls back to Auto-detect. */
    fun labelFor(id: String?): String = all.firstOrNull { it.id == id }?.label ?: all.first().label

    private fun isSupported(id: String): Boolean = try {
        Charset.isSupported(id)
    } catch (_: Exception) {
        false
    }
}
