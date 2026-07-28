package com.beatraxus.app.engine

import com.beatraxus.app.model.AiAnalysisEntity
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.ParametricEqBand
import com.beatraxus.app.model.ReplayGainOption
import com.beatraxus.app.model.ReplayGainSource
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SoundStageNodePosition
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
    fun updateOutputBitDepth(bitDepth: Int) {}
    fun updateAiAnalysis(aiAnalysis: AiAnalysisEntity?) {}
    fun flush() {}
    fun release() {}
}

internal class AudioDspPipeline(
    private val processors: List<DspProcessor>,
    val config: DspConfig,
    val inputSampleRate: Int,
    val outputSampleRate: Int,
    val channels: Int,
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
                config.bitPerfectUnbypass3DStage ||
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

    fun updateOutputBitDepth(bitDepth: Int) {
        processors.forEach { it.updateOutputBitDepth(bitDepth) }
    }

    fun updateAiAnalysis(aiAnalysis: AiAnalysisEntity?) {
        processors.forEach { it.updateAiAnalysis(aiAnalysis) }
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

            return AudioDspPipeline(processors, config, effectiveInputRate, outputSampleRate, channels, song)
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

    // Tone smoothing state removed as it is now handled in C++
    private var previousBassEnabled = config.bassEnabled
    private var previousTrebleEnabled = config.trebleEnabled
    private var previousAirEnabled = config.airEnabled
    private var isFirstConfig = true

    // Fraction of the remaining difference to apply per audio buffer (0..1)
    private val toneSmoothingFactor = 0.25f
    private val toneSnapThreshold = 0.001f

    private val native = NativeDsp().also { dsp ->
        dsp.init(inputSampleRate.toFloat(), channels, outputSampleRate)
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

        dsp.setDvcLevel(if (isBP || cfg.hardwareVolumeEnabled) 1f else cfg.dvcLevel)

        dsp.setDvcMode(cfg.dvcMode.ordinal)
        
        val resampleActive = !isBP || cfg.bitPerfectUnbypassResample
        val soxrActive = !isBP || cfg.bitPerfectUnbypassSoxr
        
        dsp.setHighQualityResampler(if (soxrActive) cfg.highQualityResampler else false)
        if (cfg.highQualityResampler && soxrActive) {
            dsp.setSoxrQuality(cfg.soxrQuality.nativeValue)
        }
        dsp.setFloat64(if (isBP) cfg.bitPerfectUnbypassFloat64 else cfg.float64Enabled)
        dsp.setCutoffRatio(if (resampleActive) cfg.resamplerCutoffRatio else 0.999f)
        
        // Tone knobs - smooth application is handled inside NativeDsp
        val targetBass = if (!isBP && cfg.bassEnabled) cfg.bassDb else 0f
        val targetTreble = if (!isBP && cfg.trebleEnabled) cfg.trebleDb else 0f
        val targetAir = if (!isBP && cfg.airEnabled) cfg.airDb else 0f

        val bassToggled = cfg.bassEnabled != previousBassEnabled
        val trebleToggled = cfg.trebleEnabled != previousTrebleEnabled
        val airToggled = cfg.airEnabled != previousAirEnabled

        var forceApplyTone = isFirstConfig
        if (bassToggled) {
            previousBassEnabled = cfg.bassEnabled
            forceApplyTone = true
        }
        if (trebleToggled) {
            previousTrebleEnabled = cfg.trebleEnabled
            forceApplyTone = true
        }
        if (airToggled) {
            previousAirEnabled = cfg.airEnabled
            forceApplyTone = true
        }

        if (forceApplyTone || cfg.bassDb != targetBass || cfg.trebleDb != targetTreble || cfg.airDb != targetAir) {
            dsp.setTone(targetBass, targetTreble, targetAir)
            isFirstConfig = false
        }

        dsp.setSpatial(
            if (!isBP && cfg.balanceEnabled) cfg.balance else 0f,
            if (!isBP && cfg.stereoExpansionEnabled) cfg.stereoWidth else 1f
        )

        dsp.setCrossfeed(if (!isBP) cfg.crossfeedEnabled else false, cfg.crossfeedLevel)
        val spatialUnbypassed = !isBP || cfg.bitPerfectUnbypass3DStage
        val spatialActive = if (spatialUnbypassed) (cfg.spatialAudioEnabled || cfg.soundStageEnabled) else false
        dsp.setSpatialEnabled(spatialActive)
        dsp.setHrtfMode(cfg.hrtfMode.ordinal)
        
        // Separation logic: 
        // 1. The "Soundstage" knob (width) is now processed independently in the native engine.
        // 2. Spatial Intensity ONLY controls the 3D positioning (ITD/ILD/Dist/Elev) blend.
        val effectiveIntensity = if (cfg.spatialAudioEnabled) cfg.spatialAudioIntensity else 0.0f
        dsp.setSpatialIntensity(effectiveIntensity)
        
        // 8-band Sound Stage mapping
        fun getPos(node: String) = cfg.soundStageNodePositions[node] ?: SoundStageNodePosition()

        val nodesToBands = listOf(
            listOf("Bass"),           // Band 0: < 120 Hz
            listOf("Drums"),          // Band 1: 120 - 280 Hz
            listOf("Vocals", "Backing Vocals"), // Band 2: 280 - 550 Hz
            listOf("Vocals", "Keys"),           // Band 3: 550 - 1.1 kHz
            listOf("Vocals"),                   // Band 4: 1.1 - 2.5 kHz
            listOf("Vocals", "Guitar"),         // Band 5: 2.5 - 5 kHz
            listOf("Lead Guitar"),              // Band 6: 5 - 10 kHz
            listOf("Ambience")                  // Band 7: > 10 kHz
        )


        nodesToBands.forEachIndexed { bandIdx, nodes ->
            if (nodes.isEmpty()) return@forEachIndexed
            var avgAz = 0f
            var avgEl = 0f
            var avgDist = 0f
            nodes.forEach { node ->
                val p = getPos(node)
                // Only apply 3D positioning (azimuth/distance) if Spatial Audio is actually ON.
                // If only Soundstage knob is ON, we keep them centered to act as a pure width expander.
                if (cfg.spatialAudioEnabled) {
                    avgAz += p.azimuth
                    avgEl += p.elevation
                    avgDist += p.distance
                } else {
                    avgAz += 0f
                    avgEl += 0f
                    avgDist += 2.0f // Reference distance
                }
            }
            dsp.setSoundStageNodePosition(bandIdx, avgAz / nodes.size, avgEl / nodes.size, avgDist / nodes.size)
        }

        val effectiveWidth = if (cfg.soundStageEnabled) cfg.soundStageWidth else cfg.spatialStageWidth
        dsp.setSoundStageWidth(effectiveWidth)
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
        dsp.setLimiterHardMode(cfg.limiterHardModeEnabled)

        val ditherUnbypassed = !isBP || cfg.bitPerfectUnbypassDithering
        val shouldDither = cfg.ditherEnabled && ditherUnbypassed &&
            cfg.ditherType != com.beatraxus.app.model.DitherType.NONE &&
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

        applyEqBands(cfg, dsp, aiAnalysis)
    }

    private fun applyEqBands(config: DspConfig, dsp: NativeDsp, analysis: AiAnalysisEntity? = null) {
        val isBP = config.bitPerfectEnabled
        val eqUnbypassed = !isBP || config.bitPerfectUnbypassEq
        val effectiveEqEnabled = config.eqEnabled && eqUnbypassed

        // Handle AI EQ
        val aiEnabled = config.aiEqEnabled && !isBP
        dsp.setAiEqEnabled(aiEnabled)
        val targetAnalysis = analysis ?: aiAnalysis
        if (aiEnabled && targetAnalysis != null) {
            val bands = listOf(
                31.25f to targetAnalysis.eq31,
                62.5f to targetAnalysis.eq62,
                125f to targetAnalysis.eq125,
                250f to targetAnalysis.eq250,
                500f to targetAnalysis.eq500,
                1000f to targetAnalysis.eq1k,
                2000f to targetAnalysis.eq2k,
                4000f to targetAnalysis.eq4k,
                8000f to targetAnalysis.eq8k,
                16000f to targetAnalysis.eq16k
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
        dsp.setEqPhaseMode(config.eqPhaseMode == com.beatraxus.app.model.EqPhaseMode.LINEAR_PHASE)

        val autoEqPreamp = if (effectiveEqEnabled && config.autoEqEnabled && config.autoEqProfile != null) {
            config.autoEqProfile.preampDb
        } else 0f

        val reverbUnbypassed = !isBP || config.bitPerfectUnbypassReverb
        val reverbCompensation = if (config.reverbEnabled && reverbUnbypassed) -2.0f * config.reverbAmount else 0f

        val manualPreamp = if (!isBP && config.preampEnabled) config.preampDb else 0f
        val eqMasterGain = if (effectiveEqEnabled) config.eqMasterGainDb else 0f
        val appliedEqMasterGain = eqMasterGain
        val dvcCompensationDb = if (config.dvcEnabled && config.compensateDvcVolumeEnabled && !isBP) 3.0f else 0f

        val totalPreamp = manualPreamp + autoEqPreamp + reverbCompensation + appliedEqMasterGain + dvcCompensationDb

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

    override fun updateOutputBitDepth(bitDepth: Int) {
        native.setBitDepth(bitDepth)
        // Also need to update dither config because it depends on bit depth
        updateNativeConfig(currentConfig, native)
    }

    override fun updateAiAnalysis(aiAnalysis: AiAnalysisEntity?) {
        applyEqBands(currentConfig, native, aiAnalysis)
    }

    override fun flush() {
        native.init(inputSampleRate.toFloat(), channels, outputSampleRate)
        if (inputSampleRate != outputSampleRate) {
            native.initResampler(inputSampleRate.toFloat(), channels, outputSampleRate.toFloat())
        }
        updateNativeConfig(currentConfig, native)
    }

    fun getHeadroomDb(): Float = native.getHeadroomDb()

    fun getLatencyFrames(): Int = native.getEqLatencyFrames()

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val speedActive = abs(currentConfig.playbackSpeed - 1.0f) > 0.001f
        if (inputSampleRate == outputSampleRate && !speedActive) {
            native.process(input.data, input.sampleCount / channels)
            return input
        }

        val speedRatio = 1.0f / currentConfig.playbackSpeed.coerceAtLeast(0.1f)
        val ratio = (outputSampleRate.toFloat() / inputSampleRate.toFloat()) * speedRatio
        // Account for speed expansion and use a safer margin matching the native side (1.5x + 1024)
        val maxFrames = (input.sampleCount / channels * ratio * 1.5f).toInt() + 1024
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
