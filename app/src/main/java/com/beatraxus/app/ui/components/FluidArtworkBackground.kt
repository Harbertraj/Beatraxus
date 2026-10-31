package com.beatraxus.app.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.beatraxus.app.ui.utils.FastBlur
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Language("AGSL")
internal val FLUID_AGSL = """
    uniform shader curArt;
    uniform shader prevArt;
    uniform float2 curArtSize;
    uniform float2 prevArtSize;
    uniform float fade;

    uniform float4 sides;
    uniform float4 angles;
    uniform float2 centre0;
    uniform float2 centre1;
    uniform float2 centre2;
    uniform float2 centre3;

    uniform float2 twistA;
    uniform float2 twistB;
    uniform float twistRadius;
    uniform float twistAngle;

    uniform float saturation;
    uniform float brightness;
    uniform float contrast;

    float2 twist(float2 coord, float2 offset, float radius, float angle) {
        float2 d = coord - offset;
        float dist = length(d);
        if (dist < radius) {
            float ratio = (radius - dist) / radius;
            float a = ratio * ratio * angle;
            float s = sin(a);
            float c = cos(a);
            d = float2(d.x * c - d.y * s, d.x * s + d.y * c);
        }
        return d + offset;
    }

    float2 localOf(float2 p, float2 centre, float angle, float side) {
        float2 d = p - centre;
        float c = cos(-angle);
        float s = sin(-angle);
        return float2(d.x * c - d.y * s, d.x * s + d.y * c) / side + 0.5;
    }

    bool covers(float2 uv) {
        return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
    }

    half4 main(float2 fragCoord) {
        float2 p = twist(fragCoord, twistA, twistRadius, twistAngle);
        p = twist(p, twistB, twistRadius, -twistAngle);

        float2 uv = clamp(localOf(p, centre0, angles.x, sides.x), 0.0, 1.0);
        float2 u1 = localOf(p, centre1, angles.y, sides.y);
        float2 u2 = localOf(p, centre2, angles.z, sides.z);
        float2 u3 = localOf(p, centre3, angles.w, sides.w);
        if (covers(u1)) uv = u1;
        if (covers(u2)) uv = u2;
        if (covers(u3)) uv = u3;

        float3 cur = float3(curArt.eval(uv * curArtSize).rgb);
        float3 prv = float3(prevArt.eval(uv * prevArtSize).rgb);
        float3 c = mix(prv, cur, fade);

        float mid = (c.r + c.g + c.b) / 3.0;
        c = float3(mid) + (c - float3(mid)) * saturation;
        c *= brightness;
        c = c * (1.0 + contrast) - float3(0.5 * contrast);
        return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
    }
""".trimIndent()

internal data class FluidCopy(
    val centreX: Float,
    val centreY: Float,
    val side: Float,
    val angle: Float,
)

internal fun fluidCopies(width: Float, height: Float, seconds: Float, seed: Int): List<FluidCopy> =
    fluidCopies(width, height, seconds) { startPhase(seed, it) }

/**
 * Same layout, but each copy's start phase comes from [phase] (index 0..[PHASE_COUNT]-1). The composable animates those
 * phases toward the new track's values, so the motion glides into the new pattern instead of jumping when the song changes.
 */
internal fun fluidCopies(width: Float, height: Float, seconds: Float, phase: (Int) -> Float): List<FluidCopy> {
    val longest = maxOf(width, height)
    val t = seconds * FLOW_SPEED
    val cx = width / 2f
    val cy = height / 2f
    val orbit = { rate: Float, radius: Float, phase: Float ->
        val a = rate * t + phase
        cx + width * radius * cos(a) to cy + width * radius * sin(a)
    }
    val (x3, y3) = orbit(ORBIT_RATE_MID, ORBIT_RADIUS_MID, phase(4))
    val (x4, y4) = orbit(ORBIT_RATE_SMALL, ORBIT_RADIUS_SMALL, phase(5))
    return listOf(
        FluidCopy(cx, cy, longest * SQRT_2, phase(0) + ROTATION[0] * t),
        FluidCopy(width / 2.5f, height / 2.5f, longest * 0.8f, phase(1) + ROTATION[1] * t),
        FluidCopy(x3, y3, longest * 0.5f, phase(2) + ROTATION[2] * t),
        FluidCopy(x4, y4, longest * 0.25f, phase(3) + ROTATION[3] * t),
    )
}

