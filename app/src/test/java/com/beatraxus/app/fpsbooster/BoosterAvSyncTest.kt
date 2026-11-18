package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Test

class BoosterAvSyncTest {
    @Test fun compensatesTheHoldWhileTheBoosterRuns() {
        assertEquals(64, BoosterAvSync.targetMs(enabled = true, planActive = true, running = true, holdMs = 63.6f))
    }

    @Test fun zeroWhenOffOrNotRunning() {
        assertEquals(0, BoosterAvSync.targetMs(false, true, true, 60f))
        assertEquals(0, BoosterAvSync.targetMs(true, false, true, 60f))
        assertEquals(0, BoosterAvSync.targetMs(true, true, false, 60f))
    }

    @Test fun inaudibleOrInvalidHoldsAreIgnored() {
        assertEquals(0, BoosterAvSync.targetMs(true, true, true, 3f))
        assertEquals(0, BoosterAvSync.targetMs(true, true, true, Float.NaN))
    }

    @Test fun cappedAtTheClockLimit() {
        assertEquals(200, BoosterAvSync.targetMs(true, true, true, 5_000f))
    }

    @Test fun avSyncIsOnByDefaultAndRoundTrips() {
        assertEquals(true, FpsBoosterSettings().avSync)
        assertEquals(true, FpsBoosterSettings.fromStored("ON", 60, "AUTO", "BALANCED", true, false, true).avSync)
        assertEquals(false, FpsBoosterSettings.fromStored("ON", 60, "AUTO", "BALANCED", true, false, true, avSync = false).avSync)
    }
}
