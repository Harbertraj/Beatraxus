package com.beatraxus.app.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer

/**
 * Custom RenderersFactory for video playback that includes the FFmpeg audio renderer
 * for formats like EAC3, AC3, and DTS which are often not supported by hardware decoders.
 */
@UnstableApi
class VideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // Prefer extensions (FFmpeg) over platform decoders for unsupported formats.
        // This mode tries the platform decoder first and falls back to FFmpeg if needed.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
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
        // 1. Build standard MediaCodec audio renderers
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )

        // 2. Append FFmpeg audio renderer for software decoding fallback
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            out.add(
                FfmpegAudioRenderer(
                    eventHandler,
                    eventListener,
                    audioSink
                )
            )
        }
    }
}
