package com.beatraxus.app.engine

import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.surroundCalibrationDelaysMsOrNull
import com.beatraxus.app.model.surroundSmartSteeringStrength

/**
 * Upmixer options beyond the matrix, trims and 360° move. With the defaults ([NONE]) the upmixer behaves exactly as it
 * did before these options existed.
 *
 * @property smartSteering   0 = off, 0..1 = strength of the song-following centre / surround steering.
 * @property bassManagement  the main speakers are high-passed at the bass cutoff (the subwoofer carries the low end).
 * @property delaysMs        per speaker delay (UI order L R C SL SR BL BR SW) from distance calibration, or null.
 * @property testChannel     UI index of the speaker that plays test noise (everything else is muted), or -1.
 * @property holdOriginal    plain stereo on the front pair instead of the surround mix (the A/B button).
 */
internal class SurroundExtras(
    val smartSteering: Float = 0f,
    val bassManagement: Boolean = false,
    val delaysMs: FloatArray? = null,
    val testChannel: Int = -1,
    val holdOriginal: Boolean = false
) {
    companion object {
        val NONE = SurroundExtras()
    }
}

/**
 * Extras for [DspConfig]. In headphone (binaural) mode the speakers are virtual: distance delays and bass management
 * describe a real room, so they are left out, and the A/B hold is handled by the binauralizer itself.
 */
internal fun DspConfig.surroundExtras(sevenOne: Boolean, binaural: Boolean): SurroundExtras = SurroundExtras(
    smartSteering = surroundSmartSteeringStrength(),
    bassManagement = surroundBassManagement && !binaural,
    delaysMs = if (binaural) null else surroundCalibrationDelaysMsOrNull(sevenOne),
    testChannel = surroundTestChannel,
    holdOriginal = surroundHoldOriginal && !binaural
)
