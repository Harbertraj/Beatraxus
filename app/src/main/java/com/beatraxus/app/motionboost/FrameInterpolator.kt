package com.beatraxus.app.motionboost

/**
 * Interface for frame interpolation.
 */
interface FrameInterpolator {
    /**
     * Prepares the interpolator with the given configuration.
     */
    suspend fun initialize(config: MotionBoostConfig)
    
    /**
     * Interpolates between two frames at a given time factor.
     * @param interpolationTime 0.0 (frameA) to 1.0 (frameB)
     */
    suspend fun interpolate(
        frameA: VideoFrame, 
        frameB: VideoFrame, 
        interpolationTime: Float
    ): VideoFrame
    
    /**
     * Releases resources.
     */
    fun release()
}


/**
 * Phase 1 placeholder — Phase 2 will replace this with real
 * motion-estimation-based interpolation. This performs NO interpolation.
 */
class NoOpFrameInterpolator : FrameInterpolator {
    override suspend fun initialize(config: MotionBoostConfig) {
        // No-op for Phase 1
    }

    override suspend fun interpolate(
        frameA: VideoFrame,
        frameB: VideoFrame,
        interpolationTime: Float
    ): VideoFrame {
        // Phase 1: Just return frameA unchanged.
        return frameA
    }

    override fun release() {
        // No-op for Phase 1
    }
}
