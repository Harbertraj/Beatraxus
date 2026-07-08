package com.beatflowy.app.utils

import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.SavedEqPreset
import com.beatflowy.app.model.EqBandType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

data class JsonPreset(
    @SerializedName("name") val name: String,
    @SerializedName("preamp") val preamp: Float,
    @SerializedName("parametric") val parametric: Boolean,
    @SerializedName("bands") val bands: List<JsonBand>
)

data class JsonBand(
    @SerializedName("type") val type: Int,
    @SerializedName("channels") val channels: Int = 0,
    @SerializedName("frequency") val frequency: Int,
    @SerializedName("q") val q: Float,
    @SerializedName("gain") val gain: Float,
    @SerializedName("color") val color: Int = 0
)

object PresetExporter {
    private val gson = Gson()

    private val standardFreqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private fun snapToStandardBand(freq: Float): Float =
        standardFreqs.minByOrNull { kotlin.math.abs(it - freq) } ?: freq

    fun exportToCurrentJson(name: String, preamp: Float, bands: List<ParametricEqBand>): String {
        val jsonBands = bands.map { band ->
            JsonBand(
                type = when (band.type) {
                    EqBandType.LOW_SHELF -> 0
                    EqBandType.HIGH_SHELF -> 1
                    else -> 2 // Mapping Peaking to 2 to match user's example
                },
                frequency = band.frequencyHz.toInt(),
                q = band.q,
                gain = band.gainDb
            )
        }
        val preset = JsonPreset(
            name = name,
            preamp = preamp,
            parametric = true,
            bands = jsonBands
        )
        return gson.toJson(listOf(preset))
    }

    fun parseJson(json: String): List<SavedEqPreset>? {
        return try {
            val type = object : TypeToken<List<JsonPreset>>() {}.type
            val jsonPresets: List<JsonPreset> = gson.fromJson(json, type)
            jsonPresets.map { jp ->
                SavedEqPreset(
                    name = jp.name,
                    preampDb = jp.preamp,
                    bands = jp.bands.mapIndexed { index, jb ->
                        ParametricEqBand(
                            id = index,
                            enabled = true,
                            frequencyHz = snapToStandardBand(jb.frequency.toFloat()),
                            gainDb = jb.gain,
                            q = if (jb.q == 0f) 1.41f else jb.q,
                            type = when (jb.type) {
                                0 -> EqBandType.LOW_SHELF
                                1 -> EqBandType.HIGH_SHELF
                                else -> EqBandType.PEAKING
                            }
                        )
                    }.groupBy { it.frequencyHz }
                        .map { (_, group) ->
                            group.first().copy(gainDb = group.map { it.gainDb }.average().toFloat())
                        }
                        .sortedBy { it.frequencyHz }
                        .mapIndexed { i, band -> band.copy(id = i) }
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
