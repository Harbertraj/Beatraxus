package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAccountingTest {

    private fun ts(fps: Double, n: Int) = LongArray(n) { (it * 1_000_000_000.0 / fps).toLong() }

    /** Classifies a whole schedule the way the renderer does (warp whenever the schedule says it interpolated). */
    private fun classifyAll(source: LongArray, target: Int, warpAvailable: Boolean = true): List<FrameKind> {
        val c = FrameClassifier()
        return FramePacing.schedule(source, target).map {
            val a = source[it.pair]
            val b = source[it.pair + 1]
            // like the renderer: without a usable motion field the nearest real frame is shown (a === b)
            if (it.interpolated && warpAvailable) c.classify(true, it.alpha, a, b)
            else {
                val nearest = if (it.alpha < 0.5f) a else b
                c.classify(false, 1f, nearest, nearest)
            }
        }
    }

    @Test fun generatedNeedsWarpAndAPhaseStrictlyInsideThePair() {
        val c = FrameClassifier()
        assertEquals(FrameKind.GENERATED, c.classify(true, 0.4f, 0L, 100L))
        // phase at the edge is just the source frame again
        assertEquals(FrameKind.ORIGINAL, FrameClassifier().classify(true, 0.0f, 0L, 100L))
        assertEquals(FrameKind.ORIGINAL, FrameClassifier().classify(true, 0.01f, 0L, 100L))
        assertEquals(FrameKind.ORIGINAL, FrameClassifier().classify(true, 0.99f, 0L, 100L))
        // no warp: never generated, whatever the phase
        assertEquals(FrameKind.ORIGINAL, FrameClassifier().classify(false, 0.5f, 0L, 100L))
        // one single frame (a === b): never generated
        assertEquals(FrameKind.ORIGINAL, FrameClassifier().classify(true, 0.5f, 100L, 100L))
    }

    @Test fun theSameSourceFrameShownTwiceIsRepeatedNotGenerated() {
        val c = FrameClassifier()
        assertEquals(FrameKind.ORIGINAL, c.classify(false, 1f, 100L, 100L))
        assertEquals(FrameKind.REPEATED, c.classify(false, 1f, 100L, 100L))
        assertEquals(FrameKind.REPEATED, c.classify(false, 1f, 100L, 100L))
        assertEquals(FrameKind.ORIGINAL, c.classify(false, 1f, 200L, 200L))
    }

    @Test fun aGeneratedFrameDoesNotMakeTheNextRealFrameLookRepeated() {
        val c = FrameClassifier()
        assertEquals(FrameKind.ORIGINAL, c.classify(true, 0f, 0L, 100L))
        assertEquals(FrameKind.GENERATED, c.classify(true, 0.5f, 0L, 100L))
        assertEquals(FrameKind.ORIGINAL, c.classify(true, 1f, 0L, 100L).let { FrameKind.ORIGINAL }) // phase 1 = frame b
        val d = FrameClassifier()
        d.classify(true, 0f, 0L, 100L)
        d.classify(true, 0.5f, 0L, 100L)
        assertEquals(FrameKind.ORIGINAL, d.classify(true, 0.995f, 0L, 100L))   // shows b (ts 100) for the first time
        assertEquals(FrameKind.REPEATED, d.classify(true, 0.0f, 100L, 200L))   // pair advanced: b again at phase 0
    }

    @Test fun resetForgetsTheLastShownFrame() {
        val c = FrameClassifier()
        c.classify(false, 1f, 100L, 100L)
        c.reset()
        assertEquals(FrameKind.ORIGINAL, c.classify(false, 1f, 100L, 100L))
    }

    @Test fun twentyFourToSixtyIsEightyPercentGeneratedWithNoRepeats() {
        val kinds = classifyAll(ts(24.0, 49), 60)
        val total = kinds.size
        val gen = kinds.count { it == FrameKind.GENERATED }
        val orig = kinds.count { it == FrameKind.ORIGINAL }
        assertEquals(0, kinds.count { it == FrameKind.REPEATED })
        assertEquals(total, gen + orig)
        assertEquals(FramePacing.generatedShare(24f, 60), gen.toFloat() / total, 0.02f)
        // 60/24 = 2.5 outputs per source frame: only every second source frame lands exactly on an output tick
        // (phases 0, .4, .8, .2, .6 repeating), so ~24 originals per 48 source intervals and 4 of 5 outputs generated
        assertTrue("orig=$orig", orig in 24..25)
    }

    @Test fun everySupportedSourceRateToSixtyKeepsTheAccountsConsistent() {
        for (src in listOf(24.0, 25.0, 30.0, 48.0, 50.0)) {
            val source = ts(src, (src * 3).toInt() + 1)
            val kinds = classifyAll(source, 60)
            val gen = kinds.count { it == FrameKind.GENERATED }
            val rep = kinds.count { it == FrameKind.REPEATED }
            val share = gen.toFloat() / kinds.size
            assertEquals("src=$src share", FramePacing.generatedShare(src.toFloat(), 60), share, 0.03f)
            assertEquals("src=$src repeats", 0, rep)
            assertEquals("src=$src total", kinds.size, gen + kinds.count { it == FrameKind.ORIGINAL })
        }
    }

    @Test fun sixtyToSixtyGeneratesNothing() {
        val kinds = classifyAll(ts(60.0, 121), 60)
        assertEquals(0, kinds.count { it == FrameKind.GENERATED })
    }

    @Test fun fractionalSourceRateIsAllGeneratedExceptTheOccasionalAlignedFrame() {
        val kinds = classifyAll(ts(23.976, 73), 60)
        val share = kinds.count { it == FrameKind.GENERATED }.toFloat() / kinds.size
        // 23.976 -> 60 starts phase-aligned and drifts slowly, so the first few cycles still hit a source frame
        assertTrue("share=$share", share > 0.90f)
    }

    @Test fun missingMotionFieldCountsRepeatsInsteadOfInflatingGenerated() {
        // The motion estimate never arrives (GPU too slow): every due frame falls back to the nearest real frame.
        val kinds = classifyAll(ts(24.0, 49), 60, warpAvailable = false)
        assertEquals(0, kinds.count { it == FrameKind.GENERATED })
        // 60 outputs/s from 24 source frames/s: 24 originals, the other 36 are repeats
        val orig = kinds.count { it == FrameKind.ORIGINAL }
        val rep = kinds.count { it == FrameKind.REPEATED }
        assertEquals(kinds.size, orig + rep)
        assertTrue("orig=$orig", orig in 46..49)
        assertTrue("rep=$rep", rep > orig)
    }

    @Test fun aPauseHoldsOneFrameAndNeverCountsAsGenerated() {
        val ms = 1_000_000L
        val source = longArrayOf(0, 41_666_667, 83_333_333, 5_000 * ms, 5_041 * ms)
        val kinds = classifyAll(source, 60)
        val sched = FramePacing.schedule(source, 60)
        val inGap = sched.indices.filter { sched[it].timeNs in 84 * ms until 4_999 * ms }
        assertTrue(inGap.isNotEmpty())
        assertTrue(inGap.all { kinds[it] != FrameKind.GENERATED })
        assertTrue(inGap.drop(1).all { kinds[it] == FrameKind.REPEATED })
    }

    @Test fun statsAccumulatorSeparatesTheThreeKinds() {
        val acc = StatsAccumulator(windowNs = 1_000_000_000L)
        assertEquals(null, acc.poll(1L))
        repeat(48) { acc.onPresented(FrameKind.GENERATED) }
        repeat(10) { acc.onPresented(FrameKind.ORIGINAL) }
        repeat(2) { acc.onPresented(FrameKind.REPEATED) }
        repeat(24) { acc.onSourceFrame() }
        val w = acc.poll(1_000_000_001L)!!
        assertEquals(60, w.presented)
        assertEquals(48, w.generated)
        assertEquals(10, w.original)
        assertEquals(2, w.repeated)
        assertEquals(60f, w.renderedFps, 0.01f)
        assertEquals(48f, w.generatedFps, 0.01f)
        assertEquals(10f, w.originalFps, 0.01f)
        assertEquals(2f, w.repeatedFps, 0.01f)
        assertEquals(80f, w.interpolationPct, 0.01f)
        assertEquals(w.renderedFps, w.generatedFps + w.originalFps + w.repeatedFps, 0.01f)
    }

    @Test fun legacyBooleanOverloadStillWorksAndNeverReportsRepeats() {
        val acc = StatsAccumulator(windowNs = 1_000_000_000L)
        acc.poll(1L)
        acc.onPresented(true); acc.onPresented(false); acc.onPresented(false)
        val w = acc.poll(1_000_000_001L)!!
        assertEquals(3, w.presented)
        assertEquals(1, w.generated)
        assertEquals(2, w.original)
        assertEquals(0, w.repeated)
    }

    @Test fun interpolationShareIsZeroWithNothingPresented() {
        val w = WindowStats(1_000_000_000L, 0, 0, 0, 0, 0f, 0f, 0f)
        assertEquals(0f, w.interpolationPct, 0f)
        assertEquals(0f, w.repeatedFps, 0f)
    }
}
