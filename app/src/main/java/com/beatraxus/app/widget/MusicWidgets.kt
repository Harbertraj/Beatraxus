package com.beatraxus.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.sin
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.beatraxus.app.MainActivity
import com.beatraxus.app.R
import com.beatraxus.app.service.AudioPlaybackService
import com.beatraxus.app.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WidgetSize {
    SMALL, MEDIUM, LARGE
}

object MusicWidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val IS_PLAYING = booleanPreferencesKey("is_playing")
    val ALBUM_ART_URI = stringPreferencesKey("album_art_uri")
    val SHUFFLE_ON = booleanPreferencesKey("shuffle_on")
    val REPEAT_MODE = stringPreferencesKey("repeat_mode")
    val PROGRESS_RATIO = floatPreferencesKey("progress_ratio")
}

data class WidgetState(
    val title: String = "Not Playing",
    val artist: String = "Beatraxus",
    val isPlaying: Boolean = false,
    val albumArtUri: String = "",
    val shuffleOn: Boolean = false,
    val repeatMode: String = "OFF", // OFF, ALL, ONE
    val progressRatio: Float = 0f
)

object WidgetStateStore {
    private const val PREFS_NAME = "beatraxus_widget_state"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_IS_PLAYING = "is_playing"
    val KEY_ALBUM_ART_URI = "album_art_uri"
    private const val KEY_SHUFFLE_ON = "shuffle_on"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_PROGRESS_RATIO = "progress_ratio"

    fun saveState(context: Context, state: WidgetState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TITLE, state.title)
            .putString(KEY_ARTIST, state.artist)
            .putBoolean(KEY_IS_PLAYING, state.isPlaying)
            .putString(KEY_ALBUM_ART_URI, state.albumArtUri)
            .putBoolean(KEY_SHUFFLE_ON, state.shuffleOn)
            .putString(KEY_REPEAT_MODE, state.repeatMode)
            .putFloat(KEY_PROGRESS_RATIO, state.progressRatio)
            .apply()
    }

    fun loadState(context: Context): WidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WidgetState(
            title = prefs.getString(KEY_TITLE, "Not Playing") ?: "Not Playing",
            artist = prefs.getString(KEY_ARTIST, "Beatraxus") ?: "Beatraxus",
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
            albumArtUri = prefs.getString(KEY_ALBUM_ART_URI, "") ?: "",
            shuffleOn = prefs.getBoolean(KEY_SHUFFLE_ON, false),
            repeatMode = prefs.getString(KEY_REPEAT_MODE, "OFF") ?: "OFF",
            progressRatio = prefs.getFloat(KEY_PROGRESS_RATIO, 0f)
        )
    }

    suspend fun saveAndSyncState(context: Context, state: WidgetState) {
        saveState(context, state)
        val manager = GlanceAppWidgetManager(context)
        val widgets = listOf(
            MusicWidgetSmall::class.java to MusicWidgetSmall(),
            MusicWidgetMedium::class.java to MusicWidgetMedium(),
            MusicWidgetLarge::class.java to MusicWidgetLarge()
        )

        for ((clazz, widget) in widgets) {
            try {
                val ids = manager.getGlanceIds(clazz)
                for (id in ids) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            set(MusicWidgetKeys.TITLE, state.title)
                            set(MusicWidgetKeys.ARTIST, state.artist)
                            set(MusicWidgetKeys.IS_PLAYING, state.isPlaying)
                            set(MusicWidgetKeys.ALBUM_ART_URI, state.albumArtUri)
                            set(MusicWidgetKeys.SHUFFLE_ON, state.shuffleOn)
                            set(MusicWidgetKeys.REPEAT_MODE, state.repeatMode)
                            set(MusicWidgetKeys.PROGRESS_RATIO, state.progressRatio)
                        }.toPreferences()
                    }
                    widget.update(context, id)
                }
            } catch (e: Exception) {
                Log.e("WidgetStateStore", "Error updating widget $clazz: ${e.message}", e)
            }
        }
    }

    suspend fun syncStateToWidgets(context: Context) {
        val state = loadState(context)
        saveAndSyncState(context, state)
    }
}

data class WidgetArtProviders(
    val coverProvider: ImageProvider,
    val bgProvider: ImageProvider,
    val hasArt: Boolean,
    val accent: Color = WidgetDefaults.ACCENT
)

object WidgetDefaults {
    val ACCENT = Color(0xFFE23AF0)   // Beatraxus magenta
    val SURFACE = Color(0xFF14121C)
}

