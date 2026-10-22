package com.beatraxus.app.model

/**
 * How the video picture is turned on screen: rotated clockwise in 90 degree steps, then optionally mirrored.
 *
 * [flipHorizontal] is the "mirror" (left and right swap) and [flipVertical] swaps top and bottom. Both are
 * applied to the rotated picture, so "mirror" always means left/right *as the viewer sees it*, whatever the rotation.
 *
 * Pure maths, no Android types, so it can be unit tested. The player uses it in two places:
 *  - [textureMatrixValues] for the TextureView render path (Matrix.setValues order),
 *  - [videoFromScreen] for the FPS Booster GL renderers (a mat2 uniform of the present pass).
 *
 * A plain SurfaceView cannot be rotated or mirrored, so the player switches to one of those two paths
 * as soon as [isIdentity] is false.
 */
data class VideoOrientation(
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
) {
    /** Always 0..3 (clockwise quarter turns). */
    val turns: Int get() = ((quarterTurns % 4) + 4) % 4

    val isIdentity: Boolean get() = turns == 0 && !flipHorizontal && !flipVertical

    /** True for 90 / 270 degrees: the picture's width and height trade places on screen. */
    val swapsAxes: Boolean get() = turns % 2 == 1

    val rotationDegrees: Int get() = turns * 90

    fun rotatedClockwise(): VideoOrientation = copy(quarterTurns = (turns + 1) % 4)

    fun withFlipHorizontal(flip: Boolean): VideoOrientation = copy(flipHorizontal = flip)

    fun withFlipVertical(flip: Boolean): VideoOrientation = copy(flipVertical = flip)

    /** Short text for the on-screen confirmation, e.g. "Rotated 90°, mirrored". */
    fun describe(): String {
        if (isIdentity) return "Original orientation"
        val parts = mutableListOf<String>()
        if (turns != 0) parts += "Rotated $rotationDegrees°"
        if (flipHorizontal) parts += "mirrored"
        if (flipVertical) parts += "flipped vertically"
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    /**
     * Where a point of the picture lands on screen. Both are in coordinates centred on the picture with Y pointing
     * UP and each axis running -0.5..0.5, as a row-major 2x2 [m00, m01, m10, m11]: screen = M * picture.
     * Rotation first (clockwise: x' = y, y' = -x), then the flips.
     */
    private fun screenFromPictureYUp(): FloatArray {
        // R^turns, clockwise 90 degrees in Y-up space is [[0, 1], [-1, 0]]
        var m00 = 1f; var m01 = 0f; var m10 = 0f; var m11 = 1f
        repeat(turns) {
            val n00 = 0f * m00 + 1f * m10
            val n01 = 0f * m01 + 1f * m11
            val n10 = -1f * m00 + 0f * m10
            val n11 = -1f * m01 + 0f * m11
            m00 = n00; m01 = n01; m10 = n10; m11 = n11
        }
        val fx = if (flipHorizontal) -1f else 1f
        val fy = if (flipVertical) -1f else 1f
        return floatArrayOf(fx * m00, fx * m01, fy * m10, fy * m11)
    }

    /**
     * The inverse of the above, for a shader: given a screen position (centred, Y up) it gives the picture
     * position to sample. Returned COLUMN-major, ready for glUniformMatrix2fv(..., transpose = false) and a GLSL mat2.
     */
    fun videoFromScreen(): FloatArray {
        val s = screenFromPictureYUp()
        // Every orientation is a signed permutation matrix, so the inverse is its transpose.
        val i00 = s[0]; val i01 = s[2]; val i10 = s[1]; val i11 = s[3]
        return clean(floatArrayOf(i00, i10, i01, i11))
    }

    /**
     * Values for android.graphics.Matrix.setValues(), for TextureView.setTransform() on a view of
     * [viewWidth] x [viewHeight] pixels. The TextureView stretches the video to its bounds before the transform,
     * and the player sizes those bounds to the picture's *rotated* shape, so the transform only has to turn the
     * stretched picture within the same box.
     */
    fun textureMatrixValues(viewWidth: Float, viewHeight: Float): FloatArray {
        val s = screenFromPictureYUp()
        // Y-up -> Y-down (android pixel space): conjugate with diag(1, -1).
        val a = s[0]; val b = -s[1]; val c = -s[2]; val d = s[3]
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val skewX = if (viewHeight == 0f) 0f else b * viewWidth / viewHeight
        val skewY = if (viewWidth == 0f) 0f else c * viewHeight / viewWidth
        return clean(
            floatArrayOf(
                a, skewX, cx - (a * cx + skewX * cy),
                skewY, d, cy - (skewY * cx + d * cy),
                0f, 0f, 1f
            )
        )
    }

    /** Turns -0.0 into 0.0 so the values compare and print cleanly. */
    private fun clean(values: FloatArray): FloatArray {
        for (i in values.indices) if (values[i] == 0f) values[i] = 0f
        return values
    }

    companion object {
        val NONE = VideoOrientation()
    }
}
