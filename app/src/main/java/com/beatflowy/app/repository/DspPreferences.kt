package com.beatflowy.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatflowy.app.model.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dsp_settings")

class DspPreferences(private val context: Context) {

    private val dataStore = context.dataStore
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    private val _deviceIdFlow = MutableStateFlow(getCurrentDeviceId())
    val deviceIdFlow: Flow<String> = _deviceIdFlow.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                _deviceIdFlow.value = getCurrentDeviceId()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                _deviceIdFlow.value = getCurrentDeviceId()
            }
        }, null)
    }

    fun getCurrentDeviceId(): String {
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val best = devices.filter { it.isSink }.minByOrNull { dev ->
            when (dev.type) {
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> 1
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
                android.media.AudioDeviceInfo.TYPE_HDMI -> 3
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
                else -> 10
            }
        }
        return best?.let { "${it.productName}_${it.type}" } ?: "default"
    }

    fun getCurrentDeviceLabel(): String {
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val best = devices.filter { it.isSink }.minByOrNull { dev ->
            when (dev.type) {
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> 1
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
                android.media.AudioDeviceInfo.TYPE_HDMI -> 3
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
                else -> 10
            }
        }
        return when (best?.type) {
            null, android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "This Device"
            else -> best.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Connected Device"
        }
    }

    val dspConfig: Flow<DspConfig> = combine(dataStore.data, deviceIdFlow) { preferences, deviceId ->
        mapPreferencesToConfig(preferences, deviceId)
    }

    fun dspConfigForDevice(deviceId: String): Flow<DspConfig> = dataStore.data.map { preferences ->
        mapPreferencesToConfig(preferences, deviceId)
    }

    private fun mapPreferencesToConfig(preferences: Preferences, deviceId: String? = null): DspConfig {
        fun <T> pref(key: Preferences.Key<T>, def: T): T {
            if (deviceId != null) {
                @Suppress("UNCHECKED_CAST")
                val k = when (def) {
                    is String -> stringPreferencesKey("dev_${deviceId}_${key.name}")
                    is Boolean -> booleanPreferencesKey("dev_${deviceId}_${key.name}")
                    is Float -> floatPreferencesKey("dev_${deviceId}_${key.name}")
                    is Int -> intPreferencesKey("dev_${deviceId}_${key.name}")
                    is Long -> longPreferencesKey("dev_${deviceId}_${key.name}")
                    is Double -> doublePreferencesKey("dev_${deviceId}_${key.name}")
                    else -> null
                } as? Preferences.Key<T>
                if (k != null) return preferences[k] ?: preferences[key] ?: def
            }
            return preferences[key] ?: def
        }

        fun <T> prefNullable(key: Preferences.Key<T>, def: T? = null): T? {
            if (deviceId != null) {
                // For nullable keys, we mostly have Strings (like EQ_BANDS)
                val k = stringPreferencesKey("dev_${deviceId}_${key.name}")
                @Suppress("UNCHECKED_CAST")
                return (preferences[k] as? T) ?: preferences[key] ?: def
            }
            return preferences[key] ?: def
        }

        return DspConfig(
            outputMode = OutputMode.fromName(prefNullable(OUTPUT_MODE)),
            usbExclusiveEnabled = pref(USB_EXCLUSIVE_ENABLED, false),
            bitPerfectEnabled = pref(BIT_PERFECT_ENABLED, false),
            monoEnabled = pref(MONO_ENABLED, false),
            bitPerfectUnbypassEq = pref(BIT_PERFECT_UNBYPASS_EQ, false),
            bitPerfectUnbypassResample = pref(BIT_PERFECT_UNBYPASS_RESAMPLE, false),
            bitPerfectUnbypassSoxr = pref(BIT_PERFECT_UNBYPASS_SOXR, false),
            bitPerfectUnbypassReverb = pref(BIT_PERFECT_UNBYPASS_REVERB, false),
            bitPerfectUnbypassDithering = pref(BIT_PERFECT_UNBYPASS_DITHERING, false),
            bitPerfectUnbypassFloat64 = pref(BIT_PERFECT_UNBYPASS_FLOAT64, false),
            bitPerfectUnbypassLimiter = pref(BIT_PERFECT_UNBYPASS_LIMITER, false),
            mmapRequestedBufferSizeFrames = pref(MMAP_BUFFER_FRAMES, 96),
            highQualityResampler = pref(HIGH_QUALITY_RESAMPLER, true),
            soxrQuality = runCatching {
                SoxrQuality.valueOf(prefNullable(SOXR_QUALITY) ?: SoxrQuality.HIGH.name)
            }.getOrDefault(SoxrQuality.HIGH),
            float64Enabled = pref(FLOAT64_ENABLED, false),
            resamplerMode = ResamplerMode.valueOf(prefNullable(RESAMPLER_MODE) ?: ResamplerMode.AUTO.name),
            resamplerCutoffRatio = pref(RESAMPLER_CUTOFF, 0.97f),
            sampleFormat = SampleFormat.valueOf(prefNullable(SAMPLE_FORMAT) ?: SampleFormat.AUTO.name),
            preampEnabled = pref(PREAMP_ENABLED, false),
            preampDb = pref(PREAMP_DB, 0f),
            eqEnabled = pref(EQ_ENABLED, false),
            eqBands = deserializeBands(prefNullable(EQ_BANDS)),
            eqMasterGainDb = pref(EQ_MASTER_GAIN, 0f),
            eqPhaseMode = runCatching {
                EqPhaseMode.valueOf(prefNullable(EQ_PHASE_MODE) ?: EqPhaseMode.MINIMUM_PHASE.name)
            }.getOrDefault(EqPhaseMode.MINIMUM_PHASE),
            autoEqEnabled = pref(AUTO_EQ_ENABLED, false),
            autoEqProfile = deserializeAutoEqProfile(prefNullable(AUTO_EQ_PROFILE)),
            bassEnabled = pref(BASS_ENABLED, false),
            bassDb = pref(BASS_DB, 0f),
            trebleEnabled = pref(TREBLE_ENABLED, false),
            trebleDb = pref(TREBLE_DB, 0f),
            airEnabled = pref(AIR_ENABLED, false),
            airDb = pref(AIR_DB, 0f),
            balanceEnabled = pref(BALANCE_ENABLED, false),
            balance = pref(BALANCE, 0f),
            stereoExpansionEnabled = pref(STEREO_EXP_ENABLED, false),
            stereoWidth = pref(STEREO_WIDTH, 1f),
            crossfeedEnabled = pref(CROSSFEED_ENABLED, false),
            crossfeedLevel = pref(CROSSFEED_LEVEL, 0.4f),
            spatialAudioEnabled = pref(SPATIAL_AUDIO_ENABLED, false),
            soundStageEnabled = pref(SOUND_STAGE_ENABLED, false),
            spatialTouchEnabled = pref(SPATIAL_TOUCH_ENABLED, false),
            spatialAudioIntensity = pref(SPATIAL_AUDIO_INTENSITY, 0.6f),
            soundStageSelectedNode = pref(SOUND_STAGE_SELECTED_NODE, "Vocals"),
            soundStageNodePositions = deserializeNodePositions(prefNullable(SOUND_STAGE_NODE_POSITIONS)),
            soundStageWidth = pref(SOUND_STAGE_WIDTH, 1.0f),
            spatialStageWidth = pref(SPATIAL_STAGE_WIDTH, 1.0f),
            soundStageCenterLock = pref(SOUND_STAGE_CENTER_LOCK, 0f),
            hrtfMode = HrtfMode.valueOf(prefNullable(HRTF_MODE) ?: HrtfMode.NATURAL_BALANCED.name),
            reverbEnabled = pref(REVERB_ENABLED, false),
            reverbAmount = pref(REVERB_AMOUNT, 0f),
            reverbPreset = pref(REVERB_PRESET, "FLAT"),
            reverbPredelayMs = pref(REVERB_PREDELAY, 0f),
            reverbWidth = pref(REVERB_WIDTH, 1.0f),
            reverbDamping = pref(REVERB_DAMPING, 0.5f),
            reverbRoomSize = pref(REVERB_ROOM_SIZE, 0.5f),
            reverbDecay = pref(REVERB_DECAY, 0.5f),
            reverbPredelayMix = pref(REVERB_PREDELAY_MIX, 0.62f),
            dcBlockerEnabled = pref(DC_BLOCKER_ENABLED, false),
            replayGainEnabled = pref(REPLAY_GAIN_ENABLED, false),
            replayGainOption = ReplayGainOption.valueOf(prefNullable(REPLAY_GAIN_OPTION) ?: ReplayGainOption.APPLY_GAIN.name),
            replayGainSource = ReplayGainSource.valueOf(prefNullable(REPLAY_GAIN_SOURCE) ?: ReplayGainSource.TRACK.name),
            replayGainPreamp = pref(REPLAY_GAIN_PREAMP, 0f),
            dvcEnabled = pref(DVC_ENABLED, true),
            dvcBluetoothEnabled = pref(DVC_BT_ENABLED, false),
            rmsDvcEnabled = pref(RMS_DVC_ENABLED, false),
            rmsLevelerEnabled = pref(RMS_LEVELER_ENABLED, false),
            dvcMode = DvcMode.valueOf(prefNullable(DVC_MODE) ?: DvcMode.DAC.name),
            dvcLevel = pref(DVC_LEVEL, 1f),
            compensateDvcVolumeEnabled = pref(COMPENSATE_DVC_VOLUME, false),
            ditherEnabled = pref(DITHER_ENABLED, true),
            ditherType = runCatching {
                DitherType.valueOf(prefNullable(DITHER_TYPE) ?: DitherType.SHAPED.name)
            }.getOrDefault(DitherType.SHAPED),
            limiterEnabled = pref(LIMITER_ENABLED, false) && !pref(SOFT_LIMITER_ENABLED, false),
            softLimiterEnabled = pref(SOFT_LIMITER_ENABLED, false),
            limiterThresholdDb = pref(LIMITER_THRESHOLD_DB, -1.0f),
            limiterAttackMs = pref(LIMITER_ATTACK_MS, 0.5f),
            limiterReleaseMs = pref(LIMITER_RELEASE_MS, 80f),
            settingsLocked = pref(SETTINGS_LOCKED, false),
            playbackSpeed = pref(PLAYBACK_SPEED, 1.0f),
            preservePitch = pref(PRESERVE_PITCH, true),
            crossfadeDurationS = pref(CROSSFADE_DURATION, 0),
            headroomManagementEnabled = pref(HEADROOM_MANAGEMENT, true),
            noHeadroomGainEnabled = pref(NO_HEADROOM_GAIN, false),
            hardwareVolumeEnabled = pref(HARDWARE_VOLUME, false),
            headphoneSimulationEnabled = pref(HEADPHONE_SIM_ENABLED, false),
            headphoneSimulationProfile = deserializeAutoEqProfile(prefNullable(HEADPHONE_SIM_PROFILE)),
            customUsbDriverEnabled = pref(CUSTOM_USB_DRIVER, false)
        )
    }

    suspend fun saveConfig(config: DspConfig, deviceId: String? = null) {
        dataStore.edit { preferences ->
            if (deviceId != null) {
                val knownDevices = (preferences[KNOWN_DEVICE_IDS] ?: emptySet()) + deviceId
                preferences[KNOWN_DEVICE_IDS] = knownDevices
            }
            fun <T> set(key: Preferences.Key<T>, value: Any?) {
                if (value == null) return
                val k = if (deviceId != null) {
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is String -> stringPreferencesKey("dev_${deviceId}_${key.name}")
                        is Boolean -> booleanPreferencesKey("dev_${deviceId}_${key.name}")
                        is Float -> floatPreferencesKey("dev_${deviceId}_${key.name}")
                        is Int -> intPreferencesKey("dev_${deviceId}_${key.name}")
                        is Long -> longPreferencesKey("dev_${deviceId}_${key.name}")
                        is Double -> doublePreferencesKey("dev_${deviceId}_${key.name}")
                        else -> null
                    } as? Preferences.Key<T>
                } else key
                
                if (k != null) {
                    @Suppress("UNCHECKED_CAST")
                    preferences[k] = value as T
                }
            }

            set(OUTPUT_MODE, config.outputMode.name)
            set(USB_EXCLUSIVE_ENABLED, config.usbExclusiveEnabled)
            set(BIT_PERFECT_ENABLED, config.bitPerfectEnabled)
            set(MONO_ENABLED, config.monoEnabled)
            set(BIT_PERFECT_UNBYPASS_EQ, config.bitPerfectUnbypassEq)
            set(BIT_PERFECT_UNBYPASS_RESAMPLE, config.bitPerfectUnbypassResample)
            set(BIT_PERFECT_UNBYPASS_SOXR, config.bitPerfectUnbypassSoxr)
            set(BIT_PERFECT_UNBYPASS_REVERB, config.bitPerfectUnbypassReverb)
            set(BIT_PERFECT_UNBYPASS_DITHERING, config.bitPerfectUnbypassDithering)
            set(BIT_PERFECT_UNBYPASS_FLOAT64, config.bitPerfectUnbypassFloat64)
            set(BIT_PERFECT_UNBYPASS_LIMITER, config.bitPerfectUnbypassLimiter)
            set(MMAP_BUFFER_FRAMES, config.mmapRequestedBufferSizeFrames)
            set(HIGH_QUALITY_RESAMPLER, config.highQualityResampler)
            set(SOXR_QUALITY, config.soxrQuality.name)
            set(FLOAT64_ENABLED, config.float64Enabled)
            set(RESAMPLER_MODE, config.resamplerMode.name)
            set(RESAMPLER_CUTOFF, config.resamplerCutoffRatio)
            set(SAMPLE_FORMAT, config.sampleFormat.name)
            set(PREAMP_ENABLED, config.preampEnabled)
            set(PREAMP_DB, config.preampDb)
            set(EQ_ENABLED, config.eqEnabled)
            set(EQ_BANDS, serializeBands(config.eqBands))
            set(EQ_MASTER_GAIN, config.eqMasterGainDb)
            set(EQ_PHASE_MODE, config.eqPhaseMode.name)
            set(AUTO_EQ_ENABLED, config.autoEqEnabled)
            set(AUTO_EQ_PROFILE, serializeAutoEqProfile(config.autoEqProfile))
            set(BASS_ENABLED, config.bassEnabled)
            set(BASS_DB, config.bassDb)
            set(TREBLE_ENABLED, config.trebleEnabled)
            set(TREBLE_DB, config.trebleDb)
            set(AIR_ENABLED, config.airEnabled)
            set(AIR_DB, config.airDb)
            set(BALANCE_ENABLED, config.balanceEnabled)
            set(BALANCE, config.balance)
            set(STEREO_EXP_ENABLED, config.stereoExpansionEnabled)
            set(STEREO_WIDTH, config.stereoWidth)
            set(CROSSFEED_ENABLED, config.crossfeedEnabled)
            set(CROSSFEED_LEVEL, config.crossfeedLevel)
            set(SPATIAL_AUDIO_ENABLED, config.spatialAudioEnabled)
            set(SOUND_STAGE_ENABLED, config.soundStageEnabled)
            set(SPATIAL_TOUCH_ENABLED, config.spatialTouchEnabled)
            set(SPATIAL_AUDIO_INTENSITY, config.spatialAudioIntensity)
            set(SOUND_STAGE_SELECTED_NODE, config.soundStageSelectedNode)
            set(SOUND_STAGE_NODE_POSITIONS, serializeNodePositions(config.soundStageNodePositions))
            set(SOUND_STAGE_WIDTH, config.soundStageWidth)
            set(SPATIAL_STAGE_WIDTH, config.spatialStageWidth)
            set(SOUND_STAGE_CENTER_LOCK, config.soundStageCenterLock)
            set(HRTF_MODE, config.hrtfMode.name)
            set(REVERB_ENABLED, config.reverbEnabled)
            set(REVERB_AMOUNT, config.reverbAmount)
            set(REVERB_PRESET, config.reverbPreset)
            set(REVERB_PREDELAY, config.reverbPredelayMs)
            set(REVERB_WIDTH, config.reverbWidth)
            set(REVERB_DAMPING, config.reverbDamping)
            set(REVERB_ROOM_SIZE, config.reverbRoomSize)
            set(REVERB_DECAY, config.reverbDecay)
            set(REVERB_PREDELAY_MIX, config.reverbPredelayMix)
            set(DC_BLOCKER_ENABLED, config.dcBlockerEnabled)
            set(REPLAY_GAIN_ENABLED, config.replayGainEnabled)
            set(REPLAY_GAIN_OPTION, config.replayGainOption.name)
            set(REPLAY_GAIN_SOURCE, config.replayGainSource.name)
            set(REPLAY_GAIN_PREAMP, config.replayGainPreamp)
            set(DVC_ENABLED, config.dvcEnabled)
            set(DVC_BT_ENABLED, config.dvcBluetoothEnabled)
            set(RMS_DVC_ENABLED, config.rmsDvcEnabled)
            set(RMS_LEVELER_ENABLED, config.rmsLevelerEnabled)
            set(DVC_MODE, config.dvcMode.name)
            set(DVC_LEVEL, config.dvcLevel)
            set(COMPENSATE_DVC_VOLUME, config.compensateDvcVolumeEnabled)
            set(DITHER_ENABLED, config.ditherEnabled)
            set(DITHER_TYPE, config.ditherType.name)
            set(LIMITER_ENABLED, config.limiterEnabled)
            set(LIMITER_THRESHOLD_DB, config.limiterThresholdDb)
            set(LIMITER_ATTACK_MS, config.limiterAttackMs)
            set(LIMITER_RELEASE_MS, config.limiterReleaseMs)
            set(SOFT_LIMITER_ENABLED, config.softLimiterEnabled)
            set(SETTINGS_LOCKED, config.settingsLocked)
            set(PLAYBACK_SPEED, config.playbackSpeed)
            set(PRESERVE_PITCH, config.preservePitch)
            set(CROSSFADE_DURATION, config.crossfadeDurationS)
            set(HEADROOM_MANAGEMENT, config.headroomManagementEnabled)
            set(NO_HEADROOM_GAIN, config.noHeadroomGainEnabled)
            set(HARDWARE_VOLUME, config.hardwareVolumeEnabled)
            set(HEADPHONE_SIM_ENABLED, config.headphoneSimulationEnabled)
            set(HEADPHONE_SIM_PROFILE, serializeAutoEqProfile(config.headphoneSimulationProfile))
            set(CUSTOM_USB_DRIVER, config.customUsbDriverEnabled)
        }
    }

    suspend fun exportPreferences(): Map<String, Any> {
        return dataStore.data.first().asMap().mapKeys { it.key.name }.filterValues { it != null } as Map<String, Any>
    }

    suspend fun importPreferences(map: Map<String, Any>) {
        dataStore.edit { preferences ->
            map.forEach { (keyName, value) ->
                when (value) {
                    is String -> preferences[stringPreferencesKey(keyName)] = value
                    is Boolean -> preferences[booleanPreferencesKey(keyName)] = value
                    is Float -> preferences[floatPreferencesKey(keyName)] = value
                    is Int -> preferences[intPreferencesKey(keyName)] = value
                    is Long -> preferences[longPreferencesKey(keyName)] = value
                    is Double -> {
                        // Gson deserializes all numbers as Double. 
                        // We need to be careful. Most of our numbers are Float or Int.
                        if (keyName.contains("duration") || keyName.contains("frames") || keyName.contains("count")) {
                            preferences[intPreferencesKey(keyName)] = value.toInt()
                        } else {
                            preferences[floatPreferencesKey(keyName)] = value.toFloat()
                        }
                    }
                    is List<*> -> {
                        if (value.all { it is String }) {
                            @Suppress("UNCHECKED_CAST")
                            preferences[stringSetPreferencesKey(keyName)] = (value as List<String>).toSet()
                        }
                    }
                }
            }
        }
    }

    suspend fun clearDeviceOverrides(deviceId: String) {
        dataStore.edit { preferences ->
            val prefix = "dev_${deviceId}_"
            preferences.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { preferences.remove(it) }
        }
    }

    suspend fun applyConfigToAllDevices(config: DspConfig) {
        dataStore.edit { preferences ->
            val knownDevices = preferences[KNOWN_DEVICE_IDS] ?: emptySet()
            knownDevices.forEach { deviceId ->
                fun <T> set(key: Preferences.Key<T>, value: Any?) {
                    if (value == null) return
                    @Suppress("UNCHECKED_CAST")
                    val k = when (value) {
                        is String -> stringPreferencesKey("dev_${deviceId}_${key.name}")
                        is Boolean -> booleanPreferencesKey("dev_${deviceId}_${key.name}")
                        is Float -> floatPreferencesKey("dev_${deviceId}_${key.name}")
                        is Int -> intPreferencesKey("dev_${deviceId}_${key.name}")
                        is Long -> longPreferencesKey("dev_${deviceId}_${key.name}")
                        is Double -> doublePreferencesKey("dev_${deviceId}_${key.name}")
                        else -> null
                    } as? Preferences.Key<T>

                    if (k != null) {
                        @Suppress("UNCHECKED_CAST")
                        preferences[k] = value as T
                    }
                }

                set(OUTPUT_MODE, config.outputMode.name)
                set(USB_EXCLUSIVE_ENABLED, config.usbExclusiveEnabled)
                set(BIT_PERFECT_ENABLED, config.bitPerfectEnabled)
                set(MONO_ENABLED, config.monoEnabled)
                set(BIT_PERFECT_UNBYPASS_EQ, config.bitPerfectUnbypassEq)
                set(BIT_PERFECT_UNBYPASS_RESAMPLE, config.bitPerfectUnbypassResample)
                set(BIT_PERFECT_UNBYPASS_SOXR, config.bitPerfectUnbypassSoxr)
                set(BIT_PERFECT_UNBYPASS_REVERB, config.bitPerfectUnbypassReverb)
                set(BIT_PERFECT_UNBYPASS_DITHERING, config.bitPerfectUnbypassDithering)
                set(BIT_PERFECT_UNBYPASS_FLOAT64, config.bitPerfectUnbypassFloat64)
                set(BIT_PERFECT_UNBYPASS_LIMITER, config.bitPerfectUnbypassLimiter)
                set(MMAP_BUFFER_FRAMES, config.mmapRequestedBufferSizeFrames)
                set(HIGH_QUALITY_RESAMPLER, config.highQualityResampler)
                set(SOXR_QUALITY, config.soxrQuality.name)
                set(FLOAT64_ENABLED, config.float64Enabled)
                set(RESAMPLER_MODE, config.resamplerMode.name)
                set(RESAMPLER_CUTOFF, config.resamplerCutoffRatio)
                set(SAMPLE_FORMAT, config.sampleFormat.name)
                set(PREAMP_ENABLED, config.preampEnabled)
                set(PREAMP_DB, config.preampDb)
                set(EQ_ENABLED, config.eqEnabled)
                set(EQ_BANDS, serializeBands(config.eqBands))
                set(EQ_MASTER_GAIN, config.eqMasterGainDb)
                set(EQ_PHASE_MODE, config.eqPhaseMode.name)
                set(AUTO_EQ_ENABLED, config.autoEqEnabled)
                set(AUTO_EQ_PROFILE, serializeAutoEqProfile(config.autoEqProfile))
                set(BASS_ENABLED, config.bassEnabled)
                set(BASS_DB, config.bassDb)
                set(TREBLE_ENABLED, config.trebleEnabled)
                set(TREBLE_DB, config.trebleDb)
                set(AIR_ENABLED, config.airEnabled)
                set(AIR_DB, config.airDb)
                set(BALANCE_ENABLED, config.balanceEnabled)
                set(BALANCE, config.balance)
                set(STEREO_EXP_ENABLED, config.stereoExpansionEnabled)
                set(STEREO_WIDTH, config.stereoWidth)
                set(CROSSFEED_ENABLED, config.crossfeedEnabled)
                set(CROSSFEED_LEVEL, config.crossfeedLevel)
                set(SPATIAL_AUDIO_ENABLED, config.spatialAudioEnabled)
                set(SOUND_STAGE_ENABLED, config.soundStageEnabled)
                set(SPATIAL_TOUCH_ENABLED, config.spatialTouchEnabled)
                set(SPATIAL_AUDIO_INTENSITY, config.spatialAudioIntensity)
                set(SOUND_STAGE_SELECTED_NODE, config.soundStageSelectedNode)
                set(SOUND_STAGE_NODE_POSITIONS, serializeNodePositions(config.soundStageNodePositions))
                set(SOUND_STAGE_WIDTH, config.soundStageWidth)
                set(SPATIAL_STAGE_WIDTH, config.spatialStageWidth)
                set(SOUND_STAGE_CENTER_LOCK, config.soundStageCenterLock)
                set(HRTF_MODE, config.hrtfMode.name)
                set(REVERB_ENABLED, config.reverbEnabled)
                set(REVERB_AMOUNT, config.reverbAmount)
                set(REVERB_PRESET, config.reverbPreset)
                set(REVERB_PREDELAY, config.reverbPredelayMs)
                set(REVERB_WIDTH, config.reverbWidth)
                set(REVERB_DAMPING, config.reverbDamping)
                set(REVERB_ROOM_SIZE, config.reverbRoomSize)
                set(REVERB_DECAY, config.reverbDecay)
                set(REVERB_PREDELAY_MIX, config.reverbPredelayMix)
                set(DC_BLOCKER_ENABLED, config.dcBlockerEnabled)
                set(REPLAY_GAIN_ENABLED, config.replayGainEnabled)
                set(REPLAY_GAIN_OPTION, config.replayGainOption.name)
                set(REPLAY_GAIN_SOURCE, config.replayGainSource.name)
                set(REPLAY_GAIN_PREAMP, config.replayGainPreamp)
                set(DVC_ENABLED, config.dvcEnabled)
                set(DVC_BT_ENABLED, config.dvcBluetoothEnabled)
                set(RMS_DVC_ENABLED, config.rmsDvcEnabled)
                set(RMS_LEVELER_ENABLED, config.rmsLevelerEnabled)
                set(DVC_MODE, config.dvcMode.name)
                set(DVC_LEVEL, config.dvcLevel)
                set(COMPENSATE_DVC_VOLUME, config.compensateDvcVolumeEnabled)
                set(DITHER_ENABLED, config.ditherEnabled)
                set(DITHER_TYPE, config.ditherType.name)
                set(LIMITER_ENABLED, config.limiterEnabled)
                set(LIMITER_THRESHOLD_DB, config.limiterThresholdDb)
                set(LIMITER_ATTACK_MS, config.limiterAttackMs)
                set(LIMITER_RELEASE_MS, config.limiterReleaseMs)
                set(SOFT_LIMITER_ENABLED, config.softLimiterEnabled)
                set(SETTINGS_LOCKED, config.settingsLocked)
                set(PLAYBACK_SPEED, config.playbackSpeed)
                set(PRESERVE_PITCH, config.preservePitch)
                set(CROSSFADE_DURATION, config.crossfadeDurationS)
                set(HEADROOM_MANAGEMENT, config.headroomManagementEnabled)
                set(NO_HEADROOM_GAIN, config.noHeadroomGainEnabled)
                set(HARDWARE_VOLUME, config.hardwareVolumeEnabled)
                set(HEADPHONE_SIM_ENABLED, config.headphoneSimulationEnabled)
                set(HEADPHONE_SIM_PROFILE, serializeAutoEqProfile(config.headphoneSimulationProfile))
                set(CUSTOM_USB_DRIVER, config.customUsbDriverEnabled)
            }
        }
    }

    fun listKnownDeviceIds(): Flow<Set<String>> = dataStore.data.map { it[KNOWN_DEVICE_IDS] ?: emptySet() }

    private fun serializeBands(bands: List<ParametricEqBand>): String {
        val array = JSONArray()
        bands.forEach { band ->
            val obj = JSONObject()
            obj.put("id", band.id)
            obj.put("enabled", band.enabled)
            obj.put("freq", band.frequencyHz)
            obj.put("gain", band.gainDb)
            obj.put("q", band.q)
            obj.put("type", band.type.name)
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
                    q = obj.getDouble("q").toFloat(),
                    type = runCatching {
                        EqBandType.valueOf(obj.optString("type", EqBandType.PEAKING.name))
                    }.getOrDefault(EqBandType.PEAKING)
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

    private fun serializeNodePositions(positions: Map<String, SoundStageNodePosition>): String {
        val obj = JSONObject()
        positions.forEach { (name, pos) ->
            val nodeObj = JSONObject()
            nodeObj.put("az", pos.azimuth)
            nodeObj.put("el", pos.elevation)
            nodeObj.put("dist", pos.distance)
            obj.put(name, nodeObj)
        }
        return obj.toString()
    }

    private fun deserializeNodePositions(json: String?): Map<String, SoundStageNodePosition> {
        val defaultPositions = mapOf(
            "Vocals" to SoundStageNodePosition(0f, 0f, 2.0f),
            "Drums" to SoundStageNodePosition(45f, 0f, 2.8f),
            "Keys" to SoundStageNodePosition(90f, 0f, 1.8f),
            "Lead Guitar" to SoundStageNodePosition(135f, 0f, 2.3f),
            "Ambience" to SoundStageNodePosition(180f, 0f, 3.5f),
            "Backing Vocals" to SoundStageNodePosition(225f, 0f, 2.5f),
            "Bass" to SoundStageNodePosition(270f, 0f, 2.2f),
            "Guitar" to SoundStageNodePosition(315f, 0f, 2.6f)
        )
        if (json.isNullOrBlank()) return defaultPositions
        return runCatching {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, SoundStageNodePosition>()
            obj.keys().forEach { name ->
                val nodeObj = obj.getJSONObject(name)
                map[name] = SoundStageNodePosition(
                    azimuth = nodeObj.getDouble("az").toFloat(),
                    elevation = nodeObj.getDouble("el").toFloat(),
                    distance = nodeObj.getDouble("dist").toFloat()
                )
            }
            map
        }.getOrDefault(defaultPositions)
    }

    companion object {
        private val OUTPUT_MODE = stringPreferencesKey("output_mode")
        private val USB_EXCLUSIVE_ENABLED = booleanPreferencesKey("usb_exclusive_enabled")
        private val BIT_PERFECT_ENABLED = booleanPreferencesKey("bit_perfect_enabled")
        private val BIT_PERFECT_UNBYPASS_EQ = booleanPreferencesKey("bit_perfect_unbypass_eq")
        private val BIT_PERFECT_UNBYPASS_RESAMPLE = booleanPreferencesKey("bit_perfect_unbypass_resample")
        private val BIT_PERFECT_UNBYPASS_SOXR = booleanPreferencesKey("bit_perfect_unbypass_soxr")
        private val BIT_PERFECT_UNBYPASS_REVERB = booleanPreferencesKey("bit_perfect_unbypass_reverb")
        private val BIT_PERFECT_UNBYPASS_DITHERING = booleanPreferencesKey("bit_perfect_unbypass_dithering")
        private val BIT_PERFECT_UNBYPASS_FLOAT64 = booleanPreferencesKey("bit_perfect_unbypass_float64")
        private val BIT_PERFECT_UNBYPASS_LIMITER = booleanPreferencesKey("bit_perfect_unbypass_limiter")
        private val MMAP_BUFFER_FRAMES = intPreferencesKey("mmap_buffer_frames")
        private val SOXR_QUALITY = stringPreferencesKey("soxr_quality")
        private val FLOAT64_ENABLED = booleanPreferencesKey("float64_enabled")
        private val HIGH_QUALITY_RESAMPLER = booleanPreferencesKey("high_quality_resampler")
        private val RESAMPLER_MODE = stringPreferencesKey("resampler_mode")
        private val RESAMPLER_CUTOFF = floatPreferencesKey("resampler_cutoff")
        private val SAMPLE_FORMAT = stringPreferencesKey("sample_format")
        private val PREAMP_ENABLED = booleanPreferencesKey("preamp_enabled")
        private val PREAMP_DB = floatPreferencesKey("preamp_db")
        private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val EQ_BANDS = stringPreferencesKey("eq_bands")
        private val EQ_MASTER_GAIN = floatPreferencesKey("eq_master_gain")
        private val EQ_PHASE_MODE = stringPreferencesKey("eq_phase_mode")
        private val AUTO_EQ_ENABLED = booleanPreferencesKey("auto_eq_enabled")
        private val AUTO_EQ_PROFILE = stringPreferencesKey("auto_eq_profile")
        private val BASS_ENABLED = booleanPreferencesKey("bass_enabled")
        private val BASS_DB = floatPreferencesKey("bass_db")
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
        private val SPATIAL_AUDIO_ENABLED = booleanPreferencesKey("spatial_audio_enabled")
        private val SOUND_STAGE_ENABLED = booleanPreferencesKey("sound_stage_enabled")
        private val SPATIAL_TOUCH_ENABLED = booleanPreferencesKey("spatial_touch_enabled")
        private val SPATIAL_AUDIO_INTENSITY = floatPreferencesKey("spatial_audio_intensity")
        private val SOUND_STAGE_SELECTED_NODE = stringPreferencesKey("sound_stage_selected_node")
        private val SOUND_STAGE_NODE_POSITIONS = stringPreferencesKey("sound_stage_node_positions")
        private val SOUND_STAGE_WIDTH = floatPreferencesKey("sound_stage_width")
        private val SPATIAL_STAGE_WIDTH = floatPreferencesKey("spatial_stage_width")
        private val SOUND_STAGE_CENTER_LOCK = floatPreferencesKey("sound_stage_center_lock")
        private val HRTF_MODE = stringPreferencesKey("hrtf_mode")
        private val REVERB_ENABLED = booleanPreferencesKey("reverb_enabled")
        private val REVERB_AMOUNT = floatPreferencesKey("reverb_amount")
        private val REVERB_PRESET = stringPreferencesKey("reverb_preset")
        private val REVERB_PREDELAY = floatPreferencesKey("reverb_predelay")
        private val REVERB_WIDTH = floatPreferencesKey("reverb_width")
        private val REVERB_DAMPING = floatPreferencesKey("reverb_damping")
        private val REVERB_ROOM_SIZE = floatPreferencesKey("reverb_room_size")
        private val REVERB_DECAY = floatPreferencesKey("reverb_decay")
        private val REVERB_PREDELAY_MIX = floatPreferencesKey("reverb_predelay_mix")
        private val DC_BLOCKER_ENABLED = booleanPreferencesKey("dc_blocker_enabled")
        private val REPLAY_GAIN_ENABLED = booleanPreferencesKey("replay_gain_enabled")
        private val REPLAY_GAIN_OPTION = stringPreferencesKey("replay_gain_option")
        private val REPLAY_GAIN_SOURCE = stringPreferencesKey("replay_gain_source")
        private val REPLAY_GAIN_PREAMP = floatPreferencesKey("replay_gain_preamp")
        private val DVC_ENABLED = booleanPreferencesKey("dvc_enabled")
        private val DVC_BT_ENABLED = booleanPreferencesKey("dvc_bt_enabled")
        private val RMS_DVC_ENABLED = booleanPreferencesKey("rms_dvc_enabled")
        private val RMS_LEVELER_ENABLED = booleanPreferencesKey("rms_leveler_enabled")
        private val DVC_MODE = stringPreferencesKey("dvc_mode")
        private val DVC_LEVEL = floatPreferencesKey("dvc_level")
        private val COMPENSATE_DVC_VOLUME = booleanPreferencesKey("compensate_dvc_volume")
        private val DITHER_ENABLED = booleanPreferencesKey("dither_enabled")
        private val DITHER_TYPE = stringPreferencesKey("dither_type")
        private val LIMITER_ENABLED = booleanPreferencesKey("limiter_enabled")
        private val LIMITER_THRESHOLD_DB = floatPreferencesKey("limiter_threshold_db")
        private val LIMITER_ATTACK_MS = floatPreferencesKey("limiter_attack_ms")
        private val LIMITER_RELEASE_MS = floatPreferencesKey("limiter_release_ms")
        private val MONO_ENABLED = booleanPreferencesKey("mono_enabled")
        private val SOFT_LIMITER_ENABLED = booleanPreferencesKey("soft_limiter_enabled")
        private val SETTINGS_LOCKED = booleanPreferencesKey("settings_locked")
        private val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        private val PRESERVE_PITCH = booleanPreferencesKey("preserve_pitch")
        private val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
        private val HEADROOM_MANAGEMENT = booleanPreferencesKey("headroom_management")
        private val NO_HEADROOM_GAIN = booleanPreferencesKey("no_headroom_gain")
        private val HARDWARE_VOLUME = booleanPreferencesKey("hardware_volume")
        private val HEADPHONE_SIM_ENABLED = booleanPreferencesKey("headphone_sim_enabled")
        private val HEADPHONE_SIM_PROFILE = stringPreferencesKey("headphone_sim_profile")
        private val CUSTOM_USB_DRIVER = booleanPreferencesKey("custom_usb_driver")
        private val KNOWN_DEVICE_IDS = stringSetPreferencesKey("known_device_ids")
    }
}
