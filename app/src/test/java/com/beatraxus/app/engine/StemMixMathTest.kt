package com.beatraxus.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StemMixMathTest {

    private fun mix(gains: FloatArray, stems: Int, n: Int, readable: (Int) -> Boolean = { true }): Pair<Boolean, FloatArray> {
        val m = FloatArray(n) { 99f }                       // must be zeroed first
        val scratch = FloatArray(n)
        val read = BooleanArray(stems)
        val any = StemMixMath.mixInto(gains, stems, n, m, scratch) { stem, dst ->
            if (!readable(stem)) false else { for (i in 0 until n) dst[i] = (stem + 1).toFloat(); read[stem] = true; true }
        }
        return any to m
    }

    @Test
    fun sumsFourAndSixStemsWithTheirGains() {
        val (any4, m4) = mix(floatArrayOf(1f, 0.5f, 0f, 2f), 4, 8)
        assertTrue(any4)
        assertEquals(1f * 1 + 0.5f * 2 + 2f * 4, m4[0], 1e-6f)
        val (any6, m6) = mix(floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f), 6, 8)
        assertTrue(any6)
        assertEquals(21f, m6[7], 1e-6f)                    // 1+2+3+4+5+6
    }

    @Test
    fun silentStemsAreNotRead() {
        var reads = 0
        val m = FloatArray(4); val s = FloatArray(4)
        StemMixMath.mixInto(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f), 6, 4, m, s) { _, dst -> reads++; dst.fill(1f); true }
        assertEquals(1, reads)
    }

    @Test
    fun unreadableStemsAreSkippedAndReportedWhenNothingIsMixed() {
        val (any, m) = mix(floatArrayOf(1f, 1f, 1f, 1f), 4, 4) { false }
        assertFalse(any)
        assertTrue(m.all { it == 0f })
        val (some, m2) = mix(floatArrayOf(1f, 1f, 1f, 1f), 4, 4) { it != 1 }
        assertTrue(some)
        assertEquals(1f + 3f + 4f, m2[0], 1e-6f)
    }

    @Test
    fun allSilentChecksOnlyTheActiveStems() {
        assertTrue(StemMixMath.allSilent(floatArrayOf(0f, 0f, 0f, 0f, 5f, 5f), 4))
        assertFalse(StemMixMath.allSilent(floatArrayOf(0f, 0f, 0f, 0f, 5f, 5f), 5))
    }

    @Test
    fun nanFromABrokenStemIsSanitisedBeforeItReachesTheFilterChain() {
        val m = FloatArray(6)
        val s = FloatArray(6)
        StemMixMath.mixInto(floatArrayOf(1f, 1f), 2, 6, m, s) { stem, dst ->
            dst.fill(0.25f)
            if (stem == 1) { dst[0] = Float.NaN; dst[1] = Float.POSITIVE_INFINITY; dst[2] = 1e9f }
            true
        }
        assertTrue(m.any { !it.isFinite() })               // the mix itself is dirty ...
        val bad = StemDsp.sanitize(m, 0, m.size)           // ... and the sanitise step the processor runs cleans it
        assertTrue(bad >= 2)
        assertTrue(m.all { it.isFinite() && Math.abs(it) <= StemDsp.MAX_ABS })
    }

    @Test
    fun modelOutputWithTooManyBadValuesIsRejected() {
        val total = 6L * 2 * 343_980
        assertFalse(StemModelContract.tooManyBad(0, total))
        assertFalse(StemModelContract.tooManyBad((total * 0.05).toLong(), total))
        assertTrue(StemModelContract.tooManyBad((total * 0.05).toLong() + 1, total))
        val out = FloatArray(1000) { if (it % 10 == 0) Float.NaN else 0.1f }      // 10 % bad
        val bad = StemDsp.sanitize(out, 0, out.size)
        assertEquals(100, bad)
        assertTrue(StemModelContract.tooManyBad(bad.toLong(), out.size.toLong()))
        assertTrue(out.all { it.isFinite() })
    }
}
