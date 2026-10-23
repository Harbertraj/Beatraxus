package com.beatraxus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Size
import coil.transform.Transformation
import com.beatraxus.app.ui.utils.FastBlurTransformation
import com.beatraxus.app.utils.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Full Cover Art helpers: "HD cover" + "never cut the cover" layout.
 *
 * Why the old layout looked bad:
 *  - a square cover was cropped into a taller box (ContentScale.Crop), so ~18% of the width
 *    (text / faces near the left and right edges) was thrown away;
 *  - the whole cover's bottom 48% was alpha-faded, so text / faces in the lower part were lost;
 *  - the cover came from the 512px, JPEG-70 library cache and was then stretched to ~1080px+.
 *
 * What this file does instead:
 *  1. The cover is fitted to the screen WIDTH (nothing at the sides is cut). The rest of the box is
 *     filled with a blurred, dimmed copy of the same cover, and the sharp cover only feathers out
 *     at its own bottom edge.
 *  2. Non-square covers: a small vertical crop (<= 12%) is allowed and is steered by face detection
 *     so faces stay in view; anything bigger is shown whole ("fit inside") on the blurred backdrop.
 *  3. HD: the original embedded picture is re-read from the audio file (up to 2048px, no extra
 *     recompression) and the bitmap is upscaled + unsharp-masked when it is smaller than the screen.
 */

@Immutable
data class CoverAnalysis(
    /** width / height of the cover. */
    val aspect: Float,
    /** Top of the detected face area as a fraction (0..1) of the cover height, or null. */
    val faceTop: Float?,
    /** Bottom of the detected face area as a fraction (0..1) of the cover height, or null. */
    val faceBottom: Float?
)

private val analysisCache = LruCache<String, CoverAnalysis>(48)

private enum class CoverFit { FIT_WIDTH, CROP, FIT_INSIDE }

/**
 * Full Cover Art with [reachBottom]: a cover that is fitted to the width but leaves up to this share of the
 * box empty is scaled up to fill the box height (small side crop) so it touches the quality badge.
 */
private const val REACH_BOTTOM_MAX_GAP = 0.22f

// ---------------------------------------------------------------------------------------------
// Analysis (aspect ratio + faces)
// ---------------------------------------------------------------------------------------------

internal suspend fun analyzeCover(context: Context, data: Any?): CoverAnalysis = withContext(Dispatchers.IO) {
    val fallback = CoverAnalysis(1f, null, null)
    try {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(320)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
        val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@withContext fallback
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext fallback
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val (top, bottom) = detectFaces(bitmap)
        CoverAnalysis(aspect, top, bottom)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        fallback
    }
}

/** Returns the vertical extent (fractions of height) of the detected faces, or (null, null). */
private fun detectFaces(src: Bitmap): Pair<Float?, Float?> {
    return try {
        var w = min(src.width, 320)
        val scale = w.toFloat() / src.width
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        if (w % 2 != 0) w -= 1 // FaceDetector needs an even width
        if (w < 48 || h < 48) return null to null

        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val rgb = scaled.copy(Bitmap.Config.RGB_565, false) ?: return null to null
        val height = rgb.height.toFloat()

        val maxFaces = 4
        val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
        val count = FaceDetector(rgb.width, rgb.height, maxFaces).findFaces(rgb, faces)

        var top = Float.MAX_VALUE
        var bottom = -1f
        val mid = PointF()
        for (i in 0 until count) {
            val face = faces[i] ?: continue
            if (face.confidence() < 0.45f) continue
            face.getMidPoint(mid)
            val eyes = face.eyesDistance()
            top = min(top, mid.y - eyes * 1.8f)   // forehead / hair
            bottom = max(bottom, mid.y + eyes * 2.2f) // chin
        }
        rgb.recycle()

        if (bottom < 0f) null to null
        else (top / height).coerceIn(0f, 1f) to (bottom / height).coerceIn(0f, 1f)
    } catch (e: Throwable) {
        null to null
    }
}

// ---------------------------------------------------------------------------------------------
// HD source: original embedded picture instead of the 512px library cache
// ---------------------------------------------------------------------------------------------

private const val HD_MAX_EDGE = 2048

/**
 * The original embedded cover of [audioUri] (cached in cacheDir/hd_cover), or [fallback] when the
 * file has no embedded picture / is not a local file / anything fails.
 */
internal suspend fun resolveHdCover(context: Context, songId: String, audioUri: Uri?, fallback: Any?): Any? =
    withContext(Dispatchers.IO) { hdCoverBlocking(context, songId, audioUri) ?: fallback }

