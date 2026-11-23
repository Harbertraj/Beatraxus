package com.beatraxus.app.fpsbooster

import android.content.Context
import com.beatraxus.app.motionboost.DisplayRefreshRate

/**
 * The refresh rate the system currently reports for the panel, for [VsyncPeriodEstimator]. Re-read at most every
 * [REFRESH_NS]: cheap, but not worth doing on every vsync. 0 when unknown.
 */
class PanelRateHint(private val context: Context?) {
    private var hz = 0f
    private var readAtNs = 0L

    fun hz(nowNs: Long): Float {
        val c = context ?: return 0f
        if (readAtNs == 0L || nowNs - readAtNs >= REFRESH_NS) {
            readAtNs = nowNs
            hz = runCatching { DisplayRefreshRate.currentRate(c) }.getOrDefault(0f)
        }
        return hz
    }

    private companion object { const val REFRESH_NS = 500_000_000L }
}
