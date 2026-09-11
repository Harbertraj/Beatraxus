package com.beatraxus.app.motionboost

/**
 * Holds current Motion Boost configuration.
 */
data class MotionBoostConfig(
    val mode: MotionBoostMode = MotionBoostMode.ORIGINAL,
    val quality: MotionBoostQuality = MotionBoostQuality.BALANCED
)
