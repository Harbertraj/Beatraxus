package com.beatraxus.app.theme

import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.ui.theme.DspPalette
import kotlin.math.abs

/** The separately customizable parts of Appearance that a theme sets. */
enum class ThemeArea {
    MAIN, NOW_PLAYING, MINI_PLAYER, HOME, SETTINGS, SEEKBAR, FONT, DSP
}

/**
 * True when [area] still holds exactly what the active theme set. As soon as the user customizes
 * that area by hand it stops matching, so the "Theme" tag under its name goes away by itself.
 * No extra bookkeeping is stored: the comparison is always against the live settings.
 */
fun AppearanceConfig.isFromTheme(area: ThemeArea): Boolean {
    val t = activeTheme ?: return false
    return when (area) {
        ThemeArea.SEEKBAR -> seekbarStyle.name == t.seekbarStyle
        ThemeArea.FONT -> selectedFontId == t.fontId
        ThemeArea.DSP -> dspPaletteId == t.dspPaletteId &&
            (t.dspPaletteId != DspPalette.CUSTOM_ID ||
                (dspCustomPrimary == t.dspPrimary && dspCustomSecondary == t.dspSecondary))
        ThemeArea.MAIN -> t.backgroundFor(ThemeSurface.MAIN).matches(
            mainBackgroundMode, mainBlurIntensity, mainBlurDarkness, mainSolidColorIntensity, mainSolidColorDarkness
        )
        ThemeArea.HOME -> t.backgroundFor(ThemeSurface.HOME).matches(
            homeBackgroundMode, homeBlurIntensity, homeBlurDarkness, homeSolidColorIntensity, homeSolidColorDarkness
        )
        ThemeArea.SETTINGS -> t.backgroundFor(ThemeSurface.SETTINGS).matches(
            settingsBackgroundMode, settingsBlurIntensity, settingsBlurDarkness,
            settingsSolidColorIntensity, settingsSolidColorDarkness
        )
        ThemeArea.NOW_PLAYING -> t.backgroundFor(ThemeSurface.NOW_PLAYING).matches(
            nowPlayingBackgroundMode, nowPlayingBlurIntensity, nowPlayingBlurDarkness,
            nowPlayingSolidColorIntensity, nowPlayingSolidColorDarkness
        )
        ThemeArea.MINI_PLAYER -> t.backgroundFor(ThemeSurface.MINI_PLAYER).matches(
            miniPlayerBackgroundMode, miniPlayerBlurIntensity, miniPlayerBlurDarkness,
            miniPlayerSolidColorIntensity, miniPlayerSolidColorDarkness
        )
    }
}

/** True for the Appearance entries that cover several areas (e.g. "Main Interface" = main + mini player). */
fun AppearanceConfig.isFromTheme(vararg areas: ThemeArea): Boolean =
    activeTheme != null && areas.all { isFromTheme(it) }

/**
 * Subtitle for an Appearance entry: "Theme: <name>" while it still follows the active theme,
 * otherwise the entry's normal description.
 */
fun AppearanceConfig.themeSubtitle(normal: String, vararg areas: ThemeArea): String {
    val name = activeTheme?.name
    return if (name != null && isFromTheme(*areas)) "Theme: $name" else normal
}

private fun SurfaceBackground.matches(
    mode: com.beatraxus.app.model.NowPlayingBackgroundMode,
    blurIntensity: Float,
    blurDarkness: Float,
    solidIntensity: Float,
    solidDarkness: Float
): Boolean {
    if (this.mode != mode) return false
    // Only the values the current mode actually uses have to match.
    return when (mode) {
        com.beatraxus.app.model.NowPlayingBackgroundMode.BLACK -> true
        com.beatraxus.app.model.NowPlayingBackgroundMode.SOLID ->
            near(this.solidIntensity, solidIntensity) && near(this.solidDarkness, solidDarkness)
        com.beatraxus.app.model.NowPlayingBackgroundMode.BLUR ->
            near(this.blurIntensity, blurIntensity) && near(this.blurDarkness, blurDarkness)
    }
}

private fun near(a: Float, b: Float) = abs(a - b) < 0.005f

/** Accent override for the Material colour scheme, or null to keep the app's default. */
val AppearanceConfig.themeAccent: Int? get() = activeTheme?.accent
val AppearanceConfig.themeAccentSecondary: Int? get() = activeTheme?.accentSecondary