/** Draws every custom bitmap used by the widgets (consistent on all Android versions). */
private object WidgetBitmaps {
    private val cache = LruCache<String, Bitmap>(40)

    /** Beatraxus "slash" progress bar - same look as the Beatraxus Pulse seekbar in the app. */
    fun pulseBar(progress: Float, accent: Int, seed: Int, wPx: Int = 720, hPx: Int = 56): Bitmap {
        val p = progress.coerceIn(0f, 1f)
        val key = "bar_${(p * 100).toInt()}_${accent}_$seed"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = 48
        val slant = hPx * 0.28f
        val step = (wPx - slant) / n
        val segW = step * 0.55f
        val p1 = (seed % 97) * 0.13f
        val p2 = (seed % 61) * 0.29f
        val path = Path()
        for (i in 0 until n) {
            val base = (0.2f + 0.8f * abs(
                sin(i * 0.23f + p1) * 0.55f + sin(i * 0.071f + p2) * 0.30f + sin(i * 0.61f + p1 * 2f) * 0.15f
            )).coerceIn(0.2f, 1f)
            val h = hPx * (0.28f + 0.72f * base)
            val x = i * step
            val top = (hPx - h) / 2f
            val bottom = top + h
            val frac = (i + 0.5f) / n
            paint.color = if (frac <= p) {
                ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), frac * 0.35f)
            } else {
                0x59FFFFFF
            }
            path.reset()
            path.moveTo(x + slant, top)
            path.lineTo(x + slant + segW, top)
            path.lineTo(x + segW, bottom)
            path.lineTo(x, bottom)
            path.close()
            c.drawPath(path, paint)
        }
        if (p > 0f) {
            paint.color = 0xFFFFFFFF.toInt()
            val hx = (p * (wPx - 4f)).coerceIn(2f, wPx - 4f)
            c.drawRoundRect(RectF(hx, 0f, hx + 4f, hPx.toFloat()), 2f, 2f, paint)
        }
        cache.put(key, bmp)
        return bmp
    }

    fun playButton(sizePx: Int, playing: Boolean, accent: Int): Bitmap {
        val key = "play_${sizePx}_${playing}_$accent"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = sizePx / 2f
        paint.color = ColorUtils.setAlphaComponent(accent, 70)
        c.drawCircle(r, r, r, paint)                    // soft halo
        paint.color = accent
        c.drawCircle(r, r, r * 0.84f, paint)            // button
        paint.color = 0xFF101010.toInt()
        if (playing) {
            val bw = r * 0.20f
            val bh = r * 0.62f
            c.drawRoundRect(RectF(r - bw * 1.7f, r - bh / 2, r - bw * 0.5f, r + bh / 2), bw / 2, bw / 2, paint)
            c.drawRoundRect(RectF(r + bw * 0.5f, r - bh / 2, r + bw * 1.7f, r + bh / 2), bw / 2, bw / 2, paint)
        } else {
            val t = Path()
            val s = r * 0.62f
            t.moveTo(r - s * 0.42f, r - s * 0.62f)
            t.lineTo(r + s * 0.72f, r)
            t.lineTo(r - s * 0.42f, r + s * 0.62f)
            t.close()
            c.drawPath(t, paint)
        }
        cache.put(key, bmp)
        return bmp
    }

    /** Centre-cropped, rounded cover with a thin accent outline. */
    fun roundedCover(src: Bitmap, sizePx: Int, accent: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = sizePx * 0.2f
        c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, paint)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.016f
            color = ColorUtils.setAlphaComponent(accent, 210)
        }
        val inset = stroke.strokeWidth / 2f
        c.drawRoundRect(RectF(inset, inset, sizePx - inset, sizePx - inset), radius, radius, stroke)
        return out
    }
}

private object WidgetImageLoader {
    private val cache = LruCache<String, WidgetArtProviders>(15)

    private fun defaultArt(): WidgetArtProviders {
        val res = ImageProvider(ImageUtils.getDefaultAlbumArtRes())
        return WidgetArtProviders(res, res, hasArt = false)
    }

