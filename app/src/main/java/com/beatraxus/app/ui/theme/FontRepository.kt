package com.beatraxus.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.beatraxus.app.R

data class AppFontItem(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val fontFamily: FontFamily
)

object FontRepository {
    private val googleFontProvider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs
    )

    private fun googleFont(fontName: String): FontFamily {
        val font = GoogleFont(fontName)
        return FontFamily(
            Font(googleFont = font, fontProvider = googleFontProvider, weight = FontWeight.Normal),
            Font(googleFont = font, fontProvider = googleFontProvider, weight = FontWeight.Bold)
        )
    }

    val availableFonts: List<AppFontItem> = listOf(
        // System / Default (FIRST OPTION - Default System Font)
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
            description = "Standard clean sans-serif",
            fontFamily = FontFamily.SansSerif
        ),
        AppFontItem(
            id = "serif",
            name = "Serif",
            category = "System",
            description = "Classic serif typography",
            fontFamily = FontFamily.Serif
        ),
        AppFontItem(
            id = "monospace",
            name = "Monospace",
            category = "System",
            description = "Fixed-width terminal style",
            fontFamily = FontFamily.Monospace
        ),
        AppFontItem(
            id = "cursive",
            name = "Cursive",
            category = "System",
            description = "Handwritten style font",
            fontFamily = FontFamily.Cursive
        ),

        // Modern Sans-Serif
        AppFontItem("roboto", "Roboto", "Sans-Serif", "Modern & geometric", googleFont("Roboto")),
        AppFontItem("open_sans", "Open Sans", "Sans-Serif", "Neutral & legible", googleFont("Open Sans")),
        AppFontItem("lato", "Lato", "Sans-Serif", "Warm & balanced", googleFont("Lato")),
        AppFontItem("montserrat", "Montserrat", "Sans-Serif", "Urban & bold", googleFont("Montserrat")),
        AppFontItem("poppins", "Poppins", "Sans-Serif", "Geometric & stylish", googleFont("Poppins")),
        AppFontItem("inter", "Inter", "Sans-Serif", "Designed for screens", googleFont("Inter")),
        AppFontItem("nunito", "Nunito", "Sans-Serif", "Rounded & friendly", googleFont("Nunito")),
        AppFontItem("raleway", "Raleway", "Sans-Serif", "Elegant sans-serif", googleFont("Raleway")),
        AppFontItem("rubik", "Rubik", "Sans-Serif", "Slightly rounded corners", googleFont("Rubik")),
        AppFontItem("ubuntu", "Ubuntu", "Sans-Serif", "Distinctive & clear", googleFont("Ubuntu")),
        AppFontItem("work_sans", "Work Sans", "Sans-Serif", "Optimized for print & screen", googleFont("Work Sans")),
        AppFontItem("fira_sans", "Fira Sans", "Sans-Serif", "Mozilla open typeface", googleFont("Fira Sans")),
        AppFontItem("quicksand", "Quicksand", "Sans-Serif", "Display sans with rounded ends", googleFont("Quicksand")),
        AppFontItem("pt_sans", "PT Sans", "Sans-Serif", "Universal legibility", googleFont("PT Sans")),
        AppFontItem("kanit", "Kanit", "Sans-Serif", "Futuristic Thai-influenced", googleFont("Kanit")),
        AppFontItem("noto_sans", "Noto Sans", "Sans-Serif", "Global harmonious font", googleFont("Noto Sans")),
        AppFontItem("titillium_web", "Titillium Web", "Sans-Serif", "Technical & sharp", googleFont("Titillium Web")),
        AppFontItem("outfit", "Outfit", "Sans-Serif", "Modern geometric brand font", googleFont("Outfit")),
        AppFontItem("plus_jakarta_sans", "Plus Jakarta Sans", "Sans-Serif", "Contemporary clean", googleFont("Plus Jakarta Sans")),
        AppFontItem("syne", "Syne", "Sans-Serif", "Artistic & unique", googleFont("Syne")),

        // Display & Headline
        AppFontItem("bebas_neue", "Bebas Neue", "Display", "Tall & impactful all-caps", googleFont("Bebas Neue")),
        AppFontItem("oswald", "Oswald", "Display", "Condensed classic gothic", googleFont("Oswald")),
        AppFontItem("righteous", "Righteous", "Display", "Retro-futuristic style", googleFont("Righteous")),
        AppFontItem("bungee", "Bungee", "Display", "Urban signage weight", googleFont("Bungee")),
        AppFontItem("audiowide", "Audiowide", "Display", "Techno & sci-fi aesthetic", googleFont("Audiowide")),
        AppFontItem("orbitron", "Orbitron", "Display", "Futuristic geometric display", googleFont("Orbitron")),
        AppFontItem("space_grotesk", "Space Grotesk", "Display", "Proportional monospaced aesthetic", googleFont("Space Grotesk")),
        AppFontItem("abril_fatface", "Abril Fatface", "Display", "Heavy elegant titling", googleFont("Abril Fatface")),
        AppFontItem("cinzel", "Cinzel", "Display", "Roman inscription inspired", googleFont("Cinzel")),

        // Serif
        AppFontItem("playfair_display", "Playfair Display", "Serif", "High-contrast editorial serif", googleFont("Playfair Display")),
        AppFontItem("merriweather", "Merriweather", "Serif", "Designed for screen reading", googleFont("Merriweather")),
        AppFontItem("lora", "Lora", "Serif", "Contemporary calligraphic serif", googleFont("Lora")),
        AppFontItem("pt_serif", "PT Serif", "Serif", "High legibility serif", googleFont("PT Serif")),
        AppFontItem("cormorant_garamond", "Cormorant Garamond", "Serif", "Refined classic Garamond", googleFont("Cormorant Garamond")),

        // Handwritten & Creative
        AppFontItem("lobster", "Lobster", "Handwritten", "Bold script style", googleFont("Lobster")),
        AppFontItem("pacifico", "Pacifico", "Handwritten", "Brush script 1950s surf style", googleFont("Pacifico")),
        AppFontItem("dancing_script", "Dancing Script", "Handwritten", "Lively casual script", googleFont("Dancing Script")),
        AppFontItem("caveat", "Caveat", "Handwritten", "Natural handwriting", googleFont("Caveat")),
        AppFontItem("great_vibes", "Great Vibes", "Handwritten", "Formal calligraphic script", googleFont("Great Vibes")),
        AppFontItem("satisfy", "Satisfy", "Handwritten", "Timeless classic script", googleFont("Satisfy")),
        AppFontItem("comfortaa", "Comfortaa", "Handwritten", "Rounded geometric style", googleFont("Comfortaa")),
        AppFontItem("permanent_marker", "Permanent Marker", "Handwritten", "Sharpie marker aesthetic", googleFont("Permanent Marker")),
        AppFontItem("amatic_sc", "Amatic SC", "Handwritten", "Condensed hand-drawn caps", googleFont("Amatic SC")),
        AppFontItem("shadows_into_light", "Shadows Into Light", "Handwritten", "Neat personal handwriting", googleFont("Shadows Into Light")),

        // Monospace & Retro Code
        AppFontItem("jetbrains_mono", "JetBrains Mono", "Monospace", "Developer-focused font", googleFont("JetBrains Mono")),
        AppFontItem("fira_code", "Fira Code", "Monospace", "Monospaced with ligatures", googleFont("Fira Code")),
        AppFontItem("source_code_pro", "Source Code Pro", "Monospace", "Adobe monospaced family", googleFont("Source Code Pro")),
        AppFontItem("inconsolata", "Inconsolata", "Monospace", "Clean humanist monospace", googleFont("Inconsolata")),
        AppFontItem("space_mono", "Space Mono", "Monospace", "Eclectic headline monospace", googleFont("Space Mono")),
        AppFontItem("press_start_2p", "Press Start 2P", "Retro", "8-bit retro arcade game style", googleFont("Press Start 2P")),
        AppFontItem("silkscreen", "Silkscreen", "Retro", "Compact 8-bit screen font", googleFont("Silkscreen"))
    )

    fun getFontFamily(fontId: String): FontFamily {
        return availableFonts.firstOrNull { it.id == fontId }?.fontFamily ?: FontFamily.Default
    }

    fun getFontItem(fontId: String): AppFontItem {
        return availableFonts.firstOrNull { it.id == fontId } ?: availableFonts.first()
    }
}
