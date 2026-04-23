package com.beatflowy.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.beatflowy.app.MainActivity
import com.beatflowy.app.R
import com.beatflowy.app.service.AudioPlaybackService
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
}

class MusicWidgetSmall : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val albumArtUri = prefs[MusicWidgetKeys.ALBUM_ART_URI] ?: ""
        val artProvider = getImageProvider(context, albumArtUri)
        val hasArt = albumArtUri.isNotEmpty()

        provideContent {
            val title = prefs[MusicWidgetKeys.TITLE] ?: "Not Playing"
            val artist = prefs[MusicWidgetKeys.ARTIST] ?: "Beatraxus"
            val isPlaying = prefs[MusicWidgetKeys.IS_PLAYING] ?: false
            val shuffleOn = prefs[MusicWidgetKeys.SHUFFLE_ON] ?: false
            val repeatMode = prefs[MusicWidgetKeys.REPEAT_MODE] ?: "OFF"
            
            WidgetContent(WidgetSize.SMALL, title, artist, isPlaying, artProvider, hasArt, shuffleOn, repeatMode)
        }
    }
    
    companion object {
        val KEY_TITLE = MusicWidgetKeys.TITLE
        val KEY_ARTIST = MusicWidgetKeys.ARTIST
        val KEY_IS_PLAYING = MusicWidgetKeys.IS_PLAYING
        val KEY_ALBUM_ART_URI = MusicWidgetKeys.ALBUM_ART_URI
    }
}

class MusicWidgetMedium : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val albumArtUri = prefs[MusicWidgetKeys.ALBUM_ART_URI] ?: ""
        val artProvider = getImageProvider(context, albumArtUri)
        val hasArt = albumArtUri.isNotEmpty()

        provideContent {
            val title = prefs[MusicWidgetKeys.TITLE] ?: "Not Playing"
            val artist = prefs[MusicWidgetKeys.ARTIST] ?: "Beatraxus"
            val isPlaying = prefs[MusicWidgetKeys.IS_PLAYING] ?: false
            val shuffleOn = prefs[MusicWidgetKeys.SHUFFLE_ON] ?: false
            val repeatMode = prefs[MusicWidgetKeys.REPEAT_MODE] ?: "OFF"
            
            WidgetContent(WidgetSize.MEDIUM, title, artist, isPlaying, artProvider, hasArt, shuffleOn, repeatMode)
        }
    }
    
    companion object {
        val KEY_TITLE = MusicWidgetKeys.TITLE
        val KEY_ARTIST = MusicWidgetKeys.ARTIST
        val KEY_IS_PLAYING = MusicWidgetKeys.IS_PLAYING
        val KEY_ALBUM_ART_URI = MusicWidgetKeys.ALBUM_ART_URI
    }
}

class MusicWidgetLarge : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val albumArtUri = prefs[MusicWidgetKeys.ALBUM_ART_URI] ?: ""
        val artProvider = getImageProvider(context, albumArtUri)
        val hasArt = albumArtUri.isNotEmpty()

        provideContent {
            val title = prefs[MusicWidgetKeys.TITLE] ?: "Not Playing"
            val artist = prefs[MusicWidgetKeys.ARTIST] ?: "Beatraxus"
            val isPlaying = prefs[MusicWidgetKeys.IS_PLAYING] ?: false
            val shuffleOn = prefs[MusicWidgetKeys.SHUFFLE_ON] ?: false
            val repeatMode = prefs[MusicWidgetKeys.REPEAT_MODE] ?: "OFF"
            
            WidgetContent(WidgetSize.LARGE, title, artist, isPlaying, artProvider, hasArt, shuffleOn, repeatMode)
        }
    }
    
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
        context.startForegroundService(intent)
    }

    companion object {
        val ActionKey = ActionParameters.Key<String>("action")
    }
}

@Composable
private fun WidgetContent(
    size: WidgetSize,
    title: String,
    artist: String,
    isPlaying: Boolean,
    artProvider: ImageProvider,
    hasArt: Boolean,
    shuffleOn: Boolean,
    repeatMode: String
) {
    val textColor = fixedColorProvider(Color.White)
    val subTextColor = fixedColorProvider(Color.LightGray)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.Transparent)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        // Dynamic Background using Album Art
        if (hasArt) {
            Image(
                provider = artProvider,
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                contentScale = ContentScale.Crop
            )
            // Dark overlay for readability
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(fixedColorProvider(Color.Black.copy(alpha = 0.5f)))
                    .cornerRadius(16.dp)
            ) {}
        } else {
            // Semi-transparent dark background for fallback
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(fixedColorProvider(Color(0xFF1C1B1F).copy(alpha = 0.7f)))
                    .cornerRadius(16.dp)
            ) {}
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            when (size) {
                WidgetSize.SMALL -> SmallWidgetContent(title, isPlaying, artProvider, textColor, subTextColor)
                WidgetSize.MEDIUM -> MediumWidgetContent(title, artist, isPlaying, artProvider, textColor, subTextColor, shuffleOn, repeatMode)
                WidgetSize.LARGE -> LargeWidgetContent(title, artist, isPlaying, artProvider, textColor, subTextColor, shuffleOn, repeatMode)
            }
        }
    }
}

@Composable
private fun SmallWidgetContent(title: String, isPlaying: Boolean, artProvider: ImageProvider, textColor: ColorProvider, subTextColor: ColorProvider) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = artProvider,
            contentDescription = null,
            modifier = GlanceModifier.size(48.dp).cornerRadius(8.dp),
            contentScale = ContentScale.Crop
        )
        
        Spacer(GlanceModifier.width(12.dp))
        
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = title, 
                style = TextStyle(color = textColor, fontWeight = FontWeight.Bold), 
                maxLines = 1
            )
            Text(
                text = if (isPlaying) "Playing" else "Paused", 
                style = TextStyle(color = subTextColor), 
                maxLines = 1
            )
        }
        
        PlayPauseButton(isPlaying)
    }
}

