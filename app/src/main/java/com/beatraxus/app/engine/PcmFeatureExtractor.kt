package com.beatraxus.app.engine

import android.util.Log
import com.beatraxus.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets AI EQ analyse songs the native MediaCodec path refuses (ALAC, Dolby/DTS, WAV variants, FFmpeg-only
 * formats): decodes them with the SAME [DecoderFactory] playback uses, then runs the native analysis on that PCM.
 */
internal class PcmFeatureExtractor(private val decoderFactory: DecoderFactory) {

    suspend fun extract(song: Song, dsp: NativeDsp): AudioFeatures? = withContext(Dispatchers.IO) {
        val collector = PcmCollector(MAX_FRAMES)
        val decoder = try {
            decoderFactory.create(song)
        } catch (t: Throwable) {
            Log.w(TAG, "No decoder available for ${song.title}: ${t.message}")
            return@withContext null
        }

        val control = object : DecoderControl {
            override fun isActive(): Boolean = !collector.isFull()
            override fun isSeekPending(): Boolean = false
            override fun consumePendingSeekMs(): Long? = null
            override fun setSeekListener(listener: () -> Unit) {}
            override fun notifySeek(positionMs: Long) {}
            override fun logDebug(message: String) {}
            override fun logWarn(message: String) { Log.w(TAG, message) }
        }

        // A stalled decoder must not leave "Analysing…" on screen forever; whatever was collected by then is used.
        var decodeFailure: String? = null
        val finished = withTimeoutOrNull(DECODE_TIMEOUT_MS) {
            try {
                val result = decoder.decode(PlaybackRequest(song = song, startPositionMs = 0L), collector, control)
                if (result is DecodeResult.Failed) decodeFailure = result.reason
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                decodeFailure = t.message ?: t.javaClass.simpleName
            }
            true
        }
        if (finished == null) Log.w(TAG, "Decoding timed out for ${song.title}; analysing the ${collector.frames} frames collected")

        if (!collector.hasEnough()) {
            Log.w(TAG, "Not enough decoded audio to analyse ${song.title} (${collector.frames} frames, dop=${collector.isDoP}, failure=$decodeFailure)")
            return@withContext null
        }
        dsp.analyzePcm(collector.samples(), collector.frames, collector.sampleRate, collector.outChannels)
    }

    private companion object {
        const val TAG = "PcmFeatureExtractor"
        /** ~60 s at 48 kHz (about 23 MB of floats as stereo); hi-res files are analysed over a shorter span. */
        const val MAX_FRAMES = 2_880_000
        const val DECODE_TIMEOUT_MS = 60_000L
    }
}
