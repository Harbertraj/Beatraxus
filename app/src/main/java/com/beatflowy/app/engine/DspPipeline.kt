package com.beatflowy.app.engine

import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.ParametricEqBand
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class DspProcessResult(
    val data: FloatArray,
    val sampleCount: Int,
    val sampleRate: Int
)

internal interface DspProcessor {
    fun process(input: DspProcessResult, channels: Int): DspProcessResult
}

internal class AudioDspPipeline(
    private val processors: List<DspProcessor>
) {
    fun process(data: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int): DspProcessResult {
        var current = DspProcessResult(data = data, sampleCount = sampleCount, sampleRate = sampleRate)
        processors.forEach { processor ->
            current = processor.process(current, channels)
        }
        return current
    }

    companion object {
        fun create(
            inputSampleRate: Int,
            outputSampleRate: Int,
            channels: Int,
            config: DspConfig
        ): AudioDspPipeline {
            val processors = mutableListOf<DspProcessor>()
            val effectiveOutputRate = if (config.resamplerEnabled) outputSampleRate else inputSampleRate
            if (config.resamplerEnabled && inputSampleRate > 0 && outputSampleRate > 0 && inputSampleRate != outputSampleRate) {
                processors += WindowedSincResamplerProcessor(
                    inputSampleRate = inputSampleRate,
                    outputSampleRate = outputSampleRate,
                    channels = channels,
                    cutoffRatio = config.resamplerCutoffRatio
                )
            }

            val effectivePreamp = (if (config.preampEnabled) config.preampDb else 0f) +
                (if (config.autoEqEnabled) config.autoEqProfile?.preampDb ?: 0f else 0f)
            if (effectivePreamp != 0f) {
                processors += GainProcessor(dbToLinear(effectivePreamp))
            }

            val eqFilters = mutableListOf<StereoBiquad>()
            if (config.eqEnabled) {
                config.eqBands.forEach { band ->
                    if (band.enabled && band.gainDb != 0f) {
                        eqFilters += StereoBiquad.peaking(effectiveOutputRate, band)
                    }
                }
            }
            if (config.autoEqEnabled) {
                config.autoEqProfile?.bands?.forEach { band ->
                    if (band.enabled && band.gainDb != 0f) {
                        eqFilters += StereoBiquad.peaking(effectiveOutputRate, band)
                    }
                }
            }
            if (config.bassEnabled && config.bassDb != 0f) {
                eqFilters += StereoBiquad.lowShelf(effectiveOutputRate, 105f, config.bassDb, 0.7f)
            }
            if (config.trebleEnabled && config.trebleDb != 0f) {
                eqFilters += StereoBiquad.highShelf(effectiveOutputRate, 8000f, config.trebleDb, 0.7f)
            }
            if (eqFilters.isNotEmpty()) {
                processors += FilterChainProcessor(eqFilters)
            }

            if (config.balanceEnabled && config.balance != 0f) {
                processors += BalanceProcessor(config.balance)
            }
            if (channels >= 2 && config.stereoExpansionEnabled && config.stereoWidth != 1f) {
                processors += StereoWidthProcessor(config.stereoWidth)
            }
            if (channels >= 2 && config.reverbEnabled && config.reverbAmount > 0f) {
                processors += ReverbProcessor(effectiveOutputRate, config.reverbAmount)
            }

            return AudioDspPipeline(processors)
        }

        private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    }
}