    suspend fun loadArt(context: Context, uriString: String): WidgetArtProviders {
        if (uriString.isEmpty()) return defaultArt()
        cache.get(uriString)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: return@withContext defaultArt()

                val accent = extractAccent(bitmap)
                val cover = WidgetBitmaps.roundedCover(bitmap, 320, accent.toArgb())
                val bg = createBlurredBitmap(bitmap, targetSize = 256)
                val providers = WidgetArtProviders(ImageProvider(cover), ImageProvider(bg), true, accent)
                cache.put(uriString, providers)
                providers
            } catch (e: Exception) {
                defaultArt()
            }
        }
    }

    /** Picks a vivid colour from the artwork and makes sure it stays readable on dark glass. */
    private fun extractAccent(src: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(src, 96, 96, true)
        val palette = Palette.from(small).maximumColorCount(12).generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
        val rgb = swatch?.rgb ?: return WidgetDefaults.ACCENT
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.55f)
        hsl[2] = hsl[2].coerceIn(0.58f, 0.74f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    private fun createBlurredBitmap(src: Bitmap, targetSize: Int): Bitmap {
        val tiny = Bitmap.createScaledBitmap(src, 24, 24, true)
        return Bitmap.createScaledBitmap(tiny, targetSize, targetSize, true)
    }
}

abstract class BaseMusicWidget(private val defaultSizeCategory: WidgetSize) : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val title = prefs[MusicWidgetKeys.TITLE] ?: "Not Playing"
            val artist = prefs[MusicWidgetKeys.ARTIST] ?: "Beatraxus"
            val isPlaying = prefs[MusicWidgetKeys.IS_PLAYING] ?: false
            val albumArtUri = prefs[MusicWidgetKeys.ALBUM_ART_URI] ?: ""
            val shuffleOn = prefs[MusicWidgetKeys.SHUFFLE_ON] ?: false
            val repeatMode = prefs[MusicWidgetKeys.REPEAT_MODE] ?: "OFF"
            val progressRatio = prefs[MusicWidgetKeys.PROGRESS_RATIO] ?: 0f

            val artProvidersState = produceState<WidgetArtProviders?>(initialValue = null, key1 = albumArtUri) {
                value = WidgetImageLoader.loadArt(context, albumArtUri)
            }
            val defaultRes = ImageProvider(ImageUtils.getDefaultAlbumArtRes())
            val artProviders = artProvidersState.value ?: WidgetArtProviders(defaultRes, defaultRes, hasArt = false)

            WidgetUiContainer(
                sizeCategory = defaultSizeCategory,
                state = WidgetState(
                    title = title, artist = artist, isPlaying = isPlaying, albumArtUri = albumArtUri,
                    shuffleOn = shuffleOn, repeatMode = repeatMode, progressRatio = progressRatio
                ),
                art = artProviders
            )
        }
    }
}

class MusicWidgetSmall : BaseMusicWidget(WidgetSize.SMALL) {
    companion object {
        val KEY_TITLE = MusicWidgetKeys.TITLE
        val KEY_ARTIST = MusicWidgetKeys.ARTIST
        val KEY_IS_PLAYING = MusicWidgetKeys.IS_PLAYING
        val KEY_ALBUM_ART_URI = MusicWidgetKeys.ALBUM_ART_URI
    }
}

class MusicWidgetMedium : BaseMusicWidget(WidgetSize.MEDIUM) {
    companion object {
        val KEY_TITLE = MusicWidgetKeys.TITLE
        val KEY_ARTIST = MusicWidgetKeys.ARTIST
        val KEY_IS_PLAYING = MusicWidgetKeys.IS_PLAYING
        val KEY_ALBUM_ART_URI = MusicWidgetKeys.ALBUM_ART_URI
    }
}

class MusicWidgetLarge : BaseMusicWidget(WidgetSize.LARGE) {
    companion object {
        val KEY_TITLE = MusicWidgetKeys.TITLE
        val KEY_ARTIST = MusicWidgetKeys.ARTIST
        val KEY_IS_PLAYING = MusicWidgetKeys.IS_PLAYING
        val KEY_ALBUM_ART_URI = MusicWidgetKeys.ALBUM_ART_URI
    }
}

class ControlActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val actionStr = parameters[ActionKey] ?: return
        val intent = Intent(context, AudioPlaybackService::class.java).apply { action = actionStr }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            Log.e("MusicWidgets", "Foreground service start failed for $actionStr: ${e.message}", e)
            try { context.startService(intent) } catch (e2: Exception) {
                Log.e("MusicWidgets", "Fallback startService also failed: ${e2.message}", e2)
            }
        }
    }

    companion object {
        val ActionKey = ActionParameters.Key<String>("action")
    }
}

