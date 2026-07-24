package com.beatraxus.app.ui.screens

import androidx.activity.compose.BackHandler
import com.beatraxus.app.utils.ImageUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.Canvas
import com.beatraxus.app.ui.components.CastButton
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beatraxus.app.model.LibraryMode
import com.beatraxus.app.model.LibraryView
import com.beatraxus.app.model.SortType
import com.beatraxus.app.model.RadioStation
import com.beatraxus.app.model.toSong
import com.beatraxus.app.repository.RadioBrowserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.beatraxus.app.ui.components.*
import com.beatraxus.app.ui.theme.*
import com.beatraxus.app.viewmodel.PlayerViewModel

@Composable
fun MainBackground(
    albumArtUri: android.net.Uri?,
    blurEffect: AndroidRenderEffect?
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = albumArtUri,
            transitionSpec = {
                fadeIn(tween(800)) togetherWith fadeOut(tween(800))
            },
            label = "mainBackgroundArt"
        ) { artUri ->
            if (artUri != null) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(artUri)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    renderEffect = blurEffect?.asComposeRenderEffect()
                                }
                                alpha = 0.45f
                            }
                            .then(
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    Modifier.blur(64.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(0.35f),
                                        Color.Black.copy(0.65f)
                                    )
                                )
                            )
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(BgDeep)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

enum class MainSheetType {
    SORT, CAST, CLOUD, DENSITY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onNavigateToInspector: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progressMs by viewModel.progressMs.collectAsStateWithLifecycle()

    val targetAccentColor = when (uiState.currentView) {
        LibraryView.HOME -> Color(0xFF00E676)
        LibraryView.ALL_SONGS -> Color(0xFFFF4081)
        LibraryView.ALBUMS, LibraryView.ALBUM_DETAIL -> Color(0xFFB2FF59)
        LibraryView.ARTISTS, LibraryView.ARTIST_DETAIL -> Color(0xFF7C4DFF)
        LibraryView.FOLDERS, LibraryView.FOLDER_DETAIL -> Color(0xFFFFAB40)
        LibraryView.YEARS, LibraryView.YEAR_DETAIL -> Color(0xFFFF5252)
        LibraryView.GENRES, LibraryView.GENRE_DETAIL -> Color(0xFFE040FB)
        LibraryView.PLAYLISTS, LibraryView.PLAYLIST_DETAIL -> Color(0xFFFDD835)
        LibraryView.FAVORITES -> Color(0xFFFF4081)
        LibraryView.RECENTLY_PLAYED -> Color(0xFF40C4FF)
        LibraryView.RECENTLY_ADDED -> Color(0xFF00E676)
        LibraryView.CLOUD -> Color(0xFF1A73E8)
        LibraryView.RADIO -> Color(0xFF00B8D4)
        LibraryView.SMB_NAS -> Color(0xFF546E7A)
        LibraryView.FTP_SFTP -> Color(0xFF8D6E63)
    }
    val viewAccentColor by animateColorAsState(
        targetValue = targetAccentColor,
        animationSpec = tween(500),
        label = "viewAccentColor"
    )

