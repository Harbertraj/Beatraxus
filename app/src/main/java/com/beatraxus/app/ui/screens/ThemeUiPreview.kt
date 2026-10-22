package com.beatraxus.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.theme.ThemeIconKeys
import com.beatraxus.app.theme.ThemeSpec
import com.beatraxus.app.theme.ThemeSurface
import com.beatraxus.app.theme.backgroundFor
import com.beatraxus.app.ui.theme.FontRepository
import com.beatraxus.app.ui.theme.rememberPackIcons
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.material3.Icon as M3Icon

/**
 * A small picture of the app drawn with a theme's own settings: its colours, font, seekbar style, DSP palette,
 * backgrounds, corner roundness, mini player shape, quality badge and icons. This is what the gallery shows
 * instead of plain colour stripes, so a theme can be judged before it is applied.
 *
 * It is drawn here with the theme's numbers (not by switching the live app), so it never changes the
 * user's current look and works for themes that are not installed yet.
 */
@Composable
fun ThemeUiPreview(spec: ThemeSpec, modifier: Modifier = Modifier) {
    val packIcons by rememberPackIcons(spec, ThemeIconKeys.previewKeys)
    val look = remember(spec, packIcons) { PreviewLook(spec, packIcons) }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PreviewFrame(look, ThemeSurface.HOME, Modifier.weight(1f)) { HomeMock(look) }
        PreviewFrame(look, ThemeSurface.NOW_PLAYING, Modifier.weight(1f)) { NowPlayingMock(look) }
    }
}

/** Everything the mock screens read from a theme, worked out once. */
private class PreviewLook(val spec: ThemeSpec, val icons: Map<String, ImageBitmap>) {
    val accent = Color(spec.accent)
    val accent2 = Color(spec.accentSecondary)
    val dsp1 = Color(spec.dspPrimary)
    val dsp2 = Color(spec.dspSecondary)
    val text = spec.surfaces.text?.let { Color(it) } ?: Color(0xFFF0F0FF)
    val subText = text.copy(alpha = 0.62f)
    val card = spec.surfaces.surface?.let { Color(it) } ?: Color.White.copy(alpha = 0.08f)
    val font: FontFamily = FontRepository.getFontFamily(spec.fontId)
    /** Material shape corners scale with the theme's cornerScale. */
    val cornerScale = spec.shape?.cornerScale ?: 1f
    val iconColor: Color? = spec.icons?.color?.let { Color(it) }
    val iconScale = spec.icons?.scale ?: 1f
    val packTint = spec.icons?.packTint ?: true

    fun label(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = text) =
        TextStyle(fontFamily = font, fontSize = size.sp, fontWeight = weight, color = color)
}

@Composable
private fun PreviewFrame(look: PreviewLook, surface: ThemeSurface, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .aspectRatio(0.52f)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), RoundedCornerShape(16.dp))
    ) {
        Backdrop(look, surface, Modifier.fillMaxSize())
        content()
    }
}

/** The background of a surface: black, a tinted solid, or a soft blurred-colour wash, with its darkness. */
@Composable
private fun Backdrop(look: PreviewLook, surface: ThemeSurface, modifier: Modifier) {
    val bg = look.spec.backgroundFor(surface)
    val base = look.spec.surfaces.background?.let { Color(it) } ?: Color(0xFF0B0B14)
    Box(modifier) {
        when (bg.mode) {
            NowPlayingBackgroundMode.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
            NowPlayingBackgroundMode.SOLID -> {
                val tint = lerp(base, look.accent, (bg.solidIntensity * 0.5f).coerceIn(0f, 1f))
                Box(Modifier.fillMaxSize().background(lerp(tint, Color.Black, bg.solidDarkness.coerceIn(0f, 1f))))
            }
            NowPlayingBackgroundMode.BLUR -> {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(look.accent.copy(alpha = 0.75f), look.accent2.copy(alpha = 0.55f), base)
                        )
                    )
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (0.25f + bg.blurDarkness * 0.65f).coerceIn(0f, 0.92f))))
            }
        }
    }
}

