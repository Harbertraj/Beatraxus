package com.beatraxus.app.engine

import com.beatraxus.app.model.AiEqCorrectionEntity
import kotlin.math.log10
import kotlin.math.max

data class AiEqResult(
    val bandGains: FloatArray,
    val preampDb: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AiEqResult

        if (!bandGains.contentEquals(other.bandGains)) return false
        if (preampDb != other.preampDb) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bandGains.contentHashCode()
        result = 31 * result + preampDb.hashCode()
        return result
    }
}

object AiEqCalculator {
    val ISO_FREQUENCIES = floatArrayOf(
        31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )

    private val BAND_BIN_RANGES = arrayOf(
        0..0,   // 31 Hz
        0..1,   // 62 Hz
        1..2,   // 125 Hz
        2..3,   // 250 Hz
        4..6,   // 500 Hz
        7..12,  // 1 kHz
        13..25, // 2 kHz
        26..50, // 4 kHz
        51..95, // 8 kHz
        96..127 // 16 kHz
    )

    /**
     * Calculates adaptive per-song AI EQ by comparing the 128-bin spectrum against
     * genre/mood target curves, applying smoothing, clamping gains to [-4.0dB, +4.0dB],
     * and calculating anti-clipping preamp adjustment.
     */
    fun calculateAiEq(
        genre: String,
        mood: String,
        spectral128: FloatArray,
        lufs: Float = -14.0f,
        dr: Float = 12.0f,
        bassScore: Float = 0.33f,
        midScore: Float = 0.33f,
        trebleScore: Float = 0.33f
    ): AiEqResult {
        val target = getTargetCurve(genre, mood)
        val measured = measure10BandEnergy(spectral128, bassScore, midScore, trebleScore)

        val rawDeltas = FloatArray(10) { i ->
            target[i] - measured[i]
        }

        // Apply 3-point exponential/moving average smoothing across adjacent bands
        val smoothed = FloatArray(10)
        for (i in 0 until 10) {
            val prev = if (i > 0) rawDeltas[i - 1] else rawDeltas[i]
            val curr = rawDeltas[i]
            val next = if (i < 9) rawDeltas[i + 1] else rawDeltas[i]
            smoothed[i] = 0.25f * prev + 0.50f * curr + 0.25f * next
        }

        // Clamp gains strictly to [-4.0 dB, +4.0 dB]
        val clampedGains = FloatArray(10) { i ->
            smoothed[i].coerceIn(-4.0f, 4.0f)
        }

        // Calculate anti-clipping preamp: min(0, -max_gain)
        var maxGainBoost = 0.0f
        for (g in clampedGains) {
            if (g > maxGainBoost) maxGainBoost = g
        }
        val preampDb = if (maxGainBoost > 0f) -maxGainBoost else 0.0f

        return AiEqResult(clampedGains, preampDb)
    }

    private fun measure10BandEnergy(
        spectral128: FloatArray,
        bassScore: Float,
        midScore: Float,
        trebleScore: Float
    ): FloatArray {
        val bandEnergies = FloatArray(10)

        if (spectral128.size >= 128) {
            for (i in 0 until 10) {
                val range = BAND_BIN_RANGES[i]
                var sum = 0.0f
                var count = 0
                for (bin in range) {
                    if (bin in spectral128.indices) {
                        sum += spectral128[bin]
                        count++
                    }
                }
                bandEnergies[i] = if (count > 0) sum / count else 0.01f
            }
        } else {
            // Fallback estimation using overall spectral scores if 128-bin spectrum unavailable
            for (i in 0..2) bandEnergies[i] = bassScore
            for (i in 3..6) bandEnergies[i] = midScore
            for (i in 7..9) bandEnergies[i] = trebleScore
        }

        // Convert to dB relative to average energy
        var totalEnergy = 0.0f
        for (e in bandEnergies) totalEnergy += e
        val avgEnergy = max(totalEnergy / 10.0f, 1e-6f)

        val relativeDb = FloatArray(10)
        for (i in 0 until 10) {
            val ratio = max(bandEnergies[i], 1e-6f) / avgEnergy
            relativeDb[i] = (20.0f * log10(ratio)).coerceIn(-6.0f, 6.0f)
        }

        return relativeDb
    }

