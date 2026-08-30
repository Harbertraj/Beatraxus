package com.beatraxus.app.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.beatraxus.app.viewmodel.VideoAspectRatio
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import com.beatraxus.app.viewmodel.VideoPlayerViewModel
import com.beatraxus.app.viewmodel.VideoTrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs

enum class GestureType {
    NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // Gesture States
    var gestureType by remember { mutableStateOf(GestureType.NONE) }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }
    var doubleTapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var doubleTapRippleText by remember { mutableStateOf("") }

    // Error handling
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Log.e("VideoPlayerScreen", "Player error: $it")
            onBack()
        }
    }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, uiState.isPlaying) {
        if (controlsVisible && uiState.isPlaying && !uiState.isLocked) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Gesture overlay timeout
    LaunchedEffect(showGestureOverlay) {
        if (showGestureOverlay) {
            delay(800)
            showGestureOverlay = false
            gestureType = GestureType.NONE
        }
    }

    // Ripple timeout
    LaunchedEffect(doubleTapRipplePos) {
        if (doubleTapRipplePos != null) {
            delay(600)
            doubleTapRipplePos = null
        }
    }

    // Keep screen on
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler {
        if (showSettingsSheet) {
            showSettingsSheet = false
        } else if (uiState.isLocked) {
            // Locked
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(uiState.isLocked) {
                if (uiState.isLocked) return@pointerInput
                
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var dragStarted = false
                    var initialDragIntent = GestureType.NONE
                    var cumulativeChange = Offset.Zero
                    
                    val longPressJob = scope.launch {
                        delay(500)
                        if (!dragStarted) {
                            isFastForwarding = true
                            viewModel.setPlaybackSpeed(2.0f)
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            // Release
                            longPressJob.cancel()
                            if (isFastForwarding) {
                                isFastForwarding = false
                                viewModel.setPlaybackSpeed(uiState.playbackSpeed)
                            }
                            
                            if (initialDragIntent == GestureType.SEEK) {
                                viewModel.seekTo(uiState.currentPosition + gestureValue.toLong() * 1000)
                            }
                            break
                        }

                        if (event.changes.size > 1) {
                            initialDragIntent = GestureType.ZOOM
                            dragStarted = true
                        }

                        val change = event.changes.first()
                        cumulativeChange += (change.position - change.previousPosition)
                        
                        if (!dragStarted && cumulativeChange.getDistance() > 10.dp.toPx()) {
                            dragStarted = true
                            longPressJob.cancel()
                            
                            initialDragIntent = if (abs(cumulativeChange.x) > abs(cumulativeChange.y)) {
                                GestureType.SEEK
                            } else {
                                val isLeftThird = firstDown.position.x < size.width / 3
                                val isRightThird = firstDown.position.x > size.width * 2 / 3
                                when {
                                    isLeftThird -> GestureType.BRIGHTNESS
                                    isRightThird -> GestureType.VOLUME
                                    else -> GestureType.NONE
                                }
                            }
                        }

                        if (dragStarted) {
                            gestureType = initialDragIntent
                            showGestureOverlay = true
                            
                            val delta = change.position - change.previousPosition
                            when (initialDragIntent) {
                                GestureType.BRIGHTNESS -> {
                                    val activity = context as? Activity
                                    val params = activity?.window?.attributes
                                    val current = if (params?.screenBrightness ?: -1f < 0) 0.5f else params!!.screenBrightness
                                    val next = (current - delta.y / size.height).coerceIn(0f, 1f)
                                    params?.screenBrightness = next
                                    activity?.window?.attributes = params
                                    gestureValue = next * 100
                                }
                                GestureType.VOLUME -> {
                                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val next = (current - (delta.y / size.height) * max).coerceIn(0f, max.toFloat())
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next.toInt(), 0)
                                    gestureValue = (next / max) * 100
                                }
                                GestureType.SEEK -> {
                                    val deltaSeconds = (cumulativeChange.x / size.width) * 60f
                                    gestureValue = deltaSeconds
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
            .pointerInput(uiState.isLocked) {
                if (uiState.isLocked) return@pointerInput
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val isLeft = offset.x < size.width / 2
                        val delta = if (isLeft) -10000L else 10000L
                        viewModel.seekTo(uiState.currentPosition + delta)
                        doubleTapRipplePos = offset
                        doubleTapRippleText = if (isLeft) "-10s" else "+10s"
                    }
                )
            }
            .pointerInput(uiState.isLocked) {
                if (uiState.isLocked) return@pointerInput
                detectTransformGestures { _, _, zoom, _ ->
                    zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                }
            }
    ) {
        // Player View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.getPlayer()
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.resizeMode = when (uiState.aspectRatio) {
                    VideoAspectRatio.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoAspectRatio.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    VideoAspectRatio.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale
                )
        )

        // Ripple Animation Layer
        doubleTapRipplePos?.let { pos ->
            DoubleTapRipple(pos, doubleTapRippleText)
        }

        // Gesture Overlays
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = showGestureOverlay,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                GestureOverlay(type = gestureType, value = gestureValue)
            }

            AnimatedVisibility(
                visible = isFastForwarding,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)
            ) {
                Surface(
                    color = Color.Black.copy(0.6f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("2X Speed", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // UI Layer
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!uiState.isLocked) {
                    // Top Bar
                    PlayerTopBar(
                        title = uiState.currentVideo?.title ?: "",
                        isHdr = uiState.isHdr,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    // Bottom Bar
                    PlayerBottomBar(
                        uiState = uiState,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onSeek = { viewModel.seekTo(it) },
                        onLock = { viewModel.toggleLock() },
                        onSettings = { showSettingsSheet = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else {
                    // Lock Icon only
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(24.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleLock() },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.Black.copy(0.4f), RoundedCornerShape(28.dp))
                        ) {
                            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = Color(0xFF1A1A1A),
            scrimColor = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            VideoSettingsSheetContent(
                uiState = uiState,
                onSpeedSelect = { viewModel.setPlaybackSpeed(it) },
                onAspectRatioSelect = { viewModel.setAspectRatio(it) },
                onAudioTrackSelect = { viewModel.selectAudioTrack(it) },
                onSubtitleTrackSelect = { viewModel.selectSubtitleTrack(it) }
            )
        }
    }
}

@Composable
fun GestureOverlay(type: GestureType, value: Float) {
    Surface(
        color = Color.Black.copy(0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.size(120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val icon = when (type) {
                GestureType.BRIGHTNESS -> Icons.Rounded.WbSunny
                GestureType.VOLUME -> {
                    when {
                        value <= 0 -> Icons.Rounded.VolumeOff
                        value < 50 -> Icons.Rounded.VolumeDown
                        else -> Icons.Rounded.VolumeUp
                    }
                }
                GestureType.SEEK -> if (value >= 0) Icons.Rounded.Forward10 else Icons.Rounded.Replay10
                else -> Icons.Rounded.TouchApp
            }
            
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (type) {
                    GestureType.BRIGHTNESS, GestureType.VOLUME -> "${value.toInt()}%"
                    GestureType.SEEK -> "${if (value >= 0) "+" else ""}${value.toInt()}s"
                    else -> ""
                },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DoubleTapRipple(offset: Offset, text: String) {
    val alpha = remember { Animatable(0.6f) }
    val scale = remember { Animatable(0.8f) }

    LaunchedEffect(offset) {
        launch {
            alpha.animateTo(0f, animationSpec = tween(600))
        }
        launch {
            scale.animateTo(1.5f, animationSpec = tween(600))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.x.toInt() - 50, offset.y.toInt() - 50) }
                .size(100.dp)
                .graphicsLayer(scaleX = scale.value, scaleY = scale.value, alpha = alpha.value)
                .background(Color.White.copy(0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}

@Composable
fun PlayerTopBar(
    title: String,
    isHdr: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(0.7f), Color.Transparent)
                )
            )
            .padding(top = 40.dp, bottom = 20.dp, start = 8.dp, end = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isHdr) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFFFFD54F).copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "HDR",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    uiState: VideoPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(0.7f))
                )
            )
            .padding(bottom = 40.dp, top = 20.dp, start = 16.dp, end = 16.dp)
    ) {
        Column {
            // Seek bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(uiState.currentPosition),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = uiState.currentPosition.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..uiState.duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(0.3f)
                    )
                )
                Text(
                    text = formatTime(uiState.duration),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onLock) {
                    Icon(if (uiState.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = Color.White.copy(0.8f))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.MoreVert, null, tint = Color.White.copy(0.8f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSettingsSheetContent(
    uiState: VideoPlayerUiState,
    onSpeedSelect: (Float) -> Unit,
    onAspectRatioSelect: (VideoAspectRatio) -> Unit,
    onAudioTrackSelect: (VideoTrackInfo) -> Unit,
    onSubtitleTrackSelect: (VideoTrackInfo?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding()
    ) {
        Text("Playback Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // Audio Tracks
        if (uiState.availableAudioTracks.size > 1) {
            SettingSectionHeader("Audio Track")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.availableAudioTracks) { track: VideoTrackInfo ->
                    val isDolby = track.format == MimeTypes.AUDIO_E_AC3 || track.format == MimeTypes.AUDIO_AC3
                    FilterChip(
                        selected = track.isSelected,
                        onClick = { onAudioTrackSelect(track) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(track.name)
                                if (isDolby) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFFFD54F).copy(alpha = 0.9f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            "DOLBY",
                                            color = Color.Black,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Subtitle Tracks
        SettingSectionHeader("Subtitles")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = uiState.availableSubtitleTracks.none { it.isSelected },
                    onClick = { onSubtitleTrackSelect(null) },
                    label = { Text("None") }
                )
            }
            items(uiState.availableSubtitleTracks) { track: VideoTrackInfo ->
                FilterChip(
                    selected = track.isSelected,
                    onClick = { onSubtitleTrackSelect(track) },
                    label = { Text(track.name) }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Playback Speed
        SettingSectionHeader("Speed")
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(speeds) { speed: Float ->
                FilterChip(
                    selected = uiState.playbackSpeed == speed,
                    onClick = { onSpeedSelect(speed) },
                    label = { Text("${speed}x") }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Aspect Ratio
        SettingSectionHeader("Aspect Ratio")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VideoAspectRatio.entries) { ratio ->
                FilterChip(
                    selected = uiState.aspectRatio == ratio,
                    onClick = { onAspectRatioSelect(ratio) },
                    label = { Text(ratio.name) }
                )
            }
        }
    }
}

@Composable
private fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White.copy(0.5f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