private fun startPhase(seed: Int, index: Int): Float =
    (((seed ushr (index * 5)) and 0x1F) / 32f) * TWO_PI

private const val TWO_PI = 6.2831855f
private const val PI_F = 3.1415927f
internal const val PHASE_COUNT = 6

/** [d] wrapped into -PI..PI, so a phase change always takes the shorter way round. */
internal fun shortestAngle(d: Float): Float {
    var a = d % TWO_PI
    if (a > PI_F) a -= TWO_PI
    if (a < -PI_F) a += TWO_PI
    return a
}

/**
 * The fluid is drawn on a smaller surface and scaled up: it is blurred anyway, and a full-screen shader plus a huge
 * blur every frame is what made it stutter on mid-range GPUs. Small areas (the Now Playing bar) are drawn 1:1.
 */
internal fun fluidRenderScale(width: Int, height: Int): Int = (maxOf(width, height) / RENDER_SCALE_STEP_PX).coerceIn(1, MAX_RENDER_SCALE)

private const val RENDER_SCALE_STEP_PX = 600
private const val MAX_RENDER_SCALE = 4
private val SQRT_2 = sqrt(2f)

private val ROTATION = floatArrayOf(0.06f, -0.12f, 0.06f, -0.08f)

private const val ORBIT_RADIUS_MID = 0.25f
private const val ORBIT_RADIUS_SMALL = 0.10f
private const val ORBIT_RATE_MID = 0.045f
private const val ORBIT_RATE_SMALL = 0.09f
// Lowered from 2.5f so the animated background drifts slower.
private const val FLOW_SPEED = 1.4f

private const val SATURATION = 1.9f
private const val BRIGHTNESS = 0.52f
private const val LYRICS_BRIGHTNESS = 0.44f
private const val CONTRAST = 0.3f

private const val TWIST_ANGLE = 1.6f
private const val TWIST_REACH = 1.0f

private const val BLUR_FRACTION = 0.18f
private const val BLUR_SIGMA_MIN = 80f
private const val BLUR_SIGMA_MAX = 360f

private const val TEXTURE_PX = 256
private const val TEXTURE_CACHE = 3
private const val CROSSFADE_MS = 1800
// How long the motion pattern takes to glide to the next song's pattern.
private const val PHASE_BLEND_MS = 3200
private const val APPEAR_MS = 800
// 0f = advance on every display frame (was 1f/30f, which made motion step at 30fps and look choppy).
private const val FRAME_SECONDS = 0f

private val textureCache = LinkedHashMap<String, Bitmap>()

private suspend fun loadTexture(context: Context, url: String, preBlur: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
    val cacheKey = if (preBlur) "$url-preblurred" else url
    synchronized(textureCache) {
        textureCache.remove(cacheKey)?.also { textureCache[cacheKey] = it }
    }?.let { return@withContext it }

    val request = ImageRequest.Builder(context)
        .data(url)
        .size(TEXTURE_PX)
        .allowHardware(false)
        .build()
    val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
    var bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null

    if (preBlur) {
        bitmap = FastBlur.blurArtwork(bitmap, radius = 18, maxDimension = 64)
    }

    synchronized(textureCache) {
        textureCache[cacheKey] = bitmap
        while (textureCache.size > TEXTURE_CACHE) {
            textureCache.remove(textureCache.keys.first())
        }
    }
    bitmap
}

