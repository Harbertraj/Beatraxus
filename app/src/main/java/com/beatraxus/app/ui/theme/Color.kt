package com.beatraxus.app.ui.theme

import androidx.compose.ui.graphics.Color

// Status / fixed colours: these never change with a theme.
val PremiumGold   = Color(0xFF00C2A8)
val PremiumGoldSoft = Color(0xFF80E1D4)
val OnyxBlack     = Color(0xFF08090C)
val AccentRed     = Color(0xFFFF2D55)
val AccentRedSoft = Color(0xFFFF6B81)
val GradientStart = Color(0xFFFF2D55)
val GradientMid   = Color(0xFF9B2FC5)
val GradientEnd   = Color(0xFF0A84FF)
val ToggleOn      = Color(0xFF30D158)
val ToggleOff     = Color(0xFF3A3A55)

// Theme-driven colours. They are read from the applied theme's palette (see ThemePalette), so a
// theme change reaches every screen that uses them. They MUST stay getters: never copy one into a
// top-level `val` (that would freeze the colour at app start).
val BgDeep: Color           get() = ThemePalette.current.bgDeep
val BgBase: Color           get() = ThemePalette.current.bgBase
val BgSurface: Color        get() = ThemePalette.current.bgSurface
val BgElevated: Color       get() = ThemePalette.current.bgElevated
val BgHighlight: Color      get() = ThemePalette.current.bgHighlight
val NowPlayingBg: Color     get() = ThemePalette.current.nowPlayingBg

val AccentBlue: Color       get() = ThemePalette.current.accent
val AccentBlueSoft: Color   get() = ThemePalette.current.accentSoft
/** Default secondary accent (Material `secondary`). AccentRed stays reserved for errors and alerts. */
val AccentViolet: Color     get() = ThemePalette.current.secondary
val SliderActive: Color     get() = ThemePalette.current.accent
val RippleColor: Color      get() = ThemePalette.current.accent.copy(alpha = 0.125f)
val TextOnAccent: Color     get() = ThemePalette.current.onAccent

val TextPrimary: Color      get() = ThemePalette.current.text
val TextSecondary: Color    get() = ThemePalette.current.textSecondary
val TextMuted: Color        get() = ThemePalette.current.textMuted
val OnSurface: Color        get() = ThemePalette.current.textSoft
val Divider: Color          get() = ThemePalette.current.divider

// Backgrounds that used to be hard-coded in individual screens.
val BgGradTop: Color        get() = ThemePalette.current.gradTop
val BgGradBottom: Color     get() = ThemePalette.current.gradBottom
val BgPanel: Color          get() = ThemePalette.current.panel
val BgPanelDeep: Color      get() = ThemePalette.current.panelDeep
val BgInk: Color            get() = ThemePalette.current.ink
val BgInkWarm: Color        get() = ThemePalette.current.inkWarm
val BgDialog: Color         get() = ThemePalette.current.dialog
val BgCard: Color           get() = ThemePalette.current.card

/** The "premium" highlight (teal by default, the theme accent under a theme) and its deeper partner. */
val PremiumTeal: Color      get() = ThemePalette.current.premium
val PremiumTealDeep: Color  get() = ThemePalette.current.accentDeep

/** Settings screen text tones. */
val ThemeTextSoft: Color    get() = ThemePalette.current.textSoft
val ThemeTextDim: Color     get() = ThemePalette.current.textDim
