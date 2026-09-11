package com.beatraxus.app.motionboost

/**
 * Capabilities of the current device for Motion Boost based on hardware.
 */
data class MotionBoostCapabilities(
    val displayRefreshRate: Float,
    val maxRecommendedFps: Int,
    val supports45: Boolean,
    val supports60: Boolean,
    val supports90: Boolean,
    val supports120: Boolean
) {
    companion object {
        fun compute(refreshRate: Float): MotionBoostCapabilities {
            return MotionBoostCapabilities(
                displayRefreshRate = refreshRate,
                maxRecommendedFps = refreshRate.toInt(),
                supports45 = refreshRate >= 45f,
                supports60 = refreshRate >= 60f,
                supports90 = refreshRate >= 90f,
                supports120 = refreshRate >= 120f
            )
        }
    }
}