private fun GlanceModifier.appWidgetBackgroundRadius(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    else this.cornerRadius(20.dp)

private fun GlanceModifier.appWidgetInnerRadius(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.cornerRadius(android.R.dimen.system_app_widget_inner_radius)
    else this.cornerRadius(14.dp)

private fun fixedColorProvider(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}

@Composable
private fun WidgetUiContainer(sizeCategory: WidgetSize, state: WidgetState, art: WidgetArtProviders) {
    val context = LocalContext.current
    val size = LocalSize.current
    val openNowPlayingIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        putExtra("open_now_playing", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val compact = size.height < 90.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(fixedColorProvider(WidgetDefaults.SURFACE))
            .appWidgetBackgroundRadius()
            .clickable(actionStartActivity(openNowPlayingIntent))
    ) {
        if (art.hasArt) {
            Image(
                provider = art.bgProvider,
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        // dark glass + a soft tint of the artwork accent
        Box(modifier = GlanceModifier.fillMaxSize().background(fixedColorProvider(Color.Black.copy(alpha = 0.58f)))) {}
        Box(modifier = GlanceModifier.fillMaxSize().background(fixedColorProvider(art.accent.copy(alpha = 0.12f)))) {}

        Box(
            modifier = GlanceModifier.fillMaxSize().padding(if (compact) 8.dp else 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when (sizeCategory) {
                WidgetSize.SMALL -> SmallContent(state, art)
                WidgetSize.MEDIUM -> MediumContent(state, art, compact)
                WidgetSize.LARGE -> LargeContent(state, art)
            }
        }
    }
}

@Composable
private fun TitleBlock(state: WidgetState, titleSize: Int, artistSize: Int, modifier: GlanceModifier = GlanceModifier) {
    val idle = state.title == "Not Playing" || state.title.isBlank()
    Column(modifier = modifier) {
        Text(
            text = if (idle) "Not Playing" else state.title,
            style = TextStyle(color = fixedColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = titleSize.sp),
            maxLines = 1
        )
        Text(
            text = if (idle || state.artist.isEmpty()) "Tap to play" else state.artist,
            style = TextStyle(color = fixedColorProvider(Color.White.copy(alpha = 0.72f)), fontSize = artistSize.sp),
            maxLines = 1
        )
    }
}

@Composable
private fun BrandRow(state: WidgetState, accent: Color) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "BEATRAXUS",
            style = TextStyle(color = fixedColorProvider(accent), fontWeight = FontWeight.Bold, fontSize = 10.sp)
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = if (state.isPlaying) "PLAYING" else "PAUSED",
            style = TextStyle(color = fixedColorProvider(Color.White.copy(alpha = 0.6f)), fontWeight = FontWeight.Medium, fontSize = 10.sp)
        )
    }
}

@Composable
private fun SmallContent(state: WidgetState, art: WidgetArtProviders) {
    val size = LocalSize.current
    val wide = size.width >= 200.dp
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Cover(art, 44.dp)
        Spacer(GlanceModifier.width(10.dp))
        TitleBlock(state, 14, 12, GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(6.dp))
        if (wide) {
            GhostButton(AudioPlaybackService.ACTION_PREVIOUS, R.drawable.ic_skip_previous, R.string.cd_previous, 34.dp, 20.dp)
            PlayPauseButton(state.isPlaying, art.accent, 40.dp)
            GhostButton(AudioPlaybackService.ACTION_NEXT, R.drawable.ic_skip_next, R.string.cd_next, 34.dp, 20.dp)
        } else {
            PlayPauseButton(state.isPlaying, art.accent, 42.dp)
        }
    }
}

@Composable
private fun MediumContent(state: WidgetState, art: WidgetArtProviders, compact: Boolean) {
    val size = LocalSize.current
    val showExtras = size.width >= 290.dp
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            BrandRow(state, art.accent)
            Spacer(GlanceModifier.height(6.dp))
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Cover(art, if (compact) 40.dp else 52.dp)
            Spacer(GlanceModifier.width(10.dp))
            TitleBlock(state, 15, 12, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(4.dp))
            if (showExtras) {
                GhostButton(
                    AudioPlaybackService.ACTION_TOGGLE_SHUFFLE, R.drawable.ic_shuffle, R.string.cd_shuffle, 34.dp, 18.dp,
                    tint = if (state.shuffleOn) art.accent else Color.White.copy(alpha = 0.75f)
                )
            }
            GhostButton(AudioPlaybackService.ACTION_PREVIOUS, R.drawable.ic_skip_previous, R.string.cd_previous, 34.dp, 22.dp)
            PlayPauseButton(state.isPlaying, art.accent, if (compact) 40.dp else 46.dp)
            GhostButton(AudioPlaybackService.ACTION_NEXT, R.drawable.ic_skip_next, R.string.cd_next, 34.dp, 22.dp)
            if (showExtras) {
                GhostButton(
                    AudioPlaybackService.ACTION_TOGGLE_REPEAT, R.drawable.ic_repeat, R.string.cd_repeat, 34.dp, 18.dp,
                    tint = if (state.repeatMode != "OFF") art.accent else Color.White.copy(alpha = 0.75f)
                )
            }
        }
        Spacer(GlanceModifier.height(if (compact) 4.dp else 8.dp))
        PulseProgress(state, art.accent, if (compact) 10.dp else 14.dp)
    }
}

