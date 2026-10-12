package com.beatraxus.app.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PtPreloadPlanTest {

    private val queue = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")

    @Test
    fun upcomingIsTheEightSongsAfterTheCurrentOne() {
        assertEquals(listOf("c", "d", "e", "f", "g", "h", "i", "j"), PtPreloadPlan.upcoming(queue, "b"))
        assertEquals(8, PtPreloadPlan.COUNT)
    }

    @Test
    fun upcomingIsShorterNearTheEndOfTheQueue() {
        assertEquals(listOf("k", "l"), PtPreloadPlan.upcoming(queue, "j"))
        assertTrue(PtPreloadPlan.upcoming(queue, "l").isEmpty())
    }

    @Test
    fun upcomingIsEmptyWhenTheCurrentSongIsNotInTheQueue() {
        assertTrue(PtPreloadPlan.upcoming(queue, "zzz").isEmpty())
        assertTrue(PtPreloadPlan.upcoming(queue, null).isEmpty())
        assertTrue(PtPreloadPlan.upcoming(emptyList(), "a").isEmpty())
    }

    @Test
    fun upcomingSkipsRepeatsOfTheCurrentSongAndOfEachOther() {
        assertEquals(listOf("b", "c"), PtPreloadPlan.upcoming(listOf("a", "b", "a", "c", "b"), "a"))
    }

    @Test
    fun nextIsTheFirstSongThatIsMissing() {
        val have = setOf("c", "d")
        assertEquals("e", PtPreloadPlan.next(PtPreloadPlan.upcoming(queue, "b")) { it in have })
    }

    @Test
    fun nextIsNullWhenEverythingIsAlreadyThere() {
        val upcoming = PtPreloadPlan.upcoming(queue, "b")
        assertNull(PtPreloadPlan.next(upcoming) { true })
        assertNull(PtPreloadPlan.next(emptyList()) { false })
    }

    @Test
    fun keepCoversPreviousCurrentAndTheNextEight() {
        assertEquals(
            setOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"),
            PtPreloadPlan.keep(queue, "b")
        )
    }

    @Test
    fun keepHasNoPreviousSongAtTheStart() {
        assertEquals(
            setOf("a", "b", "c", "d", "e", "f", "g", "h", "i"),
            PtPreloadPlan.keep(queue, "a")
        )
    }

    @Test
    fun keepAlwaysKeepsTheCurrentSongEvenWhenTheQueueDoesNotListIt() {
        assertEquals(setOf("zzz"), PtPreloadPlan.keep(queue, "zzz"))
        assertTrue(PtPreloadPlan.keep(queue, null).isEmpty())
    }
}
