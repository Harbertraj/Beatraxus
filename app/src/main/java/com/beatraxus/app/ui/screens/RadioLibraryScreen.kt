package com.beatraxus.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.model.RadioStation
import com.beatraxus.app.repository.RadioBrowserApi
import com.beatraxus.app.repository.RadioStationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Quick-pick regions. An empty query means the default Tamil list (see [RadioBrowserApi.tamilStations]). */
private val RadioQuickPicks = listOf(
    "Tamil" to "",
    "India" to "India",
    "Sri Lanka" to "Sri Lanka",
    "Malaysia" to "Malaysia",
    "Singapore" to "Singapore",
    "Canada" to "Canada",
    "Australia" to "Australia"
)

/**
 * Radio library. Deliberately has no shuffle / sort / cloud / density strip above it: a live radio
 * list has nothing to shuffle or sort, so the screen is just search, region chips and stations.
 */
@Composable
fun RadioLibraryScreen(
    currentSongId: String?,
    isPlaying: Boolean,
    accent: Color,
    onStationClick: (RadioStation) -> Unit,
    modifier: Modifier = Modifier
) {
    var stations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }

    // Blank query = popular Tamil stations, loaded immediately. Typing (or picking a chip) switches
    // to a country search. Typing is debounced so each keystroke doesn't hit the network.
    LaunchedEffect(query, reloadKey) {
        isLoading = true
        if (query.isNotBlank()) delay(400)
        val result = withContext(Dispatchers.IO) {
            if (query.isBlank()) RadioBrowserApi.tamilStations(limit = 150)
            else RadioBrowserApi.stationsByCountry(query.trim())
        }
        stations = result.distinctBy { it.id.ifBlank { it.streamUrl } }
        if (result.isNotEmpty()) RadioStationCache.stations = result
        isLoading = false
    }

    Column(modifier.fillMaxSize()) {
        RadioSearchField(
            query = query,
            onQueryChange = { query = it },
            accent = accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            items(RadioQuickPicks, key = { it.first }) { (label, value) ->
                val selected = query.trim().equals(value, ignoreCase = true)
                RadioChip(label = label, selected = selected, accent = accent) { query = value }
            }
        }

        val sectionTitle = (if (query.isBlank()) "TAMIL STATIONS" else query.trim().uppercase()) +
            if (!isLoading && stations.isNotEmpty()) "  ·  ${stations.size}" else ""
        Text(
            text = sectionTitle,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }

            stations.isEmpty() -> RadioEmptyState(
                accent = accent,
                searching = query.isNotBlank(),
                onRetry = { reloadKey++ }
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stations, key = { it.id.ifBlank { it.streamUrl } }) { station ->
                    val isCurrent = currentSongId == "radio_${station.id}"
                    RadioStationRow(
                        station = station,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        accent = accent,
                        onClick = { onStationClick(station) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search stations by country",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                }
                inner()
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
private fun RadioChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(
                BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f)),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun RadioStationRow(
    station: RadioStation,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isCurrent) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (isCurrent) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioStationLogo(
            logoUrl = station.favicon,
            accent = accent,
            size = 52.dp,
            cornerRadius = 14.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = station.name.trim().ifBlank { "Unknown station" },
                color = if (isCurrent) accent else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(station.band, station.country.ifBlank { null })
                    .filterNotNull().joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) {
                RadioEqBars(color = accent, animate = true, maxHeight = 18.dp)
            } else {
                Icon(
                    imageVector = if (isCurrent) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = if (isCurrent) accent else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White.copy(alpha = 0.06f), CircleShape)
                        .padding(6.dp)
                )
            }
        }
    }
}

/** Station favicon on a soft tinted tile, falling back to a radio glyph when there is none / it fails. */
@Composable
internal fun RadioStationLogo(
    logoUrl: String?,
    accent: Color,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    val showImage = !logoUrl.isNullOrBlank() && !failed
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f)))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showImage) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(size * 0.12f)
            )
        } else {
            Icon(
                Icons.Rounded.Radio,
                contentDescription = null,
                tint = accent.copy(alpha = 0.9f),
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

@Composable
private fun RadioEmptyState(accent: Color, searching: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (searching) "No stations found" else "Couldn't load stations",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searching) "Try another country name." else "Check your connection and try again.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                .clickable(onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Little bouncing level bars used as the "this station is live" indicator. */
@Composable
internal fun RadioEqBars(
    color: Color,
    animate: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 18.dp
) {
    val transition = rememberInfiniteTransition(label = "radioEq")
    val levels = (0 until barCount).map { i ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 420 + i * 110, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "radioEqBar$i"
        )
    }
    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(barWidth * 0.8f),
        verticalAlignment = Alignment.Bottom
    ) {
        levels.forEach { level ->
            Box(
                Modifier
                    .width(barWidth)
                    .height(maxHeight)
                    .graphicsLayer {
                        scaleY = if (animate) level.value else 0.25f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}
