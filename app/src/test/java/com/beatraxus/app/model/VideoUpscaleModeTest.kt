package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUpscaleModeTest {

    @Test
    fun `unknown or missing name falls back to off`() {
        assertEquals(VideoUpscaleMode.OFF, VideoUpscaleMode.fromName(null))
        assertEquals(VideoUpscaleMode.OFF, VideoUpscaleMode.fromName("nonsense"))
        assertEquals(VideoUpscaleMode.EDGE, VideoUpscaleMode.fromName("EDGE"))
    }

    @Test
    fun `only off is not on`() {
        assertFalse(VideoUpscaleMode.OFF.isOn)
        assertTrue(VideoUpscaleMode.CLEAN.isOn)
        assertTrue(VideoUpscaleMode.EDGE.isOn)
    }

    @Test
    fun `720p on a 1080p-long-side screen is doubled at most`() {
        // 1280x720 on a 2400 px long side: scale would be 1.875
        val (w, h) = UpscaleSizing.outputSize(1280, 720, 2400)
        assertEquals(2400, w)
        assertEquals(1350, h)
    }

    @Test
    fun `scale never goes above the maximum`() {
        val (w, h) = UpscaleSizing.outputSize(640, 360, 3840)
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun `video already as large as the screen is left alone`() {
        assertEquals(1920 to 1080, UpscaleSizing.outputSize(1920, 1080, 1920))
        assertEquals(1920 to 1080, UpscaleSizing.outputSize(1920, 1080, 2000)) // scale 1.04 is under the minimum
    }

    @Test
    fun `output is never smaller than the input and never above 4K pixels`() {
        val (w, h) = UpscaleSizing.outputSize(2560, 1440, 7680)
        assertTrue(w >= 2560 && h >= 1440)
        assertTrue(w.toLong() * h <= UpscaleSizing.MAX_OUTPUT_PIXELS + 2L * (w + h))
    }

    @Test
    fun `invalid input is passed through`() {
        assertEquals(0 to 0, UpscaleSizing.outputSize(0, 0, 2400))
    }
}
