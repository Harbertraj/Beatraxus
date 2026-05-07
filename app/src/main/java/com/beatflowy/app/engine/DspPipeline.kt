package com.beatflowy.app.engine

import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ReplayGainOption
import com.beatflowy.app.model.ReplayGainSource
import com.beatflowy.app.model.ResamplerType
import com.beatflowy.app.model.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

import kotlin.math.*

internal data class DspProcessResult(
    val data: FloatArray,
    val sampleCount: Int,
    val sampleRate: Int
)

internal interface DspProcessor {
    fun process(input: DspProcessResult, channels: Int): DspProcessResult
    fun updateConfig(config: DspConfig) {}
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

    fun updateConfig(config: DspConfig) {
        processors.forEach { it.updateConfig(config) }
    }

    companion object {
        fun create(
            inputSampleRate: Int,
            outputSampleRate: Int,
            channels: Int,
            config: DspConfig,
            song: Song?
        ): AudioDspPipeline {
            val processors = mutableListOf<DspProcessor>()
            val effectiveInputRate = inputSampleRate.coerceAtLeast(8_000)

            // If DVC is enabled, we use the new Native Poweramp-style engine
            if (config.dvcEnabled) {
                // NATIVE ENGINE (Handles ReplayGain, Preamp, Tone, EQ, Spatial, Resampling, Volume, Limiter)
                processors += NativeDspProcessor(config, effectiveInputRate, outputSampleRate, channels, song)

                return AudioDspPipeline(processors)
            }

            // --- LEGACY BIQUAD PIPELINE ---
            // 1. PREAMP (-6dB default headroom)
            val baseHeadroom = -6.0f
            val preampDb = if (config.preampEnabled) config.preampDb + baseHeadroom else baseHeadroom
            processors += GainProcessor(dbToLinear(preampDb))

            // 2. REPLAY GAIN
            val replayGainState = ReplayGainState.from(config, song)
            if (abs(replayGainState.gainDb) > 0.01f) {
                processors += GainProcessor(dbToLinear(replayGainState.gainDb))
            }

            // 3. DSP CHAIN (Biquad Filters)
            val eqFilters = mutableListOf<StereoBiquad>()
            val effectiveEqBands = if (config.eqEnabled) {
                config.eqBands
            } else {
                emptyList()
            }

            effectiveEqBands.forEach { band ->
                if (band.enabled && abs(band.gainDb) > 0.01f) {
                    eqFilters += StereoBiquad.peaking(effectiveInputRate, band)
                }
            }

            if (config.bassEnabled && abs(config.bassDb) > 0.01f) {
                eqFilters += StereoBiquad.lowShelf(effectiveInputRate, 105f, config.bassDb, 0.7f)
            }
            if (config.trebleEnabled && abs(config.trebleDb) > 0.01f) {
                eqFilters += StereoBiquad.highShelf(effectiveInputRate, 8_000f, config.trebleDb, 0.7f)
            }
            
            if (eqFilters.isNotEmpty()) {
                processors += FilterChainProcessor(eqFilters)
            }

            // 4. STEREO PROCESSING
            if (channels >= 2 && config.balanceEnabled && abs(config.balance) > 0.001f) {
                processors += BalanceProcessor(config.balance)
            }
            if (channels >= 2 && config.stereoExpansionEnabled && abs(config.stereoWidth - 1f) > 0.001f) {
                processors += StereoWidthProcessor(config.stereoWidth)
            }

            // 5. COMPRESSOR / LIMITER
            processors += SoftClipLimiterProcessor(threshold = 0.95f)

            // 6. RESAMPLER
            if (inputSampleRate != outputSampleRate) {
                processors += WindowedSincResamplerProcessor(
                    inputSampleRate = inputSampleRate,
                    outputSampleRate = outputSampleRate,
                    cutoffRatio = config.resamplerCutoffRatio
                )
            }

            // 7. VOLUME STAGE
            processors += DvcVolumeProcessor(config.dvcLevel)

            return AudioDspPipeline(processors)
        }

        private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    }
}

/**
 * High-precision Biquad filter using Double for internal state to prevent 
 * coefficient quantization noise and instability at low frequencies.
 */
