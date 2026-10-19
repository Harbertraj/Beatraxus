package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Resampler "Auto" on Bluetooth: 44.1 -> 48, 48 -> 48, 96 kHz and higher untouched. */
class AutoResamplerBluetoothTest {

    private fun rate(input: Int, max: Int) = AudioEngine.autoBluetoothTargetRate(input, max)

    @Test
    fun `44_1 kHz goes to 48 kHz even when the headset reports 96 kHz`() {
        assertEquals(48_000, rate(44_100, 96_000))
        assertEquals(48_000, rate(44_100, 48_000))
        assertEquals(48_000, rate(44_100, 0))
    }

    @Test
    fun `48 kHz stays 48 kHz`() {
        assertEquals(48_000, rate(48_000, 96_000))
        assertEquals(48_000, rate(48_000, 48_000))
    }

    @Test
    fun `lower rates are lifted to 48 kHz`() {
        assertEquals(48_000, rate(22_050, 96_000))
        assertEquals(48_000, rate(32_000, 48_000))
    }

    @Test
    fun `88_2 and 96 kHz are untouched whatever the headset reports`() {
        assertEquals(88_200, rate(88_200, 48_000))
        assertEquals(96_000, rate(96_000, 48_000))
        assertEquals(96_000, rate(96_000, 96_000))
        assertEquals(96_000, rate(96_000, 0))
    }

    @Test
    fun `rates above 96 kHz are untouched when the headset can carry them`() {
        assertEquals(192_000, rate(192_000, 192_000))
        assertEquals(176_400, rate(176_400, 0))
    }

    @Test
    fun `rates above 96 kHz are limited to what the headset takes`() {
        assertEquals(96_000, rate(192_000, 96_000))
        assertEquals(48_000, rate(192_000, 48_000))
    }
}
