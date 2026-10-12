package com.beatraxus.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Movie
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
import com.beatraxus.app.addons.AddonHttp
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
    onBack: () -> Unit
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state = BrowseState.Error("Couldn't load ${addon.displayName}\n${AddonHttp.friendlyMessage(e)}")
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
                    placeholder = { Text("Search ${addon.displayName}…", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = addon.brandColor,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.8.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
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
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = currentState.message,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = { loadData(searchQuery) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = addon.brandColor.copy(alpha = 0.18f),
                                contentColor = addon.brandColor
                            )
                        ) {
                            Text("RETRY", fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
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
                            columns = GridCells.Adaptive(minSize = 108.dp),
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
                                                    playerViewModel = playerViewModel
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
    val isAudio = item.mediaType == "audio"
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (isAudio) 1f else 2f / 3f)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    BorderStroke(0.8.dp, Color.White.copy(alpha = 0.1f)),
                    shape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Placeholder icon shows through while art loads or if the item has none.
            Icon(
                imageVector = if (isAudio) Icons.Default.MusicNote else Icons.Default.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.18f),
                modifier = Modifier.size(32.dp)
            )
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
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
