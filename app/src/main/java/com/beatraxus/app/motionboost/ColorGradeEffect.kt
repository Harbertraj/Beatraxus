package com.beatraxus.app.motionboost

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * A [GlEffect] that applies color grading.
 */
@UnstableApi
class ColorGradeEffect(
    var brightness: Float,
    var contrast: Float,
    var saturation: Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorGradeShaderProgram(useHdr, this)
    }
}
