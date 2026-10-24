package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrFormatTest {

    @Test
    fun `sdr stream is not hdr`() {
        val h = HdrFormat.detect("video/avc", "avc1.640028", 3, 1)
        assertEquals(HdrKind.NONE, h.kind)
        assertFalse(h.isHdr)
        assertNull(h.badge)
    }

    @Test
    fun `pq with bt2020 is hdr10`() {
        val h = HdrFormat.detect("video/hevc", "hvc1.2.4.L153.B0", 6, 6)
        assertEquals(HdrKind.HDR10, h.kind)
        assertEquals("HDR10", h.badge)
    }

    @Test
    fun `hlg is detected`() {
        val h = HdrFormat.detect("video/hevc", null, 7, 6)
        assertEquals(HdrKind.HLG, h.kind)
        assertEquals("HLG", h.badge)
    }

    @Test
    fun `dolby vision mime without codec string has no profile`() {
        val h = HdrFormat.detect("video/dolby-vision", null, -1)
        assertEquals(HdrKind.DOLBY_VISION, h.kind)
        assertNull(h.dolbyVisionProfile)
        assertEquals("Dolby Vision", h.label)
        assertEquals("DV", h.badge)
    }

    @Test
    fun `dolby vision profile is read from the codec string`() {
        assertEquals(8, HdrFormat.detect("video/hevc", "dvhe.08.06", 6, 6).dolbyVisionProfile)
        assertEquals(5, HdrFormat.detect("video/dolby-vision", "dvh1.05.09", -1).dolbyVisionProfile)
        assertEquals(10, HdrFormat.detect("video/av01", "dav1.10.09", -1).dolbyVisionProfile)
        assertEquals("Dolby Vision (profile 8)", HdrFormat.detect(null, "dvhe.08.06", 6).label)
    }

    @Test
    fun `dolby vision wins over the hdr10 base layer`() {
        assertEquals(HdrKind.DOLBY_VISION, HdrFormat.detect("video/hevc", "dvhe.07.06", 6, 6).kind)
    }

    @Test
    fun `codec string with several entries is handled`() {
        assertEquals(8, HdrFormat.detect(null, "dvhe.08.04, mp4a.40.2", -1).dolbyVisionProfile)
    }

    @Test
    fun `only profile 5 needs a dolby vision decoder`() {
        assertTrue(HdrFormat.detect(null, "dvhe.05.06", -1).needsDolbyVisionDecoder)
        assertFalse(HdrFormat.detect(null, "dvhe.08.06", -1).needsDolbyVisionDecoder)
        assertFalse(HdrFormat.detect(null, "dvhe.07.06", -1).needsDolbyVisionDecoder)
        assertFalse(HdrFormat.detect("video/hevc", null, 6).needsDolbyVisionDecoder)
    }

    @Test
    fun `unreadable profile gives null`() {
        assertNull(HdrFormat.dolbyVisionProfile("dvhe"))
        assertNull(HdrFormat.dolbyVisionProfile("dvhe.xx.06"))
    }

    @Test
    fun `profile notes only for dolby vision`() {
        assertNull(HdrFormat.detect("video/hevc", null, 6).profileNote)
        assertTrue(HdrFormat.detect(null, "dvhe.08.06", -1).profileNote!!.contains("Profile 8"))
        assertNull(HdrFormat.detect(null, "dvhe.99.06", -1).profileNote)
    }

    @Test
    fun `dynamic range text names the dolby vision profile`() {
        assertEquals("Dolby Vision (profile 8)", VideoInfoReport.dynamicRange("video/dolby-vision", 6, 6, "dvhe.08.06"))
        assertEquals("Dolby Vision", VideoInfoReport.dynamicRange("video/dolby-vision", 6, 6))
        assertEquals("HDR10 / PQ, BT.2020", VideoInfoReport.dynamicRange("video/hevc", 6, 6, "hvc1.2.4.L153.B0"))
    }
}
