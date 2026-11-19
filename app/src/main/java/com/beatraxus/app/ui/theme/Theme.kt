package com.beatraxus.app.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.beatraxus.app.theme.ThemeSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

/** The Material colour scheme for a palette (the Default look or the applied theme). */
private fun baseColorScheme(p: AppPalette) = darkColorScheme(
    primary             = p.accent,
    onPrimary           = p.onAccent,
    primaryContainer    = p.bgElevated,
    onPrimaryContainer  = p.text,
    secondary           = p.secondary,
    onSecondary         = p.onSecondary,
    secondaryContainer  = p.bgHighlight,
    onSecondaryContainer= p.text,
    background          = p.bgBase,
    onBackground        = p.text,
    surface             = p.bgSurface,
    onSurface           = p.text,
    surfaceVariant      = p.bgElevated,
    onSurfaceVariant    = p.textSecondary,
    outline             = p.divider,
    error               = AccentRed,
    onError             = TextOnAccent
)

/**
 * The font family chosen in Settings > Fonts, as seen from the current composition.
 *
 * Text() only inherits the ambient text style (and its font) when the caller does not pass a
 * `style`. A hand-built `TextStyle(...)` replaces it, so it must name the font explicitly:
 * `TextStyle(fontFamily = appFontFamily, ...)`.
 */
val appFontFamily: FontFamily?
    @Composable
    @ReadOnlyComposable
    get() = LocalTextStyle.current.fontFamily

@Composable
fun BeatraxusTheme(
    fontFamily: FontFamily = FontFamily.Default,
    /** Accent colours of the applied theme (ARGB), or null for the app's default colours. */
    themeAccent: Int? = null,
    themeAccentSecondary: Int? = null,
    /** The applied theme, for its optional extras (surface colours, corner scale, icons). Null = none. */
    activeTheme: ThemeSpec? = null,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    val typography = remember(fontFamily) {
        getBeatraxusTypography(fontFamily)
    }
    // Share the theme's colours with every screen (the tokens in Color.kt read from this).
    SideEffect { ThemePalette.apply(activeTheme) }
    val palette = remember(activeTheme) { AppPalette.from(activeTheme) }
    val surfaces = activeTheme?.surfaces
    val colorScheme = remember(palette, themeAccent, themeAccentSecondary, surfaces) {
        var scheme = if (themeAccent == null) baseColorScheme(palette)
        else {
            val primary = Color(themeAccent)
            val secondary = Color(themeAccentSecondary ?: themeAccent)
            baseColorScheme(palette).copy(
                primary = primary,
                // Keep text readable on the accent whether it is pale or dark.
                onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White,
                secondary = secondary,
                onSecondary = if (secondary.luminance() > 0.5f) Color.Black else Color.White
            )
        }
        // Optional theme colours for Material components (dialogs, sheets, cards, menus, fields).
        surfaces?.background?.let { c ->
            scheme = scheme.copy(background = Color(c))
        }
        surfaces?.surface?.let { c ->
            val base = Color(c)
            scheme = scheme.copy(
                surface = base,
                surfaceVariant = base,
                surfaceContainer = base,
                surfaceContainerHigh = base,
                surfaceContainerHighest = base
            )
        }
        surfaces?.text?.let { c ->
            val text = Color(c)
            scheme = scheme.copy(
                onBackground = text,
                onSurface = text,
                onSurfaceVariant = text.copy(alpha = 0.7f)
            )
        }
        scheme
    }
    val cornerScale = activeTheme?.shape?.cornerScale
    val shapes = remember(cornerScale) {
        if (cornerScale == null) Shapes()
        else Shapes(
            extraSmall = RoundedCornerShape((4f * cornerScale).dp),
            small = RoundedCornerShape((8f * cornerScale).dp),
            medium = RoundedCornerShape((12f * cornerScale).dp),
            large = RoundedCornerShape((16f * cornerScale).dp),
            extraLarge = RoundedCornerShape((28f * cornerScale).dp)
        )
    }

    // Icon look from the theme: recolour/resize, and any images the theme brings with it.
    val icons = activeTheme?.icons
    val packIcons by rememberPackIcons(activeTheme)
    val iconTheme = remember(icons, packIcons) {
        if (icons == null) IconTheme.None
        else IconTheme(
            color = icons.color?.let { Color(it) },
            scale = icons.scale,
            pack = packIcons,
            packTint = icons.packTint
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = typography,
        shapes = shapes
    ) {
        // Make the selected font the base for every Text(), including ones that set only
        // fontSize/color and ones inside dialogs, sheets and menus, so nothing falls back to
        // the device system font.
        CompositionLocalProvider(LocalIconTheme provides iconTheme) {
            ProvideTextStyle(value = LocalTextStyle.current.copy(fontFamily = fontFamily), content = content)
        }
    }
}
