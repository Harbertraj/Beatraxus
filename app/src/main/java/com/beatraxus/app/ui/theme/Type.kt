package com.beatraxus.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun getBeatraxusTypography(fontFamily: FontFamily = FontFamily.Default): Typography {
    // Material3 ships defaults for all 15 text styles, and every default uses the system font.
    // Plain Text() calls inherit bodyLarge (provided by MaterialTheme) and several screens use
    // headlineMedium / headlineSmall / labelMedium, none of which were overridden before. That
    // is why the selected in-app font only showed up in some places. Start from the defaults and
    // force the chosen font family onto every style, then layer the app's custom styles on top.
    val base = Typography()
    return Typography(
        displayLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Black,
            fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-1).sp
        ),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Bold,
            fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp
        ),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Medium,
            fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp, color = TextSecondary
        ),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = TextSecondary
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Normal,
            fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, color = TextMuted
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Medium,
            fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp, color = TextSecondary
        ),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Medium,
            fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp, color = TextMuted
        )
    )
}

val BeatraxusTypography = getBeatraxusTypography()
