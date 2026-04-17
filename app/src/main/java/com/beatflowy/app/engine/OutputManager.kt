package com.beatflowy.app.engine

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.beatflowy.app.model.AudioOutputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OutputManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _deviceFlow = MutableStateFlow(detectCurrentDevice())
    val deviceFlow: StateFlow<AudioOutputDevice> = _deviceFlow.asStateFlow()
    val currentDevice: AudioOutputDevice get() = _deviceFlow.value

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _deviceFlow.value = detectCurrentDevice()
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        context.registerReceiver(receiver, filter)
        _deviceFlow.value = detectCurrentDevice()
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun refresh() {
        _deviceFlow.value = detectCurrentDevice()
    }

    fun detectCurrentDevice(): AudioOutputDevice {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            detectViaAudioDeviceInfo()
        } else {
            @Suppress("DEPRECATION")
            when {
                audioManager.isBluetoothA2dpOn -> AudioOutputDevice.BLUETOOTH
                audioManager.isWiredHeadsetOn  -> AudioOutputDevice.WIRED
                else                           -> AudioOutputDevice.SPEAKER
            }
        }
    }

    private fun detectViaAudioDeviceInfo(): AudioOutputDevice {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var hasWired = false
        var hasBluetooth = false
        var hasUsb = false
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET      -> hasUsb = true
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL      -> hasWired = true
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO     -> hasBluetooth = true
            }
        }
        return when {
            hasUsb       -> AudioOutputDevice.USB_DAC
            hasWired     -> AudioOutputDevice.WIRED
            hasBluetooth -> AudioOutputDevice.BLUETOOTH
            else         -> AudioOutputDevice.SPEAKER
        }
    }

    fun getMaxSupportedSampleRate(): Int {
        val preferred = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return preferred?.toIntOrNull() ?: 48_000
    }
}