private class WindowedSincResamplerProcessor(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val channels: Int,
    private val cutoffRatio: Float
) : DspProcessor {
    private val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
    private val taps = 32
    private val phases = 1024
    private val filterTable = buildFilterTable()

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        if (input.sampleRate == outputSampleRate || input.sampleCount <= 0) return input
        val inputFrames = input.sampleCount / channels
        if (inputFrames <= 1) return input.copy(sampleRate = outputSampleRate)

        val outputFrames = ceil(inputFrames * ratio).toInt()
        val out = FloatArray(outputFrames * channels)
        for (frame in 0 until outputFrames) {
            val sourcePosition = frame / ratio
            val center = floor(sourcePosition).toInt()
            val frac = sourcePosition - center
            val phaseIndex = min(phases - 1, max(0, (frac * phases).toInt()))
            val kernel = filterTable[phaseIndex]
            val start = center - taps / 2 + 1
            for (channel in 0 until channels) {
                var acc = 0.0
                for (tap in 0 until taps) {
                    val sourceIndex = (start + tap).coerceIn(0, inputFrames - 1)
                    val sample = input.data[sourceIndex * channels + channel]
                    acc += sample * kernel[tap]
                }
                out[frame * channels + channel] = acc.toFloat()
            }
        }
        return DspProcessResult(
            data = out,
            sampleCount = out.size,
            sampleRate = outputSampleRate
        )
    }

    private fun buildFilterTable(): Array<DoubleArray> {
        val normalizedCutoff = if (outputSampleRate > inputSampleRate) {
            0.5 * cutoffRatio
        } else {
            0.5 * ratio * cutoffRatio
        }
        return Array(phases) { phase ->
            val frac = phase.toDouble() / phases.toDouble()
            val kernel = DoubleArray(taps)
            var sum = 0.0
            for (tap in 0 until taps) {
                val distance = tap - taps / 2 + 1 - frac
                val sinc = if (distance == 0.0) {
                    2.0 * normalizedCutoff
                } else {
                    sin(2.0 * PI * normalizedCutoff * distance) / (PI * distance)
                }
                val window = 0.42 -
                    0.5 * cos((2.0 * PI * tap) / (taps - 1)) +
                    0.08 * cos((4.0 * PI * tap) / (taps - 1))
                val value = sinc * window
                kernel[tap] = value
                sum += value
            }
            if (sum != 0.0) {
                for (tap in kernel.indices) {
                    kernel[tap] /= sum
                }
            }
            kernel
        }
    }
}

private class GainProcessor(private val gain: Float) : DspProcessor {
    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        for (index in 0 until input.sampleCount) {
            data[index] *= gain
        }
        return input.copy(data = data)
    }
}

private class FilterChainProcessor(
    private val filters: List<StereoBiquad>
) : DspProcessor {
    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        val frameCount = input.sampleCount / channels
        for (frame in 0 until frameCount) {
            val offset = frame * channels
            var left = data[offset]
            var right = if (channels > 1) data[offset + 1] else left
            filters.forEach { filter ->
                left = filter.processLeft(left)
                right = filter.processRight(right)
            }
            data[offset] = left
            if (channels > 1) data[offset + 1] = right
        }
        return input.copy(data = data)
    }
}

private class BalanceProcessor(private val balance: Float) : DspProcessor {
    private val leftGain = if (balance > 0f) 1f - balance else 1f
    private val rightGain = if (balance < 0f) 1f + balance else 1f

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        if (channels < 2) return input
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        val frameCount = input.sampleCount / channels
        for (frame in 0 until frameCount) {
            val offset = frame * channels
            data[offset] *= leftGain
            data[offset + 1] *= rightGain
        }
        return input.copy(data = data)
    }
}

private class StereoWidthProcessor(private val width: Float) : DspProcessor {
    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        if (channels < 2) return input
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        val frameCount = input.sampleCount / channels
        for (frame in 0 until frameCount) {
            val offset = frame * channels
            val left = data[offset]
            val right = data[offset + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * width
            data[offset] = mid + side
            data[offset + 1] = mid - side
        }
        return input.copy(data = data)
    }
}

