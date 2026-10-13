package com.beatraxus.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun getBeatraxusTypography(fontFamily: FontFamily = FontFamily.Default): Typography {
    return Typography(
        displayLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Black,
            fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-1).sp
        ),
        headlineLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Bold,
            fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Medium,
            fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp, color = TextSecondary
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Medium,
            fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp, color = TextSecondary
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Medium,
            fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp, color = TextMuted
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = TextSecondary
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily, fontWeight = FontWeight.Normal,
            fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, color = TextMuted
        )
    )
}

val BeatraxusTypography = getBeatraxusTypography()

