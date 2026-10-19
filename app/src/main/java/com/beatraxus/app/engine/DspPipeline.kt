package com.beatraxus.app.engine

import android.os.Build
import android.util.Log
import com.beatraxus.app.engine.aieq.AiEqProfile
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.ParametricEqBand
import com.beatraxus.app.model.ReplayGainOption
import com.beatraxus.app.model.ReplayGainSource
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SpatialUiMode
import com.beatraxus.app.model.tuning
import com.beatraxus.app.model.Audio3DSpeakerPosition
import com.beatraxus.app.model.SoundStageNodePosition
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.log10

internal data class DspProcessResult(
    val data: FloatArray,
    val sampleCount: Int,
    val sampleRate: Int,
    val intData: IntArray? = null,
    val isDoP: Boolean = false,
    /**
     * Interleaved channel count of [data]. 0 means "same as the channel count that was fed in"; it is only
     * set (6 or 8) by the surround stage, which widens a stereo stream.
     */
    val channels: Int = 0
)

internal interface DspProcessor {
    fun process(input: DspProcessResult, channels: Int): DspProcessResult
    fun updateConfig(config: DspConfig) {}
    fun updateOutputBitDepth(bitDepth: Int) {}
    fun updateAiEq(profile: AiEqProfile?) {}
    fun flush() {}
    fun release() {}
    fun getLevels(): FloatArray = floatArrayOf(0f, 0f)
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

    // --- 5.1 / 7.1 surround stage (last stage, after the whole native chain) ---------------------------------
    // 0 = off: process() output is exactly what the chain produced. 6 / 8 = upmix stereo to that many channels.
    // Set by the engine after it has opened the output with the same channel count (see AudioEngine.configure).
    @Volatile private var surroundOutChannels = 0
    private var surroundUpmixer: SurroundUpmixer? = null
    private var surroundBuffer = FloatArray(0)
    @Volatile private var surroundConfig: DspConfig = config

    // Real 5.1 / 7.1 source (e.g. a video soundtrack with a multichannel track): channels stay discrete, only the
    // per-speaker trims apply (relative to the Default preset, so untouched trims change nothing).
    @Volatile private var discreteActive = false
    private var surroundTrimmer: SurroundTrimmer? = null

    /** Channel count this pipeline currently produces: 6 / 8 while the surround stage is on, else the input count. */
    val outputChannels: Int get() = surroundOutChannels.takeIf { it > 2 } ?: channels

    /**
     * Turns the surround stage on for [outChannels] = 6 (5.1) or 8 (7.1), or off for anything else.
     * Only stereo / mono sources can be upmixed; a multichannel source keeps its own layout.
     */
    fun setSurroundChannels(outChannels: Int) {
        val valid = (outChannels == 6 || outChannels == 8) && channels in 1..2
        if (!valid) {
            surroundOutChannels = 0
            return
        }
        val upmixer = surroundUpmixer ?: SurroundUpmixer(outputSampleRate).also { surroundUpmixer = it }
        val cfg = surroundConfig
        upmixer.configure(outChannels, cfg.surroundMode.tuning(), cfg.surroundChannelTrimsDb)
        surroundOutChannels = outChannels
    }

    /** Cheap per-batch check: (re)configures the surround stage only when [outChannels] differs from the current one. */
    fun syncSurroundChannels(outChannels: Int) {
        val wanted = if (outChannels == 6 || outChannels == 8) outChannels else 0
        if (wanted != surroundOutChannels) setSurroundChannels(wanted)
    }

    /**
     * Turns the discrete 5.1 / 7.1 trim stage on or off. Only valid when this pipeline's own input already is a
     * 6 / 8 channel stream; anything else keeps it off.
     */
    fun setDiscreteTrims(enabled: Boolean) {
        val valid = enabled && (channels == 6 || channels == 8)
        if (!valid) {
            discreteActive = false
            return
        }
        val trimmer = surroundTrimmer ?: SurroundTrimmer(outputSampleRate).also { surroundTrimmer = it }
        trimmer.configure(channels, surroundConfig.surroundChannelTrimsDb)
        discreteActive = true
    }