    val songs   by viewModel.songs.collectAsStateWithLifecycle()
    val albums  by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val years   by viewModel.years.collectAsStateWithLifecycle()
    val genres  by viewModel.genres.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(uiState.castErrorMessage) {
        uiState.castErrorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.setCastErrorMessage(null)
        }
    }

    val activeItemsCount by remember(uiState.currentView, uiState.isSearchActive, songs, albums, artists, folders, years, genres, playlists, searchResults) {
        derivedStateOf {
            if (uiState.isSearchActive) searchResults.size
            else when (uiState.currentView) {
                LibraryView.HOME -> 1
                LibraryView.ALBUMS -> albums.size
                LibraryView.ARTISTS -> artists.size
                LibraryView.FOLDERS -> folders.size
                LibraryView.YEARS -> years.size
                LibraryView.GENRES -> genres.size
                LibraryView.PLAYLISTS -> playlists.size
                else -> songs.size
            }
        }
    }

    val cachedBlurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.DECAL)
        } else null
    }
    val cachedBackgroundBlurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.DECAL)
        } else null
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onDeleteSuccess()
        }
    }

    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { intent ->
            launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(intent).build())
            viewModel.consumeDeleteRequest()
        }
    }
    val listState = rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    // Separate scroll states for different views to avoid jumps/flicker when switching content
    val homeListState = rememberLazyListState()
    val cloudListState = rememberLazyListState()
    val allSongsListState = rememberLazyListState()
    val albumsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val artistsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val foldersGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val yearsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val genresGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val playlistsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val favoritesListState = rememberLazyListState()
    val recentlyAddedListState = rememberLazyListState()
    val recentlyPlayedListState = rememberLazyListState()

    val albumDetailListState = rememberLazyListState()
    val albumDetailGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val artistDetailListState = rememberLazyListState()
    val artistDetailGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val folderDetailListState = rememberLazyListState()
    val folderDetailGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val yearDetailListState = rememberLazyListState()
    val yearDetailGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val genreDetailListState = rememberLazyListState()
    val genreDetailGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val playlistDetailListState = rememberLazyListState()
    val playlistDetailGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    val searchFieldRowVisible = uiState.isSearchActive

    val scope = rememberCoroutineScope()

    // Header hide on scroll logic
    var headerVisible by remember { mutableStateOf(true) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchFieldRowVisible) {
        if (searchFieldRowVisible) {
            kotlinx.coroutines.delay(60)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            headerVisible = true // Ensure top icons return when search is closed
        }
    }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < -12f && headerVisible) {
                    headerVisible = false
                } else if (delta > 32f && !headerVisible) {
                    headerVisible = true
                }
                return Offset.Zero
            }
        }
    }

    var showFullPlayer by rememberSaveable { mutableStateOf(false) }

    // Sink UI state from ViewModel
    LaunchedEffect(uiState.showFullPlayer) {
        if (uiState.showFullPlayer) {
            showFullPlayer = true
            viewModel.setShowFullPlayer(false)
        }
    }
    var activeMainSheet by remember { mutableStateOf<MainSheetType?>(null) }
    var selectedSongForOptions by remember { mutableStateOf<com.beatraxus.app.model.Song?>(null) }
    var reopenSongOptionsInfo by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.pendingInspectorReturnSong) {
        val pendingSong = uiState.pendingInspectorReturnSong
        if (pendingSong != null) {
            selectedSongForOptions = pendingSong
            reopenSongOptionsInfo = true
            viewModel.setPendingInspectorReturn(null)
        }
    }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistDialogSong by remember { mutableStateOf<com.beatraxus.app.model.Song?>(null) }
    var playlistToDelete by remember { mutableStateOf<com.beatraxus.app.model.Playlist?>(null) }
    var showPipelineOverlay by remember { mutableStateOf(false) }
    var showDrawer by rememberSaveable { mutableStateOf(false) }

    var categoryGridColumns by rememberSaveable { mutableIntStateOf(2) }
    var trackLayoutDensity by rememberSaveable { mutableIntStateOf(1) }

    val isCompactList = trackLayoutDensity == 2

    var titleWidth by remember { mutableFloatStateOf(0f) }
    var settingsLeft by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
    }
    val isTitleTouchingSettings by remember { derivedStateOf { (titleWidth + with(density) { 128.dp.toPx() }) > screenWidthPx } }

    LaunchedEffect(showFullPlayer) {
        if (showFullPlayer) {
            keyboardController?.hide()
            focusManager.clearFocus()
            viewModel.setShowFullPlayer(false)
        }
    }

    val isDetailView = uiState.currentView in listOf(
        LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL,
        LibraryView.FOLDER_DETAIL, LibraryView.YEAR_DETAIL, LibraryView.GENRE_DETAIL,
        LibraryView.PLAYLIST_DETAIL
    )

    BackHandler(enabled = showFullPlayer || uiState.isSearchActive || isDetailView || uiState.currentView != LibraryView.HOME || showDrawer || (activeMainSheet == MainSheetType.SORT) || showPipelineOverlay || uiState.isMultiSelectMode) {
        if (showDrawer) {
            showDrawer = false
        } else if (uiState.isMultiSelectMode) {
            viewModel.setMultiSelectMode(false)
        } else if (showPipelineOverlay) {
            showPipelineOverlay = false
        } else if (activeMainSheet == MainSheetType.SORT) {
            activeMainSheet = null
        } else if (showFullPlayer) {
            if (uiState.showQueue) {
                viewModel.toggleQueue()
            } else {
                showFullPlayer = false
            }
        } else if (uiState.isSearchActive) {
            viewModel.setSearchActive(false)
        } else if (isDetailView) {
            val backView = uiState.previousView ?: when(uiState.currentView) {
                LibraryView.ALBUM_DETAIL -> LibraryView.ALBUMS
                LibraryView.ARTIST_DETAIL -> LibraryView.ARTISTS
                LibraryView.FOLDER_DETAIL -> LibraryView.FOLDERS
                LibraryView.YEAR_DETAIL -> LibraryView.YEARS
                LibraryView.GENRE_DETAIL -> LibraryView.GENRES
                LibraryView.PLAYLIST_DETAIL -> LibraryView.PLAYLISTS
                else -> LibraryView.HOME
            }
            if (uiState.cameFromNowPlaying) {
                viewModel.setCameFromNowPlaying(false)
                showFullPlayer = true
                viewModel.setLibraryView(backView)
            } else {
                viewModel.setLibraryView(backView)
                if (uiState.wasSearchingBeforeDetail) {
                    viewModel.setSearchActive(true)
                }
            }
        } else if (uiState.currentView != LibraryView.HOME) {
            viewModel.setLibraryView(LibraryView.HOME)
        }
    }

    val blurByScan = 0f
    val saturationByScan = 1f
    val alphaByScan = 1f

    val drawerProgress by animateFloatAsState(
        targetValue = if (showDrawer) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "drawerProgress"
    )

    val contentScale = 1f - (0.03f * drawerProgress)
    val contentRotation = -2f * drawerProgress
    val contentCornerRadius = (32f * drawerProgress).dp

    Box(Modifier.fillMaxSize()) {
        // 1. Static Full-Screen Background - This will be revealed behind the drawer
        Box(Modifier.fillMaxSize().background(Color.Black))
        MainBackground(
            albumArtUri = uiState.currentSong?.albumArtUri,
            blurEffect = cachedBackgroundBlurEffect
        )

        // Slide drawer (Reveals the background behind it)
        if (drawerProgress > 0f) {
            Box(
                Modifier
                    .width(210.dp)
                    .fillMaxHeight(1.0f)
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .graphicsLayer {
                        translationX = -with(density) { 60.dp.toPx() } * (1f - drawerProgress)
                        alpha = (drawerProgress * 1.5f).coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp))
            ) {
                SlideDrawerMenu(
                    currentView = uiState.currentView,
                    libraryMode = uiState.libraryMode,
                    accentColor = viewAccentColor,
                    drawerProgress = drawerProgress,
                    isPlaying = uiState.isPlaying,
                    onSelectView = { view ->
                        viewModel.setLibraryView(view)
                    },
                    onSetLibraryMode = { mode ->
                        showDrawer = false // Auto close when library mode changes
                        // Delay the mode change slightly to allow the drawer animation to start
                        // and avoid simultaneous heavy recomposition of both drawer and main content
                        scope.launch {
                            delay(150)
                            viewModel.setLibraryMode(mode)
                        }
                    },
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToDsp = onNavigateToDsp,
                    onClose = { showDrawer = false }
                )
            }
        }

        val needsGraphicsLayer = blurByScan > 0.1f || saturationByScan < 0.99f || alphaByScan < 0.99f
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(2f) // Main content is on top
                .graphicsLayer {
                    translationX = drawerProgress * with(density) { 210.dp.toPx() }
                    scaleX = contentScale
                    scaleY = 1f - (0.08f * drawerProgress)
                    rotationY = contentRotation
                    cameraDistance = 12f * density.density
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    shape = RoundedCornerShape(contentCornerRadius.coerceAtLeast(0.dp))
                    clip = true
                }
        ) {
            // Main Content Area Background
            // When drawer is closed (progress 0), it's transparent to show the Album Art behind
            // When drawer is open (progress 1), it becomes solid BgDeep (interchange effect)
            // Use a stable alpha transition based purely on progress to avoid flicker during state toggles
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BgDeep.copy(alpha = (drawerProgress * 4f).coerceIn(0f, 1f)))
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (needsGraphicsLayer) {
                            Modifier.graphicsLayer {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val blurEffect = if (blurByScan > 19.9f && blurByScan < 20.1f) {
                                        cachedBlurEffect
                                    } else if (blurByScan > 0.1f) {
                                        AndroidRenderEffect.createBlurEffect(
                                            blurByScan,
                                            blurByScan,
                                            Shader.TileMode.DECAL
                                        )
                                    } else null

                                    val colorFilterEffect = if (saturationByScan < 0.99f) {
                                        val colorMatrix = ColorMatrix().apply {
                                            setSaturation(saturationByScan)
                                        }
                                        AndroidRenderEffect.createColorFilterEffect(
                                            ColorMatrixColorFilter(colorMatrix)
                                        )
                                    } else null

                                    this.renderEffect = when {
                                        blurEffect != null && colorFilterEffect != null ->
                                            AndroidRenderEffect.createChainEffect(blurEffect, colorFilterEffect).asComposeRenderEffect()
                                        blurEffect != null -> blurEffect.asComposeRenderEffect()
                                        colorFilterEffect != null -> colorFilterEffect.asComposeRenderEffect()
                                        else -> null
                                    }
                                }
                                alpha = alphaByScan
                            }
                        } else Modifier
                    )
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {}
                ) { paddingValues ->
                    // FIX 3: Wrap Column + mini player in Box so mini player can float at bottom
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .nestedScroll(nestedScrollConnection)
                                .padding(
                                    top = paddingValues.calculateTopPadding(),
                                    bottom = 0.dp
                                )
                        ) {
                            // New Integrated Header - Always Visible
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .zIndex(10f),
                                contentAlignment = Alignment.Center
                            ) {
                                val isDetailView = uiState.currentView in listOf(
                                    LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL,
                                    LibraryView.FOLDER_DETAIL, LibraryView.YEAR_DETAIL,
                                    LibraryView.GENRE_DETAIL, LibraryView.PLAYLIST_DETAIL
                                )

                                val titleText = when (uiState.currentView) {
                                    LibraryView.HOME -> "HOME"
                                    LibraryView.ALL_SONGS -> "ALL SONGS"
                                    LibraryView.ALBUMS -> "ALBUMS"
                                    LibraryView.ARTISTS -> "ARTISTS"
                                    LibraryView.FOLDERS -> "FOLDERS"
                                    LibraryView.YEARS -> "YEARS"
                                    LibraryView.GENRES -> "GENRES"
                                    LibraryView.PLAYLISTS -> "PLAYLISTS"
                                    LibraryView.FAVORITES -> "FAVORITES"
                                    LibraryView.RECENTLY_PLAYED -> "RECENTLY PLAYED"
                                    LibraryView.RECENTLY_ADDED -> "RECENTLY ADDED"
                                    LibraryView.ALBUM_DETAIL -> uiState.selectedItemName ?: "ALBUM"
                                    LibraryView.ARTIST_DETAIL -> uiState.selectedItemName ?: "ARTIST"
                                    LibraryView.PLAYLIST_DETAIL -> uiState.selectedItemName ?: "PLAYLIST"
                                    LibraryView.FOLDER_DETAIL -> uiState.selectedItemName ?: "FOLDER"
                                    LibraryView.YEAR_DETAIL -> uiState.selectedItemName ?: "YEAR"
                                    LibraryView.GENRE_DETAIL -> uiState.selectedItemName ?: "GENRE"
                                    LibraryView.CLOUD -> "CLOUD"
                                    LibraryView.RADIO -> "RADIO"
                                    LibraryView.SMB_NAS -> "SMB/NAS"
                                    LibraryView.FTP_SFTP -> "FTP/SFTP"
                                }
                                val titleIcon = when (uiState.currentView) {
                                    LibraryView.HOME -> Icons.Rounded.Home
                                    LibraryView.ALL_SONGS -> Icons.Rounded.MusicNote
                                    LibraryView.ALBUMS -> Icons.Rounded.Album
                                    LibraryView.ARTISTS -> Icons.Rounded.Person
                                    LibraryView.FOLDERS -> Icons.Rounded.Folder
                                    LibraryView.YEARS -> Icons.Rounded.CalendarMonth
                                    LibraryView.GENRES -> Icons.Rounded.GridView
                                    LibraryView.PLAYLISTS -> Icons.AutoMirrored.Rounded.PlaylistPlay
                                    LibraryView.FAVORITES -> Icons.Rounded.Favorite
                                    LibraryView.RECENTLY_PLAYED -> Icons.Rounded.History
                                    LibraryView.RECENTLY_ADDED -> Icons.Rounded.NewReleases
                                    LibraryView.ALBUM_DETAIL -> Icons.Rounded.Album
                                    LibraryView.ARTIST_DETAIL -> Icons.Rounded.Person
                                    LibraryView.FOLDER_DETAIL -> Icons.Rounded.Folder
                                    LibraryView.YEAR_DETAIL -> Icons.Rounded.CalendarMonth
                                    LibraryView.GENRE_DETAIL -> Icons.Rounded.GridView
                                    LibraryView.PLAYLIST_DETAIL -> Icons.AutoMirrored.Rounded.PlaylistPlay
                                    LibraryView.CLOUD -> Icons.Rounded.Cloud
                                    LibraryView.RADIO -> Icons.Rounded.Radio
                                    LibraryView.SMB_NAS -> Icons.Rounded.Storage
                                    LibraryView.FTP_SFTP -> Icons.Rounded.NetworkCheck
                                }

                                // Menu icon on the left
                                IconButton(
                                    onClick = {
                                        showDrawer = !showDrawer
                                    },
                                    modifier = Modifier.align(Alignment.CenterStart).size(42.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showDrawer) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Menu,
                                        contentDescription = null,
                                        tint = Color.White.copy(0.8f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                // Centered Title - Unique & Premium
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 64.dp, end = if (isTitleTouchingSettings) 12.dp else 64.dp)
                                        .animateContentSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val isHome = uiState.currentView == LibraryView.HOME

                                    Box(
                                        modifier = Modifier
                                            .animateContentSize()
                                            .onGloballyPositioned { titleWidth = it.size.width.toFloat() }
                                            .clip(RoundedCornerShape(if (isDetailView) 28.dp else 16.dp))
                                            .background(
                                                if (isDetailView) viewAccentColor.copy(alpha = 0.15f)
                                                else Color.Transparent
                                            )
                                            .then(
                                                if (isDetailView) Modifier.border(
                                                    width = 1.dp,
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(
                                                            viewAccentColor.copy(alpha = 0.5f),
                                                            viewAccentColor.copy(alpha = 0.1f)
                                                        )
                                                    ),
                                                    shape = RoundedCornerShape(28.dp)
                                                ) else Modifier
                                            )
                                            .clickable { showDrawer = true }
                                    ) {
                                        if (isHome) {
                                            // Unique Home Brand Look - BEATRAXUS
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "BEAT",
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 22.sp,
                                                    color = Color.White,
                                                    letterSpacing = 1.sp
                                                )

                                                // Unique Music Planet Icon (Custom drawn for premium look)
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .padding(horizontal = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                                        val centerPoint = Offset(size.width / 2, size.height / 2)
                                                        val planetRadius = size.minDimension * 0.38f

                                                        // 1. Planet Atmosphere Glow
                                                        drawCircle(
                                                            brush = Brush.radialGradient(
                                                                colors = listOf(viewAccentColor.copy(alpha = 0.4f), Color.Transparent),
                                                                center = centerPoint,
                                                                radius = planetRadius * 2.2f
                                                            ),
                                                            radius = planetRadius * 2.2f
                                                        )

                                                        // 2. The Planet Sphere with custom multi-stop gradient
                                                        drawCircle(
                                                            brush = Brush.linearGradient(
                                                                colors = listOf(
                                                                    Color(0xFF00E676), // Home Accent (Green)
                                                                    Color(0xFF00B0FF), // Cyber Blue
                                                                    Color(0xFFD500F9)  // Deep Purple
                                                                ),
                                                                start = Offset.Zero,
                                                                end = Offset(size.width, size.height)
                                                            ),
                                                            radius = planetRadius
                                                        )

                                                        // 3. Saturn-style Planet Ring (Unique touch)
                                                        withTransform({
                                                            rotate(-25f, centerPoint)
                                                        }) {
                                                            drawOval(
                                                                brush = Brush.linearGradient(
                                                                    colors = listOf(
                                                                        Color.White.copy(alpha = 0.1f),
                                                                        Color.White.copy(alpha = 0.8f),
                                                                        Color.White.copy(alpha = 0.1f)
                                                                    )
                                                                ),
                                                                topLeft = Offset(centerPoint.x - planetRadius * 1.7f, centerPoint.y - planetRadius * 0.3f),
                                                                size = Size(planetRadius * 3.4f, planetRadius * 0.6f),
                                                                style = Stroke(width = 1.5.dp.toPx())
                                                            )
                                                        }
                                                    }

                                                    // 4. Unique Music Symbol (Glassy/Neon effect)
                                                    Icon(
                                                        imageVector = Icons.Rounded.MusicNote,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(15.dp)
                                                            .graphicsLayer(alpha = 0.99f)
                                                            .drawWithCache {
                                                                onDrawWithContent {
                                                                    drawContent()
                                                                    drawRect(
                                                                        Brush.verticalGradient(
                                                                            colors = listOf(Color.White, Color.White.copy(alpha = 0.8f))
                                                                        ),
                                                                        blendMode = BlendMode.SrcAtop
                                                                    )
                                                                }
                                                            },
                                                        tint = Color.White
                                                    )
                                                }

                                                Text(
                                                    text = "RAXUS",
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 22.sp,
                                                    color = viewAccentColor,
                                                    letterSpacing = 1.sp,
                                                    style = androidx.compose.ui.text.TextStyle(
                                                        shadow = Shadow(
                                                            color = viewAccentColor.copy(alpha = 0.5f),
                                                            offset = Offset(0f, 0f),
                                                            blurRadius = 12f
                                                        )
                                                    )
                                                )
                                            }
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(26.dp)
                                                        .background(
                                                            if (isDetailView) viewAccentColor.copy(alpha = 0.15f)
                                                            else Color.Transparent,
                                                            CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        titleIcon,
                                                        null,
                                                        tint = viewAccentColor,
                                                        modifier = Modifier.size(if (isDetailView) 16.dp else 20.dp)
                                                    )
                                                }
                                                Spacer(Modifier.width(if (isDetailView) 10.dp else 8.dp))
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = if (isDetailView) titleText else titleText.uppercase(),
                                                        fontWeight = if (isDetailView) FontWeight.Bold else FontWeight.Black,
                                                        fontSize = if (isDetailView) 17.sp else 20.sp,
                                                        color = Color.White,
                                                        letterSpacing = if (isDetailView) 0.sp else 1.5.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = if (!isDetailView) androidx.compose.ui.text.TextStyle(
                                                            shadow = Shadow(
                                                                color = viewAccentColor.copy(alpha = 0.4f),
                                                                offset = Offset(0f, 0f),
                                                                blurRadius = 8f
                                                            )
                                                        ) else LocalTextStyle.current
                                                    )
                                                    if (uiState.isCloudScanning || uiState.scanProgress > 0f && uiState.scanProgress < 1f) {
                                                        LinearProgressIndicator(
                                                            progress = { uiState.scanProgress },
                                                            modifier = Modifier
                                                                .width(60.dp)
                                                                .height(2.dp)
                                                                .clip(CircleShape),
                                                            color = viewAccentColor,
                                                            trackColor = viewAccentColor.copy(alpha = 0.2f),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Icons on the right
                                Row(
                                    modifier = Modifier.align(Alignment.CenterEnd).wrapContentSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val searchBgColor by animateColorAsState(
                                        targetValue = if (uiState.isSearchActive) AccentBlue.copy(0.15f) else Color.White.copy(0.08f),
                                        label = "searchBg"
                                    )
                                    IconButton(
                                        onClick = {
                                            val nextActive = !uiState.isSearchActive
                                            viewModel.setSearchActive(nextActive)
                                        },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(searchBgColor, CircleShape)
                                            .border(1.dp, if (uiState.isSearchActive) AccentBlue.copy(0.4f) else Color.White.copy(0.1f), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Search,
                                            null,
                                            tint = if (uiState.isSearchActive) AccentBlue else Color.White.copy(0.9f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }


                            Spacer(Modifier.height(4.dp))

                            // Search field
                            AnimatedVisibility(
                                visible = searchFieldRowVisible,
                                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                                exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(23.dp))
                                        .background(Color.White.copy(0.1f))
                                        .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(23.dp)),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    ) {
                                        Icon(Icons.Rounded.Search, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(12.dp))
                                        BasicTextField(
                                            value = uiState.searchQuery,
                                            onValueChange = { viewModel.setSearchQuery(it) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .focusRequester(searchFocusRequester),
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            cursorBrush = SolidColor(AccentBlue),
                                            singleLine = true,
                                            decorationBox = { innerTextField ->
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    if (uiState.searchQuery.isEmpty()) {
                                                        Text(
                                                            "Search songs, artists, albums...",
                                                            color = Color.White.copy(0.4f),
                                                            fontSize = 15.sp
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )
                                        // Removed clear button
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            Spacer(Modifier.height(4.dp))

                            // Cloud Sync Status - New location requested
                            androidx.compose.animation.AnimatedVisibility(
                                visible = uiState.currentView == LibraryView.CLOUD && uiState.isCloudScanning,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF1A73E8).copy(0.12f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color(0xFF1A73E8).copy(0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { uiState.scanProgress },
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF1A73E8),
                                            trackColor = Color(0xFF1A73E8).copy(alpha = 0.2f)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = if (uiState.scanProgress > 0f) "Enriching Metadata... ${(uiState.scanProgress * 100).toInt()}%" else "Syncing Cloud Library...",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // Action Icons Row
                            AnimatedVisibility(
                                visible = (headerVisible || activeItemsCount <= 8) && uiState.currentView != LibraryView.HOME,
                                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    if (uiState.isMultiSelectMode) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            IconButton(onClick = { viewModel.selectAll() }) {
                                                Icon(Icons.Rounded.SelectAll, null, tint = Color.White)
                                            }
                                            if (uiState.currentView != LibraryView.CLOUD) {
                                                IconButton(onClick = { viewModel.deleteSelected() }) {
                                                    Icon(Icons.Rounded.Delete, null, tint = Color.White)
                                                }
                                            }
                                            IconButton(onClick = { viewModel.playNextSelected() }) {
                                                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Color.White)
                                            }
                                            IconButton(onClick = {
                                                playlistDialogSong = null
                                                showPlaylistDialog = true
                                            }) {
                                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Color.White)
                                            }
                                            IconButton(onClick = { viewModel.shareSelected() }) {
                                                Icon(Icons.Rounded.Share, null, tint = Color.White)
                                            }
                                        }
                                    } else {
                                        val canShufflePlay = when (uiState.currentView) {
                                            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                            LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS -> false
                                            else -> true
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Shuffle All Button
                                            if (canShufflePlay && uiState.currentView != LibraryView.HOME) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(3.5f),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Surface(
                                                        color = Color.White.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(28.dp),
                                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                                        modifier = Modifier
                                                            .wrapContentSize()
                                                            .clickable { viewModel.shuffleAndPlay() }
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier.size(28.dp).glassIconBackground(shape = CircleShape),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                                            }
                                                            Spacer(Modifier.width(10.dp))
                                                            Text(
                                                                text = "Shuffle All",
                                                                color = Color.White,
                                                                fontSize = 15.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // Sort / Filter Icon
                                            if (uiState.currentView != LibraryView.HOME) {
                                                val isSelected = activeMainSheet == MainSheetType.SORT
                                                val isCloud = uiState.currentView == LibraryView.CLOUD
                                                Box(
                                                    modifier = Modifier.weight(1f),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    val sortIconBgColor by animateColorAsState(
                                                        targetValue = if (isSelected) Color.White.copy(0.2f) else Color.Transparent,
                                                        animationSpec = tween(400),
                                                        label = "sortIconBgColor"
                                                    )
                                                    IconButton(
                                                        onClick = { activeMainSheet = MainSheetType.SORT },
                                                        modifier = Modifier
                                                            .size(46.dp)
                                                            .glassIconBackground(
                                                                backgroundColor = sortIconBgColor,
                                                                shape = CircleShape,
                                                                borderColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                            )
                                                    ) {
                                                        Icon(
                                                            if (isCloud) Icons.Rounded.FilterList else Icons.AutoMirrored.Rounded.Sort,
                                                            null,
                                                            tint = if (isSelected) AccentBlue else Color.White.copy(0.85f),
                                                            modifier = Modifier.size(23.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Cloud Icon
                                            if (uiState.currentView != LibraryView.HOME) {
                                                val isSelected = activeMainSheet == MainSheetType.CLOUD
                                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                                    val cloudIconBgColor by animateColorAsState(
                                                        targetValue = if (isSelected) Color.White.copy(0.12f) else Color.Transparent,
                                                        label = "cloudIconBg"
                                                    )
                                                    IconButton(
                                                        onClick = { activeMainSheet = MainSheetType.CLOUD },
                                                        modifier = Modifier
                                                            .size(46.dp)
                                                            .glassIconBackground(
                                                                backgroundColor = cloudIconBgColor,
                                                                shape = CircleShape,
                                                                borderColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                            )
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.Cloud,
                                                            null,
                                                            tint = if (isSelected) AccentBlue else Color.White.copy(0.85f),
                                                            modifier = Modifier.size(23.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Layout Density Icon
                                            if (uiState.currentView != LibraryView.HOME) {
                                                val isSelected = activeMainSheet == MainSheetType.DENSITY
                                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                                    val sliderIconBgColor by animateColorAsState(
                                                        targetValue = if (isSelected) Color.White.copy(0.12f) else Color.Transparent,
                                                        label = "sliderIconBg"
                                                    )
                                                    IconButton(
                                                        onClick = { activeMainSheet = MainSheetType.DENSITY },
                                                        modifier = Modifier
                                                            .size(46.dp)
                                                            .glassIconBackground(
                                                                backgroundColor = sliderIconBgColor,
                                                                shape = CircleShape,
                                                                borderColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                            )
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.GridView,
                                                            null,
                                                            tint = if (isSelected) AccentBlue else Color.White.copy(0.85f),
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Content Area
                            Box(
                                Modifier
                                    .weight(1f)
                                    .pinchToZoom(
                                        onZoomIn  = {
                                            val isGrid = uiState.currentView in listOf(
                                                LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                                LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS
                                            )
                                            if (isGrid) {
                                                categoryGridColumns = (categoryGridColumns - 1).coerceAtLeast(1)
                                            } else {
                                                trackLayoutDensity = (trackLayoutDensity - 1).coerceAtLeast(1)
                                            }
                                        },
                                        onZoomOut = {
                                            val isGrid = uiState.currentView in listOf(
                                                LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                                LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS
                                            )
                                            if (isGrid) {
                                                categoryGridColumns = (categoryGridColumns + 1).coerceAtMost(5)
                                            } else {
                                                trackLayoutDensity = (trackLayoutDensity + 1).coerceAtMost(6)
                                            }
                                        }
                                    )
                            ) {
                                // Normal Content
                                Box(modifier = Modifier.fillMaxSize()) {
                                    androidx.compose.animation.AnimatedContent(
                                        targetState = uiState.isSearchActive && uiState.searchQuery.isNotEmpty(),
                                        transitionSpec = {
                                            (fadeIn(animationSpec = tween(300)) +
                                                    scaleIn(initialScale = 0.98f, animationSpec = tween(300))
                                                    ).togetherWith(
                                                    fadeOut(animationSpec = tween(250))
                                                )
                                        },
                                        label = "searchContentTransition"
                                    ) { isSearching ->
                                        if (isSearching) {
                                            // Search Results logic
                                            Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
                                                LazyColumn(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentPadding = PaddingValues(bottom = 120.dp)
                                                ) {
                                                    var songIndex = 1
                                                    searchResults.forEachIndexed { index, item ->
                                                        when (item) {
                                                            is String -> {
                                                                item(key = "header_${item}_$index") {
                                                                    songIndex = 1
                                                                    Surface(
                                                                        color = Color.White.copy(alpha = 0.15f),
                                                                        shape = RoundedCornerShape(12.dp),
                                                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                                                        modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = item,
                                                                            color = Color.White,
                                                                            fontSize = 12.sp,
                                                                            fontWeight = FontWeight.Black,
                                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            is com.beatraxus.app.model.Song -> {
                                                                val currentNumber = songIndex++
                                                                item(key = "song_${item.id}") {
                                                                    Box(modifier = Modifier.padding(horizontal = 8.dp).animateItem()) {
                                                                        SongListItem(
                                                                            song = item,
                                                                            trackNumber = currentNumber,
                                                                            isPlaying = uiState.currentSong?.id == item.id,
                                                                            onClick = {
                                                                                if (uiState.isMultiSelectMode) {
                                                                                    viewModel.toggleSongSelection(item.id)
                                                                                } else {
                                                                                    viewModel.playSong(item)
                                                                                }
                                                                            },
                                                                            isMultiSelectMode = uiState.isMultiSelectMode,
                                                                            isSelected = uiState.selectedIds.contains(item.id),
                                                                            onMoreClick = { selectedSongForOptions = item },
                                                                            onLongClick = {
                                                                                if (!uiState.isMultiSelectMode) {
                                                                                    viewModel.setMultiSelectMode(true)
                                                                                    viewModel.toggleSongSelection(item.id)
                                                                                }
                                                                            },
                                                                            isCompact = isCompactList
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            is Triple<*, *, *> -> {
                                                                val title = item.first as String
                                                                val artist = item.second as String
                                                                val art = item.third as android.net.Uri?
                                                                item(key = "album_$title") {
                                                                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem()) {
                                                                        LibraryGridItem(title, artist, art, onClick = {
                                                                            viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, title)
                                                                        })
                                                                    }
                                                                }
                                                            }
                                                            is Pair<*, *> -> {
                                                                val name = item.first as String
                                                                val art = item.second as android.net.Uri?
                                                                item(key = "artist_$name") {
                                                                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem()) {
                                                                        LibraryGridItem(name, "Artist", art, onClick = {
                                                                            viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, name)
                                                                        })
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // Normal Content
                                        androidx.compose.animation.AnimatedContent(
                                            targetState = uiState.currentView,
                                            transitionSpec = {
                                                (fadeIn(animationSpec = tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                    .togetherWith(fadeOut(animationSpec = tween(120)))
                                            },
                                            label = "viewTransition"
                                        ) { targetView ->
                                            when (targetView) {
                                                LibraryView.HOME -> {
                                                    HomeScreen(viewModel, uiState, homeListState)
                                                }
                                                LibraryView.CLOUD -> {
                                                    val accounts = uiState.driveAccounts
                                                    val tgChannels = uiState.telegramChannels
                                                    if (accounts.isEmpty() && tgChannels.isEmpty()) {
                                                        Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                                                            Icon(Icons.Rounded.Cloud, null, tint=Color(0xFF1A73E8), modifier=Modifier.size(64.dp))
                                                            Spacer(Modifier.height(16.dp))
                                                            Text("No Cloud accounts or Telegram channels connected", color=Color.White.copy(0.6f), textAlign=TextAlign.Center)
                                                            Text("Add an account in Settings", color=Color.White.copy(0.3f), fontSize=13.sp, textAlign=TextAlign.Center)
                                                        }
                                                    } else if (songs.isEmpty()) {
                                                        if (uiState.isCloudScanning) {
                                                            Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                                                                CircularProgressIndicator(color=Color(0xFF1A73E8))
                                                                Spacer(Modifier.height(16.dp))
                                                                Text("Scanning Cloud Library...", color=Color.White.copy(0.6f), textAlign=TextAlign.Center)
                                                                Text("This may take a moment depending on your library size", color=Color.White.copy(0.3f), fontSize=12.sp, textAlign=TextAlign.Center)
                                                            }
                                                        } else {
                                                            Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                                                                Icon(Icons.Rounded.SearchOff, null, tint=Color.White.copy(0.3f), modifier=Modifier.size(64.dp))
                                                                Spacer(Modifier.height(16.dp))
                                                                Text("No music found in your Cloud Account", color=Color.White.copy(0.6f), textAlign=TextAlign.Center)
                                                                Text("Try clicking 'Sync' in Settings if you have new files", color=Color.White.copy(0.3f), fontSize=12.sp, textAlign=TextAlign.Center)
                                                            }
                                                        }
                                                    } else {
                                                        val albumCount = remember(songs) { songs.map { it.album }.distinct().size }
                                                        val artistCount = remember(songs) { songs.map { it.artist }.distinct().size }
                                                        var showSyncConfirm by remember { mutableStateOf(false) }

                                                        if (showSyncConfirm) {
                                                            AlertDialog(
                                                                onDismissRequest = { showSyncConfirm = false },
                                                                title = { Text("Sync Cloud Account?") },
                                                                text = { Text("Do you want to scan your Cloud Account for new music? This may take some time.") },
                                                                confirmButton = {
                                                                    TextButton(onClick = {
                                                                        showSyncConfirm = false
                                                                        viewModel.refreshCloudLibrary()
                                                                    }) {
                                                                        Text("Sync", color = Color(0xFF1A73E8))
                                                                    }
                                                                },
                                                                dismissButton = {
                                                                    TextButton(onClick = { showSyncConfirm = false }) {
                                                                        Text("Cancel", color = Color.White.copy(0.6f))
                                                                    }
                                                                },
                                                                containerColor = Color(0xFF1C1C1E),
                                                                titleContentColor = Color.White,
                                                                textContentColor = Color.White.copy(0.7f)
                                                            )
                                                        }

                                                        Box(Modifier.fillMaxSize()) {
                                                            LazyColumn(state=cloudListState, modifier = Modifier.fillMaxSize(), contentPadding=PaddingValues(bottom=120.dp)) {
                                                                item {
                                                                    Column {
                                                                        androidx.compose.animation.AnimatedVisibility(
                                                                    visible = false, // Moved to main UI stack above
                                                                    enter = expandVertically() + fadeIn(),
                                                                    exit = shrinkVertically() + fadeOut()
                                                                ) {
                                                                    // Placeholder to keep indexing stable during transition
                                                                }
                                                                    }
                                                                }

                                                                item {
                                                                    Column(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .padding(top = 20.dp, bottom = 10.dp),
                                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                                    ) {
                                                                        Row(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(horizontal = 16.dp),
                                                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                                                            verticalAlignment = Alignment.CenterVertically
                                                                        ) {
                                                                            StatItem(Icons.Rounded.MusicNote, songs.size.toString(), "Songs", Color(0xFFFF4081))
                                                                            StatItem(Icons.Rounded.Album, albumCount.toString(), "Albums", Color(0xFFB2FF59))
                                                                            StatItem(Icons.Rounded.Person, artistCount.toString(), "Artists", Color(0xFF7C4DFF))
                                                                        }
                                                                    }
                                                                }
                                                                itemsIndexed(songs, key={_,s->s.id}) { index, song ->
                                                                    Box(Modifier.animateItem()) {
                                                                        SongListItem(
                                                                            song = song, isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                            trackNumber = index + 1, isCompact = isCompactList,
                                                                            onClick = {
                                                                                if (uiState.isMultiSelectMode) {
                                                                                    viewModel.toggleSongSelection(song.id)
                                                                                } else {
                                                                                    viewModel.playSong(song)
                                                                                }
                                                                            },
                                                                            isMultiSelectMode = uiState.isMultiSelectMode,
                                                                            isSelected = uiState.selectedIds.contains(song.id),
                                                                            onMoreClick = { selectedSongForOptions = song },
                                                                            onLongClick = {
                                                                                if (!uiState.isMultiSelectMode) {
                                                                                    viewModel.setMultiSelectMode(true)
                                                                                    viewModel.toggleSongSelection(song.id)
                                                                                }
                                                                            }
                                                                        )
                                                                    }
                                                                }
                                                            }

                                                            if (songs.size > 20) {
                                                                val songTitles = remember(songs) {
                                                                    songs.map { it.title }
                                                                }
                                                                AlphabetScroller(
                                                                    modifier = Modifier
                                                                        .align(Alignment.CenterEnd)
                                                                        .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                    items = songTitles,
                                                                    onScrollTo = { targetIndex: Int ->
                                                                        scope.launch {
                                                                            cloudListState.scrollToItem(targetIndex)
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.ALBUMS -> {
                                                    val isGridView = trackLayoutDensity <= 2 // In this view, zoom affects item size but not layout type, OR we can implement something similar
                                                    Box(Modifier.fillMaxSize()) {
                                                        LazyVerticalGrid(
                                                            state = albumsGridState,
                                                            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                        ) {
                                                            items(albums, key = { it.first + it.second }) { album ->
                                                                Box(Modifier.animateItem()) {
                                                                    LibraryGridItem(
                                                                        title = album.first,
                                                                        subtitle = album.second,
                                                                        artUri = album.third,
                                                                        isSelected = uiState.selectedIds.contains(album.first),
                                                                        isMultiSelectMode = uiState.isMultiSelectMode,
                                                                        onClick = {
                                                                            if (uiState.isMultiSelectMode) {
                                                                                viewModel.toggleItemSelection(album.first)
                                                                            } else {
                                                                                viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, album.first)
                                                                            }
                                                                        },
                                                                        onLongClick = {
                                                                            if (!uiState.isMultiSelectMode) {
                                                                                viewModel.setMultiSelectMode(true)
                                                                                viewModel.toggleItemSelection(album.first)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.ALBUM_DETAIL -> {
                                                    val albumSongs = songs
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "albumDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = albumDetailListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(albumSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (albumSongs.size > 20) {
                                                                    val songTitles = remember(albumSongs) {
                                                                        albumSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                albumDetailListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = albumDetailGridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(albumSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.ARTISTS -> {
                                                    Box(Modifier.fillMaxSize()) {
                                                        LazyVerticalGrid(
                                                            state = artistsGridState,
                                                            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                        ) {
                                                            items(artists, key = { it.first }) { artist ->
                                                                Box(Modifier.animateItem()) {
                                                                    LibraryGridItem(
                                                                        title = artist.first,
                                                                        subtitle = artist.second,
                                                                        artUri = artist.third,
                                                                        isArtistTile = true,
                                                                        isSelected = uiState.selectedIds.contains(artist.first),
                                                                        isMultiSelectMode = uiState.isMultiSelectMode,
                                                                        onClick = {
                                                                            if (uiState.isMultiSelectMode) {
                                                                                viewModel.toggleItemSelection(artist.first)
                                                                            } else {
                                                                                viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, artist.first)
                                                                            }
                                                                        },
                                                                        onLongClick = {
                                                                            if (!uiState.isMultiSelectMode) {
                                                                                viewModel.setMultiSelectMode(true)
                                                                                viewModel.toggleItemSelection(artist.first)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.ARTIST_DETAIL -> {
                                                    val artistSongs = songs
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "artistDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = artistDetailListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(artistSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (artistSongs.size > 20) {
                                                                    val songTitles = remember(artistSongs) {
                                                                        artistSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                artistDetailListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = artistDetailGridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(artistSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.FOLDERS -> {
                                                    LazyVerticalGrid(
                                                        state = foldersGridState,
                                                        columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                    ) {
                                                        items(folders, key = { it.first }) { folder ->
                                                            Box(Modifier.animateItem()) {
                                                                LibraryGridItem(
                                                                    title = folder.second,
                                                                    subtitle = folder.first,
                                                                    artUri = folder.third,
                                                                    isSelected = uiState.selectedIds.contains(folder.first),
                                                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                                                    onClick = {
                                                                        if (uiState.isMultiSelectMode) {
                                                                            viewModel.toggleItemSelection(folder.first)
                                                                        } else {
                                                                            viewModel.navigateToFolder(folder.first, folder.second)
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        if (!uiState.isMultiSelectMode) {
                                                                            viewModel.setMultiSelectMode(true)
                                                                            viewModel.toggleItemSelection(folder.first)
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                // FIX 5: FOLDER_DETAIL branch properly closed
                                                LibraryView.FOLDER_DETAIL -> {
                                                    val folderSongs = songs
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "folderDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = folderDetailListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(folderSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (folderSongs.size > 20) {
                                                                    val songTitles = remember(folderSongs) {
                                                                        folderSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                folderDetailListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = folderDetailGridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(folderSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.YEARS -> {
                                                    LazyVerticalGrid(
                                                        state = yearsGridState,
                                                        columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                    ) {
                                                        items(years, key = { it.first }) { year ->
                                                            Box(Modifier.animateItem()) {
                                                                LibraryGridItem(
                                                                    title = year.first,
                                                                    subtitle = year.second,
                                                                    artUri = year.third,
                                                                    isSelected = uiState.selectedIds.contains(year.first),
                                                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                                                    onClick = {
                                                                        if (uiState.isMultiSelectMode) {
                                                                            viewModel.toggleItemSelection(year.first)
                                                                        } else {
                                                                            viewModel.setLibraryView(LibraryView.YEAR_DETAIL, year.first)
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        if (!uiState.isMultiSelectMode) {
                                                                            viewModel.setMultiSelectMode(true)
                                                                            viewModel.toggleItemSelection(year.first)
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.YEAR_DETAIL -> {
                                                    val yearSongs = songs
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "yearDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = yearDetailListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(yearSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (yearSongs.size > 20) {
                                                                    val songTitles = remember(yearSongs) {
                                                                        yearSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                yearDetailListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = yearDetailGridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(yearSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.GENRES -> {
                                                    LazyVerticalGrid(
                                                        state = genresGridState,
                                                        columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                    ) {
                                                        items(genres, key = { it.first }) { genre ->
                                                            Box(Modifier.animateItem()) {
                                                                GenreGridItem(
                                                                    title = genre.first,
                                                                    subtitle = genre.second,
                                                                    isSelected = uiState.selectedIds.contains(genre.first),
                                                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                                                    onClick = {
                                                                        if (uiState.isMultiSelectMode) {
                                                                            viewModel.toggleItemSelection(genre.first)
                                                                        } else {
                                                                            viewModel.setLibraryView(LibraryView.GENRE_DETAIL, genre.first)
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        if (!uiState.isMultiSelectMode) {
                                                                            viewModel.setMultiSelectMode(true)
                                                                            viewModel.toggleItemSelection(genre.first)
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.GENRE_DETAIL -> {
                                                    val genreSongs = songs
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "genreDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = genreDetailListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(genreSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (genreSongs.size > 20) {
                                                                    val songTitles = remember(genreSongs) {
                                                                        genreSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                genreDetailListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = genreDetailGridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(genreSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.PLAYLISTS -> {
                                                    if (playlists.isEmpty()) {
                                                        // ...
                                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, modifier = Modifier.size(64.dp), tint = TextMuted)
                                                                Spacer(Modifier.height(16.dp))
                                                                Text("No playlists yet", color = TextMuted, fontSize = 18.sp)
                                                                Spacer(Modifier.height(4.dp))
                                                                Button(
                                                                    onClick = {
                                                                        // Open playlist dialog to prompt for name
                                                                        playlistDialogSong = null
                                                                        showPlaylistDialog = true
                                                                    },
                                                                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                                                                ) {
                                                                    Text("Create Playlist", color = Color.Black)
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        LazyVerticalGrid(
                                                            state = playlistsGridState,
                                                            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                        ) {
                                                            items(playlists, key = { it.id }) { playlist ->
                                                                Box(Modifier.animateItem()) {
                                                                    LibraryGridItem(
                                                                        title = playlist.name,
                                                                        subtitle = "${playlist.songIds.size} songs",
                                                                        artUri = null,
                                                                        isSelected = uiState.selectedIds.contains(playlist.id),
                                                                        isMultiSelectMode = uiState.isMultiSelectMode,
                                                                        onClick = {
                                                                            if (uiState.isMultiSelectMode) {
                                                                                viewModel.toggleItemSelection(playlist.id)
                                                                            } else {
                                                                                viewModel.setLibraryView(LibraryView.PLAYLIST_DETAIL, playlist.name)
                                                                            }
                                                                        },
                                                                        onLongClick = {
                                                                            if (!uiState.isMultiSelectMode) {
                                                                                viewModel.setMultiSelectMode(true)
                                                                                viewModel.toggleItemSelection(playlist.id)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.PLAYLIST_DETAIL -> {
                                                    val playlistName = uiState.selectedItemName
                                                    val playlist = playlists.find { it.name == playlistName }
                                                    val playlistSongs = songs.filter { playlist?.songIds?.contains(it.id) == true }
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "playlistDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = playlistDetailListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(playlistSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (playlistSongs.size > 20) {
                                                                    val songTitles = remember(playlistSongs) {
                                                                        playlistSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                playlistDetailListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = playlistDetailGridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(playlistSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.FAVORITES -> {
                                                    val favSongs = songs.filter { favorites.contains(it.id) }
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "favoritesTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = favoritesListState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 120.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(favSongs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                if (favSongs.size > 20) {
                                                                    val songTitles = remember(favSongs) {
                                                                        favSongs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                favoritesListState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = gridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(favSongs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.SMB_NAS -> {
                                                    SmbBrowserScreen(uiState, viewModel)
                                                }
                                                LibraryView.FTP_SFTP -> {
                                                    FtpBrowserScreen(uiState, viewModel)
                                                }
                                                LibraryView.RADIO -> {
                                                    var radioStations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
                                                    var isLoading by remember { mutableStateOf(false) }
                                                    var searchCountry by rememberSaveable { mutableStateOf("") }

                                                    // Default view: popular Tamil stations, loaded immediately —
                                                    // no need to type anything. Typing a country overrides this
                                                    // with a country-specific search.
                                                    LaunchedEffect(searchCountry) {
                                                        isLoading = true
                                                        radioStations = withContext(Dispatchers.IO) {
                                                            if (searchCountry.isBlank()) {
                                                                RadioBrowserApi.tamilStations(limit = 150)
                                                            } else {
                                                                RadioBrowserApi.stationsByCountry(searchCountry)
                                                            }
                                                        }
                                                        isLoading = false
                                                    }

                                                    Column(Modifier.fillMaxSize()) {
                                                        Surface(
                                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                            color = Color.White.copy(0.05f),
                                                            shape = RoundedCornerShape(12.dp)
                                                        ) {
                                                            Row(
                                                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(20.dp))
                                                                Spacer(Modifier.width(8.dp))
                                                                BasicTextField(
                                                                    value = searchCountry,
                                                                    onValueChange = { searchCountry = it },
                                                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                                                                    modifier = Modifier.weight(1f),
                                                                    cursorBrush = SolidColor(Color.White),
                                                                    decorationBox = { innerTextField ->
                                                                        if (searchCountry.isEmpty()) {
                                                                            Text("Showing Tamil stations — type a country to search others...", color = Color.White.copy(0.3f), fontSize = 14.sp)
                                                                        }
                                                                        innerTextField()
                                                                    }
                                                                )
                                                            }
                                                        }

                                                        if (isLoading) {
                                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                CircularProgressIndicator(color = viewAccentColor)
                                                            }
                                                        } else {
                                                            LazyColumn(
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentPadding = PaddingValues(bottom = 120.dp)
                                                            ) {
                                                                itemsIndexed(radioStations, key = { _, station -> station.id }) { index, station ->
                                                                    val song = station.toSong()
                                                                    SongListItem(
                                                                        song = song,
                                                                        isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                        trackNumber = index + 1,
                                                                        onClick = { viewModel.playSong(song) }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                else -> {
                                                    // Library list or grid based on zoom
                                                    val isListView = trackLayoutDensity <= 2
                                                    AnimatedContent(
                                                        targetState = isListView,
                                                        transitionSpec = {
                                                            (fadeIn(tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                                .togetherWith(fadeOut(tween(120)))
                                                        },
                                                        label = "mainLibraryTransition"
                                                    ) { targetIsListView ->
                                                        val currentState = when(targetView) {
                                                            LibraryView.RECENTLY_PLAYED -> recentlyPlayedListState
                                                            LibraryView.RECENTLY_ADDED -> recentlyAddedListState
                                                            else -> allSongsListState
                                                        }
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = currentState,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentPadding = PaddingValues(
                                                                        top = 8.dp,
                                                                        bottom = paddingValues.calculateBottomPadding() + 100.dp,
                                                                        end = 32.dp
                                                                    )
                                                                ) {
                                                                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                                                                        Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                                song = song,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                trackNumber = index + 1,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                onMoreClick = {
                                                                                    selectedSongForOptions = song
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                // Alphabet Fast Scroller
                                                                val viewsWithAlphabet = setOf(
                                                                    LibraryView.ALL_SONGS,
                                                                    LibraryView.ALBUM_DETAIL,
                                                                    LibraryView.ARTIST_DETAIL,
                                                                    LibraryView.FOLDER_DETAIL,
                                                                    LibraryView.PLAYLIST_DETAIL,
                                                                    LibraryView.RECENTLY_ADDED,
                                                                    LibraryView.FAVORITES,
                                                                    LibraryView.CLOUD
                                                                )
                                                                if (uiState.currentView in viewsWithAlphabet && songs.size > 20) {
                                                                    val songTitles = remember(songs) {
                                                                        songs.map { it.title }
                                                                    }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                currentState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
                                                                    state = gridState,
                                                                    columns = GridCells.Fixed(trackLayoutDensity.coerceIn(1, 6)),
                                                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(if (trackLayoutDensity >= 3) 8.dp else 16.dp)
                                                                ) {
                                                                    items(songs, key = { it.id }) { song ->
                                                                        Box(Modifier.animateItem()) {
                                                                            SongGridItem(
                                                                                song = song,
                                                                                isCurrent = uiState.currentSong?.id == song.id,
                                                                                isPlaying = uiState.isPlaying && uiState.currentSong?.id == song.id,
                                                                                isSelected = uiState.selectedIds.contains(song.id),
                                                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                                                onClick = {
                                                                                    if (uiState.isMultiSelectMode) {
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    } else {
                                                                                        viewModel.playSong(song)
                                                                                    }
                                                                                },
                                                                                onLongClick = {
                                                                                    if (!uiState.isMultiSelectMode) {
                                                                                        viewModel.setMultiSelectMode(true)
                                                                                        viewModel.toggleSongSelection(song.id)
                                                                                    }
                                                                                },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } // end Column

                                // FIX 3: Mini player is now a sibling of Column inside Box — has BoxScope for .align()
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(bottom = paddingValues.calculateBottomPadding() + 12.dp)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = uiState.currentSong != null && !showFullPlayer,
                                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(64.dp)
                                                .shadow(16.dp, RoundedCornerShape(20.dp))
                                                .clip(RoundedCornerShape(20.dp))
                                                .combinedClickable(
                                                    onClick = { showFullPlayer = true },
                                                    onLongClick = {
                                                        uiState.currentSong?.let { current ->
                                                            val index = songs.indexOfFirst { it.id == current.id }
                                                            if (index >= 0) {
                                                                val currentState = when(uiState.currentView) {
                                                                    LibraryView.HOME -> homeListState
                                                                    LibraryView.CLOUD -> cloudListState
                                                                    LibraryView.ALBUMS -> null // No scroll for grid
                                                                    LibraryView.ARTISTS -> null
                                                                    LibraryView.ALBUM_DETAIL -> albumDetailListState
                                                                    LibraryView.ARTIST_DETAIL -> artistDetailListState
                                                                    LibraryView.FAVORITES -> favoritesListState
                                                                    LibraryView.RECENTLY_PLAYED -> recentlyPlayedListState
                                                                    LibraryView.RECENTLY_ADDED -> recentlyAddedListState
                                                                    else -> allSongsListState
                                                                }
                                                                val currentGridState = when(uiState.currentView) {
                                                                    LibraryView.ALBUMS -> albumsGridState
                                                                    LibraryView.ARTISTS -> artistsGridState
                                                                    LibraryView.FOLDERS -> foldersGridState
                                                                    LibraryView.YEARS -> yearsGridState
                                                                    LibraryView.GENRES -> genresGridState
                                                                    LibraryView.PLAYLISTS -> playlistsGridState
                                                                    LibraryView.ALBUM_DETAIL -> albumDetailGridState
                                                                    LibraryView.ARTIST_DETAIL -> artistDetailGridState
                                                                    else -> null
                                                                }
                                                                scope.launch {
                                                                    if (trackLayoutDensity <= 2) {
                                                                        currentState?.scrollToItem(
                                                                            index = index,
                                                                            scrollOffset = -200
                                                                        )
                                                                    } else {
                                                                        currentGridState?.scrollToItem(
                                                                            index = index,
                                                                            scrollOffset = -200
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                                .pointerInput(Unit) {
                                                    var totalX = 0f
                                                    detectHorizontalDragGestures(
                                                        onDragStart = { totalX = 0f },
                                                        onDragEnd = {
                                                            if (totalX > 50) viewModel.skipToPrevious()
                                                            else if (totalX < -50) viewModel.skipToNext()
                                                        },
                                                        onHorizontalDrag = { change, dragAmount ->
                                                            change.consume()
                                                            totalX += dragAmount
                                                        }
                                                    )
                                                }
                                        ) {
                                            // Glass Background Layer
                                            Box(Modifier.fillMaxSize()) {
                                                Box(Modifier.fillMaxSize().background(Color(0xFF121212)))
                                                AnimatedContent(
                                                    targetState = uiState.currentSong,
                                                    transitionSpec = {
                                                        fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                                                    },
                                                    label = "miniPlayerBgArt"
                                                ) { currentSong ->
                                                    val context = LocalContext.current
                                                    val model = remember(currentSong?.id) {
                                                        ImageRequest.Builder(context)
                                                            .data(currentSong?.albumArtUri)
                                                            .diskCachePolicy(CachePolicy.ENABLED)
                                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                                            .error(ImageUtils.getDefaultAlbumArtRes())
                                                            .fallback(ImageUtils.getDefaultAlbumArtRes())
                                                            .build()
                                                    }
                                                    AsyncImage(
                                                        model = model,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .blur(70.dp),
                                                        contentScale = ContentScale.Crop,
                                                        alpha = 1f
                                                    )
                                                }
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            Brush.verticalGradient(
                                                                listOf(
                                                                    Color.Black.copy(0.4f),
                                                                    Color.Black.copy(0.6f)
                                                                )
                                                            )
                                                        )
                                                        .border(
                                                            width = 0.5.dp,
                                                            brush = Brush.verticalGradient(
                                                                listOf(Color.White.copy(0.4f), Color.White.copy(0.05f))
                                                            ),
                                                            shape = RoundedCornerShape(20.dp)
                                                        )
                                                )
                                            }

                                            MiniPlayerProgressBar(
                                                progressProvider = {
                                                    val duration = uiState.currentSong?.durationMs ?: 0L
                                                    if (duration > 0) progressMs.toFloat() / duration.toFloat() else 0f
                                                }
                                            )

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    modifier = Modifier.size(44.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color.White.copy(0.05f)
                                                ) {
                                                    AnimatedContent(
                                                        targetState = uiState.currentSong,
                                                        transitionSpec = {
                                                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                                                        },
                                                        label = "miniPlayerArt"
                                                    ) { currentSong ->
                                                        val context = LocalContext.current
                                                        val model = remember(currentSong?.id) {
                                                            ImageRequest.Builder(context)
                                                                .data(currentSong?.albumArtUri)
                                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                                .error(ImageUtils.getDefaultAlbumArtRes())
                                                                .fallback(ImageUtils.getDefaultAlbumArtRes())
                                                                .build()
                                                        }
                                                        AsyncImage(
                                                            model = model,
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    }
                                                }

                                                Spacer(Modifier.width(12.dp))

                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        text = uiState.currentSong?.title ?: "Unknown",
                                                        color = Color.White,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = uiState.currentSong?.artist ?: "Unknown Artist",
                                                            color = Color.White.copy(0.7f),
                                                            fontSize = 13.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        MiniPlayerTimeText(
                                                            progressProvider = { progressMs },
                                                            duration = uiState.currentSong?.durationMs ?: 0L
                                                        )
                                                    }
                                                }

                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(onClick = { viewModel.togglePlayPause() }) {
                                                        Icon(
                                                            if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                            null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(28.dp)
                                                        )
                                                    }
                                                    IconButton(onClick = { viewModel.skipToNext() }) {
                                                        Icon(
                                                            Icons.Rounded.SkipNext,
                                                            null,
                                                            tint = Color.White.copy(0.8f),
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // END FIX 3
                                }
                            }
                        }



                        var songToDelete by remember { mutableStateOf<com.beatraxus.app.model.Song?>(null) }

                        if (songToDelete != null) {
                            AlertDialog(
                                onDismissRequest = { songToDelete = null },
                                title = { Text("Delete Song?") },
                                text = { Text("Are you sure you want to delete '${songToDelete?.title}'? This action cannot be undone.") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            songToDelete?.let { viewModel.deleteSong(it) }
                                            songToDelete = null
                                            selectedSongForOptions = null
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                                    ) {
                                        Text("Delete")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { songToDelete = null }) {
                                        Text("Cancel")
                                    }
                                },
                                containerColor = Color(0xFF1A1A1A),
                                titleContentColor = Color.White,
                                textContentColor = Color.White.copy(0.7f)
                            )
                        }

                                selectedSongForOptions?.let { song ->
                            SongOptionsSheet(
                                song = song,
                                currentPlayingSong = uiState.currentSong,
                                initialShowInfoOverlay = reopenSongOptionsInfo.also { reopenSongOptionsInfo = false },
                                onDismiss = { selectedSongForOptions = null },
                                onPlayNext = {
                                    viewModel.playNext(song)
                                    selectedSongForOptions = null
                                },
                                onAddToQueue = {
                                    viewModel.addToQueue(song)
                                    selectedSongForOptions = null
                                },
                                        onAddToPlaylist = {
                                            // Open playlist selection for single song
                                            playlistDialogSong = song
                                            showPlaylistDialog = true
                                            selectedSongForOptions = null
                                        },
                                onInfo = {
                                    viewModel.fetchOnlineInfo(song)
                                },
                                onDelete = {
                                    songToDelete = song
                                },
                                isFavorite = favorites.contains(song.id),
                                onToggleFavorite = { viewModel.toggleFavorite(song) },
                                onGoToArtist = {
                                    viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.ARTIST_DETAIL, song.artist)
                                    selectedSongForOptions = null
                                },
                                onGoToAlbum = {
                                    viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.ALBUM_DETAIL, song.album)
                                    selectedSongForOptions = null
                                },
                                onGoToFolder = {
                                    viewModel.navigateToFolder(song.folder, song.folder.substringAfterLast("/"))
                                    selectedSongForOptions = null
                                },
                                onGoToGenre = {
                                    viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.GENRE_DETAIL, song.genre)
                                    selectedSongForOptions = null
                                },
                                onOpenInspector = { s ->
                                    viewModel.setPendingInspectorReturn(s)
                                    onNavigateToInspector(s.id)
                                    selectedSongForOptions = null
                                },
                                lastFmTrackInfo = uiState.lastFmTrackInfo,
                                lastFmArtistInfo = uiState.lastFmArtistInfo,
                                lastFmAlbumInfo = uiState.lastFmAlbumInfo,
                                isLoadingInfo = uiState.isLoadingOnlineInfo,
                                selectedLastFmTrackInfo = uiState.selectedLastFmTrackInfo,
                                selectedLastFmArtistInfo = uiState.selectedLastFmArtistInfo,
                                selectedLastFmAlbumInfo = uiState.selectedLastFmAlbumInfo,
                                isSelectedLoading = uiState.isSelectedLoadingOnlineInfo
                            )
                        }

                        // Integrated Main Sheets (Replacing Popups)
                        if (activeMainSheet != null) {
                            ModalBottomSheet(
                                onDismissRequest = { activeMainSheet = null },
                                containerColor = BgDeep.copy(alpha = 0.95f),
                                scrimColor = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                tonalElevation = 8.dp
                            ) {
                                when (activeMainSheet) {
                                    MainSheetType.SORT -> SortSheetContent(viewModel, uiState, onDismiss = { activeMainSheet = null })
                                    MainSheetType.CAST -> {
                                        val context = LocalContext.current
                                        CastSheetContent(
                                            currentSong = uiState.currentSong,
                                            onCast = { route ->
                                                uiState.currentSong?.let { song ->
                                                    com.beatraxus.app.cast.CastManager.castSong(context, route, song, song.uri.toString())
                                                }
                                            },
                                            onDismiss = { activeMainSheet = null }
                                        )
                                    }
                                    MainSheetType.CLOUD -> {
                                        val driveAccounts = uiState.driveAccounts
                                        val telegramChannels = uiState.telegramChannels
                                        CloudSheetContent(
                                            accounts = driveAccounts,
                                            telegramChannels = telegramChannels,
                                            onSelectAccount = { email -> viewModel.setLibraryView(LibraryView.CLOUD, email) },
                                            onSelectTelegramChannel = { url -> viewModel.setLibraryViewTelegram(url) },
                                            onRefreshAccount = { email -> viewModel.scanDriveAccount(email) },
                                            onSyncTelegramChannel = { url -> viewModel.syncTelegramChannel(url) },
                                            onDismiss = { activeMainSheet = null }
                                        )
                                    }
                                    MainSheetType.DENSITY -> {
                                        val isGrid = uiState.currentView in listOf(
                                            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                            LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS
                                        )
                                        LayoutDensitySheetContent(
                                            isGrid = isGrid,
                                            categoryGridColumns = categoryGridColumns,
                                            onCategoryGridColumnsChange = { categoryGridColumns = it },
                                            trackLayoutDensity = trackLayoutDensity,
                                            onTrackLayoutDensityChange = { trackLayoutDensity = it },
                                            onDismiss = { activeMainSheet = null }
                                        )
                                    }
                                    else -> {}
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }

        if (showPlaylistDialog) {
            PlaylistSelectionDialog(
                playlists = playlists,
                onDismiss = {
                    showPlaylistDialog = false
                    playlistDialogSong = null
                },
                onSelect = { playlist ->
                    if (playlistDialogSong != null) {
                        viewModel.addSongToPlaylist(playlist.name, playlistDialogSong!!.id)
                    } else {
                        viewModel.addSelectedToPlaylist(playlist.name)
                    }
                    showPlaylistDialog = false
                    playlistDialogSong = null
                },
                onCreateNew = { name ->
                    if (playlistDialogSong != null) {
                        viewModel.addSongToPlaylist(name, playlistDialogSong!!.id)
                    } else {
                        viewModel.addSelectedToPlaylist(name)
                    }
                    showPlaylistDialog = false
                    playlistDialogSong = null
                }
            )
        }

        if (playlistToDelete != null) {
            AlertDialog(
                onDismissRequest = { playlistToDelete = null },
                title = { Text("Delete Playlist?") },
                text = { Text("Are you sure you want to delete '${playlistToDelete?.name}'? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            playlistToDelete?.let { viewModel.deletePlaylist(it.id) }
                            playlistToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { playlistToDelete = null }) {
                        Text("Cancel")
                    }
                },
                containerColor = Color(0xFF1A1A1A),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(0.7f)
            )
        }

        // Floating Add button on Playlists view to create new playlists
        if (uiState.currentView == LibraryView.PLAYLISTS && drawerProgress == 0f && !showFullPlayer) {
            val isMiniPlayerVisible = uiState.currentSong != null
            val bottomPadding by animateDpAsState(
                targetValue = if (isMiniPlayerVisible) 80.dp else 16.dp,
                label = "fab_bottom_padding"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding, end = 16.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = AccentBlue,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .size(56.dp)
                        .clickable {
                            playlistDialogSong = null
                            showPlaylistDialog = true
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = Color.Black, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        if (showDrawer || drawerProgress > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent) // Removed dark shade
                    .clickable(
                        enabled = drawerProgress > 0f,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showDrawer = false }
                    .zIndex(8f)
            )
        }

        AnimatedVisibility(
            visible = showFullPlayer && uiState.currentSong != null,
            modifier = Modifier.fillMaxSize().zIndex(100f),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(tween(400)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            ) + fadeOut(tween(400))
        ) {
            NowPlayingScreen(
                song = uiState.currentSong,
                isPlaying = uiState.isPlaying,
                progressMs = { progressMs },
                durationMs = uiState.currentSong?.durationMs ?: 0L,
                shuffleMode = uiState.shuffleMode,
                repeatMode = uiState.repeatMode,
                uiState = uiState,
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.skipToNext() },
                onPrevious = { viewModel.skipToPrevious() },
                onShuffle = { viewModel.toggleShuffle() },
                onRepeat = { viewModel.toggleRepeat() },
                onSeek = { viewModel.seekTo(it) },
                onClose = { showFullPlayer = false },
                onOpenEqualizer = onNavigateToDsp,
                onToggleQueue = { viewModel.toggleQueue() },
                onRemoveFromQueue = { viewModel.removeFromQueue(it) },
                onMoveInQueue = { from, to -> viewModel.moveInQueue(from, to) },
                onPlayFromQueue = { viewModel.playFromQueue(it) },
                previousSongs = uiState.previousSongs,
                upcomingSongs = uiState.upcomingSongs,
                isFavorite = uiState.currentSong?.let { favorites.contains(it.id) } ?: false,
                onFavoriteClick = { uiState.currentSong?.let { viewModel.toggleFavorite(it) } },
                onNavigateToAlbum = { album ->
                    viewModel.setCameFromNowPlaying(true)
                    viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.ALBUM_DETAIL, album)
                    showFullPlayer = false
                },
                onNavigateToInspector = { songId ->
                    onNavigateToInspector(songId)
                },
                onSetShowSongInfoConsumed = { viewModel.setShowSongInfo(false) },
                onClearPendingInspectorReturn = { viewModel.setPendingInspectorReturn(null) },
                onToggleLyrics = { viewModel.toggleLyrics() },
                onAdjustOffset = { viewModel.adjustLyricsOffset(it) },
                onSetLyricsOffset = { viewModel.setLyricsOffset(it) },
                onSearchLyricsOnline = { viewModel.forceSearchLyricsOnline() },
                showPipelineOverlay = showPipelineOverlay,
                onTogglePipeline = { showPipelineOverlay = it },
                onSetSleepTimer = { seconds, finishTrack, playCount ->
                    viewModel.setSleepTimer(seconds, finishTrack, playCount)
                },
                onStopSleepTimer = { viewModel.stopSleepTimer() }
            )
        }

        AnimatedVisibility(
            visible = showPipelineOverlay && !uiState.showQueue,
            modifier = Modifier.fillMaxSize().zIndex(110f),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180))
        ) {
            uiState.currentSong?.let { song ->
                AudioPipelineOverlay(
                    song = song,
                    uiState = uiState,
                    onDismiss = { showPipelineOverlay = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
}

@Composable
fun CastSheetContent(
    currentSong: com.beatraxus.app.model.Song?,
    onCast: (androidx.mediarouter.media.MediaRouter.RouteInfo) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(AccentBlue.copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Cast, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "CAST TO DEVICE",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
        }

        HorizontalDivider(color = Color.White.copy(0.08f), modifier = Modifier.padding(horizontal = 16.dp))

        if (com.beatraxus.app.cast.CastManager.availableDevices.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = AccentBlue.copy(0.5f),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Searching for devices...",
                    color = Color.White.copy(0.5f),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 280.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(com.beatraxus.app.cast.CastManager.availableDevices) { route ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCast(route); onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(0.03f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(0.08f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Tv, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                route.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E676), CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("Ready to cast", color = Color(0xFF00E676).copy(0.8f), fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (com.beatraxus.app.cast.CastManager.isConnected) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFF5252).copy(0.08f))
                    .border(1.dp, Color(0xFFFF5252).copy(0.15f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "CURRENTLY CASTING",
                            color = Color(0xFFFF5252).copy(0.7f),
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            com.beatraxus.app.cast.CastManager.connectedDeviceName ?: "Device",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    IconButton(
                        onClick = { com.beatraxus.app.cast.CastManager.stopCast(); onDismiss() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFF5252), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CastDevicePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    currentSong: com.beatraxus.app.model.Song?,
    onCast: (androidx.mediarouter.media.MediaRouter.RouteInfo) -> Unit
) {
    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 240.dp
    ) {
        CastSheetContent(currentSong, onCast, onDismiss)
    }
}

@Composable
fun LayoutDensityPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    isGrid: Boolean,
    categoryGridColumns: Int,
    onCategoryGridColumnsChange: (Int) -> Unit,
    trackLayoutDensity: Int,
    onTrackLayoutDensityChange: (Int) -> Unit
) {
    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 240.dp
    ) {
        val maxVal = if (isGrid) 5f else 6f
        val currentVal = if (isGrid) categoryGridColumns.toFloat() else trackLayoutDensity.toFloat()

        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isGrid) Icons.Rounded.GridView else Icons.Rounded.FormatLineSpacing,
                    null,
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isGrid) "GRID COLUMNS" else "LAYOUT DENSITY",
                    color = Color.White.copy(0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(24.dp))

            Box(contentAlignment = Alignment.Center) {
                Slider(
                    value = currentVal,
                    onValueChange = { newSize ->
                        if (isGrid) {
                            onCategoryGridColumnsChange(newSize.toInt().coerceIn(1, 5))
                        } else {
                            onTrackLayoutDensityChange(newSize.toInt().coerceIn(1, 6))
                        }
                    },
                    valueRange = 1f..maxVal,
                    steps = (maxVal - 2).toInt(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentBlue,
                        activeTrackColor = AccentBlue,
                        inactiveTrackColor = Color.White.copy(0.1f)
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AccentBlue.copy(0.12f), CircleShape)
                    .border(1.dp, AccentBlue.copy(0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentVal.toInt().toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
fun CloudDrivePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    accounts: List<com.beatraxus.app.repository.DriveAccount>,
    telegramChannels: List<com.beatraxus.app.model.TelegramChannel>,
    onSelectAccount: (String?) -> Unit,
    onSelectTelegramChannel: (String) -> Unit,
    onRefreshAccount: (String) -> Unit,
    onSyncTelegramChannel: (String) -> Unit
) {
    val enabledAccounts = remember(accounts) { accounts.filter { it.enabled } }

    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 240.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                "CLOUD ACCOUNTS",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            // Option to show ALL cloud songs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectAccount(null); onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(0.04f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(30.dp).background(AccentBlue.copy(0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.CloudQueue, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("All Accounts", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp, horizontal = 14.dp), color = Color.White.copy(0.08f))

            if (enabledAccounts.isEmpty()) {
                Text(
                    "No accounts connected",
                    color = Color.White.copy(0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                enabledAccounts.forEach { account ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAccount(account.email); onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(0.03f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).background(Color.White.copy(0.08f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.AccountCircle, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                account.accountName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                account.email,
                                color = Color.White.copy(0.4f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            onClick = { onRefreshAccount(account.email) },
                            color = AccentBlue.copy(0.15f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Sync, null, tint = AccentBlue, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SYNC", color = AccentBlue, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }

            val enabledChannels = telegramChannels.filter { it.enabled }
            if (enabledChannels.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "TELEGRAM CHANNELS",
                    color = Color.White.copy(0.5f),
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                enabledChannels.forEach { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTelegramChannel(channel.url); onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2AABEE).copy(0.05f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(Color(0xFF2AABEE).copy(0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                channel.name.firstOrNull()?.uppercaseChar()?.toString() ?: "",
                                color = Color(0xFF2AABEE),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            channel.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            onClick = { onSyncTelegramChannel(channel.url) },
                            color = Color(0xFF2AABEE).copy(0.12f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Sync, null, tint = Color(0xFF2AABEE), modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SYNC", color = Color(0xFF2AABEE), fontSize = 9.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: PlayerViewModel,
    uiState: com.beatraxus.app.model.PlayerUiState,
    listState: LazyListState
) {
    val recentlyPlayed by viewModel.homeRecentlyPlayed.collectAsStateWithLifecycle()
    val quickPicks by viewModel.homeQuickPicks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val aiAnalysis by viewModel.aiAnalysis.collectAsStateWithLifecycle()
    var showAllMoodsDialog by remember { mutableStateOf(false) }

    val allMoods = remember {
        listOf(
            MoodData("Sleep", Icons.Rounded.Bedtime, Color(0xFF5E5CE6), listOf("Sleep", "Ambient", "Calm")),
            MoodData("Calm", Icons.Rounded.SelfImprovement, Color(0xFF32D74B), listOf("Calm", "Relaxing", "Chill", "Acoustic")),
            MoodData("Focus", Icons.Rounded.CenterFocusStrong, Color(0xFF00F2FF), listOf("Focus", "Classical", "Lofi")),
            MoodData("Energetic", Icons.Rounded.FlashOn, Color(0xFFFFD60A), listOf("Energy", "EDM", "Dance")),
            MoodData("Workout", Icons.Rounded.FitnessCenter, Color(0xFFFF6B00), listOf("Workout", "Gym", "Hip-Hop")),
            MoodData("Happy", Icons.Rounded.SentimentVerySatisfied, Color(0xFFFFC107), listOf("Happy", "Pop", "Feel Good")),
            MoodData("Sad", Icons.Rounded.WaterDrop, Color(0xFF5C6BC0), listOf("Sad", "Blues", "Ballad")),
            MoodData("Romantic", Icons.Rounded.Favorite, Color(0xFFFF4081), listOf("Romantic", "Love", "R&B")),
            MoodData("Party", Icons.Rounded.Celebration, Color(0xFFE040FB), listOf("Party", "Dance", "Club")),
            MoodData("Motivational", Icons.Rounded.EmojiEvents, Color(0xFFFF9100), listOf("Motivational", "Inspirational")),
            MoodData("Aggressive", Icons.Rounded.Whatshot, Color(0xFFFF1744), listOf("Aggressive", "Metal", "Rock")),
            MoodData("Meditation", Icons.Rounded.Spa, Color(0xFF00BFA5), listOf("Meditation", "Yoga", "Ambient")),
            MoodData("Emotional", Icons.Rounded.TheaterComedy, Color(0xFF7C4DFF), listOf("Emotional", "Soul")),
            MoodData("Epic", Icons.Rounded.Landscape, Color(0xFFFF6D00), listOf("Epic", "Soundtrack", "Cinematic")),
            MoodData("Dark", Icons.Rounded.NightsStay, Color(0xFF37474F), listOf("Dark", "Gothic", "Industrial"))
        )
    }

    // Accurate mood matching: prefer AI+BPM+Last.fm moodTags, fall back to
    // keyword matching only for songs that haven't been analyzed yet.
    fun playMood(mood: MoodData) {
        val moodSongs = allSongs.filter { song ->
            val tags = aiAnalysis[song.id]?.moodTags
            if (!tags.isNullOrBlank()) {
                tags.split(",").any { it.trim().equals(mood.label, ignoreCase = true) }
            } else {
                mood.keywords.any { kw -> kw.lowercase() in song.genre.lowercase() || kw.lowercase() in song.title.lowercase() }
            }
        }
        if (moodSongs.isNotEmpty()) viewModel.playList(moodSongs.shuffled(), 0)
    }

    val calendar = java.util.Calendar.getInstance()
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

    val greeting = when (hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        in 17..20 -> "Good Evening"
        else -> "Good Night"
    }

    val greetingIcon = when (hour) {
        in 5..11 -> Icons.Rounded.WbSunny
        in 12..16 -> Icons.Rounded.LightMode
        in 17..20 -> Icons.Rounded.WbTwilight
        else -> Icons.Rounded.NightsStay
    }

    val greetingColors = when (hour) {
        in 5..11 -> listOf(Color(0xFF00F2FF).copy(0.15f), Color(0xFF0066FF).copy(0.05f))
        in 12..16 -> listOf(Color(0xFFFFD60A).copy(0.15f), Color(0xFFFF9F0A).copy(0.05f))
        in 17..20 -> listOf(Color(0xFFFF5E62).copy(0.15f), Color(0xFFB91D73).copy(0.05f))
        else -> listOf(Color(0xFF5E5CE6).copy(0.18f), Color(0xFF131B2A).copy(0.08f))
    }

    val deviceName = "Audiophile"

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        item {
            // Updated Premium Greeting Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.9f))
                    .border(
                        0.5.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(0.15f), Color.White.copy(0.05f))
                        ),
                        RoundedCornerShape(24.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        // Compact Premium Icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(greetingColors[0].copy(alpha = 0.2f))
                                .border(1.dp, greetingColors[0].copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = greetingIcon,
                                contentDescription = null,
                                tint = greetingColors[0].copy(alpha = 1f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = "$greeting,",
                                fontSize = 14.sp,
                                color = Color.White.copy(0.5f),
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.2.sp
                            )
                            Text(
                                text = deviceName,
                                fontSize = 20.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                        }
                    }

                    // Compact Shuffle Button
                    Surface(
                        onClick = { viewModel.shuffleAndPlay() },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF00E5FF), Color(0xFF1200FF))
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ActionChip(
                        label = "Shuffle All",
                        icon = Icons.Rounded.Shuffle,
                        onClick = { viewModel.playList(allSongs.shuffled(), 0) }
                    )
                }
                item {
                    ActionChip(
                        label = "Favorites",
                        icon = Icons.Rounded.Favorite,
                        onClick = {
                            val favSongs = allSongs.filter { favorites.contains(it.id) }
                            if (favSongs.isNotEmpty()) viewModel.playList(favSongs, 0)
                        }
                    )
                }
                item {
                    ActionChip(
                        label = "Recently Added",
                        icon = Icons.Rounded.NewReleases,
                        onClick = {
                            val recentSongs = allSongs.sortedByDescending { it.dateAdded }
                            if (recentSongs.isNotEmpty()) viewModel.playList(recentSongs, 0)
                        }
                    )
                }
            }
        }

        item {
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.libraryMode != LibraryMode.LOCAL || uiState.showSyncStatusOnHome,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                var showCloudPopup by remember { mutableStateOf(false) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(0.08f), Color.White.copy(0.02f))
                            )
                        )
                        .border(
                            0.5.dp,
                            Brush.verticalGradient(
                                listOf(Color.White.copy(0.15f), Color.Transparent)
                            ),
                            RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            if (uiState.selectedTelegramChannelUrl != null) {
                                viewModel.setLibraryViewTelegram(uiState.selectedTelegramChannelUrl!!)
                            } else {
                                viewModel.setLibraryView(LibraryView.CLOUD, uiState.selectedCloudEmail)
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFF1A73E8).copy(0.15f), CircleShape)
                                        .border(1.dp, Color(0xFF1A73E8).copy(0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.CloudSync,
                                        null,
                                        tint = Color(0xFF1A73E8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "CLOUD LIBRARY",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.2.sp
                                    )
                                    val currentAccount = when {
                                        uiState.selectedCloudEmail != null -> uiState.selectedCloudEmail
                                        uiState.selectedTelegramChannelUrl != null -> {
                                            uiState.telegramChannels.find { it.url == uiState.selectedTelegramChannelUrl }?.name ?: "Telegram"
                                        }
                                        else -> "All Accounts"
                                    }
                                    Text(
                                        text = currentAccount ?: "All Accounts",
                                        color = Color.White.copy(0.5f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (uiState.isCloudScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color(0xFF1A73E8),
                                        strokeWidth = 2.dp
                                    )
                                } else if (uiState.isSyncFinishedRecently) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                IconButton(
                                    onClick = { showCloudPopup = true },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                                        .background(if (showCloudPopup) Color.Black else Color.White.copy(0.08f), CircleShape)
                                        .then(if (showCloudPopup) Modifier.border(1.dp, Color.White.copy(0.4f), CircleShape) else Modifier)
                                ) {
                                    Icon(
                                        Icons.Rounded.SwapHoriz,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        val statusText = if (uiState.isCloudScanning) {
                            uiState.enrichmentStatus ?: uiState.driveErrorMessage ?: uiState.telegramSyncErrorMessage ?: "Syncing..."
                        } else if (uiState.isSyncFinishedRecently) {
                            uiState.driveErrorMessage ?: uiState.telegramSyncErrorMessage ?: "Sync Complete"
                        } else ""

                        if (statusText.isNotEmpty()) {
                            Text(
                                text = statusText,
                                color = Color.White.copy(0.7f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        if (uiState.isCloudScanning) {
                            LinearProgressIndicator(
                                progress = { uiState.scanProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = Color(0xFF1A73E8),
                                trackColor = Color(0xFF1A73E8).copy(0.1f)
                            )
                            Spacer(Modifier.height(16.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(0.05f))
                                    .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp))
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                StatItem(Icons.Rounded.MusicNote, uiState.cloudSongCount.toString(), "Songs", Color(0xFFFF4081))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(0.05f))
                                    .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp))
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                StatItem(Icons.Rounded.Album, uiState.cloudAlbumCount.toString(), "Albums", Color(0xFF00E676))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(0.05f))
                                    .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp))
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                StatItem(Icons.Rounded.Person, uiState.cloudArtistCount.toString(), "Artists", Color(0xFF2979FF))
                            }
                        }
                    }

                    if (showCloudPopup) {
                        CloudDrivePopup(
                            expanded = showCloudPopup,
                            onDismiss = { showCloudPopup = false },
                            anchorBounds = anchorBounds,
                            accounts = uiState.driveAccounts,
                            telegramChannels = uiState.telegramChannels,
                            onSelectAccount = { viewModel.setCloudAccount(it) },
                            onSelectTelegramChannel = { viewModel.setCloudTelegram(it) },
                            onRefreshAccount = { viewModel.scanDriveAccount(it) },
                            onSyncTelegramChannel = { viewModel.syncTelegramChannel(it) }
                        )
                    }
                }
            }
        }

        // Browse by Mood
        item {
            HomeSectionHeader(
                title = "Browse by Mood",
                actionText = "See All",
                actionIcon = Icons.Rounded.KeyboardArrowRight,
                onActionClick = { showAllMoodsDialog = true }
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(allMoods) { mood ->
                    MoodTile(mood) { playMood(mood) }
                }
            }
        }

        // Additional Sections (Optional, placed after the main content from image)
        if (quickPicks.isNotEmpty()) {
            item { HomeSectionHeader("Made For You") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(quickPicks.take(5), key = { "featured_${it.id}" }) { song ->
                        Box(
                            modifier = Modifier
                                .width(280.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { viewModel.playSong(song) }
                                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp))
                        ) {
                            AsyncImage(
                                model = song.albumArtUri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(0.85f)),
                                            startY = 100f
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = "FEATURED",
                                    color = Color(0xFF00F2FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    style = androidx.compose.ui.text.TextStyle(letterSpacing = 2.sp)
                                )
                                Text(
                                    text = song.title,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    color = Color.White.copy(0.7f),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                                    .size(44.dp)
                                    .background(Color.White.copy(0.15f), CircleShape)
                                    .border(1.dp, Color.White.copy(0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }

        if (recentlyPlayed.isNotEmpty()) {
            item { HomeSectionHeader("Listen Again") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(recentlyPlayed, key = { it.id }) { song ->
                        HomeSongItem(song) { viewModel.playSong(song) }
                    }
                }
            }
        }

        val recentlyAddedSongs = allSongs.sortedByDescending { it.dateAdded }.take(15)
        if (recentlyAddedSongs.isNotEmpty()) {
            item { HomeSectionHeader("Recently Added") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(recentlyAddedSongs, key = { _, song -> "recent_${song.id}" }) { index, song ->
                        HomeSongCard(song) {
                            viewModel.playList(recentlyAddedSongs, index)
                        }
                    }
                }
            }
        }

        val favoriteSongs = allSongs.filter { favorites.contains(it.id) }.take(15)
        if (favoriteSongs.isNotEmpty()) {
            item { HomeSectionHeader("Your Favorites") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(favoriteSongs, key = { _, song -> "fav_${song.id}" }) { index, song ->
                        HomeSongCard(song) {
                            viewModel.playList(favoriteSongs, index)
                        }
                    }
                }
            }
        }

        if (albums.isNotEmpty()) {
            item { HomeSectionHeader("Featured Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(albums.take(10), key = { it.first }) { album ->
                        HomeGridItem(album.first, album.second, album.third) {
                            viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.ALBUM_DETAIL, album.first)
                        }
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            item { HomeSectionHeader("Artists You Love") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(artists.take(10), key = { it.first }) { artist ->
                        HomeArtistItem(artist.first, artist.third) {
                            viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.ARTIST_DETAIL, artist.first)
                        }
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item { HomeSectionHeader("Your Playlists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        HomeGridItem(playlist.name, "${playlist.songIds.size} songs", null) {
                            viewModel.setLibraryView(com.beatraxus.app.model.LibraryView.PLAYLIST_DETAIL, playlist.name)
                        }
                    }
                }
            }
        }
    }

    if (showAllMoodsDialog) {
        AllMoodsDialog(
            moods = allMoods,
            onDismiss = { showAllMoodsDialog = false },
            onMoodClick = { mood -> playMood(mood); showAllMoodsDialog = false }
        )
    }
}

private data class MoodData(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val keywords: List<String>
)

@Composable
private fun MoodTile(
    mood: MoodData,
    modifier: Modifier = Modifier.size(width = 130.dp, height = 100.dp),
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(mood.color.copy(0.2f), mood.color.copy(0.5f))
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(0.15f), Color.Transparent)
                ),
                RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Text(
            text = mood.label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopStart)
        )

        Icon(
            imageVector = mood.icon,
            contentDescription = null,
            tint = Color.White.copy(0.3f),
            modifier = Modifier
                .size(54.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 10.dp, y = 10.dp)
        )
    }
}

@Composable
private fun AllMoodsDialog(
    moods: List<MoodData>,
    onDismiss: () -> Unit,
    onMoodClick: (MoodData) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp, 24.dp, 20.dp, 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("All Moods", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(moods) { mood ->
                        MoodTile(
                            mood = mood,
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        ) { onMoodClick(mood) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(0.08f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color(0xFF00F2FF).copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(0.08f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF00F2FF).copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


@Composable
fun HomeSectionHeader(
    title: String,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        if (actionText != null || actionIcon != null) {
            Row(
                modifier = Modifier.clickable { onActionClick?.invoke() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (actionText != null) {
                    Text(
                        text = actionText,
                        color = Color(0xFF00F2FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (actionIcon != null) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = Color(0xFF00F2FF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HomeSongCard(song: com.beatraxus.app.model.Song, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable { onClick() },
        color = Color.White.copy(0.06f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(0.06f))
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    color = Color.White.copy(0.5f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun HomeSongItem(song: com.beatraxus.app.model.Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier
                .size(140.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(0.05f)
        ) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artist,
            color = Color.White.copy(0.6f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun HomeGridItem(title: String, subtitle: String, artUri: android.net.Uri?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier.size(150.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(0.1f)
        ) {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(subtitle, color = Color.White.copy(0.6f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun HomeArtistItem(name: String, artUri: android.net.Uri?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(110.dp)
            .clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier.size(110.dp),
            shape = CircleShape,
            color = Color.White.copy(0.1f)
        ) {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
fun MiniPlayerProgressBar(progressProvider: () -> Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(Color.White.copy(0.1f))
    ) {
        val progress by remember { derivedStateOf { progressProvider().coerceIn(0f, 1f) } }
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(Color.White)
        )
    }
}

@Composable
fun MiniPlayerTimeText(progressProvider: () -> Long, duration: Long) {
    val currentProgress by remember { derivedStateOf { formatTime(progressProvider()) } }
    val totalDuration = remember(duration) { formatTime(duration) }
    Text(
        text = "($currentProgress/$totalDuration)",
        color = Color.White.copy(0.7f),
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun StatItemSmall(label: String, count: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(count, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, color = Color.White.copy(0.5f), fontSize = 10.sp)
        }
    }
}

@Composable
fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, count: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(count, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = Color.White.copy(0.5f), fontSize = 11.sp)
    }
}


@Composable
fun AlphabetScroller(
    modifier: Modifier = Modifier,
    items: List<String>,
    onScrollTo: (Int) -> Unit
) {
    val alphabet = remember { listOf('↑', '0') + ('A'..'Z').toList() + '#' }
    var currentLetter by remember { mutableStateOf<Char?>(null) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    val alphabetIndices = remember(items) {
        alphabet.associateWith { letter ->
            when (letter) {
                '↑' -> 0
                '0' -> items.indexOfFirst { it.firstOrNull()?.isDigit() == true }
                '#' -> items.indexOfFirst { it.firstOrNull()?.let { c -> !c.isLetterOrDigit() && !c.isWhitespace() } == true }
                else -> items.indexOfFirst { it.startsWith(letter, ignoreCase = true) }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .onGloballyPositioned {
                itemHeight = it.size.height.toFloat() / alphabet.size.coerceAtLeast(1)
            }
            // FIX 4: awaitFirstDown directly inside awaitEachGesture, updateScroll as local fun
            .pointerInput(alphabetIndices) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    var lastLetter: Char? = null

                    fun updateScroll(pos: Offset) {
                        if (itemHeight <= 0) return
                        val index = (pos.y / itemHeight).toInt().coerceIn(0, alphabet.size - 1)
                        val letter = alphabet[index]
                        if (lastLetter != letter) {
                            lastLetter = letter
                            currentLetter = letter
                            alphabetIndices[letter]?.let { if (it != -1) onScrollTo(it) }
                        }
                    }

                    updateScroll(down.position)

                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        if (anyPressed) {
                            val pos = event.changes.first().position
                            updateScroll(pos)
                            event.changes.forEach { it.consume() }
                        } else {
                            // When released, perform a final smooth scroll to the exact start of the section
                            currentLetter?.let { letter ->
                                alphabetIndices[letter]?.let { if (it != -1) onScrollTo(it) }
                            }
                            currentLetter = null
                            break
                        }
                    }
                }
            }
            .background(Color.Transparent)
            .wrapContentHeight(Alignment.CenterVertically)
    ) {
        Column(
            modifier = Modifier
                .width(22.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(0.3f), RoundedCornerShape(11.dp))
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { letter ->
                Text(
                    text = letter.toString(),
                    color = if (currentLetter == letter) AccentBlue else Color.White.copy(0.5f),
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    fontWeight = if (currentLetter == letter) FontWeight.ExtraBold else FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (currentLetter != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-40).dp)
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(0.8f))
                    .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetter.toString(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@Composable
fun GenreGridItem(
    title: String,
    subtitle: String,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val randomColor = remember(title) {
        listOf(
            Color(0xFF8E44AD), Color(0xFF2980B9), Color(0xFF27AE60),
            Color(0xFFD35400), Color(0xFFC0392B), Color(0xFF16A085),
            Color(0xFFF39C12), Color(0xFF2C3E50)
        ).random()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .background(randomColor.copy(alpha = 0.8f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp)
    ) {
        Column {
            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = Color.White.copy(0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) AccentBlue.copy(0.4f) else Color.Transparent),
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(if (isSelected) AccentBlue else Color.Black.copy(0.3f), CircleShape)
                        .border(1.dp, Color.White.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Icon(
            Icons.Rounded.MusicNote,
            null,
            tint = Color.White.copy(0.2f),
            modifier = Modifier
                .size(64.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 12.dp, y = 12.dp)
        )
    }
}

@Composable
fun LibraryGridItem(
    title: String,
    subtitle: String,
    artUri: android.net.Uri?,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    isArtistTile: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .graphicsLayer {}
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(16.dp)
                },
            color = Color.White.copy(0.15f)
        ) {
            if (isArtistTile && artUri == null) {
                // No embedded album art — show a deterministic initials avatar instead
                ArtistAvatar(name = title, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = artUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
            }

            if (isMultiSelectMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) AccentBlue.copy(0.4f) else Color.Transparent),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                            .background(if (isSelected) AccentBlue else Color.Black.copy(0.3f), CircleShape)
                            .border(1.dp, Color.White.copy(0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            color = Color.White.copy(0.6f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SongGridItem(
    song: com.beatraxus.app.model.Song,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isCompact: Boolean = false,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(0.05f)
        ) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
            if (isCurrent && !isMultiSelectMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            if (isMultiSelectMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) AccentBlue.copy(0.4f) else Color.Transparent),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                            .background(if (isSelected) AccentBlue else Color.Black.copy(0.3f), CircleShape)
                            .border(1.dp, Color.White.copy(0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(if (isCompact) 4.dp else 8.dp))
        Text(
            song.title,
            color = Color.White,
            fontSize = if (isCompact) 11.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!isCompact) {
            Text(
                song.artist,
                color = Color.White.copy(0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
fun SortDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    viewModel: PlayerViewModel,
    uiState: com.beatraxus.app.model.PlayerUiState
) {
    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 190.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            // Compact Header with Order Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "SORT BY",
                    color = Color.White.copy(0.5f),
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(0.06f))
                        .border(0.5.dp, Color.White.copy(0.1f), CircleShape)
                        .clickable { viewModel.toggleSortOrder() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (uiState.isAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                        null,
                        tint = AccentBlue,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            listOf(
                Triple("Name", SortType.NAME, Icons.Rounded.SortByAlpha),
                Triple("Date Added", SortType.DATE_ADDED, Icons.Rounded.CalendarToday),
                Triple("File Size", SortType.FILE_SIZE, Icons.Rounded.Storage),
                Triple("Duration", SortType.DURATION, Icons.Rounded.Schedule)
            ).forEach { (label, type, icon) ->
                val isSelected = uiState.sortType == type
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) AccentBlue.copy(0.12f) else Color.Transparent)
                        .clickable { viewModel.setSortType(type); onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        null,
                        tint = if (isSelected) AccentBlue else Color.White.copy(0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        color = if (isSelected) Color.White else Color.White.copy(0.7f),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    if (isSelected) {
                        Spacer(Modifier.weight(1f))
                        Box(modifier = Modifier.size(4.dp).background(AccentBlue, CircleShape))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}


data class DrawerMenuItem(
    val label: String,
    val view: LibraryView,
    val icon: ImageVector,
    val color: Color
)

@Composable
fun SlideDrawerMenu(
    currentView: LibraryView,
    libraryMode: LibraryMode,
    accentColor: Color,
    drawerProgress: Float = 1f,
    isPlaying: Boolean = false,
    onSelectView: (LibraryView) -> Unit,
    onSetLibraryMode: (LibraryMode) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onClose: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "menuBackground")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientOffset"
    )

    val menuItems = remember(libraryMode) {
        listOf(
            DrawerMenuItem("Home", LibraryView.HOME, Icons.Rounded.Home, Color(0xFF00E676)),
            DrawerMenuItem("All Songs", LibraryView.ALL_SONGS, Icons.Rounded.MusicNote, Color(0xFFFF4081)),
            DrawerMenuItem("Albums", LibraryView.ALBUMS, Icons.Rounded.Album, Color(0xFFB2FF59)),
            DrawerMenuItem("Artists", LibraryView.ARTISTS, Icons.Rounded.Person, Color(0xFF7C4DFF)),
            DrawerMenuItem("Folders", LibraryView.FOLDERS, Icons.Rounded.Folder, Color(0xFFFFAB40)),
            DrawerMenuItem("Years", LibraryView.YEARS, Icons.Rounded.CalendarToday, Color(0xFFFF5252)),
            DrawerMenuItem("Genres", LibraryView.GENRES, Icons.Rounded.GridView, Color(0xFFE040FB)),
            DrawerMenuItem("Playlists", LibraryView.PLAYLISTS, Icons.AutoMirrored.Rounded.PlaylistPlay, Color(0xFFFDD835)),
            DrawerMenuItem("Favorite Songs", LibraryView.FAVORITES, Icons.Rounded.Favorite, Color(0xFFFF5252)),
            DrawerMenuItem("Recently Added", LibraryView.RECENTLY_ADDED, Icons.Rounded.NewReleases, Color(0xFF00E676)),
            DrawerMenuItem("Recently Played", LibraryView.RECENTLY_PLAYED, Icons.Rounded.History, Color(0xFF40C4FF)),
            DrawerMenuItem("Radio", LibraryView.RADIO, Icons.Rounded.Radio, Color(0xFF00B8D4)),
            DrawerMenuItem("SMB / NAS", LibraryView.SMB_NAS, Icons.Rounded.Storage, Color(0xFF546E7A)),
            DrawerMenuItem("FTP / SFTP", LibraryView.FTP_SFTP, Icons.Rounded.Dns, Color(0xFF8D6E63))
        ).filter { item ->
            !(item.view == LibraryView.FOLDERS && libraryMode == LibraryMode.CLOUD)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .graphicsLayer {
                alpha = drawerProgress.coerceIn(0f, 1f)
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // SECTION 1 — Header
            Column(
                modifier = Modifier
                    .padding(top = 40.dp, start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(0.12f),
                                    accentColor.copy(0.15f),
                                    Color.White.copy(0.12f)
                                ),
                                start = Offset(gradientOffset, gradientOffset),
                                end = Offset(gradientOffset + 500f, gradientOffset + 500f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        CastButton(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(32.dp),
                            tint = if (com.beatraxus.app.cast.CastManager.isConnected ||
                                com.beatraxus.app.cast.CastManager.availableDevices.isNotEmpty()
                            ) Color.White else Color.Black
                        )
                        if (isPlaying) {
                            val notesTransition = rememberInfiniteTransition(label = "notes")

                            val note1X by notesTransition.animateFloat(
                                initialValue = 0.1f, targetValue = 0.9f,
                                animationSpec = infiniteRepeatable(tween(6000), RepeatMode.Reverse), label = "n1x"
                            )
                            val note1Y by notesTransition.animateFloat(
                                initialValue = 0.2f, targetValue = 0.8f,
                                animationSpec = infiniteRepeatable(tween(8000), RepeatMode.Reverse), label = "n1y"
                            )
                            val note2X by notesTransition.animateFloat(
                                initialValue = 0.8f, targetValue = 0.2f,
                                animationSpec = infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "n2x"
                            )
                            val note2Y by notesTransition.animateFloat(
                                initialValue = 0.7f, targetValue = 0.3f,
                                animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse), label = "n2y"
                            )

                            Icon(
                                Icons.Rounded.MusicNote, null,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = 180.dp * note1X, y = 100.dp * note1Y)
                                    .blur(8.dp)
                                    .size(40.dp),
                                tint = Color.White.copy(0.2f)
                            )
                            Icon(
                                Icons.Rounded.Audiotrack, null,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = 180.dp * note2X, y = 100.dp * note2Y)
                                    .blur(6.dp)
                                    .size(36.dp),
                                tint = accentColor.copy(0.25f)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .graphicsLayer {
                                    alpha = drawerProgress.coerceIn(0f, 1f)
                                    translationY = 20f * (1f - drawerProgress)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = 0.8f + (0.2f * drawerProgress)
                                        scaleY = 0.8f + (0.2f * drawerProgress)
                                        alpha = drawerProgress
                                    }
                                    .size(48.dp)
                                    .background(accentColor.copy(0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Headphones,
                                    null,
                                    modifier = Modifier.size(28.dp),
                                    tint = accentColor
                                )
                            }
                            Spacer(Modifier.height(12.dp))

                            Text(
                                "Hello\nAudiophile",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                lineHeight = 28.sp,
                                modifier = Modifier.graphicsLayer {
                                    alpha = drawerProgress.coerceIn(0f, 1f)
                                    translationX = -10f * (1f - drawerProgress)
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            LibraryModeSelector(
                            currentMode = libraryMode,
                            onModeSelected = onSetLibraryMode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = drawerProgress.coerceIn(0f, 1f)
                                    translationX = -5f * (1f - drawerProgress)
                                }
                        )
                    }
                }
            }
        }

        // SECTION 3 — Library items
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = drawerProgress.coerceIn(0f, 1f)
                    },
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(menuItems) { item ->
                    val isSelected = currentView == item.view ||
                            (item.view == LibraryView.ALBUMS && currentView == LibraryView.ALBUM_DETAIL) ||
                            (item.view == LibraryView.ARTISTS && currentView == LibraryView.ARTIST_DETAIL) ||
                            (item.view == LibraryView.FOLDERS && currentView == LibraryView.FOLDER_DETAIL) ||
                            (item.view == LibraryView.YEARS && currentView == LibraryView.YEAR_DETAIL) ||
                            (item.view == LibraryView.GENRES && currentView == LibraryView.GENRE_DETAIL) ||
                            (item.view == LibraryView.PLAYLISTS && currentView == LibraryView.PLAYLIST_DETAIL)

                    val itemBg = if (isSelected) item.color.copy(0.3f) else Color.White.copy(0.1f)

                    Surface(
                        color = itemBg,
                        shape = RoundedCornerShape(18.dp),
                        border = if (isSelected) BorderStroke(1.dp, item.color.copy(0.45f)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable { onSelectView(item.view); onClose() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                item.icon,
                                null,
                                modifier = Modifier.size(22.dp),
                                tint = if (isSelected) Color.White else Color.White.copy(0.7f)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                item.label,
                                color = if (isSelected) Color.White else Color.White.copy(0.7f),
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // SECTION 4 — Bottom controls
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .graphicsLayer {
                        alpha = drawerProgress.coerceIn(0f, 1f)
                        translationY = 10f * (1f - drawerProgress)
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = Color.White.copy(0.08f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clickable {
                                android.widget.Toast.makeText(context, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ColorLens, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
                        }
                    }
                    Surface(
                        color = Color.White.copy(0.08f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clickable { onNavigateToDsp(); onClose() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
                        }
                    }
                    Surface(
                        color = Color.White.copy(0.08f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clickable { onNavigateToSettings(); onClose() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Settings, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

fun Modifier.pinchToZoom(onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var initialDist = -1f
            var fired = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val changes = event.changes

                if (changes.size >= 2) {
                    val p1 = changes[0].position
                    val p2 = changes[1].position
                    val dx = p1.x - p2.x
                    val dy = p1.y - p2.y
                    val dist = sqrt(dx * dx + dy * dy)

                    if (initialDist < 0) {
                        initialDist = dist
                    } else if (!fired && initialDist > 10f) {
                        val ratio = dist / initialDist
                        if (ratio > 1.22f) {
                            onZoomIn()
                            fired = true
                        } else if (ratio < 0.82f) {
                            onZoomOut()
                            fired = true
                        }
                    }

                    if (fired) {
                        changes.forEach { it.consume() }
                    }
                }

                if (changes.none { it.pressed }) break
            }
        }
    }

@Composable
fun SortSheetContent(
    viewModel: PlayerViewModel,
    uiState: com.beatraxus.app.model.PlayerUiState,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val titleText = if (uiState.currentView == com.beatraxus.app.model.LibraryView.CLOUD) "SORT & FILTER" else "SORT BY"
            Text(titleText, color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp)
            IconButton(onClick = { viewModel.toggleSortOrder() }, modifier = Modifier.size(40.dp).background(Color.White.copy(0.06f), CircleShape)) {
                Icon(if (uiState.isAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
            }
        }
        listOf(
            Triple("Name", com.beatraxus.app.model.SortType.NAME, Icons.Rounded.SortByAlpha),
            Triple("Date Added", com.beatraxus.app.model.SortType.DATE_ADDED, Icons.Rounded.CalendarToday),
            Triple("File Size", com.beatraxus.app.model.SortType.FILE_SIZE, Icons.Rounded.Storage),
            Triple("Duration", com.beatraxus.app.model.SortType.DURATION, Icons.Rounded.Schedule)
        ).forEach { (label, type, icon) ->
            val isSelected = uiState.sortType == type
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) AccentBlue.copy(0.12f) else Color.White.copy(0.04f))
                    .clickable { viewModel.setSortType(type); onDismiss() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = if (isSelected) AccentBlue else Color.White.copy(0.5f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(label, color = if (isSelected) Color.White else Color.White.copy(0.7f), fontSize = 15.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
            }
        }

        // Phase 4: quality-tier filter chips (audio quality analyzer). Shown for the
        // main song list views where a per-song SongQualityEntity is meaningful.
        if (uiState.currentView == com.beatraxus.app.model.LibraryView.ALL_SONGS ||
            uiState.currentView == com.beatraxus.app.model.LibraryView.FAVORITES ||
            uiState.currentView == com.beatraxus.app.model.LibraryView.RECENTLY_ADDED
        ) {
            Spacer(Modifier.height(20.dp))
            Text("QUALITY", color = Color.White.copy(0.5f), fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf(null, "Excellent", "Good", "Fair", "Poor")) { tier ->
                    val isSelected = uiState.qualityTierFilter == tier
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) AccentBlue.copy(0.18f) else Color.White.copy(0.04f))
                            .border(
                                1.dp,
                                if (isSelected) AccentBlue.copy(0.6f) else Color.White.copy(0.08f),
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { viewModel.setQualityTierFilter(tier) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tier ?: "All",
                            color = if (isSelected) Color.White else Color.White.copy(0.7f),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun CloudSheetContent(
    accounts: List<com.beatraxus.app.repository.DriveAccount>,
    telegramChannels: List<com.beatraxus.app.model.TelegramChannel>,
    onSelectAccount: (String?) -> Unit,
    onSelectTelegramChannel: (String) -> Unit,
    onRefreshAccount: (String) -> Unit,
    onSyncTelegramChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val enabledAccounts = remember(accounts) { accounts.filter { it.enabled } }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("CLOUD ACCOUNTS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 16.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.04f)).clickable { onSelectAccount(null); onDismiss() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudQueue, null, tint = AccentBlue, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text("All Accounts", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        enabledAccounts.forEach { account ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.04f)).clickable { onSelectAccount(account.email); onDismiss() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AccountCircle, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.accountName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(account.email, color = Color.White.copy(0.4f), fontSize = 11.sp)
                }
                Surface(
                    onClick = { onRefreshAccount(account.email) },
                    color = AccentBlue.copy(0.15f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Sync, null, tint = AccentBlue, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SYNC", color = AccentBlue, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        val enabledChannels = telegramChannels.filter { it.enabled }
        if (enabledChannels.isNotEmpty()) {
            Text("TELEGRAM CHANNELS", color = Color.White.copy(0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            enabledChannels.forEach { channel ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2AABEE).copy(0.05f)).clickable { onSelectTelegramChannel(channel.url); onDismiss() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name.firstOrNull()?.uppercaseChar()?.toString() ?: "", color = Color(0xFF2AABEE), fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(12.dp))
                    Text(channel.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Surface(
                        onClick = { onSyncTelegramChannel(channel.url) },
                        color = Color(0xFF2AABEE).copy(0.12f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Sync, null, tint = Color(0xFF2AABEE), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SYNC", color = Color(0xFF2AABEE), fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun LayoutDensitySheetContent(
    isGrid: Boolean,
    categoryGridColumns: Int,
    onCategoryGridColumnsChange: (Int) -> Unit,
    trackLayoutDensity: Int,
    onTrackLayoutDensityChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val maxVal = if (isGrid) 5f else 6f
    val currentVal = if (isGrid) categoryGridColumns.toFloat() else trackLayoutDensity.toFloat()
    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (isGrid) "GRID COLUMNS" else "LAYOUT DENSITY", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(24.dp))
        Slider(value = currentVal, onValueChange = { if (isGrid) onCategoryGridColumnsChange(it.toInt()) else onTrackLayoutDensityChange(it.toInt()) }, valueRange = 1f..maxVal, steps = (maxVal - 2).toInt(), colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue))
        Text(currentVal.toInt().toString(), color = AccentBlue, fontWeight = FontWeight.Black, fontSize = 24.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) { Text("DONE", color = Color.White) }
    }
}
