package com.beatraxus.app.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatraxus.app.subtitles.api.SubtitleApiClientFactory
import com.beatraxus.app.subtitles.data.SubtitleAuthManager
import com.beatraxus.app.subtitles.data.SubtitleCredentialsStore
import com.beatraxus.app.subtitles.data.SubtitleRepositoryImpl
import com.beatraxus.app.subtitles.data.SubtitleStyle
import com.beatraxus.app.subtitles.data.SubtitleStylePrefs
import com.beatraxus.app.subtitles.domain.SubtitlePositionPreset
import com.beatraxus.app.subtitles.player.Media3SubtitlePlayerController
import com.beatraxus.app.subtitles.ui.MxFilterChip
import com.beatraxus.app.subtitles.ui.SubtitleSettingsSection
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModelFactory
import com.beatraxus.app.util.VideoOrientationMode
import com.beatraxus.app.util.VideoPlayerOptions
import com.beatraxus.app.util.VideoPlayerPrefs

// Section names used by the settings back stack. Text after ": " becomes the screen title.
const val VIDEO_SETTINGS_SUBTITLES = "Video Settings: Subtitles"
const val VIDEO_SETTINGS_GESTURES = "Video Settings: Gestures & Controls"
const val VIDEO_SETTINGS_PLAYBACK = "Video Settings: Playback & Audio"

private val VsAccent = Color(0xFFFF8F00)

