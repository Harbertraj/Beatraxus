package com.beatraxus.app.model

import kotlin.math.max
import kotlin.math.min

/**
 * Video feature 15: deinterlacing.
 *
 * Interlaced video (old TV recordings, DVDs, some camcorders, 1080i broadcasts) stores the picture as two
 * half-height "fields" woven together. On a progressive screen, anything that moves shows horizontal "combing"
 * lines. This mode decides how those lines are removed.
 *
 * Pure maths, no Android types, so it can be unit tested. [DeinterlaceMath] is the reference implementation of the
 * fragment shader in `DeinterlaceShaderProgram`, which builds its GLSL from the same constants.
 */
enum class DeinterlaceMode(val label: String, val description: String) {
    /** Picture is shown exactly as decoded. */
    OFF("Off", "Show the picture exactly as decoded"),

    /** Only lines that show combing are rebuilt, so progressive video stays razor sharp. Safe to leave on. */
    AUTO("Auto", "Fix combing lines only where they appear. Progressive video stays sharp"),

    /** Every line is softly blended with its neighbours. Strongest, but costs a little vertical sharpness. */
    ALWAYS("Always", "Blend every line. Use for clearly interlaced video that still shows lines");

    val isOn: Boolean get() = this != OFF

    /** Shader switch value: 0 = off, 1 = adaptive, 2 = always blend. */
    val shaderValue: Int get() = ordinal

    companion object {
        const val PREF_KEY = "video_deinterlace_mode"

        fun fromName(name: String?): DeinterlaceMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Reference maths for the deinterlace shader. A pixel is looked at together with the pixel straight above and
 * straight below it (the lines of the other field).
 */
object DeinterlaceMath {
    /** Comb metric below this is treated as normal picture detail. */
    const val COMB_LOW = 0.002f

    /** Comb metric above this is certainly combing: the line is fully rebuilt. */
    const val COMB_HIGH = 0.02f

    /**
     * Combing means the current line is out of step with BOTH neighbours in the same direction (a line much
     * brighter than the lines above and below it, or much darker). A smooth edge or gradient has neighbours on
     * opposite sides of the current value, so the product is negative or zero there.
     * Returns 0 when there is no combing; larger means stronger combing.
     */
    fun combMetric(above: Float, current: Float, below: Float): Float =
        max(0f, (above - current) * (below - current))

    /** 0..1: how much of the interpolated line replaces the current one in [DeinterlaceMode.AUTO]. */
    fun autoWeight(comb: Float): Float {
        val t = min(1f, max(0f, (comb - COMB_LOW) / (COMB_HIGH - COMB_LOW)))
        return t * t * (3f - 2f * t) // smoothstep
    }

    /** Result for one luma value. Colour channels follow the same weights in the shader. */
    fun apply(mode: DeinterlaceMode, above: Float, current: Float, below: Float): Float = when (mode) {
        DeinterlaceMode.OFF -> current
        DeinterlaceMode.AUTO -> {
            val w = autoWeight(combMetric(above, current, below))
            current + (0.5f * (above + below) - current) * w
        }
        DeinterlaceMode.ALWAYS -> 0.25f * above + 0.5f * current + 0.25f * below
    }
}
