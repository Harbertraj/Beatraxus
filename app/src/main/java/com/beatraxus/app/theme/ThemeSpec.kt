package com.beatraxus.app.theme

import com.beatraxus.app.model.NowPlayingBackgroundMode

/**
 * A theme is a bundle of the appearance settings the app already has. It is data only: colours,
 * a DSP palette, a seekbar style, a font and background/blur settings. Never code.
 *
 * Colours are ARGB ints. Everything here has already been validated and clamped by [ThemeCodec],
 * so it is always safe to hand to the settings store.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    /** "dark" or "light" in the file. The app currently only renders dark surfaces, see [ThemeCodec]. */
    val isDark: Boolean,
    /** Main accent. Feeds the Material colour scheme (switches, sliders, progress, fields). */
    val accent: Int,
    val accentSecondary: Int,
    /** 2 to 5 swatches for the gallery card, so no preview images are needed. */
    val preview: List<Int>,
    /** A preset id from DspPalette.presets, or DspPalette.CUSTOM_ID to use [dspPrimary]/[dspSecondary]. */
    val dspPaletteId: String,
    val dspPrimary: Int,
    val dspSecondary: Int,
    /** A com.beatraxus.app.model.SeekbarStyle name. */
    val seekbarStyle: String,
    /** A FontRepository id. */
    val fontId: String,
    val background: ThemeBackground
)

/**
 * Background look shared by every surface (main, home, settings, now playing, mini player).
 * [blurScale] multiplies each surface's own default blur, so the surfaces keep their relative
 * look instead of all getting the same blur radius.
 */
data class ThemeBackground(
    val mode: NowPlayingBackgroundMode,
    val blurScale: Float,
    val blurDarkness: Float,
    val solidIntensity: Float,
    val solidDarkness: Float
)

/** The surfaces that have their own background settings, with the app's default blur for each. */
enum class ThemeSurface(val defaultBlur: Float) {
    NOW_PLAYING(210f),
    MAIN(120f),
    HOME(120f),
    SETTINGS(120f),
    MINI_PLAYER(70f)
}

/** The concrete numbers a theme resolves to for one surface. */
data class SurfaceBackground(
    val mode: NowPlayingBackgroundMode,
    val blurIntensity: Float,
    val blurDarkness: Float,
    val solidIntensity: Float,
    val solidDarkness: Float
)

fun ThemeSpec.backgroundFor(surface: ThemeSurface): SurfaceBackground = SurfaceBackground(
    mode = background.mode,
    // The settings slider runs from 10 to 250.
    blurIntensity = (surface.defaultBlur * background.blurScale).coerceIn(10f, 250f),
    blurDarkness = background.blurDarkness,
    solidIntensity = background.solidIntensity,
    solidDarkness = background.solidDarkness
)

/** One row of the store's index.json. */
data class ThemeIndexEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    /** Path of the theme file relative to the repo root, e.g. "themes/neon-night.json". */
    val file: String,
    val isDark: Boolean,
    val preview: List<Int>
)
