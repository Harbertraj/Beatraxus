package com.beatraxus.app.colorgrade

import kotlin.math.cos
import kotlin.math.sin

/**
 * Hue and colour temperature as a single 3x3 RGB matrix, for the GL renderers that draw the video themselves
 * (the FPS Booster). The normal player applies the same two adjustments with an android ColorMatrix on the
 * video TextureView, which the booster surfaces bypass; both paths must give the same picture, so the numbers
 * here are the single source for both: `buildColorGradeMatrix` in VideoPlayerScreen uses [matrix] too.
 *
 * The matrix is handed to the shader as a DELTA from the identity (see [deltaColumnMajor]). A shader uniform
 * that is never set reads as 0, so "delta = 0" means "no change" and an older harness that does not know the
 * uniform still renders the picture untouched.
 */
object ColorTune {
    private const val LR = 0.213f
    private const val LG = 0.715f
    private const val LB = 0.072f

    /** Row-major hue rotation by [degrees] that keeps the brightness (luma) of every colour. */
    fun hueMatrix(degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(
            LR + c * (1f - LR) + s * (-LR), LG + c * (-LG) + s * (-LG), LB + c * (-LB) + s * (1f - LB),
            LR + c * (-LR) + s * 0.143f, LG + c * (1f - LG) + s * 0.140f, LB + c * (-LB) + s * (-0.283f),
            LR + c * (-LR) + s * (-(1f - LR)), LG + c * (-LG) + s * LG, LB + c * (1f - LB) + s * LB
        )
    }

    /**
     * Row-major hue-then-temperature matrix. [temperature] -1 (cool) .. 1 (warm): warm adds red and removes blue,
     * green stays put so the tint stays neutral.
     */
    fun matrix(hueDegrees: Float, temperature: Float): FloatArray {
        val h = if (hueDegrees != 0f) hueMatrix(hueDegrees) else IDENTITY
        val warm = temperature * 0.25f
        val scale = floatArrayOf(1f + warm, 1f, 1f - warm)
        val out = FloatArray(9)
        for (row in 0 until 3) for (col in 0 until 3) out[row * 3 + col] = scale[row] * h[row * 3 + col]
        return out
    }

    /** True when neither hue nor temperature changes the picture. */
    fun isIdentity(hueDegrees: Float, temperature: Float): Boolean = hueDegrees == 0f && temperature == 0f

    /**
     * `matrix(hue, temperature) - identity`, column-major, ready for glUniformMatrix3fv(transpose = false).
     * The shader does `rgb += uColorDelta * rgb`.
     */
    fun deltaColumnMajor(hueDegrees: Float, temperature: Float): FloatArray {
        val m = matrix(hueDegrees, temperature)
        val out = FloatArray(9)
        for (row in 0 until 3) for (col in 0 until 3) {
            out[col * 3 + row] = m[row * 3 + col] - (if (row == col) 1f else 0f)
        }
        return out
    }

    val NO_DELTA = FloatArray(9)
    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
}
