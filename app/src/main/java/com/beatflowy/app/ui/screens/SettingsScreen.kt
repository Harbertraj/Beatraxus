package com.beatflowy.app.ui.screens

import java.util.Locale
import android.graphics.Shader
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beatflowy.app.R
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.model.DownloadQuality
import com.beatflowy.app.model.FilenameTemplate
import com.beatflowy.app.repository.DriveAccount
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.viewmodel.PlayerViewModel
import com.beatflowy.app.viewmodel.QobuzDownloadViewModel

private val PremiumAccent = Color(0xFF00F2FF)
private val DownloadAccent = Color(0xFF00F2FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    downloadViewModel: QobuzDownloadViewModel = viewModel(factory = QobuzDownloadViewModel.Factory),
    onBack: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onRequestGDriveAccount: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showInfoPopup by remember { mutableStateOf(false) }
    var currentSection by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (currentSection != null) currentSection = null else onBack()
    }

    val blurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.DECAL)
        } else null
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.currentSong?.albumArtUri != null) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uiState.currentSong?.albumArtUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 100.dp else 0.dp)
                        .graphicsLayer {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                renderEffect = blurEffect?.asComposeRenderEffect()
                            }
                            alpha = 0.1f
                        },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "SETTINGS",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = if (currentSection == null) 22.sp else 16.sp,
                                letterSpacing = 2.sp
                            )
                            currentSection?.let { section ->
                                Text(
                                    text = section.uppercase(Locale.getDefault()),
                                    color = Color.White.copy(0.6f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentSection != null) currentSection = null else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                )
            }
        ) { padding ->
            Crossfade(
                targetState = currentSection,
                modifier = Modifier.padding(padding),
                label = "settings_transition"
            ) { section ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (section == null) {
                        SettingMenuItem(
                            title = "Audio Engine",
                            subtitle = "Configure output, sample rates and DVC",
                            icon = Icons.Rounded.GraphicEq,
                            iconColor = Color(0xFF4CAF50),
                            onClick = { currentSection = "Audio Engine" }
                        )
                        SettingMenuItem(
                            title = "DSP Enhancements",
                            subtitle = "Peak limiter and Crossfeed",
                            icon = Icons.Rounded.Tune,
                            iconColor = Color(0xFFFF9800),
                            onClick = { currentSection = "DSP Enhancements" }
                        )
                        SettingMenuItem(
                            title = "Replay Gain",
                            subtitle = "Normalize volume across tracks",
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            iconColor = Color(0xFF2196F3),
                            onClick = { currentSection = "Replay Gain" }
                        )
                        SettingMenuItem(
                            title = "Library",
                            subtitle = "Manage music folders and scanning",
                            icon = Icons.Rounded.AudioFile,
                            iconColor = Color(0xFFE91E63),
                            onClick = { currentSection = "Library" }
                        )
                        SettingMenuItem(
                            title = "Cloud Account",
                            subtitle = "Stream music from Cloud and Telegram",
                            icon = Icons.Rounded.Cloud,
                            iconColor = Color(0xFF1A73E8),
                            onClick = { currentSection = "Cloud" }
                        )
                        SettingMenuItem(
                            title = "Downloads",
                            subtitle = "Lucida services, quality, format & storage",
                            icon = Icons.Rounded.Download,
                            iconColor = Color(0xFF00F2FF),
                            onClick = { currentSection = "Downloads" }
                        )
                        SettingMenuItem(
                            title = "About",
                            subtitle = "App version and information",
                            icon = Icons.Rounded.Info,
                            iconColor = Color(0xFF9C27B0),
                            onClick = { currentSection = "About" }
                        )
                    } else {
                        when (section) {
                            "Audio Engine" -> AudioEngineContent(uiState, viewModel)
                            "DSP Enhancements" -> DspEnhancementsContent(uiState, viewModel)
                            "Replay Gain" -> ReplayGainContent(uiState, viewModel)
                            "Library" -> LibraryContent(uiState, viewModel, onShowInfo = { showInfoPopup = true })
                            "Cloud" -> CloudContent(viewModel, onRequestGDriveAccount = onRequestGDriveAccount)
                            "Downloads" -> DownloadsSettingsContent(downloadViewModel)
                            "About" -> AboutContent()
                        }
                    }
                }
            }
        }

        if (uiState.isFullScanning) {
            FullScanPopup(uiState.scanProgress, uiState.scanCount, uiState.albumCount, uiState.artistCount)
        }

        if (showInfoPopup) {
            Dialog(
                onDismissRequest = { showInfoPopup = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDeep),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1A24))
                            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = PremiumAccent,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Original Quality Art",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "If you enable this, the app will store high-resolution album art which increases storage usage.",
                                color = Color.White.copy(0.8f),
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { showInfoPopup = false },
                                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Service data class ───────────────────────────────────────────────────────
