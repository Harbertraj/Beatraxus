package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.data.SubtitleDelayShifter
import com.beatraxus.app.subtitles.domain.SubtitlePositionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Media3SubtitlePlayerControllerTest {

    @Test
    fun testSubtitlePositionPresets() {
        assertEquals(0f, SubtitlePositionPreset.BOTTOM.offsetDp, 0.001f)
        assertEquals(-150f, SubtitlePositionPreset.LOWER_MIDDLE.offsetDp, 0.001f)
        assertEquals(-350f, SubtitlePositionPreset.MIDDLE.offsetDp, 0.001f)
    }

    @Test
    fun testDelayShifterIntegration() {
        val srtContent = "1\n00:00:01,000 --> 00:00:03,000\nTest\n"
        val shifted = SubtitleDelayShifter.shiftDelay(srtContent, 1000L)
        assertTrue(shifted.contains("00:00:02,000 --> 00:00:04,000"))

        val tempDir = File.createTempFile("delay_test", "").apply { delete(); mkdirs() }
        tempDir.deleteOnExit()

        val origFile = File(tempDir, "sample.srt").apply { writeText(srtContent, Charsets.UTF_8) }
        val shiftedFile = SubtitleDelayShifter.getOrCreateShiftedFile(origFile, 1000L)

        assertTrue(shiftedFile.exists())
        assertTrue(shiftedFile.name.contains("sample_delay_1000.srt"))
        assertTrue(shiftedFile.readText().contains("00:00:02,000 --> 00:00:04,000"))
    }
}
