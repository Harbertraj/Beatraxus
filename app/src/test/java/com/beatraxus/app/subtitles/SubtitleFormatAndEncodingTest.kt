package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.data.ZipSubtitleExtractor
import com.beatraxus.app.subtitles.domain.EncodingNormalizer
import com.beatraxus.app.subtitles.domain.SubtitleConverter
import com.beatraxus.app.subtitles.domain.SubtitleFormat
import com.beatraxus.app.subtitles.domain.SubtitleFormatDetector
import com.beatraxus.app.subtitles.domain.SubtitleValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleFormatAndEncodingTest {

    @Test
    fun testFormatDetection() {
        val srtContent = "1\n00:00:01,000 --> 00:00:04,000\nHello World\n"
        assertEquals(SubtitleFormat.SRT, SubtitleFormatDetector.detect(srtContent))

        val vttContent = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello World\n"
        assertEquals(SubtitleFormat.WEBVTT, SubtitleFormatDetector.detect(vttContent))

        val assContent = "[Script Info]\nTitle: Test\nDialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello ASS\n"
        assertEquals(SubtitleFormat.ASS_SSA, SubtitleFormatDetector.detect(assContent))

        val subContent = "{100}{200}Hello SUB\n"
        assertEquals(SubtitleFormat.SUB, SubtitleFormatDetector.detect(subContent))
    }

    @Test
    fun testWebVttAndAssConversionToSrt() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello WEBVTT\n"
        val srtFromVtt = SubtitleConverter.convertToSrt(vtt, SubtitleFormat.WEBVTT)
        assertNotNull(srtFromVtt)
        assertTrue(srtFromVtt!!.contains("00:00:01,000 --> 00:00:04,000"))
        assertTrue(srtFromVtt.contains("Hello WEBVTT"))

        val ass = "[Script Info]\nDialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\\pos(10,10)}Hello ASS"
        val srtFromAss = SubtitleConverter.convertToSrt(ass, SubtitleFormat.ASS_SSA)
        assertNotNull(srtFromAss)
        assertTrue(srtFromAss!!.contains("00:00:01,000 --> 00:00:04,000"))
        assertTrue(srtFromAss.contains("Hello ASS"))
    }

    @Test
    fun testUtf8BomAndUtf16EncodingNormalizer() {
        // UTF-8 BOM
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "1\n00:00:01,000 --> 00:00:02,000\nTest".toByteArray(Charsets.UTF_8)
        val normalizedUtf8 = EncodingNormalizer.normalizeToUtf8(utf8BomBytes)
        assertTrue(normalizedUtf8.startsWith("1"))

        // UTF-16LE
        val utf16LeBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "1\n00:00:01,000 --> 00:00:02,000\nTest".toByteArray(Charsets.UTF_16LE)
        val normalizedUtf16 = EncodingNormalizer.normalizeToUtf8(utf16LeBytes)
        assertTrue(normalizedUtf16.contains("00:00:01,000"))

        // Arabic Legacy Windows-1256
        val arabicText = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا"
        val arabicBytes = arabicText.toByteArray(Charset.forName("windows-1256"))
        val normalizedArabic = EncodingNormalizer.normalizeToUtf8(arabicBytes, "ar")
        assertTrue(normalizedArabic.contains("مرحبا"))
    }

    @Test
    fun testHtmlErrorPageRejection() {
        val htmlPage = "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body>Error</body></html>"
        val result = SubtitleValidator.validate(htmlPage)
        assertTrue(result.isFailure)
    }

    @Test
    fun testZipSlipRejection() {
        val tempDir = File.createTempFile("zip_target", "").apply { delete(); mkdirs() }
        tempDir.deleteOnExit()

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                val entry = ZipEntry("../../../evil.srt")
                zos.putNextEntry(entry)
                zos.write("1\n00:00:01,000 --> 00:00:02,000\nEvil".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val res = ZipSubtitleExtractor.extractSubtitleFromZip(zipBytes, tempDir)
        assertTrue(res.isFailure)
    }
}
