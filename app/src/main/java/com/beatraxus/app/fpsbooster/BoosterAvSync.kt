package com.beatraxus.app.fpsbooster

import com.beatraxus.app.engine.AutoDelayRamp

/**
 * Decides how far the player's clock is moved to keep the sound with the picture the booster holds back (pure Kotlin).
 *
 * The compensation equals the hold the presenter really applies ([BoosterStats.addedDelayMs]). It is 0 whenever the
 * booster is not producing a picture (off, fell back, waiting), so plain playback is never shifted.
 */
object BoosterAvSync {
    /** Smaller holds are not audible (lip-sync is noticed from about 40 ms) and not worth the clock movement. */
    const val MIN_MS = 8

    fun targetMs(enabled: Boolean, planActive: Boolean, running: Boolean, holdMs: Float): Int {
        if (!enabled || !planActive || !running) return 0
        if (holdMs.isNaN() || holdMs < MIN_MS) return 0
        return Math.round(holdMs).coerceAtMost(AutoDelayRamp.MAX_MS)
    }
}
