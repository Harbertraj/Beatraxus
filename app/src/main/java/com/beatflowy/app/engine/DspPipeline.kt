package com.beatflowy.app.engine

import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ReplayGainOption
import com.beatflowy.app.model.ReplayGainSource
import com.beatflowy.app.model.Song
import kotlin.math.abs
import kotlin.math.pow

internal data class DspProcessResult(
    val data: FloatArray,
    val sampleCount: Int,
    val sampleRate: Int
)

internal interface DspProcessor {
    fun process(input: DspProcessResult, channels: Int): DspProcessResult
    fun updateConfig(config: DspConfig) {}
    fun flush() {}
}

internal class AudioDspPipeline(
    private val processors: List<DspProcessor>,
    private val config: DspConfig,
    private val outputSampleRate: Int
) {
    fun process(data: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int): DspProcessResult {
        val isBitPerfect = config.bitPerfectEnabled
        val anyUnbypassed = config.bitPerfectUnbypassEq || config.bitPerfectUnbypassResample ||
                config.bitPerfectUnbypassSoxr || config.bitPerfectUnbypassReverb ||
                config.bitPerfectUnbypassDithering || config.bitPerfectUnbypassFloat64 ||
                config.bitPerfectUnbypassLimiter

        // FIX: Only bypass if sample rates match. If we are outputting to a higher rate (e.g. forced by AudioTrack HI_RES),
        // we MUST resample even in Bit-Perfect mode, otherwise the song will play at the wrong speed (e.g. 2x).
        if (isBitPerfect && !anyUnbypassed && sampleRate == outputSampleRate) {
            return DspProcessResult(data = data, sampleCount = sampleCount, sampleRate = sampleRate)
        }

        var current = DspProcessResult(data = data, sampleCount = sampleCount, sampleRate = sampleRate)
        processors.forEach { processor ->
            current = processor.process(current, channels)
        }
        return current
    }

    fun updateConfig(config: DspConfig) {
        processors.forEach { it.updateConfig(config) }
    }

    fun flush() {
        processors.forEach { it.flush() }
    }

    companion object {
        fun create(
            inputSampleRate: Int,
            outputSampleRate: Int,
            channels: Int,
            outputBitDepth: Int,
            config: DspConfig,
            song: Song?
        ): AudioDspPipeline {
            val processors = mutableListOf<DspProcessor>()
            val effectiveInputRate = inputSampleRate.coerceAtLeast(8_000)

            processors += NativeDspProcessor(config, effectiveInputRate, outputSampleRate, channels, outputBitDepth, song)

            return AudioDspPipeline(processors, config, outputSampleRate)
        }
    }
}

