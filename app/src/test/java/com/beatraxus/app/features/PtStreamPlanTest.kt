package com.beatraxus.app.features

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtStreamPlanTest {
    // a 4 minute, 10 MB song: plays at about 42 KB/s
    private val total = 10_000_000L
    private val dur = 240_000L

    @Test
    fun formatsThatCanStartFromTheFront() {
        assertTrue(PtStreamPlan.streamable("mp3"))
        assertTrue(PtStreamPlan.streamable("FLAC"))
        assertFalse(PtStreamPlan.streamable("m4a"))
        assertFalse(PtStreamPlan.streamable("wav"))
    }

    @Test
    fun waitsForTheFirstBytes() {
        assertFalse(PtStreamPlan.shouldStart(10_000, total, dur, 2_000))
    }

    @Test
    fun waitsToMeasureTheSpeed() {
        assertFalse(PtStreamPlan.shouldStart(1_000_000, total, dur, 300))
    }

    @Test
    fun fastLinkStartsRightAway() {
        // 1 MB in 2 s = 500 KB/s, far above the 42 KB/s the song needs
        assertTrue(PtStreamPlan.shouldStart(1_000_000, total, dur, 2_000))
    }

    @Test
    fun slowLinkWaitsForAHeadStart() {
        // 20 KB/s (x0.8 = 16 KB/s) is below 42 KB/s: 400 KB is not enough, the song would stall
        assertFalse(PtStreamPlan.shouldStart(400_000, total, dur, 20_000))
        // ...but once most of the file is here the rest arrives before playback needs it
        assertTrue(PtStreamPlan.shouldStart(7_000_000, total, dur, 350_000))
    }

    @Test
    fun joiningInTheMiddleNeedsTheBytesAtThatPosition() {
        // room is at 2:00 (50 %): 3 MB downloaded fast is still before that position
        assertFalse(PtStreamPlan.shouldStart(3_000_000, total, dur, 3_000, startPosMs = 120_000))
        assertTrue(PtStreamPlan.shouldStart(6_000_000, total, dur, 3_000, startPosMs = 120_000))
    }

    @Test
    fun unknownSizeOrLengthNeverStartsEarly() {
        assertFalse(PtStreamPlan.shouldStart(1_000_000, 0, dur, 5_000))
        assertFalse(PtStreamPlan.shouldStart(1_000_000, total, 0, 5_000))
    }

    @Test
    fun aCompleteFileAlwaysStarts() {
        assertTrue(PtStreamPlan.shouldStart(total, total, dur, 10))
    }
}
