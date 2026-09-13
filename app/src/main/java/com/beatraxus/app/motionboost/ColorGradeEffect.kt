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
    private val brightness: Float,
    private val contrast: Float,
    private val saturation: Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorGradeShaderProgram(useHdr, brightness, contrast, saturation)
    }
}
