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

data class DspConfig(
    val resamplerEnabled: Boolean = false,
    val targetSampleRate: Int = 44_100,
    val resamplerCutoffRatio: Float = 0.97f,
    val preampEnabled: Boolean = false,
    val preampDb: Float = 0f,
    val eqEnabled: Boolean = false,
    val eqBands: List<ParametricEqBand> = defaultEqBands(),
    val autoEqEnabled: Boolean = false,
    val autoEqProfile: AutoEqProfile? = null,
    val bassEnabled: Boolean = false,
    val bassDb: Float = 0f,
    val trebleEnabled: Boolean = false,
    val trebleDb: Float = 0f,
    val balanceEnabled: Boolean = false,
    val balance: Float = 0f,
    val stereoExpansionEnabled: Boolean = false,
    val stereoWidth: Float = 1f,
    val reverbEnabled: Boolean = false,
    val reverbAmount: Float = 0f
) {
    fun activeEffects(): List<String> = buildList {
        if (resamplerEnabled) add("Resampler")
        if (preampEnabled && abs(preampDb) > 0.05f) add("Preamp")
        if (eqEnabled && eqBands.any { it.enabled && abs(it.gainDb) > 0.05f }) add("EQ")
        if (autoEqEnabled && autoEqProfile != null && autoEqProfile.bands.isNotEmpty()) add("AutoEQ")
        if (bassEnabled && abs(bassDb) > 0.05f) add("Bass")
        if (trebleEnabled && abs(trebleDb) > 0.05f) add("Treble")
        if (balanceEnabled && abs(balance) > 0.01f) add("Balance")
        if (stereoExpansionEnabled && abs(stereoWidth - 1f) > 0.01f) add("Stereo")
        if (reverbEnabled && reverbAmount > 0.01f) add("Reverb")
    }
}

data class DspUiState(
    val config: DspConfig = DspConfig(),
    val autoEqQuery: String = "",
    val autoEqLoading: Boolean = false,
    val autoEqError: String? = null,
    val autoEqResults: List<AutoEqProfileSummary> = emptyList()
)

fun defaultEqBands(): List<ParametricEqBand> {
    val freqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    return freqs.mapIndexed { index, freq ->
        ParametricEqBand(
            id = index,
            enabled = true,
            frequencyHz = freq,
            gainDb = 0f,
            q = 1.0f
        )
    }
}