private class StereoBiquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1L = 0.0; private var z2L = 0.0
    private var z1R = 0.0; private var z2R = 0.0

    fun processLeft(sample: Float): Float {
        val s = sample.toDouble()
        val out = s * b0 + z1L
        z1L = s * b1 + z2L - a1 * out
        z2L = s * b2 - a2 * out
        return out.toFloat()
    }

    fun processRight(sample: Float): Float {
        val s = sample.toDouble()
        val out = s * b0 + z1R
        z1R = s * b1 + z2R - a1 * out
        z2R = s * b2 - a2 * out
        return out.toFloat()
    }

    companion object {
        private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            StereoBiquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun peaking(sr: Int, band: ParametricEqBand): StereoBiquad {
            val a = 10.0.pow(band.gainDb.toDouble() / 40.0)
            val w0 = 2.0 * PI * band.frequencyHz.toDouble() / sr
            val alpha = sin(w0) / (2.0 * band.q.toDouble().coerceAtLeast(0.1))
            val cosW0 = cos(w0)
            return normalize(1.0 + alpha * a, -2.0 * cosW0, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cosW0, 1.0 - alpha / a)
        }

        fun lowShelf(sr: Int, freq: Float, gain: Float, slope: Float): StereoBiquad {
            val a = 10.0.pow(gain.toDouble() / 40.0)
            val w0 = 2.0 * PI * freq.toDouble() / sr
            val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope.toDouble() - 1.0) + 2.0)
            val cosW0 = cos(w0)
            return normalize(
                a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0),
                a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha),
                (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha,
                -2.0 * ((a - 1.0) + (a + 1.0) * cosW0),
                (a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha
            )
        }

        fun highShelf(sr: Int, freq: Float, gain: Float, slope: Float): StereoBiquad {
            val a = 10.0.pow(gain.toDouble() / 40.0)
            val w0 = 2.0 * PI * freq.toDouble() / sr
            val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope.toDouble() - 1.0) + 2.0)
            val cosW0 = cos(w0)
            return normalize(
                a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0),
                a * ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha),
                (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha,
                2.0 * ((a - 1.0) - (a + 1.0) * cosW0),
                (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha
            )
        }
    }
}


private class NativeDspProcessor(
    private val config: DspConfig,
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val channels: Int,
    private val song: Song?
) : DspProcessor {
    private val defaultEqFreqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private val native = NativeDsp().also { dsp ->
        // Sample Rate
        dsp.initResampler(inputSampleRate.toFloat(), channels, outputSampleRate.toFloat())
        
        // Initial config sync
        updateNativeConfig(config, dsp)
    }

    private fun updateNativeConfig(cfg: DspConfig, dsp: NativeDsp) {
        // Replay Gain
        val rg = ReplayGainState.from(cfg, song)
        dsp.setReplayGain(rg.gainDb)

        // DVC (Digital Volume Control)
        dsp.setVolume(cfg.dvcLevel)
        dsp.setDvc(cfg.dvcEnabled)
        
        // Resampler type
        dsp.setHighQualityResampler(cfg.highQualityResampler)
        dsp.setCutoffRatio(cfg.resamplerCutoffRatio)
        
        // EQ / Tone
        dsp.setTone(
            if (cfg.bassEnabled) cfg.bassDb else 0.0f,
            if (cfg.midBassEnabled) cfg.midBassDb else 0.0f,
            if (cfg.trebleEnabled) cfg.trebleDb else 0.0f,
            if (cfg.airEnabled) cfg.airDb else 0.0f
        )
        
        // Spatial
        dsp.setSpatial(
            if (cfg.balanceEnabled) cfg.balance else 0.0f,
            if (cfg.stereoExpansionEnabled) cfg.stereoWidth else 1.0f
        )

        // Reverb
        dsp.setReverb(if (cfg.reverbEnabled) cfg.reverbAmount else 0.0f)
        val reverbType = when (cfg.reverbPreset) {
            "ROOM" -> 1
            "HALL" -> 2
            "PLATE" -> 3
            "CATHEDRAL" -> 4
            else -> 0 // FLAT
        }
        dsp.setReverbType(reverbType)

        // Limiter
        dsp.setLimiter(cfg.limiterEnabled)

        // Preamp
        val preampDb = (if (cfg.preampEnabled) cfg.preampDb else 0f)
        dsp.setPreamp(preampDb)
        
        // Sync EQ bands
        val eqBandsToSync = if (cfg.eqEnabled) {
            if (cfg.autoEqEnabled && cfg.autoEqProfile != null) {
                cfg.autoEqProfile.bands
            } else {
                cfg.eqBands
            }
        } else {
            emptyList()
        }

        for (index in 0 until 32) {
            if (index < eqBandsToSync.size) {
                val band = eqBandsToSync[index]
                dsp.setBand(index, band.frequencyHz, if (band.enabled) band.gainDb else 0f, band.q)
            } else {
                // Reset band if EQ disabled or out of range
                val freq = if (index < defaultEqFreqs.size) defaultEqFreqs[index] else 1000f * (index - 9)
                dsp.setBand(index, freq, 0f, 1.0f)
            }
        }
    }

    private var outputBuffer = FloatArray(0)

    override fun updateConfig(config: DspConfig) {
        updateNativeConfig(config, native)
    }

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        // Calculate max possible output size with 20% headroom for resampling jitter
        val ratio = outputSampleRate.toFloat() / inputSampleRate.toFloat()
        val maxFrames = (input.sampleCount / channels * ratio * 1.2f).toInt() + 128
        val requiredSize = maxFrames * channels
        
        if (outputBuffer.size < requiredSize) {
            outputBuffer = FloatArray(requiredSize)
        }

        val outFrames = native.processResampled(input.data, input.sampleCount / channels, outputBuffer)
        
        // We return a copy to avoid subsequent processors potentially messing with the member buffer
        // although in a sequential pipeline it might be optimized.
        return DspProcessResult(
            data = outputBuffer.copyOf(outFrames * channels),
            sampleCount = outFrames * channels,
            sampleRate = outputSampleRate
        )
    }
}

