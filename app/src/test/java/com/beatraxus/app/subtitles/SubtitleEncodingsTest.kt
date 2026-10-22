package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.EncodingNormalizer
import com.beatraxus.app.subtitles.domain.SubtitleEncodings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleEncodingsTest {

    @Test
    fun autoDetectComesFirstAndHasNoCharset() {
        val options = SubtitleEncodings.options()
        assertEquals(SubtitleEncodings.AUTO, options.first().id)
        assertNull(SubtitleEncodings.charsetFor(SubtitleEncodings.AUTO))
        assertNull(SubtitleEncodings.charsetFor(null))
    }

    @Test
    fun everyOfferedChoiceResolvesToACharset() {
        SubtitleEncodings.options().filter { it.id != SubtitleEncodings.AUTO }.forEach {
            assertNotNull("${it.id} should resolve", SubtitleEncodings.charsetFor(it.id))
        }
    }

    @Test
    fun idsAreUniqueAndLabelled() {
        val ids = SubtitleEncodings.options().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SubtitleEncodings.options().all { it.label.isNotBlank() })
    }

    @Test
    fun unknownIdsFallBackToAutoDetect() {
        assertNull(SubtitleEncodings.charsetFor("not-a-charset"))
        assertEquals("Auto-detect", SubtitleEncodings.labelFor("not-a-charset"))
        assertEquals("Cyrillic (Windows-1251)", SubtitleEncodings.labelFor("windows-1251"))
    }

    @Test
    fun forcedCharsetDecodesLegacyText() {
        val cyrillic = "1\n00:00:01,000 --> 00:00:02,000\nПривет, мир".toByteArray(Charset.forName("windows-1251"))
        val text = EncodingNormalizer.normalizeToUtf8(cyrillic, "en", Charset.forName("windows-1251"))
        assertTrue(text.contains("Привет, мир"))

        val chinese = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界".toByteArray(Charset.forName("GBK"))
        assertTrue(EncodingNormalizer.normalizeToUtf8(chinese, null, Charset.forName("GBK")).contains("你好，世界"))

        val japanese = "1\n00:00:01,000 --> 00:00:02,000\nこんにちは".toByteArray(Charset.forName("Shift_JIS"))
        assertTrue(EncodingNormalizer.normalizeToUtf8(japanese, null, Charset.forName("Shift_JIS")).contains("こんにちは"))
    }

    @Test
    fun forcedCharsetIsUsedEvenWhenAutoDetectWouldGuessDifferently() {
        // Valid UTF-8, but the viewer says it is Windows-1252: they get what they asked for.
        val utf8 = "café".toByteArray(Charsets.UTF_8)
        assertEquals("café", EncodingNormalizer.normalizeToUtf8(utf8))
        assertEquals("cafÃ©", EncodingNormalizer.normalizeToUtf8(utf8, null, Charset.forName("windows-1252")))
    }

    @Test
    fun forcedDecodingDropsTheByteOrderMark() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hello".toByteArray(Charsets.UTF_8)
        assertEquals("Hello", EncodingNormalizer.normalizeToUtf8(utf8Bom, null, Charsets.UTF_8))

        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hello".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hello", EncodingNormalizer.normalizeToUtf8(utf16le, null, Charsets.UTF_16LE))

        val utf16 = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Hello".toByteArray(Charsets.UTF_16BE)
        assertEquals("Hello", EncodingNormalizer.normalizeToUtf8(utf16, null, Charset.forName("UTF-16")))
    }

    @Test
    fun forcedDecodingNormalisesLineEndingsAndNulls() {
        val bytes = "a\r\nb\rc\u0000d".toByteArray(Charsets.UTF_8)
        assertEquals("a\nb\ncd", EncodingNormalizer.normalizeToUtf8(bytes, null, Charsets.UTF_8))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", EncodingNormalizer.normalizeToUtf8(ByteArray(0), null, Charsets.UTF_8))
    }
}
