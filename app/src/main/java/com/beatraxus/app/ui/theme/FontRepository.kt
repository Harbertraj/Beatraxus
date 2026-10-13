package com.beatraxus.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.beatraxus.app.R

data class AppFontItem(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val fontFamily: FontFamily
)

/**
 * All selectable fonts are bundled inside the app (res/font) instead of being downloaded through
 * Google Play Services. Downloadable fonts silently fall back to the device font when the provider
 * is missing, offline, or when the phone's own "system font" setting takes over, which made every
 * option look identical. Bundled fonts always render, on every device, and work offline.
 *
 * Fonts are licensed under the SIL Open Font License, Apache 2.0 or the Ubuntu Font Licence; see
 * ATTRIBUTION.md.
 */
object FontRepository {

    /** Builds a family from bundled files. Single-weight fonts pass no bold file. */
    private fun bundled(regular: Int, bold: Int? = null): FontFamily {
        val fonts = buildList {
            add(Font(regular, FontWeight.Normal))
            if (bold != null) add(Font(bold, FontWeight.Bold))
        }
        return FontFamily(fonts)
    }

    // Shared families. The generic entries (Sans-Serif, Serif, Monospace, Cursive) are backed by
    // the fonts Android itself uses for those names, bundled here, because the real generic
    // families are replaced by the device font on phones with a custom system font.
    private val roboto = bundled(R.font.roboto_regular, R.font.roboto_bold)
    private val notoSerif = bundled(R.font.noto_serif_regular, R.font.noto_serif_bold)
    private val robotoMono = bundled(R.font.roboto_mono_regular, R.font.roboto_mono_bold)
    private val dancingScript = bundled(R.font.dancing_script_regular, R.font.dancing_script_bold)

