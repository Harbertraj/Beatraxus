package com.beatraxus.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
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
    val hasArt: Boolean
)

private object WidgetImageLoader {
    private val cache = LruCache<String, WidgetArtProviders>(15)

    suspend fun loadArt(context: Context, uriString: String): WidgetArtProviders {
        if (uriString.isEmpty()) {
            val defaultRes = ImageProvider(ImageUtils.getDefaultAlbumArtRes())
            return WidgetArtProviders(defaultRes, defaultRes, hasArt = false)
        }

        cache.get(uriString)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }

                if (bitmap != null) {
                    val coverBitmap = scaleBitmap(bitmap, maxDim = 300)
                    val coverProvider = ImageProvider(coverBitmap)

                    val bgBitmap = createFastBlurredBitmap(bitmap, targetSize = 250)
                    val bgProvider = ImageProvider(bgBitmap)

                    val providers = WidgetArtProviders(coverProvider, bgProvider, hasArt = true)
                    cache.put(uriString, providers)
                    providers
                } else {
                    val defaultRes = ImageProvider(ImageUtils.getDefaultAlbumArtRes())
                    WidgetArtProviders(defaultRes, defaultRes, hasArt = false)
                }
            } catch (e: Exception) {
                val defaultRes = ImageProvider(ImageUtils.getDefaultAlbumArtRes())
                WidgetArtProviders(defaultRes, defaultRes, hasArt = false)
            }
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        if (src.width <= maxDim && src.height <= maxDim) return src
        val ratio = src.width.toFloat() / src.height.toFloat()
        val w = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
        val h = if (ratio >= 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun createFastBlurredBitmap(src: Bitmap, targetSize: Int): Bitmap {
        val tinyW = 32
        val tinyH = (32f / (src.width.toFloat() / src.height.toFloat())).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, tinyW, tinyH, true)
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

            val artProvidersState = produceState<WidgetArtProviders?>(
                initialValue = null,
                key1 = albumArtUri
            ) {
                value = WidgetImageLoader.loadArt(context, albumArtUri)
            }

            val artProviders = artProvidersState.value ?: WidgetArtProviders(
                coverProvider = ImageProvider(ImageUtils.getDefaultAlbumArtRes()),
                bgProvider = ImageProvider(ImageUtils.getDefaultAlbumArtRes()),
                hasArt = false
            )

            WidgetUiContainer(
                sizeCategory = defaultSizeCategory,
                state = WidgetState(
                    title = title,
                    artist = artist,
                    isPlaying = isPlaying,
                    albumArtUri = albumArtUri,
                    shuffleOn = shuffleOn,
                    repeatMode = repeatMode,
                    progressRatio = progressRatio
                ),
                artProviders = artProviders
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
        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            action = actionStr
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MusicWidgets", "Foreground service start failed for $actionStr: ${e.message}", e)
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                Log.e("MusicWidgets", "Fallback startService also failed: ${e2.message}", e2)
            }
        }
    }

    companion object {
        val ActionKey = ActionParameters.Key<String>("action")
    }
}

private fun GlanceModifier.appWidgetBackgroundRadius(): GlanceModifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        this.cornerRadius(16.dp)
    }
}

private fun GlanceModifier.appWidgetInnerRadius(): GlanceModifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.cornerRadius(android.R.dimen.system_app_widget_inner_radius)
    } else {
        this.cornerRadius(12.dp)
    }
}

@Composable
private fun WidgetUiContainer(
    sizeCategory: WidgetSize,
    state: WidgetState,
    artProviders: WidgetArtProviders
) {
    val context = LocalContext.current
    val openNowPlayingIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        putExtra("open_now_playing", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    val textColor = fixedColorProvider(Color.White)
    val subTextColor = fixedColorProvider(Color(0xFFE0E0E0))

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.Transparent)
            .appWidgetBackgroundRadius()
            .clickable(actionStartActivity(openNowPlayingIntent))
    ) {
        if (artProviders.hasArt) {
            Image(
                provider = artProviders.bgProvider,
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize().appWidgetBackgroundRadius(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(fixedColorProvider(Color.Black.copy(alpha = 0.45f)))
                    .appWidgetBackgroundRadius()
            ) {}
        } else {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(fixedColorProvider(Color(0xFF1C1B1F)))
                    .appWidgetBackgroundRadius()
            ) {}
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (sizeCategory) {
                    WidgetSize.SMALL -> SmallWidgetContent(state, artProviders, textColor, subTextColor)
                    WidgetSize.MEDIUM -> MediumWidgetContent(state, artProviders, textColor, subTextColor)
                    WidgetSize.LARGE -> LargeWidgetContent(state, artProviders, textColor, subTextColor)
                }
            }

            if (state.progressRatio > 0f && sizeCategory != WidgetSize.SMALL) {
                Spacer(GlanceModifier.height(4.dp))
                ProgressBar(progress = state.progressRatio)
            }
        }
    }
}

