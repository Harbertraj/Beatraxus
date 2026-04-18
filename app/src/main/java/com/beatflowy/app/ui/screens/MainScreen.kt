package com.beatflowy.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.SortType
import com.beatflowy.app.ui.components.*
import com.beatflowy.app.ui.theme.*
import com.beatflowy.app.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onNavigateToEqualizer: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val songs   by viewModel.songs.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    
    var showFullPlayer by remember { mutableStateOf(false) }
    var showSortMenu   by remember { mutableStateOf(false) }
    var showLibraryPopup by remember { mutableStateOf(false) }

    // Logic to open full player if returning from Equalizer or if it was already open
    LaunchedEffect(uiState.showFullPlayer) {
        if (uiState.showFullPlayer) {
            showFullPlayer = true
            viewModel.setShowFullPlayer(false) // Consume the state
        }
    }

    val isDetailView = uiState.currentView in listOf(
        LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL,
        LibraryView.FOLDER_DETAIL, LibraryView.YEAR_DETAIL, LibraryView.GENRE_DETAIL
    )

    BackHandler(enabled = showFullPlayer || uiState.isSearchActive || isDetailView || uiState.currentView != LibraryView.ALL_SONGS) {
        if (showFullPlayer) {
            showFullPlayer = false
        } else if (uiState.isSearchActive) {
            viewModel.setSearchActive(false)
        } else if (isDetailView) {
            val backView = when(uiState.currentView) {
                LibraryView.ALBUM_DETAIL -> LibraryView.ALBUMS
                LibraryView.ARTIST_DETAIL -> LibraryView.ARTISTS
                LibraryView.FOLDER_DETAIL -> LibraryView.FOLDERS
                LibraryView.YEAR_DETAIL -> LibraryView.YEARS
                LibraryView.GENRE_DETAIL -> LibraryView.GENRES
                else -> LibraryView.ALL_SONGS
            }
            viewModel.setLibraryView(backView)
        } else if (uiState.currentView != LibraryView.ALL_SONGS) {
            viewModel.setLibraryView(LibraryView.ALL_SONGS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Background Image (Blurred Album Art) - Moved to root for full bleed
        if (uiState.currentSong != null) {
            AsyncImage(
                model = uiState.currentSong?.albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(50.dp)
                    .graphicsLayer(alpha = 0.4f),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(0.6f),
                                Color.Black.copy(0.85f)
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
                if (!showFullPlayer) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        title = {
                            val titleText = when (uiState.currentView) {
                                LibraryView.ALL_SONGS -> "All Songs"
                                LibraryView.ALBUMS -> "Albums"
                                LibraryView.ARTISTS -> "Artists"
                                LibraryView.FOLDERS -> "Folders"
                                LibraryView.YEARS -> "Years"
                                LibraryView.GENRES -> "Genres"
                                LibraryView.FAVORITES -> "Favorites"
                                LibraryView.RECENTLY_PLAYED -> "Recently Played"
                                LibraryView.RECENTLY_ADDED -> "Recently Added"
                                LibraryView.ALBUM_DETAIL -> uiState.selectedItemName ?: "Album"
                                LibraryView.ARTIST_DETAIL -> uiState.selectedItemName ?: "Artist"
                                LibraryView.FOLDER_DETAIL -> uiState.selectedItemName?.substringAfterLast("/") ?: "Folder"
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
                                LibraryView.FAVORITES -> Icons.Rounded.Favorite
                                LibraryView.RECENTLY_PLAYED -> Icons.Rounded.History
                                LibraryView.RECENTLY_ADDED -> Icons.Rounded.NewReleases
                                else -> Icons.Rounded.MusicNote
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(titleIcon, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = titleText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = Color.White
                                )
                            }
                        },
                        actions = {
                            if (uiState.isMultiSelectMode) {
                                Text(
                                    "${uiState.selectedSongIds.size} selected",
                                    color = AccentBlue,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                IconButton(onClick = { viewModel.addSelectedToPlaylist() }) {
                                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Playlist", tint = Color.White)
                                }
                                IconButton(onClick = { viewModel.deleteSelectedSongs() }) {
                                    Icon(Icons.Rounded.Delete, "Delete", tint = Color.White)
                                }
                                IconButton(onClick = { viewModel.setMultiSelectMode(false) }) {
                                    Icon(Icons.Rounded.Close, "Cancel", tint = Color.White)
                                }
                            } else {
                                IconButton(onClick = { 
                                    Toast.makeText(context, "Settings coming soon!", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Rounded.Settings, "Settings", tint = Color.White.copy(0.7f))
                                }
                            }
                        }
                    )
                }
            }
        ) { paddingValues ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding(),
                            bottom = 0.dp // We handle bottom padding manually for floating mini player
                        )
                ) {
                    // Persistent Search Bar at Top
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color.White.copy(0.12f))
                            .clickable { viewModel.setSearchActive(true) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Search, 
                                null, 
                                tint = Color.White.copy(0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Search songs, artists, albums...",
                                color = Color.White.copy(0.5f),
                                fontSize = 15.sp
                            )
                        }
                    }

                    // Full Screen Search Overlay
                    AnimatedVisibility(
                        visible = uiState.isSearchActive,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                        modifier = Modifier.fillMaxSize().zIndex(10f).background(Color.Black.copy(0.95f))
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            // Search Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewModel.setSearchActive(false) }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
                                }
                                TextField(
                                    value = uiState.searchQuery,
                                    onValueChange = { viewModel.setSearchQuery(it) },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Search...", color = Color.White.copy(0.5f)) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        cursorColor = AccentBlue,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    singleLine = true
                                )
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Rounded.Close, null, tint = Color.White)
                                    }
                                }
                            }

                            if (uiState.searchQuery.isEmpty()) {
                                // Browse / Genre Grid (User's Image style)
                                Text(
                                    "Browse all",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                
                                val genres by viewModel.genres.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(genres) { genre ->
                                        GenreGridItem(genre.first, genre.second, genre.third) {
                                            viewModel.setSearchQuery(genre.first)
                                        }
                                    }
                                }
                            } else {
                                // Search Results
                                val filteredSongs by viewModel.songs.collectAsStateWithLifecycle()
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 120.dp)
                                ) {
                                    itemsIndexed(filteredSongs) { index, song ->
                                        SongListItem(
                                            song = song,
                                            trackNumber = index + 1,
                                            isPlaying = uiState.currentSong?.id == song.id,
                                            onClick = { 
                                                viewModel.playSong(song)
                                                viewModel.setSearchActive(false)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Main Action Row (Shuffle + Sort + Search + Library + Cloud)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. Combined Shuffle Play Button
                        IconButton(
                            onClick = { viewModel.shuffleAndPlay() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    null,
                                    tint = Color.White.copy(0.9f),
                                    modifier = Modifier.size(30.dp).align(Alignment.CenterStart).offset(x = (-2).dp)
                                )
                                Icon(
                                    Icons.Rounded.Shuffle,
                                    null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(14.dp).align(Alignment.BottomEnd)
                                )
                            }
                        }

                        // 2. Sort Button
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Sort,
                                    null,
                                    tint = if (showSortMenu) AccentBlue else Color.White.copy(0.7f)
                                )
                            }
                            SortDropdown(
                                expanded = showSortMenu,
                                onDismiss = { showSortMenu = false },
                                viewModel = viewModel,
                                uiState = uiState
                            )
                        }

                        // 3. (Hidden Search Button since it's now at the top)
                        Spacer(modifier = Modifier.width(48.dp))

                        // 4. Library Button (Popup)
                        Box {
                            val arrowRotation by animateFloatAsState(if (showLibraryPopup) 180f else 0f, label = "arrowRotation")
                            IconButton(onClick = { showLibraryPopup = true }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.LibraryMusic, 
                                        null, 
                                        tint = if(showLibraryPopup) AccentBlue else Color.White.copy(0.7f)
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        null,
                                        tint = Color.White.copy(0.5f),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .graphicsLayer { rotationZ = arrowRotation }
                                    )
                                }
                            }
                            LibraryDropdown(
                                expanded = showLibraryPopup,
                                onDismiss = { showLibraryPopup = false },
                                viewModel = viewModel
                            )
                        }

                        // 5. (Replace Cloud with Equalizer for quick access)
                        IconButton(onClick = onNavigateToEqualizer) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(0.7f))
                        }
                    }

                    // Content Area (List / Grid)
                    Box(Modifier.weight(1f)) {
                        when (uiState.currentView) {
                            LibraryView.ALBUMS -> {
                                val albums by viewModel.albums.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
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
                            LibraryView.ARTISTS -> {
                                val artists by viewModel.artists.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
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
                            LibraryView.FOLDERS -> {
                                val folders by viewModel.folders.collectAsStateWithLifecycle()
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(folders) { folder ->
                                        LibraryGridItem(folder.second, folder.first, folder.third) {
                                            viewModel.setLibraryView(LibraryView.FOLDER_DETAIL, folder.first)
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
                                        GenreGridItem(genre.first, genre.second, genre.third) {
                                            viewModel.setLibraryView(LibraryView.GENRE_DETAIL, genre.first)
                                        }
                                    }
                                }
                            }
                            else -> {
                                val alphabet = ("#ABCDEFGHIJKLMNOPQRSTUVWXYZ").toList()
                                var dragOffset by remember { mutableStateOf<Float?>(null) }
                                var currentLetter by remember { mutableStateOf<Char?>(null) }

                                // Library list with alphabet scroller
                                Box(Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            top = 8.dp,
                                            bottom = paddingValues.calculateBottomPadding() + 100.dp
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
                                                isSelected = uiState.selectedSongIds.contains(song.id)
                                            )
                                        }
                                    }

                                    // Alphabet Scroller (Right Side)
                                    if (songs.isNotEmpty() && uiState.currentView == LibraryView.ALL_SONGS) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 4.dp)
                                                .width(32.dp)
                                                .fillMaxHeight(0.85f)
                                                .background(Color.White.copy(0.05f), RoundedCornerShape(16.dp))
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
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                                                verticalArrangement = Arrangement.SpaceEvenly,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                alphabet.forEach { letter ->
                                                    Text(
                                                        text = letter.toString(),
                                                        color = if (currentLetter == letter) AccentBlue else Color.White.copy(0.4f),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (currentLetter == letter) FontWeight.ExtraBold else FontWeight.Bold,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }

                                            // Bubble Preview
                                            val density = LocalDensity.current
                                            BoxWithConstraints {
                                                dragOffset?.let { y ->
                                                    val currentMaxHeightPx = with(density) { maxHeight.toPx() }
                                                    val index = (y / (currentMaxHeightPx / alphabet.size)).toInt().coerceIn(0, alphabet.size - 1)
                                                    val letter = alphabet[index]
                                                    currentLetter = letter

                                                    // Update list scroll
                                                    LaunchedEffect(letter) {
                                                        val scrollIndex = songs.indexOfFirst { it.title.uppercase().startsWith(letter) }
                                                        if (scrollIndex != -1) {
                                                            listState.scrollToItem(scrollIndex)
                                                        }
                                                    }

                                                    // Visual Bubble
                                                    Box(
                                                        modifier = Modifier
                                                            .offset { IntOffset(x = (-60).dp.roundToPx(), y = y.toInt() - 30.dp.roundToPx()) }
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
                                }
                            }
                        }
                    }
                }

                // Mini player overlay (Anchored to bottom, floating)
                if (uiState.currentSong != null && !uiState.isScanning) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = paddingValues.calculateBottomPadding() + 12.dp)
                            .padding(horizontal = 12.dp)
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
                                Text(
                                    text = uiState.currentSong?.artist ?: "Unknown Artist",
                                    color = Color.White.copy(0.7f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
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

                // Scan Overlay
                if (uiState.isScanning) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(0.7f)),
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
                                    progress = uiState.scanProgress,
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
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
            }
        }

        AnimatedVisibility(
            visible = showFullPlayer && uiState.currentSong != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(320)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(320))
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
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.skipToNext() },
                onPrevious = { viewModel.skipToPrevious() },
                onShuffle = { viewModel.toggleShuffle() },
                onRepeat = { viewModel.toggleRepeat() },
                onSeek = { viewModel.seekTo(it) },
                onClose = { showFullPlayer = false },
                onOpenEqualizer = onNavigateToEqualizer,
                onToggleLyrics = { viewModel.toggleLyrics() }
            )
        }
    }
}

