package com.beatraxus.app.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

private val BeatraxusDarkColorScheme = darkColorScheme(
    primary             = AccentBlue,
    onPrimary           = TextOnAccent,
    primaryContainer    = BgElevated,
    onPrimaryContainer  = TextPrimary,
    secondary           = AccentRed,
    onSecondary         = TextOnAccent,
    secondaryContainer  = BgHighlight,
    onSecondaryContainer= TextPrimary,
    background          = BgBase,
    onBackground        = TextPrimary,
    surface             = BgSurface,
    onSurface           = TextPrimary,
    surfaceVariant      = BgElevated,
    onSurfaceVariant    = TextSecondary,
    outline             = Divider,
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
    MaterialTheme(
        colorScheme = BeatraxusDarkColorScheme,
        typography  = typography
    ) {
        // Make the selected font the base for every Text(), including ones that set only
        // fontSize/color and ones inside dialogs, sheets and menus, so nothing falls back to
        // the device system font.
        ProvideTextStyle(value = LocalTextStyle.current.copy(fontFamily = fontFamily), content = content)
    }
}
