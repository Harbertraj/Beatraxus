package com.beatraxus.app.motionboost

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player

/**
 * Metadata about the source video's frame rate.
 */
data class VideoFrameRateInfo(
    val sourceFrameRate: Float?,
    val sourceFrameDurationUs: Long?,
    val isEstimated: Boolean
)

/**
 * Detects source FPS from ExoPlayer's current format.
 */
object SourceFrameRateDetector {
    fun detect(player: Player): VideoFrameRateInfo {
        // Find the selected video track
        val videoTrackGroup = player.currentTracks.groups.find { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        val format = videoTrackGroup?.getTrackFormat(0)
        val frameRate = format?.frameRate
        
        return if (frameRate == null || frameRate == Format.NO_VALUE.toFloat() || frameRate <= 0f) {
            VideoFrameRateInfo(null, null, isEstimated = true)
        } else {
            val durationUs = (1_000_000f / frameRate).toLong()
            VideoFrameRateInfo(frameRate, durationUs, isEstimated = false)
        }
    }
}
