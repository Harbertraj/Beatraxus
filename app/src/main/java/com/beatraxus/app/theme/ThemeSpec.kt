package com.beatraxus.app.theme

import com.beatraxus.app.model.NowPlayingBackgroundMode

/**
 * A theme is a bundle of the appearance settings the app already has. It is data only: colours,
 * a DSP palette, a seekbar style, a font and background/blur settings. Never code.
 *
 * Colours are ARGB ints. Everything here has already been validated and clamped by [ThemeCodec],
 * so it is always safe to hand to the settings store.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    /** "dark" or "light" in the file. The app currently only renders dark surfaces, see [ThemeCodec]. */
    val isDark: Boolean,
    /** Main accent. Feeds the Material colour scheme (switches, sliders, progress, fields). */
    val accent: Int,
    val accentSecondary: Int,
    /** 2 to 5 swatches for the gallery card, so no preview images are needed. */
    val preview: List<Int>,
    /** A preset id from DspPalette.presets, or DspPalette.CUSTOM_ID to use [dspPrimary]/[dspSecondary]. */
    val dspPaletteId: String,
    val dspPrimary: Int,
    val dspSecondary: Int,
    /** A com.beatraxus.app.model.SeekbarStyle name. */
    val seekbarStyle: String,
    /** A FontRepository id. */
    val fontId: String,
    val background: ThemeBackground,
    /** Optional extras. Null (or all-null) means "the theme says nothing about it", so the user's own setting stays. */
    val surfaces: ThemeSurfaces = ThemeSurfaces(),
    val shape: ThemeShape? = null,
    val player: ThemePlayer? = null,
    val surround: ThemeSurround? = null,
    val icons: ThemeIcons? = null
)

/** Optional colours for Material components (dialogs, sheets, cards, menus, text fields). ARGB or null. */
data class ThemeSurfaces(
    val background: Int? = null,
    val surface: Int? = null,
    val text: Int? = null
) {
    val isEmpty: Boolean get() = background == null && surface == null && text == null
}

/** Corner roundness of Material shapes (cards, dialogs, buttons, chips). 1.0 = the app's default. */
data class ThemeShape(val cornerScale: Float)

/** Player look: album art animation, quality badge and the mini player. Any field may be null. */
data class ThemePlayer(
    /** A com.beatraxus.app.model.AlbumArtTransform name. */
    val albumArt: String? = null,
    /** A com.beatraxus.app.model.QualityBadgeStyle name. */
    val qualityBadge: String? = null,
    /** A com.beatraxus.app.model.MiniPlayerStyle name. */
    val miniStyle: String? = null,
    val miniHeight: Float? = null,
    val miniCorner: Float? = null,
    val miniArtCorner: Float? = null,
    val miniProgressRing: Boolean? = null,
    val miniShowTime: Boolean? = null,
    val miniTintFromArt: Boolean? = null
)

/** Studio DSP > Surround screen look. Any field may be null. */
data class ThemeSurround(
    /** dark | studio | living */
    val room: String? = null,
    /** wave | spectrum | circle */
    val visualizer: String? = null,
    val visualizerEnabled: Boolean? = null,
    val speakerIcons: Boolean? = null,
    val labels: Boolean? = null,
    val beams: Boolean? = null,
    val animated: Boolean? = null,
    val arcs: Boolean? = null,
    val screen: Boolean? = null
)

/**
 * Icon look. [color] recolours every light (near-white) icon, so red/green status icons keep their
 * meaning. [scale] resizes all icons. [pack] maps an icon key (see [ThemeIconKeys]) to a PNG/WebP
 * file name in the theme repo's `icons/<theme id>/` folder; those images replace the app's own icon
 * for that key, which is how a theme can bring icons the app does not have built in.
 */
data class ThemeIcons(
    val color: Int? = null,
    val scale: Float = 1f,
    val pack: Map<String, String> = emptyMap(),
    /** True = pack images are single-colour glyphs that the app recolours; false = drawn as they are. */
    val packTint: Boolean = true
)