    private fun resolveGenreKey(genre: String): String {
        val normalized = genre.trim().lowercase()
        return when {
            normalized in listOf(
                "tamil", "tamil film music", "tamil mass", "tamil folk",
                "tamil classical", "tamil devotional", "telugu", "malayalam",
                "kannada", "tamil melody"
            ) -> "Tamil Melody"

            normalized in listOf(
                "hindi", "hindi film music", "hindi folk", "hindi classical", "hindi melody"
            ) -> "Hindi Melody"

            normalized in listOf(
                "electronic", "dance", "edm", "house", "techno", "trance"
            ) -> "EDM"

            normalized in listOf(
                "hip-hop", "rap", "trap", "english hip-hop"
            ) -> "Hip-Hop"

            normalized in listOf(
                "pop", "english pop"
            ) -> "Pop"

            normalized in listOf(
                "rock", "metal", "alternative", "indie", "english rock"
            ) -> "Rock"

            normalized in listOf(
                "classical", "jazz", "blues", "acoustic", "soundtrack", "instrumental"
            ) -> "Classical"

            normalized in listOf(
                "r&b", "rnb", "rhythm and blues"
            ) -> "R&B"

            normalized == "soul" -> "Soul"

            normalized in listOf(
                "lo-fi", "lofi"
            ) -> "Lo-Fi"

            normalized == "ambient" -> "Ambient"

            normalized == "country" -> "Country"

            normalized == "reggae" -> "Reggae"

            else -> genre
        }
    }

    private fun getTargetCurve(genre: String, mood: String): FloatArray {
        val target = FloatArray(10) { 0.0f }

        when (resolveGenreKey(genre)) {
            "Pop" -> {
                target[0] = 0.5f; target[1] = 0.5f; target[7] = 0.5f; target[8] = 0.8f; target[9] = 0.5f
            }
            "EDM" -> {
                target[0] = 2.0f; target[1] = 2.0f; target[2] = 1.5f; target[3] = 0.5f
                target[4] = -0.5f; target[7] = 0.8f; target[8] = 1.0f; target[9] = 0.5f
            }
            "Hip-Hop" -> {
                target[0] = 2.5f; target[1] = 2.0f; target[2] = 1.2f; target[3] = 0.5f
                target[4] = -0.3f; target[7] = 0.5f; target[8] = 0.8f
            }
            "Rock" -> {
                target[0] = 1.0f; target[1] = 1.2f; target[2] = 0.8f; target[4] = -0.5f
                target[6] = 0.5f; target[7] = 1.0f; target[8] = 1.2f; target[9] = 0.8f
            }
            "Tamil Melody", "Hindi Melody" -> {
                target[0] = 0.8f; target[1] = 1.0f; target[2] = 0.7f; target[3] = 0.2f
                target[5] = 0.3f; target[6] = 0.5f; target[7] = 0.8f; target[8] = 1.0f; target[9] = 0.6f
            }
            "Classical" -> {
                target[6] = 0.3f; target[7] = 0.5f; target[8] = 0.5f; target[9] = 0.3f
            }
            "R&B" -> {
                target[0] = 1.0f; target[1] = 1.2f; target[2] = 0.8f; target[5] = 0.4f; target[7] = 0.5f; target[8] = 0.8f
            }
            "Soul" -> {
                target[1] = 0.8f; target[2] = 1.0f; target[3] = 0.5f; target[5] = 0.3f; target[8] = 0.5f
            }
            "Lo-Fi" -> {
                target[0] = 0.8f; target[1] = 1.0f; target[2] = 0.6f; target[3] = 0.4f; target[8] = -0.5f; target[9] = -1.0f
            }
            "Ambient" -> {
                target[0] = 0.5f; target[1] = 0.5f; target[4] = -0.3f; target[7] = 0.5f; target[8] = 0.8f; target[9] = 1.0f
            }
            "Country" -> {
                target[1] = 0.5f; target[2] = 0.5f; target[5] = 0.5f; target[6] = 0.6f; target[7] = 0.8f; target[8] = 0.5f
            }
            "Reggae" -> {
                target[0] = 1.5f; target[1] = 1.5f; target[2] = 1.0f; target[6] = 0.5f; target[7] = 0.8f
            }
        }

        val normalizedMood = mood.trim().lowercase()
        when {
            normalizedMood in listOf("energetic", "aggressive", "workout", "party") -> {
                target[0] += 0.5f; target[1] += 0.5f; target[7] += 0.3f; target[8] += 0.3f
            }
            normalizedMood in listOf("calm", "relaxing", "sleep", "meditation") -> {
                target[0] -= 0.5f; target[1] -= 0.5f; target[7] -= 0.5f; target[8] -= 0.5f
            }
            normalizedMood in listOf("sad", "romantic", "emotional") -> {
                target[2] += 0.3f; target[3] += 0.3f; target[8] -= 0.3f
            }
            normalizedMood in listOf("happy", "uplifting", "motivational", "epic") -> {
                target[7] += 0.3f; target[8] += 0.3f; target[1] += 0.3f
            }
            normalizedMood == "dark" -> {
                target[8] -= 0.4f; target[9] -= 0.4f
            }
        }

        return target
    }

