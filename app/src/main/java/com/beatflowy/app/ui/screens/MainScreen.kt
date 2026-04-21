package com.beatflowy.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.SortType
import com.beatflowy.app.ui.components.*
import com.beatflowy.app.ui.theme.*
import com.beatflowy.app.viewmodel.PlayerViewModel

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@Composable
fun LazyGridState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val songs   by viewModel.songs.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val isListScrollingUp = listState.isScrollingUp()
    val isGridScrollingUp = gridState.isScrollingUp()
    val isScrollingUp = if (uiState.currentView in listOf(LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS, LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS)) {
        isGridScrollingUp
    } else {
        isListScrollingUp
    }
    /** Scroll up hides the search field while staying in search mode; tap strip or scroll down to show again. */
    var keepSearchFieldPinned by remember { mutableStateOf(false) }
    LaunchedEffect(isScrollingUp) {
        if (isScrollingUp) keepSearchFieldPinned = false
    }
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) keepSearchFieldPinned = true
        else keepSearchFieldPinned = false
    }
    val searchFieldRowVisible = uiState.isSearchActive && (!isScrollingUp || keepSearchFieldPinned)

    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchFieldRowVisible, uiState.isSearchActive) {
        if (searchFieldRowVisible && uiState.isSearchActive) {
            kotlinx.coroutines.delay(60)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    val scope = rememberCoroutineScope()
    
    var showFullPlayer by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showSortMenu   by remember { mutableStateOf(false) }
    var showLibraryPopup by remember { mutableStateOf(false) }
    var sortMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var libraryMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var selectedSongForOptions by remember { mutableStateOf<com.beatflowy.app.model.Song?>(null) }

    // Logic to open full player if returning from Equalizer or if it was already open
    LaunchedEffect(uiState.showFullPlayer) {
        if (uiState.showFullPlayer) {
            showFullPlayer = true
            viewModel.setShowFullPlayer(false) // Consume the state
        }
    }

    val isDetailView = uiState.currentView in listOf(
        LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL,
        LibraryView.FOLDER_DETAIL, LibraryView.YEAR_DETAIL, LibraryView.GENRE_DETAIL,
        LibraryView.PLAYLIST_DETAIL
    )

    BackHandler(enabled = showFullPlayer || showEqualizer || uiState.isSearchActive || isDetailView || uiState.currentView != LibraryView.ALL_SONGS || showLibraryPopup || showSortMenu) {
        if (showLibraryPopup) {
            showLibraryPopup = false
        } else if (showSortMenu) {
            showSortMenu = false
        } else if (showFullPlayer) {
            if (uiState.showQueue) {
                viewModel.toggleQueue()
            } else if (uiState.showLyrics) {
                viewModel.toggleLyrics()
            } else {
                showFullPlayer = false
            }
        } else if (showEqualizer) {
            showEqualizer = false
        } else if (uiState.isSearchActive) {
            viewModel.setSearchActive(false)
        } else if (isDetailView) {
            val backView = when(uiState.currentView) {
                LibraryView.ALBUM_DETAIL -> LibraryView.ALBUMS
                LibraryView.ARTIST_DETAIL -> LibraryView.ARTISTS
                LibraryView.FOLDER_DETAIL -> LibraryView.FOLDERS
                LibraryView.YEAR_DETAIL -> LibraryView.YEARS
                LibraryView.GENRE_DETAIL -> LibraryView.GENRES
                LibraryView.PLAYLIST_DETAIL -> LibraryView.PLAYLISTS
                else -> LibraryView.ALL_SONGS
            }
            viewModel.setLibraryView(backView)
        } else if (uiState.currentView != LibraryView.ALL_SONGS) {
            viewModel.setLibraryView(LibraryView.ALL_SONGS)
        }
    }

    val blurByScan by animateFloatAsState(
        targetValue = if (uiState.isScanning) 15f else 0f,
        animationSpec = tween(500),
        label = "blur"
    )
    val saturationByScan by animateFloatAsState(
        targetValue = if (uiState.isScanning) 0f else 1f,
        animationSpec = tween(500),
        label = "saturation"
    )
    val alphaByScan by animateFloatAsState(
        targetValue = if (uiState.isScanning) 0.8f else 1f,
        animationSpec = tween(500),
        label = "alpha"
    )

    Box(Modifier.fillMaxSize()) {
        // Main content with blur and grayscale effects
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val blurEffect = if (blurByScan > 0.1f) {
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
        ) {
            // Background Image (Blurred Album Art)
            if (uiState.currentSong != null) {
                AsyncImage(
                    model = uiState.currentSong?.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(60.dp)
                        .graphicsLayer(alpha = 0.4f),
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
            } else {
                Box(Modifier.fillMaxSize().background(BgDeep))
            }

            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    // topBar removed to move title down
                }
            ) { paddingValues ->
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = paddingValues.calculateTopPadding(),
                                bottom = 0.dp
                            )
                    ) {
                        // New Integrated Header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            // Center Content: Title and Greeting
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Sub Title (Center)
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
                                Surface(
                                    color = viewAccentColor.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, viewAccentColor.copy(alpha = 0.5f)),
                                    modifier = Modifier.animateContentSize()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(viewAccentColor.copy(alpha = 0.2f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(titleIcon, null, tint = viewAccentColor, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = titleText,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 20.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                            }

                            // Top Right Icons (Settings / Multi-select)
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd)
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
                                } else {
                                    IconButton(onClick = onNavigateToSettings) {
                                        Icon(
                                            Icons.Rounded.Settings,
                                            null,
                                            tint = Color.White.copy(0.7f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Search field: shown when search mode is on; scroll up collapses (keyboard hides); scroll down or tap strip restores.
                        AnimatedVisibility(
                            visible = searchFieldRowVisible,
                            enter = expandVertically(animationSpec = tween(500)) + fadeIn(tween(500)),
                            exit = shrinkVertically(animationSpec = tween(500)) + fadeOut(tween(500)),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(Color.White.copy(0.1f))
                                    .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(26.dp)),
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
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.setSearchQuery("") }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.isSearchActive && !searchFieldRowVisible) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(0.08f))
                                    .clickable {
                                        keepSearchFieldPinned = true
                                        searchFocusRequester.requestFocus()
                                        keyboardController?.show()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Tap to search…", color = Color.White.copy(0.55f), fontSize = 14.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Action Icons Row (Play All + Action Icons)
                        AnimatedVisibility(
                            visible = isScrollingUp || uiState.isSearchActive,
                            enter = expandVertically(animationSpec = tween(500)) + fadeIn(tween(500)),
                            exit = shrinkVertically(animationSpec = tween(500)) + fadeOut(tween(500))
                        ) {
                            val canShufflePlay = when (uiState.currentView) {
                                LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS, 
                                LibraryView.YEARS, LibraryView.GENRES, LibraryView.PLAYLISTS -> false
                                else -> true
                            }

                            val playAllWeight by animateFloatAsState(
                                targetValue = if (canShufflePlay) 3.5f else 0.001f,
                                animationSpec = tween(700, easing = FastOutSlowInEasing),
                                label = "playAllWeight"
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (canShufflePlay) {
                                    Box(
                                        modifier = Modifier
                                            .weight(playAllWeight)
                                            .graphicsLayer {
                                                alpha = (playAllWeight / 3.5f).coerceIn(0f, 1f)
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
                                                modifier = Modifier
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(32.dp).background(Color.White.copy(0.1f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                                }
                                                AnimatedVisibility(
                                                    visible = playAllWeight > 1.8f,
                                                    enter = fadeIn(tween(400)) + expandHorizontally(tween(400)),
                                                    exit = fadeOut(tween(400)) + shrinkHorizontally(tween(400))
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            text = "Shuffle All",
                                                            color = Color.White,
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { sortMenuAnchor = it.boundsInRoot() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box {
                                        IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(44.dp)) {
                                            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(24.dp))
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
                                            viewModel.setSearchActive(!uiState.isSearchActive)
                                            if (!uiState.isSearchActive) {
                                                // When opening search, we might want to clear previous query
                                                // viewModel.setSearchQuery("") 
                                            }
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
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

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { libraryMenuAnchor = it.boundsInRoot() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val libraryIconBgColor by animateColorAsState(
                                        targetValue = if (showLibraryPopup) Color.White.copy(0.2f) else Color.Transparent,
                                        animationSpec = tween(400),
                                        label = "libraryIconBgColor"
                                    )
                                    Box(contentAlignment = Alignment.Center) {
                                        IconButton(
                                            onClick = { showLibraryPopup = true },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(libraryIconBgColor, CircleShape)
                                                .border(
                                                    width = if (showLibraryPopup) 1.dp else 0.dp,
                                                    color = if (showLibraryPopup) Color.White.copy(0.15f) else Color.Transparent,
                                                    shape = CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Rounded.LibraryMusic,
                                                null,
                                                tint = if (showLibraryPopup) AccentBlue else Color.White.copy(0.7f),
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                        
                                        // The LibraryViewDropdown now handles its own arrow to ensure layering
                                        LibraryViewDropdown(
                                            expanded = showLibraryPopup,
                                            onDismiss = { showLibraryPopup = false },
                                            anchorBounds = libraryMenuAnchor,
                                            viewModel = viewModel,
                                            currentView = uiState.currentView
                                        )
                                    }
                                }

                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = { showEqualizer = true }, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }

                        // Content Area
                        Box(Modifier.weight(1f)) {
                            // Search Overlay (if active)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = uiState.isSearchActive,
                                enter = fadeIn(tween(400)),
                                exit = fadeOut(tween(400)),
                                modifier = Modifier.fillMaxSize().zIndex(5f)
                            ) {
                                val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.98f))) {
                                    if (uiState.searchQuery.isEmpty()) {
                                        // Browse all genres
                                        val genres by viewModel.genres.collectAsStateWithLifecycle()
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(genres) { genre ->
                                                GenreGridItem(genre.first, genre.second) {
                                                    viewModel.setSearchQuery(genre.first)
                                                }
                                            }
                                        }
                                    } else {
                                        // Search Results with Categories
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(bottom = 120.dp)
                                        ) {
                                            items(searchResults) { item ->
                                                when (item) {
                                                    is String -> {
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
                                                    is com.beatflowy.app.model.Song -> {
                                                        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                                                            SongListItem(
                                                                song = item,
                                                                trackNumber = 0,
                                                                isPlaying = uiState.currentSong?.id == item.id,
                                                                onClick = { 
                                                                    viewModel.playSong(item)
                                                                    viewModel.setSearchActive(false)
                                                                },
                                                                onMoreClick = { selectedSongForOptions = item }
                                                            )
                                                        }
                                                    }
                                                    is Triple<*, *, *> -> { // Album
                                                        val title = item.first as String
                                                        val artist = item.second as String
                                                        val art = item.third as android.net.Uri?
                                                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                            LibraryGridItem(title, artist, art) {
                                                                viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, title)
                                                                viewModel.setSearchActive(false)
                                                            }
                                                        }
                                                    }
                                                    is Pair<*, *> -> { // Artist
                                                        val name = item.first as String
                                                        val art = item.second as android.net.Uri?
                                                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                            LibraryGridItem(name, "Artist", art) {
                                                                viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, name)
                                                                viewModel.setSearchActive(false)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Normal Content (wrapped in Visibility to avoid overlapping)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !uiState.isSearchActive,
                                enter = fadeIn(tween(400)),
                                exit = fadeOut(tween(400))
                            ) {
                                when (uiState.currentView) {
                            LibraryView.ALBUMS -> {
                                val albums by viewModel.albums.collectAsStateWithLifecycle()
                                Box(Modifier.fillMaxSize()) {
                                    LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Fixed(2),
                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(albums) { album ->
                                            LibraryGridItem(album.first, album.second, album.third) {
                                                viewModel.setLibraryView(LibraryView.ALBUM_DETAIL, album.first)
                                            }
                                        }
                                    }
                                }
                            }
                            LibraryView.ARTISTS -> {
                                val artists by viewModel.artists.collectAsStateWithLifecycle()
                                Box(Modifier.fillMaxSize()) {
                                    LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Fixed(2),
                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(artists) { artist ->
                                            LibraryGridItem(artist.first, artist.second, artist.third) {
                                                viewModel.setLibraryView(LibraryView.ARTIST_DETAIL, artist.first)
                                            }
                                        }
                                    }
                                }
                            }
                            LibraryView.FOLDERS -> {
                                val folders by viewModel.folders.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(folders) { folder ->
                                        LibraryGridItem(folder.second, folder.first, folder.third) {
                                            viewModel.navigateToFolder(folder.first, folder.second)
                                        }
                                    }
                                }
                            }
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
                                                onMoreClick = { selectedSongForOptions = song }
                                            )
                                        }
                                    }
                                }
                            }
                            LibraryView.YEARS -> {
                                val years by viewModel.years.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(years) { year ->
                                        LibraryGridItem(year.first, year.second, year.third) {
                                            viewModel.setLibraryView(LibraryView.YEAR_DETAIL, year.first)
                                        }
                                    }
                                }
                            }
                            LibraryView.GENRES -> {
                                val genres by viewModel.genres.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(genres) { genre ->
                                        GenreGridItem(genre.first, genre.second) {
                                            viewModel.setLibraryView(LibraryView.GENRE_DETAIL, genre.first)
                                        }
                                    }
                                }
                            }
                            LibraryView.PLAYLISTS -> {
                                val playlists by viewModel.playlists.collectAsStateWithLifecycle()
                                if (playlists.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, modifier = Modifier.size(64.dp), tint = TextMuted)
                                            Spacer(Modifier.height(16.dp))
                                            Text("No playlists yet", color = TextMuted, fontSize = 18.sp)
                                            Spacer(Modifier.height(8.dp))
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
                                        columns = GridCells.Fixed(2),
                                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(playlists) { playlist ->
                                            LibraryGridItem(playlist.name, "${playlist.songIds.size} songs", null) {
                                                viewModel.setLibraryView(LibraryView.PLAYLIST_DETAIL, playlist.name)
                                            }
                                        }
                                    }
                                }
                            }
                            LibraryView.PLAYLIST_DETAIL -> {
                                val playlistName = uiState.selectedItemName
                                val playlist = viewModel.playlists.collectAsStateWithLifecycle().value.find { it.name == playlistName }
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
                                                onMoreClick = { selectedSongForOptions = song }
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
                                                onMoreClick = { selectedSongForOptions = song }
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
                                                onMoreClick = { selectedSongForOptions = song }
                                            )
                                        }
                                    }

                                    // Alphabet Fast Scroller
                                    if (uiState.currentView == LibraryView.ALL_SONGS) {
                                        AlphabetScroller(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 4.dp, top = 20.dp, bottom = 120.dp),
                                            items = songs.map { it.title },
                                            onScrollTo = { targetIndex ->
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

                // Mini player overlay (Anchored to bottom, floating)
                AnimatedVisibility(
                    visible = uiState.currentSong != null && !uiState.isScanning && !showFullPlayer,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = paddingValues.calculateBottomPadding() + 12.dp)
                        .padding(horizontal = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp) // Sleek Windows 11 style height
                            .shadow(16.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { showFullPlayer = true }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { _, dragAmount ->
                                    if (dragAmount > 50) viewModel.skipToPrevious()
                                    else if (dragAmount < -50) viewModel.skipToNext()
                                }
                            }
                    ) {
                        // Glass Background Layer (Windows 11 Mica/Acrylic style)
                        Box(Modifier.fillMaxSize()) {
                            // Opaque base layer to prevent underlying content bleed
                            Box(Modifier.fillMaxSize().background(Color(0xFF121212)))
                            
                            // Sub-layer for internal blur to simulate glass
                            AsyncImage(
                                model = uiState.currentSong?.albumArtUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(70.dp), // Deep Windows 11 blur
                                contentScale = ContentScale.Crop,
                                alpha = 1f // Full opacity for "Full Blur" (opaque)
                            )
                            // Tint and Gloss
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

                        // Pinned Progress Bar at the top edge
                        val progress = if (uiState.currentSong?.durationMs ?: 0 > 0) {
                            uiState.progressMs.toFloat() / (uiState.currentSong?.durationMs ?: 1).toFloat()
                        } else 0f

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(Color.White.copy(0.1f))
                                .align(Alignment.TopCenter)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Album Art
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(0.05f)
                            ) {
                                AsyncImage(
                                    model = uiState.currentSong?.albumArtUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            // Info
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = uiState.currentSong?.title ?: "Unknown",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = uiState.currentSong?.artist ?: "Unknown Artist",
                                        color = Color.White.copy(0.7f),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val currentProgress = formatTime(uiState.progressMs)
                                    val totalDuration = formatTime(uiState.currentSong?.durationMs ?: 0)
                                    Text(
                                        text = "($currentProgress/$totalDuration)",
                                        color = Color.White.copy(0.7f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Controls
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
            }
        }
    } // End of Blurred Box

    // Scan Overlay - Placed outside the blurred container
        if (uiState.isScanning) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.7f))
                    .pointerInput(Unit) {}, // Consume touches during scan
                contentAlignment = Alignment.Center
            ) {
                // Glassy scanning dialog
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0x991E1E22))
                        .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(32.dp))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Scanning Library...",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { uiState.scanProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = AccentBlue,
                            trackColor = Color.White.copy(0.1f)
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "${(uiState.scanProgress * 100).toInt()}%",
                            color = Color.White.copy(0.7f),
                            fontSize = 16.sp
                        )
                    }
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
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            ) + fadeOut(tween(400))
        ) {
            NowPlayingScreen(
                song = uiState.currentSong,
                isPlaying = uiState.isPlaying,
                progressMs = uiState.progressMs,
                durationMs = uiState.currentSong?.durationMs ?: 0L,
                shuffleMode = uiState.shuffleMode,
                repeatMode = uiState.repeatMode,
                showLyrics = uiState.showLyrics,
                lyrics = lyrics,
                uiState = uiState,
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.skipToNext() },
                onPrevious = { viewModel.skipToPrevious() },
                onShuffle = { viewModel.toggleShuffle() },
                onRepeat = { viewModel.toggleRepeat() },
                onSeek = { viewModel.seekTo(it) },
                onClose = { showFullPlayer = false },
                onOpenEqualizer = { showEqualizer = true },
                onToggleLyrics = { viewModel.toggleLyrics() },
                onToggleQueue = { viewModel.toggleQueue() },
                onRemoveFromQueue = { viewModel.removeFromQueue(it) },
                onMoveInQueue = { from, to -> viewModel.moveInQueue(from, to) },
                onPlayFromQueue = { viewModel.playFromQueue(it) },
                upcomingSongs = uiState.upcomingSongs,
                isFavorite = uiState.currentSong?.let { favorites.contains(it.id) } ?: false,
                onFavoriteClick = { uiState.currentSong?.let { viewModel.toggleFavorite(it) } }
            )
        }

        AnimatedVisibility(
            visible = showEqualizer,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
        ) {
            EqualizerScreen(
                viewModel = viewModel,
                onBack = { showEqualizer = false }
            )
        }

        selectedSongForOptions?.let { song ->
            SongOptionsSheet(
                song = song,
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
                    // This typically opens another dialog or sub-menu
                    selectedSongForOptions = null
                },
                onEditTags = { 
                    selectedSongForOptions = null
                },
                onSetAsRingtone = { 
                    selectedSongForOptions = null
                },
                onShare = { 
                    selectedSongForOptions = null
                },
                onDelete = { 
                    viewModel.deleteSong(song)
                    selectedSongForOptions = null
                },
                isFavorite = favorites.contains(song.id),
                onToggleFavorite = { viewModel.toggleFavorite(song) }
            )
        }
    }
}
}