private data class ServiceOption(val id: String, val label: String, val color: Color)

private val LUCIDA_SERVICES = listOf(
    ServiceOption("qobuz",      "Qobuz",        Color(0xFF00B2A9)),
    ServiceOption("tidal",      "Tidal",        Color(0xFF1DB954)),
    ServiceOption("deezer",     "Deezer",       Color(0xFFE2224D)),
    ServiceOption("soundcloud", "SoundCloud",   Color(0xFFFF5500)),
    ServiceOption("amazon",     "Amazon Music", Color(0xFF00A8E0)),
    ServiceOption("yandex",     "Yandex Music", Color(0xFFFFCC00)),
)

// ─── Audio format options ─────────────────────────────────────────────────────
private data class FormatOption(val id: String, val label: String, val sublabel: String)

private val AUDIO_FORMATS = listOf(
    FormatOption("flac",     "FLAC",         "Lossless · up to 24-bit"),
    FormatOption("mp3_320",  "MP3 320",      "Lossy · 320 kbps"),
    FormatOption("mp3_128",  "MP3 128",      "Lossy · 128 kbps"),
    FormatOption("wav",      "WAV",          "Lossless · uncompressed"),
    FormatOption("ogg",      "OGG",          "Lossy · Vorbis"),
    FormatOption("m4a",      "M4A / AAC",    "Lossy · AAC"),
    FormatOption("opus",     "OPUS",         "Lossy · modern codec"),
)

