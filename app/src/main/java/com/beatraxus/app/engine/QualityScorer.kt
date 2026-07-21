package com.beatraxus.app.engine

import kotlin.math.abs

/**
 * Pure, unit-testable audio-quality scoring function.
 *
 * Combines resolution (bitrate/sample rate/bit depth/codec), loudness sanity, dynamic
 * range, clipping, and stereo balance into a single 0-100 score, plus a human-readable
 * tier label. Used both by the library scan pipeline (to populate SongQualityEntity)
 * and by the Music Detail Inspector screen.
 */
object QualityScorer {

    // Documented tier thresholds (avoid magic numbers scattered inline).
    private const val TIER_EXCELLENT_MIN = 85
    private const val TIER_GOOD_MIN = 65
    private const val TIER_FAIR_MIN = 40

    // Lossless/hi-res codec families get full codec credit regardless of bitrate.
    private val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD", "DSF", "PCM")

    data class Result(val score: Int, val tier: String)

    fun score(
        bitrateKbps: Int,
        sampleRateHz: Int,
        bitDepth: Int,
        codec: String,
        lufs: Float,
        dynamicRange: Float,
        truePeakDb: Float,
        clippedSamplePct: Float,
        stereoWidth: Float
    ): Result {
        val resolutionScore = resolutionScore(bitrateKbps, sampleRateHz, bitDepth, codec) // 0-1
        val loudnessScore = loudnessScore(lufs) // 0-1
        val dynamicRangeScore = dynamicRangeScore(dynamicRange) // 0-1
        val clippingScore = clippingScore(clippedSamplePct, truePeakDb) // 0-1
        val stereoScore = stereoScore(stereoWidth) // 0-1

        val total = (resolutionScore * 30f) +
            (loudnessScore * 25f) +
            (dynamicRangeScore * 20f) +
            (clippingScore * 15f) +
            (stereoScore * 10f)

        val clamped = total.coerceIn(0f, 100f).toInt()
        return Result(clamped, tierFor(clamped))
    }

    fun tierFor(score: Int): String = when {
        score >= TIER_EXCELLENT_MIN -> "Excellent"
        score >= TIER_GOOD_MIN -> "Good"
        score >= TIER_FAIR_MIN -> "Fair"
        else -> "Poor"
    }

    // --- 30%: resolution (bitrate + sample rate + bit depth vs lossless/hi-res thresholds) ---
    private fun resolutionScore(bitrateKbps: Int, sampleRateHz: Int, bitDepth: Int, codec: String): Float {
        val isLossless = LOSSLESS_CODECS.any { codec.uppercase().contains(it) }

        // Codec credit: lossless/hi-res formats get full marks; lossy scales with bitrate.
        val codecCredit = if (isLossless) 1.0f else (bitrateKbps / 320f).coerceIn(0f, 1f)

        // Bitrate credit only meaningfully applies to lossy formats; lossless already
        // gets full marks here since bitrate is a byproduct of sample rate/bit depth, not
        // an independent quality signal.
        val bitrateCredit = if (isLossless) 1.0f else {
            when {
                bitrateKbps >= 320 -> 1.0f
                bitrateKbps >= 256 -> 0.85f
                bitrateKbps >= 192 -> 0.65f
                bitrateKbps >= 128 -> 0.4f
                bitrateKbps > 0 -> 0.15f
                else -> 0.3f // unknown bitrate — don't zero it out
            }
        }

        // Sample rate credit: 44.1/48kHz = full "standard" marks, hi-res (>48kHz) tops out.
        val sampleRateCredit = when {
            sampleRateHz >= 88200 -> 1.0f
            sampleRateHz >= 44100 -> 0.9f
            sampleRateHz >= 22050 -> 0.5f
            sampleRateHz > 0 -> 0.25f
            else -> 0.5f
        }

        // Bit depth credit: 16-bit = standard full marks, 24-bit+ = hi-res bonus.
        val bitDepthCredit = when {
            bitDepth >= 24 -> 1.0f
            bitDepth >= 16 -> 0.85f
            bitDepth > 0 -> 0.5f
            else -> 0.85f
        }

        return (codecCredit * 0.4f) + (bitrateCredit * 0.3f) + (sampleRateCredit * 0.15f) + (bitDepthCredit * 0.15f)
    }

    // --- 25%: loudness sanity (healthy streaming-normalized range: -18 to -8 LUFS) ---
    private fun loudnessScore(lufs: Float): Float {
        val healthyLow = -18f
        val healthyHigh = -8f
        return when {
            lufs in healthyLow..healthyHigh -> 1.0f
            lufs < healthyLow -> {
                // Too quiet: linear falloff down to -35 LUFS
                (1.0f - (healthyLow - lufs) / 17f).coerceIn(0f, 1f)
            }
            else -> {
                // Too loud / over-compressed: linear falloff up to 0 LUFS
                (1.0f - (lufs - healthyHigh) / 8f).coerceIn(0f, 1f)
            }
        }
    }

    // --- 20%: dynamic range (< 6 dB poor, > 12 dB excellent) ---
    private fun dynamicRangeScore(dynamicRange: Float): Float {
        return when {
            dynamicRange >= 12f -> 1.0f
            dynamicRange <= 6f -> (dynamicRange / 6f).coerceIn(0f, 1f) * 0.4f
            else -> 0.4f + (dynamicRange - 6f) / 6f * 0.6f
        }
    }

    // --- 15%: clipping (clipped samples + true peak > -1.0 dBFS both penalize heavily) ---
    private fun clippingScore(clippedSamplePct: Float, truePeakDb: Float): Float {
        var s = 1.0f

        // Any meaningful clipping caps the score noticeably.
        s -= when {
            clippedSamplePct <= 0.0f -> 0.0f
            clippedSamplePct < 0.01f -> 0.1f
            clippedSamplePct < 0.1f -> 0.35f
            clippedSamplePct < 1.0f -> 0.6f
            else -> 0.9f
        }

        // True-peak overs (inter-sample clipping risk) also penalize.
        if (truePeakDb > -1.0f) {
            s -= if (truePeakDb > 0f) 0.4f else 0.2f
        }

        return s.coerceIn(0f, 1f)
    }

    // --- 10%: stereo balance (near-0 = collapsed mono, extremely high = phase issues) ---
    private fun stereoScore(stereoWidth: Float): Float {
        val w = abs(stereoWidth)
        return when {
            w < 0.02f -> 0.3f // essentially collapsed to mono
            w in 0.02f..1.5f -> 1.0f // healthy stereo image
            w in 1.5f..2.5f -> 0.6f // wide, possibly phase-y
            else -> 0.3f // likely phase/correlation issues
        }
    }
}
