package com.beatflowy.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatflowy.app.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dsp_settings")

class DspPreferences(private val context: Context) {

    private val dataStore = context.dataStore

    val dspConfig: Flow<DspConfig> = dataStore.data.map { preferences ->
        DspConfig(
            outputMode = OutputMode.valueOf(preferences[OUTPUT_MODE] ?: OutputMode.AAUDIO.name),
            highQualityResampler = preferences[HIGH_QUALITY_RESAMPLER] ?: true,
            resamplerMode = ResamplerMode.valueOf(preferences[RESAMPLER_MODE] ?: ResamplerMode.AUTO.name),
            resamplerCutoffRatio = preferences[RESAMPLER_CUTOFF] ?: 0.97f,
            sampleFormat = SampleFormat.valueOf(preferences[SAMPLE_FORMAT] ?: SampleFormat.AUTO.name),
            preampEnabled = preferences[PREAMP_ENABLED] ?: false,
            preampDb = preferences[PREAMP_DB] ?: 0f,
            eqEnabled = preferences[EQ_ENABLED] ?: false,
            eqBands = deserializeBands(preferences[EQ_BANDS]),
            autoEqEnabled = preferences[AUTO_EQ_ENABLED] ?: false,
            autoEqProfile = deserializeAutoEqProfile(preferences[AUTO_EQ_PROFILE]),
            bassEnabled = preferences[BASS_ENABLED] ?: false,
            bassDb = preferences[BASS_DB] ?: 0f,
            midBassEnabled = preferences[MID_BASS_ENABLED] ?: false,
            midBassDb = preferences[MID_BASS_DB] ?: 0f,
            trebleEnabled = preferences[TREBLE_ENABLED] ?: false,
            trebleDb = preferences[TREBLE_DB] ?: 0f,
            airEnabled = preferences[AIR_ENABLED] ?: false,
            airDb = preferences[AIR_DB] ?: 0f,
            balanceEnabled = preferences[BALANCE_ENABLED] ?: false,
            balance = preferences[BALANCE] ?: 0f,
            stereoExpansionEnabled = preferences[STEREO_EXP_ENABLED] ?: false,
            stereoWidth = preferences[STEREO_WIDTH] ?: 1f,
            crossfeedEnabled = preferences[CROSSFEED_ENABLED] ?: false,
            crossfeedLevel = preferences[CROSSFEED_LEVEL] ?: 0.4f,
            reverbEnabled = preferences[REVERB_ENABLED] ?: false,
            reverbAmount = preferences[REVERB_AMOUNT] ?: 0f,
            reverbPreset = preferences[REVERB_PRESET] ?: "FLAT",
            reverbPredelayMs = preferences[REVERB_PREDELAY] ?: 0f,
            reverbWidth = preferences[REVERB_WIDTH] ?: 1.0f,
            reverbDamping = preferences[REVERB_DAMPING] ?: 0.5f,
            reverbRoomSize = preferences[REVERB_ROOM_SIZE] ?: 0.5f,
            reverbPredelayMix = preferences[REVERB_PREDELAY_MIX] ?: 0.62f,
            dcBlockerEnabled = preferences[DC_BLOCKER_ENABLED] ?: true,
            replayGainEnabled = preferences[REPLAY_GAIN_ENABLED] ?: false,
            replayGainOption = ReplayGainOption.valueOf(preferences[REPLAY_GAIN_OPTION] ?: ReplayGainOption.APPLY_GAIN.name),
            replayGainSource = ReplayGainSource.valueOf(preferences[REPLAY_GAIN_SOURCE] ?: ReplayGainSource.TRACK.name),
            replayGainPreamp = preferences[REPLAY_GAIN_PREAMP] ?: 0f,
            dvcEnabled = preferences[DVC_ENABLED] ?: true,
            dvcBluetoothEnabled = preferences[DVC_BT_ENABLED] ?: false,
            dvcMode = DvcMode.valueOf(preferences[DVC_MODE] ?: DvcMode.DAC.name),
            dvcLevel = preferences[DVC_LEVEL] ?: 1f
        )
    }

