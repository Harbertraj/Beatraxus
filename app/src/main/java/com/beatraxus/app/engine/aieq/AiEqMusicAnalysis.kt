package com.beatraxus.app.engine.aieq

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin tempo (BPM) and musical-key estimation for the AI EQ. The native analyzer only delivers raw
 * material (a spectral-flux "novelty" curve and 12 pitch-class energies); everything that decides the
 * answer lives here so it is deterministic and unit-testable without a device.
 */
object AiEqMusicAnalysis {

    data class Tempo(val bpm: Float, val confidence: Float) {
        val known: Boolean get() = bpm > 0f && confidence > 0f
    }

    data class Key(val tonic: Int, val minor: Boolean, val confidence: Float) {
        val label: String get() = "${NOTE_NAMES[tonic.mod(12)]} ${if (minor) "minor" else "major"}"
    }

    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private const val MIN_BPM = 55f
    private const val MAX_BPM = 190f
    private const val PRIOR_CENTER_BPM = 118f
    private const val PRIOR_SIGMA_OCTAVES = 0.85f
    private const val MIN_CONFIDENCE = 0.15f

    // ---- Tempo ------------------------------------------------------------------------------------------------

    /**
     * Tempo from a spectral-flux curve sampled at [rateHz] values per second.
     *
     * Onset envelope -> autocorrelation -> harmonic-summed, octave-biased peak pick. To make the result
     * stable the clip is also analysed in two halves: when both halves agree with the whole the confidence
     * goes up, when they disagree (a tempo change, or just noise) it goes down.
     */
    fun estimateTempo(novelty: FloatArray?, rateHz: Float): Tempo {
        if (novelty == null || rateHz <= 0f || novelty.size < MIN_FRAMES) return Tempo(0f, 0f)
        val whole = estimateOnce(novelty, rateHz) ?: return Tempo(0f, 0f)
        var confidence = whole.confidence

        val half = novelty.size / 2
        if (half >= MIN_FRAMES) {
            val a = estimateOnce(novelty.copyOfRange(0, half), rateHz)
            val b = estimateOnce(novelty.copyOfRange(half, novelty.size), rateHz)
            if (a != null && b != null) {
                val agree = closeTempo(a.bpm, whole.bpm) && closeTempo(b.bpm, whole.bpm)
                confidence = if (agree) min(1f, confidence * 1.15f + 0.1f) else confidence * 0.75f
            }
        }
        if (confidence < MIN_CONFIDENCE) return Tempo(0f, 0f)
        return Tempo((whole.bpm * 10f).roundToInt() / 10f, confidence.coerceIn(0f, 1f))
    }

    private const val MIN_FRAMES = 96

    private fun closeTempo(a: Float, b: Float) = abs(a - b) <= 0.04f * b

    private fun estimateOnce(novelty: FloatArray, rateHz: Float): Tempo? {
        val n = novelty.size
        // 1. onset envelope: flux minus its local mean (~0.8 s), half-wave rectified, then mean-removed
        val win = max(2, (rateHz * 0.8f).roundToInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + novelty[i]
        val env = DoubleArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - win); val hi = min(n, i + win + 1)
            val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
            env[i] = max(0.0, novelty[i] - mean)
        }
        val envMean = env.average()
        for (i in 0 until n) env[i] -= envMean
        var r0 = 0.0
        for (v in env) r0 += v * v
        if (r0 < 1e-12) return null

        // 2. normalised autocorrelation for every integer lag we may need (up to 4x the slowest period)
        val maxLagNeeded = ceil4(rateHz * 60f / MIN_BPM)
        val maxLag = min(n - 8, maxLagNeeded)
        if (maxLag < 4) return null
        val acf = DoubleArray(maxLag + 2)
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until n - lag) sum += env[i] * env[i + lag]
            acf[lag] = sum / (n - lag) / (r0 / n)
        }
        fun acfAt(lag: Float): Double {
            if (lag < 1f || lag >= maxLag) return 0.0
            val l = lag.toInt(); val f = lag - l
            return acf[l] * (1 - f) + acf[l + 1] * f
        }

        // 3. score candidate tempi in 0.5 BPM steps: the beat period plus its 2x and 4x multiples
        var bestBpm = 0f; var bestScore = Double.NEGATIVE_INFINITY
        var scoreSum = 0.0; var scoreCount = 0
        var bpm = MIN_BPM
        while (bpm <= MAX_BPM) {
            val lag = rateHz * 60f / bpm
            val raw = acfAt(lag) + 0.5 * acfAt(lag * 2f) + 0.25 * acfAt(lag * 4f)
            val octaves = ln(bpm / PRIOR_CENTER_BPM) / ln(2f) / PRIOR_SIGMA_OCTAVES
            val score = raw * exp(-0.5 * octaves * octaves)
            if (score > 0) { scoreSum += score; scoreCount++ }
            if (score > bestScore) { bestScore = score; bestBpm = bpm }
            bpm += 0.5f
        }
        if (bestScore <= 0.0 || scoreCount == 0) return null
        val mean = scoreSum / scoreCount
        val ratio = bestScore / max(mean, 1e-9)
        val confidence = ((ratio - 1.5) / 2.5).toFloat().coerceIn(0f, 1f)
        return Tempo(bestBpm, confidence)
    }

    private fun ceil4(lag: Float): Int = (lag * 4f).toInt() + 2

    // ---- Key --------------------------------------------------------------------------------------------------

    // Krumhansl-Kessler key profiles (index 0 = tonic).
    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Best of the 24 major/minor keys for a 12-value pitch-class profile (C..B); null when it carries no information. */
    fun estimateKey(chroma: FloatArray?): Key? {
        if (chroma == null || chroma.size != 12) return null
        val c = DoubleArray(12) { chroma[it].toDouble().coerceAtLeast(0.0) }
        if (c.sum() < 1e-9) return null
        var best = -2.0; var second = -2.0; var bestTonic = 0; var bestMinor = false
        for (minor in booleanArrayOf(false, true)) {
            val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
            for (tonic in 0 until 12) {
                val r = pearson(c) { i -> profile[(i - tonic).mod(12)] }
                if (r > best) { second = best; best = r; bestTonic = tonic; bestMinor = minor }
                else if (r > second) second = r
            }
        }
        if (best <= 0.0) return null
        val confidence = ((best - 0.45) * 1.5 + (best - second) * 3.0).toFloat().coerceIn(0f, 1f)
        return Key(bestTonic, bestMinor, confidence)
    }

    private inline fun pearson(x: DoubleArray, y: (Int) -> Double): Double {
        val n = x.size
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y(i) }
        mx /= n; my /= n
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx; val dy = y(i) - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        val d = sqrt(sxx * syy)
        return if (d < 1e-12) 0.0 else sxy / d
    }

    /** Frequency (Hz) of the key's tonic folded into the 40-80 Hz octave - where it sits in the bass bands. */
    fun tonicBassHz(tonic: Int): Float {
        var f = 440f * 2f.pow((tonic.mod(12) - 9) / 12f)
        while (f >= 80f) f /= 2f
        while (f < 40f) f *= 2f
        return f
    }
}
