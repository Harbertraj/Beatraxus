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
import com.beatraxus.app.subtitles.ui.SecondarySubtitleOverlay
import com.beatraxus.app.subtitles.ui.SubtitlePlayerSheetSection
import com.beatraxus.app.subtitles.viewmodel.SubtitleUiEvent
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModelFactory
import androidx.activity.compose.BackHandler
import com.beatraxus.app.cast.CastManager
import com.beatraxus.app.ui.components.CastButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.beatraxus.app.motionboost.MotionBoostSurfaceView
import com.beatraxus.app.fpsbooster.BoosterEngine
import com.beatraxus.app.fpsbooster.BoosterQuality
import com.beatraxus.app.fpsbooster.BoosterStats
import com.beatraxus.app.fpsbooster.FallbackReason
import com.beatraxus.app.fpsbooster.FpsBoosterSurfaceView
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.layout.layout
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
import com.beatraxus.app.model.DeinterlaceMode
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoOrientation
import com.beatraxus.app.model.VideoUpscaleMode
import com.beatraxus.app.util.PlaybackGlobalState
import com.beatraxus.app.util.VideoPlayerPrefs
import com.beatraxus.app.viewmodel.VideoAspectRatio
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import com.beatraxus.app.viewmodel.VideoPlayerViewModel
import com.beatraxus.app.viewmodel.VideoTrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import com.beatraxus.app.ui.theme.Icon

enum class GestureType {
    NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM, SUBTITLE
}

enum class PlayerSheetType {
    NONE, AUDIO, SUBTITLE, SPEED, ALL, EQUALIZER, SLEEP_TIMER, COLOR, AUDIO_DELAY, MOTION_BOOST, SURROUND, ORIENTATION, DEINTERLACE, NIGHT_FILTER, INFO, QUEUE, UPSCALER
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
    // Settings -> Video Settings (gestures, orientation, ...). Read once per player session.
    val playerOptions = remember { VideoPlayerPrefs.read(context) }
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
                // (While casting, the phone is only a remote: turning its screen off must not pause the TV.)
                if (uiState.isPlaying && !uiState.isBackgroundPlayEnabled && !isInPiP && !isReconfiguring && uiState.castDeviceName == null) {
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
    // Which tool category (Playback / Audio / Picture) is open under the title; null = all collapsed.
    var openToolCategory by remember { mutableStateOf<VpToolCategory?>(null) }

    // Night filter (feature 17): the local hour is checked once a minute, only needed for the "Auto" mode.
    var localHour by remember { mutableStateOf(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(uiState.nightFilter.mode) {
        while (uiState.nightFilter.mode == com.beatraxus.app.model.NightFilterMode.AUTO) {
            localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            delay(60_000)
        }
        localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }
    val nightFilterActive = uiState.nightFilter.isActive(localHour)
    val boostPlan = uiState.fpsBoosterPlan
    val boostActive = boostPlan.active
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

    // The viewer's custom subtitle font (video feature 23); null means the default font.
    val customSubtitleTypeface by subtitleViewModel.customTypeface.collectAsState()

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
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }
    var doubleTapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var doubleTapRippleText by remember { mutableStateOf("") }

    var showChapterStrip by remember { mutableStateOf(false) }
    var lastScrubSeek by remember { mutableLongStateOf(0L) }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, uiState.isPlaying, uiState.isLocked, uiState.scrubbingTimeMs == null, openToolCategory) {
        if (controlsVisible && uiState.scrubbingTimeMs == null && (uiState.isPlaying || uiState.isLocked)) {
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
        activity?.requestedOrientation = playerOptions.orientationMode.requestedOrientation

        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (playerOptions.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    // Touch began on the visible control panels (seekbar, buttons, top bar):
                    // leave it entirely to those controls, no screen gestures, no auto-hide.
                    val onControls = controlsVisible && (
                        firstDown.position.y > size.height - 200.dp.toPx() ||
                        firstDown.position.y < 130.dp.toPx()
                    )
                    var seekStartPos = 0L
                    var lastLiveSeek = 0L
                    var volumeAccum = 0f
                    var volumeGestureActive = false
                    
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

                        if (onControls) continue

                        if (event.changes.size > 1 && playerOptions.gestureZoom) {
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
                            // Video Settings -> Gestures & Controls: a switched-off gesture is simply ignored.
                            val intentEnabled = when (initialDragIntent) {
                                GestureType.SEEK -> playerOptions.gestureSeek
                                GestureType.BRIGHTNESS -> playerOptions.gestureBrightness
                                GestureType.VOLUME -> playerOptions.gestureVolume
                                GestureType.SUBTITLE -> playerOptions.gestureSubtitleMove
                                else -> true
                            }
                            if (!intentEnabled) initialDragIntent = GestureType.NONE
                        }

                        if (dragStarted && initialDragIntent != GestureType.NONE) {
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
                                    if (!volumeGestureActive) {
                                        volumeAccum = uiState.volume.toFloat()
                                        volumeGestureActive = true
                                    }
                                    // Float accumulator: sub-step deltas are no longer lost to Int truncation.
                                    // 1.5f = a full-height swipe covers the whole range.
                                    volumeAccum = (volumeAccum - (delta.y / size.height) * max * 1.5f)
                                        .coerceIn(0f, max.toFloat())
                                    viewModel.setVolume(volumeAccum.roundToInt())
                                    gestureValue = (volumeAccum / max) * 100
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
                    // Hold to fast-forward: speed up while the finger stays down, restore on release.
                    onPress = {
                        tryAwaitRelease()
                        if (isFastForwarding) {
                            isFastForwarding = false
                            viewModel.setPlaybackSpeed(speedBeforeHold)
                        }
                    },
                    onLongPress = {
                        if (!uiState.isLocked && playerOptions.holdToSpeedEnabled) {
                            speedBeforeHold = uiState.playbackSpeed
                            isFastForwarding = true
                            viewModel.setPlaybackSpeed(playerOptions.holdSpeed)
                        }
                    },
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (uiState.isLocked) return@detectTapGestures
                        val isCenter = offset.x > size.width / 3 && offset.x < size.width * 2 / 3
                        if (isCenter) {
                            if (playerOptions.doubleTapPlayPause) viewModel.togglePlayPause()
                        } else {
                            val isLeft = offset.x < size.width / 2
                            val delta = if (isLeft) -playerOptions.doubleTapSeekMs else playerOptions.doubleTapSeekMs
                            viewModel.seekTo(viewModel.positionFlow.value + delta)
                            doubleTapRipplePos = offset
                            doubleTapRippleText = if (isLeft) "-${playerOptions.doubleTapSeekSeconds}s" else "+${playerOptions.doubleTapSeekSeconds}s"
                        }
                    }
                )
            }
            .pointerInput(uiState.isLocked) {
                if (uiState.isLocked || !playerOptions.gestureZoom) return@pointerInput
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
        val latestOrientation by rememberUpdatedState(uiState.videoOrientation)

        // PlayerView resets its own frame ratio on every video-size change (new video,
        // rotation, format change). Re-apply ours right after it, on the same callback.
        DisposableEffect(playerViewRef) {
            val pv = playerViewRef
            val exo = viewModel.getPlayer()
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    pv?.let { applyVideoAspect(it, latestAspect, videoSize, latestOrientation) }
                }
            }
            if (pv != null) exo?.addListener(listener)
            onDispose { exo?.removeListener(listener) }
        }

        // Rotate / flip / mirror on the TextureView (a SurfaceView cannot be transformed; the FPS Booster draws it in GL).
        // The matrix depends on the view's size, which changes with every aspect-ratio or rotation change, so it is
        // applied again after each layout.
        DisposableEffect(playerViewRef, uiState.videoOrientation) {
            val texture = playerViewRef?.videoSurfaceView as? TextureView
            val current = uiState.videoOrientation
            val applyTransform = { v: TextureView ->
                val matrix = android.graphics.Matrix()
                if (v.width > 0 && v.height > 0) {
                    matrix.setValues(current.textureMatrixValues(v.width.toFloat(), v.height.toFloat()))
                }
                v.setTransform(matrix)
            }
            val layoutListener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                (v as? TextureView)?.let(applyTransform)
            }
            if (texture != null) {
                texture.addOnLayoutChangeListener(layoutListener)
                applyTransform(texture)
            }
            onDispose { texture?.removeOnLayoutChangeListener(layoutListener) }
        }

