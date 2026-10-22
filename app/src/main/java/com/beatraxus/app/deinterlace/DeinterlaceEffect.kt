package com.beatraxus.app.deinterlace

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.beatraxus.app.model.DeinterlaceMode

/**
 * A [GlEffect] that removes interlacing ("combing") lines from the video frames. [mode] can be changed while the
 * video plays; the shader reads it on every frame.
 */
@UnstableApi
class DeinterlaceEffect(@Volatile var mode: DeinterlaceMode) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DeinterlaceShaderProgram(useHdr, this)
}
