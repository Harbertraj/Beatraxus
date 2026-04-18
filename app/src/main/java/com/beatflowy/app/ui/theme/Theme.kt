package com.beatflowy.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
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

@Composable
fun BeatraxusTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BgDeep.toArgb()
            window.navigationBarColor = BgDeep.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = BeatraxusDarkColorScheme,
        typography  = BeatraxusTypography,
        content     = content
    )
}
