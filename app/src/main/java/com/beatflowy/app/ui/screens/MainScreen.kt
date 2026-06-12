package com.beatflowy.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.beatflowy.app.model.LibraryMode
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.SortType
import com.beatflowy.app.ui.components.*
import com.beatflowy.app.ui.theme.*
import com.beatflowy.app.viewmodel.PlayerViewModel

@Composable
fun MainBackground(
    albumArtUri: android.net.Uri?,
    blurEffect: AndroidRenderEffect?
) {
    if (albumArtUri != null) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(albumArtUri)
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

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onNavigateToDownload: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progressMs by viewModel.progressMs.collectAsStateWithLifecycle()

    val viewAccentColor = when (uiState.currentView) {
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
    }

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
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val activeItemsCount by remember(uiState.currentView, uiState.isSearchActive, songs, albums, artists, folders, years, genres, playlists, searchResults) {
        derivedStateOf {
            if (uiState.isSearchActive) searchResults.size
            else when (uiState.currentView) {
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

    val searchFieldRowVisible = uiState.isSearchActive

    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchFieldRowVisible) {
        if (searchFieldRowVisible) {
            kotlinx.coroutines.delay(60)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    val scope = rememberCoroutineScope()

    // Header hide on scroll logic
    var headerVisible by remember { mutableStateOf(true) }
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
    var showSortMenu   by remember { mutableStateOf(false) }
    var sortMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var selectedSongForOptions by remember { mutableStateOf<com.beatflowy.app.model.Song?>(null) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistDialogSong by remember { mutableStateOf<com.beatflowy.app.model.Song?>(null) }
    var playlistToDelete by remember { mutableStateOf<com.beatflowy.app.model.Playlist?>(null) }
    var showPipelineOverlay by remember { mutableStateOf(false) }
    var showDrawer by rememberSaveable { mutableStateOf(false) }
    var showCastPopup by remember { mutableStateOf(false) }
    var showCloudPopup by remember { mutableStateOf(false) }
    var showSortPopup by remember { mutableStateOf(false) }

    var categoryGridColumns by rememberSaveable { mutableIntStateOf(2) }
    var trackLayoutDensity by rememberSaveable { mutableIntStateOf(1) }
    var showLayoutDensitySlider by remember { mutableStateOf(false) }
    var layoutSliderAnchor by remember { mutableStateOf(Rect.Zero) }

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

    BackHandler(enabled = showFullPlayer || uiState.isSearchActive || isDetailView || uiState.currentView != LibraryView.ALL_SONGS || showDrawer || showSortMenu || showPipelineOverlay) {
        if (showDrawer) {
            showDrawer = false
        } else if (showPipelineOverlay) {
            showPipelineOverlay = false
        } else if (showSortMenu) {
            showSortMenu = false
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
                else -> LibraryView.ALL_SONGS
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
        } else if (uiState.currentView != LibraryView.ALL_SONGS) {
            viewModel.setLibraryView(LibraryView.ALL_SONGS)
        }
    }

    val blurByScan = 0f
    val saturationByScan = 1f
    val alphaByScan = 1f

    val drawerProgress by animateFloatAsState(
        targetValue = if (showDrawer) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = 380f
        ),
        label = "drawerProgress"
    )

    val contentScale = 1f - (0.10f * drawerProgress)
    val contentRotation = -6f * drawerProgress
    val contentCornerRadius = (32f * drawerProgress).dp
    val drawerDimAlpha = 0.6f * drawerProgress
    val drawerOffsetX = (-210f + (210f * drawerProgress)).dp

    Box(Modifier.fillMaxSize()) {
        // 1. Static Full-Screen Background (Static)
        Box(Modifier.fillMaxSize().background(Color.Black))

        MainBackground(
            albumArtUri = uiState.currentSong?.albumArtUri,
            blurEffect = cachedBackgroundBlurEffect
        )

        val needsGraphicsLayer = blurByScan > 0.1f || saturationByScan < 0.99f || alphaByScan < 0.99f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = drawerProgress * with(density) { 210.dp.toPx() }
                    scaleX = contentScale
                    scaleY = contentScale
                    rotationY = contentRotation
                    cameraDistance = 12f * density.density
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    shape = RoundedCornerShape(contentCornerRadius.coerceAtLeast(0.dp))
                    clip = true
                }
        ) {
            // Main Content Area (Transformed)
            // We can add a very subtle glass or dark tint here if we want the "sheet" to be distinct
            Box(
                Modifier.fillMaxSize()
            )

            // Content Dim Overlay
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = drawerDimAlpha))
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
                                val titleText = when (uiState.currentView) {
                                    LibraryView.ALL_SONGS -> "All Songs"
                                    LibraryView.ALBUMS -> "Albums"
                                    LibraryView.ARTISTS -> "Artists"
                                    LibraryView.FOLDERS -> "Folders"
                                    LibraryView.YEARS -> "Years"
                                    LibraryView.GENRES -> "Genres"
                                    LibraryView.PLAYLISTS -> "Playlists"
                                    LibraryView.FAVORITES -> "Favorites"
                                    LibraryView.RECENTLY_PLAYED -> "Recently Played"
                                    LibraryView.RECENTLY_ADDED -> "Recently Added"
                                    LibraryView.ALBUM_DETAIL -> uiState.selectedItemName ?: "Album"
                                    LibraryView.ARTIST_DETAIL -> uiState.selectedItemName ?: "Artist"
                                    LibraryView.PLAYLIST_DETAIL -> uiState.selectedItemName ?: "Playlist"
                                    LibraryView.FOLDER_DETAIL -> uiState.selectedItemName ?: "Folder"
                                    LibraryView.YEAR_DETAIL -> uiState.selectedItemName ?: "Year"
                                    LibraryView.GENRE_DETAIL -> uiState.selectedItemName ?: "Genre"
                                    LibraryView.CLOUD -> "Cloud Account"
                                }
                                val titleIcon = when (uiState.currentView) {
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

                                // Centered Title
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 64.dp, end = if (isTitleTouchingSettings) 12.dp else 64.dp)
                                        .animateContentSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .animateContentSize()
                                            .onGloballyPositioned { titleWidth = it.size.width.toFloat() }
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(viewAccentColor.copy(alpha = 0.15f))
                                            .border(
                                                width = 1.dp,
                                                brush = Brush.linearGradient(
                                                    colors = listOf(
                                                        viewAccentColor.copy(alpha = 0.5f),
                                                        viewAccentColor.copy(alpha = 0.1f)
                                                    )
                                                ),
                                                shape = RoundedCornerShape(28.dp)
                                            )
                                            .clickable { showDrawer = true }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(viewAccentColor.copy(alpha = 0.15f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(titleIcon, null, tint = viewAccentColor, modifier = Modifier.size(15.dp))
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = titleText,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 17.sp,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
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

                                // Icons on the right
                                Box(
                                    modifier = Modifier.align(Alignment.CenterEnd).wrapContentSize(),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    if (uiState.isMultiSelectMode) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = {
                                                // Open playlist selection for multi-select
                                                playlistDialogSong = null
                                                showPlaylistDialog = true
                                            }) {
                                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Color.White)
                                            }
                                            IconButton(onClick = { viewModel.deleteSelectedSongs() }) {
                                                Icon(Icons.Rounded.Delete, null, tint = Color.White)
                                            }
                                            IconButton(onClick = { viewModel.setMultiSelectMode(false) }) {
                                                Icon(Icons.Rounded.Close, null, tint = Color.White)
                                            }
                                        }
                                    } else {
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
                                                if (uiState.isSearchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                                                null,
                                                tint = if (uiState.isSearchActive) AccentBlue else Color.White.copy(0.9f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
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
                                        // FIX 2: Correct brace structure for clear button
                                        if (uiState.searchQuery.isNotEmpty()) {
                                            IconButton(
                                                onClick = { viewModel.setSearchQuery("") },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Close,
                                                    null,
                                                    tint = Color.White.copy(0.6f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        // END FIX 2
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            // Action Icons Row
                            AnimatedVisibility(
                                visible = headerVisible || activeItemsCount <= 8,
                                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    val canShufflePlay = when (uiState.currentView) {
                                        LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                        LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS -> false
                                        else -> true
                                    }

                                    val playAllWeight by animateFloatAsState(
                                        targetValue = if (canShufflePlay) 3.5f else 0.001f,
                                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                                        label = "playAllWeight"
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(playAllWeight)
                                                .graphicsLayer {
                                                    alpha = (playAllWeight / 3.5f).coerceIn(0f, 1f)
                                                    scaleX = (playAllWeight / 3.5f).coerceIn(0.5f, 1f)
                                                    scaleY = (playAllWeight / 3.5f).coerceIn(0.5f, 1f)
                                                    clip = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Surface(
                                                color = Color.White.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(28.dp),
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                                modifier = Modifier
                                                    .wrapContentSize()
                                                    .clickable(enabled = canShufflePlay) { viewModel.shuffleAndPlay() }
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
                                                    AnimatedVisibility(
                                                        visible = playAllWeight > 2f,
                                                        enter = fadeIn() + expandHorizontally(),
                                                        exit = fadeOut() + shrinkHorizontally()
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        }

                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val sortIconBgColor by animateColorAsState(
                                                targetValue = if (showSortMenu) Color.White.copy(0.2f) else Color.Transparent,
                                                animationSpec = tween(400),
                                                label = "sortIconBgColor"
                                            )
                                            Box(contentAlignment = Alignment.Center) {
                                                IconButton(
                                                    onClick = { showSortMenu = true },
                                                    modifier = Modifier
                                                        .size(46.dp)
                                                        .glassIconBackground(
                                                            backgroundColor = sortIconBgColor,
                                                            shape = CircleShape,
                                                            borderColor = if (showSortMenu) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                        )
                                                ) {
                                                    Icon(
                                                        Icons.AutoMirrored.Rounded.Sort,
                                                        null,
                                                        tint = if (showSortMenu) AccentBlue else Color.White.copy(0.85f),
                                                        modifier = Modifier
                                                            .size(23.dp)
                                                            .onGloballyPositioned { sortMenuAnchor = it.boundsInRoot() }
                                                    )
                                                }
                                                SortDropdown(
                                                    expanded = showSortMenu,
                                                    onDismiss = { showSortMenu = false },
                                                    anchorBounds = sortMenuAnchor,
                                                    viewModel = viewModel,
                                                    uiState = uiState
                                                )
                                            }
                                        }

                                        var castIconBounds by remember { mutableStateOf(Rect.Zero) }
                                        var cloudIconBounds by remember { mutableStateOf(Rect.Zero) }
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            val context = androidx.compose.ui.platform.LocalContext.current
                                            val castIconBgColor by animateColorAsState(
                                                targetValue = if (showCastPopup) Color.White.copy(0.12f) else Color.Transparent,
                                                label = "castIconBg"
                                            )
                                            IconButton(
                                                onClick = { showCastPopup = true },
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .glassIconBackground(
                                                        backgroundColor = castIconBgColor,
                                                        shape = CircleShape,
                                                        borderColor = if (showCastPopup) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                    )
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Cast,
                                                    null,
                                                    tint = if (showCastPopup || com.beatflowy.app.cast.CastManager.isConnected) AccentBlue else Color.White.copy(0.85f),
                                                    modifier = Modifier
                                                        .size(23.dp)
                                                        .onGloballyPositioned {
                                                            castIconBounds = it.boundsInRoot()
                                                        }
                                                )
                                            }
                                            CastDevicePopup(
                                                expanded = showCastPopup,
                                                onDismiss = { showCastPopup = false },
                                                anchorBounds = castIconBounds,
                                                currentSong = uiState.currentSong,
                                                onCast = { route ->
                                                    uiState.currentSong?.let { song ->
                                                        com.beatflowy.app.cast.CastManager.castSong(context, route, song, song.uri.toString())
                                                    }
                                                }
                                            )
                                        }

                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            val cloudIconBgColor by animateColorAsState(
                                                targetValue = if (showCloudPopup) Color.White.copy(0.12f) else Color.Transparent,
                                                label = "cloudIconBg"
                                            )
                                            IconButton(
                                                onClick = { showCloudPopup = true },
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .glassIconBackground(
                                                        backgroundColor = cloudIconBgColor,
                                                        shape = CircleShape,
                                                        borderColor = if (showCloudPopup) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                    )
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Cloud,
                                                    null,
                                                    tint = if (showCloudPopup) AccentBlue else Color.White.copy(0.85f),
                                                    modifier = Modifier
                                                        .size(23.dp)
                                                        .onGloballyPositioned {
                                                            cloudIconBounds = it.boundsInRoot()
                                                        }
                                                )
                                            }
                                            val driveAccounts by viewModel.driveAccounts.collectAsState(initial = emptyList())
                                            val telegramChannels by viewModel.telegramChannels.collectAsStateWithLifecycle(emptyList())
                                            CloudDrivePopup(
                                                expanded = showCloudPopup,
                                                onDismiss = { showCloudPopup = false },
                                                anchorBounds = cloudIconBounds,
                                                accounts = driveAccounts,
                                                telegramChannels = telegramChannels,
                                                onSelectAccount = { email ->
                                                    viewModel.setLibraryView(LibraryView.CLOUD, email)
                                                },
                                                onSelectTelegramChannel = { url -> 
                                                    viewModel.setLibraryViewTelegram(url)
                                                },
                                                onRefreshAccount = { email ->
                                                    viewModel.scanDriveAccount(email)
                                                },
                                                onSyncTelegramChannel = { url ->
                                                    viewModel.syncTelegramChannel(url)
                                                }
                                            )
                                        }

                                        // Layout Density Slider Icon
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            val isGrid = uiState.currentView in listOf(
                                                LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                                LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS
                                            )
                                            val sliderIconBgColor by animateColorAsState(
                                                targetValue = if (showLayoutDensitySlider) Color.White.copy(0.12f) else Color.Transparent,
                                                label = "sliderIconBg"
                                            )
                                            IconButton(
                                                onClick = { showLayoutDensitySlider = true },
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .glassIconBackground(
                                                        backgroundColor = sliderIconBgColor,
                                                        shape = CircleShape,
                                                        borderColor = if (showLayoutDensitySlider) Color.White.copy(alpha = 0.2f) else Color.Transparent
                                                    )
                                            ) {
                                                Icon(
                                                    Icons.Rounded.GridView,
                                                    null,
                                                    tint = if (showLayoutDensitySlider) AccentBlue else Color.White.copy(0.85f),
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .onGloballyPositioned {
                                                            layoutSliderAnchor = it.boundsInRoot()
                                                        }
                                                )
                                            }

                                            LayoutDensityPopup(
                                                expanded = showLayoutDensitySlider,
                                                onDismiss = { showLayoutDensitySlider = false },
                                                anchorBounds = layoutSliderAnchor,
                                                isGrid = isGrid,
                                                categoryGridColumns = categoryGridColumns,
                                                onCategoryGridColumnsChange = { categoryGridColumns = it },
                                                trackLayoutDensity = trackLayoutDensity,
                                                onTrackLayoutDensityChange = { trackLayoutDensity = it }
                                            )
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
                                androidx.compose.animation.AnimatedContent(
                                    targetState = uiState.isSearchActive && uiState.searchQuery.isNotEmpty(),
                                    transitionSpec = {
                                        (fadeIn(animationSpec = tween(400, delayMillis = 90)) +
                                                scaleIn(initialScale = 0.92f, animationSpec = tween(400, delayMillis = 90))
                                                ).togetherWith(
                                                fadeOut(animationSpec = tween(300))
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
                                                        is com.beatflowy.app.model.Song -> {
                                                            val currentNumber = songIndex++
                                                            item(key = "song_${item.id}") {
                                                                Box(modifier = Modifier.padding(horizontal = 8.dp).animateItem()) {
                                                                    SongListItem(
                                                                        song = item,
                                                                        trackNumber = currentNumber,
                                                                        isPlaying = uiState.currentSong?.id == item.id,
                                                                        onClick = { viewModel.playSong(item) },
                                                                        onMoreClick = { selectedSongForOptions = item },
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
                                                                    LibraryGridItem(title, artist, art) {
                                                                        viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, title)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        is Pair<*, *> -> {
                                                            val name = item.first as String
                                                            val art = item.second as android.net.Uri?
                                                            item(key = "artist_$name") {
                                                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem()) {
                                                                    LibraryGridItem(name, "Artist", art) {
                                                                        viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, name)
                                                                    }
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
                                                (fadeIn(animationSpec = tween(400, delayMillis = 90)) +
                                                        scaleIn(initialScale = 0.92f, animationSpec = tween(400, delayMillis = 90))
                                                        ).togetherWith(
                                                        fadeOut(animationSpec = tween(300))
                                                    )
                                            },
                                            label = "viewTransition"
                                        ) { targetView ->
                                            when (targetView) {
                                                LibraryView.CLOUD -> {
                                                    val accounts by viewModel.driveAccounts.collectAsStateWithLifecycle(emptyList())
                                                    if (accounts.isEmpty()) {
                                                        Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                                                            Icon(Icons.Rounded.Cloud, null, tint=Color(0xFF1A73E8), modifier=Modifier.size(64.dp))
                                                            Spacer(Modifier.height(16.dp))
                                                            Text("No Cloud accounts connected", color=Color.White.copy(0.6f), textAlign=TextAlign.Center)
                                                            Text("Add an account in Settings → Cloud Account", color=Color.White.copy(0.3f), fontSize=13.sp, textAlign=TextAlign.Center)
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

                                                        LazyColumn(state=listState, contentPadding=PaddingValues(bottom=120.dp)) {
                                                            item {
                                                                Column {
                                                                    androidx.compose.animation.AnimatedVisibility(
                                                                        visible = uiState.isCloudScanning,
                                                                        enter = expandVertically() + fadeIn(),
                                                                        exit = shrinkVertically() + fadeOut()
                                                                    ) {
                                                                        Surface(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                                                            color = Color(0xFF1A73E8).copy(0.1f),
                                                                            shape = RoundedCornerShape(12.dp),
                                                                            border = BorderStroke(1.dp, Color(0xFF1A73E8))
                                                                        ) {
                                                                            Row(
                                                                                modifier = Modifier.padding(12.dp),
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
                                                                                    text = when {
                                                                                        uiState.scanProgress >= 1.0f -> "Enrichment complete"
                                                                                        uiState.scanProgress > 0f -> "Enriching metadata... ${(uiState.scanProgress * 100).toInt()}%"
                                                                                        else -> "Syncing cloud library..."
                                                                                    },
                                                                                    color = Color.White,
                                                                                    fontSize = 12.sp,
                                                                                    fontWeight = FontWeight.Bold
                                                                                )
                                                                            }
                                                                        }
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
                                                                    
                                                                    Spacer(Modifier.height(16.dp))
                                                                    
                                                                    Button(
                                                                        onClick = { showSyncConfirm = true },
                                                                        colors = ButtonDefaults.buttonColors(
                                                                            containerColor = Color(0xFF1A73E8).copy(0.15f),
                                                                            contentColor = Color(0xFF1A73E8)
                                                                        ),
                                                                        shape = RoundedCornerShape(12.dp),
                                                                        border = BorderStroke(1.dp, Color(0xFF1A73E8).copy(0.3f)),
                                                                        modifier = Modifier.height(36.dp),
                                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                                                    ) {
                                                                        Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(16.dp))
                                                                        Spacer(Modifier.width(8.dp))
                                                                        Text("Sync Library", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                                    }
                                                                }
                                                            }
                                                            itemsIndexed(songs, key={_,s->s.id}) { index, song ->
                                                                Box(Modifier.animateItem()) {
                                                                    SongListItem(
                                                                        song=song, isPlaying=uiState.isPlaying && uiState.currentSong?.id==song.id,
                                                                        trackNumber=index+1, isCompact=isCompactList,
                                                                        onClick={ viewModel.playSong(song) },
                                                                        onMoreClick={ selectedSongForOptions=song }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                LibraryView.ALBUMS -> {
                                                    val isGridView = trackLayoutDensity <= 2 // In this view, zoom affects item size but not layout type, OR we can implement something similar
                                                    Box(Modifier.fillMaxSize()) {
                                                        LazyVerticalGrid(
                                                            state = gridState,
                                                            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                        ) {
                                                            items(albums, key = { it.first + it.second }) { album ->
                                                                Box(Modifier.animateItem()) {
                                                                    LibraryGridItem(album.first, album.second, album.third) {
                                                                        viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, album.first)
                                                                    }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "albumDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                            state = gridState,
                                                            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                        ) {
                                                            items(artists, key = { it.first }) { artist ->
                                                                Box(Modifier.animateItem()) {
                                                                    LibraryGridItem(artist.first, artist.second, artist.third) {
                                                                        viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, artist.first)
                                                                    }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "artistDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                        state = gridState,
                                                        columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                    ) {
                                                        items(folders, key = { it.first }) { folder ->
                                                            Box(Modifier.animateItem()) {
                                                                LibraryGridItem(folder.second, folder.first, folder.third) {
                                                                    viewModel.navigateToFolder(folder.first, folder.second)
                                                                }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "folderDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                        columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                    ) {
                                                        items(years, key = { it.first }) { year ->
                                                            Box(Modifier.animateItem()) {
                                                                LibraryGridItem(year.first, year.second, year.third) {
                                                                    viewModel.setLibraryView(LibraryView.YEAR_DETAIL, year.first)
                                                                }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "yearDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                        columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                    ) {
                                                        items(genres, key = { it.first }) { genre ->
                                                            Box(Modifier.animateItem()) {
                                                                GenreGridItem(genre.first, genre.second) {
                                                                    viewModel.setLibraryView(LibraryView.GENRE_DETAIL, genre.first)
                                                                }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "genreDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                            columns = GridCells.Fixed(categoryGridColumns.coerceIn(1, 5)),
                                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp),
                                                            verticalArrangement = Arrangement.spacedBy(if (categoryGridColumns >= 3) 8.dp else 16.dp)
                                                        ) {
                                                            items(playlists, key = { it.name }) { playlist ->
                                                                Box(Modifier.animateItem()) {
                                                                    Box(
                                                                        modifier = Modifier.combinedClickable(
                                                                            onClick = {
                                                                                viewModel.setLibraryView(LibraryView.PLAYLIST_DETAIL, playlist.name)
                                                                            },
                                                                            onLongClick = {
                                                                                playlistToDelete = playlist
                                                                            }
                                                                        )
                                                                    ) {
                                                                        LibraryGridItem(playlist.name, "${playlist.songIds.size} songs", null) {
                                                                            viewModel.setLibraryView(LibraryView.PLAYLIST_DETAIL, playlist.name)
                                                                        }
                                                                    }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "playlistDetailTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "favoritesTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
                                                                                isCompact = trackLayoutDensity >= 5
                                                                            )
                                                                        }
                                                                    }
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
                                                            (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(
                                                                fadeOut(tween(300))
                                                            )
                                                        },
                                                        label = "mainLibraryTransition"
                                                    ) { targetIsListView ->
                                                        Box(Modifier.fillMaxSize()) {
                                                            if (targetIsListView) {
                                                                LazyColumn(
                                                                    state = listState,
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
                                                                                isSelected = uiState.selectedSongIds.contains(song.id),
                                                                                onMoreClick = { selectedSongForOptions = song },
                                                                                isCompact = trackLayoutDensity == 2
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                // Alphabet Fast Scroller
                                                                if (uiState.currentView == LibraryView.ALL_SONGS) {
                                                                    val songTitles = remember(songs) { songs.map { it.title } }
                                                                    AlphabetScroller(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                                                        items = songTitles,
                                                                        onScrollTo = { targetIndex: Int ->
                                                                            scope.launch {
                                                                                // Use scrollToItem for immediate visual feedback during drag
                                                                                listState.scrollToItem(targetIndex)
                                                                            }
                                                                        }
                                                                    )
                                                                }
                                                            } else {
                                                                LazyVerticalGrid(
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
                                                                                onClick = { viewModel.playSong(song) },
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
                                                                scope.launch {
                                                                    listState.animateScrollToItem(index)
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
                                                AsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(uiState.currentSong?.albumArtUri)
                                                        .diskCachePolicy(CachePolicy.ENABLED)
                                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                                        .build(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .blur(70.dp),
                                                    contentScale = ContentScale.Crop,
                                                    alpha = 1f
                                                )
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
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(uiState.currentSong?.albumArtUri)
                                                            .diskCachePolicy(CachePolicy.ENABLED)
                                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                                            .build(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop
                                                    )
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



                        AnimatedVisibility(
                            visible = showFullPlayer && uiState.currentSong != null,
                            enter = slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
                            ) + fadeIn(tween(400)),
                            exit = slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
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
                                upcomingSongs = uiState.upcomingSongs,
                                isFavorite = uiState.currentSong?.let { favorites.contains(it.id) } ?: false,
                                onFavoriteClick = { uiState.currentSong?.let { viewModel.toggleFavorite(it) } },
                                onNavigateToAlbum = { album ->
                                    viewModel.setCameFromNowPlaying(true)
                                    viewModel.setLibraryView(com.beatflowy.app.model.LibraryView.ALBUM_DETAIL, album)
                                    showFullPlayer = false
                                },
                                onToggleLyrics = { viewModel.toggleLyrics() },
                                onAdjustOffset = { viewModel.adjustLyricsOffset(it) },
                                onSetLyricsOffset = { viewModel.setLyricsOffset(it) },
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

                        var songToDelete by remember { mutableStateOf<com.beatflowy.app.model.Song?>(null) }

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
                                    selectedSongForOptions = null
                                },
                                onDelete = {
                                    songToDelete = song
                                },
                                isFavorite = favorites.contains(song.id),
                                onToggleFavorite = { viewModel.toggleFavorite(song) },
                                onGoToArtist = {
                                    viewModel.setLibraryView(com.beatflowy.app.model.LibraryView.ARTIST_DETAIL, song.artist)
                                    selectedSongForOptions = null
                                },
                                onGoToAlbum = {
                                    viewModel.setLibraryView(com.beatflowy.app.model.LibraryView.ALBUM_DETAIL, song.album)
                                    selectedSongForOptions = null
                                },
                                onGoToFolder = {
                                    viewModel.navigateToFolder(song.folder, song.folder.substringAfterLast("/"))
                                    selectedSongForOptions = null
                                },
                                onGoToGenre = {
                                    viewModel.setLibraryView(com.beatflowy.app.model.LibraryView.GENRE_DETAIL, song.genre)
                                    selectedSongForOptions = null
                                }
                            )
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
                    viewModel.createPlaylist(name)
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

        // Slide drawer
        Box(
            Modifier
                .width(210.dp)
                .fillMaxHeight(1.0f)
                .align(Alignment.TopStart)
                .offset(x = drawerOffsetX)
                .zIndex(9f)
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
                onSetLibraryMode = { mode -> viewModel.setLibraryMode(mode) },
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToDownload = onNavigateToDownload,
                onClose = { showDrawer = false }
            )
        }
    }
}

@Composable
fun CastDevicePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    currentSong: com.beatflowy.app.model.Song?,
    onCast: (androidx.mediarouter.media.MediaRouter.RouteInfo) -> Unit
) {
    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 260.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cast, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Cast to Device", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(0.1f))
            Spacer(Modifier.height(12.dp))

            if (com.beatflowy.app.cast.CastManager.availableDevices.isEmpty()) {
                Text(
                    "Scanning for devices...",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(com.beatflowy.app.cast.CastManager.availableDevices) { route ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCast(route); onDismiss() }
                                .padding(vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Tv, null, tint = AccentBlue)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(route.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Available", color = Color.Green, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (com.beatflowy.app.cast.CastManager.isConnected) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(0.1f))
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Connected to ${com.beatflowy.app.cast.CastManager.connectedDeviceName}",
                        color = Color.Green,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { com.beatflowy.app.cast.CastManager.stopCast(); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.2f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Stop Cast", color = Color.Red, fontSize = 11.sp)
                    }
                }
            }
        }
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
        cardWidth = 220.dp
    ) {
        val maxVal = if (isGrid) 5f else 6f
        val currentVal = if (isGrid) categoryGridColumns.toFloat() else trackLayoutDensity.toFloat()

        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isGrid) "Grid Columns" else "Layout Density",
                color = Color.White.copy(0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
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
                )
            )
            Text(
                text = currentVal.toInt().toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun CloudDrivePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    accounts: List<com.beatflowy.app.repository.DriveAccount>,
    telegramChannels: List<com.beatflowy.app.model.TelegramChannel>,
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
        Text(
            "Cloud Account",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)
        )

        // Option to show ALL cloud songs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectAccount(null); onDismiss() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.CloudQueue, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text("All Cloud Accounts", color = Color.White, fontSize = 14.sp)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp), color = Color.White.copy(0.1f))

        if (enabledAccounts.isEmpty()) {
            Text(
                "No Cloud accounts",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )
        } else {
            enabledAccounts.forEach { account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAccount(account.email); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AccountCircle, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            account.accountName,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            account.email,
                            color = Color.White.copy(0.5f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { 
                            onRefreshAccount(account.email)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (telegramChannels.filter { it.enabled }.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp), 
                              color = Color.White.copy(0.1f))
            
            Text(
                "Telegram",
                color = Color.Gray,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp)
            )
            
            telegramChannels.filter { it.enabled }.forEach { channel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTelegramChannel(channel.url); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(0xFF2AABEE).copy(0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            channel.name.firstOrNull()?.uppercaseChar()?.toString() ?: "",
                            color = Color(0xFF2AABEE),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        channel.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onSyncTelegramChannel(channel.url) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Rounded.Sync, null, tint = Color(0xFF2AABEE), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
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
    onClick: () -> Unit
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
            .clickable(onClick = onClick)
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            AsyncImage(
                model = artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
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
    song: com.beatflowy.app.model.Song,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isCompact: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            if (isCurrent) {
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
    uiState: com.beatflowy.app.model.PlayerUiState
) {
    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 160.dp
    ) {
        Text(
            "Sort & order",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)
        )
        listOf(
            Triple("Name", SortType.NAME, Icons.Rounded.SortByAlpha),
            Triple("Date Added", SortType.DATE_ADDED, Icons.Rounded.CalendarToday),
            Triple("File Size", SortType.FILE_SIZE, Icons.Rounded.Storage),
            Triple("Duration", SortType.DURATION, Icons.Rounded.Schedule)
        ).forEach { (label, type, icon) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setSortType(type); onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (uiState.sortType == type) AccentBlue else Color.White.copy(0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    color = if (uiState.sortType == type) Color.White else Color.White.copy(0.7f),
                    fontSize = 14.sp,
                    fontWeight = if (uiState.sortType == type) FontWeight.Bold else FontWeight.Normal
                )
                if (uiState.sortType == type) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.Check, null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp, horizontal = 12.dp), color = Color.White.copy(0.12f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.toggleSortOrder(); onDismiss() }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (uiState.isAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                null,
                tint = Color.White.copy(0.75f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (uiState.isAscending) "Ascending" else "Descending",
                color = Color.White,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(4.dp))
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
    onNavigateToDownload: () -> Unit,
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
            DrawerMenuItem("All Songs", LibraryView.ALL_SONGS, Icons.Rounded.MusicNote, Color(0xFFFF4081)),
            DrawerMenuItem("Albums", LibraryView.ALBUMS, Icons.Rounded.Album, Color(0xFFB2FF59)),
            DrawerMenuItem("Artists", LibraryView.ARTISTS, Icons.Rounded.Person, Color(0xFF7C4DFF)),
            DrawerMenuItem("Folders", LibraryView.FOLDERS, Icons.Rounded.Folder, Color(0xFFFFAB40)),
            DrawerMenuItem("Years", LibraryView.YEARS, Icons.Rounded.CalendarToday, Color(0xFFFF5252)),
            DrawerMenuItem("Genres", LibraryView.GENRES, Icons.Rounded.GridView, Color(0xFFE040FB)),
            DrawerMenuItem("Playlists", LibraryView.PLAYLISTS, Icons.AutoMirrored.Rounded.PlaylistPlay, Color(0xFFFDD835)),
            DrawerMenuItem("Favorite Songs", LibraryView.FAVORITES, Icons.Rounded.Favorite, Color(0xFFFF5252)),
            DrawerMenuItem("Recently Added", LibraryView.RECENTLY_ADDED, Icons.Rounded.NewReleases, Color(0xFF00E676)),
            DrawerMenuItem("Recently Played", LibraryView.RECENTLY_PLAYED, Icons.Rounded.History, Color(0xFF40C4FF))
        ).filter { item ->
            !(item.view == LibraryView.FOLDERS && libraryMode == LibraryMode.CLOUD)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
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
                            modifier = Modifier.padding(16.dp),
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
                                    alpha = drawerProgress
                                    translationX = -20f * (1f - drawerProgress)
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            LibraryModeSelector(
                            currentMode = libraryMode,
                            onModeSelected = onSetLibraryMode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = drawerProgress
                                    translationX = -10f * (1f - drawerProgress)
                                }
                        )
                    }
                }
            }
        }

        // SECTION 3 — Library items
            LazyColumn(
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.padding(12.dp),
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
                            .clickable { onNavigateToDownload(); onClose() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.FileDownload, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
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
