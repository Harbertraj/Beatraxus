package com.beatraxus.app.upscale

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.beatraxus.app.model.VideoUpscaleMode

/**
 * A [GlEffect] that upscales each frame. [mode] and [strength] (0 = no sharpening .. 1 = strong) are read on every
 * frame, so they can change while the video plays. The output size is fixed when the effect starts.
 */
@UnstableApi
class VideoUpscaleEffect(
    @Volatile var mode: VideoUpscaleMode,
    @Volatile var strength: Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val metrics = context.resources.displayMetrics
        return VideoUpscaleShaderProgram(useHdr, this, maxOf(metrics.widthPixels, metrics.heightPixels))
    }
}
