package com.beatraxus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.beatraxus.app.addons.*
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OttBg = Color(0xFF0B0B0F)
private val OttAccent = Color(0xFFFF8A00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttHomeScreen(addon: StremioAddon, playerViewModel: PlayerViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var history by remember { mutableStateOf(OttHistory.load(context, addon.id)) }
    var rows by remember { mutableStateOf<List<Pair<OttCatalog, List<OttTitle>>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OttTitle>?>(null) }
    var selected by remember { mutableStateOf<OttTitle?>(null) }

    LaunchedEffect(Unit) {
        // connect() restores the saved manifest asynchronously; wait briefly for catalogs
        var tries = 0
        while (addon.catalogs.isEmpty() && tries++ < 40) delay(250)
        rows = addon.catalogs.filter { it.isRow }.take(8)
            .map { c -> async { c to ottCatching { addon.loadCatalog(c) }.getOrDefault(emptyList()) } }
            .awaitAll().filter { it.second.isNotEmpty() }
        loading = false
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(400)
        results = ottCatching { addon.searchTitles(query) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().background(OttBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search ${addon.addonName}", color = Color.White.copy(0.5f)) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White.copy(0.6f)) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = OttAccent, unfocusedBorderColor = Color.White.copy(0.2f), cursorColor = OttAccent
                ),
                modifier = Modifier.weight(1f)
            )
        }
        when {
            results != null -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (results!!.isEmpty()) item { Text("No results", color = Color.White.copy(0.6f)) }
                items(results!!) { t -> SearchRow(t) { selected = t } }
            }
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = OttAccent) }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("This addon has no browsable catalogs.\nUse search instead.", color = Color.White.copy(0.6f))
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                rows.first().second.firstOrNull()?.let { hero -> item { Hero(hero) { selected = hero } } }
                if (history.isNotEmpty()) item {
                    Text("Continue watching", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(history) { t -> Poster(t) { selected = t } }
                    }
                }
                items(rows) { (cat, titles) ->
                    Text("${cat.name} · ${cat.type.replaceFirstChar { it.uppercase() }}", color = Color.White,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(titles) { t -> Poster(t) { selected = t } }
                    }
                }
            }
        }
    }

    selected?.let { t ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Color(0xFF15151C)) {
            DetailSheet(addon, t) { stream, vid, title ->
                scope.launch {
                    selected = null
                    AddonStreamHeaders.register(stream.url, stream.headers)
                    OttHistory.add(context, addon.id, t)
                    history = OttHistory.load(context, addon.id)
                    AddonPlaybackHelper.resolveAndPlay(
                        addon,
                        AddonMediaItem("${t.type}:$vid", title, null, t.poster, "video", 0L, stream.url),
                        playerViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(t: OttTitle, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(280.dp)) {
        AsyncImage(t.background ?: t.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, OttBg))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(t.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            t.description?.let { Text(it, color = Color.White.copy(0.75f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp)) }
            Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = OttAccent, contentColor = Color.Black)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Poster(t: OttTitle, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick)) {
        AsyncImage(t.poster, t.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF22222B)), contentScale = ContentScale.Crop)
        Text(t.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SearchRow(t: OttTitle, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(t.poster, null, Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF22222B)), contentScale = ContentScale.Crop)
        Column(Modifier.padding(start = 12.dp)) {
            Text(t.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(t.type.replaceFirstChar { it.uppercase() }, t.year).joinToString(" · "), color = Color.White.copy(0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun DetailSheet(addon: StremioAddon, t: OttTitle, onPlay: (OttStream, String, String) -> Unit) {
    var detail by remember { mutableStateOf<OttDetail?>(null) }
    var season by remember { mutableIntStateOf(1) }
    var streamsFor by remember { mutableStateOf<String?>(null) }
    var streams by remember { mutableStateOf<List<OttStream>?>(null) }
    var streamTitle by remember { mutableStateOf(t.name) }

    suspend fun loadStreams(videoId: String, title: String) {
        streamsFor = videoId; streamTitle = title; streams = null
        streams = ottCatching { addon.streams(t.type, videoId) }.getOrDefault(emptyList())
    }
    LaunchedEffect(t.id) {
        detail = ottCatching { addon.detail(t) }.getOrNull()
        if (t.type != "series") loadStreams(t.id, t.name)
        else season = detail?.episodes?.firstOrNull { it.season > 0 }?.season ?: 1
    }
    val scope = rememberCoroutineScope()
    val d = detail
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(t.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(t.year, d?.runtime, t.rating?.let { "★ $it" }, d?.genres?.take(3)?.joinToString(", ")).joinToString("  ·  "), color = Color.White.copy(0.6f), fontSize = 12.sp)
            (d?.title?.description ?: t.description)?.let { Text(it, color = Color.White.copy(0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (d != null && d.episodes.isNotEmpty()) {
            val seasons = d.episodes.map { it.season }.distinct()
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { s ->
                        FilterChip(selected = s == season, onClick = { season = s }, label = { Text(if (s == 0) "Specials" else "Season $s") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OttAccent, selectedLabelColor = Color.Black, labelColor = Color.White))
                    }
                }
            }
            items(d.episodes.filter { it.season == season }) { ep ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { scope.launch { loadStreams(ep.id, "${t.name} S${ep.season}E${ep.episode}") } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(ep.thumbnail, null, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF22222B)), contentScale = ContentScale.Crop)
                    Text("${ep.episode}. ${ep.title}", color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        if (streamsFor != null) {
            item { Text("Streams", color = OttAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            val s = streams
            when {
                s == null -> item { CircularProgressIndicator(color = OttAccent, modifier = Modifier.size(24.dp)) }
                s.isEmpty() -> item { Text("No directly playable streams from this addon for this title.", color = Color.White.copy(0.6f), fontSize = 13.sp) }
                else -> items(s) { st ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF22222B)).clickable { onPlay(st, streamsFor!!, streamTitle) }.padding(12.dp)) {
                        Text(st.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (st.detail.isNotBlank()) Text(st.detail, color = Color.White.copy(0.6f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
