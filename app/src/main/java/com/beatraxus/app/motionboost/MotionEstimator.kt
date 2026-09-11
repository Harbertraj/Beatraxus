package com.beatraxus.app.motionboost

/**
 * Data representing a field of motion vectors.
 * Phase 2 uses a block-based grid.
 */
data class MotionVectorField(
    val textureId: Int,
    val width: Int, // Grid width
    val height: Int // Grid height
) {
    fun release() {
        if (textureId != 0) {
            android.opengl.GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}

/**
 * Result of motion estimation between two frames.
 */
data class MotionData(
    val forwardMotion: MotionVectorField?,
    val backwardMotion: MotionVectorField?,
    val confidence: Float,
    val occlusionMask: Int = 0 // Placeholder for Stage 2C
)

/**
 * Interface for estimating motion between two video frames.
 */
interface MotionEstimator {
    /**
     * Estimates motion from [frameA] to [frameB].
     */
    suspend fun estimateMotion(frameA: VideoFrame, frameB: VideoFrame): MotionData
    
    /**
     * Releases GPU resources.
     */
    fun release()
}
