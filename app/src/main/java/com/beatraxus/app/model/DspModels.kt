package com.beatraxus.app.model

import kotlin.math.abs

enum class EqBandType(val displayName: String, val nativeValue: Int) {
    PEAKING("Peaking", 0),
    LOW_SHELF("Low Shelf", 1),
    HIGH_SHELF("High Shelf", 2),
    LOW_PASS("Low Pass", 3),
    HIGH_PASS("High Pass", 4),
    NOTCH("Notch", 5),
    BAND_PASS("Band Pass", 6),
    ALL_PASS("All Pass", 7)
}

enum class EqPhaseMode(val displayName: String, val nativeValue: Int) {
    MINIMUM_PHASE("Minimum Phase", 0),   // zero-latency biquad IIR
    LINEAR_PHASE("Linear Phase", 1)      // FIR convolution, symmetric — adds latency
}

data class ParametricEqBand(
    val id: Int,
    val enabled: Boolean = true,
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1.0f,
    val type: EqBandType = EqBandType.PEAKING
)

data class AutoEqProfileSummary(
    val name: String,
    val relativePath: String,
    val source: String = "",
    val bands: List<ParametricEqBand> = emptyList()
)

data class AutoEqProfile(
    val name: String,
    val source: String = "",
    val relativePath: String,
    val preampDb: Float = 0f,
    val bands: List<ParametricEqBand> = emptyList()
)

data class SavedEqPreset(
    val name: String,
    val bands: List<ParametricEqBand>,
    val preampDb: Float = 0f
)

data class SoundStageNodePosition(
    val azimuth: Float = 0f,
    val elevation: Float = 0f,
    val distance: Float = 2.0f
)

enum class SampleFormat(val displayName: String, val bitDepth: Int) {
    AUTO("Auto", 0),
    PCM_16BIT("16-bit", 16),
    PCM_24BIT("24-bit", 24),
    PCM_32BIT("32-bit", 32),
    FLOAT_32BIT("Float 32-bit", 32)
}

enum class ResamplerType(val displayName: String) {
    SW("SW"),
    SOXR("SOXR")
}

enum class SoxrQuality(val displayName: String, val nativeValue: Int) {
    QUICK("Quick", 0),
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    VERY_HIGH("Very High", 4)
}

enum class DitherType(val displayName: String, val nativeValue: Int) {
    NONE("None", 0),
    TPDF("TPDF", 1),          // Triangular PDF — standard, flat noise spectrum
    SHAPED("Shaped", 2),       // Noise-shaped — psychoacoustically optimized (pushes noise to 15–20kHz)
    HIGHPASS("High-Pass", 3)   // Simple HP-filtered TPDF — lighter than shaped
}

enum class ResamplerMode(val displayName: String, val rate: Int) {
    AUTO("Auto", 0),
    SR_44100("44.1 kHz", 44100),
    SR_48000("48 kHz", 48000),
    SR_88200("88.2 kHz", 88200),
    SR_96000("96 kHz", 96000),
    SR_176400("176.4 kHz", 176400),
    SR_192000("192 kHz", 192000)
}

enum class DvcMode(val displayName: String) {
    DAC("DAC Volume"),
    BLUETOOTH("Bluetooth Volume"),
    SYSTEM("System Fallback")
}

enum class ReplayGainOption(val displayName: String) {
    APPLY_GAIN("Apply Gain"),
    APPLY_GAIN_PREVENT_CLIPPING("Apply Gain & Prevent Clipping")
}

enum class ReplayGainSource(val displayName: String) {
    TRACK("Track"),
    ALBUM("Album")
}

enum class OutputMode(val title: String, val subtitle: String) {
    AAUDIO("AAudio", "Default low-latency Android path"),
    HI_RES("MTK HiFi", "Direct high-resolution path"),
    MMAP_EXCLUSIVE("MMAP Exclusive", "Ultra-low latency kernel bypass via AAudio MMAP");

    companion object {
        fun fromName(value: String?): OutputMode {
            return entries.firstOrNull { it.name == value } ?: HI_RES
        }
    }
}

enum class HrtfMode(val displayName: String) {
    NATURAL_BALANCED("Natural (Balanced)"),
    NATURAL_WIDE("Natural (Wide)"),
    CINEMATIC("Cinematic"),
    STUDIO("Studio (Reference)")
}

