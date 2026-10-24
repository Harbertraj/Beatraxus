package com.beatraxus.app.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video upscaler: redraws every frame on the GPU at a higher resolution than the file, so low-resolution video
 * looks cleaner on a high-resolution screen.
 *
 * These are lightweight single-pass shaders (not the heavy neural-network upscalers of desktop players), so they
 * run on ordinary phones.
 */
enum class VideoUpscaleMode(val label: String, val description: String) {
    OFF("Off", "Show the picture at the size the video has; the screen scales it"),

    /** Catmull-Rom bicubic scaling followed by a limited sharpen. */
    CLEAN("Clean upscale", "Smooth bicubic scaling with gentle sharpening. Best for movies and live action"),

    /** Edge-directed smoothing on top of the bicubic result, then the same sharpen. */
    EDGE("Edge-smooth", "Smooths jagged diagonal lines along their edges, then sharpens. Best for anime, cartoons and low-resolution video");

    val isOn: Boolean get() = this != OFF

    /** Shader switch value: 0 = off, 1 = clean, 2 = edge-smooth. */
    val shaderValue: Int get() = ordinal

    companion object {
        const val PREF_KEY = "video_upscale_mode"
        const val STRENGTH_KEY = "video_upscale_strength"
        const val DEFAULT_STRENGTH = 0.5f

        fun fromName(name: String?): VideoUpscaleMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** Decides how large the upscaled frame is. Pure maths so it can be unit tested. */
object UpscaleSizing {
    /** Never draw more pixels than a 4K frame, whatever the screen says. */
    const val MAX_OUTPUT_PIXELS = 3840L * 2160L

    /** Largest enlargement. More than this costs a lot of GPU for no visible gain. */
    const val MAX_SCALE = 2f

    /** Below this enlargement the frame is only sharpened, not resized. */
    const val MIN_SCALE = 1.15f

    /** Returns the output width and height for an input frame on a screen whose long side is [displayLongSide] px. */
    fun outputSize(inputWidth: Int, inputHeight: Int, displayLongSide: Int): Pair<Int, Int> {
        if (inputWidth <= 0 || inputHeight <= 0) return inputWidth to inputHeight
        val inputLong = max(inputWidth, inputHeight)
        var scale = min(MAX_SCALE, displayLongSide.toFloat() / inputLong)
        if (scale < MIN_SCALE) return inputWidth to inputHeight
        val area = inputWidth.toLong() * inputHeight * scale.toDouble() * scale
        if (area > MAX_OUTPUT_PIXELS) scale = kotlin.math.sqrt(MAX_OUTPUT_PIXELS / (inputWidth.toDouble() * inputHeight)).toFloat()
        if (scale < MIN_SCALE) return inputWidth to inputHeight
        val w = (inputWidth * scale).roundToInt().coerceAtLeast(inputWidth)
        val h = (inputHeight * scale).roundToInt().coerceAtLeast(inputHeight)
        return w to h
    }
}