// ------------------------------------------------------------------ home screen

@Composable
private fun HomeMock(look: PreviewLook) {
    val radius = (9f * look.cornerScale).dp
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text("Good evening", style = look.label(10f, FontWeight.Bold))
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("Songs", true, look)
            Chip("Albums", false, look)
            Chip("Artists", false, look)
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(2) { i ->
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(radius))
                        .background(
                            if (i == 0) Brush.linearGradient(listOf(look.accent, look.accent2))
                            else Brush.linearGradient(listOf(look.dsp2, look.dsp1))
                        )
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text("Made for you", style = look.label(8f, FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        repeat(2) { i ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(radius)).background(look.card).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape((4f * look.cornerScale).dp))
                        .background(if (i == 0) look.accent else look.accent2)
                )
                Spacer(Modifier.width(5.dp))
                Column {
                    Bar(34.dp, look.text.copy(alpha = 0.8f))
                    Spacer(Modifier.height(2.dp))
                    Bar(22.dp, look.subText)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        MiniPlayerMock(look)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavIcon("home", Icons.Rounded.Home, true, look)
            NavIcon("search", Icons.Rounded.Search, false, look)
            NavIcon("library", Icons.Rounded.LibraryMusic, false, look)
            NavIcon("settings", Icons.Rounded.Settings, false, look)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, look: PreviewLook) {
    Box(
        Modifier.clip(RoundedCornerShape((8f * look.cornerScale).dp))
            .background(if (selected) look.accent else Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        val onAccent = if (look.accent.luminance() > 0.5f) Color.Black else Color.White
        Text(label, style = look.label(7f, FontWeight.Medium, if (selected) onAccent else look.text))
    }
}

@Composable
private fun Bar(width: Dp, color: Color) {
    Box(Modifier.width(width).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

@Composable
private fun NavIcon(key: String, fallback: ImageVector, selected: Boolean, look: PreviewLook) {
    PreviewIcon(key, fallback, if (selected) look.accent else look.subText, look, 14.dp)
}

// ------------------------------------------------------------------ now playing screen

@Composable
private fun NowPlayingMock(look: PreviewLook) {
    val player = look.spec.player
    Column(
        Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Now Playing", style = look.label(7f, color = look.subText))
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth(0.78f).aspectRatio(1f).clip(RoundedCornerShape((12f * look.cornerScale).dp))
                .background(Brush.linearGradient(listOf(look.accent, look.accent2))),
            contentAlignment = Alignment.Center
        ) {
            M3Icon(Icons.Rounded.MusicNote, null, Modifier.size(28.dp), Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(5.dp))
        QualityBadgeMock(player?.qualityBadge ?: "GLASSMORPHIC", look)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Song title", style = look.label(9f, FontWeight.Bold), maxLines = 1)
                Text("Artist name", style = look.label(7f, color = look.subText), maxLines = 1)
            }
            PreviewIcon("favorite", Icons.Rounded.Favorite, look.accent, look, 12.dp)
        }
        Spacer(Modifier.height(5.dp))
        SeekbarMock(look.spec.seekbarStyle, look.accent, look.accent2, Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreviewIcon("shuffle", Icons.Rounded.Shuffle, look.subText, look, 12.dp)
            PreviewIcon("previous", Icons.Rounded.SkipPrevious, look.text, look, 15.dp)
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(look.accent),
                contentAlignment = Alignment.Center
            ) {
                val onAccent = if (look.accent.luminance() > 0.5f) Color.Black else Color.White
                PreviewIcon("pause", Icons.Rounded.Pause, onAccent, look, 14.dp)
            }
            PreviewIcon("next", Icons.Rounded.SkipNext, look.text, look, 15.dp)
            PreviewIcon("play", Icons.Rounded.PlayArrow, look.subText, look, 12.dp)
        }
        Spacer(Modifier.weight(1f))
        // The Studio DSP palette, drawn as a few level bars.
        Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            listOf(0.5f, 0.85f, 0.65f, 1f, 0.7f, 0.45f, 0.8f).forEachIndexed { i, h ->
                Box(
                    Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(2.dp))
                        .background(Brush.verticalGradient(listOf(look.dsp1, look.dsp2.copy(alpha = if (i % 2 == 0) 0.9f else 0.6f))))
                )
            }
        }
    }
}