data class DspConfig(
    val outputMode: OutputMode = OutputMode.HI_RES,
    val highQualityResampler: Boolean = true,
    val soxrQuality: SoxrQuality = SoxrQuality.HIGH,
    val float64Enabled: Boolean = false,
    val resamplerMode: ResamplerMode = ResamplerMode.AUTO,
    val resamplerCutoffRatio: Float = 0.99f,
    val sampleFormat: SampleFormat = SampleFormat.AUTO,
    val preampEnabled: Boolean = false,
    val preampDb: Float = 0f,
    val eqEnabled: Boolean = false,
    val eqBands: List<ParametricEqBand> = defaultEqBands(),
    val eqMasterGainDb: Float = 0f,
    val eqPhaseMode: EqPhaseMode = EqPhaseMode.MINIMUM_PHASE,
    val autoEqEnabled: Boolean = false,
    val autoEqProfile: AutoEqProfile? = null,
    val aiEqEnabled: Boolean = false, // New AI EQ
    val bassEnabled: Boolean = false,
    val bassDb: Float = 0f,
    val trebleEnabled: Boolean = false,
    val trebleDb: Float = 0f,
    val airEnabled: Boolean = false,
    val airDb: Float = 0f,
    val dcBlockerEnabled: Boolean = false,
    val balanceEnabled: Boolean = false,
    val balance: Float = 0f,
    val stereoExpansionEnabled: Boolean = false,
    val stereoWidth: Float = 1f,
    val crossfeedEnabled: Boolean = false,
    val crossfeedLevel: Float = 0.4f,
    val spatialAudioEnabled: Boolean = false,
    val soundStageEnabled: Boolean = false,
    val spatialTouchEnabled: Boolean = false,
    val spatialAudioIntensity: Float = 0.6f,
    val soundStageSelectedNode: String = "Vocals",
    val soundStageNodePositions: Map<String, SoundStageNodePosition> = mapOf(
        "Vocals" to SoundStageNodePosition(0f, 0f, 2.0f),
        "Drums" to SoundStageNodePosition(45f, 0f, 2.8f),
        "Keys" to SoundStageNodePosition(90f, 0f, 1.8f),
        "Lead Guitar" to SoundStageNodePosition(135f, 0f, 2.3f),
        "Ambience" to SoundStageNodePosition(180f, 0f, 3.5f),
        "Backing Vocals" to SoundStageNodePosition(225f, 0f, 2.5f),
        "Bass" to SoundStageNodePosition(270f, 0f, 2.2f),
        "Guitar" to SoundStageNodePosition(315f, 0f, 2.6f)
    ),
    val soundStageWidth: Float = 1.0f,
    val spatialStageWidth: Float = 1.0f,
    val soundStageCenterLock: Float = 0f,
    val hrtfMode: HrtfMode = HrtfMode.NATURAL_BALANCED,
    val reverbEnabled: Boolean = false,
    val reverbAmount: Float = 0f,
    val reverbPreset: String = "FLAT",
    val reverbPredelayMs: Float = 0f,
    val reverbWidth: Float = 1.0f,
    val reverbDamping: Float = 0.5f,
    val reverbRoomSize: Float = 0.5f,
    val reverbDecay: Float = 0.5f,
    val reverbPredelayMix: Float = 0.62f,
    
    // Replay Gain
    val replayGainEnabled: Boolean = false,
    val replayGainOption: ReplayGainOption = ReplayGainOption.APPLY_GAIN,
    val replayGainSource: ReplayGainSource = ReplayGainSource.TRACK,
    val replayGainPreamp: Float = 0f,

    // DVC (Direct Volume Control)
    val dvcEnabled: Boolean = true,
    val dvcBluetoothEnabled: Boolean = false,
    val rmsDvcEnabled: Boolean = false,
    val rmsLevelerEnabled: Boolean = false,
    val dvcMode: DvcMode = DvcMode.DAC,
    val dvcLevel: Float = 1f,
    val compensateDvcVolumeEnabled: Boolean = false,

    // USB
    val usbExclusiveEnabled: Boolean = false,
    val bitPerfectEnabled: Boolean = false,
    val monoEnabled: Boolean = false,

    // Bit-Perfect Unbypass
    val bitPerfectUnbypassEq: Boolean = false,
    val bitPerfectUnbypassResample: Boolean = false,
    val bitPerfectUnbypassSoxr: Boolean = false,
    val bitPerfectUnbypassReverb: Boolean = false,
    val bitPerfectUnbypassDithering: Boolean = false,
    val bitPerfectUnbypassFloat64: Boolean = false,
    val bitPerfectUnbypassLimiter: Boolean = false,

    // MMAP — enabled state is derived from outputMode == MMAP_EXCLUSIVE
    // mmapExclusiveEnabled field removed to avoid dual source-of-truth conflict
    val outputBufferMs: Int = 50,
    val outputBufferCount: Int = 2,
    val postFadeBufferMs: Int = 0,
    val mmapRequestedBufferSizeFrames: Int = 512,  // Safer default for high-load DSP (Atmos/Hi-Res)

    // Limiter
    val softLimiterEnabled: Boolean = false,
    val limiterEnabled: Boolean = false,
    val limiterThresholdDb: Float = -1.0f,   // More headroom before knee engages
    val limiterAttackMs: Float = 0.5f,        // Slightly longer — avoids transient shaving
    val limiterReleaseMs: Float = 80f,        // Longer release = no micro-pumping

    // Dither
    val ditherEnabled: Boolean = true,
    val ditherType: DitherType = DitherType.SHAPED,
    val settingsLocked: Boolean = false,

    // Phase 2.1: Tempo/Speed
    val playbackSpeed: Float = 1.0f,
    val preservePitch: Boolean = true,

    // Phase 2.2: Crossfade
    val crossfadeDurationS: Int = 0,

    // Phase 2.7: Headroom Management
    val headroomManagementEnabled: Boolean = false,
    val noHeadroomGainEnabled: Boolean = true,

    // Phase 3.4: Hardware Volume
    val hardwareVolumeEnabled: Boolean = false,

    // Phase 3.5: Headphone Simulation
    val headphoneSimulationEnabled: Boolean = false,
    val headphoneSimulationProfile: AutoEqProfile? = null,

    // Phase 3.1: Custom USB Driver (Decision Point)
    val customUsbDriverEnabled: Boolean = false,

    // Phase 4.2: A/B Bypass
    val bypassAll: Boolean = false
) {
    fun activeEffects(): List<String> = buildList {
        if (outputMode == OutputMode.HI_RES) add("MTK HiFi")
        if (outputMode == OutputMode.MMAP_EXCLUSIVE) add("MMAP Exclusive")
        add(if (highQualityResampler) "SOXR ${soxrQuality.displayName}" else "Cubic")
        if (preampEnabled && abs(preampDb) > 0.05f) add("Preamp")
        if (eqEnabled) {
            if (abs(eqMasterGainDb) > 0.05f) add("EQ Gain")
            if (autoEqEnabled && autoEqProfile != null && autoEqProfile.bands.isNotEmpty()) {
                add("AutoEQ")
            } else if (eqBands.any { it.enabled && abs(it.gainDb) > 0.05f }) {
                add(if (eqPhaseMode == EqPhaseMode.LINEAR_PHASE) "EQ (LP)" else "EQ")
            }
        }
        if (bassEnabled && abs(bassDb) > 0.05f) add("Bass")
        if (trebleEnabled && abs(trebleDb) > 0.05f) add("Treble")
        if (airEnabled && abs(airDb) > 0.05f) add("Air")
        if (dcBlockerEnabled) add("DC Block")
        if (balanceEnabled && abs(balance) > 0.01f) add("Balance")
        if (stereoExpansionEnabled && abs(stereoWidth - 1f) > 0.01f) add("Stereo")
        if (crossfeedEnabled) add("Crossfeed")
        if (spatialAudioEnabled) add("Spatial")
        if (soundStageEnabled) add("Soundstage")
        if (reverbEnabled && reverbAmount > 0.01f) add("Reverb")
        if (dvcEnabled) add("DVC")
        if (usbExclusiveEnabled) add("USB Direct")
        if (aiEqEnabled) add("AI EQ")
        if (headphoneSimulationEnabled) add("Sim")
        if (bitPerfectEnabled) add("Bit-Perfect")
        if (monoEnabled) add("Mono")
        if (float64Enabled) add("Float64")
        if (limiterEnabled) add("Limiter")
        if (noHeadroomGainEnabled) add("No Headroom Gain")
        if (ditherEnabled && ditherType != DitherType.NONE) add(ditherType.displayName)
    }
}

data class DspUiState(
    val config: DspConfig = DspConfig(),
    val customEqPresets: List<SavedEqPreset> = emptyList(),
    val autoEqQuery: String = "",
    val autoEqLoading: Boolean = false,
    val autoEqError: String? = null,
    val autoEqResults: List<AutoEqProfileSummary> = emptyList(),
    val currentHeadroomDb: Float = 0f,
    val currentLatencyFrames: Int = 0,
    val currentDitherType: String = "None",
    val currentEqMode: String = "IIR",
    val activeOutputDeviceLabel: String = "This Device"
)

fun defaultEqBands(): List<ParametricEqBand> {
    val standardFreqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    return standardFreqs.mapIndexed { index, freq ->
        ParametricEqBand(
            id = index,
            enabled = true,
            frequencyHz = freq,
            gainDb = 0f,
            q = 1.41f, // Q=1.41 is standard for 1-octave spacing to minimize overlap/muffling
            type = EqBandType.PEAKING // All peaking is standard for graphic EQ
        )
    }
}
