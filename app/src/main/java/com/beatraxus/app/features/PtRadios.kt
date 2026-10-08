package com.beatraxus.app.features

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat

/**
 * Radios that Nearby Connections needs (Bluetooth, Wi-Fi and, on Android 10 and 11, Location).
 *
 * Android does not let a normal app flip these silently on recent versions, so the Play Together
 * screen asks for them with the system's own one-tap prompts, in one go, when a room is created or
 * joined. Android 8 / 9 can still switch Wi-Fi on without any prompt ([enableWifiSilently]).
 */
object PtRadios {
    fun bluetoothOn(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return true // no hardware, nothing to ask
        return try { adapter.isEnabled } catch (_: Exception) { true }
    }

    fun wifiOn(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return true
        return try { wm.isWifiEnabled } catch (_: Exception) { true }
    }

    /** Android 11 and older only scan for nearby phones while Location is on. */
    fun locationOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    fun anyOff(context: Context) = !bluetoothOn(context) || !wifiOn(context) || locationOff(context)

    /** System dialog "Allow Beatraxus to turn on Bluetooth?" (needs BLUETOOTH_CONNECT on Android 12+). */
    fun bluetoothEnableIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** Android 8 / 9: switch Wi-Fi on without a prompt. Returns true if it is now on. */
    @Suppress("DEPRECATION")
    fun enableWifiSilently(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return false
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        return try { wm.setWifiEnabled(true) } catch (_: Exception) { false }
    }

    /** Android 10+: the small Wi-Fi panel with the on/off switch, shown over the app. */
    @androidx.annotation.RequiresApi(29)
    fun wifiPanelIntent(): Intent = Intent(
        if (Build.VERSION.SDK_INT >= 30) Settings.Panel.ACTION_WIFI else Settings.Panel.ACTION_INTERNET_CONNECTIVITY
    )

    fun locationSettingsIntent() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}
