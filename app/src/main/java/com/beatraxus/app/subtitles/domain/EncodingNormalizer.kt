package com.beatraxus.app.subtitles.domain

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object EncodingNormalizer {

    fun normalizeToUtf8(bytes: ByteArray, languageCode: String? = null): String {
        if (bytes.isEmpty()) return ""

        // Check BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            val str = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            return sanitizeText(str)
        }

        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                val str = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                return sanitizeText(str)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                val str = String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                return sanitizeText(str)
            }
        }

        // Try strict UTF-8
        try {
            val utf8Decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = utf8Decoder.decode(ByteBuffer.wrap(bytes))
            return sanitizeText(charBuffer.toString())
        } catch (_: Exception) {
            // Strict UTF-8 failed, fallback to legacy charset
        }

        val legacyCharset = getFallbackCharset(languageCode)
        val decoded = String(bytes, legacyCharset)
        return sanitizeText(decoded)
    }

    private fun getFallbackCharset(languageCode: String?): Charset {
        val lang = languageCode?.lowercase() ?: ""
        val charsetName = when {
            lang == "ar" -> "windows-1256" // Arabic
            lang in listOf("ru", "bg", "uk", "be", "sr", "mk") -> "windows-1251" // Cyrillic
            lang in listOf("cs", "pl", "hu", "ro", "sk", "sl", "hr") -> "windows-1250" // Central European
            lang == "el" -> "windows-1253" // Greek
            lang == "he" -> "windows-1255" // Hebrew
            lang == "tr" -> "windows-1254" // Turkish
            else -> "windows-1252" // Latin1 / Western European default
        }
        return try {
            Charset.forName(charsetName)
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
    }

    private fun sanitizeText(raw: String): String {
        return raw.replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }
}
