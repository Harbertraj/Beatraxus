package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundPositionTest {

    private val idxL = 0
    private val idxR = 1
    private val idxC = 2
    private val idxSL = 3
    private val idxSR = 4
    private val idxBL = 5
    private val idxBR = 6
    private val idxSW = 7

    @Test
    fun anglesWrapIntoZeroToThreeSixty() {
        assertEquals(0f, normalizeSurroundAngle(0f), 0f)
        assertEquals(10f, normalizeSurroundAngle(370f), 1e-4f)
        assertEquals(350f, normalizeSurroundAngle(-10f), 1e-4f)
        assertEquals(0f, normalizeSurroundAngle(720f), 1e-4f)
        assertEquals(SURROUND_POSITION_DEFAULT_ANGLE_DEG, normalizeSurroundAngle(Float.NaN), 0f)
        assertEquals(SURROUND_POSITION_DEFAULT_ANGLE_DEG, normalizeSurroundAngle(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun distanceIsClampedAndNanFallsBack() {
        assertEquals(SURROUND_POSITION_MIN_DISTANCE, sanitizeSurroundDistance(-3f), 0f)
        assertEquals(SURROUND_POSITION_MAX_DISTANCE, sanitizeSurroundDistance(9f), 0f)
        assertEquals(SURROUND_POSITION_DEFAULT_DISTANCE, sanitizeSurroundDistance(Float.NaN), 0f)
        assertEquals(0.5f, sanitizeSurroundDistance(0.5f), 0f)
    }

    @Test
    fun alwaysEightFiniteValuesInsideTheAllowedRange() {
        var angle = 0f
        while (angle < 360f) {
            for (distance in listOf(0.15f, 0.4f, 0.8f, 1f)) {
                val g = surroundPositionGainsDb(angle, distance)
                assertEquals(8, g.size)
                assertTrue(g.all { !it.isNaN() && it in SURROUND_POSITION_MAX_CUT_DB..SURROUND_POSITION_MAX_BOOST_DB })
            }
            angle += 7.5f
        }
    }

    @Test
    fun subwooferNeverMoves() {
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            assertEquals(0f, surroundPositionGainsDb(angle, 1f)[idxSW], 0f)
        }
    }

    @Test
    fun straightAheadLiftsFrontAndCutsBack() {
        val g = surroundPositionGainsDb(0f, 1f)
        assertTrue(g[idxC] > g[idxL])
        assertTrue(g[idxL] > g[idxSL])
        assertTrue(g[idxSL] > g[idxBL])
        assertTrue(g[idxC] > 0f)
        assertTrue(g[idxBL] < 0f && g[idxBR] < 0f)
    }

    @Test
    fun straightBehindLiftsBackAndCutsFront() {
        val g = surroundPositionGainsDb(180f, 1f)
        assertTrue(g[idxBL] > 0f && g[idxBR] > 0f)
        assertTrue(g[idxC] < 0f)
        assertTrue(g[idxBL] > g[idxL])
    }

    @Test
    fun rightAndLeftAreMirrorImages() {
        for (angle in listOf(20f, 60f, 100f, 150f)) {
            val right = surroundPositionGainsDb(angle, 0.8f)
            val left = surroundPositionGainsDb(360f - angle, 0.8f)
            assertEquals(right[idxR], left[idxL], 0f)
            assertEquals(right[idxSR], left[idxSL], 0f)
            assertEquals(right[idxBR], left[idxBL], 0f)
            assertEquals(right[idxC], left[idxC], 0f)
        }
    }

    @Test
    fun aPositionBetweenSpeakersLiftsBothNeighbours() {
        // 61 degrees sits exactly between R (32) and SR (90)
        val g = surroundPositionGainsDb(61f, 1f)
        assertTrue(g[idxR] > g[idxL])
        assertTrue(g[idxSR] > g[idxSL])
        assertTrue(kotlin.math.abs(g[idxR] - g[idxSR]) < 1.5f)
    }

    @Test
    fun closeSoundSpreadsMoreEvenlyThanWideSound() {
        fun spread(d: Float): Float {
            val g = surroundPositionGainsDb(0f, d)
            val speakers = g.filterIndexed { i, _ -> i != idxSW }
            return speakers.max() - speakers.min()
        }
        assertTrue(spread(0.15f) < spread(1f))
        assertTrue(spread(0.15f) < 12f)
    }

    @Test
    fun moveOffMeansNoOffsetsAtAll() {
        assertNull(DspConfig().surroundPositionOffsetsDb())
        val on = DspConfig(surroundMoveEnabled = true, surroundPositionAngleDeg = 90f, surroundPositionDistance = 1f)
        assertNotNull(on.surroundPositionOffsetsDb())
        assertEquals(surroundPositionGainsDb(90f, 1f), on.surroundPositionOffsetsDb())
    }

    @Test
    fun settingsEditsAreSanitisedAndKeepOtherSettings() {
        val base = DspConfig(surroundEnabled = true, surroundActivePreset = "Cinema")
        val moved = base.withSurroundPosition(-90f, 5f)
        assertEquals(270f, moved.surroundPositionAngleDeg, 1e-4f)
        assertEquals(SURROUND_POSITION_MAX_DISTANCE, moved.surroundPositionDistance, 0f)
        assertEquals("Cinema", moved.surroundActivePreset)
        assertTrue(moved.surroundEnabled)

        val on = moved.withSurroundMove(true)
        assertTrue(on.surroundMoveEnabled)
        assertEquals(270f, on.surroundPositionAngleDeg, 1e-4f)
        assertFalse(on.withSurroundMove(false).surroundMoveEnabled)

        val reset = on.withSurroundPositionReset()
        assertEquals(0f, reset.surroundPositionAngleDeg, 0f)
        assertEquals(SURROUND_POSITION_MIN_DISTANCE, reset.surroundPositionDistance, 0f)
        assertTrue(reset.surroundMoveEnabled)
    }

    @Test
    fun videoSurroundEngineKeepsThePosition() {
        val cfg = DspConfig(surroundMoveEnabled = true, surroundPositionAngleDeg = 135f, surroundPositionDistance = 0.4f)
        val engine = cfg.toSurroundEngineConfig()
        assertTrue(engine.surroundMoveEnabled)
        assertEquals(135f, engine.surroundPositionAngleDeg, 0f)
        assertEquals(0.4f, engine.surroundPositionDistance, 0f)
    }

    // ── presets ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyBuiltInPresetExplainsItself() {
        BUILT_IN_SURROUND_PRESETS.forEach {
            assertTrue("${it.name} needs a description", it.description.isNotBlank())
            assertEquals(it.description, surroundPresetDescription(it.name))
        }
        assertTrue(surroundPresetDescription("").contains("Custom 1"))
        assertTrue(surroundPresetDescription(SURROUND_CUSTOM_PRESET_NAME).isNotBlank())
        assertEquals("", surroundPresetDescription("No such preset"))
    }

    @Test
    fun positionPresetsAreValidAndUnique() {
        val names = BUILT_IN_SURROUND_POSITIONS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        BUILT_IN_SURROUND_POSITIONS.forEach {
            assertTrue(it.angleDeg >= 0f && it.angleDeg < 360f)
            assertTrue(it.distance in SURROUND_POSITION_MIN_DISTANCE..SURROUND_POSITION_MAX_DISTANCE)
        }
    }

    @Test
    fun applyingAPositionPresetMovesTheSoundAndHighlightsIt() {
        val base = DspConfig(surroundMoveEnabled = true, surroundActivePreset = "Cinema")
        BUILT_IN_SURROUND_POSITIONS.forEach { spot ->
            val moved = base.withSurroundPositionPreset(spot.name)
            assertEquals(spot.angleDeg, moved.surroundPositionAngleDeg, 1e-4f)
            assertEquals(spot.distance, moved.surroundPositionDistance, 1e-4f)
            assertEquals(spot.name, moved.activeSurroundPositionPreset())
            assertEquals("Cinema", moved.surroundActivePreset)
            assertTrue(moved.surroundMoveEnabled)
        }
        assertEquals(base, base.withSurroundPositionPreset("No such spot"))
    }

    @Test
    fun noSpotIsHighlightedWhileTheOrbitIsBetweenSpots() {
        val between = DspConfig(surroundPositionAngleDeg = 20f, surroundPositionDistance = 0.8f)
        assertNull(between.activeSurroundPositionPreset())
        val near = DspConfig(surroundPositionAngleDeg = 359f, surroundPositionDistance = 0.82f)
        assertEquals("Front stage", near.activeSurroundPositionPreset())
    }

    @Test
    fun behindYouPresetPutsTheBackSpeakersOnTop() {
        val g = surroundPositionGainsDb(
            BUILT_IN_SURROUND_POSITIONS.first { it.name == "Behind you" }.angleDeg,
            BUILT_IN_SURROUND_POSITIONS.first { it.name == "Behind you" }.distance
        )
        assertTrue(g[5] > 0f && g[6] > 0f)
        assertTrue(g[2] < 0f)
    }

    // ── 360° move for stereo sources (constant-power routing) ───────────────────────────────────────────────

    private fun power(g: List<Float>) = g.sumOf { (it * it).toDouble() }

    @Test
    fun moveRoutingKeepsConstantPowerAtEveryAngleAndDistance() {
        for (sevenOne in listOf(true, false)) {
            var angle = 0f
            while (angle < 360f) {
                for (distance in listOf(0.15f, 0.3f, 0.5f, 0.8f, 1f)) {
                    val m = surroundMoveMix(angle, distance, sevenOne)
                    assertEquals("left @$angle/$distance", 1.0, power(m.left), 1e-3)
                    assertEquals("right @$angle/$distance", 1.0, power(m.right), 1e-3)
                    assertTrue((m.left + m.right).all { !it.isNaN() && it in 0f..1.0001f })
                    assertTrue(m.centre in 0f..1f)
                }
                angle += 7.5f
            }
        }
    }

    @Test
    fun moveRoutingNeverUsesCentreOrSubOrMissingBackPair() {
        val five = surroundMoveMix(180f, 1f, false)
        assertEquals(0f, five.left[idxBL], 0f)
        assertEquals(0f, five.right[idxBR], 0f)
        for (m in listOf(surroundMoveMix(40f, 0.7f, true), five)) {
            assertEquals(0f, m.left[idxC], 0f)
            assertEquals(0f, m.right[idxSW], 0f)
        }
    }

    @Test
    fun straightAheadIsPlainStereoOnTheFrontPair() {
        val m = surroundMoveMix(0f, 1f, true)
        assertTrue(m.left[idxL] > 0.99f)
        assertTrue(m.right[idxR] > 0.99f)
        assertEquals(1f, m.centre, 1e-4f)
    }

    @Test
    fun straightBehindKeepsTheWholeSongOnTheBackPair() {
        val seven = surroundMoveMix(180f, 1f, true)
        assertTrue(seven.left[idxBR] + seven.left[idxBL] > 0.99f)
        assertTrue(seven.left[idxL] < 0.01f && seven.right[idxR] < 0.01f)
        assertEquals(0f, seven.centre, 1e-4f)
        val five = surroundMoveMix(180f, 1f, false)
        assertTrue(five.left[idxSL] + five.left[idxSR] > 0.99f)
    }

    @Test
    fun rightSideCarriesBothHalvesOfTheSong() {
        val m = surroundMoveMix(90f, 1f, true)
        val rightSide = m.left[idxR] * m.left[idxR] + m.left[idxSR] * m.left[idxSR] + m.left[idxBR] * m.left[idxBR]
        assertTrue("left half should sit on the right side", rightSide > 0.95f)
        assertTrue(m.right[idxSR] > m.right[idxSL])
    }

    @Test
    fun theDistanceSliderChangesTheSoundOverItsWholeRange() {
        // No dead zone: every 10% step of the slider changes the routing of a speaker.
        var previous = surroundMoveMix(0f, SURROUND_POSITION_MIN_DISTANCE, true)
        var d = SURROUND_POSITION_MIN_DISTANCE + 0.085f
        while (d <= SURROUND_POSITION_MAX_DISTANCE + 1e-4f) {
            val now = surroundMoveMix(0f, d, true)
            val moved = now.left.indices.any { kotlin.math.abs(now.left[it] - previous.left[it]) > 0.01f }
            assertTrue("slider at $d changed nothing", moved)
            previous = now
            d += 0.085f
        }
        // Close surrounds the listener evenly, wide is placed.
        val close = surroundMoveMix(0f, SURROUND_POSITION_MIN_DISTANCE, true)
        assertEquals(close.left[idxL], close.left[idxBR], 1e-3f)
        assertEquals(0f, close.centre, 1e-4f)
    }

    @Test
    fun moveLevelsShowTheSpeakersThatCarryTheSound() {
        val behind = surroundMoveLevelsDb(180f, 1f, true)
        assertTrue(behind[idxBL] > -3f && behind[idxBR] > -3f)
        assertTrue(behind[idxL] < -15f && behind[idxC] <= -30f)
        assertEquals(0f, behind[idxSW], 0f)
        val ahead = surroundMoveLevelsDb(0f, 1f, true)
        assertEquals(0f, ahead[idxL], 0.5f)
        assertTrue(ahead.all { !it.isNaN() })
    }

    @Test
    fun moveOffMeansNoMoveRouting() {
        assertNull(DspConfig().surroundMoveMixOrNull(true))
        assertNotNull(DspConfig(surroundMoveEnabled = true).surroundMoveMixOrNull(true))
    }
}