    /**
     * Applies learned user preference deltas for (genre, mood) to the baseline AI EQ curve.
     */
    fun applyUserCorrection(
        baseGains: FloatArray,
        correction: AiEqCorrectionEntity?
    ): FloatArray {
        if (correction == null || correction.updateCount <= 0) return baseGains

        val result = FloatArray(10)
        val deltas = floatArrayOf(
            correction.delta31, correction.delta62, correction.delta125,
            correction.delta250, correction.delta500, correction.delta1k,
            correction.delta2k, correction.delta4k, correction.delta8k, correction.delta16k
        )

        for (i in 0 until 10) {
            result[i] = (baseGains[i] + deltas[i]).coerceIn(-4.0f, 4.0f)
        }
        return result
    }

    /**
     * Computes updated running average correction entity when a user manually adjusts EQ sliders.
     */
    fun computeUpdatedCorrection(
        existing: AiEqCorrectionEntity?,
        genre: String,
        mood: String,
        userGains: FloatArray,
        aiGains: FloatArray
    ): AiEqCorrectionEntity {
        val count = (existing?.updateCount ?: 0) + 1
        val alpha = 1.0f / count

        val oldDeltas = floatArrayOf(
            existing?.delta31 ?: 0f, existing?.delta62 ?: 0f, existing?.delta125 ?: 0f,
            existing?.delta250 ?: 0f, existing?.delta500 ?: 0f, existing?.delta1k ?: 0f,
            existing?.delta2k ?: 0f, existing?.delta4k ?: 0f, existing?.delta8k ?: 0f, existing?.delta16k ?: 0f
        )

        val newDeltas = FloatArray(10) { i ->
            val userDelta = (if (i in userGains.indices) userGains[i] else 0f) - (if (i in aiGains.indices) aiGains[i] else 0f)
            oldDeltas[i] + alpha * (userDelta - oldDeltas[i])
        }

        return AiEqCorrectionEntity(
            id = "${genre}_${mood}",
            genre = genre,
            mood = mood,
            delta31 = newDeltas[0],
            delta62 = newDeltas[1],
            delta125 = newDeltas[2],
            delta250 = newDeltas[3],
            delta500 = newDeltas[4],
            delta1k = newDeltas[5],
            delta2k = newDeltas[6],
            delta4k = newDeltas[7],
            delta8k = newDeltas[8],
            delta16k = newDeltas[9],
            updateCount = count,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
