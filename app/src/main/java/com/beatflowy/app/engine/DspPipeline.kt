package com.beatflowy.app.engine

import com.beatflowy.app.model.AiAnalysisEntity
import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ReplayGainOption
import com.beatflowy.app.model.ReplayGainSource
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SoundStageNodePosition
import kotlin.math.abs
import kotlin.math.pow

internal data class DspProcessResult(
    val data: FloatArray,
    val sampleCount: Int,
    val sampleRate: Int,
    val intData: IntArray? = null,
    val isDoP: Boolean = false
)

internal interface DspProcessor {
    fun process(input: DspProcessResult, channels: Int): DspProcessResult
    fun updateConfig(config: DspConfig) {}
    fun flush() {}
    fun release() {}
}

internal class AudioDspPipeline(
    private val processors: List<DspProcessor>,
    private val config: DspConfig,
    private val outputSampleRate: Int,
    private val song: Song? = null
) {
    private var isDoP = song?.format?.contains("DSD", ignoreCase = true) == true || 
                       song?.format?.contains("DSF", ignoreCase = true) == true ||
                       song?.format?.contains("DFF", ignoreCase = true) == true

    // Intermediate buffer for DoP packing
    private var dopBuffer: IntArray? = null
    private var alternateMarker = false

    fun process(data: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int): DspProcessResult {
        if (isDoP) {
            // Bit-perfect DoP routing: bypass all processing
            val frames = sampleCount / channels
            val requiredIntSamples = sampleCount
            if (dopBuffer == null || dopBuffer!!.size < requiredIntSamples) {
                dopBuffer = IntArray(requiredIntSamples)
            }

            // Unpack DSD bits from "broken" floats and repack into proper DoP IntArray.
            // Bug 1 Fix: Route DoP output completely around processChain()
            for (i in 0 until sampleCount) {
                val bits = data[i].toRawBits()
                val d0 = (bits shr 16) and 0xFF
                val d1 = (bits shr 8) and 0xFF
                
                val marker = if (alternateMarker) 0x05 else 0xFA
                dopBuffer!![i] = (marker shl 24) or (d0 shl 16) or (d1 shl 8)
                
                // DoP markers alternate per stereo frame
                if (channels >= 2) {
                    if (i % channels == channels - 1) alternateMarker = !alternateMarker
                } else {
                    alternateMarker = !alternateMarker
                }
            }
            
            return DspProcessResult(
                data = data, 
                sampleCount = sampleCount, 
                sampleRate = sampleRate, 
                intData = dopBuffer, 
                isDoP = true
            )
        }

        if (config.bypassAll) {
            return DspProcessResult(data = data, sampleCount = sampleCount, sampleRate = sampleRate)
        }

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

    fun release() {
        processors.forEach { it.release() }
    }

    fun getHeadroomDb(): Float {
        return processors.filterIsInstance<NativeDspProcessor>().firstOrNull()?.getHeadroomDb() ?: 0f
    }

    fun getLatencyFrames(): Int {
        return processors.filterIsInstance<NativeDspProcessor>().firstOrNull()?.getLatencyFrames() ?: 0
    }

    companion object {
        fun create(
            inputSampleRate: Int,
            outputSampleRate: Int,
            channels: Int,
            outputBitDepth: Int,
            config: DspConfig,
            song: Song?,
            aiAnalysis: AiAnalysisEntity? = null
        ): AudioDspPipeline {
            val processors = mutableListOf<DspProcessor>()
            val effectiveInputRate = inputSampleRate.coerceAtLeast(8_000)

            processors += NativeDspProcessor(config, effectiveInputRate, outputSampleRate, channels, outputBitDepth, song, aiAnalysis)

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
    private val song: Song?,
    private val aiAnalysis: AiAnalysisEntity? = null
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

    override fun release() {
        native.release()
    }

    private fun updateNativeConfig(cfg: DspConfig, dsp: NativeDsp) {
        val isBP = cfg.bitPerfectEnabled

        // DC Blocker is typically not unbypassed, but usually kept for safety. 
        // For strict bit-perfect, we should disable it unless specifically bypassed (though not in user's list)
        dsp.setDcBlocker(if (isBP) false else cfg.dcBlockerEnabled)
        dsp.setMono(if (isBP) false else cfg.monoEnabled)

        val rg = ReplayGainState.from(cfg, song)
        // Replay gain is not in the unbypass list, so we bypass it in Bit-Perfect mode
        dsp.setReplayGain(if (isBP) 0f else rg.gainDb)

        dsp.setDvc(if (isBP) false else cfg.dvcEnabled)
        dsp.setRmsDvc(if (isBP) false else cfg.rmsDvcEnabled)
        dsp.setRmsLeveler(if (isBP) false else cfg.rmsLevelerEnabled)

        val dvcBoost = if (cfg.dvcEnabled && cfg.compensateDvcVolumeEnabled) 1.585f else 1.0f // ~+4dB boost
        dsp.setDvcLevel((if (isBP || cfg.hardwareVolumeEnabled) 1f else cfg.dvcLevel) * dvcBoost)

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
        dsp.setSpatialEnabled(if (!isBP) cfg.spatialAudioEnabled else false)
        dsp.setSpatialIntensity(cfg.spatialAudioIntensity)
        
        // 5-band Sound Stage mapping
        fun getPos(node: String) = cfg.soundStageNodePositions[node] ?: SoundStageNodePosition()

        val nodesToBands = listOf(
            listOf("Bass"), // Band 0: 20-150 Hz
            listOf("Drums"), // Band 1: 150-400 Hz
            listOf("Backing Vocals", "Keys"), // Band 2: 400-1000 Hz
            listOf("Vocals", "Guitar"), // Band 3: 1000-3000 Hz
            listOf("Lead Guitar", "Ambience") // Band 4: 3000+ Hz
        )

        nodesToBands.forEachIndexed { bandIdx, nodes ->
            if (nodes.isEmpty()) return@forEachIndexed
            var avgAz = 0f
            var avgEl = 0f
            var avgDist = 0f
            nodes.forEach { node ->
                val p = getPos(node)
                avgAz += p.azimuth
                avgEl += p.elevation
                avgDist += p.distance
            }
            dsp.setSoundStageNodePosition(bandIdx, avgAz / nodes.size, avgEl / nodes.size, avgDist / nodes.size)
        }

        dsp.setSoundStageWidth(cfg.soundStageWidth)
        dsp.setSoundStageCenterLock(cfg.soundStageCenterLock)

        data class ReverbParams(val type: Int, val room: Float, val damp: Float, val width: Float, val delay: Float)
        val params = when (cfg.reverbPreset) {
            "ROOM" ->       ReverbParams(1, 0.45f, 0.40f, 0.60f, 15f)
            "HALL" ->       ReverbParams(2, 0.75f, 0.25f, 0.85f, 35f)
            "PLATE" ->      ReverbParams(3, 0.60f, 0.10f, 0.70f, 5f)
            "CATHEDRAL" ->  ReverbParams(4, 0.90f, 0.20f, 1.00f, 55f)
            "STUDIO" ->     ReverbParams(5, 0.25f, 0.60f, 0.40f, 8f)
            "CHAMBER" ->    ReverbParams(6, 0.40f, 0.30f, 0.50f, 12f)
            else ->         ReverbParams(0, cfg.reverbDecay, cfg.reverbDamping, cfg.reverbWidth, cfg.reverbPredelayMs)
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
        dsp.setSoftLimiter(cfg.softLimiterEnabled && limiterUnbypassed)
        dsp.setLimiter(cfg.limiterEnabled && limiterUnbypassed)
        dsp.setLimiterParams(cfg.limiterThresholdDb, cfg.limiterAttackMs, cfg.limiterReleaseMs)

        val ditherUnbypassed = !isBP || cfg.bitPerfectUnbypassDithering
        val shouldDither = cfg.ditherEnabled && ditherUnbypassed &&
            cfg.ditherType != com.beatflowy.app.model.DitherType.NONE &&
            outputBitDepth < 32
        dsp.setDither(shouldDither, outputBitDepth)
        dsp.setDitherType(cfg.ditherType.nativeValue)

        // Phase 2.1: Speed
        dsp.setSpeed(cfg.playbackSpeed, cfg.preservePitch)
        
        // Phase 2.7: Headroom
        dsp.setHeadroomManagement(cfg.headroomManagementEnabled)
        dsp.setNoHeadroomGain(cfg.noHeadroomGainEnabled)

        // Phase 3.4: Hardware Volume
        dsp.setHardwareVolume(cfg.hardwareVolumeEnabled)

        applyEqBands(cfg, dsp)
    }

    private fun applyEqBands(config: DspConfig, dsp: NativeDsp) {
        val isBP = config.bitPerfectEnabled
        val eqUnbypassed = !isBP || config.bitPerfectUnbypassEq
        val effectiveEqEnabled = config.eqEnabled && eqUnbypassed

        // Handle AI EQ
        val aiEnabled = config.aiEqEnabled && !isBP
        dsp.setAiEqEnabled(aiEnabled)
        if (aiEnabled && aiAnalysis != null) {
            val bands = listOf(
                31.25f to aiAnalysis.eq31,
                62.5f to aiAnalysis.eq62,
                125f to aiAnalysis.eq125,
                250f to aiAnalysis.eq250,
                500f to aiAnalysis.eq500,
                1000f to aiAnalysis.eq1k,
                2000f to aiAnalysis.eq2k,
                4000f to aiAnalysis.eq4k,
                8000f to aiAnalysis.eq8k,
                16000f to aiAnalysis.eq16k
            )
            bands.forEachIndexed { index, (freq, gain) ->
                dsp.setAiBand(index, freq, gain, 1.41f, 0)
            }
        }

        // Handle Simulation EQ (Phase 3.5)
        val simEnabled = config.headphoneSimulationEnabled && !isBP
        dsp.setSimEqEnabled(simEnabled)
        if (simEnabled && config.headphoneSimulationProfile != null) {
            config.headphoneSimulationProfile.bands.forEachIndexed { index, band ->
                if (index < 32) {
                    dsp.setSimBand(
                        index = index,
                        frequency = band.frequencyHz,
                        gainDb = if (band.enabled) band.gainDb else 0f,
                        q = band.q,
                        type = band.type.nativeValue
                    )
                }
            }
            if (config.headphoneSimulationProfile.bands.size < 32) {
                for (i in config.headphoneSimulationProfile.bands.size until 32) {
                    dsp.setSimBand(i, 1000f, 0f, 1f, 0)
                }
            }
        }

        dsp.setEqEnabled(effectiveEqEnabled)
        dsp.setEqPhaseMode(config.eqPhaseMode == com.beatflowy.app.model.EqPhaseMode.LINEAR_PHASE)

        val autoEqPreamp = if (effectiveEqEnabled && config.autoEqEnabled && config.autoEqProfile != null) {
            config.autoEqProfile.preampDb
        } else 0f

        val reverbUnbypassed = !isBP || config.bitPerfectUnbypassReverb
        val reverbCompensation = if (config.reverbEnabled && reverbUnbypassed) -2.0f * config.reverbAmount else 0f

        val manualPreamp = if (!isBP && config.preampEnabled) config.preampDb else 0f
        val eqMasterGain = if (effectiveEqEnabled) config.eqMasterGainDb else 0f
        val appliedEqMasterGain = eqMasterGain

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

    fun getHeadroomDb(): Float = native.getHeadroomDb()

    fun getLatencyFrames(): Int = native.getEqLatencyFrames()

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
