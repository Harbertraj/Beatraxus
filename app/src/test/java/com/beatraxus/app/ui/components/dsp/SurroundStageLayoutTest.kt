package com.beatraxus.app.ui.components.dsp

import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.SurroundChannel
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundStageLayoutTest {

    private val zeroTrims = List(8) { 0f }

    private fun minGap(layout: List<androidx.compose.ui.geometry.Offset>): Float {
        var best = Float.MAX_VALUE
        for (i in layout.indices) for (j in i + 1 until layout.size) {
            val dx = layout[i].x - layout[j].x
            val dy = layout[i].y - layout[j].y
            best = minOf(best, sqrt(dx * dx + dy * dy))
        }
        return best
    }

    private fun radius(layout: List<androidx.compose.ui.geometry.Offset>, index: Int) =
        sqrt(layout[index].x * layout[index].x + layout[index].y * layout[index].y)

    @Test
    fun equalDistancesLeaveTheLayoutUntouched() {
        val plain = surroundStageLayout(DEFAULT_SURROUND_TRIMS_DB, null)
        val equal = surroundStageLayout(DEFAULT_SURROUND_TRIMS_DB, List(8) { 2.5f })
        assertEquals(plain, equal)
    }

    @Test
    fun aNearerSpeakerStandsCloserAndAFartherOneFurtherOut() {
        val base = surroundStageLayout(zeroTrims, null)
        val index = SPEAKER_INDEX_L
        val near = surroundStageLayout(zeroTrims, List(8) { 2.5f }.toMutableList().also { it[SurroundChannel.L.ordinal] = 1f })
        val far = surroundStageLayout(zeroTrims, List(8) { 2.5f }.toMutableList().also { it[SurroundChannel.L.ordinal] = 8f })
        assertTrue(radius(near, index) < radius(base, index))
        assertTrue(radius(far, index) > radius(base, index))
    }

    @Test
    fun speakersNeverStackOnEachOtherOrLeaveTheRoom() {
        val levels = listOf(-12f, 0f, 12f)
        val metres = listOf(0.5f, 2.5f, 10f)
        for (t in levels) for (d in metres) for (t2 in levels) for (d2 in metres) {
            val trims = List(8) { if (it % 2 == 0) t else t2 }
            val dists = List(8) { if (it % 2 == 0) d else d2 }
            val layout = surroundStageLayout(trims, dists)
            assertTrue("gap ${minGap(layout)} for $t/$d/$t2/$d2", minGap(layout) >= 0.24f)
            layout.indices.forEach { assertTrue(radius(layout, it) <= 1.23f) }
        }
    }

    @Test
    fun metresFactorIsOneAtTheDefaultAndGrowsWithDistance() {
        assertEquals(1f, speakerMetresFactor(2.5f), 1e-4f)
        assertTrue(speakerMetresFactor(1f) < 1f)
        assertTrue(speakerMetresFactor(8f) > 1f)
        assertTrue(speakerMetresFactor(8f) <= 1.2f)
    }

    private companion object {
        /** Position of L in the stage's speaker list (C, L, R, SW, SL, SR, BL, BR). */
        const val SPEAKER_INDEX_L = 1
    }
}
