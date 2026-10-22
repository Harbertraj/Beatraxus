package com.beatraxus.app.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colours pulled from the current cover art, used ONLY by the "Full Cover Art" Now Playing mode
 * (its background, the queue and the sleep timer). Nothing here runs when that option is off.
 *
 * Every colour is already graded for use behind white text: the mesh colours are mid-dark and
 * saturated (Apple Music style), [base] is near-black, [accent] is a light tint for highlights.
 */
@Immutable
data class CoverPalette(
    val base: Color,
    val c1: Color,
    val c2: Color,
    val c3: Color,
    val c4: Color,
    val accent: Color
)

private fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))
}

private fun hslOf(argb: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }

private fun colorDistance(a: Int, b: Int): Double {
    val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
    val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return sqrt((dr * dr + dg * dg + db * db).toDouble())
}

/** Pulls a raw swatch colour into the rich-but-dark range that looks good behind white text. */
private fun gradeForMesh(argb: Int): Color {
    val hsl = hslOf(argb)
    val chromatic = hsl[1] > 0.12f
    return hslColor(
        hsl[0],
        if (chromatic) hsl[1].coerceIn(0.34f, 0.90f) else hsl[1],
        hsl[2].coerceIn(0.24f, 0.44f)
    )
}

/** Fallback when no art / no swatches: a neutral palette built around a single colour. */
internal fun coverPaletteFromSeed(seed: Color): CoverPalette {
    val hsl = hslOf(seed.toArgb())
    val s = hsl[1].coerceIn(0.05f, 0.80f)
    return CoverPalette(
        base = hslColor(hsl[0], s * 0.6f, 0.07f),
        c1 = hslColor(hsl[0], s, 0.30f),
        c2 = hslColor(hsl[0] + 26f, s, 0.26f),
        c3 = hslColor(hsl[0] - 26f, s, 0.34f),
        c4 = hslColor(hsl[0] + 52f, s, 0.24f),
        accent = hslColor(hsl[0], s.coerceAtLeast(0.30f), 0.80f)
    )
}

private fun derivePalette(p: Palette, seed: Color): CoverPalette {
    // Dominant first so the biggest blob is really "the album's colour", then the other swatches.
    val ordered = ArrayList<Int>()
    listOf(
        p.dominantSwatch, p.vibrantSwatch, p.mutedSwatch, p.darkVibrantSwatch,
        p.lightVibrantSwatch, p.darkMutedSwatch, p.lightMutedSwatch
    ).forEach { swatch -> if (swatch != null) ordered += swatch.rgb }
    p.swatches.sortedByDescending { it.population }.forEach { ordered += it.rgb }

    val picked = ArrayList<Int>(4)
    for (rgb in ordered) {
        if (picked.size >= 4) break
        if (picked.none { colorDistance(it, rgb) < 56.0 }) picked += rgb
    }
    if (picked.isEmpty()) return coverPaletteFromSeed(seed)

    // Art with fewer than four distinct colours: invent neighbours by nudging hue / lightness.
    var i = 0
    while (picked.size < 4) {
        val hsl = hslOf(picked[i % picked.size])
        hsl[0] = (hsl[0] + 24f * (picked.size)) % 360f
        hsl[2] = (hsl[2] + if (picked.size % 2 == 0) 0.12f else -0.12f).coerceIn(0f, 1f)
        picked += ColorUtils.HSLToColor(hsl)
        i++
    }

    val graded = picked.map { gradeForMesh(it) }
    val lead = hslOf(picked[0])
    val chromaticLead = lead[1] > 0.12f
    val base = hslColor(
        lead[0],
        if (chromaticLead) (lead[1] * 0.75f).coerceIn(0.25f, 0.60f) else lead[1],
        0.07f
    )
    // Accent comes from the most colourful pick, lightened for use on dark glass.
    val vivid = hslOf(picked.maxByOrNull { hslOf(it)[1] } ?: picked[0])
    val accent = hslColor(
        vivid[0],
        if (vivid[1] > 0.12f) vivid[1].coerceIn(0.40f, 0.90f) else vivid[1],
        0.80f
    )
    return CoverPalette(base, graded[0], graded[1], graded[2], graded[3], accent)
}