internal fun fluidBlurSigma(width: Float, height: Float, extraBlur: Boolean = false): Float =
    (minOf(width, height) * (if (extraBlur) 0.50f else BLUR_FRACTION)).coerceIn(
        if (extraBlur) 280f else BLUR_SIGMA_MIN,
        if (extraBlur) 720f else BLUR_SIGMA_MAX
    )

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslFluidRenderer {
    private val shader = RuntimeShader(FLUID_AGSL)
    private val paint = Paint()

    /** One bitmap shader per texture (not one per frame), sampled bilinearly so the up-scaled art never looks blocky. */
    private class TextureShader {
        private var bitmap: Bitmap? = null
        private var shader: BitmapShader? = null
        fun of(b: Bitmap): BitmapShader {
            val existing = shader
            if (existing != null && bitmap === b) return existing
            val created = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            runCatching { created.setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
            bitmap = b
            shader = created
            return created
        }
    }
    private val curTexture = TextureShader()
    private val prevTexture = TextureShader()

    fun render(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        seconds: Float,
        phase: (Int) -> Float,
        current: Bitmap,
        previous: Bitmap,
        fade: Float,
        extraBlur: Boolean = false,
    ) {
        val copies = fluidCopies(width, height, seconds, phase)
        val curShader = curTexture.of(current)
        val prevShader = prevTexture.of(previous)
        shader.setInputShader("curArt", curShader)
        shader.setInputShader("prevArt", prevShader)
        shader.setFloatUniform("curArtSize", current.width.toFloat(), current.height.toFloat())
        shader.setFloatUniform("prevArtSize", previous.width.toFloat(), previous.height.toFloat())
        shader.setFloatUniform("fade", fade)
        shader.setFloatUniform("sides", copies[0].side, copies[1].side, copies[2].side, copies[3].side)
        shader.setFloatUniform("angles", copies[0].angle, copies[1].angle, copies[2].angle, copies[3].angle)
        shader.setFloatUniform("centre0", copies[0].centreX, copies[0].centreY)
        shader.setFloatUniform("centre1", copies[1].centreX, copies[1].centreY)
        shader.setFloatUniform("centre2", copies[2].centreX, copies[2].centreY)
        shader.setFloatUniform("centre3", copies[3].centreX, copies[3].centreY)
        shader.setFloatUniform("twistA", width * 0.25f, height)
        shader.setFloatUniform("twistB", width * 0.75f, 0f)
        shader.setFloatUniform("twistRadius", (maxOf(width, height) + minOf(width, height)) / 2f * TWIST_REACH)
        shader.setFloatUniform("twistAngle", TWIST_ANGLE)
        shader.setFloatUniform("saturation", SATURATION)
        shader.setFloatUniform("brightness", if (extraBlur) LYRICS_BRIGHTNESS else BRIGHTNESS)
        shader.setFloatUniform("contrast", CONTRAST)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

/**
 * Checks whether this device can render the fluid background.
 * Requires:
 *  - Android 12+ (API >= 31) because RenderEffect and AGSL (API 33+) are unsupported on API <= 30
 *    (where Modifier.blur() is an explicit no-op in Jetpack Compose).
 *  - Hardware acceleration enabled on the current View.
 *  - Not a low-RAM device where background blurs are unsupported or disabled by the OS/GPU.
 */
@Composable
fun isFluidSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return false
    }

    val context = LocalContext.current
    val view = LocalView.current

    if (view.isInEditMode) return false
    if (!view.isHardwareAccelerated) return false

    val activityManager = remember(context) {
        runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    }
    if (activityManager?.isLowRamDevice == true) {
        return false
    }

    return true
}

/**
 * Moving blurred "fluid" background built from album art.
 * [artworkUrl]: any Coil-loadable model (http(s) url, file://, content://). null -> [fallback] is shown.
 * [seedKey]: any stable per-track string (id or "title|artist"); changes the motion pattern per track.
 */
@Composable
fun FluidArtworkBackground(
    artworkUrl: String?,
    seedKey: String,
    modifier: Modifier = Modifier,
    extraBlur: Boolean = false,
    rotatingBackgroundEnabled: Boolean = true,
    lyricsRotatingBackgroundEnabled: Boolean = false,
    fallback: @Composable () -> Unit,
) {
    val fluidSupported = isFluidSupported()
    val isEnabled = if (extraBlur) lyricsRotatingBackgroundEnabled && rotatingBackgroundEnabled else rotatingBackgroundEnabled
    if (!isEnabled || !fluidSupported) {
        Box(modifier) {
            fallback()
        }
        return
    }

    val context = LocalContext.current

    var current by remember { mutableStateOf<Bitmap?>(null) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val crossfade = remember { Animatable(1f) }
    val appear = remember { Animatable(0f) }
    val seed = remember(seedKey) { seedKey.hashCode() }

    // Start phases of the moving copies. On a song change they glide (shortest way round) to the new song's values, so
    // the motion never jumps while the colours crossfade. No audio input is used: the flow is calm and independent of
    // the beat, like the Apple Music player background.
    val phases = remember { List(PHASE_COUNT) { Animatable(startPhase(seed, it)) } }
    LaunchedEffect(seed) {
        coroutineScope {
            phases.forEachIndexed { index, anim ->
                launch {
                    val delta = shortestAngle(startPhase(seed, index) - anim.value)
                    if (abs(delta) > 0.001f) {
                        anim.animateTo(anim.value + delta, tween(PHASE_BLEND_MS, easing = FastOutSlowInEasing))
                    }
                }
            }
        }
    }
    val phaseOf: (Int) -> Float = { phases[it].value }

    val agslRenderer = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { AgslFluidRenderer() }.getOrNull()
        } else {
            null
        }
    }

    LaunchedEffect(artworkUrl, agslRenderer == null) {
        val url = artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bitmap = loadTexture(context, url, preBlur = (agslRenderer == null)) ?: return@LaunchedEffect
        val showing = current
        current = bitmap
        if (showing != null && showing !== bitmap) {
            previous = showing
            crossfade.snapTo(0f)
            crossfade.animateTo(1f, tween(CROSSFADE_MS))
            previous = null
        }
    }

    LaunchedEffect(current != null) {
        if (current != null) appear.animateTo(1f, tween(APPEAR_MS))
    }

    var seconds by remember { mutableFloatStateOf(0f) }
    var cachedSigma by remember { mutableFloatStateOf(0f) }
    var cachedRenderEffect by remember { mutableStateOf<androidx.compose.ui.graphics.RenderEffect?>(null) }
    val shouldPauseLoop = extraBlur && !lyricsRotatingBackgroundEnabled
    LaunchedEffect(!shouldPauseLoop) {
        if (shouldPauseLoop) return@LaunchedEffect
        var last = 0L
        var pending = 0f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) pending += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
            }
            if (pending >= FRAME_SECONDS) {
                seconds += pending
                pending = 0f
            }
        }
    }

    Box(modifier) {
        if (appear.value < 1f || current == null) {
            fallback()
        }

        if (current != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scale = fluidRenderScale(constraints.maxWidth, constraints.maxHeight)
                Box(
                    Modifier
                        .fillMaxSize()
                        // Measure and draw the content at 1/scale size, then stretch it back over the full area.
                        .layout { measurable, c ->
                            val w = maxOf(1, c.maxWidth / scale)
                            val h = maxOf(1, c.maxHeight / scale)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(c.maxWidth, c.maxHeight) {
                                placeable.placeWithLayer(0, 0) {
                                    scaleX = c.maxWidth.toFloat() / w
                                    scaleY = c.maxHeight.toFloat() / h
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                            }
                        }
                        .graphicsLayer {
                            alpha = appear.value
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                // Sigma is chosen for the full-size area, then reduced to the smaller surface.
                                val sigma = fluidBlurSigma(size.width * scale, size.height * scale, extraBlur) / scale
                                if (sigma != cachedSigma || cachedRenderEffect == null) {
                                    cachedSigma = sigma
                                    cachedRenderEffect = runCatching {
                                        RenderEffect
                                            .createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
                                            .asComposeRenderEffect()
                                    }.getOrNull()
                                }
                                renderEffect = cachedRenderEffect
                            }
                        }
                        .drawBehind {
                            val cur = current ?: return@drawBehind
                            val prv = previous ?: cur

                            if (agslRenderer != null) {
                                drawIntoCanvas { canvas ->
                                    agslRenderer.render(
                                        canvas = canvas.nativeCanvas,
                                        width = size.width,
                                        height = size.height,
                                        seconds = seconds,
                                        phase = phaseOf,
                                        current = cur,
                                        previous = prv,
                                        fade = crossfade.value,
                                        extraBlur = extraBlur,
                                    )
                                }
                            } else {
                                val copies = fluidCopies(size.width, size.height, seconds, phaseOf)
                                val curImg = cur.asImageBitmap()
                                for (copy in copies) {
                                    rotate(
                                        degrees = Math.toDegrees(copy.angle.toDouble()).toFloat(),
                                        pivot = Offset(copy.centreX, copy.copy(angle = copy.angle).centreY)
                                    ) {
                                        drawImage(
                                            image = curImg,
                                            dstOffset = IntOffset(
                                                (copy.centreX - copy.side / 2f).toInt(),
                                                (copy.centreY - copy.side / 2f).toInt()
                                            ),
                                            dstSize = IntSize(copy.side.toInt(), copy.side.toInt())
                                        )
                                    }
                                }
                            }
                        }
                )
            }
        }
    }
}
