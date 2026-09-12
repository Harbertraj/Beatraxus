package com.beatraxus.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.beatraxus.app.viewmodel.VideoAspectRatio
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import com.beatraxus.app.viewmodel.VideoPlayerViewModel
import com.beatraxus.app.viewmodel.VideoTrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.beatraxus.app.motionboost.MotionBoostMode
import com.beatraxus.app.motionboost.MotionBoostQuality
import java.util.*
import kotlin.math.abs

enum class GestureType {
    NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM, SUBTITLE
}

enum class PlayerSheetType {
    NONE, AUDIO, SUBTITLE, SPEED, ALL, EQUALIZER, MOTION_BOOST
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
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isInPiP = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode ?: false
    } else false

    // Auto-pause on background
    DisposableEffect(lifecycleOwner, uiState.isBackgroundPlayEnabled, isInPiP) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                // Don't pause if in PiP or if Background Play is enabled
                if (uiState.isPlaying && !uiState.isBackgroundPlayEnabled && !isInPiP) {
                    viewModel.togglePlayPause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var sheetType by remember { mutableStateOf(PlayerSheetType.NONE) }

    // Gesture States
    var gestureType by remember { mutableStateOf(GestureType.NONE) }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }
    var doubleTapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var doubleTapRippleText by remember { mutableStateOf("") }

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

    // Orientation and Fullscreen management
    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        onDispose {
            activity?.requestedOrientation = originalOrientation
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    BackHandler {
        if (sheetType != PlayerSheetType.NONE) {
            sheetType = PlayerSheetType.NONE
        } else if (uiState.isLocked) {
            // Locked
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
                    
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            // Release - hide overlays
                            showGestureOverlay = false
                            gestureType = GestureType.NONE
                            
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
                            
                            initialDragIntent = if (abs(cumulativeChange.x) > abs(cumulativeChange.y)) {
                                GestureType.SEEK
                            } else {
                                val isLeftThird = firstDown.position.x < size.width / 3
                                val isRightThird = firstDown.position.x > size.width * 2 / 3
                                when {
                                    isLeftThird -> GestureType.BRIGHTNESS
                                    isRightThird -> GestureType.VOLUME
                                    else -> GestureType.SUBTITLE
                                }
                            }
                        }

                        if (dragStarted) {
                            gestureType = initialDragIntent
                            // Show overlay for navigation gestures
                            showGestureOverlay = true
                            
                            val delta = change.position - change.previousPosition
                            when (initialDragIntent) {
                                GestureType.BRIGHTNESS -> {
                                    val activity = context as? Activity
                                    val params = activity?.window?.attributes
                                    // Use 0.5f as default if -1f (system default)
                                    val current = if (params?.screenBrightness ?: -1f < 0f) 0.5f else params!!.screenBrightness
                                    // Fix: handle the case where it gets stuck at 0 or doesn't increase
                                    val sensitivity = 1.2f // Slightly higher sensitivity
                                    val next = (current - (delta.y / size.height) * sensitivity).coerceIn(0.01f, 1f)
                                    params?.screenBrightness = next
                                    activity?.window?.attributes = params
                                    gestureValue = next * 100
                                }
                                GestureType.VOLUME -> {
                                    val max = uiState.maxVolume
                                    val current = uiState.volume
                                    val changeVal = -(delta.y / size.height) * max
                                    val next = (current + changeVal).coerceIn(0f, max.toFloat())
                                    viewModel.setVolume(next.toInt())
                                    gestureValue = (next / max) * 100
                                }
                                GestureType.SEEK -> {
                                    val deltaSeconds = (cumulativeChange.x / size.width) * 60f
                                    gestureValue = deltaSeconds
                                }
                                GestureType.SUBTITLE -> {
                                    viewModel.setSubtitleOffset(uiState.subtitleOffset + delta.y)
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
                        val isCenter = offset.x > size.width / 3 && offset.x < size.width * 2 / 3
                        if (isCenter) {
                            viewModel.togglePlayPause()
                        } else {
                            val isLeft = offset.x < size.width / 2
                            val delta = if (isLeft) -10000L else 10000L
                            viewModel.seekTo(uiState.currentPosition + delta)
                            doubleTapRipplePos = offset
                            doubleTapRippleText = if (isLeft) "-10s" else "+10s"
                        }
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
                val player = viewModel.getPlayer() as? androidx.media3.exoplayer.ExoPlayer
                val contentFrame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                
                view.resizeMode = when (uiState.aspectRatio) {
                    VideoAspectRatio.FIT -> {
                        contentFrame?.setAspectRatio(0f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    VideoAspectRatio.FILL -> {
                        contentFrame?.setAspectRatio(0f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                    VideoAspectRatio.ZOOM -> {
                        contentFrame?.setAspectRatio(0f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    VideoAspectRatio.FOUR_THREE -> {
                        contentFrame?.setAspectRatio(4f/3f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    VideoAspectRatio.SIXTEEN_NINE -> {
                        contentFrame?.setAspectRatio(16f/9f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
                
                // Update Subtitle Styles
                val captionStyle = CaptionStyleCompat(
                    uiState.subtitleTextColor,
                    uiState.subtitleBackgroundColor,
                    android.graphics.Color.TRANSPARENT, // windowColor
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    uiState.subtitleOutlineColor,
                    null // typeface
                )
                view.subtitleView?.setApplyEmbeddedStyles(false)
                view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, uiState.subtitleSize)
                view.subtitleView?.setStyle(captionStyle)
                view.subtitleView?.alpha = uiState.subtitleAlpha
                
                // Vertical offset for subtitles
                view.subtitleView?.translationY = uiState.subtitleOffset
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale
                )
        )

        // Motion Boost Overlay
        if (uiState.motionBoostMode != MotionBoostMode.ORIGINAL) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                viewModel.setPresentationSurface(Surface(st))
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                viewModel.setPresentationSurface(null)
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

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

            // Aspect Ratio Overlay
            uiState.aspectRatioMessage?.let { message ->
                Surface(
                    color = Color.Black.copy(0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = message,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
            visible = controlsVisible && sheetType == PlayerSheetType.NONE && !isInPiP,
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
                        onAudioClick = { sheetType = PlayerSheetType.AUDIO },
                        onSubtitleClick = { sheetType = PlayerSheetType.SUBTITLE },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    // Floating Controls
                    FloatingControls(
                        uiState = uiState,
                        sheetType = sheetType,
                        onPiPClick = { activity?.enterPictureInPictureMode() },
                        onSpeedClick = { sheetType = PlayerSheetType.SPEED },
                        onEqClick = { sheetType = PlayerSheetType.EQUALIZER },
                        onToggleBoost = { viewModel.toggleVolumeBoost() },
                        onHeadphonesClick = { viewModel.toggleBackgroundPlay() },
                        onRotationClick = { activity?.let { viewModel.toggleOrientation(it) } },
                        onMotionBoostClick = { sheetType = PlayerSheetType.MOTION_BOOST },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 110.dp)
                    )

                    // Bottom Bar
                    PlayerBottomBar(
                        uiState = uiState,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onPlayNext = { viewModel.playNext() },
                        onPlayPrevious = { viewModel.playPrevious() },
                        onSeek = { viewModel.seekTo(it) },
                        onLock = { viewModel.toggleLock() },
                        onTimeClick = { viewModel.toggleTimeDisplay() },
                        onAspectRatioClick = { 
                            val nextRatio = when (uiState.aspectRatio) {
                                VideoAspectRatio.FIT -> VideoAspectRatio.FILL
                                VideoAspectRatio.FILL -> VideoAspectRatio.ZOOM
                                VideoAspectRatio.ZOOM -> VideoAspectRatio.FOUR_THREE
                                VideoAspectRatio.FOUR_THREE -> VideoAspectRatio.SIXTEEN_NINE
                                VideoAspectRatio.SIXTEEN_NINE -> VideoAspectRatio.FIT
                            }
                            viewModel.setAspectRatio(nextRatio)
                        },
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

    if (sheetType != PlayerSheetType.NONE) {
        ModalBottomSheet(
            onDismissRequest = { sheetType = PlayerSheetType.NONE },
            containerColor = Color(0xFF1A1A1A),
            scrimColor = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            VideoSettingsSheetContent(
                uiState = uiState,
                sheetType = sheetType,
                onSpeedSelect = { viewModel.setPlaybackSpeed(it); sheetType = PlayerSheetType.NONE },
                onAspectRatioSelect = { viewModel.setAspectRatio(it); sheetType = PlayerSheetType.NONE },
                onAudioTrackSelect = { viewModel.selectAudioTrack(it); sheetType = PlayerSheetType.NONE },
                onSubtitleTrackSelect = { viewModel.selectSubtitleTrack(it); sheetType = PlayerSheetType.NONE },
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun FloatingControls(
    uiState: VideoPlayerUiState,
    sheetType: PlayerSheetType,
    onPiPClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onEqClick: () -> Unit,
    onToggleBoost: () -> Unit,
    onHeadphonesClick: () -> Unit,
    onRotationClick: () -> Unit,
    onMotionBoostClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(0.4f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onPiPClick) {
            Icon(Icons.Rounded.PictureInPicture, null, tint = Color.White)
        }
        IconButton(onClick = onSpeedClick) {
            Icon(Icons.Rounded.Speed, null, tint = if (uiState.playbackSpeed != 1.0f) Color(0xFFFF8F00) else Color.White)
        }
        IconButton(onClick = onEqClick) {
            Icon(Icons.Rounded.Equalizer, null, tint = if (uiState.isEqEnabled) Color(0xFFFF8F00) else Color.White)
        }
        IconButton(onClick = onToggleBoost) {
            Icon(if (uiState.isVolumeBoost) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown, null, tint = if (uiState.isVolumeBoost) Color(0xFFFF8F00) else Color.White)
        }
        IconButton(onClick = onHeadphonesClick) {
            val iconColor = if (uiState.isBackgroundPlayEnabled) Color(0xFFFF8F00) else Color.White
            Icon(
                Icons.Rounded.Headset, 
                null, 
                tint = iconColor
            )
        }
        IconButton(onClick = onRotationClick) {
            Icon(Icons.Rounded.ScreenRotation, null, tint = Color.White)
        }
        IconButton(onClick = onMotionBoostClick) {
            Icon(
                Icons.Rounded.SlowMotionVideo,
                null,
                tint = if (uiState.motionBoostMode != MotionBoostMode.ORIGINAL) Color(0xFFFF8F00) else Color.White
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
                GestureType.SUBTITLE -> Icons.Rounded.Subtitles
                else -> Icons.Rounded.TouchApp
            }
            
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (type) {
                    GestureType.BRIGHTNESS, GestureType.VOLUME -> "${value.toInt()}%"
                    GestureType.SEEK -> "${if (value >= 0) "+" else ""}${value.toInt()}s"
                    GestureType.SUBTITLE -> "Move Subtitles"
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
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
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
            IconButton(onClick = onAudioClick) {
                Icon(Icons.Rounded.AudioFile, null, tint = Color.White)
            }
            IconButton(onClick = onSubtitleClick) {
                Icon(Icons.Rounded.Subtitles, null, tint = Color.White)
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    uiState: VideoPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onLock: () -> Unit,
    onTimeClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
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
            .padding(bottom = 20.dp, top = 20.dp, start = 16.dp, end = 16.dp)
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
                val mxOrange = Color(0xFFFF8F00)
                Slider(
                    value = uiState.currentPosition.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..uiState.duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = mxOrange,
                        activeTrackColor = mxOrange,
                        inactiveTrackColor = Color.White.copy(0.3f)
                    )
                )
                Text(
                    text = if (uiState.showTotalTime) {
                        "-${formatTime(uiState.duration - uiState.currentPosition)}"
                    } else {
                        formatTime(uiState.duration)
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onTimeClick() }
                )
            }

            Spacer(Modifier.height(4.dp))

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
                    IconButton(onClick = onPlayPrevious) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            if (uiState.isPlaying) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    IconButton(onClick = onPlayNext) {
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                IconButton(onClick = onAspectRatioClick) {
                    Icon(Icons.Rounded.AspectRatio, null, tint = Color.White.copy(0.8f))
                }
            }
        }
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSettingsSheetContent(
    uiState: VideoPlayerUiState,
    sheetType: PlayerSheetType,
    onSpeedSelect: (Float) -> Unit,
    onAspectRatioSelect: (VideoAspectRatio) -> Unit,
    onAudioTrackSelect: (VideoTrackInfo) -> Unit,
    onSubtitleTrackSelect: (VideoTrackInfo?) -> Unit,
    viewModel: VideoPlayerViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        val mxOrange = Color(0xFFFF8F00)
        
        val title = when (sheetType) {
            PlayerSheetType.AUDIO -> "Audio Tracks"
            PlayerSheetType.SUBTITLE -> "Subtitles"
            PlayerSheetType.SPEED -> "Playback Speed"
            PlayerSheetType.EQUALIZER -> "Premium Equalizer"
            PlayerSheetType.MOTION_BOOST -> "Motion Boost"
            else -> "Settings"
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            
            if (sheetType == PlayerSheetType.EQUALIZER) {
                Switch(
                    checked = uiState.isEqEnabled,
                    onCheckedChange = { viewModel.toggleEqEnabled() },
                    colors = SwitchDefaults.colors(checkedThumbColor = mxOrange, checkedTrackColor = mxOrange.copy(0.4f))
                )
            }
        }
        
        Spacer(Modifier.height(20.dp))

        // Equalizer Section
        if (sheetType == PlayerSheetType.EQUALIZER) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedPreset == "Manual",
                        onClick = { viewModel.setEqPreset(com.beatraxus.app.model.SavedEqPreset("Manual", List(10) { com.beatraxus.app.model.ParametricEqBand(it, true, 1000f, 0f) })) },
                        label = { Text("Manual") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White.copy(0.2f), selectedLabelColor = Color.White)
                    )
                }
                items(uiState.availablePresets) { preset: com.beatraxus.app.model.SavedEqPreset ->
                    FilterChip(
                        selected = uiState.selectedPreset == preset.name,
                        onClick = { viewModel.setEqPreset(preset) },
                        label = { Text(preset.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val bands = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
                uiState.eqGains.forEachIndexed { index, gain ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(44.dp)
                    ) {
                        Text(
                            "${gain.toInt()}",
                            color = if (uiState.isEqEnabled) mxOrange else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(8.dp))
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Slider(
                                value = gain,
                                enabled = uiState.isEqEnabled,
                                onValueChange = { viewModel.setEqGain(index, it) },
                                valueRange = -12f..12f,
                                modifier = Modifier
                                    .requiredWidth(this.maxHeight)
                                    .requiredHeight(this.maxWidth)
                                    .graphicsLayer {
                                        rotationZ = -90f
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                    },
                                colors = SliderDefaults.colors(
                                    thumbColor = mxOrange,
                                    activeTrackColor = mxOrange,
                                    inactiveTrackColor = Color.White.copy(0.1f)
                                )
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            bands[index],
                            color = Color.White.copy(0.4f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Audio Tracks
        if (sheetType == PlayerSheetType.AUDIO || sheetType == PlayerSheetType.ALL) {
            if (uiState.availableAudioTracks.isEmpty()) {
                Text("No audio tracks found", color = Color.Gray, fontSize = 14.sp)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.availableAudioTracks.forEach { track ->
                        val isDolby = track.format == MimeTypes.AUDIO_E_AC3 || track.format == MimeTypes.AUDIO_AC3
                        FilterChip(
                            selected = track.isSelected,
                            onClick = { onAudioTrackSelect(track) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = mxOrange,
                                selectedLabelColor = Color.Black
                            ),
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.name + (track.language?.let { " ($it)" } ?: ""),
                                        color = if (track.isSelected) Color.Black else Color.White
                                    )
                                    if (isDolby) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFFFD54F))
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
            }
        }

        // Subtitle Tracks
        if (sheetType == PlayerSheetType.SUBTITLE || sheetType == PlayerSheetType.ALL) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isNoneSelected = uiState.availableSubtitleTracks.none { it.isSelected }
                FilterChip(
                    selected = isNoneSelected,
                    onClick = { onSubtitleTrackSelect(null) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = mxOrange,
                        selectedLabelColor = Color.Black
                    ),
                    label = { Text("None", color = if (isNoneSelected) Color.Black else Color.White) }
                )
                uiState.availableSubtitleTracks.forEach { track ->
                    FilterChip(
                        selected = track.isSelected,
                        onClick = { onSubtitleTrackSelect(track) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = mxOrange,
                            selectedLabelColor = Color.Black
                        ),
                        label = { Text(track.name + (track.language?.let { " ($it)" } ?: ""), color = if (track.isSelected) Color.Black else Color.White) }
                    )
                }
            }
        }

        // Playback Speed
        if (sheetType == PlayerSheetType.SPEED || sheetType == PlayerSheetType.ALL) {
            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                speeds.forEach { speed ->
                    val isSelected = uiState.playbackSpeed == speed
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpeedSelect(speed) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = mxOrange,
                            selectedLabelColor = Color.Black
                        ),
                        label = { Text("${speed}x", color = if (isSelected) Color.Black else Color.White) }
                    )
                }
            }
        }

        // Motion Boost
        if (sheetType == PlayerSheetType.MOTION_BOOST) {
            MotionBoostSheetContent(uiState, viewModel)
        }

        // Subtitle Style
        if (sheetType == PlayerSheetType.SUBTITLE || sheetType == PlayerSheetType.ALL) {
            SettingSectionHeader("Subtitle Appearance")
            
            // Size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", color = Color.White.copy(0.7f), fontSize = 14.sp, modifier = Modifier.width(60.dp))
                Slider(
                    value = uiState.subtitleSize,
                    onValueChange = { viewModel.setSubtitleSize(it) },
                    valueRange = 10f..40f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = mxOrange, activeTrackColor = mxOrange)
                )
                Text("${uiState.subtitleSize.toInt()}sp", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }
            
            Spacer(Modifier.height(8.dp))
            
            SubtitleColorRow("Text", uiState.subtitleTextColor, onColorChange = { viewModel.setSubtitleTextColor(it) }, mxOrange)
            Spacer(Modifier.height(12.dp))
            
            // Subtitle Intensity (Alpha)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity", color = Color.White.copy(0.7f), fontSize = 14.sp, modifier = Modifier.width(60.dp))
                Slider(
                    value = uiState.subtitleAlpha,
                    onValueChange = { viewModel.setSubtitleAlpha(it) },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = mxOrange, activeTrackColor = mxOrange)
                )
                Text("${(uiState.subtitleAlpha * 100).toInt()}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }

            Spacer(Modifier.height(12.dp))
            SubtitleColorRow("Background", uiState.subtitleBackgroundColor, onColorChange = { viewModel.setSubtitleBackgroundColor(it) }, mxOrange)
            Spacer(Modifier.height(8.dp))
            SubtitleColorRow("Outline", uiState.subtitleOutlineColor, onColorChange = { viewModel.setSubtitleOutlineColor(it) }, mxOrange)
            
            Spacer(Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.resetSubtitleStyle() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f))
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset to Defaults", color = Color.White)
            }
        }
    }
}

@Composable
fun MotionBoostSheetContent(
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel
) {
    val mxOrange = Color(0xFFFF8F00)
    
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSectionHeader("Target Frame Rate")
        
        val modes = listOf(
            MotionBoostMode.ORIGINAL,
            MotionBoostMode.FPS_45,
            MotionBoostMode.FPS_60,
            MotionBoostMode.FPS_90,
            MotionBoostMode.FPS_120
        )
        
        modes.forEach { mode ->
            val isSupported = when (mode) {
                MotionBoostMode.ORIGINAL -> true
                MotionBoostMode.FPS_45 -> uiState.motionBoostCapabilities?.supports45 ?: false
                MotionBoostMode.FPS_60 -> uiState.motionBoostCapabilities?.supports60 ?: false
                MotionBoostMode.FPS_90 -> uiState.motionBoostCapabilities?.supports90 ?: false
                MotionBoostMode.FPS_120 -> uiState.motionBoostCapabilities?.supports120 ?: false
            }
            
            val isSelected = uiState.motionBoostMode == mode
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isSupported) { viewModel.setMotionBoostMode(mode) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { if (isSupported) viewModel.setMotionBoostMode(mode) },
                    colors = RadioButtonDefaults.colors(selectedColor = mxOrange, unselectedColor = if (isSupported) Color.White else Color.Gray),
                    enabled = isSupported
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mode.label,
                        color = if (isSupported) Color.White else Color.Gray,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (!isSupported) {
                        Text(
                            text = "Display does not support ${mode.targetFps}Hz",
                            color = Color.Red.copy(0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        SettingSectionHeader("Processing Quality")
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MotionBoostQuality.entries.forEach { quality ->
                val isSelected = uiState.motionBoostQuality == quality
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.setMotionBoostQuality(quality) },
                    label = { 
                        @OptIn(ExperimentalStdlibApi::class)
                        val labelText = quality.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                        Text(
                            labelText,
                            color = if (isSelected) Color.Black else Color.White
                        ) 
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = mxOrange,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Info Block
        Surface(
            color = Color.White.copy(0.05f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Diagnostics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                
                InfoRow("Source", "${uiState.sourceFrameRateInfo?.sourceFrameRate ?: "Unknown"} FPS")
                InfoRow("Display", "${uiState.motionBoostCapabilities?.displayRefreshRate ?: "Unknown"} Hz")
                InfoRow("Output Target", if (uiState.motionBoostMode == MotionBoostMode.ORIGINAL) "Native" else "${uiState.motionBoostMode.targetFps} FPS")
                if (uiState.motionBoostMode != MotionBoostMode.ORIGINAL) {
                    InfoRow("Live Output", "${uiState.motionBoostLiveFps} FPS")
                }
                
                Spacer(Modifier.height(12.dp))
                
                val statusText = if (uiState.motionBoostMode == MotionBoostMode.ORIGINAL) {
                    "Status: Original playback — real-time interpolation not yet enabled"
                } else {
                    "Status: Motion Boost Active"
                }
                
                Text(
                    text = statusText,
                    color = mxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(0.5f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SubtitleColorRow(label: String, currentColor: Int, onColorChange: (Int) -> Unit, accent: Color) {
    Column {
        Text(label, color = Color.White.copy(0.7f), fontSize = 14.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val colors = listOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.GREEN, android.graphics.Color.CYAN, android.graphics.Color.BLUE, android.graphics.Color.MAGENTA, android.graphics.Color.RED, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT)
            colors.forEach { colorInt ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(colorInt))
                        .border(if (currentColor == colorInt) 2.dp else 0.dp, accent, CircleShape)
                        .clickable { onColorChange(colorInt) }
                )
            }
        }
    }
}

@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White.copy(0.5f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
