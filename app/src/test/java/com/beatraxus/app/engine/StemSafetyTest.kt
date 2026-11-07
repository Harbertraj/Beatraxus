package com.beatraxus.app.engine

import com.beatraxus.app.model.StemQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StemSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun marker() = StemRunMarker(File(tmp.root, "sub/stem_run.marker"))

    // ── run marker: "was the last separation killed?" ────────────────────────────────────────────────────

    @Test
    fun aFinishedRunLeavesNothingBehind() {
        val m = marker()
        val token = m.begin("song|with|pipes", StemQuality.MAX, 1234L)
        m.end(token)
        assertNull(m.consumeUnfinished())
    }

    @Test
    fun anUnfinishedRunIsReportedOnceWithItsDetails() {
        val m = marker()
        m.begin("42", StemQuality.MAX, 1234L)                  // the process dies here: end() is never called
        val u = marker().consumeUnfinished()                   // the next process
        assertNotNull(u)
        assertEquals(StemQuality.MAX, u!!.quality)
        assertEquals("42", u.songId)
        assertEquals(1234L, u.startedAtMs)
        assertNull(marker().consumeUnfinished())               // reported once
    }

    @Test
    fun songIdsWithSeparatorsSurvive() {
        marker().begin("a|b|c", StemQuality.FAST, 5L)
        assertEquals("a|b|c", marker().consumeUnfinished()!!.songId)
    }

    @Test
    fun anOldRunCannotRemoveTheMarkerOfANewerRun() {
        val m = marker()
        val old = m.begin("1", StemQuality.MAX, 1L)
        val new = m.begin("2", StemQuality.MAX, 2L)
        m.end(old)                                             // the cancelled run's cleanup arrives late
        assertEquals("2", m.consumeUnfinished()!!.songId)
        m.end(new)
    }

    @Test
    fun damagedMarkersAreIgnoredAndCleared() {
        val f = File(tmp.root, "bad.marker")
        for (text in listOf("", "garbage", "MAX|notanumber|1", "ULTRA|5|1", "MAX|5")) {
            f.writeText(text)
            assertNull(text, StemRunMarker(f).consumeUnfinished())
            assertFalse(f.exists())
        }
        assertNull(StemRunMarker(File(tmp.root, "missing")).consumeUnfinished())
    }

    // ── memory gate ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun enoughMemoryMeansGo() {
        assertEquals(StemMemoryGate.Verdict.OK, StemMemoryGate.decide(false, 900L * 1024 * 1024, 0))
        assertEquals(StemMemoryGate.Verdict.OK, StemMemoryGate.decide(false, 900L * 1024 * 1024, 99))
    }

    @Test
    fun lowMemoryWaitsThenAborts() {
        val low = 50L * 1024 * 1024
        for (w in 0 until StemMemoryGate.MAX_WAITS) {
            assertEquals(StemMemoryGate.Verdict.WAIT, StemMemoryGate.decide(true, low, w))
        }
        assertEquals(StemMemoryGate.Verdict.ABORT, StemMemoryGate.decide(true, low, StemMemoryGate.MAX_WAITS))
    }

    @Test
    fun lowIsTheSystemFlagOrLittleAvailableRam() {
        assertTrue(StemMemoryGate.isLow(true, Long.MAX_VALUE))
        assertTrue(StemMemoryGate.isLow(false, StemMemoryGate.MIN_RUNNING_AVAIL_BYTES - 1))
        assertFalse(StemMemoryGate.isLow(false, StemMemoryGate.MIN_RUNNING_AVAIL_BYTES))
    }

    @Test
    fun aRecoveryResetsTheWaitCountInTheCaller() {
        // The manager resets its counter on OK: after one short dip a later dip gets the full number of waits again.
        assertEquals(StemMemoryGate.Verdict.WAIT, StemMemoryGate.decide(true, 0, 0))
        assertEquals(StemMemoryGate.Verdict.OK, StemMemoryGate.decide(false, 1L shl 30, 1))
        assertEquals(StemMemoryGate.Verdict.WAIT, StemMemoryGate.decide(true, 0, 0))
    }

    // ── budget and ETA ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun runBytesCountTheTempDecodeAndEveryStem() {
        val frames = 44_100L * 60
        assertEquals(frames * 8, StemBudget.decodeBytes(frames))
        assertEquals(frames * 8 * 5, StemBudget.runBytes(frames, 4))
        assertEquals(frames * 8 * 7, StemBudget.runBytes(frames, 6))
    }

    @Test
    fun etaNeedsTwoChunksAndExtrapolatesTheAverage() {
        assertNull(StemBudget.etaMs(10_000, 0, 40))
        assertNull(StemBudget.etaMs(10_000, 1, 40))
        assertEquals(2_000L * 38, StemBudget.etaMs(4_000, 2, 40))        // 2 s per chunk, 38 left
        assertEquals(0L, StemBudget.etaMs(80_000, 40, 40))
        assertNull(StemBudget.etaMs(1_000, 5, 3))                         // inconsistent input: no guess
    }
}