        // Colour grading is applied as a ColorMatrix filter on a TextureView layer (instant, works
        // while playing AND paused, independent of the codec/effect pipeline). A SurfaceView cannot
        // be colour-filtered, so we only use the TextureView variant while grading is in use or the
        // Color Grading sheet is open; otherwise the normal (best quality) SurfaceView is kept.
        val gradingActive = uiState.colorBrightness != 0f ||
            uiState.colorContrast != 1f ||
            uiState.colorSaturation != 1f ||
            uiState.colorHue != 0f ||
            uiState.colorTemperature != 0f
        // FPS Booster: the video is drawn by FpsBoosterSurfaceView (optical flow) or MotionBoostSurfaceView
        // (classic engine). Both do the colour grading themselves, so the TextureView path is not used.
        // HDR streams are excluded by the planner (no tone mapping in the interpolation pipeline).
        val boostPlan = uiState.fpsBoosterPlan
        val boostActive = boostPlan.active
        val boostEngine = boostPlan.engine
        // Rotate / flip / mirror also needs the TextureView: a SurfaceView cannot be transformed. (With the FPS Booster
        // the GL renderer does it itself.)
        val orientation = uiState.videoOrientation
        // The night filter multiplies the picture, which a SurfaceView cannot take, so it needs the TextureView too.
        val useTextureSurface = !boostActive &&
            (gradingActive || sheetType == PlayerSheetType.COLOR || !orientation.isIdentity || nightFilterActive)

