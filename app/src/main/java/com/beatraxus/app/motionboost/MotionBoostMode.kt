package com.beatraxus.app.motionboost

/**
 * FPS Booster target frame rate. [ORIGINAL] leaves the normal ExoPlayer rendering path untouched.
 */
enum class MotionBoostMode(val label: String, val targetFps: Int) {
    ORIGINAL("Original", 0),
    FPS_45("45 FPS", 45),
    FPS_60("60 FPS", 60),
    FPS_90("90 FPS", 90),
    FPS_120("120 FPS", 120);

    val isEnabled: Boolean get() = targetFps > 0

    companion object {
        fun fromName(name: String?): MotionBoostMode = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}
