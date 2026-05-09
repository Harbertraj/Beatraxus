package com.beatflowy.app.model

import kotlin.math.abs

data class ParametricEqBand(
    val id: Int,
    val enabled: Boolean = true,
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1.0f
)

data class AutoEqProfileSummary(
    val name: String,
    val relativePath: String,
    val source: String = ""
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
    val bands: List<ParametricEqBand>
)

enum class SampleFormat(val displayName: String) {
    AUTO("Auto"),
    PCM_16BIT("16-bit"),
    PCM_24BIT("24-bit"),
    PCM_32BIT("32-bit"),
    FLOAT_32BIT("Float 32-bit")
}

enum class ResamplerType(val displayName: String) {
    SW("SW"),
    SOXR("SOXR")
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
    HI_RES("MTK HiFi", "Direct high-resolution path");

    companion object {
        fun fromName(value: String?): OutputMode {
            return entries.firstOrNull { it.name == value } ?: AAUDIO
        }
    }
}

data class DspConfig(
    val outputMode: OutputMode = OutputMode.AAUDIO,
    val highQualityResampler: Boolean = true,
    val resamplerMode: ResamplerMode = ResamplerMode.AUTO,
    val resamplerCutoffRatio: Float = 0.97f,
    val sampleFormat: SampleFormat = SampleFormat.AUTO,
    val preampEnabled: Boolean = false,
    val preampDb: Float = 0f,
    val eqEnabled: Boolean = false,
    val eqBands: List<ParametricEqBand> = defaultEqBands(),
    val autoEqEnabled: Boolean = false,
    val autoEqProfile: AutoEqProfile? = null,
    val bassEnabled: Boolean = false,
    val bassDb: Float = 0f,
    val midBassEnabled: Boolean = false,
    val midBassDb: Float = 0f,
    val trebleEnabled: Boolean = false,
    val trebleDb: Float = 0f,
    val airEnabled: Boolean = false,
    val airDb: Float = 0f,
    val dcBlockerEnabled: Boolean = true,
    val balanceEnabled: Boolean = false,
    val balance: Float = 0f,
    val stereoExpansionEnabled: Boolean = false,
    val stereoWidth: Float = 1f,
    val crossfeedEnabled: Boolean = false,
    val crossfeedLevel: Float = 0.4f,
    val reverbEnabled: Boolean = false,
    val reverbAmount: Float = 0f,
    val reverbPreset: String = "FLAT",
    val reverbPredelayMs: Float = 0f,
    val reverbWidth: Float = 1.0f,
    val reverbDamping: Float = 0.5f,
    val reverbRoomSize: Float = 0.5f,
    val reverbPredelayMix: Float = 0.62f,
    
    // Replay Gain
    val replayGainEnabled: Boolean = false,
    val replayGainOption: ReplayGainOption = ReplayGainOption.APPLY_GAIN,
    val replayGainSource: ReplayGainSource = ReplayGainSource.TRACK,
    val replayGainPreamp: Float = 0f,

    // DVC (Direct Volume Control)
    val dvcEnabled: Boolean = true,
    val dvcBluetoothEnabled: Boolean = false,
    val dvcMode: DvcMode = DvcMode.DAC,
    val dvcLevel: Float = 1f,

    // Limiter
    val limiterEnabled: Boolean = true
) {
    fun activeEffects(): List<String> = buildList {
        if (outputMode == OutputMode.HI_RES) add("MTK HiFi")
        add(if (highQualityResampler) "SOXR" else "Cubic")
        if (preampEnabled && abs(preampDb) > 0.05f) add("Preamp")
        if (eqEnabled) {
            if (autoEqEnabled && autoEqProfile != null && autoEqProfile.bands.isNotEmpty()) {
                add("AutoEQ")
            } else if (eqBands.any { it.enabled && abs(it.gainDb) > 0.05f }) {
                add("EQ")
            }
        }
        if (bassEnabled && abs(bassDb) > 0.05f) add("Bass")
        if (midBassEnabled && abs(midBassDb) > 0.05f) add("Mid Bass")
        if (trebleEnabled && abs(trebleDb) > 0.05f) add("Treble")
        if (airEnabled && abs(airDb) > 0.05f) add("Air")
        if (dcBlockerEnabled) add("DC Block")
        if (balanceEnabled && abs(balance) > 0.01f) add("Balance")
        if (stereoExpansionEnabled && abs(stereoWidth - 1f) > 0.01f) add("Stereo")
        if (crossfeedEnabled) add("Crossfeed")
        if (reverbEnabled && reverbAmount > 0.01f) add("Reverb")
        if (replayGainEnabled) add("Replay Gain")
        if (dvcEnabled) add("DVC")
        if (limiterEnabled) add("Limiter")
    }
}

data class DspUiState(
    val config: DspConfig = DspConfig(),
    val customEqPresets: List<SavedEqPreset> = emptyList(),
    val autoEqQuery: String = "",
    val autoEqLoading: Boolean = false,
    val autoEqError: String? = null,
    val autoEqResults: List<AutoEqProfileSummary> = emptyList()
)

fun defaultEqBands(): List<ParametricEqBand> {
    val standardFreqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    return standardFreqs.mapIndexed { index, freq ->
        ParametricEqBand(
            id = index,
            enabled = true,
            frequencyHz = freq,
            gainDb = 0f,
            q = 1.0f
        )
    }
}