/**
 * Icon keys a theme can replace, and which of the app's icons each one stands for. Keys are the
 * only names a theme file may use, so a theme can never touch anything else.
 */
object ThemeIconKeys {
    val vectorNames: Map<String, List<String>> = linkedMapOf(
        "play" to listOf("PlayArrow", "PlayCircle", "PlayCircleFilled", "PlayCircleOutline"),
        "pause" to listOf("Pause", "PauseCircle", "PauseCircleFilled", "PauseCircleOutline"),
        "next" to listOf("SkipNext"),
        "previous" to listOf("SkipPrevious"),
        "shuffle" to listOf("Shuffle"),
        "repeat" to listOf("Repeat"),
        "repeat_one" to listOf("RepeatOne"),
        "favorite" to listOf("Favorite"),
        "favorite_border" to listOf("FavoriteBorder"),
        "home" to listOf("Home"),
        "search" to listOf("Search"),
        "settings" to listOf("Settings"),
        "queue" to listOf("QueueMusic"),
        "more" to listOf("MoreVert", "MoreHoriz"),
        "back" to listOf("ArrowBack"),
        "add" to listOf("Add"),
        "close" to listOf("Close"),
        "equalizer" to listOf("Equalizer", "GraphicEq"),
        "library" to listOf("LibraryMusic"),
        "playlist" to listOf("PlaylistPlay", "PlaylistAdd"),
        "album" to listOf("Album"),
        "artist" to listOf("Person"),
        "folder" to listOf("Folder"),
        "share" to listOf("Share"),
        "download" to listOf("Download", "FileDownload"),
        "volume" to listOf("VolumeUp"),
        "lyrics" to listOf("Lyrics", "Subtitles")
    )

    val keys: Set<String> get() = vectorNames.keys

    private val byVector: Map<String, String> =
        vectorNames.flatMap { (key, names) -> names.map { it to key } }.toMap()

    /** "Rounded.PlayArrow" or "AutoMirrored.Rounded.SkipNext" -> "play" / "next", or null if no key. */
    fun keyForVectorName(name: String): String? = byVector[name.substringAfterLast('.')]

    /** Keys the gallery preview draws, so only these few images are downloaded for a preview. */
    val previewKeys: Set<String> = setOf("play", "pause", "next", "previous", "shuffle", "home", "search", "library", "settings", "favorite")
}

/**
 * Background look shared by every surface (main, home, settings, now playing, mini player).
 * [blurScale] multiplies each surface's own default blur, so the surfaces keep their relative
 * look instead of all getting the same blur radius.
 */
data class ThemeBackground(
    val mode: NowPlayingBackgroundMode,
    val blurScale: Float,
    val blurDarkness: Float,
    val solidIntensity: Float,
    val solidDarkness: Float
)

/** The surfaces that have their own background settings, with the app's default blur for each. */
enum class ThemeSurface(val defaultBlur: Float) {
    NOW_PLAYING(210f),
    MAIN(120f),
    HOME(120f),
    SETTINGS(120f),
    MINI_PLAYER(70f)
}

/** The concrete numbers a theme resolves to for one surface. */
data class SurfaceBackground(
    val mode: NowPlayingBackgroundMode,
    val blurIntensity: Float,
    val blurDarkness: Float,
    val solidIntensity: Float,
    val solidDarkness: Float
)

fun ThemeSpec.backgroundFor(surface: ThemeSurface): SurfaceBackground = SurfaceBackground(
    mode = background.mode,
    // The settings slider runs from 10 to 250.
    blurIntensity = (surface.defaultBlur * background.blurScale).coerceIn(10f, 250f),
    blurDarkness = background.blurDarkness,
    solidIntensity = background.solidIntensity,
    solidDarkness = background.solidDarkness
)

/** One row of the store's index.json. */
data class ThemeIndexEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    /** Path of the theme file relative to the repo root, e.g. "themes/neon-night.json". */
    val file: String,
    val isDark: Boolean,
    val preview: List<Int>
)
