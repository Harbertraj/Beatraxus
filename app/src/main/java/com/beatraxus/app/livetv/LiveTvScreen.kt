package com.beatraxus.app.livetv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.sin

private val LiveRed = Color(0xFFFF3B5C)
private val LiveCyan = Color(0xFF00E5FF)
private val CardInk = Color(0xFF14161D)

/**
 * Playlist browser for video mode: paste the link of an M3U playlist you own or may use, pick it, and tap a channel
 * to watch it in the normal video player (previous / next there then zap between the channels of the list you opened).
 * The app ships with no playlists.
 *
 * [onPlay] receives the list the channel was tapped in and its index in that list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveTvScreen(
    onPlay: (channels: List<LiveChannel>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LiveRed,
    viewModel: LiveTvViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val visible = state.visibleChannels
    val favoriteIds = state.favoriteIds
    val focus = LocalFocusManager.current
    var playlistInput by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPlaylistFile(uri)
    }

    val infinite = rememberInfiniteTransition(label = "liveTv")
    val pulse = infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "livePulse"
    )
    val shimmer = infinite.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.13f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "liveShimmer"
    )

    val play: (List<LiveChannel>, LiveChannel) -> Unit = { list, channel ->
        focus.clearFocus()
        viewModel.onChannelPlayed(channel)
        onPlay(list, list.indexOfFirst { it.id == channel.id }.coerceAtLeast(0))
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                LiveHero(
                    title = state.selectedFilter?.let { "${it.emoji}  ${it.label}" } ?: "Playlists",
                    subtitle = when {
                        state.filters.isEmpty() -> "Add a link or an M3U file to get started"
                        state.isLoading -> "Opening playlist…"
                        else -> "${"%,d".format(state.channels.size)} channels"
                    },
                    accent = accent,
                    pulse = pulse
                )
            }

            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                LiveSearchField(
                    query = state.query,
                    accent = accent,
                    onQueryChange = viewModel::setQuery,
                    onSearch = { focus.clearFocus() }
                )
            }

            item(key = "playlistField", span = { GridItemSpan(maxLineSpan) }) {
                PlaylistField(
                    value = playlistInput,
                    error = state.inputError,
                    accent = accent,
                    onValueChange = {
                        playlistInput = it
                        viewModel.clearInputError()
                    },
                    onAdd = {
                        if (viewModel.addPlaylist(playlistInput)) {
                            playlistInput = ""
                            focus.clearFocus()
                        }
                    },
                    onPickFile = { pickFile.launch(arrayOf("*/*")) }
                )
            }

            if (state.filters.isNotEmpty()) {
                item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        LiveFilterRow(
                            filters = state.filters,
                            selectedCode = state.selectedCode,
                            accent = accent,
                            onSelect = viewModel::selectFilter
                        )
                        Text(
                            "Remove this playlist",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.removeSelected() }
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (state.tabs.size > 1) {
                item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
                    LiveSourceTabs(tabs = state.tabs, selected = state.tab, accent = accent, onSelect = viewModel::selectTab)
                }
            }

            if (state.facets.isNotEmpty()) {
                item(key = "facets", span = { GridItemSpan(maxLineSpan) }) {
                    LiveFilterRow(
                        filters = state.facets,
                        selectedCode = state.facet,
                        accent = accent,
                        onSelect = viewModel::selectFacet
                    )
                }
            }

            if (state.query.isBlank() && state.favorites.isNotEmpty()) {
                item(key = "favorites", span = { GridItemSpan(maxLineSpan) }) {
                    ChannelRail(
                        title = "My channels",
                        icon = { Icon(Icons.Rounded.Favorite, null, tint = accent, modifier = Modifier.size(16.dp)) },
                        channels = state.favorites,
                        keyPrefix = "fav",
                        accent = accent,
                        onClick = { play(state.favorites, it) }
                    )
                }
            }

            if (state.query.isBlank() && state.recents.isNotEmpty()) {
                item(key = "recents", span = { GridItemSpan(maxLineSpan) }) {
                    ChannelRail(
                        title = "Recently watched",
                        icon = { Icon(Icons.Rounded.History, null, tint = LiveCyan, modifier = Modifier.size(16.dp)) },
                        channels = state.recents,
                        keyPrefix = "rec",
                        accent = LiveCyan,
                        onClick = { play(state.recents, it) },
                        onClear = viewModel::clearRecents
                    )
                }
            }

            item(key = "section", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (state.query.isNotBlank()) "Results" else state.selectedFacet?.label ?: "All channels",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    if (!state.isLoading) {
                        Text(
                            "%,d".format(visible.size),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            when {
                state.isLoading -> {
                    gridItems(List(8) { it }, key = { "sk$it" }) {
                        SkeletonCard(shimmer)
                    }
                }

                state.filters.isEmpty() -> item(key = "noPlaylist", span = { GridItemSpan(maxLineSpan) }) {
                    StatusMessage(
                        icon = { Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(44.dp)) },
                        message = "Paste a playlist or stream link above, or open an M3U file. Beatraxus doesn't include or provide any channels.",
                        actionLabel = null,
                        accent = accent,
                        onAction = {}
                    )
                }

                state.error != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    StatusMessage(
                        icon = { Icon(Icons.Rounded.WifiOff, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(44.dp)) },
                        message = state.error.orEmpty(),
                        actionLabel = "Try again",
                        accent = accent,
                        onAction = viewModel::retry
                    )
                }

                visible.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    StatusMessage(
                        icon = { Icon(Icons.Rounded.SearchOff, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(44.dp)) },
                        message = if (state.query.isBlank()) "No channels here yet." else "No channel matches “${state.query.trim()}”.",
                        actionLabel = null,
                        accent = accent,
                        onAction = {}
                    )
                }

                else -> {
                    gridItemsIndexed(visible, key = { _, channel -> channel.id }) { index, channel ->
                        ChannelCard(
                            channel = channel,
                            number = index + 1,
                            isFavorite = channel.id in favoriteIds,
                            accent = accent,
                            pulse = pulse,
                            onClick = { play(visible, channel) },
                            onToggleFavorite = { viewModel.toggleFavorite(channel) }
                        )
                    }
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Beatraxus doesn't provide or host any channels. Only add playlists you have the right to use. Some streams may be offline or limited to certain regions.",
                            color = Color.White.copy(alpha = 0.32f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 12.dp, end = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Header
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LiveHero(title: String, subtitle: String, accent: Color, pulse: State<Float>) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.34f), Color(0xFF1A1020), Color(0xFF07161D))
                )
            )
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f))), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.72f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(accent, pulse, 8.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    "ON AIR",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        SignalBars(
            color = accent,
            modifier = Modifier.align(Alignment.CenterEnd).size(width = 54.dp, height = 44.dp)
        )
    }
}

