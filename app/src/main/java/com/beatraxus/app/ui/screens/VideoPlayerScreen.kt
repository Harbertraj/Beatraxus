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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import com.beatraxus.app.subtitles.ui.MxFilterChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clipToBounds
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
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.beatraxus.app.model.SavedEqPreset
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
            val isReconfiguring = activity?.isChangingConfigurations == true
            if (event == Lifecycle.Event.ON_PAUSE) {
                // Don't pause if reconfiguring (e.g. orientation change), in PiP, or if Background Play is enabled
                if (uiState.isPlaying && !uiState.isBackgroundPlayEnabled && !isInPiP && !isReconfiguring) {
                    viewModel.togglePlayPause()
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                if (!isReconfiguring && !isInPiP) {
                    activity?.window?.let { window ->
                        val layoutParams = window.attributes
                        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = layoutParams
                    }
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
    var gestureSubText by remember { mutableStateOf<String?>(null) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }
    var doubleTapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var doubleTapRippleText by remember { mutableStateOf("") }

    var showChapterStrip by remember { mutableStateOf(false) }
    var lastScrubSeek by remember { mutableLongStateOf(0L) }

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
        val originalBrightness = window?.attributes?.screenBrightness
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        onDispose {
            viewModel.saveProgressNow()
            PlaybackGlobalState.setVideoPlayerOnScreen(false)
            activity?.requestedOrientation = originalOrientation
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                val layoutParams = window.attributes
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = layoutParams
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
                    // Initial pass: we see touches BEFORE the tap detector below, so we can
                    // consume swipe movement and stop it being treated as a tap (which was
                    // toggling the controls/title/top-icons on every swipe).
                    val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragStarted = false
                    var initialDragIntent = GestureType.NONE
                    var cumulativeChange = Offset.Zero
                    var seekStartPos = 0L
                    var lastLiveSeek = 0L
                    
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.all { !it.pressed }) {
                            // Release - hide overlays
                            showGestureOverlay = false
                            gestureType = GestureType.NONE
                            
                            if (initialDragIntent == GestureType.SEEK) {
                                val dur = uiState.duration.coerceAtLeast(0L)
                                val target = (seekStartPos + (gestureValue * 1000f).toLong())
                                    .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                                viewModel.seekTo(target)
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
                            seekStartPos = viewModel.positionFlow.value
                            
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

                            if (initialDragIntent != GestureType.ZOOM) {
                                // Swipe in progress: keep player UI hidden and swallow the
                                // touch so it can't register as a tap on release.
                                if (controlsVisible) controlsVisible = false
                                event.changes.forEach { it.consume() }
                            }
                            
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
                                    val dur = uiState.duration.coerceAtLeast(0L)
                                    val target = (seekStartPos + (deltaSeconds * 1000f).toLong())
                                        .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                                    // live seek (throttled) so the picture follows the finger
                                    val now = System.currentTimeMillis()
                                    if (now - lastLiveSeek > 120) {
                                        lastLiveSeek = now
                                        viewModel.seekTo(target)
                                    }
                                    gestureSubText = "${formatTime(target)} / ${formatTime(dur)}"
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
                // Custom pinch handler: reacts on the very first pixel of movement
                // (detectTransformGestures waits for touch-slop, which felt laggy).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        // Player View
        var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
        val latestAspect by rememberUpdatedState(uiState.aspectRatio)

        // PlayerView resets its own frame ratio on every video-size change (new video,
        // rotation, format change). Re-apply ours right after it, on the same callback.
        DisposableEffect(playerViewRef) {
            val pv = playerViewRef
            val exo = viewModel.getPlayer()
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    pv?.let { applyVideoAspect(it, latestAspect, videoSize) }
                }
            }
            if (pv != null) exo?.addListener(listener)
            onDispose { exo?.removeListener(listener) }
        }

        // Colour grading is applied as a ColorMatrix filter on a TextureView layer (instant, works
        // while playing AND paused, independent of the codec/effect pipeline). A SurfaceView cannot
        // be colour-filtered, so we only use the TextureView variant while grading is in use or the
        // Color Grading sheet is open; otherwise the normal (best quality) SurfaceView is kept.
        val gradingActive = uiState.colorBrightness != 0f ||
            uiState.colorContrast != 1f ||
            uiState.colorSaturation != 1f
        val useTextureSurface = gradingActive || sheetType == PlayerSheetType.COLOR

        key(useTextureSurface) {
        AndroidView(
            factory = { ctx ->
                val pv = if (useTextureSurface) {
                    android.view.LayoutInflater.from(ctx)
                        .inflate(com.beatraxus.app.R.layout.player_view_texture, null) as PlayerView
                } else {
                    PlayerView(ctx)
                }
                pv.apply {
                    player = viewModel.getPlayer()
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    playerViewRef = this
                }
            },
            onRelease = { it.player = null },
            update = { view ->
                // Apply brightness / contrast / saturation to the video layer.
                (view.videoSurfaceView as? TextureView)?.setLayerPaint(
                    if (gradingActive) {
                        android.graphics.Paint().apply {
                            colorFilter = android.graphics.ColorMatrixColorFilter(
                                buildColorGradeMatrix(
                                    uiState.colorBrightness,
                                    uiState.colorContrast,
                                    uiState.colorSaturation
                                )
                            )
                        }
                    } else null
                )

                // NOTE: no player.videoScalingMode changes here. Codec scaling only applies to
                // the NEXT rendered frame, so while paused the new mode appeared "late".
                // The frame layout (resizeMode + aspect ratio) is applied instantly instead.
                applyVideoAspect(view, uiState.aspectRatio, uiState.videoSize)
                
                // Update Subtitle Styles
                val bgColorWithOpacity = if (uiState.subtitleBackgroundOpacity > 0f) {
                    val alphaByte = (uiState.subtitleBackgroundOpacity * 255).toInt().coerceIn(0, 255)
                    val baseColor = if (uiState.subtitleBackgroundColor == android.graphics.Color.TRANSPARENT) android.graphics.Color.BLACK else uiState.subtitleBackgroundColor
                    android.graphics.Color.argb(alphaByte, android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor))
                } else {
                    uiState.subtitleBackgroundColor
                }

                val typeface = if (uiState.subtitleBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

                @Suppress("WrongConstant")
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
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                }
                .then(
                    // Never blur / re-saturate the video while the Color Grading sheet is open:
                    // the user must see the REAL picture to judge brightness / contrast / saturation.
                    if (sheetType != PlayerSheetType.NONE &&
                        sheetType != PlayerSheetType.COLOR &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        Modifier.graphicsLayer {
                            renderEffect = RenderEffectHelper.createBlurAndSaturationEffect(20f, 1.1f)
                        }
                    } else {
                        Modifier
                    }
                )
        )
        } // key(useTextureSurface)


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
                GestureOverlay(type = gestureType, value = gestureValue, subText = gestureSubText)
            }

            // Aspect Ratio Overlay
            uiState.aspectRatioMessage?.let { message ->
                Surface(
                    color = Color(0xE60E0E12),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, VpAmberSoft.copy(alpha = 0.55f)),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = message,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
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
                    color = Color(0xE60E0E12),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, VpAmberSoft.copy(alpha = 0.55f)),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.FastForward, null, tint = VpAmberSoft, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("2X Speed", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
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
                                .padding(bottom = 190.dp)
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
                        onLoopClick = { viewModel.toggleLoop() },
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
                                VideoAspectRatio.FIT -> VideoAspectRatio.FILL
                                VideoAspectRatio.FILL -> VideoAspectRatio.ZOOM
                                VideoAspectRatio.ZOOM -> VideoAspectRatio.FOUR_THREE
                                VideoAspectRatio.FOUR_THREE -> VideoAspectRatio.SIXTEEN_NINE
                                VideoAspectRatio.SIXTEEN_NINE -> VideoAspectRatio.FIT
                            }
                            viewModel.setAspectRatio(nextRatio)
                        },

                        onScrubbing = {
                            viewModel.updateScrubbingPreview(it)
                            if (it != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastScrubSeek > 120) {
                                    lastScrubSeek = now
                                    viewModel.seekTo(it)
                                }
                            }
                        },
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
                            .padding(bottom = 190.dp, end = 24.dp)
                    ) {
                        Surface(
                            onClick = { viewModel.skipIntro() },
                            color = VpAmber,
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Skip Intro",
                                    color = Color.Black,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    null,
                                    tint = Color.Black,
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
                        VpGlassButton(
                            onClick = { viewModel.toggleLock() },
                            size = 60.dp,
                            active = true
                        ) {
                            Icon(Icons.Rounded.Lock, null, tint = VpAmberSoft, modifier = Modifier.size(30.dp))
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
                    // no dim for Color Grading, so the true colours stay visible
                    .background(Color.Black.copy(alpha = if (sheetType == PlayerSheetType.COLOR) 0f else 0.20f))
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
                        .widthIn(min = 260.dp, max = 320.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                            clip = false
                        )
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xCC141419),
                                    Color(0xE61C1C22)
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
                            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
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

// ─────────────────────────────────────────────────────────────────────────────
//  Video player UI  —  "Amber Glass" look
//  UI only: every callback, state read and gesture is unchanged.
// ─────────────────────────────────────────────────────────────────────────────
private val VpAmber = Color(0xFFFF8F00)
private val VpAmberSoft = Color(0xFFFFB347)

/** Round frosted-glass button. [active] lights it up with an amber rim. */
@Composable
private fun VpGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "vpGlassScale"
    )
    val fill = if (active) {
        Brush.verticalGradient(listOf(VpAmber.copy(alpha = 0.34f), VpAmber.copy(alpha = 0.14f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f)))
    }
    val rim = if (active) {
        Brush.verticalGradient(listOf(VpAmberSoft.copy(alpha = 0.95f), VpAmber.copy(alpha = 0.30f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f)))
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, rim, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Flat icon used inside the floating tool capsule. Active = amber disc behind an amber icon. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VpToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "vpToolScale"
    )
    val activeAmount by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(220),
        label = "vpToolActive"
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(VpAmber.copy(alpha = 0.20f * activeAmount))
            .border(1.dp, VpAmberSoft.copy(alpha = 0.65f * activeAmount), CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.92f), VpAmberSoft, activeAmount),
            modifier = Modifier.size(22.dp)
        )
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
    onLoopClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val capsule = RoundedCornerShape(30.dp)

    Box(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .shadow(14.dp, capsule, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(capsule)
            .background(Color.Black.copy(alpha = 0.40f))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f))),
                capsule
            )
            .pointerInput(Unit) {
                // Consume all touches/gestures to prevent them from triggering
                // underlying player navigation gestures (seek, brightness, etc.)
                detectTapGestures { }
            }
            .horizontalScroll(scrollState)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VpToolButton(Icons.Rounded.PictureInPicture, false, onPiPClick)
            VpToolButton(
                if (uiState.isLooping) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                uiState.isLooping || uiState.isAbRepeatActive || uiState.abRepeatPointA != null,
                onLoopClick,
                onLongClick = onAbRepeatClick
            )
            VpToolButton(
                Icons.Rounded.Bedtime,
                uiState.sleepTimerMode != com.beatraxus.app.viewmodel.SleepTimerMode.OFF,
                onSleepTimerClick
            )
            VpToolButton(Icons.Rounded.Speed, uiState.playbackSpeed != 1.0f, onSpeedClick)
            VpToolButton(Icons.Rounded.Equalizer, uiState.isEqEnabled, onEqClick)
            VpToolButton(
                if (uiState.isVolumeBoost) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown,
                uiState.isVolumeBoost,
                onToggleBoost
            )
            VpToolButton(Icons.Rounded.Headset, uiState.isBackgroundPlayEnabled, onHeadphonesClick)
            VpToolButton(Icons.Rounded.ScreenRotation, false, onRotationClick)
            VpToolButton(
                Icons.Rounded.Tune,
                uiState.colorBrightness != 0f || uiState.colorContrast != 1f || uiState.colorSaturation != 1f,
                onColorClick
            )
        }
    }
}

