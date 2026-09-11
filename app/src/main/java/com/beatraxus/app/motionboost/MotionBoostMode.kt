package com.beatraxus.app.motionboost

sealed class MotionBoostMode(
    val targetFps: Int?,
    val label: String,
    val requiresInterpolation: Boolean
) {
    object ORIGINAL : MotionBoostMode(null, "Original", false)
    object FPS_45 : MotionBoostMode(45, "45 FPS", true)
    object FPS_60 : MotionBoostMode(60, "60 FPS", true)
    object FPS_90 : MotionBoostMode(90, "90 FPS", true)
    object FPS_120 : MotionBoostMode(120, "120 FPS", true)

    companion object {
        fun fromTargetFps(fps: Int?): MotionBoostMode = when (fps) {
            45 -> FPS_45
            60 -> FPS_60
            90 -> FPS_90
            120 -> FPS_120
            else -> ORIGINAL
        }
    }
}