private fun hdCoverBlocking(context: Context, songId: String, audioUri: Uri?): Uri? {
    if (audioUri == null) return null
    val scheme = audioUri.scheme ?: return null
    if (scheme != "content" && scheme != "file") return null // never probe network streams here

    val dir = File(context.cacheDir, "hd_cover").apply { mkdirs() }
    val file = File(dir, songId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg")
    if (file.exists() && file.length() > 0L) return Uri.fromFile(file)

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, audioUri)
        val bytes = retriever.embeddedPicture ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= HD_MAX_EDGE) sample *= 2

        if (sample == 1) {
            // Already a sensible size: keep the original bytes (no extra JPEG generation loss).
            file.writeBytes(bytes)
        } else {
            val bitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
        }
        if (file.length() > 0L) Uri.fromFile(file) else null
    } catch (e: Throwable) {
        runCatching { file.delete() }
        null
    } finally {
        runCatching { retriever.release() }
    }
}

// ---------------------------------------------------------------------------------------------
// HD enhance: progressive upscale + unsharp mask
// ---------------------------------------------------------------------------------------------

/**
 * Upscales covers narrower than [minWidthPx] in clean 2x steps (then a final fit), and applies a mild
 * unsharp mask. This makes low-resolution art noticeably crisper; it cannot invent detail that the
 * file does not contain.
 */
internal class HdCoverTransformation(private val minWidthPx: Int) : Transformation {

    override val cacheKey: String = "HdCoverTransformation-v1-$minWidthPx"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        var bmp = if (input.config == Bitmap.Config.ARGB_8888) input
        else input.copy(Bitmap.Config.ARGB_8888, true) ?: return input

