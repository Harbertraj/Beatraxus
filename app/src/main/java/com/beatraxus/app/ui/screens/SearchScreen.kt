package com.beatraxus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.repository.GenreApiService
import com.beatraxus.app.ui.theme.BgBase
import com.beatraxus.app.ui.theme.BgDeep
import com.beatraxus.app.ui.theme.TextPrimary
import com.beatraxus.app.ui.theme.TextSecondary
import com.beatraxus.app.viewmodel.PlayerViewModel

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.beatraxus.app.addons.AddonManager
import com.beatraxus.app.addons.MediaServerAddon
import com.beatraxus.app.addons.AddonMediaItem
import com.beatraxus.app.addons.AddonConnectionState
import com.beatraxus.app.addons.AddonPlaybackHelper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import com.beatraxus.app.model.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToVideoPlayer: (String) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var addonResults by remember { mutableStateOf<Map<MediaServerAddon, List<AddonMediaItem>>>(emptyMap()) }
    var isSearchingAddons by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        viewModel.setSearchQuery(searchQuery)
        if (searchQuery.isNotBlank()) {
            delay(400)
            debouncedQuery = searchQuery
        } else {
            debouncedQuery = ""
            addonResults = emptyMap()
        }
    }

    LaunchedEffect(debouncedQuery) {
        if (debouncedQuery.isNotBlank()) {
            isSearchingAddons = true
            val connectedAddons = AddonManager.addedAddons.value
                .filterIsInstance<MediaServerAddon>()
                .filter { it.connectionState.value == AddonConnectionState.CONNECTED }

            val results = connectedAddons.map { addon ->
                async {
                    try {
                        addon to addon.search(debouncedQuery)
                    } catch (e: Exception) {
                        addon to emptyList<AddonMediaItem>()
                    }
                }
            }.awaitAll().toMap()
            addonResults = results
            isSearchingAddons = false
        }
    }

    val localSearchResults by viewModel.searchResults.collectAsState(initial = emptyList())

    Scaffold(
        containerColor = BgBase,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgDeep)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    placeholder = { Text("Search...", color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TextSecondary) },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(0.05f),
                        unfocusedContainerColor = Color.White.copy(0.05f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = TextPrimary
                    ),
                    singleLine = true
                )
            }
        }
    ) { paddingValues ->
        if (searchQuery.isBlank()) {
            val genres = remember {
                GenreApiService.STANDARD_GENRES.map { name ->
                    name to getGenreColor(name)
                }
            }

            Column(modifier = Modifier.padding(paddingValues)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Browse by genre",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    IconButton(onClick = { /* Toggle view */ }) {
                        Icon(Icons.Rounded.GridView, null, tint = TextPrimary)
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(genres) { (name, color) ->
                        GenreCard(name, color) {
                            // Navigate to genre detail
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.padding(paddingValues).fillMaxSize()
            ) {
                if (localSearchResults.isNotEmpty()) {
                    item {
                        Text(
                            "Local Library",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(localSearchResults) { item ->
                        when (item) {
                            is String -> {
                                Text(item, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            is Song -> {
                                // Simple song representation
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { viewModel.playSong(item) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(item.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            // Also handle Video etc if needed, but keeping it simple for search.
                        }
                    }
                }

                addonResults.forEach { (addon, items) ->
                    if (items.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = addon.icon,
                                    contentDescription = null,
                                    tint = addon.brandColor,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    addon.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        
                        items(items) { item ->
                            var isLoading by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (isLoading) return@clickable
                                    isLoading = true
                                    coroutineScope.launch {
                                        try {
                                            AddonPlaybackHelper.resolveAndPlay(
                                                addon = addon,
                                                item = item,
                                                playerViewModel = viewModel,
                                                onNavigateToVideoPlayer = onNavigateToVideoPlayer
                                            )
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha=0.1f))) {
                                    AsyncImage(
                                        model = item.artworkUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isLoading) {
                                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(24.dp), color = Color.White, strokeWidth = 2.dp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(item.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = item.subtitle ?: (if (item.mediaType == "video") "Video" else "Audio")
                                    Text(sub, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                
                if (isSearchingAddons) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TextPrimary)
                        }
                    }
                } else if (localSearchResults.isEmpty() && addonResults.values.all { it.isEmpty() }) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No items found", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun getGenreColor(name: String): Color {
    val hash = name.hashCode()
    return when (name) {
        "Tamil", "Tamil Film Music", "Tamil Melody" -> Color(0xFF0D47A1)
        "Hindi", "Hindi Film Music" -> Color(0xFFE65100)
        "English Pop", "Pop" -> Color(0xFFC2185B)
        "Rock" -> Color(0xFFD32F2F)
        "Hip-Hop", "Rap" -> Color(0xFF4527A0)
        "Electronic", "EDM", "Dance" -> Color(0xFF00796B)
        "Classical" -> Color(0xFF5D4037)
        "Lo-Fi", "Ambient" -> Color(0xFF263238)
        else -> {
            // Generate a stable color based on name
            val hue = (hash.coerceAtLeast(0) % 360).toFloat()
            Color.hsl(hue, 0.6f, 0.4f)
        }
    }
}

@Composable
fun GenreCard(name: String, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Box(Modifier.padding(12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                ),
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}
