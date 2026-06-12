package com.beatflowy.app.utils

import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.SavedEqPreset
import com.beatflowy.app.model.EqBandType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class JsonPreset(
    val name: String,
    val preamp: Float,
    val parametric: Boolean,
    val bands: List<JsonBand>
)

data class JsonBand(
    val type: Int,
    val channels: Int = 0,
    val frequency: Int,
    val q: Float,
    val gain: Float,
    val color: Int = 0
)

object PresetExporter {
    private val gson = Gson()

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
                            frequencyHz = jb.frequency.toFloat(),
                            gainDb = jb.gain,
                            q = if (jb.q == 0f) 1.41f else jb.q,
                            type = when (jb.type) {
                                0 -> EqBandType.LOW_SHELF
                                1 -> EqBandType.HIGH_SHELF
                                else -> EqBandType.PEAKING
                            }
                        )
                    }
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