/** Settings -> Video Settings: one entry per area, with every subtitle option living under "Subtitles". */
@Composable
fun VideoSettingsContent(sectionStack: SnapshotStateList<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingMenuItem(
            title = "Subtitles",
            subtitle = "Appearance, position, languages, online search, account, cache",
            icon = Icons.Rounded.Subtitles,
            iconColor = VsAccent,
            onClick = { sectionStack.add(VIDEO_SETTINGS_SUBTITLES) }
        )
        SettingMenuItem(
            title = "Gestures & Controls",
            subtitle = "Swipe gestures, double-tap seek, hold to fast-forward",
            icon = Icons.Rounded.TouchApp,
            iconColor = Color(0xFF26C6DA),
            onClick = { sectionStack.add(VIDEO_SETTINGS_GESTURES) }
        )
        SettingMenuItem(
            title = "Playback & Audio",
            subtitle = "Default speed, resume, auto-play next, orientation, audio language",
            icon = Icons.Rounded.PlayCircle,
            iconColor = Color(0xFF66BB6A),
            onClick = { sectionStack.add(VIDEO_SETTINGS_PLAYBACK) }
        )
        Text(
            "Changes apply the next time a video is opened.",
            color = Color.White.copy(0.45f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Subtitles
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoSubtitlesSettingsContent() {
    val context = LocalContext.current
    val appContext = context.applicationContext as Application
    val prefs = remember { VideoPlayerPrefs.prefs(appContext) }
    var style by remember { mutableStateOf(SubtitleStylePrefs.read(prefs)) }
    var options by remember { mutableStateOf(VideoPlayerPrefs.read(prefs)) }

    fun updateStyle(new: SubtitleStyle) {
        style = new
        SubtitleStylePrefs.write(prefs, new)
    }

    fun updateOptions(new: VideoPlayerOptions) {
        options = new
        VideoPlayerPrefs.write(prefs, new)
    }

    val api = remember { SubtitleApiClientFactory.createApi() }
    val credStore = remember { SubtitleCredentialsStore(appContext) }
    val authManager = remember { SubtitleAuthManager(api, credStore) }
    val repository = remember { SubtitleRepositoryImpl(appContext, api, authManager) }
    val controller = remember { Media3SubtitlePlayerController { null } }

    val subtitleViewModel: SubtitleViewModel = viewModel(
        factory = SubtitleViewModelFactory(
            application = appContext,
            repository = repository,
            authManager = authManager,
            subtitleController = controller,
            credentialsStore = credStore
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        VsSectionTitle("Appearance")
        SubtitlePreview(style)

        VsCard {
            Text("Size", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${style.sizePercent}%", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                Slider(
                    value = style.sizePercent.toFloat(),
                    onValueChange = { updateStyle(style.copy(sizePercent = it.toInt())) },
                    valueRange = 50f..200f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = VsAccent,
                        activeTrackColor = VsAccent,
                        inactiveTrackColor = Color.White.copy(0.2f)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            SubtitleColorRow(
                label = "Text Color",
                currentColor = style.textColor,
                onColorChange = { updateStyle(style.copy(textColor = it)) },
                accent = VsAccent
            )

            VsChoiceRow(
                title = "Edge Style",
                subtitle = null,
                options = listOf(
                    SubtitleStyle.EDGE_NONE to "None",
                    SubtitleStyle.EDGE_OUTLINE to "Outline",
                    SubtitleStyle.EDGE_SHADOW to "Shadow"
                ),
                selected = style.edgeType,
                onSelect = { updateStyle(style.copy(edgeType = it)) }
            )

            if (style.edgeType != SubtitleStyle.EDGE_NONE) {
                SubtitleColorRow(
                    label = "Edge Color",
                    currentColor = style.outlineColor,
                    onColorChange = { updateStyle(style.copy(outlineColor = it)) },
                    accent = VsAccent
                )
            }

            VsSwitchRow(
                title = "Bold Text",
                subtitle = null,
                checked = style.bold,
                onCheckedChange = { updateStyle(style.copy(bold = it)) }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bg Opacity", color = Color.White.copy(0.7f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                Slider(
                    value = style.backgroundOpacity,
                    onValueChange = { updateStyle(style.copy(backgroundOpacity = it)) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = VsAccent, activeTrackColor = VsAccent)
                )
                Text(
                    "${(style.backgroundOpacity * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.width(36.dp)
                )
            }

            VsChoiceRow(
                title = "Position",
                subtitle = "Where subtitles sit on screen. You can also drag them in the player.",
                options = SubtitlePositionPreset.entries.map { it to it.displayName },
                selected = style.positionPreset,
                onSelect = { updateStyle(style.copy(positionPreset = it)) }
            )

            Button(
                onClick = {
                    SubtitleStylePrefs.reset(prefs)
                    style = SubtitleStylePrefs.read(prefs)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f))
            ) {
                Text("Reset appearance to defaults", color = Color.White)
            }
        }

        VsSectionTitle("Default language")
        VsCard {
            VsChoiceRow(
                title = "Embedded subtitle language",
                subtitle = "Turn on the built-in subtitle track in this language when a video has one. Auto leaves it as it is today.",
                options = VideoPlayerPrefs.LANGUAGE_CHOICES,
                selected = options.preferredSubtitleLanguage,
                onSelect = { updateOptions(options.copy(preferredSubtitleLanguage = it)) }
            )
        }

        VsSectionTitle("Online subtitles & storage")
        SubtitleSettingsSection(subtitleViewModel = subtitleViewModel)
    }
}

@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    val bottomPadding = when (style.positionPreset) {
        SubtitlePositionPreset.BOTTOM -> 10.dp
        SubtitlePositionPreset.LOWER_MIDDLE -> 34.dp
        SubtitlePositionPreset.MIDDLE -> 54.dp
    }
    val previewSp = (14f * style.sizePercent / 100f).coerceIn(8f, 30f)
    val shadow = if (style.edgeType == SubtitleStyle.EDGE_NONE) {
        null
    } else {
        Shadow(color = Color(style.outlineColor), offset = Offset(2f, 2f), blurRadius = 4f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2B3A4A), Color(0xFF0E141B)))),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = "This is how subtitles will look",
            modifier = Modifier
                .padding(bottom = bottomPadding, start = 12.dp, end = 12.dp)
                .background(Color.Black.copy(alpha = style.backgroundOpacity))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = TextStyle(
                color = Color(style.textColor),
                fontSize = previewSp.sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                shadow = shadow
            )
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Gestures & controls
// ---------------------------------------------------------------------------------------------

@Composable
fun VideoGesturesSettingsContent() {
    val context = LocalContext.current
    val prefs = remember { VideoPlayerPrefs.prefs(context) }
    var options by remember { mutableStateOf(VideoPlayerPrefs.read(prefs)) }

    fun update(new: VideoPlayerOptions) {
        options = new
        VideoPlayerPrefs.write(prefs, new)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        VsSectionTitle("Swipe & pinch gestures")
        VsCard {
            VsSwitchRow(
                title = "Brightness swipe",
                subtitle = "Swipe up/down on the left side of the screen",
                checked = options.gestureBrightness,
                onCheckedChange = { update(options.copy(gestureBrightness = it)) }
            )
            VsSwitchRow(
                title = "Volume swipe",
                subtitle = "Swipe up/down on the right side of the screen",
                checked = options.gestureVolume,
                onCheckedChange = { update(options.copy(gestureVolume = it)) }
            )
            VsSwitchRow(
                title = "Swipe to seek",
                subtitle = "Drag left/right to move through the video",
                checked = options.gestureSeek,
                onCheckedChange = { update(options.copy(gestureSeek = it)) }
            )
            VsSwitchRow(
                title = "Pinch to zoom",
                subtitle = "Two fingers to zoom the picture in and out",
                checked = options.gestureZoom,
                onCheckedChange = { update(options.copy(gestureZoom = it)) }
            )
            VsSwitchRow(
                title = "Swipe to move subtitles",
                subtitle = "Swipe up/down in the middle of the screen",
                checked = options.gestureSubtitleMove,
                onCheckedChange = { update(options.copy(gestureSubtitleMove = it)) }
            )
        }

        VsSectionTitle("Double tap")
        VsCard {
            VsChoiceRow(
                title = "Double-tap seek",
                subtitle = "How far the left and right edges jump",
                options = VideoPlayerPrefs.DOUBLE_TAP_SECONDS_CHOICES.map { it to "${it}s" },
                selected = options.doubleTapSeekSeconds,
                onSelect = { update(options.copy(doubleTapSeekSeconds = it)) }
            )
            VsSwitchRow(
                title = "Double-tap centre to play / pause",
                subtitle = null,
                checked = options.doubleTapPlayPause,
                onCheckedChange = { update(options.copy(doubleTapPlayPause = it)) }
            )
        }

        VsSectionTitle("Hold to fast-forward")
        VsCard {
            VsSwitchRow(
                title = "Press and hold to speed up",
                subtitle = "Playback returns to normal speed when you let go",
                checked = options.holdToSpeedEnabled,
                onCheckedChange = { update(options.copy(holdToSpeedEnabled = it)) }
            )
            if (options.holdToSpeedEnabled) {
                VsChoiceRow(
                    title = "Hold speed",
                    subtitle = null,
                    options = VideoPlayerPrefs.HOLD_SPEED_CHOICES.map { it to "${VideoPlayerPrefs.formatSpeed(it)}x" },
                    selected = options.holdSpeed,
                    onSelect = { update(options.copy(holdSpeed = it)) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Playback & audio
// ---------------------------------------------------------------------------------------------

@Composable
fun VideoPlaybackSettingsContent() {
    val context = LocalContext.current
    val prefs = remember { VideoPlayerPrefs.prefs(context) }
    var options by remember { mutableStateOf(VideoPlayerPrefs.read(prefs)) }

    fun update(new: VideoPlayerOptions) {
        options = new
        VideoPlayerPrefs.write(prefs, new)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        VsSectionTitle("Playback")
        VsCard {
            VsChoiceRow(
                title = "Default speed",
                subtitle = "Speed every video starts at",
                options = VideoPlayerPrefs.DEFAULT_SPEED_CHOICES.map { it to "${VideoPlayerPrefs.formatSpeed(it)}x" },
                selected = options.defaultSpeed,
                onSelect = { update(options.copy(defaultSpeed = it)) }
            )
            VsSwitchRow(
                title = "Resume from last position",
                subtitle = "Continue a video where you left off",
                checked = options.resumePlayback,
                onCheckedChange = { update(options.copy(resumePlayback = it)) }
            )
            VsSwitchRow(
                title = "Play next video automatically",
                subtitle = "When off, playback pauses at the end of each video",
                checked = options.autoPlayNext,
                onCheckedChange = { update(options.copy(autoPlayNext = it)) }
            )
        }

        VsSectionTitle("Screen")
        VsCard {
            VsChoiceRow(
                title = "Screen orientation",
                subtitle = null,
                options = VideoOrientationMode.entries.map { it to it.label },
                selected = options.orientationMode,
                onSelect = { update(options.copy(orientationMode = it)) }
            )
            VsSwitchRow(
                title = "Keep screen on",
                subtitle = "Stop the display from sleeping while the player is open",
                checked = options.keepScreenOn,
                onCheckedChange = { update(options.copy(keepScreenOn = it)) }
            )
        }

        VsSectionTitle("Audio")
        VsCard {
            VsChoiceRow(
                title = "Preferred audio language",
                subtitle = "Pick this audio track when a video has it. Auto uses the file's default track.",
                options = VideoPlayerPrefs.LANGUAGE_CHOICES,
                selected = options.preferredAudioLanguage,
                onSelect = { update(options.copy(preferredAudioLanguage = it)) }
            )
            VsSwitchRow(
                title = "Pause when headphones disconnect",
                subtitle = "Also covers unplugging wired or Bluetooth audio",
                checked = options.pauseOnHeadsetUnplug,
                onCheckedChange = { update(options.copy(pauseOnHeadsetUnplug = it)) }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun VsSectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun VsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(0.06f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
    }
}

@Composable
private fun VsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = Color.White.copy(0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = VsAccent, checkedTrackColor = VsAccent.copy(0.4f))
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> VsChoiceRow(
    title: String,
    subtitle: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Text(subtitle, color = Color.White.copy(0.5f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { (value, label) ->
                MxFilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = label,
                    fontSize = 12.sp
                )
            }
        }
    }
}