@Composable
private fun SmallWidgetContent(
    state: WidgetState,
    artProviders: WidgetArtProviders,
    textColor: ColorProvider,
    subTextColor: ColorProvider
) {
    val size = LocalSize.current
    val showExtraControls = size.width >= 200.dp

    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = artProviders.coverProvider,
            contentDescription = null,
            modifier = GlanceModifier.size(48.dp).appWidgetInnerRadius(),
            contentScale = ContentScale.Crop
        )

        Spacer(GlanceModifier.width(10.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = if (state.title.isNotBlank()) state.title else "Not Playing",
                style = TextStyle(
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                maxLines = 1
            )
            Text(
                text = if (state.title == "Not Playing" || state.artist.isEmpty()) "Tap to play" else state.artist,
                style = TextStyle(
                    color = subTextColor,
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }

        Spacer(GlanceModifier.width(6.dp))

        if (showExtraControls) {
            ControlButton(
                actionStr = AudioPlaybackService.ACTION_PREVIOUS,
                iconRes = R.drawable.ic_skip_previous,
                contentDescriptionRes = R.string.cd_previous,
                buttonSize = 36.dp,
                iconSize = 20.dp
            )
            PlayPauseButton(isPlaying = state.isPlaying, buttonSize = 40.dp, iconSize = 24.dp)
            ControlButton(
                actionStr = AudioPlaybackService.ACTION_NEXT,
                iconRes = R.drawable.ic_skip_next,
                contentDescriptionRes = R.string.cd_next,
                buttonSize = 36.dp,
                iconSize = 20.dp
            )
        } else {
            PlayPauseButton(isPlaying = state.isPlaying, buttonSize = 44.dp, iconSize = 28.dp)
        }
    }
}

@Composable
private fun MediumWidgetContent(
    state: WidgetState,
    artProviders: WidgetArtProviders,
    textColor: ColorProvider,
    subTextColor: ColorProvider
) {
    val size = LocalSize.current
    val accentColor = Color(0xFFD0BCFF)
    val showShuffleRepeat = size.width >= 280.dp

    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = artProviders.coverProvider,
            contentDescription = null,
            modifier = GlanceModifier.size(56.dp).appWidgetInnerRadius(),
            contentScale = ContentScale.Crop
        )

        Spacer(GlanceModifier.width(10.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = if (state.title.isNotBlank()) state.title else "Not Playing",
                style = TextStyle(
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                maxLines = 1
            )
            Text(
                text = if (state.title == "Not Playing" || state.artist.isEmpty()) "Tap to play" else state.artist,
                style = TextStyle(
                    color = subTextColor,
                    fontSize = 13.sp
                ),
                maxLines = 1
            )
        }

        Spacer(GlanceModifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showShuffleRepeat) {
                ControlButton(
                    actionStr = AudioPlaybackService.ACTION_TOGGLE_SHUFFLE,
                    iconRes = R.drawable.ic_shuffle,
                    contentDescriptionRes = R.string.cd_shuffle,
                    tint = if (state.shuffleOn) accentColor else Color.White,
                    buttonSize = 38.dp,
                    iconSize = 20.dp
                )
            }

            ControlButton(
                actionStr = AudioPlaybackService.ACTION_PREVIOUS,
                iconRes = R.drawable.ic_skip_previous,
                contentDescriptionRes = R.string.cd_previous,
                buttonSize = 38.dp,
                iconSize = 22.dp
            )

            PlayPauseButton(isPlaying = state.isPlaying, buttonSize = 44.dp, iconSize = 28.dp)

            ControlButton(
                actionStr = AudioPlaybackService.ACTION_NEXT,
                iconRes = R.drawable.ic_skip_next,
                contentDescriptionRes = R.string.cd_next,
                buttonSize = 38.dp,
                iconSize = 22.dp
            )

            if (showShuffleRepeat) {
                val repeatTint = when (state.repeatMode) {
                    "ALL" -> accentColor
                    "ONE" -> Color(0xFFAEC6FF)
                    else -> Color.White
                }
                ControlButton(
                    actionStr = AudioPlaybackService.ACTION_TOGGLE_REPEAT,
                    iconRes = R.drawable.ic_repeat,
                    contentDescriptionRes = R.string.cd_repeat,
                    tint = repeatTint,
                    buttonSize = 38.dp,
                    iconSize = 20.dp
                )
            }
        }
    }
}