@Composable
private fun QualityBadgeMock(style: String, look: PreviewLook) {
    if (style == "NONE") return
    val shape = RoundedCornerShape(6.dp)
    val modifier = when (style) {
        "GOLDEN_SHIMMER" -> Modifier.background(Brush.horizontalGradient(listOf(Color(0xFFB8860B), Color(0xFFFFE08A), Color(0xFFB8860B))), shape)
        "MINIMAL_OUTLINE" -> Modifier.border(BorderStroke(1.dp, look.subText), shape)
        "NEON_PULSE" -> Modifier.border(BorderStroke(1.dp, look.accent), shape).background(look.accent.copy(alpha = 0.15f), shape)
        else -> Modifier.background(Color.White.copy(alpha = 0.14f), shape).border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)), shape)
    }
    val color = when (style) {
        "GOLDEN_SHIMMER" -> Color(0xFF3A2A00)
        "NEON_PULSE" -> look.accent
        else -> look.text
    }
    Box(modifier.padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text("FLAC 24/96", style = look.label(6.5f, FontWeight.SemiBold, color))
    }
}

// ------------------------------------------------------------------ mini player

@Composable
private fun MiniPlayerMock(look: PreviewLook) {
    val p = look.spec.player
    val classic = p?.miniStyle == "CLASSIC"
    // The real bar is 56-120dp tall; the preview draws it at about 0.45 scale.
    val s = 0.45f
    val height = ((p?.miniHeight ?: 64f) * s).dp
    val corner = if (classic) 0.dp else ((p?.miniCorner ?: 36f) * s).dp
    val artCorner = ((p?.miniArtCorner ?: 18f) * s).dp
    val ring = p?.miniProgressRing ?: true
    val time = p?.miniShowTime ?: true
    val shape = RoundedCornerShape(corner)
    Box(Modifier.fillMaxWidth().height(height).clip(shape).border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)), shape)) {
        Backdrop(look, ThemeSurface.MINI_PLAYER, Modifier.fillMaxSize())
        Row(
            Modifier.fillMaxSize().padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size((height.value * 0.68f).dp).clip(RoundedCornerShape(artCorner))
                    .background(Brush.linearGradient(listOf(look.accent, look.accent2)))
            )
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Bar(30.dp, look.text.copy(alpha = 0.85f))
                Spacer(Modifier.height(2.dp))
                Bar(20.dp, look.subText)
            }
            if (time) {
                Text("1:24", style = look.label(6f, color = look.subText))
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (ring) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(1.5.dp.toPx()))
                        drawArc(look.accent, -90f, 130f, false, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                PreviewIcon("play", Icons.Rounded.PlayArrow, look.text, look, 9.dp)
            }
        }
    }
}

// ------------------------------------------------------------------ seekbar

