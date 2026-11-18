package com.beatraxus.app.fpsbooster

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import android.media.MediaFormat

/**
 * Container presentation time of every frame the player releases to the booster.
 *
 * ExoPlayer tells a [VideoFrameMetadataListener] the presentation time (µs) and the render time (ns) of each frame just
 * before it is released to the codec's output surface. The render time becomes `SurfaceTexture.getTimestamp()`, so the
 * renderers can look the container time up by the stamp they were given and feed it to [TimestampRegularizer].
 * The container time is the exact spacing of the source frames: a film at 24 fps gets 41.67 ms steps, not the
 * 33 / 50 ms of a 60 Hz release grid. When a frame is not found (a codec that stamps differently, an old API) the
 * lookup returns -1 and the regulariser keeps working from the stamps alone.
 */
object FramePts {
    private const val SIZE = 32
    /** A render time that is this close to the stamp is the same frame (the codec may round to microseconds). */
    private const val MATCH_NS = 1_500_000L

    private val lock = Any()
    private val renderNs = LongArray(SIZE)
    private val ptsNs = LongArray(SIZE)
    private var head = 0
    private var count = 0

    @UnstableApi
    private val listener = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _: Format, _: MediaFormat? ->
        record(releaseTimeNs, presentationTimeUs * 1000L)
    }

    /** Starts listening to [player] (ignored unless it is an ExoPlayer). */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun attach(player: Player?) {
        clear()
        (player as? ExoPlayer)?.setVideoFrameMetadataListener(listener)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun detach(player: Player?) {
        (player as? ExoPlayer)?.clearVideoFrameMetadataListener(listener)
        clear()
    }

    fun record(renderTimeNs: Long, presentationNs: Long) {
        synchronized(lock) {
            renderNs[head] = renderTimeNs
            ptsNs[head] = presentationNs
            head = (head + 1) % SIZE
            if (count < SIZE) count++
        }
    }

    /** Container time (ns) of the frame stamped [stampNs] by the codec, or -1 when it is not known. */
    fun lookupNs(stampNs: Long): Long = synchronized(lock) {
        var best = -1L
        var bestDelta = MATCH_NS + 1
        for (i in 0 until count) {
            val d = kotlin.math.abs(renderNs[i] - stampNs)
            if (d < bestDelta) { bestDelta = d; best = ptsNs[i] }
        }
        best
    }

    fun clear() = synchronized(lock) { count = 0; head = 0 }
}