private class ReverbProcessor(
    sampleRate: Int,
    amount: Float
) : DspProcessor {
    private val wet = amount.coerceIn(0f, 1f) * 0.45f
    private val feedback = 0.15f + (amount.coerceIn(0f, 1f) * 0.55f)
    private val leftDelay = max(1, (sampleRate * (0.021f + amount * 0.036f)).toInt())
    private val rightDelay = max(1, (sampleRate * (0.029f + amount * 0.041f)).toInt())
    private val leftBuffer = FloatArray(leftDelay)
    private val rightBuffer = FloatArray(rightDelay)
    private var leftIndex = 0
    private var rightIndex = 0

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        if (channels < 2) return input
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        val frameCount = input.sampleCount / channels
        for (frame in 0 until frameCount) {
            val offset = frame * channels
            val dryL = data[offset]
            val dryR = data[offset + 1]
            val delayedL = leftBuffer[leftIndex]
            val delayedR = rightBuffer[rightIndex]
            leftBuffer[leftIndex] = dryL + delayedR * feedback
            rightBuffer[rightIndex] = dryR + delayedL * feedback
            leftIndex = (leftIndex + 1) % leftBuffer.size
            rightIndex = (rightIndex + 1) % rightBuffer.size
            data[offset] = dryL * (1f - wet) + delayedL * wet
            data[offset + 1] = dryR * (1f - wet) + delayedR * wet
        }
        return input.copy(data = data)
    }
}

private class StereoBiquad(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float
) {
    private var z1Left = 0f
    private var z2Left = 0f
    private var z1Right = 0f
    private var z2Right = 0f

    fun processLeft(sample: Float): Float {
        val out = sample * b0 + z1Left
        z1Left = sample * b1 + z2Left - a1 * out
        z2Left = sample * b2 - a2 * out
        return out
    }

    fun processRight(sample: Float): Float {
        val out = sample * b0 + z1Right
        z1Right = sample * b1 + z2Right - a1 * out
        z2Right = sample * b2 - a2 * out
        return out
    }

    companion object {
        fun peaking(sampleRate: Int, band: ParametricEqBand): StereoBiquad {
            val a = 10.0.pow((band.gainDb / 40f).toDouble()).toFloat()
            val w0 = (2.0 * PI * band.frequencyHz / sampleRate).toFloat()
            val alpha = (sin(w0) / (2f * band.q.coerceAtLeast(0.1f)))
            val cosW0 = cos(w0)
            val b0 = 1f + alpha * a
            val b1 = -2f * cosW0
            val b2 = 1f - alpha * a
            val a0 = 1f + alpha / a
            val a1 = -2f * cosW0
            val a2 = 1f - alpha / a
            return normalize(b0, b1, b2, a0, a1, a2)
        }

        fun lowShelf(sampleRate: Int, frequencyHz: Float, gainDb: Float, slope: Float): StereoBiquad {
            val a = 10.0.pow((gainDb / 40f).toDouble()).toFloat()
            val w0 = (2.0 * PI * frequencyHz / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt((a + 1f / a) * (1f / slope - 1f) + 2f)
            val beta = 2f * sqrt(a) * alpha
            val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + beta)
            val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
            val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - beta)
            val a0 = (a + 1f) + (a - 1f) * cosW0 + beta
            val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
            val a2 = (a + 1f) + (a - 1f) * cosW0 - beta
            return normalize(b0, b1, b2, a0, a1, a2)
        }

        fun highShelf(sampleRate: Int, frequencyHz: Float, gainDb: Float, slope: Float): StereoBiquad {
            val a = 10.0.pow((gainDb / 40f).toDouble()).toFloat()
            val w0 = (2.0 * PI * frequencyHz / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt((a + 1f / a) * (1f / slope - 1f) + 2f)
            val beta = 2f * sqrt(a) * alpha
            val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + beta)
            val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
            val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - beta)
            val a0 = (a + 1f) - (a - 1f) * cosW0 + beta
            val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
            val a2 = (a + 1f) - (a - 1f) * cosW0 - beta
            return normalize(b0, b1, b2, a0, a1, a2)
        }

        private fun normalize(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float): StereoBiquad {
            return StereoBiquad(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0
            )
        }
    }
}