@Composable
fun AlphabetScroller(
    modifier: Modifier = Modifier,
    items: List<String>,
    onScrollTo: (Int) -> Unit
) {
    val alphabet = listOf('↑', '0') + ('A'..'Z').toList() + '#'
    var dragOffset by remember { mutableStateOf<Float?>(null) }
    var currentLetter by remember { mutableStateOf<Char?>(null) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> dragOffset = offset.y },
                    onDragEnd = { dragOffset = null; currentLetter = null },
                    onDragCancel = { dragOffset = null; currentLetter = null },
                    onVerticalDrag = { change, _ ->
                        dragOffset = change.position.y
                    }
                )
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

        // Bubble Preview
        BoxWithConstraints {
            val currentMaxHeight = maxHeight
            dragOffset?.let { y ->
                val currentMaxHeightPx = with(LocalDensity.current) { currentMaxHeight.toPx() }
                
                // Calculate position relative to the Column's actual height to avoid offset issues
                val index = (y / (currentMaxHeightPx / alphabet.size)).toInt().coerceIn(0, alphabet.size - 1)
                val letter = alphabet[index]
                currentLetter = letter

                // Adjust scrolling logic for special symbols
                val targetIndex = when(letter) {
                    '↑' -> 0
                    '0' -> items.indexOfFirst { it.firstOrNull()?.isDigit() == true }
                    '#' -> items.indexOfFirst { it.firstOrNull()?.let { c -> !c.isLetterOrDigit() && !c.isWhitespace() } == true }
                    else -> items.indexOfFirst { it.uppercase().startsWith(letter) }
                }
                
                if (targetIndex != -1) {
                    onScrollTo(targetIndex)
                }

                // Visual Bubble
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x = (-60).dp.roundToPx(), y = y.toInt() - 25.dp.roundToPx()) }
                        .size(50.dp)
                        .clip(SquircleShape(0.2f))
                        .background(Color.Black.copy(0.8f))
                        .border(1.dp, Color.White.copy(0.2f), SquircleShape(0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        letter.toString(),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(16.dp),
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
        cardWidth = 172.dp,
        showDirectionalArrow = true
    ) {
        Text(
            "Sort & order",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
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
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.sortType == type) {
                    Icon(Icons.Rounded.Check, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
                } else {
                    Spacer(Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(label, color = Color.White, fontSize = 15.sp)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp), color = Color.White.copy(0.12f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.toggleSortOrder(); onDismiss() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (uiState.isAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                null,
                tint = Color.White.copy(0.75f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (uiState.isAscending) "Ascending" else "Descending",
                color = Color.White,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun LibraryViewDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect,
    viewModel: PlayerViewModel,
    currentView: LibraryView
) {
    val menuItems = listOf(
        Triple("All Songs", LibraryView.ALL_SONGS, Icons.Rounded.MusicNote to Color(0xFFFF4081)),
        Triple("Albums", LibraryView.ALBUMS, Icons.Rounded.Album to Color(0xFFB2FF59)),
        Triple("Artists", LibraryView.ARTISTS, Icons.Rounded.Person to Color(0xFF7C4DFF)),
        Triple("Folders", LibraryView.FOLDERS, Icons.Rounded.Folder to Color(0xFFFFAB40)),
        Triple("Years", LibraryView.YEARS, Icons.Rounded.CalendarMonth to Color(0xFFFF5252)),
        Triple("Genres", LibraryView.GENRES, Icons.Rounded.GridView to Color(0xFFE040FB)),
        Triple("Playlists", LibraryView.PLAYLISTS, Icons.AutoMirrored.Rounded.PlaylistPlay to Color(0xFFFDD835)),
        Triple("Favorite songs", LibraryView.FAVORITES, Icons.Rounded.Favorite to Color(0xFFFF4081)),
        Triple("Recently played", LibraryView.RECENTLY_PLAYED, Icons.Rounded.History to Color(0xFF40C4FF)),
        Triple("Recently added", LibraryView.RECENTLY_ADDED, Icons.Rounded.NewReleases to Color(0xFF00E676))
    )

    GlassMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        anchorBounds = anchorBounds,
        cardWidth = 188.dp,
        showDirectionalArrow = true
    ) {
        Text(
            "Library",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
        )
        menuItems.forEach { (label, view, iconPair) ->
            val (icon, color) = iconPair
            val isSelected = currentView == view ||
                (view == LibraryView.ALBUMS && currentView == LibraryView.ALBUM_DETAIL) ||
                (view == LibraryView.ARTISTS && currentView == LibraryView.ARTIST_DETAIL) ||
                (view == LibraryView.FOLDERS && currentView == LibraryView.FOLDER_DETAIL) ||
                (view == LibraryView.YEARS && currentView == LibraryView.YEAR_DETAIL) ||
                (view == LibraryView.GENRES && currentView == LibraryView.GENRE_DETAIL) ||
                (view == LibraryView.PLAYLISTS && currentView == LibraryView.PLAYLIST_DETAIL)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.setLibraryView(view)
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) color.copy(0.15f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(color.copy(0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        color = if (isSelected) Color.White else Color.White.copy(0.88f),
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    if (isSelected) {
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.Check, null, tint = color, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

