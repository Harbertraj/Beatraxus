package com.beatraxus.app.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beatraxus.app.theme.ThemeCodec
import com.beatraxus.app.theme.ThemeIconKeys
import com.beatraxus.app.theme.ThemeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material3.Icon as M3Icon

/**
 * How icons look right now, as set by the applied theme. [None] (no theme, or a theme without an
 * icons section) makes [Icon] behave exactly like the Material one.
 */
@Immutable
class IconTheme(
    /** Replaces every light (near-white) icon colour. Coloured icons (red delete, green ok) are left alone. */
    val color: Color?,
    val scale: Float,
    /** Theme-supplied images by icon key (see [ThemeIconKeys]). */
    val pack: Map<String, ImageBitmap>,
    val packTint: Boolean
) {
    fun recolor(tint: Color): Color {
        if (color == null || tint == Color.Unspecified) return tint
        return if (tint.luminance() > 0.8f) color.copy(alpha = tint.alpha) else tint
    }

    companion object {
        val None = IconTheme(color = null, scale = 1f, pack = emptyMap(), packTint = true)
    }
}

val LocalIconTheme = staticCompositionLocalOf { IconTheme.None }

/**
 * Drop-in replacement for Material 3's `Icon`. Every screen imports this one, so a theme can
 * recolour, resize or swap icons everywhere without touching the 700+ call sites.
 */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    val theme = LocalIconTheme.current
    if (theme === IconTheme.None) {
        M3Icon(imageVector, contentDescription, modifier, tint)
        return
    }
    val shown = theme.recolor(tint)
    val sized = if (theme.scale != 1f) modifier.scale(theme.scale) else modifier
    val packed = ThemeIconKeys.keyForVectorName(imageVector.name)?.let { theme.pack[it] }
    if (packed == null) {
        M3Icon(imageVector, contentDescription, sized, shown)
    } else {
        PackIcon(packed, contentDescription, sized, shown, theme.packTint)
    }
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    M3Icon(painter, contentDescription, modifier, LocalIconTheme.current.recolor(tint))
}

@Composable
fun Icon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    M3Icon(bitmap, contentDescription, modifier, LocalIconTheme.current.recolor(tint))
}

/** A theme-supplied icon image. It is 24dp unless the caller sets a size, like a Material icon. */
@Composable
fun PackIcon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier,
    tint: Color,
    tinted: Boolean
) {
    val painter = remember(bitmap) { BitmapPainter(bitmap) }
    val sized = modifier.size(24.dp)
    if (tinted) M3Icon(painter, contentDescription, sized, tint)
    else Image(painter, contentDescription, sized)
}

/**
 * Decodes the icon images of a theme from the phone (they were downloaded when the theme was
 * installed, see ThemeRepository.ensureIconPack). Anything missing, oversized or not a real image
 * is skipped, so a bad pack only means those icons stay the app's own.
 */
object IconPackLoader {
    fun dir(context: Context, themeId: String): File =
        File(File(File(context.applicationContext.filesDir, "themes"), "icons"), themeId)

    fun load(context: Context, spec: ThemeSpec, onlyKeys: Set<String>? = null): Map<String, ImageBitmap> {
        val icons = spec.icons ?: return emptyMap()
        if (icons.pack.isEmpty() || !ThemeCodec.isValidId(spec.id)) return emptyMap()
        val folder = dir(context, spec.id)
        val out = LinkedHashMap<String, ImageBitmap>()
        for ((key, file) in icons.pack) {
            if (onlyKeys != null && key !in onlyKeys) continue
            if (!ThemeCodec.isValidIconFile(file)) continue
            val f = File(folder, file)
            if (!f.isFile || f.length() > ThemeCodec.MAX_ICON_BYTES) continue
            val bmp = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                if (bounds.outWidth > ThemeCodec.MAX_ICON_PIXELS || bounds.outHeight > ThemeCodec.MAX_ICON_PIXELS) return@runCatching null
                BitmapFactory.decodeFile(f.path)?.asImageBitmap()
            }.getOrNull()
            if (bmp != null) out[key] = bmp
        }
        return out
    }
}

/**
 * The icon images of [spec], decoded off the main thread. Empty until ready, and always empty for
 * themes without an icon pack, so callers can use it without checking.
 */
@Composable
fun rememberPackIcons(spec: ThemeSpec?, onlyKeys: Set<String>? = null): State<Map<String, ImageBitmap>> {
    val context = LocalContext.current
    return produceState<Map<String, ImageBitmap>>(emptyMap(), spec?.id, spec?.version, spec?.icons?.pack, onlyKeys) {
        value = if (spec?.icons?.pack.isNullOrEmpty()) emptyMap()
        else withContext(Dispatchers.IO) { IconPackLoader.load(context, spec!!, onlyKeys) }
    }
}
