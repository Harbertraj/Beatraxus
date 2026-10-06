package com.beatraxus.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.beatraxus.app.model.AppearanceConfig

/**
 * Dual colour palette for the Studio DSP interface.
 *
 * A palette is just two colours (primary + secondary). Every other tone used by the DSP screen
 * (surfaces, sheets, soft glow, metal knobs, tertiary accent) is derived from those two, so a
 * preset and a user-made custom palette behave identically.
 */
data class DspPalette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color
) {
    val accent: Color get() = primary
    val accentSoft: Color get() = lerp(primary, Color.White, 0.55f)
    val graphic: Color get() = lerp(primary, Color.White, 0.18f)
    val tertiary: Color get() = rotateHue(secondary, -28f)

    // Dark glass tones, tinted by the palette.
    val surface: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.075f)
    val sheet: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.11f)
    val sheetHigh: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.16f)
    val ink: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.025f)
    val bgTop: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.5f), 0.14f)
    val bgBottom: Color get() = lerp(Color.Black, secondary, 0.02f)
    val panelDeep: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.05f)
    val panelRaised: Color get() = lerp(Color.Black, lerp(primary, secondary, 0.35f), 0.19f)

    // Brushed-metal tones for thumbs / knobs.
    val metalHigh: Color get() = lerp(Color(0xFF1A1A1A), lerp(primary, secondary, 0.4f), 0.32f)
    val metalMid: Color get() = lerp(Color(0xFF101010), lerp(primary, secondary, 0.4f), 0.17f)
    val metalLow: Color get() = lerp(Color(0xFF080808), lerp(primary, secondary, 0.4f), 0.08f)

    companion object {
        const val CUSTOM_ID = "custom"

        val SolarFlare = DspPalette("solar_flare", "Solar Flare", Color(0xFFFFD60A), Color(0xFFFF7A00))
        val AuroraDusk = DspPalette("aurora_dusk", "Aurora Dusk", Color(0xFF3CF2C0), Color(0xFF8B5CF6))
        val CyberPop = DspPalette("cyber_pop", "Cyber Pop", Color(0xFF22D3EE), Color(0xFFE879F9))
        val RoseGold = DspPalette("rose_gold", "Rose Gold", Color(0xFFFF6B9A), Color(0xFFFFB86B))
        val Glacier = DspPalette("glacier", "Glacier", Color(0xFF7DD3FC), Color(0xFF6366F1))
        val Verdant = DspPalette("verdant", "Verdant", Color(0xFFA3E635), Color(0xFF10B981))

        val presets: List<DspPalette> = listOf(SolarFlare, AuroraDusk, CyberPop, RoseGold, Glacier, Verdant)
        val default: DspPalette = SolarFlare

        fun custom(primary: Color, secondary: Color) =
            DspPalette(CUSTOM_ID, "Custom Dual", primary, secondary)

        fun resolve(id: String, customPrimary: Int, customSecondary: Int): DspPalette =
            if (id == CUSTOM_ID) custom(Color(customPrimary), Color(customSecondary))
            else presets.firstOrNull { it.id == id } ?: default

        fun from(config: AppearanceConfig): DspPalette =
            resolve(config.dspPaletteId, config.dspCustomPrimary, config.dspCustomSecondary)

        private fun rotateHue(color: Color, degrees: Float): Color {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color.toArgb(), hsv)
            hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
            return Color(android.graphics.Color.HSVToColor(hsv))
        }
    }
}

/**
 * Live palette for the Studio DSP interface. Backed by Compose snapshot state, so every
 * composable that reads [current] recomposes automatically when the palette changes.
 */
object DspPaletteState {
    var current: DspPalette by mutableStateOf(DspPalette.default)
        private set

    fun apply(config: AppearanceConfig) {
        val next = DspPalette.from(config)
        if (next != current) current = next
    }
}