@Composable
private fun LiveDot(color: Color, pulse: State<Float>, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = pulse.value }
            .clip(CircleShape)
            .background(color)
    )
}

/** Five bars that rise and fall out of phase, like a broadcast signal meter. */
@Composable
private fun SignalBars(color: Color, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "barsT"
    )
    Canvas(modifier) {
        val bars = 5
        val gap = size.width * 0.09f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val phase = (t + i * 0.19f) % 1f
            val level = 0.5f + 0.5f * sin(phase * 2f * PI.toFloat())
            val h = size.height * (0.28f + 0.72f * level)
            drawRoundRect(
                color = color.copy(alpha = 0.45f + 0.11f * i),
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Search, tabs, chips
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LiveSearchField(query: String, accent: Color, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search channels", color = Color.White.copy(alpha = 0.38f), fontSize = 15.sp)
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

/** Where the user pastes an M3U playlist link; [error] explains a rejected link. */
@Composable
private fun PlaylistField(
    value: String,
    error: String?,
    accent: Color,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onPickFile: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text("Paste a playlist or stream link", color = Color.White.copy(alpha = 0.38f), fontSize = 15.sp)
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Add", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .clickable(onClick = onPickFile)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.FolderOpen, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open an M3U file", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (error != null) {
            Text(
                error,
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp)
            )
        }
    }
}

/** The ways to narrow a playlist (categories, countries), with a highlight that slides to the chosen tab. */
@Composable
private fun LiveSourceTabs(tabs: List<LiveTvTab>, selected: LiveTvTab?, accent: Color, onSelect: (LiveTvTab) -> Unit) {
    val outer = RoundedCornerShape(22.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(outer)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), outer)
    ) {
        val tabWidth = maxWidth / tabs.size
        val offset by animateDpAsState(
            targetValue = tabWidth * tabs.indexOf(selected).coerceAtLeast(0),
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "tabOffset"
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(tabWidth)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.72f))))
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(outer)
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tab.label,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFilterRow(filters: List<LiveFilter>, selectedCode: String, accent: Color, onSelect: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(filters, selectedCode) {
        val index = filters.indexOfFirst { it.code == selectedCode }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(filters, key = { it.code }) { filter ->
            val selected = filter.code == selectedCode
            val bg by animateColorAsState(
                if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f),
                label = "chipBg"
            )
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bg)
                    .border(
                        1.dp,
                        if (selected) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.09f),
                        shape
                    )
                    .clickable { onSelect(filter.code) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(filter.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    filter.label,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Rails (favourites / recents)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ChannelRail(
    title: String,
    icon: @Composable () -> Unit,
    channels: List<LiveChannel>,
    keyPrefix: String,
    accent: Color,
    onClick: (LiveChannel) -> Unit,
    onClear: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(7.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onClear != null) {
                Text(
                    "Clear",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClear).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
            items(channels, key = { "${keyPrefix}_${it.id}" }) { channel ->
                ChannelBubble(channel, accent) { onClick(channel) }
            }
        }
    }
}

@Composable
private fun ChannelBubble(channel: LiveChannel, accent: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .border(2.dp, Brush.sweepGradient(listOf(accent, accent.copy(alpha = 0.15f), accent)), CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
        ) {
            LogoPlate(channel, Modifier.fillMaxSize(), cornerRadius = 100.dp, padding = 8.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            channel.name,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Channel card
// ═══════════════════════════════════════════════════════════════════════

/** Logo on a light plate (channel logos are designed for light or dark grounds, a plate keeps all of them readable). */
@Composable
private fun LogoPlate(
    channel: LiveChannel,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 14.dp,
    padding: androidx.compose.ui.unit.Dp = 10.dp
) {
    var failed by remember(channel.id) { mutableStateOf(false) }
    val tint = remember(channel.name) { tintFor(channel.name) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                if (channel.logoUrl != null && !failed) {
                    Brush.verticalGradient(listOf(Color(0xFFF7F8FB), Color(0xFFDDE2EC)))
                } else {
                    Brush.linearGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.35f)))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (channel.logoUrl != null && !failed) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(padding),
                onError = { failed = true }
            )
        } else {
            Text(
                channel.monogram,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    channel: LiveChannel,
    number: Int,
    isFavorite: Boolean,
    accent: Color,
    pulse: State<Float>,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val tint = remember(channel.name) { tintFor(channel.name) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.20f), CardInk, CardInk)))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onToggleFavorite)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(10.dp)) {
            LogoPlate(channel, Modifier.fillMaxSize().padding(top = 12.dp, bottom = 4.dp))

            // channel number, like a TV guide
            Text(
                text = "CH %03d".format(number),
                color = Color.White,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.50f))
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from my channels" else "Add to my channels",
                    tint = if (isFavorite) accent else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                channel.name,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2
            )
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveDot(accent, pulse, 5.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("LIVE", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
                channel.qualityLabel?.let { label ->
                    Tag(label, qualityColor(channel.qualityHeight))
                }
                if (channel.isGeoBlocked) Tag("GEO", Color(0xFFFFB300))
                Spacer(Modifier.weight(1f))
                channel.countryCode?.let { Text(flagEmoji(it), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private fun qualityColor(height: Int): Color = when {
    height >= 2160 -> Color(0xFFFFB300)
    height >= 1080 -> Color(0xFF0A84FF)
    height >= 720 -> Color(0xFF30D158)
    else -> Color(0xFF8E8E93)
}

/** A stable, pleasant colour per channel name, so cards without a logo still look distinct. */
private fun tintFor(name: String): Color {
    val hue = ((name.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
    return Color.hsv(hue, 0.55f, 0.92f)
}

// ═══════════════════════════════════════════════════════════════════════
// Loading / empty / error
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SkeletonCard(shimmer: State<Float>) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
            .padding(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(14.dp))
                .graphicsLayer { alpha = shimmer.value * 6f }
                .background(Color.White.copy(alpha = 0.10f))
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.45f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f)))
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatusMessage(
    icon: @Composable () -> Unit,
    message: String,
    actionLabel: String?,
    accent: Color,
    onAction: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Entry banner for the video home
// ═══════════════════════════════════════════════════════════════════════

/** Featured playlists banner for the video-mode home screen. */
@Composable
fun LiveTvBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "liveBanner")
    val pulse = infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bannerPulse"
    )
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(LiveRed.copy(alpha = 0.40f), Color(0xFF1A1020), Color(0xFF07161D))))
            .border(1.dp, Brush.linearGradient(listOf(LiveRed.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.74f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(LiveRed, pulse, 8.dp)
                Spacer(Modifier.width(7.dp))
                Text("PLAYLISTS", color = LiveRed, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Play your own M3U playlists",
                color = Color.White,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste a link or open an M3U file and watch here",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        SignalBars(
            color = LiveRed,
            modifier = Modifier.align(Alignment.CenterEnd).size(width = 50.dp, height = 40.dp)
        )
    }
}
