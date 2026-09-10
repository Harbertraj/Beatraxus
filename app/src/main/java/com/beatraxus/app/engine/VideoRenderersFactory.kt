package com.beatraxus.app.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.common.MimeTypes

/**
 * Custom RenderersFactory for video playback that includes the FFmpeg audio renderer
 * for formats like EAC3, AC3, and DTS which are often not supported by hardware decoders.
 */
@UnstableApi
class VideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // Use hardware decoders where available, but keep extensions ON for manual audio renderer injection.
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
        // 1. Add FFmpeg audio renderer FIRST to ensure it's preferred for supported formats
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            out.add(
                FfmpegAudioRenderer(
                    eventHandler,
                    eventListener,
                    audioSink
                )
            )
        }

        // 2. Wrap MediaCodecSelector to filter out problematic decoders
        val filteredSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = mediaCodecSelector.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            
            if (mimeType == MimeTypes.AUDIO_RAW) {
                // Filter out c2.android.raw.decoder as it fails on some Oplus devices for 24-bit PCM
                decoderInfos.filter { it.name != "c2.android.raw.decoder" }
            } else {
                decoderInfos
            }
        }

        // 3. Build standard MediaCodec audio renderers with the filtered selector
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            filteredSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }
}
