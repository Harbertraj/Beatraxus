package com.beatraxus.app.colorgrade

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * A [GlEffect] that applies color grading.
 *
 * [gamma] (1 = off, above 1 brightens the mid-tones) and [sharpness] (0 = off .. 1 = strong) can only be done
 * per pixel on the GPU, so they live here. Hue and colour temperature are in the player's instant colour matrix.
 * Both are read on every frame, so a slider can change them while the effect is running.
 */
@UnstableApi
class ColorGradeEffect(
    var brightness: Float,
    var contrast: Float,
    var saturation: Float,
    @Volatile var gamma: Float = 1f,
    @Volatile var sharpness: Float = 0f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorGradeShaderProgram(useHdr, this)
    }
}
