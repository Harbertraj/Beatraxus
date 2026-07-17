package com.beatraxus.app.drive

object CloudScanConstants {
    val AUDIO_EXTENSIONS = listOf(
        // Lossless
        ".flac", ".wav", ".alac", ".aiff", ".aif", ".ape", ".wv", ".tta",
        ".dsf", ".dff", ".dsd",
        // Dolby / DTS
        ".ac3", ".eac3", ".ec3", ".dts",
        // Lossy
        ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".mp4",
        // Container / misc
        ".mka", ".webm", ".caf", ".ra"
    )

    val SUPPORTED_EXTENSIONS = AUDIO_EXTENSIONS.toHashSet()

    fun isSupportedAudioFile(filename: String, mimeType: String? = null, allowedFormats: Set<String>? = null): Boolean {
        val ext = ".${filename.substringAfterLast('.', "").lowercase()}"
        val isSupported = ext in SUPPORTED_EXTENSIONS || (mimeType?.startsWith("audio/") == true)
        if (!isSupported) return false
        
        if (allowedFormats == null || allowedFormats.isEmpty()) return true
        
        val formatFromExt = ext.removePrefix(".").uppercase()
        return allowedFormats.any { it.equals(formatFromExt, ignoreCase = true) }
    }
}