    val availableFonts: List<AppFontItem> = listOf(
        // System / Default (FIRST OPTION - the only one that follows the device font)
        AppFontItem(
            id = "system",
            name = "System Font",
            category = "System",
            description = "Device default font",
            fontFamily = FontFamily.Default
        ),
        AppFontItem(
            id = "sans_serif",
            name = "Sans-Serif",
            category = "System",
            description = "Standard clean sans-serif (Roboto)",
            fontFamily = roboto
        ),
        AppFontItem(
            id = "serif",
            name = "Serif",
            category = "System",
            description = "Classic serif typography (Noto Serif)",
            fontFamily = notoSerif
        ),
        AppFontItem(
            id = "monospace",
            name = "Monospace",
            category = "System",
            description = "Fixed-width terminal style (Roboto Mono)",
            fontFamily = robotoMono
        ),
        AppFontItem(
            id = "cursive",
            name = "Cursive",
            category = "System",
            description = "Handwritten style font (Dancing Script)",
            fontFamily = dancingScript
        ),

        // Modern Sans-Serif
        AppFontItem("roboto", "Roboto", "Sans-Serif", "Modern & geometric", roboto),
        AppFontItem("open_sans", "Open Sans", "Sans-Serif", "Neutral & legible", bundled(R.font.open_sans_regular, R.font.open_sans_bold)),
        AppFontItem("lato", "Lato", "Sans-Serif", "Warm & balanced", bundled(R.font.lato_regular, R.font.lato_bold)),
        AppFontItem("montserrat", "Montserrat", "Sans-Serif", "Urban & bold", bundled(R.font.montserrat_regular, R.font.montserrat_bold)),
        AppFontItem("poppins", "Poppins", "Sans-Serif", "Geometric & stylish", bundled(R.font.poppins_regular, R.font.poppins_bold)),
        AppFontItem("inter", "Inter", "Sans-Serif", "Designed for screens", bundled(R.font.inter_regular, R.font.inter_bold)),
        AppFontItem("nunito", "Nunito", "Sans-Serif", "Rounded & friendly", bundled(R.font.nunito_regular, R.font.nunito_bold)),
        AppFontItem("raleway", "Raleway", "Sans-Serif", "Elegant sans-serif", bundled(R.font.raleway_regular, R.font.raleway_bold)),
        AppFontItem("rubik", "Rubik", "Sans-Serif", "Slightly rounded corners", bundled(R.font.rubik_regular, R.font.rubik_bold)),
        AppFontItem("ubuntu", "Ubuntu", "Sans-Serif", "Distinctive & clear", bundled(R.font.ubuntu_regular, R.font.ubuntu_bold)),
        AppFontItem("work_sans", "Work Sans", "Sans-Serif", "Optimized for print & screen", bundled(R.font.work_sans_regular, R.font.work_sans_bold)),
        AppFontItem("fira_sans", "Fira Sans", "Sans-Serif", "Mozilla open typeface", bundled(R.font.fira_sans_regular, R.font.fira_sans_bold)),
        AppFontItem("quicksand", "Quicksand", "Sans-Serif", "Display sans with rounded ends", bundled(R.font.quicksand_regular, R.font.quicksand_bold)),
        AppFontItem("pt_sans", "PT Sans", "Sans-Serif", "Universal legibility", bundled(R.font.pt_sans_regular, R.font.pt_sans_bold)),
        AppFontItem("kanit", "Kanit", "Sans-Serif", "Futuristic Thai-influenced", bundled(R.font.kanit_regular, R.font.kanit_bold)),
        AppFontItem("noto_sans", "Noto Sans", "Sans-Serif", "Global harmonious font", bundled(R.font.noto_sans_regular, R.font.noto_sans_bold)),
        AppFontItem("titillium_web", "Titillium Web", "Sans-Serif", "Technical & sharp", bundled(R.font.titillium_web_regular, R.font.titillium_web_bold)),
        AppFontItem("outfit", "Outfit", "Sans-Serif", "Modern geometric brand font", bundled(R.font.outfit_regular, R.font.outfit_bold)),
        AppFontItem("plus_jakarta_sans", "Plus Jakarta Sans", "Sans-Serif", "Contemporary clean", bundled(R.font.plus_jakarta_sans_regular, R.font.plus_jakarta_sans_bold)),
        AppFontItem("syne", "Syne", "Sans-Serif", "Artistic & unique", bundled(R.font.syne_regular, R.font.syne_bold)),

        // Display & Headline
        AppFontItem("bebas_neue", "Bebas Neue", "Display", "Tall & impactful all-caps", bundled(R.font.bebas_neue_regular)),
        AppFontItem("oswald", "Oswald", "Display", "Condensed classic gothic", bundled(R.font.oswald_regular, R.font.oswald_bold)),
        AppFontItem("righteous", "Righteous", "Display", "Retro-futuristic style", bundled(R.font.righteous_regular)),
        AppFontItem("bungee", "Bungee", "Display", "Urban signage weight", bundled(R.font.bungee_regular)),
        AppFontItem("audiowide", "Audiowide", "Display", "Techno & sci-fi aesthetic", bundled(R.font.audiowide_regular)),
        AppFontItem("orbitron", "Orbitron", "Display", "Futuristic geometric display", bundled(R.font.orbitron_regular, R.font.orbitron_bold)),
        AppFontItem("space_grotesk", "Space Grotesk", "Display", "Proportional monospaced aesthetic", bundled(R.font.space_grotesk_regular, R.font.space_grotesk_bold)),
        AppFontItem("abril_fatface", "Abril Fatface", "Display", "Heavy elegant titling", bundled(R.font.abril_fatface_regular)),
        AppFontItem("cinzel", "Cinzel", "Display", "Roman inscription inspired", bundled(R.font.cinzel_regular, R.font.cinzel_bold)),

        // Serif
        AppFontItem("playfair_display", "Playfair Display", "Serif", "High-contrast editorial serif", bundled(R.font.playfair_display_regular, R.font.playfair_display_bold)),
        AppFontItem("merriweather", "Merriweather", "Serif", "Designed for screen reading", bundled(R.font.merriweather_regular, R.font.merriweather_bold)),
        AppFontItem("lora", "Lora", "Serif", "Contemporary calligraphic serif", bundled(R.font.lora_regular, R.font.lora_bold)),
        AppFontItem("pt_serif", "PT Serif", "Serif", "High legibility serif", bundled(R.font.pt_serif_regular, R.font.pt_serif_bold)),
        AppFontItem("cormorant_garamond", "Cormorant Garamond", "Serif", "Refined classic Garamond", bundled(R.font.cormorant_garamond_regular, R.font.cormorant_garamond_bold)),

        // Handwritten & Creative
        AppFontItem("lobster", "Lobster", "Handwritten", "Bold script style", bundled(R.font.lobster_regular)),
        AppFontItem("pacifico", "Pacifico", "Handwritten", "Brush script 1950s surf style", bundled(R.font.pacifico_regular)),
        AppFontItem("dancing_script", "Dancing Script", "Handwritten", "Lively casual script", dancingScript),
        AppFontItem("caveat", "Caveat", "Handwritten", "Natural handwriting", bundled(R.font.caveat_regular, R.font.caveat_bold)),
        AppFontItem("great_vibes", "Great Vibes", "Handwritten", "Formal calligraphic script", bundled(R.font.great_vibes_regular)),
        AppFontItem("satisfy", "Satisfy", "Handwritten", "Timeless classic script", bundled(R.font.satisfy_regular)),
        AppFontItem("comfortaa", "Comfortaa", "Handwritten", "Rounded geometric style", bundled(R.font.comfortaa_regular, R.font.comfortaa_bold)),
        AppFontItem("permanent_marker", "Permanent Marker", "Handwritten", "Sharpie marker aesthetic", bundled(R.font.permanent_marker_regular)),
        AppFontItem("amatic_sc", "Amatic SC", "Handwritten", "Condensed hand-drawn caps", bundled(R.font.amatic_sc_regular, R.font.amatic_sc_bold)),
        AppFontItem("shadows_into_light", "Shadows Into Light", "Handwritten", "Neat personal handwriting", bundled(R.font.shadows_into_light_regular)),

        // Monospace & Retro Code
        AppFontItem("jetbrains_mono", "JetBrains Mono", "Monospace", "Developer-focused font", bundled(R.font.jetbrains_mono_regular, R.font.jetbrains_mono_bold)),
        AppFontItem("fira_code", "Fira Code", "Monospace", "Monospaced with ligatures", bundled(R.font.fira_code_regular, R.font.fira_code_bold)),
        AppFontItem("source_code_pro", "Source Code Pro", "Monospace", "Adobe monospaced family", bundled(R.font.source_code_pro_regular, R.font.source_code_pro_bold)),
        AppFontItem("inconsolata", "Inconsolata", "Monospace", "Clean humanist monospace", bundled(R.font.inconsolata_regular, R.font.inconsolata_bold)),
        AppFontItem("space_mono", "Space Mono", "Monospace", "Eclectic headline monospace", bundled(R.font.space_mono_regular, R.font.space_mono_bold)),
        AppFontItem("press_start_2p", "Press Start 2P", "Retro", "8-bit retro arcade game style", bundled(R.font.press_start_2p_regular)),
        AppFontItem("silkscreen", "Silkscreen", "Retro", "Compact 8-bit screen font", bundled(R.font.silkscreen_regular, R.font.silkscreen_bold))
    )

    fun getFontFamily(fontId: String): FontFamily {
        return availableFonts.firstOrNull { it.id == fontId }?.fontFamily ?: FontFamily.Default
    }

    fun getFontItem(fontId: String): AppFontItem {
        return availableFonts.firstOrNull { it.id == fontId } ?: availableFonts.first()
    }
}
