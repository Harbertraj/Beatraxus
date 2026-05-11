package com.beatflowy.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
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
    onNavigateToDsp: () -> Unit
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
    var showPipelineOverlay by remember { mutableStateOf(false) }
    var showDrawer by rememberSaveable { mutableStateOf(false) }
    var showCastPopup by remember { mutableStateOf(false) }

    var gridColumns by rememberSaveable { mutableIntStateOf(2) }
    var listDensityColumns by rememberSaveable { mutableIntStateOf(2) }

    val isCompactList = listDensityColumns >= 3

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

    val drawerTranslation by animateFloatAsState(
        targetValue = if (showDrawer) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "drawerSlide"
    )

    val contentScale by animateFloatAsState(
        targetValue = if (showDrawer) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "contentScale"
    )

    val contentCornerRadius by animateDpAsState(
        targetValue = if (showDrawer) 28.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "contentCorner"
    )

    Box(Modifier.fillMaxSize()) {
        val needsGraphicsLayer = blurByScan > 0.1f || saturationByScan < 0.99f || alphaByScan < 0.99f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = drawerTranslation * with(density) { 200.dp.toPx() }
                    scaleX = contentScale
                    scaleY = contentScale
                    shape = RoundedCornerShape(contentCornerRadius)
                    clip = true
                }
        ) {
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
            // Background Layer
            MainBackground(
                albumArtUri = uiState.currentSong?.albumArtUri,
                blurEffect = cachedBackgroundBlurEffect
            )

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
                            }

                            // Menu icon on the left
                            IconButton(
                                onClick = { showDrawer = true },
                                modifier = Modifier.align(Alignment.CenterStart).size(48.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Menu,
                                    null,
                                    tint = Color.White.copy(0.8f),
                                    modifier = Modifier.size(32.dp)
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
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(viewAccentColor.copy(alpha = 0.15f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(titleIcon, null, tint = viewAccentColor, modifier = Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = titleText,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
                                        IconButton(onClick = { viewModel.addSelectedToPlaylist("My Playlist") }) {
                                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Color.White)
                                        }
                                        IconButton(onClick = { viewModel.deleteSelectedSongs() }) {
                                            Icon(Icons.Rounded.Delete, null, tint = Color.White)
                                        }
                                        IconButton(onClick = { viewModel.setMultiSelectMode(false) }) {
                                            Icon(Icons.Rounded.Close, null, tint = Color.White)
                                        }
                                    }
                                } else if (!isTitleTouchingSettings) {
                                    IconButton(
                                        onClick = onNavigateToSettings,
                                        modifier = Modifier.onGloballyPositioned {
                                            settingsLeft = it.boundsInRoot().left
                                            val center = it.boundsInRoot().center
                                            viewModel.setSettingsIconPosition(center.x, center.y)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.Settings,
                                            null,
                                            tint = Color.White.copy(0.7f),
                                            modifier = Modifier.size(26.dp)
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
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(28.dp).background(Color.White.copy(0.1f), CircleShape),
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
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            text = "Shuffle All",
                                                            color = Color.White,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
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
                                                    .size(48.dp)
                                                    .background(sortIconBgColor, CircleShape)
                                                    .border(
                                                        width = if (showSortMenu) 1.dp else 0.dp,
                                                        color = if (showSortMenu) Color.White.copy(0.15f) else Color.Transparent,
                                                        shape = CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Rounded.Sort,
                                                    null,
                                                    tint = if (showSortMenu) AccentBlue else Color.White.copy(0.7f),
                                                    modifier = Modifier
                                                        .size(24.dp)
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

                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        val searchIconBgColor by animateColorAsState(
                                            targetValue = if (uiState.isSearchActive) Color.White.copy(0.25f) else Color.Transparent,
                                            animationSpec = tween(500),
                                            label = "searchIconBgColor"
                                        )
                                        IconButton(
                                            onClick = {
                                                val nextActive = !uiState.isSearchActive
                                                viewModel.setSearchActive(nextActive)
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(searchIconBgColor, CircleShape)
                                        ) {
                                            AnimatedContent(
                                                targetState = uiState.isSearchActive,
                                                transitionSpec = {
                                                    (fadeIn(tween(300)) + scaleIn(initialScale = 0.5f)).togetherWith(
                                                        fadeOut(tween(300)) + scaleOut(targetScale = 0.5f)
                                                    )
                                                },
                                                label = "searchIconAnimation"
                                            ) { active ->
                                                Icon(
                                                    if (active) Icons.Rounded.Close else Icons.Rounded.Search,
                                                    null,
                                                    tint = if (active) AccentBlue else Color.White.copy(0.7f),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        val context = androidx.compose.ui.platform.LocalContext.current
                                        IconButton(
                                            onClick = { showCastPopup = true },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Cast,
                                                null,
                                                tint = if (com.beatflowy.app.cast.CastManager.isConnected) AccentBlue else Color.White.copy(0.7f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        CastDevicePopup(
                                            expanded = showCastPopup,
                                            onDismiss = { showCastPopup = false },
                                            currentSong = uiState.currentSong,
                                            onCast = { route ->
                                                uiState.currentSong?.let { song ->
                                                    com.beatflowy.app.cast.CastManager.castSong(context, route, song, song.uri.toString())
                                                }
                                            }
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        val context = androidx.compose.ui.platform.LocalContext.current
                                        IconButton(
                                            onClick = {
                                                android.widget.Toast.makeText(context, "Coming Soon", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(Icons.Rounded.Cloud, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Content Area
                        Box(
                            Modifier
                                .weight(1f)
                                .pinchToZoomColumns(
                                    onZoomIn  = {
                                        val isGrid = uiState.currentView in listOf(
                                            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                            LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS
                                        )
                                        if (isGrid) {
                                            gridColumns = (gridColumns - 1).coerceAtLeast(1)
                                        } else {
                                            listDensityColumns = (listDensityColumns - 1).coerceAtLeast(1)
                                        }
                                    },
                                    onZoomOut = {
                                        val isGrid = uiState.currentView in listOf(
                                            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS,
                                            LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS
                                        )
                                        if (isGrid) {
                                            gridColumns = (gridColumns + 1).coerceAtMost(4)
                                        } else {
                                            listDensityColumns = (listDensityColumns + 1).coerceAtMost(4)
                                        }
                                    }
                                )
                        ) {
                            // Search Overlay
                            androidx.compose.animation.AnimatedVisibility(
                                visible = uiState.isSearchActive && uiState.searchQuery.isNotEmpty(),
                                enter = fadeIn(tween(400)),
                                exit = fadeOut(tween(400)),
                                modifier = Modifier.fillMaxSize().zIndex(5f)
                            ) {
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
                                                        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
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
                                                    item {
                                                        val title = item.first as String
                                                        val artist = item.second as String
                                                        val art = item.third as android.net.Uri?
                                                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                            LibraryGridItem(title, artist, art) {
                                                                viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, title)
                                                            }
                                                        }
                                                    }
                                                }
                                                is Pair<*, *> -> {
                                                    item {
                                                        val name = item.first as String
                                                        val art = item.second as android.net.Uri?
                                                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                            }

                            // Normal Content
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !uiState.isSearchActive || uiState.searchQuery.isEmpty(),
                                enter = fadeIn(tween(400)),
                                exit = fadeOut(tween(400))
                            ) {
                                when (uiState.currentView) {
                                    LibraryView.ALBUMS -> {
                                        Box(Modifier.fillMaxSize()) {
                                            LazyVerticalGrid(
                                                state = gridState,
                                                columns = GridCells.Fixed(gridColumns.coerceIn(1, 4)),
                                                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                horizontalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp),
                                                verticalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp)
                                            ) {
                                                items(albums, key = { it.first + it.second }) { album ->
                                                    LibraryGridItem(album.first, album.second, album.third) {
                                                        viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, album.first)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    LibraryView.ARTISTS -> {
                                        Box(Modifier.fillMaxSize()) {
                                            LazyVerticalGrid(
                                                state = gridState,
                                                columns = GridCells.Fixed(gridColumns.coerceIn(1, 4)),
                                                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                horizontalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp),
                                                verticalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp)
                                            ) {
                                                items(artists, key = { it.first }) { artist ->
                                                    LibraryGridItem(artist.first, artist.second, artist.third) {
                                                        viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, artist.first)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    LibraryView.FOLDERS -> {
                                        LazyVerticalGrid(
                                            state = gridState,
                                            columns = GridCells.Fixed(gridColumns.coerceIn(1, 4)),
                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                            horizontalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp)
                                        ) {
                                            items(folders, key = { it.first }) { folder ->
                                                LibraryGridItem(folder.second, folder.first, folder.third) {
                                                    viewModel.navigateToFolder(folder.first, folder.second)
                                                }
                                            }
                                        }
                                    }
                                    // FIX 5: FOLDER_DETAIL branch properly closed
                                    LibraryView.FOLDER_DETAIL -> {
                                        val folderSongs = songs
                                        Box(Modifier.fillMaxSize()) {
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
                                                        isCompact = isCompactList
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    LibraryView.YEARS -> {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(gridColumns.coerceIn(1, 4)),
                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                            horizontalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp)
                                        ) {
                                            items(years, key = { it.first }) { year ->
                                                LibraryGridItem(year.first, year.second, year.third) {
                                                    viewModel.setLibraryView(LibraryView.YEAR_DETAIL, year.first)
                                                }
                                            }
                                        }
                                    }
                                    LibraryView.GENRES -> {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(gridColumns.coerceIn(1, 4)),
                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                            horizontalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp)
                                        ) {
                                            items(genres, key = { it.first }) { genre ->
                                                GenreGridItem(genre.first, genre.second) {
                                                    viewModel.setLibraryView(LibraryView.GENRE_DETAIL, genre.first)
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
                                                        onClick = { viewModel.createPlaylist("My Playlist") },
                                                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                                                    ) {
                                                        Text("Create Playlist", color = Color.Black)
                                                    }
                                                }
                                            }
                                        } else {
                                            LazyVerticalGrid(
                                                columns = GridCells.Fixed(gridColumns.coerceIn(1, 4)),
                                                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                                horizontalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp),
                                                verticalArrangement = Arrangement.spacedBy(if (gridColumns >= 3) 8.dp else 16.dp)
                                            ) {
                                                items(playlists, key = { it.name }) { playlist ->
                                                    LibraryGridItem(playlist.name, "${playlist.songIds.size} songs", null) {
                                                        viewModel.setLibraryView(LibraryView.PLAYLIST_DETAIL, playlist.name)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    LibraryView.PLAYLIST_DETAIL -> {
                                        val playlistName = uiState.selectedItemName
                                        val playlist = playlists.find { it.name == playlistName }
                                        val playlistSongs = songs.filter { playlist?.songIds?.contains(it.id) == true }
                                        Box(Modifier.fillMaxSize()) {
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
                                                        isCompact = isCompactList
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    LibraryView.FAVORITES -> {
                                        val favSongs = songs.filter { favorites.contains(it.id) }
                                        Box(Modifier.fillMaxSize()) {
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
                                                        isCompact = isCompactList
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        // Library list with alphabet scroller
                                        Box(Modifier.fillMaxSize()) {
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
                                                        isCompact = isCompactList
                                                    )
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
                                                            listState.scrollToItem(targetIndex)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } // end Column

                    // FIX 3: Mini player is now a sibling of Column inside Box — has BoxScope for .align()
                    AnimatedVisibility(
                        visible = uiState.currentSong != null && !showFullPlayer,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = paddingValues.calculateBottomPadding() + 12.dp)
                            .padding(horizontal = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .shadow(16.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showFullPlayer = true }
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
        } // end blurred Box
        }

        if (showDrawer) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * drawerTranslation))
                    .clickable { showDrawer = false }
                    .zIndex(8f)
            )
        }

        // Slide drawer
        val drawerOffsetX by animateDpAsState(
            targetValue = if (showDrawer) 0.dp else (-200).dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "drawerOffset"
        )

        Box(
            Modifier
                .width(200.dp)
                .fillMaxHeight()
                .offset(x = drawerOffsetX)
                .shadow(elevation = if (showDrawer) 24.dp else 0.dp)
                .zIndex(9f)
                .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
        ) {
            SlideDrawerMenu(
                currentView = uiState.currentView,
                accentColor = viewAccentColor,
                onSelectView = { view ->
                    viewModel.setLibraryView(view)
                },
                onNavigateToSettings = onNavigateToSettings,
                onClose = { showDrawer = false }
            )
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
                showPipelineOverlay = showPipelineOverlay,
                onTogglePipeline = { showPipelineOverlay = it },
                onSetSleepTimer = { seconds, finishTrack, playCount ->
                    viewModel.setSleepTimer(seconds, finishTrack, playCount)
                },
                onStopSleepTimer = { viewModel.stopSleepTimer() }
            )
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

@Composable
private fun CastDevicePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentSong: com.beatflowy.app.model.Song?,
    onCast: (androidx.mediarouter.media.MediaRouter.RouteInfo) -> Unit
) {
    if (expanded) {
        androidx.compose.ui.window.Popup(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
        ) {
            Box(
                modifier = Modifier
                    .width(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C2E))
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
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
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
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
private fun MiniPlayerTimeText(progressProvider: () -> Long, duration: Long) {
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
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
        Spacer(Modifier.height(8.dp))
        Text(
            song.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            song.artist,
            color = Color.White.copy(0.6f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            "Name" to SortType.NAME,
            "Date Added" to SortType.DATE_ADDED,
            "File Size" to SortType.FILE_SIZE,
            "Duration" to SortType.DURATION
        ).forEach { (label, type) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setSortType(type); onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.sortType == type) {
                    Icon(Icons.Rounded.Check, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                } else {
                    Spacer(Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(label, color = Color.White, fontSize = 14.sp)
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

private data class DrawerMenuItem(
    val label: String,
    val view: LibraryView,
    val icon: ImageVector,
    val color: Color
)

@Composable
private fun SlideDrawerMenu(
    currentView: LibraryView,
    accentColor: Color,
    onSelectView: (LibraryView) -> Unit,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit
) {
    val menuItems = listOf(
        DrawerMenuItem("All Songs", LibraryView.ALL_SONGS, Icons.Rounded.MusicNote, Color(0xFFFF4081)),
        DrawerMenuItem("Albums", LibraryView.ALBUMS, Icons.Rounded.Album, Color(0xFFB2FF59)),
        DrawerMenuItem("Artists", LibraryView.ARTISTS, Icons.Rounded.Person, Color(0xFF7C4DFF)),
        DrawerMenuItem("Folders", LibraryView.FOLDERS, Icons.Rounded.Folder, Color(0xFFFFAB40)),
        DrawerMenuItem("Years", LibraryView.YEARS, Icons.Rounded.CalendarMonth, Color(0xFFFF5252)),
        DrawerMenuItem("Genres", LibraryView.GENRES, Icons.Rounded.GridView, Color(0xFFE040FB)),
        DrawerMenuItem("Playlists", LibraryView.PLAYLISTS, Icons.AutoMirrored.Rounded.PlaylistPlay, Color(0xFFFDD835)),
        DrawerMenuItem("Favorite Songs", LibraryView.FAVORITES, Icons.Rounded.Favorite, Color(0xFFFF4081)),
        DrawerMenuItem("Recently Played", LibraryView.RECENTLY_PLAYED, Icons.Rounded.History, Color(0xFF40C4FF)),
        DrawerMenuItem("Recently Added", LibraryView.RECENTLY_ADDED, Icons.Rounded.NewReleases, Color(0xFF00E676))
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(200.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black,
                        accentColor.copy(alpha = 0.35f).compositeOver(Color.Black)
                    )
                )
            )
            .drawBehind {
                val strokeWidth = 0.5.dp.toPx()
                drawLine(
                    color = Color.White.copy(0.08f),
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )
            }
    ) {
        // SECTION 1 — Header
        Column(
            modifier = Modifier
                .padding(top = 52.dp, start = 20.dp, end = 20.dp, bottom = 16.dp)
        ) {
            Icon(
                Icons.Rounded.Headphones,
                null,
                modifier = Modifier.size(36.dp),
                tint = accentColor
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Hello,",
                color = Color.White.copy(0.5f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Audiophile",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accentColor.copy(0.6f), Color.Transparent)
                        )
                    )
            )
        }

        // SECTION 2 — Library label
        Text(
            "LIBRARY",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = Color.White.copy(0.3f)
        )

        // SECTION 3 — Library items
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(menuItems) { item ->
                val isSelected = currentView == item.view ||
                    (item.view == LibraryView.ALBUMS && currentView == LibraryView.ALBUM_DETAIL) ||
                    (item.view == LibraryView.ARTISTS && currentView == LibraryView.ARTIST_DETAIL) ||
                    (item.view == LibraryView.FOLDERS && currentView == LibraryView.FOLDER_DETAIL) ||
                    (item.view == LibraryView.YEARS && currentView == LibraryView.YEAR_DETAIL) ||
                    (item.view == LibraryView.GENRES && currentView == LibraryView.GENRE_DETAIL) ||
                    (item.view == LibraryView.PLAYLISTS && currentView == LibraryView.PLAYLIST_DETAIL)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectView(item.view); onClose() }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(
                            if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                item.color.copy(alpha = if (isSelected) 0.25f else 0.1f),
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSelected) item.color else item.color.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        item.label,
                        color = if (isSelected) Color.White else Color.White.copy(0.7f),
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                    )
                }
            }
        }

        // SECTION 4 — Bottom
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 8.dp),
                color = Color.White.copy(0.06f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToSettings(); onClose() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White.copy(0.5f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Settings",
                    color = Color.White.copy(0.6f),
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun Modifier.pinchToZoomColumns(onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier =
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
                        if (ratio > 1.30f) {
                            onZoomIn()
                            fired = true
                        } else if (ratio < 0.75f) {
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