    suspend fun saveConfig(config: DspConfig) {
        dataStore.edit { preferences ->
            preferences[OUTPUT_MODE] = config.outputMode.name
            preferences[HIGH_QUALITY_RESAMPLER] = config.highQualityResampler
            preferences[RESAMPLER_MODE] = config.resamplerMode.name
            preferences[RESAMPLER_CUTOFF] = config.resamplerCutoffRatio
            preferences[SAMPLE_FORMAT] = config.sampleFormat.name
            preferences[PREAMP_ENABLED] = config.preampEnabled
            preferences[PREAMP_DB] = config.preampDb
            preferences[EQ_ENABLED] = config.eqEnabled
            preferences[EQ_BANDS] = serializeBands(config.eqBands)
            preferences[AUTO_EQ_ENABLED] = config.autoEqEnabled
            preferences[AUTO_EQ_PROFILE] = serializeAutoEqProfile(config.autoEqProfile)
            preferences[BASS_ENABLED] = config.bassEnabled
            preferences[BASS_DB] = config.bassDb
            preferences[MID_BASS_ENABLED] = config.midBassEnabled
            preferences[MID_BASS_DB] = config.midBassDb
            preferences[TREBLE_ENABLED] = config.trebleEnabled
            preferences[TREBLE_DB] = config.trebleDb
            preferences[AIR_ENABLED] = config.airEnabled
            preferences[AIR_DB] = config.airDb
            preferences[BALANCE_ENABLED] = config.balanceEnabled
            preferences[BALANCE] = config.balance
            preferences[STEREO_EXP_ENABLED] = config.stereoExpansionEnabled
            preferences[STEREO_WIDTH] = config.stereoWidth
            preferences[CROSSFEED_ENABLED] = config.crossfeedEnabled
            preferences[CROSSFEED_LEVEL] = config.crossfeedLevel
            preferences[REVERB_ENABLED] = config.reverbEnabled
            preferences[REVERB_AMOUNT] = config.reverbAmount
            preferences[REVERB_PRESET] = config.reverbPreset
            preferences[REVERB_PREDELAY] = config.reverbPredelayMs
            preferences[REVERB_WIDTH] = config.reverbWidth
            preferences[REVERB_DAMPING] = config.reverbDamping
            preferences[REVERB_ROOM_SIZE] = config.reverbRoomSize
            preferences[REVERB_PREDELAY_MIX] = config.reverbPredelayMix
            preferences[DC_BLOCKER_ENABLED] = config.dcBlockerEnabled
            preferences[REPLAY_GAIN_ENABLED] = config.replayGainEnabled
            preferences[REPLAY_GAIN_OPTION] = config.replayGainOption.name
            preferences[REPLAY_GAIN_SOURCE] = config.replayGainSource.name
            preferences[REPLAY_GAIN_PREAMP] = config.replayGainPreamp
            preferences[DVC_ENABLED] = config.dvcEnabled
            preferences[DVC_BT_ENABLED] = config.dvcBluetoothEnabled
            preferences[DVC_MODE] = config.dvcMode.name
            preferences[DVC_LEVEL] = config.dvcLevel
        }
    }