@Composable
private fun MediumWidgetContent(
    title: String, 
    artist: String, 
    isPlaying: Boolean, 
    artProvider: ImageProvider, 
    textColor: ColorProvider, 
    subTextColor: ColorProvider,
    shuffleOn: Boolean,
    repeatMode: String
) {
    val accentColor = Color(0xFFD0BCFF)
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = artProvider,
            contentDescription = null,
            modifier = GlanceModifier.size(64.dp).cornerRadius(12.dp),
            contentScale = ContentScale.Crop
        )
        
        Spacer(GlanceModifier.width(12.dp))
        
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = title, 
                style = TextStyle(color = textColor, fontWeight = FontWeight.Bold), 
                maxLines = 1
            )
            Text(
                text = artist, 
                style = TextStyle(color = subTextColor), 
                maxLines = 1
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(
                AudioPlaybackService.ACTION_TOGGLE_SHUFFLE, 
                R.drawable.ic_shuffle,
                tint = if (shuffleOn) accentColor else Color.White
            )
            ControlButton(AudioPlaybackService.ACTION_PREVIOUS, R.drawable.ic_skip_previous)
            PlayPauseButton(isPlaying)
            ControlButton(AudioPlaybackService.ACTION_NEXT, R.drawable.ic_skip_next)
            
            val repeatTint = when (repeatMode) {
                "ALL" -> accentColor
                "ONE" -> Color(0xFFAEC6FF) // Light Blue for ONE
                else -> Color.White
            }
            ControlButton(
                AudioPlaybackService.ACTION_TOGGLE_REPEAT, 
                R.drawable.ic_repeat,
                tint = repeatTint
            )
        }
    }
}

@Composable
private fun LargeWidgetContent(
    title: String,
    artist: String,
    isPlaying: Boolean,
    artProvider: ImageProvider,
    textColor: ColorProvider,
    subTextColor: ColorProvider,
    shuffleOn: Boolean,
    repeatMode: String
) {
    val accentColor = Color(0xFFD0BCFF) // Light purple accent

    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = artProvider,
            contentDescription = null,
            modifier = GlanceModifier.size(100.dp).cornerRadius(16.dp),
            contentScale = ContentScale.Crop
        )
        
        Spacer(GlanceModifier.height(12.dp))
        
        Text(
            text = title, 
            style = TextStyle(color = textColor, fontWeight = FontWeight.Bold), 
            maxLines = 1
        )
        Text(
            text = artist, 
            style = TextStyle(color = subTextColor), 
            maxLines = 1
        )
        
        Spacer(GlanceModifier.height(16.dp))
        
        Row(
            verticalAlignment = Alignment.CenterVertically, 
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ControlButton(
                AudioPlaybackService.ACTION_TOGGLE_SHUFFLE, 
                R.drawable.ic_shuffle,
                tint = if (shuffleOn) accentColor else Color.White
            )
            Spacer(GlanceModifier.width(12.dp))
            ControlButton(AudioPlaybackService.ACTION_PREVIOUS, R.drawable.ic_skip_previous)
            PlayPauseButton(isPlaying)
            ControlButton(AudioPlaybackService.ACTION_NEXT, R.drawable.ic_skip_next)
            Spacer(GlanceModifier.width(12.dp))
            
            val repeatTint = when (repeatMode) {
                "ALL" -> accentColor
                "ONE" -> Color(0xFFAEC6FF) // Light Blue for ONE
                else -> Color.White
            }
            ControlButton(
                AudioPlaybackService.ACTION_TOGGLE_REPEAT, 
                R.drawable.ic_repeat,
                tint = repeatTint
            )
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean) {
    val action = actionRunCallback<ControlActionCallback>(
        actionParametersOf(ControlActionCallback.ActionKey to AudioPlaybackService.ACTION_PLAY_PAUSE)
    )
    
    Box(modifier = GlanceModifier.size(48.dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size(32.dp)
        )
    }
}

@Composable
private fun ControlButton(actionStr: String, iconRes: Int, tint: Color = Color.White) {
    val action = actionRunCallback<ControlActionCallback>(
        actionParametersOf(ControlActionCallback.ActionKey to actionStr)
    )
    Box(modifier = GlanceModifier.size(44.dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(fixedColorProvider(tint))
        )
    }
}

private fun fixedColorProvider(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}

private var lastAlbumArtUri: String? = null
private var lastImageProvider: ImageProvider? = null

private suspend fun getImageProvider(context: Context, uriString: String): ImageProvider {
    if (uriString.isEmpty()) return ImageProvider(R.drawable.ic_album_placeholder)
    
    synchronized(MusicWidgetKeys) {
        if (uriString == lastAlbumArtUri && lastImageProvider != null) {
            return lastImageProvider!!
        }
    }

    return withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    val maxSize = 300
                    val scaled = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val w = if (ratio > 1) maxSize else (maxSize * ratio).toInt()
                        val h = if (ratio > 1) (maxSize / ratio).toInt() else maxSize
                        Bitmap.createScaledBitmap(bitmap, w, h, true)
                    } else bitmap
                    ImageProvider(scaled)
                } else ImageProvider(R.drawable.ic_album_placeholder)
            } ?: ImageProvider(R.drawable.ic_album_placeholder)
            
            synchronized(MusicWidgetKeys) {
                lastAlbumArtUri = uriString
                lastImageProvider = result
            }
            result
        } catch (e: Exception) {
            ImageProvider(R.drawable.ic_album_placeholder)
        }
    }
}
