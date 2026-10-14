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
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import coil.compose.AsyncImage
import com.beatraxus.app.model.RadioStation
import com.beatraxus.app.repository.RadioBrowserApi
import com.beatraxus.app.repository.RadioStationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.beatraxus.app.ui.theme.appFontFamily

// ─────────────────────────────────────────────────────────────────────────────────────────────
//  Radio catalogue: every browsable category, grouped like Apple Music's Radio / Browse tab.
//  Stations come from the free Radio-Browser directory; artist / actor / actress shelves search it by
//  name and community tag, so what shows up depends on what stations exist for that name.
// ─────────────────────────────────────────────────────────────────────────────────────────────

private enum class RadioKind { TAMIL, TOP, LANGUAGE, TAG, COUNTRY, TERMS }

private data class RadioCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: RadioKind,
    val query: String = "",
    val terms: List<String> = emptyList()
)

private data class RadioGroup(val title: String, val icon: ImageVector, val items: List<RadioCategory>)

private object RadioCatalog {
    private fun slug(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun lang(title: String, query: String = title.lowercase()) =
        RadioCategory("lang_${slug(title)}", title, "$title stations", RadioKind.LANGUAGE, query)
    private fun tag(title: String, tag: String, subtitle: String) =
        RadioCategory("tag_${slug(title)}", title, subtitle, RadioKind.TAG, tag)
    private fun country(title: String, query: String = title) =
        RadioCategory("country_${slug(title)}", title, "Stations from $title", RadioKind.COUNTRY, query)
    private fun person(prefix: String, title: String, vararg terms: String) =
        RadioCategory("${prefix}_${slug(title)}", title, "Stations playing $title", RadioKind.TERMS, terms = terms.toList())

    val tamil = RadioCategory("tamil", "Tamil Radio", "Live Tamil stations, 24/7", RadioKind.TAMIL)
    val top = RadioCategory("top", "Top Stations", "Most listened worldwide", RadioKind.TOP)
    private val kollywood = tag("Kollywood Hits", "kollywood", "Tamil film songs on air")
    private val bollywood = tag("Bollywood", "bollywood", "Hindi film music")
    private val rahman = person("artist", "A. R. Rahman", "a r rahman", "ar rahman", "rahman")

    val featured = listOf(tamil, top, kollywood, bollywood, rahman)

    private val languages = listOf(
        lang("Hindi"), lang("Telugu"), lang("Malayalam"), lang("Kannada"), lang("Bengali"),
        lang("Marathi"), lang("Punjabi"), lang("Gujarati"), lang("Urdu"), lang("Sinhala"),
        lang("English"), lang("Arabic"), lang("Spanish"), lang("French"), lang("Korean"), lang("Japanese")
    )

    private val artists = listOf(
        rahman,
        person("artist", "Ilaiyaraaja", "ilaiyaraaja", "ilayaraja", "ilaiyaraja"),
        person("artist", "Anirudh Ravichander", "anirudh"),
        person("artist", "Yuvan Shankar Raja", "yuvan"),
        person("artist", "Harris Jayaraj", "harris jayaraj"),
        person("artist", "S. P. Balasubrahmanyam", "spb", "balasubrahmanyam"),
        person("artist", "Sid Sriram", "sid sriram"),
        person("artist", "Hiphop Tamizha", "hiphop tamizha"),
        person("artist", "Arijit Singh", "arijit singh", "arijit"),
        person("artist", "Shreya Ghoshal", "shreya ghoshal"),
        person("artist", "Kishore Kumar", "kishore kumar"),
        person("artist", "Lata Mangeshkar", "lata mangeshkar", "lata"),
        person("artist", "Mohammed Rafi", "mohammed rafi", "rafi"),
        person("artist", "Michael Jackson", "michael jackson"),
        person("artist", "Taylor Swift", "taylor swift"),
        person("artist", "Ed Sheeran", "ed sheeran"),
        person("artist", "The Weeknd", "the weeknd", "weeknd"),
        person("artist", "Coldplay", "coldplay"),
        person("artist", "BTS", "bts")
    )

    private val actors = listOf(
        person("actor", "Rajinikanth", "rajinikanth", "rajini"),
        person("actor", "Kamal Haasan", "kamal haasan", "kamal"),
        person("actor", "Vijay", "thalapathy vijay", "vijay"),
        person("actor", "Ajith Kumar", "ajith kumar", "ajith"),
        person("actor", "Suriya", "suriya"),
        person("actor", "Dhanush", "dhanush"),
        person("actor", "Vikram", "chiyaan vikram", "vikram"),
        person("actor", "Sivakarthikeyan", "sivakarthikeyan"),
        person("actor", "Shah Rukh Khan", "shah rukh khan", "shahrukh"),
        person("actor", "Salman Khan", "salman khan"),
        person("actor", "Amitabh Bachchan", "amitabh bachchan", "amitabh"),
        person("actor", "Allu Arjun", "allu arjun"),
        person("actor", "Prabhas", "prabhas"),
        person("actor", "Mohanlal", "mohanlal"),
        person("actor", "Mammootty", "mammootty")
    )

    private val actresses = listOf(
        person("actress", "Nayanthara", "nayanthara"),
        person("actress", "Trisha", "trisha"),
        person("actress", "Samantha", "samantha"),
        person("actress", "Jyothika", "jyothika"),
        person("actress", "Simran", "simran"),
        person("actress", "Shruti Haasan", "shruti haasan"),
        person("actress", "Keerthy Suresh", "keerthy suresh"),
        person("actress", "Sai Pallavi", "sai pallavi"),
        person("actress", "Anushka Shetty", "anushka shetty", "anushka"),
        person("actress", "Deepika Padukone", "deepika padukone", "deepika"),
        person("actress", "Alia Bhatt", "alia bhatt"),
        person("actress", "Priyanka Chopra", "priyanka chopra")
    )

    private val genres = listOf(
        kollywood, bollywood,
        tag("Film Songs", "filmi", "Soundtrack favourites"),
        tag("Retro & Oldies", "oldies", "Golden-era classics"),
        tag("Classical", "classical", "Timeless concert music"),
        tag("Carnatic", "carnatic", "South Indian classical"),
        tag("Devotional", "devotional", "Bhajans, hymns & chants"),
        tag("Pop", "pop", "Chart-toppers"),
        tag("Rock", "rock", "Guitars up"),
        tag("Hip Hop", "hiphop", "Beats & bars"),
        tag("Electronic", "electronic", "Dance & club"),
        tag("Jazz", "jazz", "Smooth & swinging"),
        tag("Chill", "chillout", "Wind down"),
        tag("Lo-fi", "lofi", "Study & focus"),
        tag("Folk", "folk", "Roots & traditions"),
        tag("News", "news", "Live news"),
        tag("Talk", "talk", "Talk & discussion"),
        tag("Sports", "sports", "Live commentary"),
        tag("Kids", "kids", "Family friendly")
    )

    private val countries = listOf(
        country("India"), country("Sri Lanka"), country("Malaysia"), country("Singapore"),
        country("Canada"), country("Australia"),
        country("United Kingdom", "The United Kingdom Of Great Britain And Northern Ireland"),
        country("United States", "The United States Of America")
    )

    val groups = listOf(
        RadioGroup("Languages", Icons.Rounded.Language, languages),
        RadioGroup("Artists", Icons.Rounded.MusicNote, artists),
        RadioGroup("Actors", Icons.Rounded.Person, actors),
        RadioGroup("Actresses", Icons.Rounded.Star, actresses),
        RadioGroup("Genres", Icons.Rounded.Mic, genres),
        RadioGroup("Countries", Icons.Rounded.Public, countries)
    )

    val all: List<RadioCategory> = (listOf(tamil, top) + groups.flatMap { it.items }).distinctBy { it.id }
    fun find(id: String): RadioCategory? = all.firstOrNull { it.id == id }
}

private val RadioGradients = listOf(
    Color(0xFFFA233B) to Color(0xFFFB5C74),
    Color(0xFFFF9500) to Color(0xFFFF5E3A),
    Color(0xFF5E5CE6) to Color(0xFFBF5AF2),
    Color(0xFF0A84FF) to Color(0xFF64D2FF),
    Color(0xFF30D158) to Color(0xFF00C7BE),
    Color(0xFFFF375F) to Color(0xFFFF9F0A),
    Color(0xFFAF52DE) to Color(0xFFFF2D55),
    Color(0xFF32ADE6) to Color(0xFF5E5CE6)
)

private fun radioGradient(id: String): Pair<Color, Color> =
    RadioGradients[(id.hashCode() and Int.MAX_VALUE) % RadioGradients.size]

private object RadioCategoryCache {
    val map = java.util.concurrent.ConcurrentHashMap<String, List<RadioStation>>()
}

private fun RadioStation.key(): String = id.ifBlank { streamUrl }

private fun loadCategory(cat: RadioCategory): List<RadioStation> {
    val raw = when (cat.kind) {
        RadioKind.TAMIL -> RadioBrowserApi.tamilStations(limit = 150)
        RadioKind.TOP -> RadioBrowserApi.topStations(limit = 150)
        RadioKind.LANGUAGE -> RadioBrowserApi.stationsByLanguage(cat.query, 150)
        RadioKind.TAG -> RadioBrowserApi.stationsByTag(cat.query, 150)
        RadioKind.COUNTRY -> RadioBrowserApi.stationsByCountry(cat.query, 150)
        RadioKind.TERMS -> RadioBrowserApi.stationsForTerms(cat.terms, 100)
    }
    return raw.distinctBy { it.key() }
}

/**
 * Radio library, Apple Music style: large title, search, featured cards, station shelves and
 * browse-by-language / artist / actor / actress / genre / country shelves. Tapping a category opens
 * its station list. Typing in search finds stations by name, country or tag.
 */
@Composable
fun RadioLibraryScreen(
    currentSongId: String?,
    isPlaying: Boolean,
    accent: Color,
    onStationClick: (RadioStation) -> Unit,
    modifier: Modifier = Modifier,
    /** Song ids ("radio_<id>") the user hearted on the radio Now Playing screen. */
    favoriteIds: Set<String> = emptySet()
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val selected = selectedId?.let { RadioCatalog.find(it) }

    var tamil by remember { mutableStateOf<List<RadioStation>?>(null) }
    var top by remember { mutableStateOf<List<RadioStation>?>(null) }
    var detailStations by remember { mutableStateOf<List<RadioStation>?>(null) }
    var detailFor by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    BackHandler(enabled = selected != null || query.isNotEmpty()) {
        if (selected != null) selectedId = null else query = ""
    }

    // Home shelves
    LaunchedEffect(reloadKey) {
        tamil = null
        top = null
        coroutineScope {
            val t = async(Dispatchers.IO) { RadioBrowserApi.tamilStations(limit = 40).distinctBy { it.key() } }
            val p = async(Dispatchers.IO) { RadioBrowserApi.topStations(limit = 40).distinctBy { it.key() } }
            val tamilResult = t.await()
            tamil = tamilResult
            if (tamilResult.isNotEmpty() && RadioStationCache.stations.isEmpty()) RadioStationCache.stations = tamilResult
            top = p.await()
        }
    }

    // Category detail
    LaunchedEffect(selectedId, reloadKey) {
        val cat = selectedId?.let { RadioCatalog.find(it) } ?: return@LaunchedEffect
        val cached = RadioCategoryCache.map[cat.id]
        detailStations = cached
        detailFor = if (cached != null) cat.id else null
        if (cached == null) {
            val result = withContext(Dispatchers.IO) { loadCategory(cat) }
            if (result.isNotEmpty()) RadioCategoryCache.map[cat.id] = result
            detailStations = result
            detailFor = cat.id
        }
    }

    // Search (debounced): station name, country and tag in one list
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        searchResults = withContext(Dispatchers.IO) {
            (runCatching { RadioBrowserApi.stationsByName(q, 60) }.getOrDefault(emptyList()) +
                runCatching { RadioBrowserApi.stationsByCountry(q, 60) }.getOrDefault(emptyList()) +
                runCatching { RadioBrowserApi.stationsByTagLoose(q, 40) }.getOrDefault(emptyList()))
                .distinctBy { it.key() }
                .take(120)
        }
        searching = false
    }

