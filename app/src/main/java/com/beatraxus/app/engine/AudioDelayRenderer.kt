package com.beatraxus.app.engine

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer

/**
 * Wraps an audio renderer and shifts the playback clock it drives by
 * [VideoPlaybackTuning.audioDelayMs].
 *
 * The audio renderer is ExoPlayer's master clock, and video frames are shown when that clock
 * reaches their timestamp. Reporting the clock [delay] ahead therefore shows every video frame
 * [delay] earlier relative to the sound, which is the same as delaying the audio by [delay].
 * A negative delay does the opposite. Because only the reported clock changes, the delay can
 * be adjusted while playing, with no audio re-buffering.
 *
 * (The renderer is wrapped instead of subclassed because FfmpegAudioRenderer is final.)
 *
 * On top of the user's delay the clock carries [VideoPlaybackTuning.autoDelayTargetMs], the FPS Booster's own hold
 * (see [AutoDelayRamp]), so the picture the booster holds back is not behind the sound.
 *
 * Side effect: the position the player reports is shifted by the same amount.
 */
@UnstableApi
class AudioDelayRenderer(
    delegate: Renderer,
    private val tuning: VideoPlaybackTuning
) : ForwardingRenderer(delegate) {

    private var innerClock: MediaClock? = null
    private var offsetClock: MediaClock? = null

    override fun getMediaClock(): MediaClock? {
        val inner = super.getMediaClock() ?: return null
        // DefaultMediaClock requires the same instance every time it asks, so build the wrapper once.
        if (offsetClock == null || innerClock !== inner) {
            innerClock = inner
            offsetClock = OffsetMediaClock(inner, tuning, AutoDelayRamp())
        }
        return offsetClock
    }

    private class OffsetMediaClock(
        private val inner: MediaClock,
        private val tuning: VideoPlaybackTuning,
        private val ramp: AutoDelayRamp
    ) : MediaClock {
        override fun getPositionUs(): Long {
            val auto = ramp.update(tuning.autoDelayTargetMs, System.nanoTime())
            tuning.appliedAutoDelayMs = Math.round(auto).toInt()
            return inner.positionUs + tuning.audioDelayMs * 1000L + Math.round(auto * 1000.0)
        }

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            inner.playbackParameters = playbackParameters
        }

        override fun getPlaybackParameters(): PlaybackParameters = inner.playbackParameters
    }
}
