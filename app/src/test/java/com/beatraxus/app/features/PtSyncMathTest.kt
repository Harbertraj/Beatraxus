package com.beatraxus.app.features

import com.beatraxus.app.features.PtSyncMath.Fix
import com.beatraxus.app.features.PtSyncMath.GateStep
import org.junit.Assert.assertEquals
import org.junit.Test

class PtSyncMathTest {

    @Test
    fun smallErrorsAreIgnoredMediumNudgedLargeSeeked() {
        assertEquals(Fix.NONE, PtSyncMath.fixFor(0))
        assertEquals(Fix.NONE, PtSyncMath.fixFor(-15))
        assertEquals(Fix.NUDGE, PtSyncMath.fixFor(16))
        assertEquals(Fix.NUDGE, PtSyncMath.fixFor(-250))
        assertEquals(Fix.SEEK, PtSyncMath.fixFor(251))
        assertEquals(Fix.SEEK, PtSyncMath.fixFor(-4_000))
    }

    @Test
    fun gateWaitsUntilTheRoomClockReachesTheParkedPosition() {
        // parked at 10_500, room is at 10_200, play takes 120 ms to be heard -> wait 180 ms
        assertEquals(180L, PtSyncMath.gateWaitMs(10_500, 10_200, 120))
        assertEquals(GateStep.WAIT_THEN_START, PtSyncMath.gateStep(180))
    }

    @Test
    fun gateStartsAtOnceWhenOnTimeOrSlightlyLate() {
        assertEquals(GateStep.START_NOW, PtSyncMath.gateStep(0))
        assertEquals(GateStep.START_NOW, PtSyncMath.gateStep(2))
        assertEquals(GateStep.START_NOW, PtSyncMath.gateStep(-30))
    }

    @Test
    fun gateRepositionsWhenLateOrWhenTheRoomMovedAway() {
        assertEquals(GateStep.RESEEK, PtSyncMath.gateStep(-31))
        assertEquals(GateStep.RESEEK, PtSyncMath.gateStep(-2_000))
        assertEquals(GateStep.RESEEK, PtSyncMath.gateStep(1_501))
    }

    @Test
    fun parkPositionLeavesRoomForLoadingAndAudioLatency() {
        assertEquals(10_000L + 400 + 120, PtSyncMath.parkPosition(10_000, 400, 120))
    }

    @Test
    fun nudgeNeverSkipsMoreThanIsBufferedOrPadsMoreThanTheLimit() {
        assertEquals(40L, PtSyncMath.nudgeLimitMs(40, 5_000))
        assertEquals(80L, PtSyncMath.nudgeLimitMs(200, 100)) // 80 % of 100 ms buffered
        assertEquals(-40L, PtSyncMath.nudgeLimitMs(-40, 5_000))
        assertEquals(-250L, PtSyncMath.nudgeLimitMs(-900, 5_000))
        assertEquals(0L, PtSyncMath.nudgeLimitMs(0, 5_000))
    }

    @Test
    fun learnedTimesMoveTowardsTheSampleAndStayInRange() {
        assertEquals(200L, PtSyncMath.learn(100, 300, 0.5, 0, 5_000))
        assertEquals(5_000L, PtSyncMath.learn(4_900, 99_999, 0.5, 0, 5_000))
        assertEquals(0L, PtSyncMath.learn(10, -500, 0.9, 0, 5_000))
    }

    @Test
    fun clockOffsetBlendTrustsFastRoundTripsMore() {
        assertEquals(42L, PtSyncMath.blendClockOffset(null, 42, 500))
        val fast = PtSyncMath.blendClockOffset(100, 200, 40)
        val slow = PtSyncMath.blendClockOffset(100, 200, 500)
        assertEquals(150L, fast)
        assertEquals(105L, slow)
    }

    @Test
    fun noisyReadingsAreNotCorrectedButASteadyOffsetIs() {
        assertEquals(null, PtSyncMath.stableError(listOf(20L, 55L, -10L, 40L)))
        assertEquals(null, PtSyncMath.stableError(listOf(30L, 31L, 32L))) // not enough readings yet
        assertEquals(31L, PtSyncMath.stableError(listOf(30L, 31L, 32L, 36L)))
    }
}