        key(useTextureSurface, boostActive, boostEngine) {
        AndroidView(
            factory = { ctx ->
                val pv = when {
                    boostActive -> android.view.LayoutInflater.from(ctx)
                        .inflate(com.beatraxus.app.R.layout.player_view_none, null) as PlayerView
                    useTextureSurface -> android.view.LayoutInflater.from(ctx)
                        .inflate(com.beatraxus.app.R.layout.player_view_texture, null) as PlayerView
                    else -> PlayerView(ctx)
                }
                pv.apply {
                    player = viewModel.getPlayer()
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    if (boostActive) {
                        // Same spot PlayerView uses for its own surface: under the subtitle layer.
                        val frame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                        val boostView: android.view.View = if (boostEngine == BoosterEngine.OPTICAL_FLOW) {
                            FpsBoosterSurfaceView(ctx).apply {
                                this.player = viewModel.getPlayer()
                                listener = object : FpsBoosterSurfaceView.Listener {
                                    override fun onStats(stats: BoosterStats) = viewModel.updateFpsBoosterStats(stats)
                                    override fun onFallback(reason: FallbackReason, detail: String?) {
                                        viewModel.onFpsBoosterFallback(reason)
                                        android.widget.Toast.makeText(ctx, reason.message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                configure(boostPlan, uiState.fpsBooster)
                            }
                        } else {
                            MotionBoostSurfaceView(ctx).apply {
                                this.player = viewModel.getPlayer()
                                onStats = { src, out ->
                                    viewModel.updateFpsBoosterStats(
                                        BoosterStats(
                                            running = true, engine = BoosterEngine.CLASSIC, sourceFps = src,
                                            targetFps = boostPlan.targetFps, renderedFps = out
                                        )
                                    )
                                }
                                onError = { _ ->
                                    viewModel.onFpsBoosterFallback(FallbackReason.GPU_ERROR)
                                    android.widget.Toast.makeText(ctx, FallbackReason.GPU_ERROR.message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        frame?.addView(
                            boostView,
                            0,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        tag = boostView
                    }
                    playerViewRef = this
                }
            },
            onRelease = { it.player = null },
            update = { view ->
                when (val boostView = view.tag) {
                    is FpsBoosterSurfaceView -> boostView.apply {
                        configure(boostPlan, uiState.fpsBooster)
                        setDeinterlace(uiState.deinterlaceMode)
                        setColorGrade(uiState.colorBrightness, uiState.colorContrast, uiState.colorSaturation)
                        setColorTune(uiState.colorHue, uiState.colorTemperature)
                        setOrientation(orientation)
                    }
                    is MotionBoostSurfaceView -> boostView.apply {
                        setDeinterlace(uiState.deinterlaceMode)
                        setTargetFps(boostPlan.targetFps)
                        setExtreme(boostPlan.quality == BoosterQuality.PERFORMANCE)
                        setColorGrade(uiState.colorBrightness, uiState.colorContrast, uiState.colorSaturation)
                        setColorTune(uiState.colorHue, uiState.colorTemperature)
                        setOrientation(orientation)
                    }
                }
                // Apply brightness / contrast / saturation to the video layer.
                (view.videoSurfaceView as? TextureView)?.setLayerPaint(
                    if (gradingActive) {
                        android.graphics.Paint().apply {
                            colorFilter = android.graphics.ColorMatrixColorFilter(
                                buildColorGradeMatrix(
                                    uiState.colorBrightness,
                                    uiState.colorContrast,
                                    uiState.colorSaturation,
                                    uiState.colorHue,
                                    uiState.colorTemperature
                                )
                            )
                        }
                    } else null
                )

                // NOTE: no player.videoScalingMode changes here. Codec scaling only applies to
                // the NEXT rendered frame, so while paused the new mode appeared "late".
                // The frame layout (resizeMode + aspect ratio) is applied instantly instead.
                applyVideoAspect(view, uiState.aspectRatio, uiState.videoSize, orientation)
                
                // Update Subtitle Styles
                val bgColorWithOpacity = if (uiState.subtitleBackgroundOpacity > 0f) {
                    val alphaByte = (uiState.subtitleBackgroundOpacity * 255).toInt().coerceIn(0, 255)
                    val baseColor = if (uiState.subtitleBackgroundColor == android.graphics.Color.TRANSPARENT) android.graphics.Color.BLACK else uiState.subtitleBackgroundColor
                    android.graphics.Color.argb(alphaByte, android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor))
                } else {
                    uiState.subtitleBackgroundColor
                }

                val baseTypeface = customSubtitleTypeface ?: Typeface.DEFAULT
                val typeface = if (uiState.subtitleBold) Typeface.create(baseTypeface, Typeface.BOLD) else baseTypeface

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
                    // Never blur / re-saturate the video while the Color Grading or Rotate & Flip sheet is open:
                    // the user must see the REAL picture to judge the change.
                    if (sheetType != PlayerSheetType.NONE &&
                        sheetType != PlayerSheetType.COLOR &&
                        sheetType != PlayerSheetType.ORIENTATION &&
                        sheetType != PlayerSheetType.DEINTERLACE &&
                        sheetType != PlayerSheetType.UPSCALER &&
                        sheetType != PlayerSheetType.NIGHT_FILTER &&
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

        // Dual subtitles (video feature 22): the second subtitle, drawn over the video in step with the player position.
        SecondarySubtitleOverlay(
            subtitleViewModel = subtitleViewModel,
            positionProvider = { viewModel.getPlayer()?.currentPosition ?: 0L },
            primarySizeSp = uiState.subtitleSize,
            primaryTranslationY = uiState.subtitlePositionPreset.offsetDp + uiState.subtitleOffset,
            primaryBold = uiState.subtitleBold,
            typeface = customSubtitleTypeface
        )

        // Feature 26: the picture is on the TV; this screen is the remote control.
        uiState.castDeviceName?.let { deviceName ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0B0F)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Rounded.CastConnected, null, tint = VpAmberSoft, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Casting to $deviceName",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Play, pause and seek from here. Tap the cast icon to stop.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
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
                        Text("${VideoPlayerPrefs.formatSpeed(playerOptions.holdSpeed)}X Speed", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
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
                                .padding(bottom = vpBottomStackClearance())
                        )
                    }

                    // Top Bar
                    PlayerTopBar(
                        title = (if (uiState.currentVideo?.source == com.beatraxus.app.model.VideoSource.LIVE_TV) "\u25CF LIVE  " else "") +
                            (uiState.currentVideo?.title ?: ""),
                        isHdr = uiState.isHdr || uiState.hdrInfo.isHdr,
                        hdrLabel = uiState.hdrInfo.badge ?: "HDR",
                        sleepTimerRemainingMs = uiState.sleepTimerRemainingMs,
                        decoderLabel = uiState.videoDecoderMode.label,
                        isSurroundActive = uiState.surroundSimulatorEnabled,
                        onBack = onBack,
                        onDecoderClick = {
                            val mode = viewModel.cycleVideoDecoderMode()
                            val note = if (uiState.routeAudioToEngine) " (applies next start)" else ""
                            android.widget.Toast.makeText(
                                context,
                                "Decoder: ${mode.label} - ${mode.description}$note",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onAudioClick = { sheetType = PlayerSheetType.AUDIO },
                        onSubtitleClick = { sheetType = PlayerSheetType.SUBTITLE },
                        onSurroundClick = { sheetType = PlayerSheetType.SURROUND },
                        queueSize = viewModel.queue.size,
                        onQueueClick = { sheetType = PlayerSheetType.QUEUE },
                        showCast = CastManager.isConnected || CastManager.availableDevices.isNotEmpty(),
                        castDeviceName = uiState.castDeviceName,
                        toolsContent = {
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
                                onMotionBoostClick = { sheetType = PlayerSheetType.MOTION_BOOST },
                                onAudioDelayClick = { sheetType = PlayerSheetType.AUDIO_DELAY },
                                onEqClick = { sheetType = PlayerSheetType.EQUALIZER },
                                onToggleBoost = { viewModel.toggleVolumeBoost() },
                                onHeadphonesClick = { viewModel.toggleBackgroundPlay() },
                                onRotationClick = { activity?.let { viewModel.toggleOrientation(it) } },
                                onOrientationClick = { sheetType = PlayerSheetType.ORIENTATION },
                                onDeinterlaceClick = { sheetType = PlayerSheetType.DEINTERLACE },
                                onUpscalerClick = { sheetType = PlayerSheetType.UPSCALER },
                                onNightFilterClick = { sheetType = PlayerSheetType.NIGHT_FILTER },
                                onInfoClick = { sheetType = PlayerSheetType.INFO },
                                onSnapshotClick = {
                                    val pv = playerViewRef
                                    val video = uiState.currentVideo
                                    val position = viewModel.getPlayer()?.currentPosition ?: 0L
                                    val videoHeight = uiState.videoSize.height
                                    scope.launch {
                                        // On the TextureView path the picture is copied from the window: clear the controls first.
                                        val hideControls = com.beatraxus.app.util.VideoSnapshot.needsClearScreen(pv)
                                        if (hideControls) {
                                            controlsVisible = false
                                            delay(400)
                                        }
                                        val result = com.beatraxus.app.util.VideoSnapshot.captureAndSave(
                                            context, pv, video?.title, position, videoHeight
                                        )
                                        if (hideControls) controlsVisible = true
                                        val text = when (result) {
                                            is com.beatraxus.app.util.VideoSnapshot.Result.Saved ->
                                                "Screenshot saved to Gallery (Pictures/${com.beatraxus.app.model.VideoSnapshotSpec.ALBUM})"
                                            is com.beatraxus.app.util.VideoSnapshot.Result.Failed -> result.reason
                                        }
                                        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onColorClick = { sheetType = PlayerSheetType.COLOR },
                                onAbRepeatClick = { viewModel.toggleAbRepeat() },
                                onLoopClick = { viewModel.toggleLoop() },
                                onSleepTimerClick = { sheetType = PlayerSheetType.SLEEP_TIMER },
                                openCategory = openToolCategory,
                                onCategoryClick = { openToolCategory = if (openToolCategory == it) null else it }
                            )
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    // Center transport controls: previous / frame step / play-pause / next
                    PlayerCenterControls(
                        uiState = uiState,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onPlayNext = { viewModel.playNext() },
                        onPlayPrevious = { viewModel.playPrevious() },
                        onStepFrame = { viewModel.stepFrame(it) },
                        modifier = Modifier.align(Alignment.Center)
                    )

                    // Bottom Bar: seek bar, then lock | aspect ratio (tool icons are under the title)
                    PlayerBottomBar(
                        uiState = uiState,
                        positionFlow = viewModel.positionFlow,
                        onSeek = { viewModel.seekTo(it) },
                        onLock = { viewModel.toggleLock() },
                        onTimeClick = { viewModel.toggleTimeDisplay() },
                        onAspectRatioClick = {
                            viewModel.setAspectRatio(uiState.aspectRatio.next())
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
                            .padding(bottom = vpBottomStackClearance(), end = 24.dp)
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
                    // no dim for Color Grading / Rotate & Flip, so the true picture stays visible
                    .background(Color.Black.copy(alpha = if (sheetType == PlayerSheetType.COLOR || sheetType == PlayerSheetType.ORIENTATION || sheetType == PlayerSheetType.DEINTERLACE || sheetType == PlayerSheetType.UPSCALER || sheetType == PlayerSheetType.NIGHT_FILTER) 0f else 0.20f))
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
                        .widthIn(
                            min = 260.dp,
                            max = com.beatraxus.app.util.AdaptiveLayout
                                .sheetMaxWidthDp(androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp).dp
                        )
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

        // Night filter (feature 17): last child, so it tints the picture, the controls and the sheets alike.
        // It has no pointer handlers, so every touch still reaches the player underneath.
        if (nightFilterActive) {
            if (boostActive) {
                // FPS Booster draws on its own GL surface, which cannot be multiplied: keep the flat veil there.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(uiState.nightFilter.overlayArgb))
                )
            } else {
                // Multiply, not a see-through tint: blue is cut, black stays black, white turns warm (no yellow haze).
                val multiplyColor = Color(uiState.nightFilter.multiplyArgb)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(color = multiplyColor, blendMode = BlendMode.Modulate) }
                )
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

/** Flat icon used in the bottom tool row. Active = soft amber disc behind an amber icon. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VpToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    label: String? = null,
    /** Small glyph drawn in the lower-right corner of the icon (e.g. the clock on the audio delay icon). */
    badge: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentDescription: String? = null
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
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(VpAmber.copy(alpha = 0.18f * activeAmount))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val tint = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.92f), VpAmberSoft, activeAmount)
        if (label != null) {
            Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 4.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(Color(0xE6101014))
                        .border(0.8.dp, tint.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(badge, null, tint = tint, modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

/**
 * The tool icons of the video player, grouped so the row stays short.
 * Each category is ONE icon; tapping it opens the icons it holds in a second row right below it.
 * Tools that belong to no category (PiP, screenshot, video info) stay as single icons in the main row.
 */
enum class VpToolCategory(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String) {
    PLAYBACK(Icons.Rounded.SlowMotionVideo, "Playback"),
    AUDIO(Icons.Rounded.Audiotrack, "Audio"),
    PICTURE(Icons.Rounded.Palette, "Picture and screen")
}

@Composable
fun FloatingControls(
    uiState: VideoPlayerUiState,
    sheetType: PlayerSheetType,
    onPiPClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onMotionBoostClick: () -> Unit,
    onAudioDelayClick: () -> Unit,
    onEqClick: () -> Unit,
    onToggleBoost: () -> Unit,
    onHeadphonesClick: () -> Unit,
    onRotationClick: () -> Unit,
    onOrientationClick: () -> Unit,
    onDeinterlaceClick: () -> Unit,
    onUpscalerClick: () -> Unit = {},
    onSnapshotClick: () -> Unit,
    onNightFilterClick: () -> Unit,
    onInfoClick: () -> Unit,
    onColorClick: () -> Unit,
    onAbRepeatClick: () -> Unit,
    onLoopClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    /** The category whose icons are shown in the second row, or null when all are collapsed. */
    openCategory: VpToolCategory? = null,
    onCategoryClick: (VpToolCategory) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val abRepeatOn = uiState.isAbRepeatActive || uiState.abRepeatPointA != null
    val sleepTimerOn = uiState.sleepTimerMode != com.beatraxus.app.viewmodel.SleepTimerMode.OFF
    val nightFilterOn = uiState.nightFilter.mode != com.beatraxus.app.model.NightFilterMode.OFF
    val colorChanged = uiState.colorBrightness != 0f || uiState.colorContrast != 1f || uiState.colorSaturation != 1f ||
        uiState.colorGamma != 1f || uiState.colorHue != 0f || uiState.colorTemperature != 0f || uiState.colorSharpness != 0f

    // A category lights up when any tool inside it is switched on, so nothing is hidden silently.
    fun categoryActive(category: VpToolCategory): Boolean = when (category) {
        VpToolCategory.PLAYBACK ->
            uiState.isLooping || abRepeatOn || sleepTimerOn || uiState.playbackSpeed != 1.0f || uiState.fpsBoosterPlan.active
        VpToolCategory.AUDIO ->
            uiState.audioDelayMs != 0 || uiState.isEqEnabled || uiState.isVolumeBoost || uiState.isBackgroundPlayEnabled
        VpToolCategory.PICTURE ->
            !uiState.videoOrientation.isIdentity || uiState.deinterlaceMode.isOn || uiState.upscaleMode.isOn ||
                nightFilterOn || colorChanged
    }

    @Composable
    fun CategoryButton(category: VpToolCategory) {
        val open = openCategory == category
        VpToolButton(
            icon = category.icon,
            active = open || categoryActive(category),
            onClick = { onCategoryClick(category) },
            // Little arrow = "this icon holds more icons"
            badge = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = category.title + " tools"
        )
    }

    // Main row (centred) + the opened category's icons right below it.
    Column(
        modifier = modifier.pointerInput(Unit) {
            // Consume all touches/gestures to prevent them from triggering
            // underlying player navigation gestures (seek, brightness, etc.)
            detectTapGestures { }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // No category: shown directly
            VpToolButton(Icons.Rounded.PictureInPicture, false, onPiPClick, contentDescription = "Picture in picture")
            CategoryButton(VpToolCategory.PLAYBACK)
            CategoryButton(VpToolCategory.AUDIO)
            CategoryButton(VpToolCategory.PICTURE)
            VpToolButton(
                Icons.Rounded.PhotoCamera,
                false,
                onSnapshotClick,
                contentDescription = "Save a screenshot to the gallery"
            )
            VpToolButton(
                Icons.Rounded.Info,
                false,
                onInfoClick,
                contentDescription = "Video info: codec, bitrate and frame rate"
            )
        }

        AnimatedContent(
            targetState = openCategory,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "vpToolCategory"
        ) { category ->
            if (category != null) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when (category) {
                        VpToolCategory.PLAYBACK -> {
                            VpToolButton(
                                if (uiState.isLooping) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                uiState.isLooping,
                                onLoopClick,
                                contentDescription = "Loop"
                            )
                            VpToolButton(
                                Icons.Rounded.Repeat,
                                abRepeatOn,
                                onAbRepeatClick,
                                label = when {
                                    uiState.isAbRepeatActive -> "A-B"
                                    uiState.abRepeatPointA != null -> "A-"
                                    else -> "A-B"
                                }
                            )
                            VpToolButton(Icons.Rounded.Bedtime, sleepTimerOn, onSleepTimerClick, contentDescription = "Sleep timer")
                            VpToolButton(Icons.Rounded.Speed, uiState.playbackSpeed != 1.0f, onSpeedClick, contentDescription = "Playback speed")
                            VpToolButton(
                                Icons.Rounded.Animation,
                                uiState.fpsBoosterPlan.active,
                                onMotionBoostClick,
                                label = if (uiState.fpsBoosterPlan.active) uiState.fpsBoosterPlan.targetFps.toString() else null,
                                contentDescription = "Motion boost"
                            )
                        }
                        VpToolCategory.AUDIO -> {
                            // Audio delay: sound-wave glyph with a small clock badge = "audio timing"
                            VpToolButton(
                                Icons.Rounded.GraphicEq,
                                uiState.audioDelayMs != 0,
                                onAudioDelayClick,
                                badge = Icons.Rounded.Schedule,
                                contentDescription = "Audio delay"
                            )
                            VpToolButton(Icons.Rounded.Equalizer, uiState.isEqEnabled, onEqClick, contentDescription = "Equalizer")
                            VpToolButton(
                                if (uiState.isVolumeBoost) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown,
                                uiState.isVolumeBoost,
                                onToggleBoost,
                                contentDescription = "Volume boost"
                            )
                            VpToolButton(Icons.Rounded.Headset, uiState.isBackgroundPlayEnabled, onHeadphonesClick, contentDescription = "Background play")
                        }
                        VpToolCategory.PICTURE -> {
                            VpToolButton(Icons.Rounded.ScreenRotation, false, onRotationClick, contentDescription = "Screen rotation")
                            VpToolButton(
                                Icons.Rounded.Flip,
                                !uiState.videoOrientation.isIdentity,
                                onOrientationClick,
                                contentDescription = "Rotate, flip and mirror the video"
                            )
                            VpToolButton(
                                Icons.Rounded.Deblur,
                                uiState.deinterlaceMode.isOn,
                                onDeinterlaceClick,
                                contentDescription = "Deinterlacing"
                            )
                            VpToolButton(
                                Icons.Rounded.AutoFixHigh,
                                uiState.upscaleMode.isOn,
                                onUpscalerClick,
                                contentDescription = "Video upscaler"
                            )
                            VpToolButton(
                                Icons.Rounded.Nightlight,
                                nightFilterOn,
                                onNightFilterClick,
                                contentDescription = "Night filter"
                            )
                            VpToolButton(Icons.Rounded.Tune, colorChanged, onColorClick, contentDescription = "Color adjustments")
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(0.dp))
            }
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

/** Flat, background-less icon button (MX Player style). [active] tints the content and adds a soft amber disc. */
@Composable
private fun VpFlatButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "vpFlatScale"
    )
    val activeAmount by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(200),
        label = "vpFlatActive"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(VpAmber.copy(alpha = 0.18f * activeAmount))
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

/** Space the bottom control stack needs, so floating items (Skip Intro, chapter strip) sit above it. */
private fun vpBottomStackClearance(): Dp = 116.dp

@Composable
fun PlayerTopBar(
    title: String,
    isHdr: Boolean,
    /** Text of the HDR badge: "HDR10", "HLG", "DV" or plain "HDR" while the format is not known yet. */
    hdrLabel: String = "HDR",
    sleepTimerRemainingMs: Long? = null,
    decoderLabel: String,
    /** The surround simulator switch is ON (lights the icon). The icon itself is always shown. */
    isSurroundActive: Boolean,
    onBack: () -> Unit,
    onDecoderClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onSurroundClick: () -> Unit,
    /** Number of videos in the queue; the "Up next" button shows only for 2 or more. */
    queueSize: Int = 0,
    onQueueClick: () -> Unit = {},
    /** Feature 26: a Cast device is around (or connected), so the Cast button is shown. */
    showCast: Boolean = false,
    /** Feature 26: non-null while the video plays on that Cast device (lights the Cast button). */
    castDeviceName: String? = null,
    /** The tool icons (grouped by category). Drawn under the title row, centred at the top. */
    toolsContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(0.78f), Color.Black.copy(0.34f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Back + title on the left, like every popular player
            VpFlatButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(2.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
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
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFFE082), Color(0xFFFFB300))))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        hdrLabel,
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp
                    )
                }
            }
            // Decoder: no sheet, each tap switches to the next mode (HW -> HW+ -> SW -> HW)
            val decoderInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                    .clickable(
                        interactionSource = decoderInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = onDecoderClick
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    decoderLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }
            VpFlatButton(onClick = onAudioClick) {
                Icon(Icons.Rounded.AudioFile, "Audio track", tint = Color.White, modifier = Modifier.size(23.dp))
            }
            // Surround simulator: always here (never appears / disappears); lit while its switch is ON.
            SurroundTopIcon(active = isSurroundActive, onClick = onSurroundClick)
            VpFlatButton(onClick = onSubtitleClick) {
                Icon(Icons.Rounded.Subtitles, "Subtitles", tint = Color.White, modifier = Modifier.size(23.dp))
            }
            if (queueSize > 1) {
                VpFlatButton(onClick = onQueueClick) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, "Up next", tint = Color.White, modifier = Modifier.size(25.dp))
                }
            }
            if (showCast) {
                // The system Cast button opens the device picker (or the "stop casting" dialog when connected).
                VpFlatButton(onClick = {}, active = castDeviceName != null) {
                    CastButton(modifier = Modifier.fillMaxSize(), tint = Color.White)
                }
            }
        }
        // Tool icons: under the title, horizontally centred (not at the bottom any more)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            toolsContent()
        }
    }
}

/** Opens the surround simulator sheet. */
@Composable
private fun SurroundTopIcon(active: Boolean, onClick: () -> Unit) {
    VpFlatButton(onClick = onClick, active = active) {
        Icon(
            Icons.Rounded.SurroundSound,
            "Surround simulator",
            tint = if (active) VpAmberSoft else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Transport controls in the middle of the picture (previous, frame step, play/pause, next),
 * the way MX Player / YouTube / VLC lay them out. The frame-step buttons still appear only while paused.
 */
@Composable
fun PlayerCenterControls(
    uiState: VideoPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onStepFrame: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        VpGlassButton(onClick = onPlayPrevious, size = 52.dp) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
        }

        AnimatedVisibility(
            visible = !uiState.isPlaying,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            VpGlassButton(onClick = { onStepFrame(false) }, size = 38.dp) {
                Icon(Icons.Rounded.KeyboardArrowLeft, "Previous frame", tint = Color.White.copy(0.9f), modifier = Modifier.size(26.dp))
            }
        }

        Box(
            modifier = Modifier
                .size(78.dp)
                .shadow(18.dp, CircleShape, ambientColor = VpAmber, spotColor = VpAmber)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.46f))
                .border(2.dp, Brush.linearGradient(listOf(VpAmberSoft, VpAmber)), CircleShape)
                .clickable(role = Role.Button, onClick = onTogglePlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (uiState.isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(46.dp)
            )
        }

        AnimatedVisibility(
            visible = !uiState.isPlaying,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            VpGlassButton(onClick = { onStepFrame(true) }, size = 38.dp) {
                Icon(Icons.Rounded.KeyboardArrowRight, "Next frame", tint = Color.White.copy(0.9f), modifier = Modifier.size(26.dp))
            }
        }

        VpGlassButton(onClick = onPlayNext, size = 52.dp) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

/** Bottom area: seek bar with both times, then one row of  lock | aspect ratio. (The tool icons live under the title.) */
@Composable
fun PlayerBottomBar(
    uiState: VideoPlayerUiState,
    positionFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit,
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
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f), Color.Black.copy(alpha = 0.84f))
                )
            )
            .padding(top = 40.dp, bottom = 8.dp, start = 14.dp, end = 14.dp)
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
                                .shadow(16.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black, spotColor = VpAmber)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xE60C0C10))
                                .border(
                                    1.dp,
                                    Brush.verticalGradient(listOf(VpAmberSoft.copy(alpha = 0.7f), Color.White.copy(alpha = 0.08f))),
                                    RoundedCornerShape(12.dp)
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
                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
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

            // Seek bar:  current time  |  slider  |  total / remaining time
            val currentPosition by positionFlow.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(currentPosition),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                val mxOrange = VpAmber
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
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
                                    .size(15.dp)
                                    .shadow(6.dp, CircleShape, ambientColor = VpAmber, spotColor = VpAmber)
                                    .background(mxOrange, CircleShape)
                                    .border(2.dp, Color.White, CircleShape)
                            )
                        },
                        track = { sliderState ->
                            val range = sliderState.valueRange
                            val span = (range.endInclusive - range.start).coerceAtLeast(1f)
                            val fraction = ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.26f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(mxOrange)
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
                                .height(4.dp)
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
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTimeClick() }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }

            // Lock and aspect ratio. Pulled up under the seek bar: the slider's 48dp touch area leaves a big gap otherwise.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val pull = 12.dp.roundToPx()
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height - pull) { placeable.placeRelative(0, -pull) }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VpFlatButton(onClick = onLock, size = 40.dp, active = uiState.isLocked) {
                    Icon(
                        if (uiState.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        if (uiState.isLocked) "Unlock screen" else "Lock screen",
                        tint = if (uiState.isLocked) VpAmberSoft else Color.White.copy(0.92f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                VpFlatButton(onClick = onAspectRatioClick, size = 40.dp) {
                    Icon(Icons.Rounded.AspectRatio, "Aspect ratio", tint = Color.White.copy(0.92f), modifier = Modifier.size(22.dp))
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

        // Feature 25: "Add audio track" opens the system file picker; the chosen file is merged into the video.
        val audioFileContext = LocalContext.current
        val audioFilePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { picked: android.net.Uri? ->
            if (picked != null) {
                val fileName = runCatching {
                    audioFileContext.contentResolver.query(
                        picked, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: picked.lastPathSegment
                viewModel.addExternalAudio(picked, fileName)
            }
        }
        // EQ is handled by DSP Studio while video audio is routed to the Audio Engine
        val eqLockedByEngine = uiState.routeAudioToEngine || uiState.isConnectingEngineRoute
        
        val title = when (activeSheetType) {
            PlayerSheetType.AUDIO -> "Audio Tracks"
            PlayerSheetType.AUDIO_DELAY -> "Audio Delay"
            PlayerSheetType.SUBTITLE -> "Subtitles"
            PlayerSheetType.SPEED -> "Playback Speed"
            PlayerSheetType.MOTION_BOOST -> "FPS Booster"
            PlayerSheetType.EQUALIZER -> "Premium Equalizer"
            PlayerSheetType.SLEEP_TIMER -> "Sleep Timer"
            PlayerSheetType.COLOR -> "Color Grading"
            PlayerSheetType.SURROUND -> "Surround Simulator"
            PlayerSheetType.ORIENTATION -> "Rotate & Flip"
            PlayerSheetType.DEINTERLACE -> "Deinterlacing"
            PlayerSheetType.NIGHT_FILTER -> "Night Filter"
            PlayerSheetType.INFO -> "Video Info"
            PlayerSheetType.QUEUE -> "Up next"
            PlayerSheetType.UPSCALER -> "Video Upscaler"
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

                if (activeSheetType == PlayerSheetType.INFO) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    val copyContext = LocalContext.current
                    IconButton(
                        onClick = {
                            uiState.videoInfo?.let { info ->
                                val text = com.beatraxus.app.model.VideoInfoReport.toPlainText(
                                    com.beatraxus.app.model.VideoInfoReport.sections(info)
                                )
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                                android.widget.Toast.makeText(copyContext, "Video info copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = uiState.videoInfo != null
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            "Copy video info",
                            tint = Color.White.copy(alpha = if (uiState.videoInfo != null) 1f else 0.35f)
                        )
                    }
                }

                if (activeSheetType == PlayerSheetType.ORIENTATION) {
                    IconButton(
                        onClick = { viewModel.resetVideoOrientation() },
                        enabled = !uiState.videoOrientation.isIdentity
                    ) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            "Reset",
                            tint = Color.White.copy(alpha = if (uiState.videoOrientation.isIdentity) 0.35f else 1f)
                        )
                    }
                }
            }
        }

        if (activeSheetType == PlayerSheetType.AUDIO) {
            // "Connect to audio mode": the soundtrack goes through the Audio Engine with the Studio DSP chain. The surround
            // simulator (always available from its top icon) is added to that pipeline when it is ON and skipped when OFF.
            val canRoute = uiState.availableAudioTracks.isNotEmpty() && uiState.playbackSpeed == 1.0f && !uiState.isConnectingEngineRoute
            val routedOrConnecting = uiState.routeAudioToEngine || uiState.isConnectingEngineRoute
            val audioModeOn = routedOrConnecting && !uiState.surroundEngineRoute

            // "Connect to audio mode" toggle sits on its own line, below the title
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Connect to audio mode",
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (uiState.isConnectingEngineRoute && !uiState.surroundEngineRoute) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = mxOrange,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = audioModeOn,
                    enabled = canRoute,
                    onCheckedChange = { viewModel.setAudioMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = mxOrange,
                        checkedTrackColor = mxOrange.copy(0.4f),
                        disabledCheckedThumbColor = mxOrange.copy(0.4f),
                        disabledUncheckedThumbColor = Color.Gray.copy(0.4f)
                    )
                )
            }

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
                    text = when {
                        uiState.surroundEngineRoute -> "Audio is playing through the surround simulator (no DSP effects)"
                        uiState.surroundSimulatorEnabled -> "Audio is playing through the Audio Engine (DSP / Hi-Res) with the surround simulator"
                        else -> "Audio is playing through the Audio Engine (DSP / Hi-Res)"
                    },
                    color = mxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (uiState.surroundFallback) {
                    Text(
                        text = "This output refused a multichannel stream, so the sound plays in the track's own layout",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
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
                            text = when {
                                uiState.surroundEngineRoute -> "Audio track connected with the surround simulator"
                                uiState.surroundSimulatorEnabled -> "Audio track connected with DSP Studio (Audio mode) and the surround simulator"
                                else -> "Audio track connected with DSP Studio (Audio mode)"
                            },
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

        // Surround Simulator Section
        if (activeSheetType == PlayerSheetType.SURROUND) {
            VideoSurroundSection(
                uiState = uiState,
                onEnabled = { viewModel.setSurroundSimulatorEnabled(it) },
                onLayout = { viewModel.setSurroundLayout(it) },
                onMode = { viewModel.setSurroundMode(it) },
                onPreset = { viewModel.applySurroundPreset(it) },
                onTrim = { index, db -> viewModel.setSurroundChannelTrim(index, db) },
                onResetTrims = { viewModel.resetSurroundTrims() },
                onSaveCustom = { viewModel.saveSurroundCustomPreset() }
            )
        }

        // HDR format card (replaces the old "Force SDR" switch, which was not connected to anything)
        if ((uiState.isHdr || uiState.hdrInfo.isHdr) && (activeSheetType == PlayerSheetType.COLOR || activeSheetType == PlayerSheetType.ALL)) {
            val hdr = uiState.hdrInfo
            val missingDv = hdr.needsDolbyVisionDecoder && !viewModel.hasDolbyVisionDecoder
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(0.05f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (hdr.isHdr) "HDR video: ${hdr.label}" else "HDR video",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                hdr.profileNote?.let { Text(it, color = Color.White.copy(0.7f), fontSize = 12.sp) }
                Text("Your screen: ${viewModel.displayHdrSupport}", color = Color.White.copy(0.7f), fontSize = 12.sp)
                if (hdr.kind == com.beatraxus.app.model.HdrKind.DOLBY_VISION) {
                    Text(
                        "Dolby Vision decoder: " + if (viewModel.hasDolbyVisionDecoder) "available" else "not on this device",
                        color = Color.White.copy(0.7f), fontSize = 12.sp
                    )
                }
                if (missingDv) {
                    Text(
                        "This video may show wrong colours (purple / green) on this device.",
                        color = mxOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "HDR10+ files show as HDR10 here, because Android does not report their extra scene data. " +
                        "On a screen without HDR, Android converts HDR to SDR by itself.",
                    color = Color.White.copy(0.5f), fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Audio Tracks
        if (activeSheetType == PlayerSheetType.AUDIO || activeSheetType == PlayerSheetType.ALL) {
            // Feature 25: load an external audio file (e.g. a dub or commentary track) for this video.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .clickable {
                        audioFilePicker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream"))
                    }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddCircleOutline,
                    contentDescription = null,
                    tint = mxOrange,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add audio track", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = uiState.externalAudioName?.let { "Loaded: $it" } ?: "Load an audio file from your device",
                        color = Color.White.copy(0.6f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (uiState.externalAudioName != null) {
                    IconButton(onClick = { viewModel.removeExternalAudio() }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove external audio",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

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

        // Audio delay (opened from the top bar)
        if (activeSheetType == PlayerSheetType.AUDIO_DELAY || activeSheetType == PlayerSheetType.ALL) {
            Spacer(Modifier.height(16.dp))
            AudioDelaySection(uiState = uiState, viewModel = viewModel)
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

        // FPS Booster Section
        if (activeSheetType == PlayerSheetType.MOTION_BOOST) {
            FpsBoosterSheetContent(
                uiState = uiState,
                onSettingsChange = { viewModel.setFpsBoosterSettings(it) },
                onRunBenchmark = { viewModel.startFpsBoosterBenchmark() },
                onSkipBenchmark = { viewModel.skipFpsBoosterBenchmark() }
            )
        }

        // Rotate & Flip Section
        if (activeSheetType == PlayerSheetType.ORIENTATION) {
            VideoOrientationSection(uiState = uiState, viewModel = viewModel)
        }

        // Video Info Section
        if (activeSheetType == PlayerSheetType.INFO) {
            VideoInfoSection(uiState = uiState, viewModel = viewModel)
        }

        // Up next (queue) Section
        if (activeSheetType == PlayerSheetType.QUEUE) {
            VideoQueueSection(uiState = uiState, viewModel = viewModel)
        }

        // Night Filter Section
        if (activeSheetType == PlayerSheetType.NIGHT_FILTER) {
            VideoNightFilterSection(uiState = uiState, viewModel = viewModel)
        }

        // Upscaler Section
        if (activeSheetType == PlayerSheetType.UPSCALER) {
            VideoUpscalerSection(uiState = uiState, viewModel = viewModel)
        }

        // Deinterlacing Section
        if (activeSheetType == PlayerSheetType.DEINTERLACE) {
            VideoDeinterlaceSection(uiState = uiState, viewModel = viewModel)
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


/**
 * "Up next" sheet: the video that is playing, then everything queued after it, then the videos played earlier.
 * Tapping a row jumps to that video.
 */
@Composable
fun VideoQueueSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    val queue = viewModel.queue
    val currentId = uiState.currentVideo?.id
    val currentIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val accent = Color(0xFFFF8F00)

    @Composable
    fun QueueRow(index: Int, video: Video, isCurrent: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isCurrent) accent.copy(alpha = 0.16f) else Color.Transparent)
                .clickable(enabled = !isCurrent) { viewModel.playQueueItem(video.id) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = accent, modifier = Modifier.size(20.dp))
                } else {
                    Text("${index + 1}", color = Color.White.copy(0.5f), fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    video.title.ifBlank { video.displayName },
                    color = if (isCurrent) accent else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                val resolution = if (video.resolutionHeight > 0) "  \u2022  ${video.resolutionHeight}p" else ""
                Text(
                    formatTime(video.durationMs) + resolution,
                    color = Color.White.copy(0.55f),
                    fontSize = 11.sp
                )
            }
        }
    }

    if (queue.isEmpty()) {
        Text("The queue is empty", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        return
    }

    SettingSectionHeader("Now playing")
    QueueRow(currentIndex, queue[currentIndex], isCurrent = true)

    val upNext = queue.drop(currentIndex + 1)
    if (upNext.isNotEmpty()) {
        SettingSectionHeader("Up next (${upNext.size})")
        upNext.forEachIndexed { i, v -> key(v.id) { QueueRow(currentIndex + 1 + i, v, isCurrent = false) } }
    }

    val earlier = queue.take(currentIndex)
    if (earlier.isNotEmpty()) {
        SettingSectionHeader("Played earlier (${earlier.size})")
        earlier.forEachIndexed { i, v -> key(v.id) { QueueRow(i, v, isCurrent = false) } }
    }
}

/** Video info panel (feature 20): codec, resolution, frame rate, bitrate, decoder and audio facts. Updates every second. */
@Composable
fun VideoInfoSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    // Only runs while this section is on screen; dropped frames change as the video plays.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshVideoInfo()
            delay(1000)
        }
    }
    val info = uiState.videoInfo
    val sections = remember(info) { info?.let { com.beatraxus.app.model.VideoInfoReport.sections(it) }.orEmpty() }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (sections.isEmpty()) {
            Text("Reading video info...", color = Color.White.copy(0.6f), fontSize = 13.sp)
        }
        sections.forEach { section ->
            SettingSectionHeader(section.title)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(0.05f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                section.rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Text(row.label, color = Color.White.copy(0.55f), fontSize = 12.sp, modifier = Modifier.width(92.dp))
                        Text(row.value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Night / blue-light filter choices (feature 17). The tint over the player changes live while this sheet is open. */
@Composable
fun VideoNightFilterSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    val mxOrange = Color(0xFFFF8F00)
    val filter = uiState.nightFilter
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSectionHeader("When")
        com.beatraxus.app.model.NightFilterMode.entries.forEach { mode ->
            val selected = filter.mode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) mxOrange.copy(0.16f) else Color.White.copy(0.05f))
                    .clickable { viewModel.setNightFilterMode(mode) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { viewModel.setNightFilterMode(mode) },
                    colors = RadioButtonDefaults.colors(selectedColor = mxOrange, unselectedColor = Color.White.copy(0.6f))
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(mode.description, color = Color.White.copy(0.6f), fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingSectionHeader("Strength")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = filter.strength.toFloat(),
                onValueChange = { viewModel.setNightFilterStrength(it.toInt()) },
                onValueChangeFinished = { viewModel.commitNightFilterStrength() },
                valueRange = 0f..100f,
                enabled = filter.mode != com.beatraxus.app.model.NightFilterMode.OFF,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = mxOrange,
                    activeTrackColor = mxOrange,
                    inactiveTrackColor = Color.White.copy(0.3f)
                )
            )
            Spacer(Modifier.width(8.dp))
            Text("${filter.strength}%", color = Color.White.copy(0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Cuts blue light across the whole player for easier night viewing. Saved for all videos.",
            color = Color.White.copy(0.55f),
            fontSize = 12.sp
        )
    }
}

/** Deinterlacing choices (feature 15). The picture changes as soon as a row is tapped. */
@Composable
fun VideoDeinterlaceSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    val mxOrange = Color(0xFFFF8F00)
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSectionHeader("Interlaced video")
        DeinterlaceMode.entries.forEach { mode ->
            val selected = uiState.deinterlaceMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) mxOrange.copy(0.16f) else Color.White.copy(0.05f))
                    .clickable { viewModel.setDeinterlaceMode(mode) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { viewModel.setDeinterlaceMode(mode) },
                    colors = RadioButtonDefaults.colors(selectedColor = mxOrange, unselectedColor = Color.White.copy(0.6f))
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(mode.description, color = Color.White.copy(0.6f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Removes the comb-like lines that moving objects show in interlaced video (old TV, DVD, 1080i). " +
                "Saved for all videos.",
            color = Color.White.copy(0.55f),
            fontSize = 12.sp
        )
    }
}

/** Upscaler choices. The picture changes as soon as a row is tapped. */
@Composable
fun VideoUpscalerSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    val mxOrange = Color(0xFFFF8F00)
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSectionHeader("Upscaler")
        VideoUpscaleMode.entries.forEach { mode ->
            val selected = uiState.upscaleMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) mxOrange.copy(0.16f) else Color.White.copy(0.05f))
                    .clickable { viewModel.setUpscaleMode(mode) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { viewModel.setUpscaleMode(mode) },
                    colors = RadioButtonDefaults.colors(selectedColor = mxOrange, unselectedColor = Color.White.copy(0.6f))
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(mode.description, color = Color.White.copy(0.6f), fontSize = 12.sp)
                }
            }
        }
        if (uiState.upscaleMode.isOn) {
            Spacer(Modifier.height(4.dp))
            ColorSliderRow(
                label = "Sharpening",
                value = uiState.upscaleStrength,
                valueRange = 0f..1f,
                onValueChange = { viewModel.setUpscaleStrength(it) },
                mxOrange = mxOrange
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Redraws each frame larger on the GPU, up to 2x the file size and never beyond 4K. " +
                "Uses more battery, and is not used while the FPS Booster is on. Saved for all videos.",
            color = Color.White.copy(0.55f),
            fontSize = 12.sp
        )
    }
}

/** Rotate / flip / mirror controls of the "Rotate & Flip" sheet. Changes show on the picture straight away. */
@Composable
fun VideoOrientationSection(uiState: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    val mxOrange = Color(0xFFFF8F00)
    val o = uiState.videoOrientation
    val switchColors = SwitchDefaults.colors(checkedThumbColor = mxOrange, checkedTrackColor = mxOrange.copy(0.4f))

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSectionHeader("Picture")

        OrientationActionRow(
            icon = Icons.Rounded.RotateRight,
            title = "Rotate",
            subtitle = "Turn the picture 90° clockwise",
            onClick = { viewModel.rotateVideo() }
        ) {
            Text(
                text = "${o.rotationDegrees}°",
                color = if (o.turns != 0) mxOrange else Color.White.copy(0.7f),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.height(8.dp))
        OrientationActionRow(
            icon = Icons.Rounded.Flip,
            title = "Mirror",
            subtitle = "Swap left and right",
            onClick = { viewModel.toggleVideoMirror() }
        ) {
            Switch(checked = o.flipHorizontal, onCheckedChange = { viewModel.toggleVideoMirror() }, colors = switchColors)
        }

        Spacer(Modifier.height(8.dp))
        OrientationActionRow(
            icon = Icons.Rounded.SwapVert,
            title = "Flip vertically",
            subtitle = "Swap top and bottom",
            onClick = { viewModel.toggleVideoFlipVertical() }
        ) {
            Switch(checked = o.flipVertical, onCheckedChange = { viewModel.toggleVideoFlipVertical() }, colors = switchColors)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Applies to every video in this session. Subtitles stay upright.",
            color = Color.White.copy(0.55f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun OrientationActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(0.6f), fontSize = 12.sp)
        }
        trailing()
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
private fun buildColorGradeMatrix(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    hueDegrees: Float = 0f,
    temperature: Float = 0f
): android.graphics.ColorMatrix {
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
        if (!com.beatraxus.app.colorgrade.ColorTune.isIdentity(hueDegrees, temperature)) {
            // Same hue + temperature matrix the FPS Booster shaders use, so both paths give the same picture.
            val m = com.beatraxus.app.colorgrade.ColorTune.matrix(hueDegrees, temperature)
            postConcat(android.graphics.ColorMatrix(floatArrayOf(
                m[0], m[1], m[2], 0f, 0f,
                m[3], m[4], m[5], 0f, 0f,
                m[6], m[7], m[8], 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
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
        ColorSliderRow(
            label = "Gamma",
            value = uiState.colorGamma,
            valueRange = 0.5f..2f,
            onValueChange = { viewModel.setColorExtras(it, uiState.colorHue, uiState.colorTemperature, uiState.colorSharpness) },
            mxOrange = mxOrange
        )
        ColorSliderRow(
            label = "Hue",
            value = uiState.colorHue,
            valueRange = -180f..180f,
            onValueChange = { viewModel.setColorExtras(uiState.colorGamma, it, uiState.colorTemperature, uiState.colorSharpness) },
            mxOrange = mxOrange,
            format = "%.0f\u00B0"
        )
        ColorSliderRow(
            label = "Color temperature (cool \u2194 warm)",
            value = uiState.colorTemperature,
            valueRange = -1f..1f,
            onValueChange = { viewModel.setColorExtras(uiState.colorGamma, uiState.colorHue, it, uiState.colorSharpness) },
            mxOrange = mxOrange
        )
        ColorSliderRow(
            label = "Sharpness",
            value = uiState.colorSharpness,
            valueRange = 0f..1f,
            onValueChange = { viewModel.setColorExtras(uiState.colorGamma, uiState.colorHue, uiState.colorTemperature, it) },
            mxOrange = mxOrange
        )
    }
}

@Composable
fun ColorSliderRow(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, mxOrange: Color, format: String = "%.2f") {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(0.7f), fontSize = 14.sp)
            Text(String.format(format, value), color = mxOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
 * FIT / FILL / ZOOM use the video's real ratio; every mode with a [VideoAspectRatio.forcedRatio]
 * (4:3, 16:9, 16:10, 21:9, 2.35:1) forces the frame to that ratio. When the picture is rotated by 90 / 270 degrees
 * the video's real ratio is inverted first, so the same modes still fit / fill / zoom the rotated picture.
 */
@UnstableApi
private fun applyVideoAspect(view: PlayerView, mode: VideoAspectRatio, size: VideoSize, orientation: VideoOrientation) {
    val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?: return

    var natural = if (size.width > 0 && size.height > 0) {
        size.width * size.pixelWidthHeightRatio / size.height
    } else 0f
    if (natural > 0f && (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270)) {
        natural = 1f / natural
    }
    // A picture turned by 90 / 270 degrees is as tall as it was wide: the frame follows the rotated shape.
    if (natural > 0f && orientation.swapsAxes) {
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
        VideoAspectRatio.FOUR_THREE,
        VideoAspectRatio.SIXTEEN_NINE,
        VideoAspectRatio.SIXTEEN_TEN,
        VideoAspectRatio.TWENTY_ONE_NINE,
        VideoAspectRatio.CINEMASCOPE -> {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            mode.forcedRatio?.let { frame.setAspectRatio(it) }
        }
    }
}

private fun formatAudioDelay(delayMs: Int): String = if (delayMs > 0) "+$delayMs ms" else "$delayMs ms"

/** Shifts the sound against the picture: positive values play the audio later. */
@Composable
private fun AudioDelaySection(
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel
) {
    val enabled = !uiState.routeAudioToEngine && !uiState.isConnectingEngineRoute
    val delayMs = uiState.audioDelayMs
    val step = com.beatraxus.app.engine.VideoPlaybackTuning.AUDIO_DELAY_STEP_MS
    val active = delayMs != 0 && enabled
    val sliderRange = 2000f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.09f), Color.White.copy(0.04f))))
            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (enabled) "Positive = sound later than the picture"
            else "Not available while audio plays through the Audio Engine",
            color = Color.White.copy(0.6f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))

        // Big read-out: always one line.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (delayMs > 0) "+$delayMs" else "$delayMs",
                color = if (active) VpAmberSoft else Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "ms",
                color = Color.White.copy(0.6f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(bottom = 7.dp)
            )
        }
        Text(
            text = when {
                delayMs > 0 -> "Audio plays later"
                delayMs < 0 -> "Audio plays earlier"
                else -> "In sync"
            },
            color = Color.White.copy(0.55f),
            fontSize = 12.sp,
            maxLines = 1
        )

        Spacer(Modifier.height(6.dp))
        Slider(
            value = delayMs.toFloat().coerceIn(-sliderRange, sliderRange),
            onValueChange = { v ->
                if (enabled) viewModel.setAudioDelayMs((v / step).roundToInt() * step)
            },
            valueRange = -sliderRange..sliderRange,
            steps = (sliderRange * 2 / step).toInt() - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = VpAmberSoft,
                activeTrackColor = VpAmber,
                inactiveTrackColor = Color.White.copy(0.16f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DelayStepButton("-500", enabled, Modifier.weight(1f)) { viewModel.adjustAudioDelayMs(-500) }
            DelayStepButton("-$step", enabled, Modifier.weight(1f)) { viewModel.adjustAudioDelayMs(-step) }
            DelayStepButton(null, enabled && delayMs != 0, Modifier.weight(1f), highlight = true) {
                viewModel.setAudioDelayMs(0)
            }
            DelayStepButton("+$step", enabled, Modifier.weight(1f)) { viewModel.adjustAudioDelayMs(step) }
            DelayStepButton("+500", enabled, Modifier.weight(1f)) { viewModel.adjustAudioDelayMs(500) }
        }
    }
}

/** One fixed-height pill of the delay row. A null [label] draws the reset icon. Text never wraps. */
@Composable
private fun DelayStepButton(
    label: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(if (highlight && enabled) VpAmber.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
            .border(
                1.dp,
                if (highlight && enabled) VpAmberSoft.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.10f),
                shape
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label == null) {
            Icon(
                Icons.Rounded.RestartAlt,
                contentDescription = "Reset audio delay",
                tint = if (highlight && enabled) VpAmberSoft else Color.White,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}
