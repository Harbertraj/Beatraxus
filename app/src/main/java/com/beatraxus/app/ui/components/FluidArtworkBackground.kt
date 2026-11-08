package com.beatraxus.app.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.beatraxus.app.ui.utils.FastBlur
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
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

/**
 * Critically damped follower with a speed limit (the SmoothDamp curve). Speed ramps up and down smoothly and never
 * overshoots, so a changed target is approached gently instead of being chased at a visible speed.
 */
internal class SmoothDamper(var value: Float) {
    var velocity = 0f
        private set

    fun step(target: Float, dt: Float, smoothSeconds: Float, maxSpeed: Float): Float {
        if (dt <= 0f) return value
        val smooth = maxOf(0.0001f, smoothSeconds)
        val omega = 2f / smooth
        val x = omega * dt
        val decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val maxChange = maxSpeed * smooth
        val change = (value - target).coerceIn(-maxChange, maxChange)
        val limitedTarget = value - change
        val temp = (velocity + omega * change) * dt
        velocity = (velocity - omega * temp) * decay
        var out = limitedTarget + (change + temp) * decay
        if ((target - value > 0f) == (out > target)) {
            out = target
            velocity = 0f
        }
        value = out
        return out
    }
}

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
private const val CROSSFADE_MS = 1200
// Starts moving on the very first frame (no dead time at the start, which felt like a ~1s delay after a song change)
// and settles softly at the end.
private val CrossfadeEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
// The motion pattern drifts to the next song's pattern as slowly as the background itself moves (no visible change of
// speed): a critically damped follow with a speed limit, in radians and seconds.
internal const val PHASE_SMOOTH_SECONDS = 6f
internal const val PHASE_MAX_SPEED = 0.05f
private const val APPEAR_MS = 800

/**
 * The flow lives OUTSIDE any composable. Closing Now Playing, opening Studio / DSP or any other screen, and coming back
 * used to rebuild the background from second 0 (new clock, new phases, picture fading in from the fallback). Now the clock
 * is the device's monotonic time, so the motion carries on exactly where it would be had the screen stayed open, and the
 * phase followers, the picture on show and its fade are kept for the next time.
 */
internal object FluidFlowState {
    private val originNanos = android.os.SystemClock.elapsedRealtimeNanos()

    /** Seconds of flow since the app process started; keeps advancing while no background is on screen. */
    fun seconds(): Float = ((android.os.SystemClock.elapsedRealtimeNanos() - originNanos) / 1_000_000_000.0).toFloat()

    /** Start-phase followers, shared by every background (Now Playing and the mini player flow as one). */
    val dampers: List<SmoothDamper> = List(PHASE_COUNT) { SmoothDamper(0f) }
    var phasesSeeded = false
    var lastFrameSeconds = 0f

    /** Last picture that was fully on screen (null before the first one), so a returning screen shows it at once. */
    var shownBitmap: Bitmap? = null
}

private val textureCache = LinkedHashMap<String, Bitmap>()

