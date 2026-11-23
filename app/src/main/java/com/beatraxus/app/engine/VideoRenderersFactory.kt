package com.beatraxus.app.engine

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import androidx.media3.common.MimeTypes

/**
 * Custom RenderersFactory for video playback. Adds the FFmpeg audio renderer (EAC3, AC3, DTS, TrueHD, ...) and an
 * FFmpeg software video renderer (AV1, HEVC, ...) as fallbacks for what the phone's hardware decoders cannot play.
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
            if (MimeTypes.isVideo(mimeType)) {
                // Drop decoders that already crashed on this stream, so the retry lands on the next one
                // (e.g. the software HEVC decoder). ExoPlayer's own decoder fallback only covers codec
                // creation failures, not a codec that dies after it started.
                val blocked = tuning.blockedVideoDecoders
                val usable = if (blocked.isEmpty()) decoderInfos else decoderInfos.filter { it.name !in blocked }
                applyDecoderMode(usable, tuning.decoderMode)
            } else {
                decoderInfos
            }
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

        // Software fallback (MX Player style) for a video track the phone has no usable hardware decoder for (AV1 on a
        // phone without an AV1 chip, a decoder blocked after it crashed). ExoPlayer hands a track to the renderer that
        // reports the HIGHER support level, and FfmpegVideoRenderer always reports "fully supported" while MediaCodec
        // reports "exceeds capabilities" for a 4K / 10-bit / HDR stream beyond the decoder's listed limits. Left alone,
        // the CPU decoder would take over a stream the hardware decoder can play (4K HDR10 / Dolby Vision then did not
        // play at all in HW+ and SW). SoftwareFallbackVideoRenderer therefore only claims a format when
        // [mayUseSoftwareDecoder] says so; it is always added so a change of decoder mode applies without rebuilding the player.
        // Note: frames from this renderer bypass the GL effect chain (upscaler, colour grade, FPS Booster) and are
        // converted to 8-bit RGBA, so they cannot show HDR.
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            out.add(
                SoftwareFallbackVideoRenderer(
                    FfmpegVideoRenderer(
                        allowedVideoJoiningTimeMs,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAMES_TO_NOTIFY
                    ),
                    tuning
                )
            )
        }
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
        private const val MAX_DROPPED_VIDEO_FRAMES_TO_NOTIFY = 50

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

        /**
         * Decoders the system has for [format], including the base-layer decoder of a Dolby Vision stream
         * (HEVC for profiles 4/5/7/8), in Media3's own order. Empty when the query fails.
         */
        internal fun decoderInfosFor(format: Format): List<MediaCodecInfo> = try {
            MediaCodecUtil.getDecoderInfosSoftMatch(
                MediaCodecSelector.DEFAULT, format, /* requiresSecureDecoder= */ false, /* requiresTunnelingDecoder= */ false
            )
        } catch (_: Throwable) {
            emptyList()
        }

        /**
         * Whether the FFmpeg software renderer may claim [format]:
         *  - never in HW mode (hardware only);
         *  - always in SW mode (the viewer asked for software);
         *  - in HW+ only when the phone has no hardware decoder for the stream, or every one of them crashed on it
         *    (see [VideoPlaybackTuning.blockedVideoDecoders]).
         */
        internal fun mayUseSoftwareDecoder(format: Format, tuning: VideoPlaybackTuning): Boolean {
            val allowed = when (tuning.decoderMode) {
                VideoDecoderMode.HW -> false
                VideoDecoderMode.SW -> true
                VideoDecoderMode.HW_PLUS -> {
                    val blocked = tuning.blockedVideoDecoders
                    decoderInfosFor(format).none { it.isHardwareDecoder() && it.name !in blocked }
                }
            }
            if (allowed && tuning.decoderMode == VideoDecoderMode.HW_PLUS) {
                Log.i(TAG, "No usable hardware decoder for ${format.sampleMimeType}: FFmpeg software decoder allowed")
            }
            return allowed
        }

        /**
         * Whether another video decoder than [excluded] ones could play [format] in [mode], used before blocking the
         * decoder that shows no picture, so playback is never swapped for nothing.
         */
        internal fun hasAlternativeDecoder(format: Format, excluded: Set<String>, mode: VideoDecoderMode): Boolean =
            applyDecoderMode(decoderInfosFor(format).filter { it.name !in excluded }, mode).isNotEmpty()

        private const val TAG = "VideoRenderersFactory"
    }
}

/**
 * Wraps the FFmpeg software video renderer (which is final) so that it only reports support for a format when
 * [VideoRenderersFactory.mayUseSoftwareDecoder] allows it. Otherwise it reports "unsupported" and ExoPlayer keeps the
 * track on the MediaCodec (hardware) renderer, even when that one only says "exceeds capabilities".
 */
@UnstableApi
internal class SoftwareFallbackVideoRenderer(
    private val ffmpeg: Renderer,
    private val tuning: VideoPlaybackTuning
) : ForwardingRenderer(ffmpeg) {

    private val gatedCapabilities = object : RendererCapabilities {
        override fun getName(): String = ffmpeg.name

        override fun getTrackType(): Int = ffmpeg.capabilities.trackType

        override fun supportsFormat(format: Format): Int {
            val inner = ffmpeg.capabilities.supportsFormat(format)
            return if (VideoRenderersFactory.mayUseSoftwareDecoder(format, tuning)) {
                inner
            } else {
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
            }
        }

        override fun supportsMixedMimeTypeAdaptation(): Int = ffmpeg.capabilities.supportsMixedMimeTypeAdaptation()
    }

    override fun getCapabilities(): RendererCapabilities = gatedCapabilities
}