    val play: (RadioStation, List<RadioStation>) -> Unit = { station, list ->
        if (list.size >= 2) RadioStationCache.stations = list
        onStationClick(station)
    }

    if (selected != null) {
        RadioCategoryDetail(
            category = selected,
            stations = if (detailFor == selected.id) detailStations else null,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            accent = accent,
            favoriteIds = favoriteIds,
            onBack = { selectedId = null },
            onRetry = {
                RadioCategoryCache.map.remove(selected.id)
                detailStations = null
                detailFor = null
                reloadKey++
            },
            onPlay = play,
            modifier = modifier
        )
        return
    }

    val q = query.trim()
    val matchingCategories = remember(q) {
        if (q.length < 2) emptyList() else RadioCatalog.all.filter { it.title.contains(q, ignoreCase = true) }.take(12)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp)) {
                Text(
                    "Radio",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    "Live stations from around the world",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        }
        item(key = "search") {
            RadioSearchField(
                query = query,
                onQueryChange = { query = it },
                accent = accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }

        if (q.isEmpty()) {
            item(key = "hero") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                ) {
                    items(RadioCatalog.featured, key = { "hero_${it.id}" }) { cat ->
                        RadioHeroCard(cat) { selectedId = cat.id }
                    }
                }
            }
            item(key = "shelf_tamil") {
                Column {
                    RadioSectionHeader("Tamil Stations", onSeeAll = { selectedId = RadioCatalog.tamil.id })
                    RadioStationShelf(
                        stations = tamil, currentSongId = currentSongId, isPlaying = isPlaying, accent = accent,
                        onRetry = { reloadKey++ }, onClick = { st -> play(st, tamil.orEmpty()) }
                    )
                }
            }
            item(key = "shelf_top") {
                Column {
                    RadioSectionHeader("Top Stations", onSeeAll = { selectedId = RadioCatalog.top.id })
                    RadioStationShelf(
                        stations = top, currentSongId = currentSongId, isPlaying = isPlaying, accent = accent,
                        onRetry = { reloadKey++ }, onClick = { st -> play(st, top.orEmpty()) }
                    )
                }
            }
            RadioCatalog.groups.forEach { group ->
                item(key = "group_${group.title}") {
                    Column {
                        RadioSectionHeader(group.title)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(group.items, key = { "${group.title}_${it.id}" }) { cat ->
                                RadioCategoryTile(cat, group.icon) { selectedId = cat.id }
                            }
                        }
                    }
                }
            }
        } else {
            if (matchingCategories.isNotEmpty()) {
                item(key = "search_categories") {
                    Column {
                        RadioSectionHeader("Categories")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(matchingCategories, key = { "match_${it.id}" }) { cat ->
                                RadioCategoryTile(cat, Icons.Rounded.Radio) { selectedId = cat.id }
                            }
                        }
                    }
                }
            }
            item(key = "search_header") {
                RadioSectionHeader(
                    if (searching) "Searching…" else if (searchResults.isEmpty()) "Stations" else "Stations  ·  ${searchResults.size}"
                )
            }
            when {
                searching -> item(key = "search_loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent)
                    }
                }
                searchResults.isEmpty() -> item(key = "search_empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.WifiOff, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No stations found", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Try a station name, artist, language or country.",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                    }
                }
                else -> items(searchResults, key = { "res_${it.key()}" }) { station ->
                    val isCurrent = currentSongId == "radio_${station.id}"
                    RadioListRow(
                        station = station,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        accent = accent,
                        favorite = "radio_${station.id}" in favoriteIds,
                        onClick = { play(station, searchResults) }
                    )
                }
            }
        }
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RadioSectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (onSeeAll != null) {
            Text(
                "See All",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RadioHeroCard(category: RadioCategory, onClick: () -> Unit) {
    val (c1, c2) = radioGradient(category.id)
    Box(
        modifier = Modifier
            .width(290.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
            .clickable(onClick = onClick)
    ) {
        Icon(
            Icons.Rounded.Radio,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.16f),
            modifier = Modifier.size(150.dp).align(Alignment.BottomEnd).offset(x = 28.dp, y = 28.dp)
        )
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    "FEATURED",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    category.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    category.subtitle,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Listen Live", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RadioCategoryTile(category: RadioCategory, icon: ImageVector, onClick: () -> Unit) {
    val (c1, c2) = radioGradient(category.id)
    Box(
        modifier = Modifier
            .width(152.dp)
            .height(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
            .clickable(onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(64.dp).align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp)
        )
        Text(
            category.title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 28.dp)
        )
    }
}

@Composable
private fun RadioStationShelf(
    stations: List<RadioStation>?,
    currentSongId: String?,
    isPlaying: Boolean,
    accent: Color,
    onRetry: () -> Unit,
    onClick: (RadioStation) -> Unit
) {
    when {
        stations == null -> LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(5) {
                Column(Modifier.width(132.dp)) {
                    Box(Modifier.size(132.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(96.dp).height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
                }
            }
        }
        stations.isEmpty() -> Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                .clickable(onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Couldn't load stations  ·  Retry", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        else -> LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(stations.take(24), key = { "card_${it.key()}" }) { station ->
                val isCurrent = currentSongId == "radio_${station.id}"
                RadioStationCard(station, isCurrent, isCurrent && isPlaying, accent) { onClick(station) }
            }
        }
    }
}

@Composable
private fun RadioStationCard(
    station: RadioStation,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box {
            RadioStationLogo(logoUrl = station.favicon, accent = accent, size = 132.dp, cornerRadius = 14.dp)
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    RadioEqBars(color = accent, animate = isPlaying, barCount = 3, barWidth = 3.dp, maxHeight = 12.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = station.name.trim().ifBlank { "Unknown station" },
            color = if (isCurrent) accent else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOf(station.band, station.country.ifBlank { null }).filterNotNull().joinToString("  ·  "),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Flat Apple-style list row: artwork, title, subtitle, state glyph and an inset divider. */
@Composable
private fun RadioListRow(
    station: RadioStation,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    favorite: Boolean,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioStationLogo(logoUrl = station.favicon, accent = accent, size = 56.dp, cornerRadius = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = station.name.trim().ifBlank { "Unknown station" },
                    color = if (isCurrent) accent else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf(station.band, station.country.ifBlank { null }).filterNotNull().joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (favorite) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = "Favorite station",
                    tint = Color(0xFFFF4081),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (isPlaying) {
                    RadioEqBars(color = accent, animate = true, maxHeight = 16.dp)
                } else {
                    Icon(
                        imageVector = if (isCurrent) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (isCurrent) accent else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 86.dp)
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
    }
}

@Composable
private fun RadioCategoryDetail(
    category: RadioCategory,
    stations: List<RadioStation>?,
    currentSongId: String?,
    isPlaying: Boolean,
    accent: Color,
    favoriteIds: Set<String>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (RadioStation, List<RadioStation>) -> Unit,
    modifier: Modifier = Modifier
) {
    val (c1, c2) = radioGradient(category.id)
    Column(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .padding(start = 12.dp, top = 6.dp, bottom = 4.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        when {
            stations == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            stations.isEmpty() -> RadioEmptyState(accent = accent, searching = true, onRetry = onRetry)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                item(key = "banner") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .height(168.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(c1, c2)))
                    ) {
                        Icon(
                            Icons.Rounded.Radio,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.16f),
                            modifier = Modifier.size(170.dp).align(Alignment.BottomEnd).offset(x = 32.dp, y = 32.dp)
                        )
                        Column(
                            Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    category.title,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${category.subtitle}  ·  ${stations.size} stations",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White)
                                    .clickable { stations.firstOrNull()?.let { onPlay(it, stations) } }
                                    .padding(horizontal = 18.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                items(stations, key = { "row_${it.key()}" }) { station ->
                    val isCurrent = currentSongId == "radio_${station.id}"
                    RadioListRow(
                        station = station,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        accent = accent,
                        favorite = "radio_${station.id}" in favoriteIds,
                        onClick = { onPlay(station, stations) }
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
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = appFontFamily, color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Stations, artists, actors, languages",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 16.sp,
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
internal fun RadioStationRow(
    station: RadioStation,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    favorite: Boolean = false
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
        if (favorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = "Favorite station",
                tint = Color(0xFFFF4081),
                modifier = Modifier.size(16.dp)
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
            text = if (searching) "Try another category or search by name." else "Check your connection and try again.",
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
