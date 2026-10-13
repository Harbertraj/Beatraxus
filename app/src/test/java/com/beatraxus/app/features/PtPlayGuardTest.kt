package com.beatraxus.app.features

import com.beatraxus.app.features.PtPlayGuard.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class PtPlayGuardTest {

    private fun next(
        shouldPlay: Boolean = true, playing: Boolean = false,
        pausedFor: Long = 5_000, sinceKick: Long = 5_000, kicks: Int = 0
    ) = PtPlayGuard.next(shouldPlay, playing, pausedFor, sinceKick, kicks)

    @Test
    fun aPlayingFollowerIsLeftAlone() {
        assertEquals(Action.NONE, next(playing = true))
    }

    @Test
    fun nothingHappensWhenTheRoomIsNotPlayingThisSong() {
        // room paused, other song loaded, this phone is the controller, song still being received...
        assertEquals(Action.NONE, next(shouldPlay = false))
        assertEquals(Action.NONE, next(shouldPlay = false, playing = true))
    }

    @Test
    fun aStartGetsAMomentBeforeItIsCalledStuck() {
        assertEquals(Action.WAIT, next(pausedFor = 0))
        assertEquals(Action.WAIT, next(pausedFor = PtPlayGuard.GRACE_MS - 1))
        assertEquals(Action.START, next(pausedFor = PtPlayGuard.GRACE_MS))
    }

    @Test
    fun nudgesAreSpacedOut() {
        assertEquals(Action.WAIT, next(sinceKick = PtPlayGuard.KICK_GAP_MS - 1, kicks = 1))
        assertEquals(Action.START, next(sinceKick = PtPlayGuard.KICK_GAP_MS, kicks = 1))
    }

    @Test
    fun startsFirstThenReloadsOnce() {
        assertEquals(Action.START, next(kicks = 0))
        assertEquals(Action.START, next(kicks = 1))
        assertEquals(Action.RELOAD, next(kicks = 2))
    }

    @Test
    fun givesUpAfterThreeNudgesUntilTheRoomChanges() {
        assertEquals(Action.NONE, next(kicks = PtPlayGuard.MAX_KICKS))
        assertEquals(Action.NONE, next(kicks = PtPlayGuard.MAX_KICKS + 5))
    }

    @Test
    fun theLimitsMatchTheDesign() {
        assertEquals(700L, PtPlayGuard.GRACE_MS)
        assertEquals(2, PtPlayGuard.START_KICKS)
        assertEquals(3, PtPlayGuard.MAX_KICKS)
    }
}