private class SoftClipLimiterProcessor(private val threshold: Float) : DspProcessor {
    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val data = input.data
        for (i in 0 until input.sampleCount) {
            val s = data[i]
            val absS = abs(s)
            if (absS > threshold) {
                // Soft knee cubic clipping
                val over = (absS - threshold) / (1.0f - threshold)
                val shape = over - (over.pow(3) / 3.0f)
                data[i] = sign(s) * (threshold + shape * (1.0f - threshold))
            }
        }
        return input
    }
}

private class DvcVolumeProcessor(private var targetGain: Float) : DspProcessor {
    private var currentGain = -1f // Initialize on first use

    override fun updateConfig(config: DspConfig) {
        this.targetGain = config.dvcLevel
    }

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        if (currentGain < 0f) currentGain = targetGain
        val data = input.data
        if (abs(currentGain - targetGain) < 0.0001f) {
            for (i in 0 until input.sampleCount) data[i] *= targetGain
        } else {
            // Smooth ramping to prevent clicks
            val step = (targetGain - currentGain) / input.sampleCount
            for (i in 0 until input.sampleCount) {
                currentGain += step
                data[i] *= currentGain
            }
        }
        return input
    }
}


private data class ReplayGainState(
    val gainDb: Float = 0f,
    val preventClipping: Boolean = false,
    val limit: Float = 1f
) {
    companion object {
        fun from(config: DspConfig, song: Song?): ReplayGainState {
            if (!config.replayGainEnabled || song == null) return ReplayGainState()
            val baseGain = when (config.replayGainSource) {
                ReplayGainSource.TRACK -> song.replayGainTrackDb
                ReplayGainSource.ALBUM -> song.replayGainAlbumDb
            } ?: 0f
            val peak = when (config.replayGainSource) {
                ReplayGainSource.TRACK -> song.replayGainTrackPeak
                ReplayGainSource.ALBUM -> song.replayGainAlbumPeak
            } ?: 1f
            val totalGainDb = baseGain + config.replayGainPreamp
            val linearGain = 10f.pow(totalGainDb / 20f)
            val safeLimit = if (peak > 0f) (1f / peak).coerceAtMost(1f) else 1f
            return if (config.replayGainOption == ReplayGainOption.APPLY_GAIN_PREVENT_CLIPPING && linearGain > safeLimit) {
                ReplayGainState(
                    gainDb = 20f * kotlin.math.log10(safeLimit),
                    preventClipping = true,
                    limit = safeLimit
                )
            } else {
                ReplayGainState(
                    gainDb = totalGainDb,
                    preventClipping = config.replayGainOption == ReplayGainOption.APPLY_GAIN_PREVENT_CLIPPING,
                    limit = safeLimit
                )
            }
        }
    }
}

private class GainProcessor(private var gain: Float) : DspProcessor {
    override fun updateConfig(config: DspConfig) {
        // Preamp handling is complex in legacy pipeline due to headroom
        // but we can update the base gain if needed.
    }

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        for (index in 0 until input.sampleCount) {
            data[index] *= gain
        }
        return input.copy(data = data)
    }
}