    /** Cheap per-batch check, like [syncSurroundChannels]: only reconfigures when the wanted state differs. */
    fun syncDiscreteTrims(enabled: Boolean) {
        if (enabled != discreteActive) setDiscreteTrims(enabled)
    }

    private fun applySurround(base: DspProcessResult, inChannels: Int): DspProcessResult {
        if (discreteActive && inChannels == channels && !base.isDoP) {
            surroundTrimmer?.process(base.data, base.sampleCount / inChannels)
            return base
        }
        val outCh = surroundOutChannels
        val upmixer = surroundUpmixer
        if (outCh <= 2 || upmixer == null || base.isDoP || inChannels !in 1..2) return base

        val frames = base.sampleCount / inChannels
        val needed = frames * outCh
        if (surroundBuffer.size < needed) surroundBuffer = FloatArray(needed + needed / 4)
        upmixer.process(base.data, inChannels, frames, surroundBuffer)
        return DspProcessResult(
            data = surroundBuffer,
            sampleCount = needed,
            sampleRate = base.sampleRate,
            channels = outCh
        )
    }

    fun process(data: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int): DspProcessResult =
        applySurround(processChain(data, sampleCount, channels, sampleRate), channels)

    private fun processChain(data: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int): DspProcessResult {
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
        // Surround matrix / trims follow live slider changes; the layout itself (6 vs 8 ch) is only ever
        // changed through setSurroundChannels() after the output has been re-opened.
        surroundConfig = config
        val outCh = surroundOutChannels
        if (outCh > 2) {
            surroundUpmixer?.configure(outCh, config.surroundMode.tuning(), config.surroundChannelTrimsDb)
        }
        if (discreteActive) {
            surroundTrimmer?.configure(channels, config.surroundChannelTrimsDb)
        }
    }

    fun updateOutputBitDepth(bitDepth: Int) {
        processors.forEach { it.updateOutputBitDepth(bitDepth) }
    }

    fun updateAiEq(profile: AiEqProfile?) {
        processors.forEach { it.updateAiEq(profile) }
    }

    fun flush() {
        processors.forEach { it.flush() }
        surroundUpmixer?.reset()
    }

    fun release() {
        processors.forEach { it.release() }
        surroundOutChannels = 0
        discreteActive = false
    }