    private fun serializeBands(bands: List<ParametricEqBand>): String {
        val array = JSONArray()
        bands.forEach { band ->
            val obj = JSONObject()
            obj.put("id", band.id)
            obj.put("enabled", band.enabled)
            obj.put("freq", band.frequencyHz)
            obj.put("gain", band.gainDb)
            obj.put("q", band.q)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeBands(json: String?): List<ParametricEqBand> {
        if (json.isNullOrBlank()) return defaultEqBands()
        return runCatching {
            val array = JSONArray(json)
            val bands = List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                ParametricEqBand(
                    id = obj.getInt("id"),
                    enabled = obj.getBoolean("enabled"),
                    frequencyHz = obj.getDouble("freq").toFloat(),
                    gainDb = obj.getDouble("gain").toFloat(),
                    q = obj.getDouble("q").toFloat()
                )
            }
            // Migration: Only truncate if it's the specific old default pattern (32 bands, all 1k)
            if (bands.size == 32 && bands.count { it.frequencyHz == 1000f } > 30) {
                bands.take(10)
            } else {
                bands
            }
        }.getOrDefault(defaultEqBands())
    }

    private fun serializeAutoEqProfile(profile: AutoEqProfile?): String {
        if (profile == null) return ""
        val obj = JSONObject()
        obj.put("name", profile.name)
        obj.put("source", profile.source)
        obj.put("path", profile.relativePath)
        obj.put("preamp", profile.preampDb)
        obj.put("bands", serializeBands(profile.bands))
        return obj.toString()
    }

    private fun deserializeAutoEqProfile(json: String?): AutoEqProfile? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(json)
            AutoEqProfile(
                name = obj.getString("name"),
                source = obj.getString("source"),
                relativePath = obj.getString("path"),
                preampDb = obj.getDouble("preamp").toFloat(),
                bands = deserializeBands(obj.getString("bands"))
            )
        }.getOrNull()
    }

    companion object {
        private val OUTPUT_MODE = stringPreferencesKey("output_mode")
        private val HIGH_QUALITY_RESAMPLER = booleanPreferencesKey("high_quality_resampler")
        private val RESAMPLER_MODE = stringPreferencesKey("resampler_mode")
        private val RESAMPLER_CUTOFF = floatPreferencesKey("resampler_cutoff")
        private val SAMPLE_FORMAT = stringPreferencesKey("sample_format")
        private val PREAMP_ENABLED = booleanPreferencesKey("preamp_enabled")
        private val PREAMP_DB = floatPreferencesKey("preamp_db")
        private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val EQ_BANDS = stringPreferencesKey("eq_bands")
        private val AUTO_EQ_ENABLED = booleanPreferencesKey("auto_eq_enabled")
        private val AUTO_EQ_PROFILE = stringPreferencesKey("auto_eq_profile")
        private val BASS_ENABLED = booleanPreferencesKey("bass_enabled")
        private val BASS_DB = floatPreferencesKey("bass_db")
        private val MID_BASS_ENABLED = booleanPreferencesKey("mid_bass_enabled")
        private val MID_BASS_DB = floatPreferencesKey("mid_bass_db")
        private val TREBLE_ENABLED = booleanPreferencesKey("treble_enabled")
        private val TREBLE_DB = floatPreferencesKey("treble_db")
        private val AIR_ENABLED = booleanPreferencesKey("air_enabled")
        private val AIR_DB = floatPreferencesKey("air_db")
        private val BALANCE_ENABLED = booleanPreferencesKey("balance_enabled")
        private val BALANCE = floatPreferencesKey("balance")
        private val STEREO_EXP_ENABLED = booleanPreferencesKey("stereo_exp_enabled")
        private val STEREO_WIDTH = floatPreferencesKey("stereo_width")
        private val CROSSFEED_ENABLED = booleanPreferencesKey("crossfeed_enabled")
        private val CROSSFEED_LEVEL = floatPreferencesKey("crossfeed_level")
        private val REVERB_ENABLED = booleanPreferencesKey("reverb_enabled")
        private val REVERB_AMOUNT = floatPreferencesKey("reverb_amount")
        private val REVERB_PRESET = stringPreferencesKey("reverb_preset")
        private val REVERB_PREDELAY = floatPreferencesKey("reverb_predelay")
        private val REVERB_WIDTH = floatPreferencesKey("reverb_width")
        private val REVERB_DAMPING = floatPreferencesKey("reverb_damping")
        private val REVERB_ROOM_SIZE = floatPreferencesKey("reverb_room_size")
        private val REVERB_PREDELAY_MIX = floatPreferencesKey("reverb_predelay_mix")
        private val DC_BLOCKER_ENABLED = booleanPreferencesKey("dc_blocker_enabled")
        private val REPLAY_GAIN_ENABLED = booleanPreferencesKey("replay_gain_enabled")
        private val REPLAY_GAIN_OPTION = stringPreferencesKey("replay_gain_option")
        private val REPLAY_GAIN_SOURCE = stringPreferencesKey("replay_gain_source")
        private val REPLAY_GAIN_PREAMP = floatPreferencesKey("replay_gain_preamp")
        private val DVC_ENABLED = booleanPreferencesKey("dvc_enabled")
        private val DVC_BT_ENABLED = booleanPreferencesKey("dvc_bt_enabled")
        private val DVC_MODE = stringPreferencesKey("dvc_mode")
        private val DVC_LEVEL = floatPreferencesKey("dvc_level")
    }
}