@Composable
private fun LargeWidgetContent(
    state: WidgetState,
    artProviders: WidgetArtProviders,
    textColor: ColorProvider,
    subTextColor: ColorProvider
) {
    val size = LocalSize.current
    val accentColor = Color(0xFFD0BCFF)
    val coverSize: Dp = if (size.height < 200.dp) 64.dp else 90.dp
    val verticalSpacer: Dp = if (size.height < 200.dp) 4.dp else 10.dp

    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = artProviders.coverProvider,
            contentDescription = null,
            modifier = GlanceModifier.size(coverSize).appWidgetInnerRadius(),
            contentScale = ContentScale.Crop
        )

        Spacer(GlanceModifier.height(verticalSpacer))

        Text(
            text = if (state.title.isNotBlank()) state.title else "Not Playing",
            style = TextStyle(
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            ),
            maxLines = 1
        )
        Text(
            text = if (state.title == "Not Playing" || state.artist.isEmpty()) "Tap to play" else state.artist,
            style = TextStyle(
                color = subTextColor,
                fontSize = 13.sp
            ),
            maxLines = 1
        )

        Spacer(GlanceModifier.height(verticalSpacer))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ControlButton(
                actionStr = AudioPlaybackService.ACTION_TOGGLE_SHUFFLE,
                iconRes = R.drawable.ic_shuffle,
                contentDescriptionRes = R.string.cd_shuffle,
                tint = if (state.shuffleOn) accentColor else Color.White,
                buttonSize = 40.dp,
                iconSize = 22.dp
            )
            Spacer(GlanceModifier.width(8.dp))
            ControlButton(
                actionStr = AudioPlaybackService.ACTION_PREVIOUS,
                iconRes = R.drawable.ic_skip_previous,
                contentDescriptionRes = R.string.cd_previous,
                buttonSize = 40.dp,
                iconSize = 24.dp
            )
            PlayPauseButton(isPlaying = state.isPlaying, buttonSize = 48.dp, iconSize = 30.dp)
            ControlButton(
                actionStr = AudioPlaybackService.ACTION_NEXT,
                iconRes = R.drawable.ic_skip_next,
                contentDescriptionRes = R.string.cd_next,
                buttonSize = 40.dp,
                iconSize = 24.dp
            )
            Spacer(GlanceModifier.width(8.dp))

            val repeatTint = when (state.repeatMode) {
                "ALL" -> accentColor
                "ONE" -> Color(0xFFAEC6FF)
                else -> Color.White
            }
            ControlButton(
                actionStr = AudioPlaybackService.ACTION_TOGGLE_REPEAT,
                iconRes = R.drawable.ic_repeat,
                contentDescriptionRes = R.string.cd_repeat,
                tint = repeatTint,
                buttonSize = 40.dp,
                iconSize = 22.dp
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 32.dp
) {
    val context = LocalContext.current
    val action = actionRunCallback<ControlActionCallback>(
        actionParametersOf(ControlActionCallback.ActionKey to AudioPlaybackService.ACTION_PLAY_PAUSE)
    )
    val contentDescription = if (isPlaying) {
        context.getString(R.string.cd_pause)
    } else {
        context.getString(R.string.cd_play)
    }

    Box(
        modifier = GlanceModifier
            .size(buttonSize)
            .clickable(action),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(fixedColorProvider(Color.White))
        )
    }
}

@Composable
private fun ControlButton(
    actionStr: String,
    iconRes: Int,
    contentDescriptionRes: Int,
    tint: Color = Color.White,
    buttonSize: Dp = 44.dp,
    iconSize: Dp = 24.dp
) {
    val context = LocalContext.current
    val action = actionRunCallback<ControlActionCallback>(
        actionParametersOf(ControlActionCallback.ActionKey to actionStr)
    )
    Box(
        modifier = GlanceModifier
            .size(buttonSize)
            .clickable(action),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = context.getString(contentDescriptionRes),
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(fixedColorProvider(tint))
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val clamped = progress.coerceIn(0f, 1f)
    val inactiveWeight = (1f - clamped).coerceAtLeast(0.001f)

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(3.dp)
            .background(fixedColorProvider(Color.White.copy(alpha = 0.25f)))
            .cornerRadius(2.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .background(fixedColorProvider(Color(0xFFD0BCFF)))
                    .cornerRadius(2.dp)
            ) {}
            if (clamped < 1f) {
                Spacer(modifier = GlanceModifier.width((100 * inactiveWeight).dp))
            }
        }
    }
}

private fun fixedColorProvider(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}
