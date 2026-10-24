package com.beatraxus.app.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Renderers of the small audio-only player that plays an added audio file (feature 25).
 *
 * Unlike [VideoRenderersFactory], the phone's own MediaCodec decoders come FIRST and FFmpeg is only the fallback for
 * formats the phone can't decode. FFmpeg decodes just the first frame of a packet that holds several frames, which
 * made some added files play as short bursts of sound with silence in between ("audio sink discontinuity" in logcat).
 */
@UnstableApi
class ExternalAudioRenderersFactory(
    context: Context,
    private val tuning: VideoPlaybackTuning
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val first = out.size
        // MediaCodec first (ExoPlayer uses the first renderer that supports the format)...
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        // ...then FFmpeg for what MediaCodec can't play (DTS, TrueHD, ...).
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        for (i in first until out.size) out[i] = AudioDelayRenderer(out[i], tuning)
    }
}
