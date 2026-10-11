package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegOnlyFormatsTest {

    @Test
    fun apeWvTtaMpcAndMkaAreRoutedByExtension() {
        for (ext in listOf("ape", "wv", "tta", "mpc", "mka")) {
            assertTrue("$ext should use FFmpeg", FfmpegOnlyFormats.isFfmpegOnly(ext, format = ""))
        }
    }

    @Test
    fun extensionMatchIgnoresCase() {
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly("APE", format = ""))
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly("Wv", format = ""))
    }

    @Test
    fun formatNameIsEnoughWhenThereIsNoExtension() {
        // Cloud cache files and Telegram downloads often have no usable extension.
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly(extension = "", format = "APE"))
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly(extension = "", format = "wv"))
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly(extension = "", format = "MKA"))
    }

    @Test
    fun dsdStillGoesToFfmpeg() {
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly("dsf", format = ""))
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly("dff", format = ""))
        assertTrue(FfmpegOnlyFormats.isFfmpegOnly(extension = "", format = "DSD"))
    }

    @Test
    fun formatsMediaCodecHandlesAreNotForced() {
        for (ext in listOf("mp3", "flac", "aac", "ogg", "opus", "m4a", "wav")) {
            assertFalse("$ext should stay on MediaCodec", FfmpegOnlyFormats.isFfmpegOnly(ext, format = ""))
        }
        assertFalse(FfmpegOnlyFormats.isFfmpegOnly(extension = "", format = "FLAC"))
    }

    @Test
    fun demuxerHintsExistForContainersFfmpegCannotSniffFromAPipe() {
        assertEquals("ape", FfmpegOnlyFormats.demuxerHint("ape", ""))
        assertEquals("wv", FfmpegOnlyFormats.demuxerHint("wv", ""))
        assertEquals("tta", FfmpegOnlyFormats.demuxerHint("tta", ""))
        assertEquals("mpc", FfmpegOnlyFormats.demuxerHint("mpc", ""))
        assertEquals("ape", FfmpegOnlyFormats.demuxerHint("", "APE"))
    }

    @Test
    fun matroskaAndDsdAreLeftToProbing() {
        assertNull(FfmpegOnlyFormats.demuxerHint("mka", ""))
        assertNull(FfmpegOnlyFormats.demuxerHint("dsf", "DSD"))
        assertNull(FfmpegOnlyFormats.demuxerHint("mp3", "MP3"))
    }
}
