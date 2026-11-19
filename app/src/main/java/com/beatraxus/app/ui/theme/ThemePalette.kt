package com.beatraxus.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.beatraxus.app.theme.ThemeSpec

/**
 * Every colour the app's screens share, as one bundle. The tokens in Color.kt (AccentBlue, BgBase,
 * BgGradTop, PremiumTeal ...) read from [ThemePalette.current], so applying a theme changes every
 * screen that uses them, not only the Material components.
 *
 * [Default] is the app's own look. [from] derives the palette of a theme: the accent drives every
 * accent token, and the optional `colors.background` / `colors.surface` / `colors.text` of the theme
 * drive the dark surfaces and text. A theme that leaves those out keeps the app's own surfaces.
 */
@Immutable
data class AppPalette(
    val accent: Color,
    val accentSoft: Color,
    val secondary: Color,
    /** Deep accent shade, used where a darker partner of the accent is needed. */
    val accentDeep: Color,
    /**
     * The app's "premium" highlight (switches, toggles, drawer and settings highlights). Teal under the
     * Default look; under a theme it follows the theme accent.
     */
    val premium: Color,

    val bgDeep: Color,
    val bgBase: Color,
    val bgSurface: Color,
    val bgElevated: Color,
    val bgHighlight: Color,
    val nowPlayingBg: Color,

    /** Top / bottom of the gradient behind the Studio DSP and Surround screens. */
    val gradTop: Color,
    val gradBottom: Color,
    val panel: Color,
    val panelDeep: Color,
    /** Near-black ink used behind Settings and over art. */
    val ink: Color,
    val inkWarm: Color,
    val dialog: Color,
    val card: Color,

    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    /** Settings screen text tones. */
    val textSoft: Color,
    val textDim: Color,
    val divider: Color
) {
    val onAccent: Color get() = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val onSecondary: Color get() = if (secondary.luminance() > 0.5f) Color.Black else Color.White

    companion object {
        val Default = AppPalette(
            accent = Color(0xFF4D8DFF),
            accentSoft = Color(0xFF8BB8FF),
            secondary = Color(0xFFA78BFA),
            accentDeep = Color(0xFF00897B),
            premium = Color(0xFF00C2A8),
            bgDeep = Color(0xFF06070E),
            bgBase = Color(0xFF0B0C16),
            bgSurface = Color(0xFF12131F),
            bgElevated = Color(0xFF1B1D2F),
            bgHighlight = Color(0xFF26294A),
            nowPlayingBg = Color(0xFF10101E),
            gradTop = Color(0xFF050912),
            gradBottom = Color(0xFF0A1322),
            panel = Color(0xFF0B1424),
            panelDeep = Color(0xFF070B14),
            ink = Color(0xFF0A0A0C),
            inkWarm = Color(0xFF14110C),
            dialog = Color(0xFF1E1E1E),
            card = Color(0xFF15161A),
            text = Color(0xFFF0F0FF),
            textSecondary = Color(0xFF8888AA),
            textMuted = Color(0xFF44445A),
            textSoft = Color(0xFFF4F6F8),
            textDim = Color(0xFFAAB3BC),
            divider = Color(0xFF1E1E35)
        )

        fun from(spec: ThemeSpec?): AppPalette {
            if (spec == null) return Default
            val accent = Color(spec.accent)
            val secondary = Color(spec.accentSecondary)
            var p = Default.copy(
                accent = accent,
                accentSoft = lerp(accent, Color.White, 0.40f),
                secondary = secondary,
                accentDeep = lerp(accent, Color.Black, 0.30f),
                premium = accent
            )
            spec.surfaces.background?.let { argb ->
                val bg = Color(argb)
                p = p.copy(
                    bgBase = bg,
                    bgDeep = lerp(bg, Color.Black, 0.40f),
                    nowPlayingBg = lerp(bg, Color.Black, 0.10f),
                    gradTop = lerp(bg, Color.Black, 0.35f),
                    gradBottom = lerp(bg, accent, 0.06f),
                    panelDeep = lerp(bg, Color.Black, 0.20f),
                    ink = lerp(bg, Color.Black, 0.25f),
                    inkWarm = lerp(bg, accent, 0.04f),
                    divider = lerp(bg, Color.White, 0.10f)
                )
            }
            spec.surfaces.surface?.let { argb ->
                val s = Color(argb)
                p = p.copy(
                    bgSurface = s,
                    bgElevated = lerp(s, Color.White, 0.05f),
                    bgHighlight = lerp(s, Color.White, 0.11f),
                    panel = lerp(p.bgBase, s, 0.80f),
                    dialog = s,
                    card = lerp(s, Color.Black, 0.10f)
                )
            }
            spec.surfaces.text?.let { argb ->
                val t = Color(argb)
                val under = p.bgBase
                p = p.copy(
                    text = t,
                    textSoft = t,
                    textSecondary = lerp(t, under, 0.40f),
                    textDim = lerp(t, under, 0.30f),
                    textMuted = lerp(t, under, 0.70f)
                )
            }
            return p
        }
    }
}

/**
 * Live palette of the applied theme. Backed by Compose snapshot state, so every composable (and every
 * draw block) that reads a colour token recomposes or redraws by itself when the theme changes.
 */
object ThemePalette {
    var current: AppPalette by mutableStateOf(AppPalette.Default)
        private set

    fun apply(spec: ThemeSpec?) {
        val next = AppPalette.from(spec)
        if (next != current) current = next
    }
}

/**
 * A dark surface shade that follows the theme. Screens pick slightly different near-black shades for
 * dialogs, sheets, cards and rows (a step lighter than the page, and so on). This keeps each shade's
 * step away from the app's default page colour and re-applies it on the theme's page colour, so those
 * layers keep their depth but take on the theme's tint. Under the Default look it returns [argb] as it is.
 */
fun bgTone(argb: Long): Color {
    val src = Color(argb)
    val base = ThemePalette.current.bgBase
    val ref = AppPalette.Default.bgBase
    if (base == ref) return src
    return Color(
        red = (base.red + (src.red - ref.red)).coerceIn(0f, 1f),
        green = (base.green + (src.green - ref.green)).coerceIn(0f, 1f),
        blue = (base.blue + (src.blue - ref.blue)).coerceIn(0f, 1f),
        alpha = src.alpha
    )
}