@Composable
fun GenreGridItem(
    title: String,
    subtitle: String,
    artUri: android.net.Uri?,
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
            .background(randomColor)
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
            color = Color.White.copy(0.05f)
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
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            color = Color.White.copy(0.6f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(
            song.artist,
            color = Color.White.copy(0.6f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SortDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel,
    uiState: com.beatflowy.app.model.PlayerUiState
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
        exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .background(Color(0xFF1A1A1A).copy(alpha = 0.98f))
                .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
        ) {
            DropdownMenuItem(
                text = { Text("Name", color = Color.White) },
                onClick = { viewModel.setSortType(SortType.NAME); onDismiss() },
                leadingIcon = { if(uiState.sortType == SortType.NAME) Icon(Icons.Rounded.Check, null, tint = AccentBlue) }
            )
            DropdownMenuItem(
                text = { Text("Date Added", color = Color.White) },
                onClick = { viewModel.setSortType(SortType.DATE_ADDED); onDismiss() },
                leadingIcon = { if(uiState.sortType == SortType.DATE_ADDED) Icon(Icons.Rounded.Check, null, tint = AccentBlue) }
            )
            DropdownMenuItem(
                text = { Text("File Size", color = Color.White) },
                onClick = { viewModel.setSortType(SortType.FILE_SIZE); onDismiss() },
                leadingIcon = { if(uiState.sortType == SortType.FILE_SIZE) Icon(Icons.Rounded.Check, null, tint = AccentBlue) }
            )
            DropdownMenuItem(
                text = { Text("Duration", color = Color.White) },
                onClick = { viewModel.setSortType(SortType.DURATION); onDismiss() },
                leadingIcon = { if(uiState.sortType == SortType.DURATION) Icon(Icons.Rounded.Check, null, tint = AccentBlue) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.White.copy(0.1f))
            DropdownMenuItem(
                text = { Text(if (uiState.isAscending) "Ascending" else "Descending", color = Color.White) },
                onClick = { viewModel.toggleSortOrder(); onDismiss() },
                leadingIcon = { Icon(if (uiState.isAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = Color.White.copy(0.7f)) }
            )
        }
    }
}

@Composable
private fun LibraryDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel
) {
    val menuItems = listOf(
        Triple("All Songs", LibraryView.ALL_SONGS, Icons.Rounded.MusicNote to Color(0xFFFF4081)),
        Triple("Albums", LibraryView.ALBUMS, Icons.Rounded.Album to Color(0xFFB2FF59)),
        Triple("Artists", LibraryView.ARTISTS, Icons.Rounded.Person to Color(0xFF7C4DFF)),
        Triple("Folders", LibraryView.FOLDERS, Icons.Rounded.Folder to Color(0xFFFFAB40)),
        Triple("Years", LibraryView.YEARS, Icons.Rounded.CalendarMonth to Color(0xFFFF5252)),
        Triple("Genres", LibraryView.GENRES, Icons.Rounded.GridView to Color(0xFFE040FB)),
        Triple("Favorite songs", LibraryView.FAVORITES, Icons.Rounded.Favorite to Color(0xFFFF4081)),
        Triple("Recently played", LibraryView.RECENTLY_PLAYED, Icons.Rounded.History to Color(0xFF40C4FF)),
        Triple("Recently added", LibraryView.RECENTLY_ADDED, Icons.Rounded.NewReleases to Color(0xFF00E676))
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
        exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset(x = 0.dp, y = 4.dp),
            modifier = Modifier
                .background(Color(0xFF1A1A1A).copy(alpha = 0.98f))
                .width(220.dp)
                .border(
                    0.5.dp,
                    Color.White.copy(0.1f),
                    RoundedCornerShape(20.dp)
                )
                .clip(RoundedCornerShape(20.dp))
        ) {
            menuItems.forEach { (label, view, iconPair) ->
                val (icon, color) = iconPair
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(color.copy(0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    },
                    onClick = {
                        viewModel.setLibraryView(view)
                        onDismiss()
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
