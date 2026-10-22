package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.FontFileSniffer
import com.beatraxus.app.subtitles.domain.FontKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontFileSnifferTest {

    private fun header(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() } + ByteArray(32)

    @Test
    fun recognisesTrueTypeOpenTypeAndCollections() {
        assertEquals(FontKind.TRUETYPE, FontFileSniffer.detect(header(0x00, 0x01, 0x00, 0x00)))
        assertEquals(FontKind.TRUETYPE, FontFileSniffer.detect("true".toByteArray() + ByteArray(8)))
        assertEquals(FontKind.OPENTYPE, FontFileSniffer.detect("OTTO".toByteArray() + ByteArray(8)))
        assertEquals(FontKind.COLLECTION, FontFileSniffer.detect("ttcf".toByteArray() + ByteArray(8)))
    }

    @Test
    fun rejectsOtherFiles() {
        assertNull(FontFileSniffer.detect(ByteArray(0)))
        assertNull(FontFileSniffer.detect(byteArrayOf(0, 1, 0)))
        assertNull(FontFileSniffer.detect("1\n00:00:01,000 --> 00:00:02,000".toByteArray()))
        assertNull(FontFileSniffer.detect(header(0x89, 0x50, 0x4E, 0x47)))          // PNG
        assertNull(FontFileSniffer.detect(header(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))) // MP4
        assertNull(FontFileSniffer.detect("wOFF".toByteArray() + ByteArray(8)))      // WOFF is not supported by Typeface
    }

    @Test
    fun storedNamesAreUniqueAndRecognised() {
        val a = FontFileSniffer.storedFileName(FontKind.OPENTYPE, 1L)
        val b = FontFileSniffer.storedFileName(FontKind.OPENTYPE, 2L)
        assertEquals("subtitle_font_1.otf", a)
        assertFalse(a == b)
        assertTrue(FontFileSniffer.isStoredFontName(a))
        assertTrue(FontFileSniffer.isStoredFontName(FontFileSniffer.storedFileName(FontKind.TRUETYPE, 9L)))
        assertFalse(FontFileSniffer.isStoredFontName("index.json"))
        assertFalse(FontFileSniffer.isStoredFontName("other_font.ttf"))
    }

    @Test
    fun displayLabelDropsFolderAndExtension() {
        assertEquals("NotoSansTamil-Regular", FontFileSniffer.displayLabel("NotoSansTamil-Regular.ttf"))
        assertEquals("My Font", FontFileSniffer.displayLabel("fonts/My Font.OTF"))
        assertEquals("Archive.v2", FontFileSniffer.displayLabel("Archive.v2.ttf"))
        assertEquals("Custom font", FontFileSniffer.displayLabel(null))
        assertEquals("Custom font", FontFileSniffer.displayLabel("  "))
        assertEquals("Custom font", FontFileSniffer.displayLabel(".ttf"))
    }
}
