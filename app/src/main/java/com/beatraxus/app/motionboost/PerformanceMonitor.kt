package com.beatraxus.app.motionboost

/**
 * Statistics for Motion Boost processing performance.
 */
data class MotionBoostPerformanceStats(
    val frameGenerationTimeMs: Double,
    val droppedFramesCount: Int,
    val cpuUsagePercentage: Float,
    val gpuUsagePercentage: Float
)

/**
 * Tracks frame generation time and dropped frames.
 * Skeleton for Phase 1.
 */
class PerformanceMonitor {
    private var lastStats = MotionBoostPerformanceStats(0.0, 0, 0f, 0f)
    private var frameCount = 0
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var currentFps = 0

    fun recordFrameGeneration(durationMs: Double) {
        frameCount++
        val now = System.currentTimeMillis()
        val delta = now - lastFpsCalculationTime
        if (delta >= 1000) {
            currentFps = ((frameCount * 1000f) / delta).toInt()
            frameCount = 0
            lastFpsCalculationTime = now
        }
        lastStats = lastStats.copy(frameGenerationTimeMs = durationMs)
    }

    fun getLiveFps(): Int = currentFps

    fun recordDroppedFrame() {
        // Phase 1 stub
    }

    fun getStats(): MotionBoostPerformanceStats {
        return lastStats
    }
    
    fun reset() {
        lastStats = MotionBoostPerformanceStats(0.0, 0, 0f, 0f)
    }
}