/**
 * Art-derived palette for [songKey], extracted off the main thread once per song and cached.
 * Colours animate between songs. The returned State is meant to be read inside draw lambdas or
 * small composables so a colour crossfade does not recompose the whole screen.
 */
@Composable
internal fun rememberCoverPalette(artUri: Any?, songKey: Any?, seed: Color): State<CoverPalette> {
    val context = LocalContext.current
    val cache = remember { mutableStateMapOf<Any, CoverPalette>() }
    val key: Any = songKey ?: "none"

    LaunchedEffect(key, artUri) {
        if (cache.containsKey(key) || artUri == null) return@LaunchedEffect
        val derived = withContext(Dispatchers.Default) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(artUri)
                    .allowHardware(false)
                    .size(128, 128)
                    .precision(Precision.INEXACT)
                    .build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    derivePalette(Palette.from(bitmap).maximumColorCount(24).generate(), seed)
                } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (derived != null) cache[key] = derived
    }

    val fallback = remember(seed) { coverPaletteFromSeed(seed) }
    val target = cache[key] ?: fallback

    val spec = tween<Color>(durationMillis = 900)
    val base = animateColorAsState(target.base, spec, label = "coverBase")
    val c1 = animateColorAsState(target.c1, spec, label = "coverC1")
    val c2 = animateColorAsState(target.c2, spec, label = "coverC2")
    val c3 = animateColorAsState(target.c3, spec, label = "coverC3")
    val c4 = animateColorAsState(target.c4, spec, label = "coverC4")
    val accent = animateColorAsState(target.accent, spec, label = "coverAccent")
    return remember {
        derivedStateOf {
            CoverPalette(base.value, c1.value, c2.value, c3.value, c4.value, accent.value)
        }
    }
}

private class MeshBlob(
    val cx: Float, val cy: Float,   // resting centre, fraction of the canvas
    val ax: Float, val ay: Float,   // drift amplitude, fraction of the canvas
    val fx: Int, val fy: Int,       // whole-number speeds, so the loop is seamless
    val px: Float, val py: Float,   // phase offsets (0..1 of a cycle)
    val radius: Float               // multiple of the canvas width
)

// c1 (the album's main colour) is the big lower-left blob; the lower half is what stays visible
// under the cover on the Now Playing screen, so the strongest colours live there.
private val MeshBlobs = listOf(
    MeshBlob(0.22f, 0.80f, 0.18f, 0.08f, 1, 1, 0.55f, 0.20f, 1.20f),
    MeshBlob(0.82f, 0.94f, 0.16f, 0.06f, 2, 2, 0.80f, 0.70f, 1.05f),
    MeshBlob(0.88f, 0.50f, 0.12f, 0.12f, 2, 1, 0.30f, 0.55f, 1.00f),
    MeshBlob(0.18f, 0.20f, 0.16f, 0.10f, 1, 2, 0.00f, 0.10f, 1.10f)
)

private val MESH_TAU = (2.0 * PI).toFloat()

/**
 * Apple Music style background: a near-black base with four large, soft blobs of the cover's
 * colours drifting slowly. Uses no image at all, only colours taken from the art.
 */
@Composable
internal fun CoverMeshBackground(palette: State<CoverPalette>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "coverMesh")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 40_000, easing = LinearEasing), RepeatMode.Restart),
        label = "coverMeshPhase"
    )
    Canvas(modifier) {
        // State reads happen here, in the draw phase: the drift only redraws, never recomposes.
        val p = palette.value
        val t = phase.value * MESH_TAU
        val w = size.width
        val h = size.height
        drawRect(p.base)
        val colors = listOf(p.c1, p.c2, p.c3, p.c4)
        // Paint back to front so c1 (the main colour) ends up on top.
        for (i in MeshBlobs.indices.reversed()) {
            val b = MeshBlobs[i]
            val color = colors[i]
            val center = Offset(
                x = w * (b.cx + b.ax * sin(t * b.fx + b.px * MESH_TAU)),
                y = h * (b.cy + b.ay * cos(t * b.fy + b.py * MESH_TAU))
            )
            val radius = max(w * b.radius, h * 0.45f)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = 0.95f),
                    0.55f to color.copy(alpha = 0.50f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}