private class FilterChainProcessor(
    private var filters: List<StereoBiquad>
) : DspProcessor {
    override fun updateConfig(config: DspConfig) {
        // In legacy mode, we don't have easy access to inputSampleRate here
        // so real-time legacy EQ updates are limited.
    }

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

private class BalanceProcessor(balance: Float) : DspProcessor {
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
    private val wet = amount.coerceIn(0f, 1f) * 0.35f
    private val feedback = 0.12f + (amount.coerceIn(0f, 1f) * 0.42f)
    private val leftDelay = max(1, (sampleRate * (0.021f + amount * 0.036f)).roundToInt())
    private val rightDelay = max(1, (sampleRate * (0.029f + amount * 0.041f)).roundToInt())
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

private class LimiterProcessor(private val limit: Float) : DspProcessor {
    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val data = if (input.data.size == input.sampleCount) input.data else input.data.copyOf(input.sampleCount)
        for (index in 0 until input.sampleCount) {
            data[index] = data[index].coerceIn(-limit, limit)
        }
        return input.copy(data = data)
    }
}

private abstract class StreamingResamplerProcessor(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int
) : DspProcessor {
    private val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()
    private val historyFrames = 64
    private var sourcePosition = 0.0
    private var history = FloatArray(0)
    private var historyFrameCount = 0

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        if (input.sampleRate == outputSampleRate || input.sampleCount <= 0) return input
        val inputFrames = input.sampleCount / channels
        val combinedFrames = historyFrameCount + inputFrames
        val combined = FloatArray(combinedFrames * channels)
        if (historyFrameCount > 0) {
            System.arraycopy(history, 0, combined, 0, historyFrameCount * channels)
        }
        System.arraycopy(input.data, 0, combined, historyFrameCount * channels, input.sampleCount)

        val outputFrames = max(0, ((combinedFrames - 1 - sourcePosition) / ratio).toInt() + 1)
        val output = FloatArray(outputFrames * channels)
        var outFrame = 0
        while (outFrame < outputFrames) {
            val source = sourcePosition + outFrame * ratio
            if (source > combinedFrames - 1) break
            val base = source.toInt()
            val fraction = source - base
            for (channel in 0 until channels) {
                output[outFrame * channels + channel] = sample(combined, combinedFrames, channels, channel, base, fraction)
            }
            outFrame++
        }

        sourcePosition += outFrame * ratio
        val trimFrames = sourcePosition.toInt().coerceAtMost(combinedFrames)
        sourcePosition -= trimFrames
        val remainingFrames = (combinedFrames - trimFrames).coerceAtMost(historyFrames)
        history = FloatArray(remainingFrames * channels)
        if (remainingFrames > 0) {
            System.arraycopy(
                combined,
                (combinedFrames - remainingFrames) * channels,
                history,
                0,
                remainingFrames * channels
            )
        }
        historyFrameCount = remainingFrames

        return DspProcessResult(output, outFrame * channels, outputSampleRate)
    }

    protected abstract fun sample(
        data: FloatArray,
        frameCount: Int,
        channels: Int,
        channel: Int,
        baseIndex: Int,
        fraction: Double
    ): Float
}

private class LinearResamplerProcessor(
    inputSampleRate: Int,
    outputSampleRate: Int
) : StreamingResamplerProcessor(inputSampleRate, outputSampleRate) {
    override fun sample(
        data: FloatArray,
        frameCount: Int,
        channels: Int,
        channel: Int,
        baseIndex: Int,
        fraction: Double
    ): Float {
        val safeBase = baseIndex.coerceIn(0, frameCount - 1)
        val next = min(safeBase + 1, frameCount - 1)
        val a = data[safeBase * channels + channel]
        val b = data[next * channels + channel]
        return (a + ((b - a) * fraction)).toFloat()
    }
}

private class WindowedSincResamplerProcessor(
    inputSampleRate: Int,
    outputSampleRate: Int,
    private var cutoffRatio: Float
) : StreamingResamplerProcessor(inputSampleRate, outputSampleRate) {
    private val taps = 32
    private val phases = 512
    private val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
    private var filterTable = buildFilterTable(cutoffRatio)

    override fun updateConfig(config: DspConfig) {
        if (abs(this.cutoffRatio - config.resamplerCutoffRatio) > 0.001f) {
            this.cutoffRatio = config.resamplerCutoffRatio
            this.filterTable = buildFilterTable(this.cutoffRatio)
        }
    }

    override fun sample(
        data: FloatArray,
        frameCount: Int,
        channels: Int,
        channel: Int,
        baseIndex: Int,
        fraction: Double
    ): Float {
        val phaseIndex = (fraction * phases).toInt().coerceIn(0, phases - 1)
        val kernel = filterTable[phaseIndex]
        val start = baseIndex - taps / 2 + 1
        var sum = 0.0
        for (tap in 0 until taps) {
            val frame = (start + tap).coerceIn(0, frameCount - 1)
            sum += data[frame * channels + channel] * kernel[tap]
        }
        return sum.toFloat()
    }

    private fun buildFilterTable(cutoffRatio: Float): Array<DoubleArray> {
        val normalizedCutoff = if (ratio >= 1.0) 0.5 * cutoffRatio else 0.5 * ratio * cutoffRatio
        return Array(phases) { phase ->
            val frac = phase.toDouble() / phases
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
                kernel[tap] = sinc * window
                sum += kernel[tap]
            }
            if (sum != 0.0) {
                for (tap in 0 until taps) kernel[tap] /= sum
            }
            kernel
        }
    }
}


