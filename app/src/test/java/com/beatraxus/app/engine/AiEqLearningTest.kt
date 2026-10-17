package com.beatraxus.app.engine

import com.beatraxus.app.model.AiEqCorrectionEntity
import org.junit.Assert.*
import org.junit.Test

class AiEqLearningTest {

    @Test
    fun testComputeUpdatedCorrectionRunningAverage() {
        val genre = "Pop"
        val mood = "Energetic"
        val aiGains = FloatArray(10) { 0.0f }
        val userGains1 = FloatArray(10) { 2.0f }

        // Update 1: delta should be 2.0
        val correction1 = AiEqCalculator.computeUpdatedCorrection(
            existing = null,
            genre = genre,
            mood = mood,
            userGains = userGains1,
            aiGains = aiGains
        )

        assertEquals(1, correction1.updateCount)
        assertEquals(2.0f, correction1.delta31, 1e-3f)

        // Update 2: user selects 0.0 -> average of (2.0 and 0.0) should be 1.0
        val userGains2 = FloatArray(10) { 0.0f }
        val correction2 = AiEqCalculator.computeUpdatedCorrection(
            existing = correction1,
            genre = genre,
            mood = mood,
            userGains = userGains2,
            aiGains = aiGains
        )

        assertEquals(2, correction2.updateCount)
        assertEquals(1.0f, correction2.delta31, 1e-3f)
    }

    @Test
    fun testApplyUserCorrectionClamping() {
        val baseGains = FloatArray(10) { 3.0f }
        val correction = AiEqCorrectionEntity(
            id = "EDM_Energetic",
            genre = "EDM",
            mood = "Energetic",
            delta31 = 3.0f, // 3.0 + 3.0 = 6.0 -> should clamp to 4.0
            updateCount = 1
        )

        val finalGains = AiEqCalculator.applyUserCorrection(baseGains, correction)
        assertEquals(4.0f, finalGains[0], 1e-3f)
    }
}