    fun getLevels(): FloatArray = processors.find { it is NativeDspProcessor }?.getLevels() ?: floatArrayOf(0f, 0f)

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
            aiEq: AiEqProfile? = null
        ): AudioDspPipeline {
            val processors = mutableListOf<DspProcessor>()
            val effectiveInputRate = inputSampleRate.coerceAtLeast(8_000)

            processors += NativeDspProcessor(config, effectiveInputRate, outputSampleRate, channels, outputBitDepth, song, aiEq)

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
    aiEq: AiEqProfile? = null
) : DspProcessor {
    private var currentConfig = config
    private var currentAiEq: AiEqProfile? = aiEq

    // Tone smoothing state removed as it is now handled in C++
    private var previousBassEnabled = config.bassEnabled
    private var previousTrebleEnabled = config.trebleEnabled
    private var previousAirEnabled = config.airEnabled
    private var isFirstConfig = true

    // Config last fully applied to the native engine (null = must apply everything).
    private var lastAppliedConfig: DspConfig? = null

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
        // A Spatial slider drag changes only Spatial fields. Re-sending the whole config (all EQ,
        // AI EQ and simulation bands, limiter, dither, ...) on every tick is wasted work on the
        // audio thread, so push just the Spatial section in that case.
        val prev = lastAppliedConfig
        if (prev != null && prev != cfg && isSpatialOnlyChange(prev, cfg)) {
            applySpatialSection(cfg, dsp)
            lastAppliedConfig = cfg
            return
        }
        lastAppliedConfig = cfg
        val isBP = cfg.bitPerfectEnabled

        dsp.setPreamp(if (isBP) 0f else cfg.preampDb)

        // DC Blocker is typically not unbypassed, but usually kept for safety. 
        // For strict bit-perfect, we should disable it unless specifically bypassed (though not in user's list)
        dsp.setDcBlocker(if (isBP) false else cfg.dcBlockerEnabled)
        dsp.setMono(if (channels > 2 || isBP) false else cfg.monoEnabled)

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

        applySpatialSection(cfg, dsp)

        data class ReverbParams(val type: Int, val room: Float, val damp: Float, val width: Float, val delay: Float)
        val namedPreset = com.beatraxus.app.model.REVERB_NAMED_PRESETS[cfg.reverbPreset]
        val params = if (namedPreset != null) {
            ReverbParams(namedPreset.type, namedPreset.room, namedPreset.damping, namedPreset.width, namedPreset.predelayMs)
        } else {
            // Custom / FLAT: the native reverb has a single room-size (feedback) parameter, so the
            // SIZE and DECAY knobs are blended into it. Previously only DECAY was sent and SIZE was dead.
            val room = ((cfg.reverbDecay + cfg.reverbRoomSize) * 0.5f).coerceIn(0f, 1f)
            ReverbParams(0, room, cfg.reverbDamping, cfg.reverbWidth, cfg.reverbPredelayMs)
        }

        val reverbUnbypassed = !isBP || cfg.bitPerfectUnbypassReverb
        if (!cfg.reverbEnabled || !reverbUnbypassed) {
            dsp.setReverb(0.0f)
            dsp.muteReverb()
            dsp.setReverbType(params.type)
            dsp.setReverbParams(params.room, params.damp)
            dsp.setReverbWidth(params.width)
            dsp.setReverbPredelay(params.delay)
            dsp.setReverbMixBalance(0.62f) // neutral, so a disabled reverb never touches the dry signal
        } else {
            dsp.setReverb(cfg.reverbAmount)
            dsp.setReverbMixBalance(cfg.reverbPredelayMix)
            dsp.setReverbType(params.type)
            dsp.setReverbParams(params.room, params.damp)
            dsp.setReverbWidth(params.width)
            dsp.setReverbPredelay(params.delay)
        }

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

        applyEqBands(cfg, dsp, currentAiEq)
    }

    /** Everything the Spatial / Sound Stage tab controls. Safe to call on its own. */
    private fun applySpatialSection(cfg: DspConfig, dsp: NativeDsp) {
        val isBP = cfg.bitPerfectEnabled
        if (channels > 2) {
            Log.d("VideoAudioRouting", "Multichannel audio path ($channels ch). Bypassing stereo-only DSP features (Crossfeed, 3D Spatial). NO DOWNMIX APPLIED.")
            dsp.setCrossfeed(false, 0f)
            dsp.setSpatialEnabled(false)
        } else {
            dsp.setCrossfeed(if (!isBP) cfg.crossfeedEnabled else false, cfg.crossfeedLevel)
            val spatialUnbypassed = !isBP || cfg.bitPerfectUnbypass3DStage

            dsp.setHrtfMode(cfg.hrtfMode.ordinal)
            
            if (cfg.spatialUiMode == SpatialUiMode.CLASSIC) {
                dsp.setSpatialUiMode(1)
                val audio3DActive = cfg.audio3DStageEnabled && spatialUnbypassed
                dsp.setSpatialEnabled(audio3DActive)
                dsp.setAudio3DStageParams(
                    cfg.audio3DWidth,
                    cfg.audio3DDepth,
                    cfg.audio3DHeight,
                    cfg.audio3DDistance,
                    cfg.audio3DCenterFocus,
                    cfg.audio3DRoomReflections
                )
                // Set Speaker Positions for Classic (using bands 0 and 1 as L/R speakers)
                val speakerL = cfg.audio3DSpeakerPositions.find { it.id == "L" } ?: Audio3DSpeakerPosition("L", 270f, 0f, 2.0f)
                val speakerR = cfg.audio3DSpeakerPositions.find { it.id == "R" } ?: Audio3DSpeakerPosition("R", 90f, 0f, 2.0f)
                dsp.setSoundStageNodePosition(0, speakerL.azimuthDeg, speakerL.elevationDeg, speakerL.distance)
                dsp.setSoundStageNodePosition(1, speakerR.azimuthDeg, speakerR.elevationDeg, speakerR.distance)
                
                // Map legacy "Intensity" to spatial intensity
                dsp.setSpatialIntensity(cfg.audio3DRoomReflections)
            } else {
                dsp.setSpatialUiMode(0)
                val spatialActive = if (spatialUnbypassed) (cfg.spatialAudioEnabled || cfg.soundStageEnabled) else false
                dsp.setSpatialEnabled(spatialActive)
                
                val effectiveIntensity = if (cfg.spatialAudioEnabled) cfg.spatialAudioIntensity else 0.0f
                dsp.setSpatialIntensity(effectiveIntensity)

                // 8-band Sound Stage mapping
                fun getPos(node: String) = cfg.soundStageNodePositions[node] ?: SoundStageNodePosition()

                val nodesToBands = listOf(
                    listOf("Bass"),            // Band 0: < 120 Hz
                    listOf("Drums"),           // Band 1: 120 - 280 Hz
                    listOf("Backing Vocals"),  // Band 2: 280 - 550 Hz
                    listOf("Keys"),            // Band 3: 550 - 1.1 kHz
                    listOf("Vocals"),          // Band 4: 1.1 - 2.5 kHz  (vocal presence range)
                    listOf("Guitar"),          // Band 5: 2.5 - 5 kHz
                    listOf("Lead Guitar"),     // Band 6: 5 - 10 kHz
                    listOf("Ambience")         // Band 7: > 10 kHz
                )

                nodesToBands.forEachIndexed { bandIdx, nodes ->
                    if (nodes.isEmpty()) return@forEachIndexed
                    var avgAz = 0f
                    var avgEl = 0f
                    var avgDist = 0f
                    nodes.forEach { node ->
                        val p = getPos(node)
                        if (cfg.spatialAudioEnabled) {
                            avgAz += p.azimuth
                            avgEl += p.elevation
                            avgDist += p.distance
                        } else {
                            avgAz += 0f
                            avgEl += 0f
                            avgDist += 2.0f
                        }
                    }
                    dsp.setSoundStageNodePosition(bandIdx, avgAz / nodes.size, avgEl / nodes.size, avgDist / nodes.size)
                }
            }
        }

        // 3D stage width/center-lock (separate from the plain mid-side stereoWidth above).
        val effectiveWidth = if (cfg.soundStageEnabled) cfg.soundStageWidth else cfg.spatialStageWidth
        dsp.setSoundStageWidth(effectiveWidth)
        dsp.setSoundStageCenterLock(cfg.soundStageCenterLock)
    }

    /** True when [cfg] differs from [prev] ONLY in Spatial / Sound Stage fields. */
    private fun isSpatialOnlyChange(prev: DspConfig, cfg: DspConfig): Boolean {
        return cfg.copy(
            spatialAudioEnabled = prev.spatialAudioEnabled,
            soundStageEnabled = prev.soundStageEnabled,
            spatialTouchEnabled = prev.spatialTouchEnabled,
            spatialAudioIntensity = prev.spatialAudioIntensity,
            soundStageSelectedNode = prev.soundStageSelectedNode,
            soundStageNodePositions = prev.soundStageNodePositions,
            soundStageWidth = prev.soundStageWidth,
            spatialStageWidth = prev.spatialStageWidth,
            soundStageCenterLock = prev.soundStageCenterLock,
            hrtfMode = prev.hrtfMode,
            spatialUiMode = prev.spatialUiMode,
            soundStagePresets = prev.soundStagePresets,
            audio3DStageEnabled = prev.audio3DStageEnabled,
            audio3DWidth = prev.audio3DWidth,
            audio3DDepth = prev.audio3DDepth,
            audio3DHeight = prev.audio3DHeight,
            audio3DDistance = prev.audio3DDistance,
            audio3DCenterFocus = prev.audio3DCenterFocus,
            audio3DRoomReflections = prev.audio3DRoomReflections,
            audio3DSpeakerPositions = prev.audio3DSpeakerPositions,
            audio3DPresets = prev.audio3DPresets
        ) == prev
    }

    private fun applyEqBands(config: DspConfig, dsp: NativeDsp, aiProfile: AiEqProfile? = null) {
        val isBP = config.bitPerfectEnabled
        val eqUnbypassed = !isBP || config.bitPerfectUnbypassEq
        // AI EQ: ten bands predicted for the playing song (flat until its profile arrives).
        val aiEnabled = config.aiEqEnabled && !isBP
        // While AI EQ is on it is the only equalizer that runs: the selected preset / custom bands
        // (and a headphone AutoEQ profile) stay stored but are not applied.
        val effectiveEqEnabled = config.eqEnabled && eqUnbypassed && !aiEnabled
        dsp.setAiEqEnabled(aiEnabled)
        if (aiEnabled) {
            val target = aiProfile ?: currentAiEq
            val freqs = com.beatraxus.app.engine.aieq.AiEqKnowledge.BAND_FREQUENCIES
            for (index in freqs.indices) {
                dsp.setAiBand(index, freqs[index], target?.gains?.getOrNull(index) ?: 0f, 1.41f, 0)
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
        val dvcCompensationDb = if (config.dvcEnabled && config.compensateDvcVolumeEnabled && !isBP) {
            // Use the level the native DVC actually applies (forced to 1.0 with hardware volume).
            val effectiveDvcLevel = if (config.hardwareVolumeEnabled) 1f else config.dvcLevel
            val dvcAttenuationDb = if (effectiveDvcLevel > 0f) -20f * log10(effectiveDvcLevel) else 0f
            // The Android 15+ makeup gain must NOT depend on the DVC level. It was gated on
            // `dvcAttenuationDb > 0`, so at 100% the whole compensation collapsed to 0 dB and the
            // option did nothing even though it was on.
            val makeupGainDb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) 4f else 0f
            (dvcAttenuationDb + makeupGainDb).coerceIn(0f, 12f)
        } else 0f

        val totalPreamp = manualPreamp + autoEqPreamp + reverbCompensation + appliedEqMasterGain + dvcCompensationDb

        dsp.setPreamp(totalPreamp)

        if (!config.eqEnabled || aiEnabled) {
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

    override fun updateAiEq(profile: AiEqProfile?) {
        currentAiEq = profile
        applyEqBands(currentConfig, native, currentAiEq)
    }

    override fun flush() {
        lastAppliedConfig = null
        native.init(inputSampleRate.toFloat(), channels, outputSampleRate)
        if (inputSampleRate != outputSampleRate) {
            native.initResampler(inputSampleRate.toFloat(), channels, outputSampleRate.toFloat())
        }
        updateNativeConfig(currentConfig, native)
    }

    fun getHeadroomDb(): Float = native.getHeadroomDb()

    fun getLatencyFrames(): Int = native.getEqLatencyFrames()

    override fun getLevels(): FloatArray = native.getLevels()

    override fun process(input: DspProcessResult, channels: Int): DspProcessResult {
        val speedActive = abs(currentConfig.playbackSpeed.coerceIn(com.beatraxus.app.model.PLAYBACK_SPEED_RANGE) - 1.0f) > 0.001f
        if (inputSampleRate == outputSampleRate && !speedActive) {
            native.process(input.data, input.sampleCount / channels)
            return input
        }

        val speedRatio = 1.0f / currentConfig.playbackSpeed.coerceIn(com.beatraxus.app.model.PLAYBACK_SPEED_RANGE).coerceAtLeast(0.1f)
        val ratio = (outputSampleRate.toFloat() / inputSampleRate.toFloat()) * speedRatio
        // Account for speed expansion and use a safer margin matching the native side (1.5x + 1024)
        val maxFrames = (input.sampleCount / channels * ratio * 1.5f).toInt() + 1024
        val requiredSize = maxFrames * channels
        
        if (outputBuffer.size < requiredSize) {
            outputBuffer = FloatArray(requiredSize)
        }

        val outFrames = native.processResampled(input.data, input.sampleCount / channels, outputBuffer, outputBuffer.size / channels)
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