@Composable
private fun LargeContent(state: WidgetState, art: WidgetArtProviders) {
    val size = LocalSize.current
    val cover = minOf(size.height * 0.40f, size.width * 0.5f, 150.dp)
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandRow(state, art.accent)
        Spacer(GlanceModifier.defaultWeight())
        Cover(art, cover)
        Spacer(GlanceModifier.height(10.dp))
        val idle = state.title == "Not Playing" || state.title.isBlank()
        Text(
            text = if (idle) "Not Playing" else state.title,
            style = TextStyle(color = fixedColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 17.sp),
            maxLines = 1
        )
        Text(
            text = if (idle || state.artist.isEmpty()) "Tap to play" else state.artist,
            style = TextStyle(color = fixedColorProvider(Color.White.copy(alpha = 0.72f)), fontSize = 13.sp),
            maxLines = 1
        )
        Spacer(GlanceModifier.height(10.dp))
        PulseProgress(state, art.accent, 16.dp)
        Spacer(GlanceModifier.defaultWeight())
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GhostButton(
                AudioPlaybackService.ACTION_TOGGLE_SHUFFLE, R.drawable.ic_shuffle, R.string.cd_shuffle, 40.dp, 20.dp,
                tint = if (state.shuffleOn) art.accent else Color.White.copy(alpha = 0.75f)
            )
            GhostButton(AudioPlaybackService.ACTION_PREVIOUS, R.drawable.ic_skip_previous, R.string.cd_previous, 44.dp, 26.dp)
            PlayPauseButton(state.isPlaying, art.accent, 56.dp)
            GhostButton(AudioPlaybackService.ACTION_NEXT, R.drawable.ic_skip_next, R.string.cd_next, 44.dp, 26.dp)
            GhostButton(
                AudioPlaybackService.ACTION_TOGGLE_REPEAT, R.drawable.ic_repeat, R.string.cd_repeat, 40.dp, 20.dp,
                tint = if (state.repeatMode != "OFF") art.accent else Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun Cover(art: WidgetArtProviders, size: Dp) {
    Image(
        provider = art.coverProvider,
        contentDescription = null,
        modifier = if (art.hasArt) GlanceModifier.size(size) else GlanceModifier.size(size).appWidgetInnerRadius(),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun PulseProgress(state: WidgetState, accent: Color, height: Dp) {
    val bucket = (state.progressRatio * 100).toInt()
    val bitmap = remember(bucket, accent, state.title) {
        WidgetBitmaps.pulseBar(state.progressRatio, accent.toArgb(), state.title.hashCode().let { abs(it) })
    }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxWidth().height(height),
        contentScale = ContentScale.FillBounds
    )
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, accent: Color, buttonSize: Dp) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val px = (buttonSize.value * density * 1.5f).toInt().coerceAtLeast(64)
    val bitmap = remember(isPlaying, accent, px) { WidgetBitmaps.playButton(px, isPlaying, accent.toArgb()) }
    val action = actionRunCallback<ControlActionCallback>(
        actionParametersOf(ControlActionCallback.ActionKey to AudioPlaybackService.ACTION_PLAY_PAUSE)
    )
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = context.getString(if (isPlaying) R.string.cd_pause else R.string.cd_play),
        modifier = GlanceModifier.size(buttonSize).clickable(action),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun GhostButton(
    actionStr: String,
    iconRes: Int,
    contentDescriptionRes: Int,
    buttonSize: Dp,
    iconSize: Dp,
    tint: Color = Color.White
) {
    val context = LocalContext.current
    val action = actionRunCallback<ControlActionCallback>(actionParametersOf(ControlActionCallback.ActionKey to actionStr))
    Box(modifier = GlanceModifier.size(buttonSize).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = context.getString(contentDescriptionRes),
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(fixedColorProvider(tint))
        )
    }
}