        var upscaled = false
        if (bmp.width in 1 until minWidthPx) {
            val ratio = bmp.height.toFloat() / bmp.width
            var cur = bmp
            while (cur.width * 2 <= minWidthPx) {
                cur = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
            }
            if (cur.width < minWidthPx) {
                cur = Bitmap.createScaledBitmap(
                    cur, minWidthPx, (minWidthPx * ratio).roundToInt().coerceAtLeast(1), true
                )
            }
            bmp = cur
            upscaled = true
        }
        return unsharp(bmp, if (upscaled) 0.70f else 0.35f)
    }

    /** out = src + amount * (src - boxBlur3x3(src)) */
    private fun unsharp(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return src

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            val y0 = if (y > 0) y - 1 else y
            val y1 = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val x0 = if (x > 0) x - 1 else x
                val x1 = if (x < w - 1) x + 1 else x
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (yy in y0..y1) {
                    val row = yy * w
                    for (xx in x0..x1) {
                        val p = px[row + xx]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        n++
                    }
                }
                val c = px[y * w + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val nr = (cr + amount * (cr - sr / n.toFloat())).roundToInt().coerceIn(0, 255)
                val ng = (cg + amount * (cg - sg / n.toFloat())).roundToInt().coerceIn(0, 255)
                val nb = (cb + amount * (cb - sb / n.toFloat())).roundToInt().coerceIn(0, 255)
                out[y * w + x] = (c ushr 24 shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }
}

// ---------------------------------------------------------------------------------------------
// The cover layer itself
// ---------------------------------------------------------------------------------------------

/** Eased bottom fade, same curve as before, starting at [fadeStart] (fraction of the box height). */
private fun coverFadeBrush(fadeStart: Float): Brush {
    fun p(t: Float) = fadeStart + (1f - fadeStart) * t
    return Brush.verticalGradient(
        0f to Color.Black,
        p(0f) to Color.Black,
        p(0.25f) to Color.Black.copy(alpha = 0.90f),
        p(0.50f) to Color.Black.copy(alpha = 0.64f),
        p(0.73f) to Color.Black.copy(alpha = 0.30f),
        p(0.90f) to Color.Black.copy(alpha = 0.08f),
        1f to Color.Transparent
    )
}

/**
 * One cover (used inside the song-change AnimatedContent of the Full Cover Art background).
 * Fills its parent; the parent decides the box size.
 */
@Composable
internal fun FullCoverLayer(
    uri: Any?,
    songId: Any?,
    audioUri: Uri?,
    hdEnabled: Boolean,
    isBlurred: Boolean,
    strongBlur: Boolean,
    blurRadius: Dp,
    blurDim: Float,
    /** True when the parent box ends at the quality badge: the cover must reach that edge. */
    reachBottom: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // Aspect ratio + faces (cheap 320px decode, remembered per cover).
        val cacheKey = uri?.toString()
        val analysis by produceState<CoverAnalysis?>(
            initialValue = cacheKey?.let { analysisCache.get(it) },
            key1 = cacheKey
        ) {
            if (value == null) {
                val result = analyzeCover(context, uri)
                if (cacheKey != null) analysisCache.put(cacheKey, result)
                value = result
            }
        }
        val shown by animateFloatAsState(
            targetValue = if (analysis != null) 1f else 0f,
            animationSpec = tween(160),
            label = "coverReady"
        )
        val info = analysis ?: CoverAnalysis(1f, null, null)

        // Source: original embedded picture when HD is on (falls back to the library cache art).
        val displayUri by produceState<Any?>(uri, uri, songId, audioUri, hdEnabled) {
            value = uri
            if (hdEnabled) value = resolveHdCover(context, songId.toString(), audioUri, uri)
        }

        // ---- layout decision --------------------------------------------------------------
        val aspect = info.aspect.coerceIn(0.3f, 4f)
        val fitH = wPx / aspect // height when the cover is fitted to the full width
        val fit = when {
            fitH <= hPx ->
                if (reachBottom && (hPx - fitH) / hPx <= REACH_BOTTOM_MAX_GAP) CoverFit.CROP else CoverFit.FIT_WIDTH
            (fitH - hPx) / fitH <= 0.12f -> CoverFit.CROP
            else -> CoverFit.FIT_INSIDE
        }
        val artBottomFrac = if (fit == CoverFit.FIT_WIDTH) (fitH / hPx).coerceAtMost(1f) else 1f
        // reachBottom: keep the cover solid almost down to the badge, only its last 10% feathers out.
        val fadeStart = when {
            artBottomFrac >= 0.999f -> if (reachBottom) 0.90f else 0.52f
            else -> (artBottomFrac - 0.12f).coerceIn(0.52f, 0.80f)
        }

        // Vertical focus for the (small) crop: keep faces in view, otherwise show the top.
        val cropBias = run {
            val extra = fitH - hPx
            if (fit != CoverFit.CROP || extra <= 0f) -1f
            else {
                val faceTop = info.faceTop
                val offset = if (faceTop != null) (faceTop * fitH - 0.08f * hPx).coerceIn(0f, extra) else 0f
                (offset / extra) * 2f - 1f
            }
        }

        // A cover scaled up to the box height (reachBottom + CROP) is decoded at that height, so it stays sharp.
        val fillsHeight = fit == CoverFit.FIT_INSIDE || (fit == CoverFit.CROP && fitH < hPx)
        val decodeW = (if (fillsHeight) hPx * aspect else wPx).roundToInt().coerceAtLeast(1)
        val decodeH = (if (fillsHeight) hPx else fitH).roundToInt().coerceAtLeast(1)
        val hdTransform = hdEnabled && !isBlurred
        val sharpRequest = remember(displayUri, decodeW, decodeH, hdTransform, isBlurred, strongBlur) {
            ImageRequest.Builder(context)
                .data(displayUri)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .size(Size(decodeW, decodeH))
                .precision(Precision.INEXACT)
                .crossfade(true)
                .error(ImageUtils.getDefaultAlbumArtRes())
                .fallback(ImageUtils.getDefaultAlbumArtRes())
                .apply {
                    val list = ArrayList<Transformation>(2)
                    if (hdTransform) list += HdCoverTransformation(decodeW.coerceAtMost(HD_MAX_EDGE))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isBlurred) {
                        list += FastBlurTransformation(if (strongBlur) 90f else 25f)
                    }
                    if (list.isNotEmpty()) transformations(list)
                }
                .build()
        }
        val backdropRequest = remember(uri) {
            ImageRequest.Builder(context)
                .data(uri)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .size(256, 256)
                .precision(Precision.INEXACT)
                .apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        transformations(FastBlurTransformation(60f))
                    }
                }
                .build()
        }

        Box(
            Modifier
                .fillMaxSize()
                .alpha(shown)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = coverFadeBrush(fadeStart), blendMode = BlendMode.DstIn)
                }
        ) {
            // Blur (lyrics / queue / sheets) applies to backdrop + sharp cover together.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0.dp) {
                            // Rectangle (not Unbounded) so the edges don't fade out at the screen sides.
                            Modifier.blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                        } else Modifier
                    )
            ) {
                // Blurred copy of the same cover that fills whatever the sharp cover doesn't reach.
                if (fit != CoverFit.CROP) {
                    AsyncImage(
                        model = backdropRequest,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Modifier.blur(radius = 48.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                                } else Modifier
                            ),
                        contentScale = ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
                }

                when (fit) {
                    CoverFit.FIT_WIDTH -> {
                        // Full width, nothing cut at the sides; only its own bottom edge feathers out.
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(with(density) { fitH.toDp() })
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0f to Color.Black,
                                            0.90f to Color.Black,
                                            1f to Color.Transparent
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                        ) {
                            AsyncImage(
                                model = sharpRequest,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop, // box already has the cover's aspect
                                filterQuality = FilterQuality.High
                            )
                        }
                    }

                    CoverFit.CROP -> AsyncImage(
                        model = sharpRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(0f, cropBias),
                        filterQuality = FilterQuality.High
                    )

                    CoverFit.FIT_INSIDE -> AsyncImage(
                        model = sharpRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopCenter,
                        filterQuality = FilterQuality.High
                    )
                }
            }

            if (blurDim > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blurDim)))
            }
            // Light scrim under the status bar / title so the white icons stay readable.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.22f to Color.Transparent
                    )
                )
            )
        }
    }
}
