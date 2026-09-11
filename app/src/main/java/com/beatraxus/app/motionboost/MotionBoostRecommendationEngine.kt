package com.beatraxus.app.motionboost

import androidx.media3.common.VideoSize

/**
 * Pure function to recommend a Motion Boost mode based on input conditions.
 */
object MotionBoostRecommendationEngine {
    fun recommend(
        sourceFps: Float?,
        displayRefreshRate: Float,
        videoSize: VideoSize
    ): MotionBoostMode {
        if (sourceFps == null) return MotionBoostMode.ORIGINAL

        // Cap recommendation lower for 4K sources to avoid overheating/lag
        val isHighRes = videoSize.width >= 3840 || videoSize.height >= 2160
        val targetLimit = if (isHighRes) 60 else displayRefreshRate.toInt()

        return when {
            targetLimit >= 120 && sourceFps <= 60f -> MotionBoostMode.FPS_120
            targetLimit >= 90 && sourceFps <= 45f -> MotionBoostMode.FPS_90
            targetLimit >= 60 && sourceFps <= 30f -> MotionBoostMode.FPS_60
            targetLimit >= 45 && sourceFps <= 24f -> MotionBoostMode.FPS_45
            else -> MotionBoostMode.ORIGINAL
        }
    }
}