/** A rough picture of each seekbar style, in the theme's two accent colours. */
@Composable
private fun SeekbarMock(style: String, c1: Color, c2: Color, modifier: Modifier) {
    Canvas(modifier) {
        val progress = 0.42f
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val px = w * progress
        val track = Color.White.copy(alpha = 0.18f)
        val stroke = 2.dp.toPx()
        when (style) {
            "BEATRAXUS_THREAD" -> {
                drawLine(track, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
                drawLine(c1, Offset(0f, mid), Offset(px, mid), 1.5.dp.toPx(), StrokeCap.Round)
                drawCircle(c1, 3.dp.toPx(), Offset(px, mid))
            }
            "BEATRAXUS_PRISM" -> {
                drawRoundRect(track, Offset(0f, mid - stroke), Size(w, stroke * 2), androidx.compose.ui.geometry.CornerRadius(stroke))
                drawRoundRect(
                    Brush.horizontalGradient(listOf(c1, c2), 0f, px.coerceAtLeast(1f)),
                    Offset(0f, mid - stroke), Size(px, stroke * 2), androidx.compose.ui.geometry.CornerRadius(stroke)
                )
                drawCircle(Color.White, 3.dp.toPx(), Offset(px, mid))
            }
            "BEATRAXUS_MATRIX" -> {
                val dot = 2.dp.toPx()
                val gap = 5.dp.toPx()
                var x = dot
                while (x < w) {
                    drawCircle(if (x <= px) lerp(c1, c2, x / w) else track, dot / 1.5f, Offset(x, mid))
                    x += gap
                }
            }
            "BEATRAXUS_COMET" -> {
                drawLine(track, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, c1, c2), 0f, px.coerceAtLeast(1f)),
                    Offset(0f, mid), Offset(px, mid), 2.5.dp.toPx(), StrokeCap.Round
                )
                drawCircle(c2.copy(alpha = 0.35f), 5.dp.toPx(), Offset(px, mid))
                drawCircle(Color.White, 2.5.dp.toPx(), Offset(px, mid))
            }
            "WAVEFORM", "SPECTRUM_TIMELINE" -> {
                val bar = 2.dp.toPx()
                val gap = 3.5.dp.toPx()
                var x = bar
                var i = 0
                while (x < w) {
                    val amp = if (style == "WAVEFORM") 0.35f + 0.65f * abs(sin(i * 0.9f)) else 0.25f + 0.75f * abs(sin(i * 0.5f) * sin(i * 0.23f + 1f))
                    val bh = h * amp
                    drawLine(if (x <= px) c1 else track, Offset(x, mid - bh / 2), Offset(x, mid + bh / 2), bar, StrokeCap.Round)
                    x += gap; i++
                }
            }
            "LIVE_WAVEFORM" -> wave(c1, c2, track, px, w, mid, h)
            else -> { // BEATRAXUS_PULSE
                drawLine(track, Offset(0f, mid), Offset(w, mid), stroke, StrokeCap.Round)
                drawLine(c1, Offset(0f, mid), Offset(px, mid), stroke, StrokeCap.Round)
                drawCircle(c1.copy(alpha = 0.3f), 5.dp.toPx(), Offset(px, mid))
                drawCircle(c1, 3.dp.toPx(), Offset(px, mid))
            }
        }
    }
}

private fun DrawScope.wave(c1: Color, c2: Color, track: Color, px: Float, w: Float, mid: Float, h: Float) {
    var x = 0f
    var prevY = mid
    val step = 2.dp.toPx()
    while (x < w) {
        val y = mid + sin(x / w * 6f * PI.toFloat()) * h * 0.32f
        if (x > 0f) drawLine(if (x <= px) lerp(c1, c2, x / w) else track, Offset(x - step, prevY), Offset(x, y), 1.6.dp.toPx(), StrokeCap.Round)
        prevY = y
        x += step
    }
}

// ------------------------------------------------------------------ icons

/**
 * One icon of the mock UI, drawn the way the theme says: its own image for that key when the theme brings
 * one, otherwise the app's own icon, recoloured and resized by the theme's icon settings.
 */
@Composable
private fun PreviewIcon(key: String, fallback: ImageVector, tint: Color, look: PreviewLook, size: Dp) {
    val shown = look.iconColor?.takeIf { tint.luminance() > 0.45f }?.copy(alpha = tint.alpha) ?: tint
    val modifier = Modifier.size(size * look.iconScale)
    val packed = look.icons[key]
    if (packed != null) {
        val painter = remember(packed) { BitmapPainter(packed) }
        if (look.packTint) M3Icon(painter, null, modifier, shown) else Image(painter, null, modifier)
    } else {
        M3Icon(fallback, null, modifier, shown)
    }
}
