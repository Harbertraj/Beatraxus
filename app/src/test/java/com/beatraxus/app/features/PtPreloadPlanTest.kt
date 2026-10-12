package com.beatraxus.app.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PtPreloadPlanTest {

    private val queue = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")

    @Test
    fun upcomingIsTheFiveSongsAfterTheCurrentOne() {
        assertEquals(listOf("c", "d", "e", "f", "g"), PtPreloadPlan.upcoming(queue, "b"))
        assertEquals(5, PtPreloadPlan.COUNT)
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
    fun keepCoversPreviousCurrentAndTheNextFive() {
        assertEquals(
            setOf("a", "b", "c", "d", "e", "f", "g"),
            PtPreloadPlan.keep(queue, "b")
        )
    }

    @Test
    fun keepHasNoPreviousSongAtTheStart() {
        assertEquals(
            setOf("a", "b", "c", "d", "e", "f"),
            PtPreloadPlan.keep(queue, "a")
        )
    }

    @Test
    fun keepAlwaysKeepsTheCurrentSongEvenWhenTheQueueDoesNotListIt() {
        assertEquals(setOf("zzz"), PtPreloadPlan.keep(queue, "zzz"))
        assertTrue(PtPreloadPlan.keep(queue, null).isEmpty())
    }

    @Test
    fun missingListsUpToTheLimitInPlayOrder() {
        val upcoming = PtPreloadPlan.upcoming(queue, "b") // c d e f g
        assertEquals(listOf("c", "d", "e", "f", "g"), PtPreloadPlan.missing(upcoming, 5) { false })
        assertEquals(listOf("c", "d"), PtPreloadPlan.missing(upcoming, 2) { false })
    }

    @Test
    fun missingSkipsSongsAlreadyThereOrOnTheirWay() {
        val upcoming = PtPreloadPlan.upcoming(queue, "b")
        assertEquals(listOf("e", "g"), PtPreloadPlan.missing(upcoming, 5) { it in setOf("c", "d", "f") })
        assertTrue(PtPreloadPlan.missing(upcoming, 5) { true }.isEmpty())
        assertTrue(PtPreloadPlan.missing(upcoming, 0) { false }.isEmpty())
    }
}
