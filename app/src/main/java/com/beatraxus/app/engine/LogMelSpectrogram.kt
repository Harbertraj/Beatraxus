package com.beatraxus.app.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Computes Log-Mel Spectrograms from PCM audio or 128-bin spectral magnitude vectors.
 * Designed for on-device ML model preprocessing (e.g. YAMNet, MusiCNN).
 */
class LogMelSpectrogram(
    val sampleRate: Int = 16000,
    val fftSize: Int = 512,
    val hopLength: Int = 160,
    val numMelBins: Int = 64,
    val minFreqHz: Float = 125.0f,
    val maxFreqHz: Float = 7500.0f
) {
    private val filterbank: Array<FloatArray> = createMelFilterbank()

    fun hzToMel(hz: Float): Float = 2595.0f * log10(1.0f + hz / 700.0f)
    fun melToHz(mel: Float): Float = 700.0f * (10.0f.pow(mel / 2595.0f) - 1.0f)

    /**
     * Constructs a triangular Mel-scale filterbank matrix of size [numMelBins x (fftSize/2 + 1)].
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        val numFftBins = fftSize / 2 + 1
        val minMel = hzToMel(minFreqHz)
        val maxMel = hzToMel(maxFreqHz.coerceAtMost(sampleRate / 2.0f))

        // Linearly spaced points in Mel scale
        val melPoints = FloatArray(numMelBins + 2)
        val melStep = (maxMel - minMel) / (numMelBins + 1)
        for (i in melPoints.indices) {
            melPoints[i] = minMel + i * melStep
        }

        // Convert Mel points to FFT bin indices
        val binIndices = IntArray(numMelBins + 2)
        for (i in binIndices.indices) {
            val hz = melToHz(melPoints[i])
            val bin = (numFftBins - 1) * (hz / (sampleRate / 2.0f))
            binIndices[i] = bin.toInt().coerceIn(0, numFftBins - 1)
        }

        // Construct triangular filterbank
        val bank = Array(numMelBins) { FloatArray(numFftBins) }
        for (m in 0 until numMelBins) {
            val leftBin = binIndices[m]
            val centerBin = binIndices[m + 1]
            val rightBin = binIndices[m + 2]

            // Rising slope
            for (k in leftBin until centerBin) {
                val denom = (centerBin - leftBin).coerceAtLeast(1)
                bank[m][k] = (k - leftBin).toFloat() / denom
            }
            // Falling slope
            for (k in centerBin until rightBin) {
                val denom = (rightBin - centerBin).coerceAtLeast(1)
                bank[m][k] = (rightBin - k).toFloat() / denom
            }
        }
        return bank
    }

    /**
     * Converts raw PCM samples to a log-mel spectrogram [numFrames x numMelBins].
     */
    fun compute(pcm: FloatArray): Array<FloatArray> {
        if (pcm.isEmpty()) return emptyArray()

        val numFftBins = fftSize / 2 + 1
        val numFrames = ((pcm.size - fftSize) / hopLength).coerceAtLeast(0) + 1
        val result = Array(numFrames) { FloatArray(numMelBins) }

        val hannWindow = FloatArray(fftSize) { i ->
            0.5f * (1.0f - cos(2.0 * Math.PI * i / (fftSize - 1)).toFloat())
        }

        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val magnitude = FloatArray(numFftBins)

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            // Windowing
            for (i in 0 until fftSize) {
                val sampleIdx = start + i
                real[i] = if (sampleIdx < pcm.size) pcm[sampleIdx] * hannWindow[i] else 0.0f
                imag[i] = 0.0f
            }

            // Simple Discrete Fourier Transform for positive spectrum
            for (k in 0 until numFftBins) {
                var sumReal = 0.0f
                var sumImag = 0.0f
                val angleFactor = -2.0 * Math.PI * k / fftSize
                for (n in 0 until fftSize) {
                    val angle = angleFactor * n
                    sumReal += real[n] * cos(angle).toFloat()
                    sumImag += real[n] * Math.sin(angle).toFloat()
                }
                magnitude[k] = sqrt(sumReal * sumReal + sumImag * sumImag)
            }

            // Apply filterbank & log scaling: log(mel + 1e-6)
            for (m in 0 until numMelBins) {
                var melEnergy = 0.0f
                for (k in 0 until numFftBins) {
                    melEnergy += magnitude[k] * filterbank[m][k]
                }
                result[frame][m] = log10(melEnergy.coerceAtLeast(1e-6f) + 1e-6f)
            }
        }

        return result
    }

    /**
     * Interpolates or maps a 128-bin spectrum extracted by C++ into log-mel feature bins.
     */
    fun computeFrom128BinSpectrum(spectral128: FloatArray): FloatArray {
        if (spectral128.isEmpty()) return FloatArray(numMelBins) { -6.0f }

        val melOutput = FloatArray(numMelBins)
        val numFftBins = (fftSize / 2 + 1).coerceAtMost(spectral128.size)

        for (m in 0 until numMelBins) {
            var melEnergy = 0.0f
            for (k in 0 until numFftBins) {
                val specIdx = (k * spectral128.size / numFftBins).coerceIn(0, spectral128.size - 1)
                melEnergy += spectral128[specIdx] * filterbank[m][k]
            }
            melOutput[m] = log10(melEnergy.coerceAtLeast(1e-6f) + 1e-6f)
        }
        return melOutput
    }
}
