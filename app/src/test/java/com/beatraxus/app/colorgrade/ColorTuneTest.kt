package com.beatraxus.app.colorgrade

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTuneTest {
    private val eps = 1e-4f

    @Test fun noHueAndNoTemperatureIsTheIdentity() {
        assertTrue(ColorTune.isIdentity(0f, 0f))
        assertArrayEquals(FloatArray(9), ColorTune.deltaColumnMajor(0f, 0f), eps)
    }

    @Test fun anyHueOrTemperatureIsNotTheIdentity() {
        assertFalse(ColorTune.isIdentity(30f, 0f))
        assertFalse(ColorTune.isIdentity(0f, -0.2f))
    }

    @Test fun fullTurnOfHueChangesNothing() {
        assertArrayEquals(FloatArray(9), ColorTune.deltaColumnMajor(360f, 0f), 1e-3f)
    }

    @Test fun hueKeepsGreyAndBrightnessOfWhite() {
        // every row sums to 1, so r = g = b stays r = g = b
        for (deg in listOf(-180f, -90f, 45f, 120f, 180f)) {
            val m = ColorTune.hueMatrix(deg)
            for (row in 0 until 3) assertEquals("hue $deg row $row", 1f, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 2e-3f)
        }
    }

    @Test fun warmAddsRedAndTakesAwayBlue() {
        val m = ColorTune.matrix(0f, 1f)
        assertEquals(1.25f, m[0], eps)
        assertEquals(1f, m[4], eps)
        assertEquals(0.75f, m[8], eps)
        val cool = ColorTune.matrix(0f, -1f)
        assertEquals(0.75f, cool[0], eps)
        assertEquals(1.25f, cool[8], eps)
    }

    @Test fun deltaIsColumnMajorAndMinusIdentity() {
        val row = ColorTune.matrix(70f, 0.4f)
        val delta = ColorTune.deltaColumnMajor(70f, 0.4f)
        for (r in 0 until 3) for (c in 0 until 3) {
            assertEquals("r$r c$c", row[r * 3 + c] - (if (r == c) 1f else 0f), delta[c * 3 + r], eps)
        }
    }
}
