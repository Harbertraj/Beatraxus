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
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.common.MimeTypes

/**
 * Custom RenderersFactory for video playback that includes the FFmpeg audio renderer
 * for formats like EAC3, AC3, and DTS which are often not supported by hardware decoders.
 *
 * It also applies the user's video decoder choice (HW / HW+ / SW) and the audio delay,
 * both read from [tuning] while playing.
 */
@UnstableApi
class VideoRenderersFactory(
    context: Context,
    private val tuning: VideoPlaybackTuning = VideoPlaybackTuning()
) : DefaultRenderersFactory(context) {

    init {
        // Use hardware decoders where available, but keep extensions ON for manual audio renderer injection.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // Only video decoders are filtered. The mode is read on every codec lookup, so changing it
        // takes effect the next time the player creates a video codec.
        val decoderModeSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = mediaCodecSelector.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            if (MimeTypes.isVideo(mimeType)) applyDecoderMode(decoderInfos, tuning.decoderMode) else decoderInfos
        }
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            decoderModeSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
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
        val firstAudioRendererIndex = out.size

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

        // 4. Wrap every audio renderer added above so the audio delay can shift the playback clock.
        for (i in firstAudioRendererIndex until out.size) {
            out[i] = AudioDelayRenderer(out[i], tuning)
        }
    }

    internal companion object {
        /** Filters/orders video decoders for the chosen mode. Kept free of side effects for testing. */
        fun applyDecoderMode(
            decoderInfos: List<MediaCodecInfo>,
            mode: VideoDecoderMode
        ): List<MediaCodecInfo> = when (mode) {
            VideoDecoderMode.HW -> decoderInfos.filter { it.isHardwareDecoder() }
            // sortedBy is stable, so the platform's own preference order is kept within each group.
            VideoDecoderMode.HW_PLUS -> decoderInfos.sortedBy { if (it.isHardwareDecoder()) 0 else 1 }
            // Devices without a software decoder for the stream fall back to whatever exists.
            VideoDecoderMode.SW -> decoderInfos.filter { it.softwareOnly }.ifEmpty { decoderInfos }
        }

        private fun MediaCodecInfo.isHardwareDecoder() = hardwareAccelerated && !softwareOnly
    }
}