@Composable
fun DownloadsSettingsContent(viewModel: QobuzDownloadViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("beatraxus_dl", android.content.Context.MODE_PRIVATE)
    }

    // ── Local prefs state ─────────────────────────────────────────────────────
    var defaultService by remember {
        mutableStateOf(prefs.getString("default_service", "qobuz") ?: "qobuz")
    }
    var audioFormat by remember {
        mutableStateOf(prefs.getString("audio_format", "flac") ?: "flac")
    }
    var embedMetadata by remember {
        mutableStateOf(prefs.getBoolean("embed_metadata", true))
    }
    var privateDownloads by remember {
        mutableStateOf(prefs.getBoolean("private_downloads", false))
    }
    var createAlbumSubfolders by remember {
        mutableStateOf(uiState.downloadSettings.createAlbumSubfolders)
    }
    var overwriteExisting by remember {
        mutableStateOf(uiState.downloadSettings.overwriteExisting)
    }
    var concurrentDownloads by remember {
        mutableStateOf(prefs.getInt("concurrent_downloads", 1).toFloat())
    }
    var filenameTemplate by remember {
        mutableStateOf(uiState.downloadSettings.filenameTemplate)
    }
    var country by remember {
        mutableStateOf(prefs.getString("country", "auto") ?: "auto")
    }
    var showFormatExpanded by remember { mutableStateOf(false) }

    // SAF folder picker
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadLocation(uri.toString())
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {

        // ── 1. Default Service ────────────────────────────────────────────────
        DlSection(
            title = "DEFAULT SERVICE",
            icon = Icons.Rounded.Public
        ) {
            Text(
                "Search and download from this service by default in the Downloader.",
                color = Color.White.copy(0.55f),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            LUCIDA_SERVICES.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { svc ->
                        val isSelected = defaultService == svc.id
                        val bg by animateColorAsState(
                            if (isSelected) svc.color.copy(0.22f) else Color.White.copy(0.05f),
                            animationSpec = tween(200), label = "svc_bg"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(bg)
                                .border(
                                    1.dp,
                                    if (isSelected) svc.color else Color.White.copy(0.08f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    defaultService = svc.id
                                    prefs.edit().putString("default_service", svc.id).apply()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                svc.label,
                                color = if (isSelected) Color.White else Color.White.copy(0.55f),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                    // fill empty slots in last row
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── 2. Audio Format ───────────────────────────────────────────────────
        DlSection(
            title = "AUDIO FORMAT",
            icon = Icons.Rounded.AudioFile
        ) {
            // Selected format badge
            val selectedFmt = AUDIO_FORMATS.find { it.id == audioFormat }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DownloadAccent.copy(0.1f))
                    .border(1.dp, DownloadAccent.copy(0.3f), RoundedCornerShape(14.dp))
                    .clickable { showFormatExpanded = !showFormatExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        selectedFmt?.label ?: audioFormat.uppercase(),
                        color = DownloadAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        selectedFmt?.sublabel ?: "",
                        color = Color.White.copy(0.5f),
                        fontSize = 12.sp
                    )
                }
                Icon(
                    if (showFormatExpanded) Icons.Rounded.KeyboardArrowUp
                    else Icons.Rounded.KeyboardArrowDown,
                    null, tint = DownloadAccent
                )
            }

            AnimatedVisibility(
                visible = showFormatExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AUDIO_FORMATS.forEach { fmt ->
                        val isSelected = audioFormat == fmt.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) DownloadAccent.copy(0.1f)
                                    else Color.White.copy(0.03f)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) DownloadAccent.copy(0.4f)
                                    else Color.White.copy(0.05f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    audioFormat = fmt.id
                                    prefs.edit().putString("audio_format", fmt.id).apply()
                                    showFormatExpanded = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(fmt.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(fmt.sublabel, color = Color.White.copy(0.45f), fontSize = 12.sp)
                            }
                            if (isSelected) {
                                Icon(Icons.Rounded.Check, null,
                                    tint = DownloadAccent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // ── 3. Download Location ──────────────────────────────────────────────
        DlSection(
            title = "STORAGE",
            icon = Icons.Rounded.FolderOpen
        ) {
            val location = uiState.downloadSettings.downloadLocation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(0.05f))
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(14.dp))
                    .clickable { folderPicker.launch(null) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download Location", color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        location?.let { android.net.Uri.parse(it).lastPathSegment ?: it }
                            ?: "Not set — tap to choose",
                        color = if (location != null) DownloadAccent else Color.White.copy(0.4f),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    null, tint = Color.White.copy(0.4f))
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Files are saved under Music/Beatraxus/ by default. " +
                "Choose a custom folder to override.",
                color = Color.White.copy(0.4f),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        // ── 4. Filename & Folder Options ──────────────────────────────────────
        DlSection(
            title = "FILE ORGANISATION",
            icon = Icons.Rounded.DriveFileMove
        ) {
            // Filename template
            Text(
                "Filename Template",
                color = Color.White.copy(0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            FilenameTemplate.entries.forEach { tmpl ->
                val isSelected = filenameTemplate == tmpl
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) DownloadAccent.copy(0.1f) else Color.White.copy(0.03f)
                        )
                        .border(
                            1.dp,
                            if (isSelected) DownloadAccent.copy(0.35f) else Color.White.copy(0.05f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            filenameTemplate = tmpl
                            viewModel.setFilenameTemplate(tmpl)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            tmpl.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            when (tmpl) {
                                FilenameTemplate.ARTIST_TITLE -> "Artist - Title.flac"
                                FilenameTemplate.TITLE_ARTIST -> "Title - Artist.flac"
                                else -> "${tmpl.name}.flac"
                            },
                            color = Color.White.copy(0.4f),
                            fontSize = 11.sp
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, null,
                            tint = DownloadAccent, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(4.dp))

            DlToggleRow(
                title = "Album Subfolders",
                subtitle = "Group tracks in Artist/Album/ folders",
                checked = createAlbumSubfolders,
                onCheckedChange = {
                    createAlbumSubfolders = it
                    viewModel.setCreateAlbumSubfolders(it)
                }
            )

            DlDivider()

            DlToggleRow(
                title = "Overwrite Existing",
                subtitle = "Replace files if they already exist",
                checked = overwriteExisting,
                onCheckedChange = {
                    overwriteExisting = it
                    viewModel.setOverwriteExisting(it)
                }
            )
        }

        // ── 5. Metadata & Privacy ─────────────────────────────────────────────
        DlSection(
            title = "METADATA & PRIVACY",
            icon = Icons.Rounded.Tag
        ) {
            DlToggleRow(
                title = "Embed Metadata",
                subtitle = "Embed title, artist, album art into files",
                checked = embedMetadata,
                onCheckedChange = {
                    embedMetadata = it
                    prefs.edit().putBoolean("embed_metadata", it).apply()
                }
            )

            DlDivider()

            DlToggleRow(
                title = "Private Downloads",
                subtitle = "Hide from Lucida's recent downloads list",
                checked = privateDownloads,
                onCheckedChange = {
                    privateDownloads = it
                    prefs.edit().putBoolean("private_downloads", it).apply()
                }
            )
        }

        // ── 6. Performance ────────────────────────────────────────────────────
        DlSection(
            title = "PERFORMANCE",
            icon = Icons.Rounded.Speed
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Concurrent Downloads",
                    color = Color.White.copy(0.85f), fontSize = 13.sp)
                Text(
                    "${concurrentDownloads.toInt()} track${if (concurrentDownloads > 1) "s" else ""}",
                    color = DownloadAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = concurrentDownloads,
                onValueChange = {
                    concurrentDownloads = it
                    prefs.edit().putInt("concurrent_downloads", it.toInt()).apply()
                },
                valueRange = 1f..4f,
                steps = 2,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = DownloadAccent,
                    inactiveTrackColor = Color.White.copy(0.12f),
                    thumbColor = DownloadAccent
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1", color = Color.White.copy(0.3f), fontSize = 11.sp)
                Text("4", color = Color.White.copy(0.3f), fontSize = 11.sp)
            }

            Spacer(Modifier.height(4.dp))

            // Country selector
            Text(
                "Account Region",
                color = Color.White.copy(0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
            )
            val regions = listOf("auto", "us", "gb", "de", "fr", "jp", "au", "ca")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                regions.forEach { region ->
                    val isSelected = country == region
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isSelected) DownloadAccent.copy(0.18f)
                                else Color.White.copy(0.05f)
                            )
                            .border(
                                1.dp,
                                if (isSelected) DownloadAccent else Color.White.copy(0.08f),
                                RoundedCornerShape(50)
                            )
                            .clickable {
                                country = region
                                prefs.edit().putString("country", region).apply()
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            region.uppercase(),
                            color = if (isSelected) DownloadAccent else Color.White.copy(0.5f),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Text(
                "Selects which regional accounts Lucida uses to fulfil your request.",
                color = Color.White.copy(0.35f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ── 7. About Lucida ───────────────────────────────────────────────────
        DlSection(
            title = "ABOUT LUCIDA",
            icon = Icons.Rounded.Info
        ) {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Beatraxus's Downloader is powered by lucida.to, a free service that " +
                    "provides high-quality music downloads from Qobuz, Tidal, Deezer, " +
                    "SoundCloud, Amazon Music, and Yandex Music.",
                    color = Color.White.copy(0.55f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.04f))
                        .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(12.dp))
                        .clickable { uriHandler.openUri("https://lucida.to/faq") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lucida FAQ & Supported Services",
                        color = DownloadAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.OpenInNew, null,
                        tint = DownloadAccent, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SettingMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun AudioEngineContent(uiState: com.beatflowy.app.model.PlayerUiState, viewModel: com.beatflowy.app.viewmodel.PlayerViewModel) {
    val sampleFormats = com.beatflowy.app.model.SampleFormat.entries

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Output Method",
            color = Color.White.copy(0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputModeButton(
                text = "AAudio",
                selected = uiState.outputMode == OutputMode.AAUDIO.name,
                onClick = { viewModel.setOutputMode(OutputMode.AAUDIO) },
                modifier = Modifier.weight(1f)
            )
            OutputModeButton(
                text = "MTK HiFi",
                selected = uiState.outputMode == OutputMode.HI_RES.name,
                onClick = { viewModel.setOutputMode(OutputMode.HI_RES) },
                enabled = uiState.hiResDirectSupported,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = uiState.hiResCapabilitySummary,
            color = if (uiState.hiResDirectSupported) PremiumAccent else Color.White.copy(0.5f),
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                val activeMode = OutputMode.fromName(uiState.outputMode)
                Text(activeMode.title, color = Color.White, fontSize = 16.sp)
                Text(activeMode.subtitle, color = Color.White.copy(0.5f), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        DspToggleRow("High-Quality Resampler", uiState.dsp.config.highQualityResampler) {
            viewModel.setHighQualityResampler(it)
        }
        
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Target Sample Rate", color = Color.White.copy(0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResamplerMode.entries.forEach { mode ->
                    val isSelected = uiState.dsp.config.resamplerMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setResamplerMode(mode) },
                        enabled = uiState.dsp.config.highQualityResampler,
                        label = { Text(mode.displayName, color = if (isSelected) Color.Black else if (uiState.dsp.config.highQualityResampler) Color.White else Color.White.copy(0.3f)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PremiumAccent,
                            containerColor = Color.White.copy(0.05f),
                            disabledContainerColor = Color.White.copy(0.02f)
                        ),
                        border = null
                    )
                }
            }
        }

        DspSliderRow(
            title = "Cutoff Ratio",
            value = uiState.dsp.config.resamplerCutoffRatio,
            range = 0.5f..1.0f,
            enabled = uiState.dsp.config.highQualityResampler,
            valueText = { "${(it * 100).toInt()}%" },
            onValueChange = viewModel::setResamplerCutoffRatio
        )
        
        Spacer(Modifier.height(8.dp))
        
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Target Sample Format", color = Color.White.copy(0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sampleFormats.forEach { format ->
                    val isSelected = uiState.dsp.config.sampleFormat == format
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setSampleFormat(format) },
                        label = { Text(format.displayName, color = if (isSelected) Color.Black else Color.White) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PremiumAccent,
                            containerColor = Color.White.copy(0.05f)
                        ),
                        border = null
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        DspToggleRow("Direct Volume Control", uiState.dsp.config.dvcEnabled) {
            viewModel.setDvcEnabled(it)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DvcMode.entries.forEach { mode ->
                val isSelected = uiState.dsp.config.dvcMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = { if (uiState.dsp.config.dvcEnabled) viewModel.setDvcMode(mode) },
                    enabled = uiState.dsp.config.dvcEnabled,
                    label = { Text(mode.displayName, color = if (isSelected) Color.Black else Color.White) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PremiumAccent,
                        containerColor = Color.White.copy(0.05f)
                    ),
                    border = null
                )
            }
        }
        DspSliderRow(
            title = "DVC Level",
            value = uiState.dsp.config.dvcLevel,
            range = 0f..1f,
            enabled = uiState.dsp.config.dvcEnabled,
            valueText = { "${(it * 100).toInt()}%" },
            onValueChange = viewModel::setDvcLevel
        )
        Spacer(Modifier.height(12.dp))
        DspToggleRow("DC Offset Blocker", uiState.dsp.config.dcBlockerEnabled) {
            viewModel.setDcBlockerEnabled(it)
        }
    }
}

@Composable
fun DspEnhancementsContent(uiState: com.beatflowy.app.model.PlayerUiState, viewModel: com.beatflowy.app.viewmodel.PlayerViewModel) {
    val config = uiState.dsp.config
    Column(modifier = Modifier.fillMaxWidth()) {
        DspToggleRow("Peak Limiter", config.limiterEnabled) {
            viewModel.setLimiterEnabled(it)
        }
        Spacer(Modifier.height(12.dp))
        DspToggleRow("Crossfeed", config.crossfeedEnabled) {
            viewModel.setCrossfeedEnabled(it)
        }
        if (config.crossfeedEnabled) {
            DspSliderRow(
                title = "Crossfeed Level",
                value = config.crossfeedLevel,
                range = 0f..1f,
                enabled = true,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setCrossfeedLevel
            )
        }
    }
}

@Composable
fun ReplayGainContent(uiState: com.beatflowy.app.model.PlayerUiState, viewModel: com.beatflowy.app.viewmodel.PlayerViewModel) {
    val config = uiState.dsp.config
    Column(modifier = Modifier.fillMaxWidth()) {
        DspToggleRow("Enable Replay Gain", config.replayGainEnabled) {
            viewModel.setReplayGainEnabled(it)
        }
        
        if (config.replayGainEnabled) {
            Spacer(Modifier.height(12.dp))
            
            Text("Processing Mode", color = Color.White.copy(0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.beatflowy.app.model.ReplayGainOption.entries.forEach { option ->
                    val isSelected = config.replayGainOption == option
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setReplayGainOption(option) },
                        label = { Text(option.displayName, color = if (isSelected) Color.Black else Color.White) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PremiumAccent,
                            containerColor = Color.White.copy(0.05f)
                        ),
                        border = null
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Source", color = Color.White.copy(0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.beatflowy.app.model.ReplayGainSource.entries.forEach { source ->
                    val isSelected = config.replayGainSource == source
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setReplayGainSource(source) },
                        label = { Text(source.displayName, color = if (isSelected) Color.Black else Color.White) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PremiumAccent,
                            containerColor = Color.White.copy(0.05f)
                        ),
                        border = null
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            DspSliderRow(
                title = "Pre-amplification",
                value = config.replayGainPreamp,
                range = -15f..15f,
                enabled = true,
                valueText = { String.format(Locale.getDefault(), "%.1f dB", it) },
                onValueChange = viewModel::setReplayGainPreamp
            )
        }
    }
}

@Composable
fun LibraryContent(uiState: com.beatflowy.app.model.PlayerUiState, viewModel: com.beatflowy.app.viewmodel.PlayerViewModel, onShowInfo: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { viewModel.startFullScan() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Full Rescan Library", color = Color.White)
        }
        
        Spacer(Modifier.height(12.dp))
        
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.quickScan() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Quick Scan", color = Color.White)
            }
            
            if (uiState.isLoadingLibrary) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(horizontal = 4.dp),
                    color = PremiumAccent,
                    trackColor = Color.Transparent
                )
            }
        }
        
                val error = uiState.errorMessage
                if (error != null) {
                    Text(
                        error,
                        color = if (error.contains("failed", ignoreCase = true)) Color.Red else PremiumAccent,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Original Quality Album Art", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onShowInfo,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = "Info",
                        tint = Color.White.copy(0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Switch(
                checked = uiState.useOriginalQualityArt,
                onCheckedChange = { viewModel.setUseOriginalQualityArt(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PremiumAccent,
                    checkedTrackColor = PremiumAccent.copy(0.3f),
                    uncheckedThumbColor = Color.White.copy(0.5f),
                    uncheckedTrackColor = Color.White.copy(0.1f)
                )
            )
        }

        Spacer(Modifier.height(20.dp))

        HorizontalDivider(color = Color.White.copy(0.06f))

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Music Folders",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${uiState.musicFolders.size} folder${if (uiState.musicFolders.size != 1) "s" else ""} indexed",
                    color = Color.White.copy(0.45f),
                    fontSize = 11.sp
                )
            }
            Surface(
                onClick = { viewModel.openFolderPicker() },
                shape = RoundedCornerShape(12.dp),
                color = PremiumAccent.copy(0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.CreateNewFolder,
                        null,
                        tint = PremiumAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Add Folder", color = PremiumAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (uiState.musicFolders.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(0.03f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        null,
                        tint = Color.White.copy(0.25f),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "No folders added. Tap 'Add Folder' to select your music location.",
                        color = Color.White.copy(0.35f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.musicFolders.forEachIndexed { index, folder ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(0.04f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.07f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PremiumAccent.copy(0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    null,
                                    tint = PremiumAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.substringAfterLast("/"),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    folder,
                                    color = Color.White.copy(0.35f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { viewModel.removeMusicFolder(folder) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.RemoveCircleOutline,
                                    null,
                                    tint = Color.Red.copy(0.5f),
                                    modifier = Modifier.size(16.dp)
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
fun CloudContent(viewModel: PlayerViewModel, onRequestGDriveAccount: () -> Unit) {
    val driveAccounts by viewModel.driveAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val telegramChannels by viewModel.telegramChannels.collectAsStateWithLifecycle(initialValue = emptyList())
    
    var driveQuery by remember { mutableStateOf("") }
    var telegramUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            Text(
                "GOOGLE DRIVE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = driveQuery,
                        onValueChange = { driveQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search or add account...", color = Color.Gray, fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Cloud, null, tint = Color(0xFF1A73E8), modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            TextButton(onClick = onRequestGDriveAccount) {
                                Text("Add", color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1A73E8).copy(0.5f),
                            unfocusedBorderColor = Color.White.copy(0.1f),
                            focusedContainerColor = Color.Black.copy(0.2f),
                            unfocusedContainerColor = Color.Black.copy(0.2f),
                            cursorColor = Color(0xFF1A73E8)
                        ),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp)
                    )

                    val filteredDrive = driveAccounts.filter { 
                        it.email.contains(driveQuery, ignoreCase = true) || it.accountName.contains(driveQuery, ignoreCase = true) 
                    }
                    
                    if (filteredDrive.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        filteredDrive.forEach { account ->
                            ConnectedAccountRow(
                                account = account,
                                onScan = { viewModel.scanDriveAccount(account.email) },
                                onToggle = { enabled -> viewModel.toggleDriveAccountEnabled(account.email, enabled) },
                                onRemove = { viewModel.removeDriveAccount(account.email) }
                            )
                        }
                    }
                }
            }
        }

        Column {
            Text(
                "TELEGRAM CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = telegramUrl,
                        onValueChange = { telegramUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Channel URL or @username...", color = Color.Gray, fontSize = 14.sp) },
                        leadingIcon = { 
                            Box(
                                modifier = Modifier.size(20.dp).background(Color(0xFF2AABEE), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        },
                        trailingIcon = {
                            TextButton(
                                onClick = { 
                                    if (telegramUrl.isNotBlank()) {
                                        viewModel.addTelegramChannel(telegramUrl)
                                        telegramUrl = ""
                                    }
                                }
                            ) {
                                Text(if (telegramChannels.any { it.url.contains(telegramUrl, true) || it.name.contains(telegramUrl, true) }) "Search" else "Join", 
                                     color = Color(0xFF2AABEE), fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF2AABEE).copy(0.5f),
                            unfocusedBorderColor = Color.White.copy(0.1f),
                            focusedContainerColor = Color.Black.copy(0.2f),
                            unfocusedContainerColor = Color.Black.copy(0.2f),
                            cursorColor = Color(0xFF2AABEE)
                        ),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp)
                    )

                    val filteredTelegram = telegramChannels.filter {
                        it.name.contains(telegramUrl, ignoreCase = true) || it.url.contains(telegramUrl, ignoreCase = true)
                    }

                    if (filteredTelegram.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        filteredTelegram.forEach { channel ->
                            TelegramChannelRow(
                                channel = channel,
                                onSync = { viewModel.syncTelegramChannel(channel.url) },
                                onToggle = { enabled -> viewModel.toggleTelegramChannelEnabled(channel.url, enabled) },
                                onRemove = { viewModel.removeTelegramChannel(channel.url) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TelegramChannelRow(
    channel: com.beatflowy.app.model.TelegramChannel,
    onSync: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { },
                onLongClick = { showDelete = !showDelete }
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF2AABEE).copy(0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = channel.name.take(1).uppercase(),
                color = Color(0xFF2AABEE),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(Color(0xFF1A1A2E), CircleShape)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (channel.enabled) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                )
            }
        }
        
        Spacer(Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("@${channel.url.substringAfterLast("/")}", color = Color.White.copy(0.5f), fontSize = 12.sp)
        }
        
        if (showDelete) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, "Remove", tint = Color.Red.copy(0.8f))
            }
        } else {
            IconButton(onClick = onSync) {
                Icon(Icons.Rounded.Sync, "Sync", tint = Color.White.copy(0.6f))
            }
            Switch(
                checked = channel.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF2AABEE),
                    checkedTrackColor = Color(0xFF2AABEE).copy(0.3f)
                )
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConnectedAccountRow(
    account: DriveAccount,
    onScan: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { },
                onLongClick = { showDelete = !showDelete }
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF1A73E8).copy(0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = Color(0xFF1A73E8), modifier = Modifier.size(24.dp))
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(Color(0xFF1A1A2E), CircleShape)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (account.enabled) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                )
            }
        }
        
        Spacer(Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(account.accountName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(account.email, color = Color.White.copy(0.5f), fontSize = 12.sp)
        }
        
        if (showDelete) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, "Remove", tint = Color.Red.copy(0.8f))
            }
        } else {
            IconButton(onClick = onScan) {
                Icon(Icons.Rounded.Sync, "Sync", tint = Color.White.copy(0.6f))
            }
            Switch(
                checked = account.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A73E8),
                    checkedTrackColor = Color(0xFF1A73E8).copy(0.3f)
                )
            )
        }
    }
}

@Composable
fun AboutContent() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        "Unknown"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Beatraxus Music player", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("HarbertRaj", color = Color.White.copy(0.6f), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(0.15f)
                ) {
                    Text(
                        "v$versionName",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            IconButton(
                onClick = { uriHandler.openUri("https://github.com/Harbertraj/Beatraxus") },
                modifier = Modifier.align(Alignment.Top)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = "GitHub",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun FullScanPopup(progress: Float, count: Int, albums: Int, artists: Int) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDeep),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(max = 300.dp)
                    .shadow(
                        elevation = 28.dp, 
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = Color(0xFF00F2FF).copy(0.15f),
                        spotColor = Color(0xFF00F2FF).copy(0.2f)
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF131B2A), Color(0xFF0A0E18))
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color(0xFF00F2FF).copy(0.3f), Color.White.copy(0.05f))
                        ),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Syncing Music",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(Modifier.height(32.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ScanStatItem(Icons.Rounded.MusicNote, count.toString(), "Songs", Color(0xFFFF4081))
                        ScanStatItem(Icons.Rounded.Album, albums.toString(), "Albums", Color(0xFFB2FF59))
                        ScanStatItem(Icons.Rounded.Person, artists.toString(), "Artists", Color(0xFF7C4DFF))
                    }
                    
                    Spacer(Modifier.height(40.dp))
                    
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = PremiumAccent,
                        trackColor = Color.White.copy(0.1f)
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "${(progress * 100).toInt()}%",
                        color = PremiumAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ScanStatItem(icon: ImageVector, value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = Color.White.copy(0.5f), fontSize = 11.sp)
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF00F2FF), Color(0xFF0066FF))
                        )
                    )
            )
            Spacer(Modifier.width(10.dp))
            Icon(icon, null, tint = PremiumAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                letterSpacing = 0.8.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF131B2A).copy(alpha = 0.92f),
                            Color(0xFF0C1018).copy(alpha = 0.95f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xFF00F2FF).copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.04f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
