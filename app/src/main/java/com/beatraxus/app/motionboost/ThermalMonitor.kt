package com.beatraxus.app.motionboost

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Thermal states for Motion Boost throttling.
 */
enum class ThermalState {
    NORMAL, WARM, HOT, CRITICAL
}

/**
 * Wraps android.os.PowerManager.addThermalStatusListener (API 29+).
 * Graceful no-op fallback below API 29.
 */
class ThermalMonitor(context: Context) {
    private val TAG = "ThermalMonitor"
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var thermalListener: Any? = null
    
    var currentState: ThermalState = ThermalState.NORMAL
        private set

    /**
     * Starts monitoring thermal status.
     */
    fun start(onStateChanged: (ThermalState) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val newState = when (status) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.NORMAL
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.WARM
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.NORMAL
                }
                currentState = newState
                onStateChanged(newState)
            }
            thermalListener = listener
            powerManager.addThermalStatusListener(listener)
        } else {
            Log.d(TAG, "Thermal monitoring not supported on this API level")
        }
    }

    /**
     * Stops monitoring thermal status.
     */
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            powerManager.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
        }
    }
}