private class NativeDspProcessor(
    config: DspConfig,
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val channels: Int,
    private val outputBitDepth: Int,
    private val song: Song?
) : DspProcessor {
    private var currentConfig = config

    // Tone smoothing state to avoid audible artifacts when changing tone knobs
    @Volatile
    private var toneTargetMidBass = if (config.midBassEnabled) config.midBassDb else 0f
    @Volatile
    private var toneTargetTreble = if (config.trebleEnabled) config.trebleDb else 0f
    @Volatile
    private var toneTargetAir = if (config.airEnabled) config.airDb else 0f

    private var toneCurrentMidBass = toneTargetMidBass
    private var toneCurrentTreble = toneTargetTreble
    private var toneCurrentAir = toneTargetAir
    private var previousMidBassEnabled = config.midBassEnabled
    private var previousTrebleEnabled = config.trebleEnabled
    private var previousAirEnabled = config.airEnabled
    private var isFirstConfig = true

    // Fraction of the remaining difference to apply per audio buffer (0..1)
    private val toneSmoothingFactor = 0.25f
    private val toneSnapThreshold = 0.001f

    private val native = NativeDsp().also { dsp ->
        dsp.init(inputSampleRate.toFloat(), channels)
        if (inputSampleRate != outputSampleRate) {
            dsp.initResampler(inputSampleRate.toFloat(), channels, outputSampleRate.toFloat())
        }
        dsp.setBitDepth(outputBitDepth)
        updateNativeConfig(currentConfig, dsp)
    }

    private fun updateNativeConfig(cfg: DspConfig, dsp: NativeDsp) {
        val isBP = cfg.bitPerfectEnabled

        // DC Blocker is typically not unbypassed, but usually kept for safety. 
        // For strict bit-perfect, we should disable it unless specifically bypassed (though not in user's list)
        dsp.setDcBlocker(if (isBP) false else cfg.dcBlockerEnabled)

        val rg = ReplayGainState.from(cfg, song)
        // Replay gain is not in the unbypass list, so we bypass it in Bit-Perfect mode
        dsp.setReplayGain(if (isBP) 0f else rg.gainDb)

        dsp.setDvc(if (isBP) false else cfg.dvcEnabled)
        dsp.setDvcLevel(if (isBP) 1f else cfg.dvcLevel)
        dsp.setDvcMode(cfg.dvcMode.ordinal)
        
        val resampleActive = !isBP || cfg.bitPerfectUnbypassResample
        val soxrActive = !isBP || cfg.bitPerfectUnbypassSoxr
        
        dsp.setHighQualityResampler(if (soxrActive) cfg.highQualityResampler else false)
        if (cfg.highQualityResampler && soxrActive) {
            dsp.setSoxrQuality(cfg.soxrQuality.nativeValue)
        }
        dsp.setFloat64(if (isBP) cfg.bitPerfectUnbypassFloat64 else cfg.float64Enabled)
        dsp.setCutoffRatio(if (resampleActive) cfg.resamplerCutoffRatio else 0.999f)
        
        // Tone knobs - not in unbypass list
        val targetMidBass = if (!isBP && cfg.midBassEnabled) cfg.midBassDb else 0f
        val targetTreble = if (!isBP && cfg.trebleEnabled) cfg.trebleDb else 0f
        val targetAir = if (!isBP && cfg.airEnabled) cfg.airDb else 0f

        val midBassToggled = cfg.midBassEnabled != previousMidBassEnabled
        val trebleToggled = cfg.trebleEnabled != previousTrebleEnabled
        val airToggled = cfg.airEnabled != previousAirEnabled

        toneTargetMidBass = targetMidBass
        toneTargetTreble = targetTreble
        toneTargetAir = targetAir

        var forceApplyTone = isFirstConfig
        if (midBassToggled) {
            toneCurrentMidBass = targetMidBass
            previousMidBassEnabled = cfg.midBassEnabled
            forceApplyTone = true
        }
        if (trebleToggled) {
            toneCurrentTreble = targetTreble
            previousTrebleEnabled = cfg.trebleEnabled
            forceApplyTone = true
        }
        if (airToggled) {
            toneCurrentAir = targetAir
            previousAirEnabled = cfg.airEnabled
            forceApplyTone = true
        }

        if (forceApplyTone) {
            dsp.setTone(toneCurrentMidBass, toneCurrentTreble, toneCurrentAir)
            isFirstConfig = false
        }

        dsp.setSpatial(
            if (!isBP && cfg.balanceEnabled) cfg.balance else 0f,
            if (!isBP && cfg.stereoExpansionEnabled) cfg.stereoWidth else 1f
        )

        dsp.setCrossfeed(if (!isBP) cfg.crossfeedEnabled else false, cfg.crossfeedLevel)

        data class ReverbParams(val type: Int, val room: Float, val damp: Float, val width: Float, val delay: Float)
        val params = when (cfg.reverbPreset) {
            "ROOM" ->       ReverbParams(1, 0.45f, 0.40f, 0.60f, 15f)
            "HALL" ->       ReverbParams(2, 0.75f, 0.25f, 0.85f, 35f)
            "PLATE" ->      ReverbParams(3, 0.60f, 0.10f, 0.70f, 5f)
            "CATHEDRAL" ->  ReverbParams(4, 0.90f, 0.20f, 1.00f, 55f)
            "STUDIO" ->     ReverbParams(5, 0.25f, 0.60f, 0.40f, 8f)
            "CHAMBER" ->    ReverbParams(6, 0.40f, 0.30f, 0.50f, 12f)
            else ->         ReverbParams(0, cfg.reverbRoomSize, cfg.reverbDamping, cfg.reverbWidth, cfg.reverbPredelayMs)
        }
        
        val reverbUnbypassed = !isBP || cfg.bitPerfectUnbypassReverb
        if (!cfg.reverbEnabled || !reverbUnbypassed) {
            dsp.setReverb(0.0f)
            dsp.muteReverb()
        } else {
            dsp.setReverb(cfg.reverbAmount)
        }
        dsp.setReverbType(params.type)
        dsp.setReverbParams(params.room, params.damp)
        dsp.setReverbWidth(params.width)
        dsp.setReverbPredelay(params.delay)

        val limiterUnbypassed = !isBP || cfg.bitPerfectUnbypassLimiter
        dsp.setLimiter(cfg.limiterEnabled && limiterUnbypassed)
        dsp.setLimiterParams(cfg.limiterThresholdDb, cfg.limiterAttackMs, cfg.limiterReleaseMs)

        val ditherUnbypassed = !isBP || cfg.bitPerfectUnbypassDithering
        val shouldDither = cfg.ditherEnabled && ditherUnbypassed &&
            cfg.ditherType != com.beatflowy.app.model.DitherType.NONE &&
            outputBitDepth < 32
        dsp.setDither(shouldDither, outputBitDepth)
        dsp.setDitherType(cfg.ditherType.nativeValue)

        applyEqBands(cfg, dsp)
    }

    private fun applyEqBands(config: DspConfig, dsp: NativeDsp) {
        val isBP = config.bitPerfectEnabled
        val eqUnbypassed = !isBP || config.bitPerfectUnbypassEq
        val effectiveEqEnabled = config.eqEnabled && eqUnbypassed

        dsp.setEqEnabled(effectiveEqEnabled)
        dsp.setEqPhaseMode(config.eqPhaseMode == com.beatflowy.app.model.EqPhaseMode.LINEAR_PHASE)

        val autoEqPreamp = if (effectiveEqEnabled && config.autoEqEnabled && config.autoEqProfile != null) {
            config.autoEqProfile.preampDb
        } else 0f

        val reverbUnbypassed = !isBP || config.bitPerfectUnbypassReverb
        val reverbCompensation = if (config.reverbEnabled && reverbUnbypassed) -2.0f * config.reverbAmount else 0f

        // Headroom management to prevent distortion from both EQ and Tone knobs
        val bands = if (effectiveEqEnabled) {
            if (config.autoEqEnabled && config.autoEqProfile != null) config.autoEqProfile.bands else config.eqBands
        } else emptyList()

        // FIX: Only consider enabled bands for headroom calculation
        val maxEqBoost = bands.filter { it.enabled }.maxOfOrNull { it.gainDb }?.coerceAtLeast(0f) ?: 0f

        val manualPreamp = if (!isBP && config.preampEnabled) config.preampDb else 0f
        val eqMasterGain = if (effectiveEqEnabled) config.eqMasterGainDb else 0f

        // Only Equalizer bands contribute to automatic headroom reduction.
        // Tone knobs (Bass/Treble) are excluded from the main 'excess' calculation 
        // to prevent confusing volume shifts when the user tunes them.
        val totalConfiguredBoost = manualPreamp + autoEqPreamp + eqMasterGain + maxEqBoost
        
        val targetHeadroomThreshold = 9.0f // Increased to 9dB for even more output power

        val excess = (totalConfiguredBoost - targetHeadroomThreshold).coerceAtLeast(0f)
        val appliedEqMasterGain = if (excess > 0f) {
            eqMasterGain - excess
        } else eqMasterGain

        // For tone knobs, we apply a very minimal constant headroom reduction 
        // only if any boost knob is active. This avoids the "dipping" sensation.
        val maxToneBoost = listOf(
            if (config.midBassEnabled) config.midBassDb else 0f,
            if (config.trebleEnabled) config.trebleDb else 0f,
            if (config.airEnabled) config.airDb else 0f
        ).maxOrNull()?.coerceAtLeast(0f) ?: 0f
        
        // Very light compensation for tone knobs (max 1.5dB drop at full 12dB boost)
        val toneHeadroom = if (maxToneBoost > 0.1f) (maxToneBoost * 0.125f).coerceAtMost(1.5f) else 0f

        val totalPreamp = manualPreamp + autoEqPreamp + reverbCompensation + appliedEqMasterGain - toneHeadroom

        dsp.setPreamp(totalPreamp)

        if (!config.eqEnabled) {
            repeat(32) { i -> dsp.setBand(i, 1000f, 0f, 1f, 0) }
            return
        }

        val bandsToApply: List<ParametricEqBand> = if (config.autoEqEnabled && config.autoEqProfile != null) {
            config.autoEqProfile.bands
        } else {
            config.eqBands
        }

        bandsToApply.forEachIndexed { index, band ->
            if (index < 32) {
                dsp.setBand(
                    index = index,
                    frequency = band.frequencyHz,
                    gainDb = if (band.enabled) band.gainDb else 0f,
                    q = band.q,
                    type = band.type.nativeValue
                )
            }
        }

        if (bandsToApply.size < 32) {
            for (i in bandsToApply.size until 32) {
                dsp.setBand(i, 1000f, 0f, 1f, 0)
            }
        }
    }

    private var outputBuffer = FloatArray(0)

    override fun updateConfig(config: DspConfig) {
        currentConfig = config
        updateNativeConfig(currentConfig, native)
    }

    override fun flush() {
        native.init(inputSampleRate.toFloat(), channels)
        if (inputSampleRate != outputSampleRate) {
            native.initResampler(inputSampleRate.toFloat(), channels, outputSampleRate.toFloat())
        }
        updateNativeConfig(currentConfig, native)
    }

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        // Smooth tone targets towards current values each buffer to avoid clicks/noise
        smoothToneAndApply(native)

        if (inputSampleRate == outputSampleRate) {
            native.process(input.data, input.sampleCount / channels)
            return input
        }

        val ratio = outputSampleRate.toFloat() / inputSampleRate.toFloat()
        val maxFrames = (input.sampleCount / channels * ratio * 1.2f).toInt() + 128
        val requiredSize = maxFrames * channels
        
        if (outputBuffer.size < requiredSize) {
            outputBuffer = FloatArray(requiredSize)
        }

        val outFrames = native.processResampled(input.data, input.sampleCount / channels, outputBuffer)
        val outSamples = outFrames * channels

        return DspProcessResult(
            data = outputBuffer,
            sampleCount = outSamples,
            sampleRate = outputSampleRate
        )
    }

    private fun smoothToneAndApply(dsp: NativeDsp) {
        var changed = false
        synchronized(this) {
            // Mid-bass knob 1
            changed = smoothToneParameter(
                ::toneTargetMidBass,
                ::toneCurrentMidBass,
                { toneCurrentMidBass = it }
            ) || changed

            // Treble knob 2
            changed = smoothToneParameter(
                ::toneTargetTreble,
                ::toneCurrentTreble,
                { toneCurrentTreble = it }
            ) || changed

            // Air knob 3
            changed = smoothToneParameter(
                ::toneTargetAir,
                ::toneCurrentAir,
                { toneCurrentAir = it }
            ) || changed

            if (changed) {
                dsp.setTone(toneCurrentMidBass, toneCurrentTreble, toneCurrentAir)
            }
        }
    }

    private inline fun smoothToneParameter(
        getTarget: () -> Float,
        getCurrent: () -> Float,
        setCurrent: (Float) -> Unit
    ): Boolean {
        val target = getTarget()
        val current = getCurrent()
        val difference = target - current

        return if (kotlin.math.abs(difference) <= toneSnapThreshold) {
            if (current != target) {
                setCurrent(target)
                true
            } else false
        } else {
            setCurrent(current + difference * toneSmoothingFactor)
            true
        }
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
                    gainDb = 20f * kotlin.math.log10(safeLimit.toDouble()).toFloat(),
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