@Composable
fun GestureOverlay(type: GestureType, value: Float, subText: String? = null) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .size(134.dp)
            .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = VpAmber)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE61E1A14), Color(0xE60C0C10))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(VpAmberSoft.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f))),
                shape
            ),
        contentAlignment = Alignment.Center
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

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(VpAmber.copy(alpha = 0.34f), VpAmber.copy(alpha = 0.12f))))
                    .border(1.dp, VpAmberSoft.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (type) {
                    GestureType.BRIGHTNESS, GestureType.VOLUME -> "${value.toInt()}%"
                    GestureType.SEEK -> "${if (value >= 0) "+" else ""}${value.toInt()}s"
                    GestureType.SUBTITLE -> "Move Subtitles"
                    else -> ""
                },
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (type == GestureType.SEEK && subText != null) {
                Spacer(Modifier.height(4.dp))
                Text(subText, color = VpAmberSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (type == GestureType.BRIGHTNESS || type == GestureType.VOLUME) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(76.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((value / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(VpAmberSoft, VpAmber)))
                    )
                }
            }
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
                .background(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.40f), VpAmber.copy(alpha = 0.22f))),
                    CircleShape
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape),
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
                    listOf(Color.Black.copy(0.80f), Color.Black.copy(0.38f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            VpGlassButton(onClick = onBack, size = 42.dp) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (sleepTimerRemainingMs != null) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(VpAmber.copy(alpha = 0.20f))
                            .border(1.dp, VpAmberSoft.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Timer, null, tint = VpAmberSoft, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            formatTime(sleepTimerRemainingMs),
                            color = VpAmberSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (isHdr) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFFE082), Color(0xFFFFB300))))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        "HDR",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp
                    )
                }
            }
            VpGlassButton(onClick = onAudioClick, size = 42.dp) {
                Icon(Icons.Rounded.AudioFile, null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(8.dp))
            VpGlassButton(onClick = onSubtitleClick, size = 42.dp) {
                Icon(Icons.Rounded.Subtitles, null, tint = Color.White, modifier = Modifier.size(21.dp))
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
    val panelShape = RoundedCornerShape(30.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp, top = 28.dp, start = 16.dp, end = 16.dp)
    ) {
        Column {
            // Scrub Preview Overlay
            uiState.scrubbingTimeMs?.let { timeMs ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    val progress = if (uiState.duration > 0) timeMs.toFloat() / uiState.duration else 0f

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val xOffset = this.maxWidth * progress

                        Column(
                            modifier = Modifier
                                .offset(x = xOffset - 80.dp) // center the 160dp wide preview
                                .width(160.dp)
                                .shadow(16.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black, spotColor = VpAmber)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xE60C0C10))
                                .border(
                                    1.dp,
                                    Brush.verticalGradient(listOf(VpAmberSoft.copy(alpha = 0.7f), Color.White.copy(alpha = 0.08f))),
                                    RoundedCornerShape(16.dp)
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (uiState.scrubbingThumbnail != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = uiState.scrubbingThumbnail.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
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
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = VpAmberSoft)
                                }
                            }

                            Text(
                                text = formatTime(timeMs),
                                color = VpAmberSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // Glass control panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, panelShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(panelShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f))))
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f))),
                        panelShape
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Seek bar
                val currentPosition by positionFlow.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(currentPosition),
                        color = VpAmberSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    val mxOrange = VpAmber
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { onLongPress() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
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
                            ),
                            thumb = {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .shadow(8.dp, CircleShape, ambientColor = VpAmber, spotColor = VpAmber)
                                        .background(Color.White, CircleShape)
                                        .border(3.dp, mxOrange, CircleShape)
                                )
                            },
                            track = { sliderState ->
                                val range = sliderState.valueRange
                                val span = (range.endInclusive - range.start).coerceAtLeast(1f)
                                val fraction = ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.22f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .fillMaxHeight()
                                            .background(Brush.horizontalGradient(listOf(VpAmberSoft, mxOrange)))
                                    )
                                }
                            }
                        )

                        // Chapter Ticks & AB Repeat markers (drawn on top of the track, touches pass through)
                        if (uiState.duration > 0) {
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 7.dp)
                                    .height(5.dp)
                            ) {
                                val trackWidth = size.width

                                // Draw Chapter Ticks
                                uiState.chapters.forEach { chapter ->
                                    val tickX = (chapter.timestampMs.toFloat() / uiState.duration.toFloat()) * trackWidth
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        topLeft = Offset(tickX - 0.75.dp.toPx(), 0f),
                                        size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height)
                                    )
                                }

                                val startX = (uiState.abRepeatPointA?.toFloat() ?: 0f) / uiState.duration.toFloat() * trackWidth
                                val endX = (uiState.abRepeatPointB?.toFloat() ?: uiState.duration.toFloat()) / uiState.duration.toFloat() * trackWidth

                                if (uiState.abRepeatPointA != null && uiState.abRepeatPointB != null) {
                                    drawRect(
                                        color = mxOrange.copy(alpha = 0.35f),
                                        topLeft = Offset(startX, 0f),
                                        size = androidx.compose.ui.geometry.Size(endX - startX, size.height)
                                    )
                                }

                                uiState.abRepeatPointA?.let {
                                    drawRect(
                                        color = Color.White,
                                        topLeft = Offset(startX - 1.dp.toPx(), -3.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height + 6.dp.toPx())
                                    )
                                }

                                uiState.abRepeatPointB?.let {
                                    drawRect(
                                        color = Color.White,
                                        topLeft = Offset(endX - 1.dp.toPx(), -3.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height + 6.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (uiState.showTotalTime) {
                            "-${formatTime(uiState.duration - currentPosition)}"
                        } else {
                            formatTime(uiState.duration)
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable { onTimeClick() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    VpGlassButton(onClick = onLock, size = 44.dp, active = uiState.isLocked) {
                        Icon(
                            if (uiState.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            null,
                            tint = Color.White.copy(0.9f),
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VpGlassButton(onClick = onPlayPrevious, size = 46.dp) {
                            Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }

                        AnimatedVisibility(
                            visible = !uiState.isPlaying,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            VpGlassButton(onClick = { onStepFrame(false) }, size = 32.dp) {
                                Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = Color.White.copy(0.85f), modifier = Modifier.size(22.dp))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .shadow(18.dp, CircleShape, ambientColor = VpAmber, spotColor = VpAmber)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(VpAmberSoft, VpAmber)))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                .clickable(role = Role.Button, onClick = onTogglePlayPause),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null,
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        AnimatedVisibility(
                            visible = !uiState.isPlaying,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            VpGlassButton(onClick = { onStepFrame(true) }, size = 32.dp) {
                                Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(0.85f), modifier = Modifier.size(22.dp))
                            }
                        }

                        VpGlassButton(onClick = onPlayNext, size = 46.dp) {
                            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }

                    VpGlassButton(onClick = onAspectRatioClick, size = 44.dp) {
                        Icon(Icons.Rounded.AspectRatio, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(21.dp))
                    }
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
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        val mxOrange = Color(0xFFFF8F00)

        val lastActiveSheetTypeState = remember { mutableStateOf(if (sheetType != PlayerSheetType.NONE) sheetType else PlayerSheetType.AUDIO) }
        if (sheetType != PlayerSheetType.NONE) {
            lastActiveSheetTypeState.value = sheetType
        }
        val activeSheetType = lastActiveSheetTypeState.value
        // EQ is handled by DSP Studio while video audio is routed to the Audio Engine
        val eqLockedByEngine = uiState.routeAudioToEngine || uiState.isConnectingEngineRoute
        
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
                if (activeSheetType == PlayerSheetType.AUDIO) {
                    val canRoute = uiState.availableAudioTracks.isNotEmpty() && uiState.playbackSpeed == 1.0f && !uiState.isConnectingEngineRoute
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Connect to audio mode",
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiState.isConnectingEngineRoute) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = mxOrange,
                                strokeWidth = 2.dp
                            )
                        }
                        Switch(
                            checked = uiState.routeAudioToEngine || uiState.isConnectingEngineRoute,
                            enabled = canRoute,
                            onCheckedChange = { viewModel.setRouteAudioToEngine(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = mxOrange,
                                checkedTrackColor = mxOrange.copy(0.4f),
                                disabledCheckedThumbColor = mxOrange.copy(0.4f),
                                disabledUncheckedThumbColor = Color.Gray.copy(0.4f)
                            )
                        )
                    }
                }

                if (activeSheetType == PlayerSheetType.EQUALIZER) {
                    Switch(
                        checked = uiState.isEqEnabled && !eqLockedByEngine,
                        enabled = !eqLockedByEngine,
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

        if (activeSheetType == PlayerSheetType.AUDIO) {
            if (uiState.isConnectingEngineRoute) {
                Text(
                    text = "Connecting to Audio Engine...",
                    color = mxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (uiState.routeAudioToEngine) {
                Text(
                    text = "Audio is playing through the Audio Engine (DSP / Hi-Res)",
                    color = mxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (uiState.playbackSpeed != 1.0f) {
                Text(
                    text = "Audio Engine routing requires 1.0x playback speed",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            uiState.engineRouteError?.let { err ->
                Text(
                    text = err,
                    color = Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        Spacer(Modifier.height(14.dp))

        // Equalizer Section
        if (activeSheetType == PlayerSheetType.EQUALIZER) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (eqLockedByEngine) Modifier.blur(10.dp) else Modifier)
                ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedPreset == "Manual",
                            onClick = { viewModel.setEqPreset(com.beatraxus.app.model.SavedEqPreset("Manual", List(10) { com.beatraxus.app.model.ParametricEqBand(it, true, 1000f, 0f) })) },
                            label = { Text("Manual", color = if (uiState.selectedPreset == "Manual") Color.White else Color.Unspecified) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White.copy(0.2f), selectedLabelColor = Color.White)
                        )
                    }
                    items(uiState.availablePresets) { preset: SavedEqPreset ->
                        MxFilterChip(
                            selected = uiState.selectedPreset == preset.name,
                            onClick = { viewModel.setEqPreset(preset) },
                            label = preset.name
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

                if (eqLockedByEngine) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .pointerInput(Unit) {
                                // swallow every touch so the blurred EQ can't be used
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Audio track connected with DSP Studio (Audio mode)",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.availableAudioTracks.forEach { track ->
                        val isDolby = track.format == MimeTypes.AUDIO_E_AC3 || track.format == MimeTypes.AUDIO_AC3
                        val line2Text = formatAudioTrackSubtitleLine(track)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (track.isSelected) mxOrange.copy(alpha = 0.2f)
                                    else Color.White.copy(alpha = 0.06f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (track.isSelected) mxOrange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { onAudioTrackSelect(track) }
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                @OptIn(ExperimentalFoundationApi::class)
                                Column(
                                    modifier = Modifier.weight(1f).clipToBounds()
                                ) {
                                    Text(
                                        text = track.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = line2Text,
                                        color = Color.White.copy(0.6f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isDolby) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFFD54F))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            "DOLBY",
                                            color = Color.Black,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                                if (track.isSelected && uiState.routeAudioToEngine) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(mxOrange)
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            "ENGINE",
                                            color = Color.Black,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                            if (track.isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = mxOrange,
                                    modifier = Modifier.size(22.dp)
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

        // Playback Speed Section
        if (activeSheetType == PlayerSheetType.SPEED) {
            val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                speeds.forEach { speed ->
                    val isSelected = uiState.playbackSpeed == speed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) mxOrange.copy(alpha = 0.2f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) mxOrange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSpeedSelect(speed) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${speed}x",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        if (isSelected) {
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

        // Sleep Timer Section
        if (activeSheetType == PlayerSheetType.SLEEP_TIMER) {
            SleepTimerSheetContent(
                uiState = uiState,
                viewModel = viewModel
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
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE6181820), Color(0xF20C0C10))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)),
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
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
                            .clip(RoundedCornerShape(14.dp))
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

/**
 * Builds the 4x5 colour matrix for brightness (-1..1, additive), contrast (0..2, around mid-grey)
 * and saturation (0..2). Same order as the old shader: brightness -> contrast -> saturation.
 */
private fun buildColorGradeMatrix(brightness: Float, contrast: Float, saturation: Float): android.graphics.ColorMatrix {
    val b = brightness * 255f
    val brightnessM = android.graphics.ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, b,
        0f, 1f, 0f, 0f, b,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f
    ))
    val t = 127.5f * (1f - contrast)
    val contrastM = android.graphics.ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, t,
        0f, contrast, 0f, 0f, t,
        0f, 0f, contrast, 0f, t,
        0f, 0f, 0f, 1f, 0f
    ))
    val saturationM = android.graphics.ColorMatrix().apply { setSaturation(saturation) }

    return android.graphics.ColorMatrix(brightnessM).apply {
        postConcat(contrastM)
        postConcat(saturationM)
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

private fun formatAudioTrackSubtitleLine(track: VideoTrackInfo): String {
    val langStr = if (!track.language.isNullOrBlank()) {
        val loc = try { Locale.forLanguageTag(track.language) } catch (_: Exception) { null }
        val display = loc?.getDisplayLanguage(Locale.US)?.takeIf { it.isNotBlank() && !it.equals(track.language, ignoreCase = true) }
        display ?: track.language.uppercase(Locale.US)
    } else {
        null
    }

    val codecStr = when {
        track.format == MimeTypes.AUDIO_E_AC3 || track.format == MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        track.format == MimeTypes.AUDIO_AC3 -> "AC-3"
        track.format == MimeTypes.AUDIO_DTS || track.format == MimeTypes.AUDIO_DTS_HD -> "DTS"
        track.format == MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        track.format?.contains("flac", ignoreCase = true) == true -> "FLAC"
        track.format?.contains("alac", ignoreCase = true) == true -> "ALAC"
        track.format?.contains("mp4a", ignoreCase = true) == true || track.format?.contains("aac", ignoreCase = true) == true -> "AAC"
        track.format?.contains("opus", ignoreCase = true) == true -> "Opus"
        track.format?.contains("mpeg", ignoreCase = true) == true || track.format?.contains("mp3", ignoreCase = true) == true -> "MP3"
        !track.format.isNullOrBlank() -> track.format.substringAfter("/").uppercase(Locale.US)
        else -> null
    }

    val channelStr = when (track.channelCount) {
        6 -> "5.1"
        8 -> "7.1"
        2 -> "Stereo"
        1 -> "Mono"
        in 3..5, 7, in 9..16 -> "${track.channelCount} ch"
        else -> null
    }

    val parts = listOfNotNull(langStr, codecStr, channelStr)
    return if (parts.isNotEmpty()) parts.joinToString(" - ") else "Audio Track"
}

/**
 * Applies the chosen aspect-ratio mode directly to PlayerView's content frame.
 * FIT / FILL / ZOOM use the video's real ratio; 4:3 and 16:9 force the frame ratio.
 */
@UnstableApi
private fun applyVideoAspect(view: PlayerView, mode: VideoAspectRatio, size: VideoSize) {
    val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?: return

    var natural = if (size.width > 0 && size.height > 0) {
        size.width * size.pixelWidthHeightRatio / size.height
    } else 0f
    if (natural > 0f && (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270)) {
        natural = 1f / natural
    }

    when (mode) {
        VideoAspectRatio.FIT -> {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (natural > 0f) frame.setAspectRatio(natural)
        }
        VideoAspectRatio.FILL -> {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            if (natural > 0f) frame.setAspectRatio(natural)
        }
        VideoAspectRatio.ZOOM -> {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            if (natural > 0f) frame.setAspectRatio(natural)
        }
        VideoAspectRatio.FOUR_THREE -> {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            frame.setAspectRatio(4f / 3f)
        }
        VideoAspectRatio.SIXTEEN_NINE -> {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            frame.setAspectRatio(16f / 9f)
        }
    }
}
