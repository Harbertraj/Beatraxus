package com.beatraxus.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.theme.ThemeBackground
import com.beatraxus.app.theme.ThemeSpec
import com.beatraxus.app.theme.ThemeSurfaces
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemePaletteTest {

    private fun spec(surfaces: ThemeSurfaces) = ThemeSpec(
        id = "test", name = "Test", author = "t", version = 1, isDark = true,
        accent = 0xFFFF8A4C.toInt(), accentSecondary = 0xFFFF4D6D.toInt(), preview = emptyList(),
        dspPaletteId = DspPalette.default.id,
        dspPrimary = DspPalette.default.primary.toArgb(), dspSecondary = DspPalette.default.secondary.toArgb(),
        seekbarStyle = "BEATRAXUS_COMET", fontId = "system",
        background = ThemeBackground(NowPlayingBackgroundMode.BLUR, 1f, 0.5f, 0.6f, 0.4f),
        surfaces = surfaces
    )

    @After
    fun reset() = ThemePalette.apply(null)

    @Test
    fun noTheme_isTheDefaultLook() {
        ThemePalette.apply(null)
        assertEquals(AppPalette.Default, ThemePalette.current)
        // The premium highlight stays teal under the Default look.
        assertEquals(Color(0xFF00C2A8), PremiumTeal)
    }

    @Test
    fun theme_changesEveryAccentToken() {
        ThemePalette.apply(spec(ThemeSurfaces()))
        assertEquals(Color(0xFFFF8A4C), AccentBlue)
        assertEquals(Color(0xFFFF8A4C), PremiumTeal)
        assertEquals(Color(0xFFFF4D6D), AccentViolet)
    }

    @Test
    fun themeSurfaces_changeBackgroundTokens() {
        ThemePalette.apply(spec(ThemeSurfaces(background = 0xFF0C0A0B.toInt(), surface = 0xFF1A1314.toInt(), text = 0xFFFFF4EC.toInt())))
        assertEquals(Color(0xFF0C0A0B), BgBase)
        assertEquals(Color(0xFF1A1314), BgSurface)
        assertEquals(Color(0xFFFFF4EC), TextPrimary)
        assertNotEquals(AppPalette.Default.gradTop, BgGradTop)
    }

    @Test
    fun bgTone_isIdentityUnderDefault_andShiftsUnderTheme() {
        ThemePalette.apply(null)
        assertEquals(Color(0xFF1B1C26), bgTone(0xFF1B1C26))
        ThemePalette.apply(spec(ThemeSurfaces(background = 0xFF0C0A0B.toInt())))
        val shifted = bgTone(0xFF1B1C26)
        assertNotEquals(Color(0xFF1B1C26), shifted)
        // Still one step lighter than the theme's page colour.
        assert(shifted.red > BgBase.red)
    }
}