private suspend fun loadTexture(context: Context, url: String, preBlur: Boolean = false, model: Any = url): Bitmap? = withContext(Dispatchers.IO) {
    val cacheKey = if (preBlur) "$url-preblurred" else url
    synchronized(textureCache) {
        textureCache.remove(cacheKey)?.also { textureCache[cacheKey] = it }
    }?.let { return@withContext it }

    val request = ImageRequest.Builder(context)
        .data(model)
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

// Settings > Appearance > Background / Mini Player Background reset values. At these the animated background looks exactly
// as it does without the settings; the sliders then make it more or less blurred and darker or brighter than that.
const val NOW_PLAYING_DEFAULT_BLUR = 210f
const val NOW_PLAYING_DEFAULT_DARKNESS = 0.3f
const val MINI_PLAYER_DEFAULT_BLUR = 70f
const val MINI_PLAYER_DEFAULT_DARKNESS = 0.5f

/** "Blur Intensity" slider -> how much the animated background's blur is scaled (1 at the reset value). */
fun fluidBlurScale(intensity: Float, defaultIntensity: Float): Float =
    (intensity / defaultIntensity.coerceAtLeast(1f)).coerceIn(0.08f, 3.5f)

/** "Darkness" slider -> brightness multiplier of the animated background (1 at the reset value, 0 at full darkness). */
fun fluidBrightnessScale(darkness: Float, defaultDarkness: Float): Float =
    ((1f - darkness.coerceIn(0f, 1f)) / (1f - defaultDarkness.coerceIn(0f, 0.95f))).coerceIn(0f, 1.6f)

private const val BLUR_SIGMA_CEILING = 720f
private const val BLUR_SIGMA_FLOOR = 0.5f

internal fun fluidBlurSigma(width: Float, height: Float, extraBlur: Boolean = false): Float =
    (minOf(width, height) * (if (extraBlur) 0.50f else BLUR_FRACTION)).coerceIn(
        if (extraBlur) 280f else BLUR_SIGMA_MIN,
        if (extraBlur) 720f else BLUR_SIGMA_MAX
    )

/**
 * [previous] and [current] mixed by [fade] into one texture. Used when the song changes again while a crossfade is
 * still running, so the picture carries on from exactly what is on screen instead of jumping.
 */
private fun blendTextures(previous: Bitmap, current: Bitmap, fade: Float): Bitmap {
    val out = Bitmap.createBitmap(TEXTURE_PX, TEXTURE_PX, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val dst = Rect(0, 0, TEXTURE_PX, TEXTURE_PX)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(previous, null, dst, paint)
    paint.alpha = (fade.coerceIn(0f, 1f) * 255f).toInt()
    canvas.drawBitmap(current, null, dst, paint)
    return out
}

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
        brightnessScale: Float = 1f,
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
        shader.setFloatUniform("brightness", (if (extraBlur) LYRICS_BRIGHTNESS else BRIGHTNESS) * brightnessScale)
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
 * False while this screen's navigation entry is animating in or out (it is only STARTED then) or the app is not in the
 * foreground; true once it is fully RESUMED. The full-screen shader / gradient loops stop drawing in between: a slide
 * transition already needs every millisecond of the frame, and a blurred full-screen shader running behind it is what
 * made opening Settings or Studio DSP stutter. Motion is driven by [FluidFlowState.seconds] (absolute time), so it
 * resumes in the right place rather than from the start.
 */
@Composable
internal fun rememberScreenSettled(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settled by produceState(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), lifecycle) {
        lifecycle.currentStateFlow.collect { value = it.isAtLeast(Lifecycle.State.RESUMED) }
    }
    return settled
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
    /** See [fluidBlurScale]. 1 = the standard blur. */
    blurScale: Float = 1f,
    /** See [fluidBrightnessScale]. 1 = the standard brightness. */
    brightnessScale: Float = 1f,
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

    // A returning screen starts from the picture that was last shown (fully faded in) instead of from nothing.
    var current by remember { mutableStateOf<Bitmap?>(FluidFlowState.shownBitmap) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val crossfade = remember { Animatable(1f) }
    val appear = remember { Animatable(if (FluidFlowState.shownBitmap != null) 1f else 0f) }
    val seed = remember(seedKey) { seedKey.hashCode() }

    // Start phases of the moving copies. On a song change they drift (shortest way round, speed limited) to the new
    // song's values while the colours crossfade, so the motion never speeds up or jumps. No audio input is used.
    val dampers = FluidFlowState.dampers
    if (!FluidFlowState.phasesSeeded) {
        FluidFlowState.phasesSeeded = true
        dampers.forEachIndexed { i, d -> d.value = startPhase(seed, i) }
    }
    val currentSeed by rememberUpdatedState(seed)
    val phaseOf: (Int) -> Float = { dampers[it].value }

    val agslRenderer = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { AgslFluidRenderer() }.getOrNull()
        } else {
            null
        }
    }

    // A song without art (or whose art cannot be loaded) animates the time-of-day default album art (morning / afternoon /
    // evening / night, the same image the covers show) instead of leaving the static fallback.
    val defaultArtRes = com.beatraxus.app.utils.ImageUtils.getDefaultAlbumArtRes()
    LaunchedEffect(artworkUrl, defaultArtRes, agslRenderer == null) {
        val preBlur = agslRenderer == null
        val url = artworkUrl?.takeIf { it.isNotBlank() }
        val bitmap = (url?.let { loadTexture(context, it, preBlur) }
            ?: loadTexture(context, "res:$defaultArtRes", preBlur, model = defaultArtRes)) ?: return@LaunchedEffect
        // Same picture as the one on show (returning to the screen): nothing to fade.
        if (bitmap === current) return@LaunchedEffect
        val showing = current
        val inFlight = previous
        val fadeNow = crossfade.value
        val carry = when {
            showing == null || showing === bitmap -> null
            // Skipped again mid-crossfade: start from the mix that is on screen.
            inFlight != null && fadeNow < 0.999f ->
                withContext(Dispatchers.Default) { blendTextures(inFlight, showing, fadeNow) }
            else -> showing
        }
        if (carry != null) {
            // Reset the fade and the old picture BEFORE swapping in the new one, so the new art never flashes at full
            // strength for a frame, and the fade starts right away.
            previous = carry
            crossfade.snapTo(0f)
            current = bitmap
            crossfade.animateTo(1f, tween(CROSSFADE_MS, easing = CrossfadeEasing))
            previous = null
        } else {
            current = bitmap
        }
        FluidFlowState.shownBitmap = bitmap
    }

    LaunchedEffect(current != null) {
        if (current != null) appear.animateTo(1f, tween(APPEAR_MS))
    }

    var seconds by remember { mutableFloatStateOf(FluidFlowState.seconds()) }
    var cachedSigma by remember { mutableFloatStateOf(0f) }
    var cachedRenderEffect by remember { mutableStateOf<androidx.compose.ui.graphics.RenderEffect?>(null) }
    val screenSettled = rememberScreenSettled()
    val shouldPauseLoop = (extraBlur && !lyricsRotatingBackgroundEnabled) || !screenSettled
    LaunchedEffect(!shouldPauseLoop) {
        if (shouldPauseLoop) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val now = FluidFlowState.seconds()
            // The followers are shared: advance them once per frame in total, however many backgrounds are on screen.
            val stepDt = (now - FluidFlowState.lastFrameSeconds).coerceIn(0f, 0.1f)
            if (stepDt > 0f) {
                FluidFlowState.lastFrameSeconds = now
                val seedNow = currentSeed
                dampers.forEachIndexed { index, damper ->
                    val target = damper.value + shortestAngle(startPhase(seedNow, index) - damper.value)
                    damper.step(target, stepDt, PHASE_SMOOTH_SECONDS, PHASE_MAX_SPEED)
                }
            }
            seconds = now
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
                                val sigma = (fluidBlurSigma(size.width * scale, size.height * scale, extraBlur) * blurScale)
                                    .coerceIn(BLUR_SIGMA_FLOOR, BLUR_SIGMA_CEILING) / scale
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
                                        brightnessScale = brightnessScale,
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
                                if (brightnessScale < 1f) drawRect(Color.Black.copy(alpha = 1f - brightnessScale))
                            }
                        }
                )
            }
        }
    }
}