fun OutputModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White else Color.White.copy(0.1f),
            contentColor = if (selected) Color.Black else Color.White
        ),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun DspToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PremiumAccent,
                checkedTrackColor = PremiumAccent.copy(alpha = 0.32f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.14f)
            )
        )
    }
}

@Composable
private fun DspSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    steps: Int = 0,
    valueText: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = Color.White.copy(alpha = if (enabled) 0.85f else 0.45f), fontSize = 13.sp)
            Text(valueText(value), color = PremiumAccent.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                activeTrackColor = PremiumAccent,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f),
                thumbColor = PremiumAccent,
                disabledActiveTrackColor = PremiumAccent.copy(alpha = 0.22f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.08f),
                disabledThumbColor = PremiumAccent.copy(alpha = 0.32f)
            )
        )
    }
}

@Composable
private fun DlSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp).height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(
                        listOf(DownloadAccent, Color(0xFF0066FF))
                    ))
            )
            Spacer(Modifier.width(10.dp))
            Icon(icon, null, tint = DownloadAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }
        // Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(
                    listOf(Color(0xFF131B2A).copy(0.92f), Color(0xFF0C1018).copy(0.95f))
                ))
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(DownloadAccent.copy(0.18f), Color.White.copy(0.04f))
                    ),
                    RoundedCornerShape(20.dp)
                )
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun DlToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(0.45f), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DownloadAccent,
                checkedTrackColor = DownloadAccent.copy(0.28f),
                uncheckedThumbColor = Color.White.copy(0.6f),
                uncheckedTrackColor = Color.White.copy(0.12f)
            )
        )
    }
}

@Composable
private fun DlDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(1.dp)
            .background(Color.White.copy(0.06f))
    )
}
