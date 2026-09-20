package com.beatraxus.app.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.app.Application
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatraxus.app.subtitles.api.SubtitleApiClientFactory
import com.beatraxus.app.subtitles.data.SubtitleAuthManager
import com.beatraxus.app.subtitles.data.SubtitleCredentialsStore
import com.beatraxus.app.subtitles.data.SubtitleRepositoryImpl
import com.beatraxus.app.subtitles.ui.OpenSubtitlesAuthDialog
import com.beatraxus.app.subtitles.ui.SubtitlePlayerSheetSection
import com.beatraxus.app.subtitles.viewmodel.SubtitleUiEvent
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModelFactory
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.zIndex
import com.beatraxus.app.ui.utils.RenderEffectHelper
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
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
import com.beatraxus.app.util.PlaybackGlobalState
import com.beatraxus.app.viewmodel.VideoAspectRatio
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import com.beatraxus.app.viewmodel.VideoPlayerViewModel
import com.beatraxus.app.viewmodel.VideoTrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs

enum class GestureType {
    NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM, SUBTITLE
}

enum class PlayerSheetType {
    NONE, AUDIO, SUBTITLE, SPEED, ALL, EQUALIZER, SLEEP_TIMER, COLOR
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
                // Don't pause if reconfiguring (e.g. orientation change), in PiP, or if Background Play is enabled
                val isReconfiguring = activity?.isChangingConfigurations == true
                if (uiState.isPlaying && !uiState.isBackgroundPlayEnabled && !isInPiP && !isReconfiguring) {
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

    val appContext = context.applicationContext as Application
    val api = remember { SubtitleApiClientFactory.createApi() }
    val credStore = remember { SubtitleCredentialsStore(appContext) }
    val authManager = remember { SubtitleAuthManager(api, credStore) }
    val repo = remember { SubtitleRepositoryImpl(appContext, api, authManager) }

    val subtitleViewModel: SubtitleViewModel = viewModel(
        factory = SubtitleViewModelFactory(
            application = appContext,
            repository = repo,
            authManager = authManager,
            subtitleController = viewModel.subtitleController,
            credentialsStore = credStore
        )
    )

    LaunchedEffect(uiState.currentVideo) {
        uiState.currentVideo?.let { v ->
            subtitleViewModel.onVideoChanged(v)
        }
    }

    var showAuthDialogInPlayer by remember { mutableStateOf(false) }
    var authDialogNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subtitleViewModel) {
        subtitleViewModel.events.collect { event ->
            when (event) {
                is SubtitleUiEvent.NeedSignIn -> {
                    authDialogNote = "Please sign in to OpenSubtitles to download subtitles."
                    showAuthDialogInPlayer = true
                }
                is SubtitleUiEvent.SignInForHigherLimit -> {
                    val resetMsg = event.resetTime?.let { " (resets in $it)" } ?: ""
                    authDialogNote = "Daily free download limit reached$resetMsg. Sign in for a higher limit."
                    showAuthDialogInPlayer = true
                }
                is SubtitleUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is SubtitleUiEvent.DownloadSuccess -> {
                    Toast.makeText(context, "Subtitle loaded: ${event.subtitleName}", Toast.LENGTH_SHORT).show()
                }
                is SubtitleUiEvent.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showAuthDialogInPlayer) {
        OpenSubtitlesAuthDialog(
            initialNote = authDialogNote,
            onDismiss = { showAuthDialogInPlayer = false },
            onSignIn = { user, pass -> subtitleViewModel.signIn(user, pass) }
        )
    }

    // Gesture States
    var gestureType by remember { mutableStateOf(GestureType.NONE) }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }
    var doubleTapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var doubleTapRippleText by remember { mutableStateOf("") }

    var showChapterStrip by remember { mutableStateOf(false) }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, uiState.isPlaying, uiState.isLocked) {
        if (controlsVisible && (uiState.isPlaying || uiState.isLocked)) {
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
        PlaybackGlobalState.setVideoPlayerOnScreen(true)
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        onDispose {
            PlaybackGlobalState.setVideoPlayerOnScreen(false)
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
                                viewModel.seekTo(viewModel.positionFlow.value + gestureValue.toLong() * 1000)
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
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (uiState.isLocked) return@detectTapGestures
                        val isCenter = offset.x > size.width / 3 && offset.x < size.width * 2 / 3
                        if (isCenter) {
                            viewModel.togglePlayPause()
                        } else {
                            val isLeft = offset.x < size.width / 2
                            val delta = if (isLeft) -10000L else 10000L
                            viewModel.seekTo(viewModel.positionFlow.value + delta)
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
                    VideoAspectRatio.ORIGINAL -> {
                        contentFrame?.setAspectRatio(0f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    VideoAspectRatio.FIT -> {
                        contentFrame?.setAspectRatio(0f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    VideoAspectRatio.FILL, VideoAspectRatio.STRETCH -> {
                        contentFrame?.setAspectRatio(0f)
                        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                    VideoAspectRatio.ZOOM, VideoAspectRatio.CROP -> {
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
                val bgColorWithOpacity = if (uiState.subtitleBackgroundOpacity > 0f) {
                    val alphaByte = (uiState.subtitleBackgroundOpacity * 255).toInt().coerceIn(0, 255)
                    val baseColor = if (uiState.subtitleBackgroundColor == android.graphics.Color.TRANSPARENT) android.graphics.Color.BLACK else uiState.subtitleBackgroundColor
                    android.graphics.Color.argb(alphaByte, android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor))
                } else {
                    uiState.subtitleBackgroundColor
                }

                val typeface = if (uiState.subtitleBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

                val captionStyle = CaptionStyleCompat(
                    uiState.subtitleTextColor,
                    bgColorWithOpacity,
                    uiState.subtitleWindowColor,
                    uiState.subtitleEdgeType,
                    uiState.subtitleOutlineColor,
                    typeface
                )
                view.subtitleView?.setApplyEmbeddedStyles(false)
                view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, uiState.subtitleSize)
                view.subtitleView?.setStyle(captionStyle)
                view.subtitleView?.alpha = uiState.subtitleAlpha
                
                // Vertical offset + position preset for subtitles
                val basePresetOffset = uiState.subtitlePositionPreset.offsetDp
                view.subtitleView?.translationY = basePresetOffset + uiState.subtitleOffset
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale
                )
                .then(
                    if (sheetType != PlayerSheetType.NONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.graphicsLayer {
                            renderEffect = RenderEffectHelper.createBlurAndSaturationEffect(20f, 1.1f)
                        }
                    } else {
                        Modifier
                    }
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
            visible = (controlsVisible || showChapterStrip) && sheetType == PlayerSheetType.NONE && !isInPiP,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!uiState.isLocked) {
                    if (showChapterStrip) {
                        ChapterThumbnailStrip(
                            chapters = uiState.chapters,
                            onChapterClick = { 
                                viewModel.seekTo(it.timestampMs)
                                showChapterStrip = false
                            },
                            onDismiss = { showChapterStrip = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 120.dp)
                        )
                    }

                    // Top Bar
                    PlayerTopBar(
                        title = uiState.currentVideo?.title ?: "",
                        isHdr = uiState.isHdr,
                        sleepTimerRemainingMs = uiState.sleepTimerRemainingMs,
                        onBack = onBack,
                        onAudioClick = { sheetType = PlayerSheetType.AUDIO },
                        onSubtitleClick = { sheetType = PlayerSheetType.SUBTITLE },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    // Floating Controls
                    FloatingControls(
                        uiState = uiState,
                        sheetType = sheetType,
                        onPiPClick = { 
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) 
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                activity?.enterPictureInPictureMode()
                            }
                        },
                        onSpeedClick = { sheetType = PlayerSheetType.SPEED },
                        onEqClick = { sheetType = PlayerSheetType.EQUALIZER },
                        onToggleBoost = { viewModel.toggleVolumeBoost() },
                        onHeadphonesClick = { viewModel.toggleBackgroundPlay() },
                        onRotationClick = { activity?.let { viewModel.toggleOrientation(it) } },
                        onColorClick = { sheetType = PlayerSheetType.COLOR },
                        onAbRepeatClick = { viewModel.toggleAbRepeat() },
                        onSleepTimerClick = { sheetType = PlayerSheetType.SLEEP_TIMER },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 70.dp)
                    )

                    // Bottom Bar
                    PlayerBottomBar(
                        uiState = uiState,
                        positionFlow = viewModel.positionFlow,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onPlayNext = { viewModel.playNext() },
                        onPlayPrevious = { viewModel.playPrevious() },
                        onSeek = { viewModel.seekTo(it) },
                        onStepFrame = { viewModel.stepFrame(it) },
                        onLock = { viewModel.toggleLock() },
                        onTimeClick = { viewModel.toggleTimeDisplay() },
                        onAspectRatioClick = { 
                            val nextRatio = when (uiState.aspectRatio) {
                                VideoAspectRatio.ORIGINAL -> VideoAspectRatio.FIT
                                VideoAspectRatio.FIT -> VideoAspectRatio.FILL
                                VideoAspectRatio.FILL -> VideoAspectRatio.ZOOM
                                VideoAspectRatio.ZOOM -> VideoAspectRatio.STRETCH
                                VideoAspectRatio.STRETCH -> VideoAspectRatio.CROP
                                VideoAspectRatio.CROP -> VideoAspectRatio.FOUR_THREE
                                VideoAspectRatio.FOUR_THREE -> VideoAspectRatio.SIXTEEN_NINE
                                VideoAspectRatio.SIXTEEN_NINE -> VideoAspectRatio.ORIGINAL
                            }
                            viewModel.setAspectRatio(nextRatio)
                        },

                        onScrubbing = { viewModel.updateScrubbingPreview(it) },
                        onLongPress = { showChapterStrip = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )

                    // Skip Intro Button
                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.showSkipIntroButton,
                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 120.dp, end = 24.dp)
                    ) {
                        Surface(
                            onClick = { viewModel.skipIntro() },
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(24.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Skip Intro",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
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

    AnimatedVisibility(
        visible = sheetType != PlayerSheetType.NONE,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f)
        ) {
            // Full-screen dimming scrim background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.20f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        sheetType = PlayerSheetType.NONE
                    }
            )

            // Right-side glass panel
            AnimatedVisibility(
                visible = sheetType != PlayerSheetType.NONE,
                enter = slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(250)),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 320.dp, max = 380.dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                            clip = false
                        )
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0x80141419),
                                    Color(0x991C1C22)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.25f),
                                    Color.White.copy(alpha = 0.05f)
                                )
                            ),
                            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                        )
                        .systemBarsPadding()
                        .clickable(enabled = false) {}
                ) {
                    VideoSettingsSheetContent(
                        uiState = uiState,
                        sheetType = sheetType,
                        onSpeedSelect = { viewModel.setPlaybackSpeed(it); sheetType = PlayerSheetType.NONE },
                        onAspectRatioSelect = { viewModel.setAspectRatio(it); sheetType = PlayerSheetType.NONE },
                        onAudioTrackSelect = { viewModel.selectAudioTrack(it); sheetType = PlayerSheetType.NONE },
                        onSubtitleTrackSelect = { viewModel.selectSubtitleTrack(it); sheetType = PlayerSheetType.NONE },
                        viewModel = viewModel,
                        subtitleViewModel = subtitleViewModel
                    )
                }
            }
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
    onColorClick: () -> Unit,
    onAbRepeatClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    
    Box(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(0.4f))
            .pointerInput(Unit) {
                // Consume all touches/gestures to prevent them from triggering 
                // underlying player navigation gestures (seek, brightness, etc.)
                detectTapGestures { }
            }
            .horizontalScroll(scrollState)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onPiPClick) {
                Icon(Icons.Rounded.PictureInPicture, null, tint = Color.White)
            }
            IconButton(onClick = onAbRepeatClick) {
                Icon(
                    Icons.Rounded.Repeat, 
                    null, 
                    tint = if (uiState.isAbRepeatActive || uiState.abRepeatPointA != null) Color(0xFFFF8F00) else Color.White
                )
            }
            IconButton(onClick = onSleepTimerClick) {
                Icon(
                    Icons.Rounded.Bedtime, 
                    null, 
                    tint = if (uiState.sleepTimerMode != com.beatraxus.app.viewmodel.SleepTimerMode.OFF) Color(0xFFFF8F00) else Color.White
                )
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
            IconButton(onClick = onColorClick) {
                 Icon(
                    Icons.Rounded.Tune,
                    null,
                    tint = if (uiState.colorBrightness != 0f || uiState.colorContrast != 1f || uiState.colorSaturation != 1f) Color(0xFFFF8F00) else Color.White
                )
            }
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
    sleepTimerRemainingMs: Long? = null,
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
            .statusBarsPadding()
            .padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    if (sleepTimerRemainingMs != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFFFF8F00).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8F00).copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Timer, null, tint = Color(0xFFFF8F00), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    formatTime(sleepTimerRemainingMs),
                                    color = Color(0xFFFF8F00),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
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
    positionFlow: StateFlow<Long>,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onStepFrame: (Boolean) -> Unit,
    onLock: () -> Unit,
    onTimeClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onScrubbing: (Long?) -> Unit,
    onLongPress: () -> Unit,
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
            // Scrub Preview Overlay
            uiState.scrubbingTimeMs?.let { timeMs ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    val progress = if (uiState.duration > 0) timeMs.toFloat() / uiState.duration else 0f
                    
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val xOffset = this.maxWidth * progress
                        
                        Column(
                            modifier = Modifier
                                .offset(x = xOffset - 80.dp) // center the 160dp wide preview
                                .width(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(0.8f))
                                .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(8.dp)),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (uiState.scrubbingThumbnail != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = uiState.scrubbingThumbnail.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(Color.DarkGray.copy(0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                                }
                            }
                            
                            Text(
                                text = formatTime(timeMs),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Seek bar
            val currentPosition by positionFlow.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(currentPosition),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                val mxOrange = Color(0xFFFF8F00)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { onLongPress() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Chapter Ticks & AB Repeat Track Background
                    if (uiState.duration > 0) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                        ) {
                            val trackWidth = size.width
                            
                            // Draw Chapter Ticks
                            uiState.chapters.forEach { chapter ->
                                val tickX = (chapter.timestampMs.toFloat() / uiState.duration.toFloat()) * trackWidth
                                drawRect(
                                    color = Color.White.copy(alpha = 0.6f),
                                    topLeft = Offset(tickX - 0.5.dp.toPx(), 0f),
                                    size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)
                                )
                            }

                            val startX = (uiState.abRepeatPointA?.toFloat() ?: 0f) / uiState.duration.toFloat() * trackWidth
                            val endX = (uiState.abRepeatPointB?.toFloat() ?: uiState.duration.toFloat()) / uiState.duration.toFloat() * trackWidth
                            
                            if (uiState.abRepeatPointA != null && uiState.abRepeatPointB != null) {
                                drawRect(
                                    color = mxOrange.copy(alpha = 0.3f),
                                    topLeft = Offset(startX, 0f),
                                    size = androidx.compose.ui.geometry.Size(endX - startX, size.height)
                                )
                            }
                            
                            uiState.abRepeatPointA?.let {
                                drawRect(
                                    color = mxOrange,
                                    topLeft = Offset(startX - 1.dp.toPx(), -2.dp.toPx()),
                                    size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height + 4.dp.toPx())
                                )
                            }
                            
                            uiState.abRepeatPointB?.let {
                                drawRect(
                                    color = mxOrange,
                                    topLeft = Offset(endX - 1.dp.toPx(), -2.dp.toPx()),
                                    size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height + 4.dp.toPx())
                                )
                            }
                        }
                    }

                    Slider(
                        value = (uiState.scrubbingTimeMs ?: currentPosition).toFloat(),
                        onValueChange = { 
                            onScrubbing(it.toLong())
                        },
                        onValueChangeFinished = {
                            uiState.scrubbingTimeMs?.let { onSeek(it) }
                            onScrubbing(null)
                        },
                        valueRange = 0f..uiState.duration.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = mxOrange,
                            activeTrackColor = mxOrange,
                            inactiveTrackColor = Color.White.copy(0.3f)
                        )
                    )
                }
                Text(
                    text = if (uiState.showTotalTime) {
                        "-${formatTime(uiState.duration - currentPosition)}"
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
                    
                    if (!uiState.isPlaying) {
                        IconButton(
                            onClick = { onStepFrame(false) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
                        }
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

                    if (!uiState.isPlaying) {
                        IconButton(
                            onClick = { onStepFrame(true) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
                        }
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
    viewModel: VideoPlayerViewModel,
    subtitleViewModel: SubtitleViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        val mxOrange = Color(0xFFFF8F00)

        val lastActiveSheetTypeState = remember { mutableStateOf(if (sheetType != PlayerSheetType.NONE) sheetType else PlayerSheetType.AUDIO) }
        if (sheetType != PlayerSheetType.NONE) {
            lastActiveSheetTypeState.value = sheetType
        }
        val activeSheetType = lastActiveSheetTypeState.value
        
        val title = when (activeSheetType) {
            PlayerSheetType.AUDIO -> "Audio Tracks"
            PlayerSheetType.SUBTITLE -> "Subtitles"
            PlayerSheetType.SPEED -> "Playback Speed"
            PlayerSheetType.EQUALIZER -> "Premium Equalizer"
            PlayerSheetType.SLEEP_TIMER -> "Sleep Timer"
            PlayerSheetType.COLOR -> "Color Grading"
            else -> "Settings"
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeSheetType == PlayerSheetType.EQUALIZER) {
                    Switch(
                        checked = uiState.isEqEnabled,
                        onCheckedChange = { viewModel.toggleEqEnabled() },
                        colors = SwitchDefaults.colors(checkedThumbColor = mxOrange, checkedTrackColor = mxOrange.copy(0.4f))
                    )
                }

                if (activeSheetType == PlayerSheetType.COLOR) {
                    IconButton(onClick = { viewModel.resetColorGrading() }) {
                        Icon(Icons.Rounded.RestartAlt, "Reset", tint = Color.White)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(14.dp))

        // Equalizer Section
        if (activeSheetType == PlayerSheetType.EQUALIZER) {
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

        // Color Grading Section
        if (activeSheetType == PlayerSheetType.COLOR) {
            ColorGradingSection(uiState, viewModel)
        }

        // HDR Tone Mapping Toggle
        if (uiState.isHdr && (activeSheetType == PlayerSheetType.COLOR || activeSheetType == PlayerSheetType.ALL)) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(0.05f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Force SDR Tone-mapping", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Improve visibility on non-HDR displays", color = Color.White.copy(0.6f), fontSize = 12.sp)
                }
                Switch(
                    checked = uiState.forceSdrToneMapping,
                    onCheckedChange = { viewModel.setForceSdrToneMapping(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = mxOrange, checkedTrackColor = mxOrange.copy(0.4f))
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Audio Tracks
        if (activeSheetType == PlayerSheetType.AUDIO || activeSheetType == PlayerSheetType.ALL) {
            if (uiState.availableAudioTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(16.dp)
                ) {
                    Text("No audio tracks found", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.availableAudioTracks.forEach { track ->
                        val isDolby = track.format == MimeTypes.AUDIO_E_AC3 || track.format == MimeTypes.AUDIO_AC3
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (track.isSelected) mxOrange.copy(alpha = 0.2f)
                                    else Color.White.copy(alpha = 0.06f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (track.isSelected) mxOrange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onAudioTrackSelect(track) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AudioFile,
                                    contentDescription = null,
                                    tint = if (track.isSelected) mxOrange else Color.White.copy(0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = track.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    track.language?.let { lang ->
                                        Text(
                                            text = lang.uppercase(),
                                            color = Color.White.copy(0.5f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                if (isDolby) {
                                    Spacer(Modifier.width(8.dp))
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
                            if (track.isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = mxOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Subtitle Section
        if (activeSheetType == PlayerSheetType.SUBTITLE) {
            SubtitlePlayerSheetSection(
                videoUiState = uiState,
                videoViewModel = viewModel,
                subtitleViewModel = subtitleViewModel,
                onOpenAuthDialog = { }
            )
        }
    }
}


@androidx.media3.common.util.UnstableApi
@Composable
fun SleepTimerSheetContent(
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel
) {
    val mxOrange = Color(0xFFFF8F00)
    
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSectionHeader("Auto-Stop Playback")
        
        com.beatraxus.app.viewmodel.SleepTimerMode.entries.forEach { mode ->
            val isSelected = uiState.sleepTimerMode == mode
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setSleepTimer(mode) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { viewModel.setSleepTimer(mode) },
                    colors = RadioButtonDefaults.colors(selectedColor = mxOrange, unselectedColor = Color.White)
                )
                Text(
                    text = mode.label,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
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

@Composable
fun ChapterThumbnailStrip(
    chapters: List<com.beatraxus.app.model.VideoChapterEntity>,
    onChapterClick: (com.beatraxus.app.model.VideoChapterEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (chapters.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chapters", color = Color.White, fontWeight = FontWeight.Bold)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, null, tint = Color.White)
            }
        }
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(chapters) { chapter ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clickable { onChapterClick(chapter) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                    ) {
                        if (chapter.thumbnailPath != null) {
                            androidx.compose.foundation.Image(
                                painter = coil.compose.rememberAsyncImagePainter(java.io.File(chapter.thumbnailPath)),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        Text(
                            text = formatTime(chapter.timestampMs),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(Color.Black.copy(0.6f), RoundedCornerShape(topStart = 4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = chapter.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ColorGradingSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    val mxOrange = Color(0xFFFF8F00)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ColorSliderRow(
            label = "Brightness",
            value = uiState.colorBrightness,
            valueRange = -1f..1f,
            onValueChange = { viewModel.setColorGrading(it, uiState.colorContrast, uiState.colorSaturation) },
            mxOrange = mxOrange
        )
        ColorSliderRow(
            label = "Contrast",
            value = uiState.colorContrast,
            valueRange = 0f..2f,
            onValueChange = { viewModel.setColorGrading(uiState.colorBrightness, it, uiState.colorSaturation) },
            mxOrange = mxOrange
        )
        ColorSliderRow(
            label = "Saturation",
            value = uiState.colorSaturation,
            valueRange = 0f..2f,
            onValueChange = { viewModel.setColorGrading(uiState.colorBrightness, uiState.colorContrast, it) },
            mxOrange = mxOrange
        )
    }
}

@Composable
fun ColorSliderRow(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, mxOrange: Color) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(0.7f), fontSize = 14.sp)
            Text(String.format("%.2f", value), color = mxOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = mxOrange, activeTrackColor = mxOrange, inactiveTrackColor = Color.White.copy(0.1f))
        )
    }
}
