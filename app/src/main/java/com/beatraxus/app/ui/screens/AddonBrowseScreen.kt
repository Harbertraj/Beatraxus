package com.beatraxus.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Movie
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import kotlinx.coroutines.Job
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.addons.AddonHttp
import com.beatraxus.app.addons.AddonMediaItem
import com.beatraxus.app.addons.AddonPlaybackHelper
import com.beatraxus.app.addons.ArchiveOrgAddon
import com.beatraxus.app.addons.FilterableAddon
import com.beatraxus.app.addons.JamendoAddon
import com.beatraxus.app.addons.MediaServerAddon
import com.beatraxus.app.addons.PagedAddon
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.Video
import com.beatraxus.app.ui.theme.BgBase
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed class BrowseState {
    object Loading : BrowseState()
    data class Success(
        val items: List<AddonMediaItem>,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val page: Int = 0
    ) : BrowseState()
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

    var loadJob by remember { mutableStateOf<Job?>(null) }
    var detailItem by remember { mutableStateOf<AddonMediaItem?>(null) }
    val isArchive = addon is ArchiveOrgAddon
    val paged = addon as? PagedAddon
    val filterable = addon as? FilterableAddon
    val uriHandler = LocalUriHandler.current

    // Chip selection per filter group; add-ons without filters simply have none.
    var filters by remember(addon.id) {
        mutableStateOf(filterable?.filterGroups?.associate { it.id to it.defaultKey } ?: emptyMap())
    }

    fun loadData(query: String? = null) {
        state = BrowseState.Loading
        loadJob?.cancel() // a slow older request must never overwrite a newer one
        loadJob = coroutineScope.launch {
            try {
                state = if (paged != null) {
                    val first = paged.page(query?.takeIf { it.isNotBlank() }, 0)
                    BrowseState.Success(first.items.distinctBy { it.id }, hasMore = first.hasMore, page = 0)
                } else {
                    val results = if (query.isNullOrBlank()) {
                        addon.browse(null)
                    } else {
                        addon.search(query)
                    }
                    BrowseState.Success(results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = BrowseState.Error("Couldn't load ${addon.displayName}\n${AddonHttp.friendlyMessage(e)}")
            }
        }
    }

    // Next page, appended below what is already shown (infinite scroll).
    fun loadMore() {
        val current = state as? BrowseState.Success ?: return
        if (paged == null || !current.hasMore || current.loadingMore) return
        state = current.copy(loadingMore = true)
        val query = searchQuery
        loadJob = coroutineScope.launch {
            try {
                val next = paged.page(query.takeIf { it.isNotBlank() }, current.page + 1)
                val known = current.items.mapTo(HashSet()) { it.id }
                val fresh = next.items.filter { known.add(it.id) }
                state = BrowseState.Success(
                    current.items + fresh,
                    hasMore = next.hasMore && fresh.isNotEmpty(),
                    page = current.page + 1
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = current.copy(loadingMore = false)
                streamError = AddonHttp.friendlyMessage(e)
            }
        }
    }

    // One effect drives every load (first open, typing, chip change) so nothing is fetched twice.
    LaunchedEffect(addon.id, searchQuery, filters) {
        filterable?.let { f -> filters.forEach { (group, option) -> f.setFilter(group, option) } }
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

        filterable?.filterGroups?.forEach { group ->
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Text(
                        text = group.label,
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                }
                rowItems(group.options) { option ->
                    val selected = filters[group.id] == option.key
                    FilterChip(
                        selected = selected,
                        onClick = { filters = filters + (group.id to option.key) },
                        label = { Text(option.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            labelColor = Color.White.copy(alpha = 0.75f),
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color.Black
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selected,
                            borderColor = Color.White.copy(alpha = 0.12f),
                            selectedBorderColor = Color.Transparent
                        )
                    )
                }
            }
        }

        if (addon is JamendoAddon) {
            // Jamendo's API terms: credit the artists and Jamendo as the provider, link each track to its Jamendo page.
            Text(
                text = "Music provided by Jamendo · tap ⓘ on a track for its licence and Jamendo page",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

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
                        val gridState = rememberLazyGridState()
                        if (paged != null) {
                            val total = currentState.items.size
                            LaunchedEffect(gridState, total, currentState.hasMore) {
                                snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                                    .distinctUntilChanged()
                                    .collect { last -> if (last >= total - 6) loadMore() }
                            }
                        }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = if (isArchive) 150.dp else 108.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(currentState.items, key = { it.id }) { item ->
                                AddonGridItem(
                                    item = item,
                                    landscapeVideo = isArchive,
                                    isLoading = resolvingItemId == item.id,
                                    onInfo = if (item.pageUrl != null || item.licenseUrl != null) {
                                        { detailItem = item }
                                    } else null,
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
                                                    browsing = currentState.items
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                streamError = e.message ?: "Playback failed"
                                            } finally {
                                                resolvingItemId = null
                                            }
                                        }
                                    }
                                )
                            }
                            if (currentState.loadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Credits + links for a track: the creator, the provider, its page, and the licence it is published under.
    detailItem?.let { item ->
        AlertDialog(
            onDismissRequest = { detailItem = null },
            containerColor = Color(0xFF1A1B1F),
            title = { Text(item.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.artist?.let { Text("Artist: $it", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
                    item.album?.let { Text("Album: $it", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
                    item.durationLabel?.let { Text("Length: $it", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
                    Text("Provided by ${addon.displayName}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    if (item.licenseUrl != null) {
                        Text(
                            "Licence: ${item.licenseUrl}",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                        )
                    } else {
                        Text(
                            "Licence: see the item's page",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    item.licenseUrl?.let { link ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                            Text("LICENCE", color = Color.White)
                        }
                    }
                    item.pageUrl?.let { link ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                            Text("OPEN PAGE", color = addon.brandColor)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailItem = null }) { Text("CLOSE", color = Color.White) }
            }
        )
    }
}

@Composable
fun AddonGridItem(
    item: AddonMediaItem,
    isLoading: Boolean,
    landscapeVideo: Boolean = false,
    onInfo: (() -> Unit)? = null,
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
                .aspectRatio(if (isAudio) 1f else if (landscapeVideo) 4f / 3f else 2f / 3f)
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

            item.durationLabel?.let { label ->
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (onInfo != null) {
                IconButton(
                    onClick = onInfo,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Track info and licence",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

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
            maxLines = if (landscapeVideo) 2 else 1,
            lineHeight = 18.sp,
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
