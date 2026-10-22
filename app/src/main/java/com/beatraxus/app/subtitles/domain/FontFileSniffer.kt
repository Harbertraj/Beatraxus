package com.beatraxus.app.subtitles.domain

/** The kinds of font file accepted for subtitles (video feature 23). */
enum class FontKind(val extension: String) {
    TRUETYPE("ttf"),
    OPENTYPE("otf"),
    COLLECTION("ttc")
}

/** Why a picked file was not accepted as a subtitle font. */
enum class SubtitleFontError { UNREADABLE, NOT_A_FONT, TOO_LARGE, UNUSABLE }

/**
 * Checks that a picked file really is a font before it is copied into the app.
 *
 * The file's name and MIME type are not trusted (the system file picker reports fonts as anything from
 * `font/ttf` to `application/octet-stream`); the first four bytes say what it is.
 */
object FontFileSniffer {

    /** Subtitle fonts are small; this stops a wrong pick (a video, say) from being copied in full. */
    const val MAX_FONT_BYTES: Long = 25L * 1024 * 1024

    /** The font kind named by the file's first bytes, or null when it is not a TrueType/OpenType font. */
    fun detect(header: ByteArray): FontKind? {
        if (header.size < 4) return null
        val tag = String(header, 0, 4, Charsets.ISO_8859_1)
        return when {
            header[0] == 0.toByte() && header[1] == 1.toByte() && header[2] == 0.toByte() && header[3] == 0.toByte() ->
                FontKind.TRUETYPE
            tag == "true" -> FontKind.TRUETYPE
            tag == "OTTO" -> FontKind.OPENTYPE
            tag == "ttcf" -> FontKind.COLLECTION
            else -> null
        }
    }

    /**
     * File name to store the font under. A fresh [stamp] each time means a font that is still in use is never
     * overwritten in place when the viewer picks another one.
     */
    fun storedFileName(kind: FontKind, stamp: Long): String = "subtitle_font_$stamp.${kind.extension}"

    /** True for names produced by [storedFileName], used to clear out the previous font. */
    fun isStoredFontName(fileName: String): Boolean =
        fileName.startsWith("subtitle_font_") && FontKind.entries.any { fileName.endsWith(".${it.extension}") }

    /** Name for the settings row: the picked file's name without its extension. */
    fun displayLabel(fileName: String?): String {
        val base = fileName?.substringAfterLast('/')?.trim().orEmpty()
        val withoutExtension = base.substringBeforeLast('.', base).trim()
        return withoutExtension.ifBlank { "Custom font" }
    }
}
