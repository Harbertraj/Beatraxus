package com.beatraxus.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.addons.AddonMediaItem
import com.beatraxus.app.addons.AddonPlaybackHelper
import com.beatraxus.app.addons.MediaServerAddon
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.Video
import com.beatraxus.app.ui.theme.BgBase
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class BrowseState {
    object Loading : BrowseState()
    data class Success(val items: List<AddonMediaItem>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonBrowseScreen(
    addon: MediaServerAddon,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToVideoPlayer: (String) -> Unit
) {
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var searchQuery by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var resolvingItemId by remember { mutableStateOf<String?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }

    fun loadData(query: String? = null) {
        state = BrowseState.Loading
        coroutineScope.launch {
            try {
                val results = if (query.isNullOrBlank()) {
                    addon.browse(null)
                } else {
                    addon.search(query)
                }
                state = BrowseState.Success(results)
            } catch (e: Exception) {
                state = BrowseState.Error("Couldn't reach ${addon.displayName} — check your connection or server URL")
            }
        }
    }

    LaunchedEffect(addon.id) {
        loadData()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            loadData(null)
        } else {
            delay(400) // Debounce
            loadData(searchQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search ${addon.displayName}...", color = Color.White.copy(alpha = 0.5f)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        streamError?.let { errorMsg ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { streamError = null }) { Text("Dismiss", color = Color.White) }
                },
                containerColor = Color(0xFFFF5252)
            ) {
                Text(errorMsg, color = Color.White)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (val currentState = state) {
                is BrowseState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
                is BrowseState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentState.message,
                            color = Color.White,
                            modifier = Modifier.padding(16.dp)
                        )
                        Button(onClick = { loadData(searchQuery) }) {
                            Text("Retry")
                        }
                    }
                }
                is BrowseState.Success -> {
                    if (currentState.items.isEmpty()) {
                        Text(
                            text = "No items found",
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(currentState.items, key = { it.id }) { item ->
                                AddonGridItem(
                                    item = item,
                                    isLoading = resolvingItemId == item.id,
                                    onClick = {
                                        if (resolvingItemId != null) return@AddonGridItem
                                        resolvingItemId = item.id
                                        streamError = null
                                        coroutineScope.launch {
                                            try {
                                                AddonPlaybackHelper.resolveAndPlay(
                                                    addon = addon,
                                                    item = item,
                                                    playerViewModel = playerViewModel,
                                                    onNavigateToVideoPlayer = onNavigateToVideoPlayer
                                                )
                                            } catch (e: Exception) {
                                                streamError = e.message ?: "Playback failed"
                                            } finally {
                                                resolvingItemId = null
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddonGridItem(
    item: AddonMediaItem,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            AsyncImage(
                model = item.artworkUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        if (!item.subtitle.isNullOrBlank()) {
            Text(
                text = item.subtitle,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
