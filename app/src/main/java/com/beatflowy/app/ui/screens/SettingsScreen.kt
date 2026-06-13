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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.beatflowy.app.ui.components.PremiumSwitch
import com.beatflowy.app.ui.components.glassIconBackground
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.model.DownloadQuality
import com.beatflowy.app.model.FilenameTemplate
import com.beatflowy.app.model.SoxrQuality
import com.beatflowy.app.model.DitherType
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.SoxrQuality as SoxrQualityEnum
import com.beatflowy.app.repository.DriveAccount
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.viewmodel.PlayerViewModel
import com.beatflowy.app.viewmodel.QobuzDownloadViewModel

private val PremiumAccent = Color(0xFF00F2FF)
private val DownloadAccent = Color(0xFF00F2FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    downloadViewModel: QobuzDownloadViewModel = viewModel(factory = QobuzDownloadViewModel.Factory),
    onBack: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onRequestGDriveAccount: () -> Unit
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    var showInfoPopup by remember { mutableStateOf(false) }
    var currentSection by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf<EditingValue?>(null) }

    var lastBackClickTime by remember { mutableStateOf(0L) }
    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackClickTime < 500) return@BackHandler
        lastBackClickTime = currentTime

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
                        var lastClickTime by remember { mutableStateOf(0L) }
                        IconButton(onClick = {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastClickTime < 500) return@IconButton
                            lastClickTime = currentTime

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
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if (section == null) {
                        SettingMenuItem(
                            title = "Audio Engine",
                            subtitle = "Configure output, sample rates, Limiter, Resampler, Dither...",
                            icon = Icons.Rounded.GraphicEq,
                            iconColor = Color(0xFF4CAF50),
                            onClick = { currentSection = "Audio Engine" }
                        )
                        SettingMenuItem(
                            title = "DSP Enhancements",
                            subtitle = "USB Direct, Bit-Perfect and DVC",
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
                            title = "Cloud Account (Admin Only)",
                            subtitle = "Stream music from Cloud and Telegram",
                            icon = Icons.Rounded.Cloud,
                            iconColor = Color(0xFF1A73E8),
                            onClick = { currentSection = "Cloud" }
                        )
                        SettingMenuItem(
                            title = "Metadata Sync",
                            subtitle = "Enrichment rules, network and data saver",
                            icon = Icons.Rounded.Sync,
                            iconColor = Color(0xFF00BCD4),
                            onClick = { currentSection = "Metadata Sync" }
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
                            "Audio Engine" -> AudioEngineContent(uiState, playerViewModel, onEditValue = { editingValue = it })
                            "DSP Enhancements" -> DspEnhancementsContent(uiState, playerViewModel, onEditValue = { editingValue = it })
                            "Replay Gain" -> ReplayGainContent(uiState, playerViewModel)
                            "Library" -> LibraryContent(uiState, playerViewModel, onShowInfo = { showInfoPopup = true })
                            "Cloud" -> CloudContent(playerViewModel, onRequestGDriveAccount = onRequestGDriveAccount)
                            "Metadata Sync" -> MetadataSyncContent(uiState, playerViewModel)
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
                        .background(Color.Black.copy(0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .shadow(
                                elevation = 32.dp,
                                shape = RoundedCornerShape(24.dp),
                                ambientColor = PremiumAccent.copy(0.2f),
                                spotColor = PremiumAccent.copy(0.25f)
                            )
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF141E30), Color(0xFF0C1018))
                                )
                            )
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(PremiumAccent.copy(0.35f), Color.White.copy(0.05f))
                                ),
                                RoundedCornerShape(24.dp)
                            )
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .shadow(12.dp, CircleShape, ambientColor = PremiumAccent.copy(0.3f), spotColor = PremiumAccent.copy(0.3f))
                                    .clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.22f), Color.Transparent))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = PremiumAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                "Original Quality Art",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "If you enable this, the app will store high-resolution album art which increases storage usage.",
                                color = Color.White.copy(0.65f),
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(PremiumAccent.copy(0.9f), Color(0xFF0066FF).copy(0.85f))
                                        )
                                    )
                                    .clickable { showInfoPopup = false }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        editingValue?.let { ev ->
            ValueEditDialog(
                initialValue = ev.value,
                range = ev.range,
                label = ev.label,
                onDismiss = { editingValue = null },
                onConfirm = ev.onConfirm
            )
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

data class EditingValue(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val onConfirm: (Float) -> Unit
)

@Composable
private fun ValueEditDialog(
    initialValue: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var textValue by remember { mutableStateOf("%.1f".format(initialValue)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A24),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Edit $label", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter value (${range.start} to ${range.endInclusive})", fontSize = 12.sp, color = Color.White.copy(0.6f))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { 
                        textValue.replace(",", ".").toFloatOrNull()?.let { onConfirm(it.coerceIn(range)) }
                        onDismiss()
                    }),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PremiumAccent,
                        unfocusedBorderColor = Color.White.copy(0.1f),
                        cursorColor = PremiumAccent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    textValue.replace(",", ".").toFloatOrNull()?.let { onConfirm(it.coerceIn(range)) }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent, contentColor = Color.Black)
            ) { Text("SET VALUE", color = Color.Black) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(0.6f)) }
        }
    )
}

@Composable
fun SettingMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgAlpha by animateFloatAsState(if (isPressed) 0.10f else 0.045f, label = "bg")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = bgAlpha))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        iconColor.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon with colored glass background
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .glassIconBackground(
                    backgroundColor = iconColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(14.dp),
                    borderColor = iconColor.copy(alpha = 0.18f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.1.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(28.dp)
                .glassIconBackground(
                    backgroundColor = Color.White.copy(alpha = 0.06f),
                    shape = CircleShape,
                    borderColor = Color.White.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun AudioEngineContent(
    uiState: PlayerUiState,
    playerViewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val sampleFormats = com.beatflowy.app.model.SampleFormat.entries

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. Output Configuration ──────────────────────────────────────────
        SettingsSection(
            title = "OUTPUT CONFIGURATION",
            icon = Icons.Rounded.SettingsInputComponent,
            isActive = true
        ) {
            Text(
                "Output Method",
                color = Color.White.copy(0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputModeButton(
                    text = "AAudio",
                    selected = uiState.outputMode == OutputMode.AAUDIO.name,
                    onClick = { playerViewModel.setOutputMode(OutputMode.AAUDIO) },
                    modifier = Modifier.weight(1f)
                )
                OutputModeButton(
                    text = "MTK HiFi",
                    selected = uiState.outputMode == OutputMode.HI_RES.name,
                    onClick = { playerViewModel.setOutputMode(OutputMode.HI_RES) },
                    enabled = uiState.hiResDirectSupported,
                    modifier = Modifier.weight(1f)
                )
                OutputModeButton(
                    text = "MMAP",
                    selected = uiState.outputMode == OutputMode.MMAP_EXCLUSIVE.name,
                    onClick = { playerViewModel.setOutputMode(OutputMode.MMAP_EXCLUSIVE) },
                    enabled = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            val activeMode = OutputMode.fromName(uiState.outputMode)
            Text(activeMode.title, color = PremiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(activeMode.subtitle, color = Color.White.copy(0.5f), fontSize = 12.sp, lineHeight = 16.sp)

            if (activeMode == OutputMode.MMAP_EXCLUSIVE) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "MMAP buffer size (frames):",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(0.6f)
                )

                val bufferOptions = listOf(64, 96, 128, 192, 256)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bufferOptions.forEach { frames ->
                        val isSelected = uiState.dsp.config.mmapRequestedBufferSizeFrames == frames
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) PremiumAccent else Color.White.copy(0.05f))
                                .clickable { playerViewModel.setMmapBufferSize(frames) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                frames.toString(),
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(0.05f))
            Spacer(Modifier.height(12.dp))

            Text(
                text = uiState.hiResCapabilitySummary,
                color = if (uiState.hiResDirectSupported) Color(0xFF00FF88).copy(0.8f) else Color.White.copy(0.4f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // ── 2. Resampling ────────────────────────────────────────────────────
        val isResamplerBypassed = uiState.dsp.config.bitPerfectEnabled
        SettingsSection(
            title = "RESAMPLING",
            icon = Icons.Rounded.Speed,
            isActive = uiState.dsp.config.highQualityResampler && !isResamplerBypassed,
            headerActions = {
                if (isResamplerBypassed) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(0.05f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "BYPASSED",
                            color = Color.White.copy(0.3f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                } else {
                    PremiumSwitch(
                        checked = uiState.dsp.config.highQualityResampler,
                        onCheckedChange = { playerViewModel.setHighQualityResampler(it) }
                    )
                }
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Target Sample Rate", color = Color.White.copy(if (isResamplerBypassed) 0.3f else 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ResamplerMode.entries.forEach { mode ->
                        val isSelected = uiState.dsp.config.resamplerMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { playerViewModel.setResamplerMode(mode) },
                            enabled = uiState.dsp.config.highQualityResampler && !isResamplerBypassed,
                            label = { Text(mode.displayName, color = if (isSelected) Color.Black else if (uiState.dsp.config.highQualityResampler && !isResamplerBypassed) Color.White else Color.White.copy(0.3f), fontSize = 11.sp) },
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

            Spacer(Modifier.height(8.dp))

            DspSliderRow(
                title = "Cutoff Ratio",
                value = uiState.dsp.config.resamplerCutoffRatio,
                range = 0.5f..1.0f,
                enabled = uiState.dsp.config.highQualityResampler && !isResamplerBypassed,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = playerViewModel::setResamplerCutoffRatio
            )
        }

        // ── 3. Bit Depth & Format ────────────────────────────────────────────
        SettingsSection(
            title = "DATA FORMAT",
            icon = Icons.Rounded.Memory,
            isActive = true
        ) {
            Text("Target Sample Format", color = Color.White.copy(0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sampleFormats.forEach { format ->
                    val isSelected = uiState.dsp.config.sampleFormat == format
                    FilterChip(
                        selected = isSelected,
                        onClick = { playerViewModel.setSampleFormat(format) },
                        label = { Text(format.displayName, color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PremiumAccent,
                            containerColor = Color.White.copy(0.05f)
                        ),
                        border = null
                    )
                }
            }
        }

        // Moved from DSP Enhancements
        SoxrQualityCard(uiState = uiState, viewModel = playerViewModel)

        Float64Card(uiState = uiState, viewModel = playerViewModel)

        DitherCard(uiState = uiState, viewModel = playerViewModel)

        LimiterCard(uiState = uiState, viewModel = playerViewModel, onEditValue = onEditValue)

        // ── 5. System Tweaks ─────────────────────────────────────────────────
        SettingsSection(
            title = "SYSTEM TWEAKS",
            icon = Icons.Rounded.Tune,
            isActive = uiState.dsp.config.dcBlockerEnabled,
            headerActions = {
                PremiumSwitch(
                    checked = uiState.dsp.config.dcBlockerEnabled,
                    onCheckedChange = { playerViewModel.setDcBlockerEnabled(it) }
                )
            }
        ) {
            Text(
                "DC Offset Blocker prevents clicks and pops by centering the audio waveform. Recommended for most setups.",
                color = Color.White.copy(0.5f),
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun DspEnhancementsContent(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val config = uiState.dsp.config
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UsbDirectModeCard(uiState = uiState, viewModel = viewModel)

        BitPerfectCard(uiState = uiState, viewModel = viewModel)

        DvcCard(uiState = uiState, viewModel = viewModel)

        SettingsSection(
            title = "CROSSFEED",
            icon = Icons.Rounded.Headset,
            isActive = config.crossfeedEnabled,
            headerActions = {
                PremiumSwitch(
                    checked = config.crossfeedEnabled,
                    onCheckedChange = { viewModel.setCrossfeedEnabled(it) }
                )
            }
        ) {
            if (config.crossfeedEnabled) {
                DspSliderRow(
                    title = "Crossfeed Level",
                    value = config.crossfeedLevel,
                    range = 0f..1f,
                    enabled = true,
                    valueText = { "${(it * 100).toInt()}%" },
                    onValueChange = viewModel::setCrossfeedLevel
                )
            } else {
                Text(
                    "Blends left and right channels to reduce listener fatigue on headphones.",
                    color = Color.White.copy(0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun UsbDirectModeCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val isUsbConnected = uiState.outputDevice == "USB DAC"
    val isUsbActive = config.usbExclusiveEnabled && isUsbConnected

    SettingsSection(
        title = "USB DIRECT MODE",
        icon = Icons.Rounded.Usb,
        isActive = isUsbActive,
        statusDot = if (isUsbActive) PremiumAccent else if (isUsbConnected) Color(0xFFFFAA00) else Color.White.copy(0.2f),
        subtitle = when {
            isUsbActive -> "Bypassing Android mixer — direct to DAC"
            isUsbConnected -> "USB DAC detected — enable to activate"
            else -> "Connect a USB DAC to activate"
        },
        headerActions = {
            PremiumSwitch(
                checked = config.usbExclusiveEnabled,
                onCheckedChange = { viewModel.setUsbExclusiveMode(it) }
            )
        }
    ) {
        if (isUsbConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UsbInfoChip(
                    label = "DEVICE",
                    value = uiState.outputDevice,
                    modifier = Modifier.weight(1f)
                )
                UsbInfoChip(
                    label = "PATH",
                    value = if (isUsbActive) "USB Direct" else "AAudio",
                    modifier = Modifier.weight(1f),
                    highlight = isUsbActive
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UsbInfoChip(
                    label = "RATE",
                    value = "${uiState.outputSampleRate / 1000}kHz",
                    modifier = Modifier.weight(1f),
                    highlight = isUsbActive
                )
                UsbInfoChip(
                    label = "BIT DEPTH",
                    value = "${uiState.outputBitDepth}-bit",
                    modifier = Modifier.weight(1f),
                    highlight = isUsbActive
                )
            }
        }

        if (config.usbExclusiveEnabled && !isUsbConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF8800).copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = Color(0xFFFF8800),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "No USB DAC detected. Falling back to AAudio.",
                    color = Color(0xFFFF8800).copy(0.9f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun UsbInfoChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight) PremiumAccent.copy(alpha = 0.07f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                0.5.dp,
                if (highlight) PremiumAccent.copy(0.2f) else Color.White.copy(0.07f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                label,
                color = Color.White.copy(0.35f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Text(
                value,
                color = if (highlight) PremiumAccent else Color.White.copy(0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BitPerfectCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val isActive = config.bitPerfectEnabled

    SettingsSection(
        title = "BIT-PERFECT MODE",
        icon = Icons.Rounded.DoneAll,
        isActive = isActive,
        subtitle = if (isActive) "DSP bypassed — pure source signal"
        else "Bypasses all DSP processing",
        headerActions = {
            PremiumSwitch(
                checked = isActive,
                onCheckedChange = { viewModel.setBitPerfectMode(it) }
            )
        }
    ) {
        if (isActive) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatChip("RATE", "${uiState.inputSampleRate / 1000}kHz")
                    StatChip("DEPTH", "${uiState.bitDepth}-bit")
                    StatChip("DSP", "OFF")
                }
            }
        }
    }
}

@Composable
private fun UnbypassChip(
    label: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { 
            Text(
                label, 
                fontSize = 11.sp, 
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.Black else Color.White.copy(0.6f)
            ) 
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PremiumAccent,
            containerColor = Color.White.copy(0.05f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color.White.copy(0.1f),
            selectedBorderColor = PremiumAccent
        )
    )
}

@Composable
private fun DvcCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val isActive = config.dvcEnabled

    SettingsSection(
        title = "DIRECT VOLUME CONTROL (DVC)",
        icon = Icons.Rounded.VolumeUp,
        isActive = isActive,
        subtitle = if (isActive) "Hardware-level volume control active"
        else "Direct access to device volume",
        headerActions = {
            PremiumSwitch(
                checked = isActive,
                onCheckedChange = { viewModel.setDvcEnabled(it) }
            )
        }
    ) {
        if (isActive) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DVC Mode", color = Color.White.copy(0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DvcMode.entries.forEach { mode ->
                        val isSelected = config.dvcMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setDvcMode(mode) },
                            label = { Text(mode.displayName, color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PremiumAccent,
                                containerColor = Color.White.copy(0.05f)
                            ),
                            border = null
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                DspSliderRow(
                    title = "DVC Level",
                    value = config.dvcLevel,
                    range = 0f..1f,
                    enabled = true,
                    valueText = { "${(it * 100).toInt()}%" },
                    onValueChange = viewModel::setDvcLevel
                )
            }
        }
    }
}

@Composable
private fun SoxrQualityCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val isResamplerOn = config.highQualityResampler
    val isResampling = uiState.inputSampleRate != uiState.outputSampleRate
    val isBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassSoxr
    val canChange = isResamplerOn && !isBypassed

    SettingsSection(
        title = "SOXR RESAMPLER QUALITY",
        icon = Icons.Rounded.HighQuality,
        isActive = isResampling && canChange,
        subtitle = if (isResampling)
            "${uiState.inputSampleRate / 1000}kHz → ${uiState.outputSampleRate / 1000}kHz active"
        else
            "No resampling — source matches output rate",
        headerActions = {
            if (!canChange) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (isBypassed) "BYPASSED" else "OFF",
                        color = Color.White.copy(0.3f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    ) {
        val qualities = SoxrQualityEnum.entries
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            qualities.forEach { quality ->
                val isSelected = config.soxrQuality == quality
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected && canChange) PremiumAccent.copy(0.15f)
                            else Color.White.copy(0.04f)
                        )
                        .border(
                            1.dp,
                            if (isSelected && canChange) PremiumAccent.copy(0.5f)
                            else Color.White.copy(0.08f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = canChange) {
                            viewModel.setSoxrQuality(quality)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            quality.displayName.uppercase(),
                            color = if (isSelected && canChange) PremiumAccent
                                    else Color.White.copy(if (canChange) 0.7f else 0.25f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            when (quality) {
                                SoxrQualityEnum.QUICK    -> "Lowest CPU"
                                SoxrQualityEnum.LOW      -> "Light"
                                SoxrQualityEnum.MEDIUM   -> "Balanced"
                                SoxrQualityEnum.HIGH     -> "Recommended"
                                SoxrQualityEnum.VERY_HIGH -> "Max quality"
                            },
                            color = Color.White.copy(if (canChange) 0.35f else 0.15f),
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }

        if (config.soxrQuality == SoxrQualityEnum.VERY_HIGH && isResamplerOn && !config.bitPerfectEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF8800).copy(0.07f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.Info, null, tint = Color(0xFFFF8800), modifier = Modifier.size(13.dp))
                Text(
                    "Very High quality uses significant CPU. Monitor for underruns.",
                    color = Color(0xFFFF8800).copy(0.85f),
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun Float64Card(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val isActive = config.float64Enabled
    val isBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassFloat64

    SettingsSection(
        title = "FLOAT64 PROCESSING",
        icon = Icons.Rounded.PrecisionManufacturing,
        isActive = isActive && !isBypassed,
        subtitle = when {
            isBypassed -> "Inactive — DSP bypassed in Bit-Perfect mode"
            isActive   -> "64-bit internal precision active"
            else       -> "64-bit math inside DSP chain"
        },
        headerActions = {
            if (isBypassed) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "BYPASSED",
                        color = Color.White.copy(0.3f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            } else {
                PremiumSwitch(
                    checked = isActive,
                    onCheckedChange = {
                        viewModel.setFloat64Enabled(it)
                    }
                )
            }
        }
    ) {
        if (isActive && !isBypassed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF8800).copy(0.07f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    null,
                    tint = Color(0xFFFF8800),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    "Doubles CPU load on the DSP chain. Audible mainly on EQ and reverb.",
                    color = Color(0xFFFF8800).copy(0.85f),
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun DitherCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    val currentType = config.ditherType
    val isBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassDithering
    val isHighBitDepth = uiState.outputBitDepth >= 32

    SettingsSection(
        title = "DITHERING",
        icon = Icons.Rounded.Waves,
        isActive = config.ditherEnabled && !isBypassed,
        subtitle = when {
            isBypassed -> "Inactive — DSP bypassed"
            isHighBitDepth -> "Not required for 32-bit output — will be skipped"
            !config.ditherEnabled || currentType == DitherType.NONE -> "Dither disabled"
            else -> "${currentType.displayName} active (${uiState.outputBitDepth}-bit)"
        },
        headerActions = {
            if (!isBypassed) {
                PremiumSwitch(
                    checked = config.ditherEnabled,
                    onCheckedChange = { viewModel.setDitherEnabled(it) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "BYPASSED",
                        color = Color.White.copy(0.3f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    ) {
        val types = DitherType.entries.filter { it != DitherType.NONE }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            types.forEach { type ->
                val isSelected = currentType == type
                val canSelect = !isBypassed && config.ditherEnabled
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected && canSelect) PremiumAccent.copy(0.15f)
                            else Color.White.copy(0.04f)
                        )
                        .border(
                            1.dp,
                            if (isSelected && canSelect) PremiumAccent.copy(0.5f)
                            else Color.White.copy(0.08f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = canSelect) {
                            viewModel.setDitherType(type)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            type.displayName.uppercase(),
                            color = if (isSelected && canSelect) PremiumAccent
                                    else Color.White.copy(if (canSelect) 0.7f else 0.25f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            when (type) {
                                DitherType.TPDF -> "Standard"
                                DitherType.SHAPED -> "Low noise"
                                DitherType.HIGHPASS -> "Optimal"
                                else -> ""
                            },
                            color = Color.White.copy(if (canSelect) 0.35f else 0.15f),
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LimiterCard(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val config = uiState.dsp.config
    val isBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassLimiter
    val isActive = config.limiterEnabled && !isBypassed

    SettingsSection(
        title = "PEAK LIMITER",
        icon = Icons.Rounded.Security,
        isActive = isActive,
        subtitle = if (isBypassed) "Inactive — DSP bypassed"
        else if (config.limiterEnabled) "Lookahead peak protection active"
        else "Prevents digital clipping",
        headerActions = {
            if (isBypassed) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "BYPASSED",
                        color = Color.White.copy(0.3f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            } else {
                PremiumSwitch(
                    checked = config.limiterEnabled,
                    onCheckedChange = { viewModel.setLimiterEnabled(it) }
                )
            }
        }
    ) {
        if (config.limiterEnabled && !isBypassed) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LimiterSlider(
                    label = "Threshold",
                    value = config.limiterThresholdDb,
                    range = -6f..0f,
                    unit = "dB",
                    onValueChange = viewModel::setLimiterThresholdDb,
                    onLongPress = {
                        onEditValue(EditingValue("Threshold", config.limiterThresholdDb, -6f..0f, viewModel::setLimiterThresholdDb))
                    }
                )
                LimiterSlider(
                    label = "Attack",
                    value = config.limiterAttackMs,
                    range = 0.1f..10f,
                    unit = "ms",
                    onValueChange = viewModel::setLimiterAttackMs,
                    onLongPress = {
                        onEditValue(EditingValue("Attack", config.limiterAttackMs, 0.1f..10f, viewModel::setLimiterAttackMs))
                    }
                )
                LimiterSlider(
                    label = "Release",
                    value = config.limiterReleaseMs,
                    range = 10f..200f,
                    unit = "ms",
                    onValueChange = viewModel::setLimiterReleaseMs,
                    onLongPress = {
                        onEditValue(EditingValue("Release", config.limiterReleaseMs, 10f..200f, viewModel::setLimiterReleaseMs))
                    }
                )
            }
        }
    }
}

@Composable
private fun LimiterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
    onLongPress: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White.copy(0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                if (unit == "dB") "%.1f %s".format(value, unit) else "%.0f %s".format(value, unit),
                color = PremiumAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.clickable { onLongPress() }
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = PremiumAccent,
                activeTrackColor = PremiumAccent,
                inactiveTrackColor = Color.White.copy(0.1f)
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

@Composable
fun ReplayGainContent(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val config = uiState.dsp.config
    SettingsSection(
        title = "REPLAY GAIN",
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        isActive = config.replayGainEnabled,
        headerActions = {
            PremiumSwitch(
                checked = config.replayGainEnabled,
                onCheckedChange = { viewModel.setReplayGainEnabled(it) }
            )
        }
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Text(
                "Processing Mode",
                color = Color.White.copy(if (config.replayGainEnabled) 0.6f else 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.beatflowy.app.model.ReplayGainOption.entries.forEach { option ->
                    val isSelected = config.replayGainOption == option
                    FilterChip(
                        selected = isSelected,
                        enabled = config.replayGainEnabled,
                        onClick = { viewModel.setReplayGainOption(option) },
                        label = {
                            Text(
                                option.displayName,
                                color = if (isSelected) {
                                    if (config.replayGainEnabled) Color.Black else Color.Black.copy(0.4f)
                                } else {
                                    if (config.replayGainEnabled) Color.White else Color.White.copy(0.3f)
                                }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (config.replayGainEnabled) PremiumAccent else PremiumAccent.copy(0.4f),
                            containerColor = Color.White.copy(0.05f),
                            disabledContainerColor = Color.White.copy(0.02f),
                            disabledSelectedContainerColor = PremiumAccent.copy(0.2f)
                        ),
                        border = null
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Source",
                color = Color.White.copy(if (config.replayGainEnabled) 0.6f else 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.beatflowy.app.model.ReplayGainSource.entries.forEach { source ->
                    val isSelected = config.replayGainSource == source
                    FilterChip(
                        selected = isSelected,
                        enabled = config.replayGainEnabled,
                        onClick = { viewModel.setReplayGainSource(source) },
                        label = {
                            Text(
                                source.displayName,
                                color = if (isSelected) {
                                    if (config.replayGainEnabled) Color.Black else Color.Black.copy(0.4f)
                                } else {
                                    if (config.replayGainEnabled) Color.White else Color.White.copy(0.3f)
                                }
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (config.replayGainEnabled) PremiumAccent else PremiumAccent.copy(0.4f),
                            containerColor = Color.White.copy(0.05f),
                            disabledContainerColor = Color.White.copy(0.02f),
                            disabledSelectedContainerColor = PremiumAccent.copy(0.2f)
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
                enabled = config.replayGainEnabled,
                valueText = { String.format(Locale.getDefault(), "%.1f dB", it) },
                onValueChange = viewModel::setReplayGainPreamp
            )

            if (!config.replayGainEnabled) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Normalizes volume across tracks based on embedded ReplayGain tags. Prevents sudden volume jumps between albums.",
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun LibraryContent(uiState: PlayerUiState, viewModel: PlayerViewModel, onShowInfo: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(listOf(PremiumAccent.copy(0.14f), Color(0xFF0066FF).copy(0.10f)))
                )
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(PremiumAccent.copy(0.45f), Color(0xFF0066FF).copy(0.4f))),
                    RoundedCornerShape(14.dp)
                )
                .clickable { viewModel.startFullScan() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Sync, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                Text("Full Rescan Library", color = PremiumAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(0.05f))
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(14.dp))
                    .clickable { viewModel.quickScan() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Search, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp))
                    Text("Quick Scan", color = Color.White.copy(0.8f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
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
            PremiumSwitch(
                checked = uiState.useOriginalQualityArt,
                onCheckedChange = { viewModel.setUseOriginalQualityArt(it) }
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

        if (uiState.blockedFolders.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color.White.copy(0.06f))
            Spacer(Modifier.height(16.dp))

            Column {
                Text(
                    "Excluded Folders",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "These folders are ignored during scanning.",
                    color = Color.White.copy(0.45f),
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.blockedFolders.forEach { folder ->
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
                                    .background(Color.White.copy(0.05f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.FolderOff,
                                    null,
                                    tint = Color.White.copy(0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.substringAfterLast("/"),
                                    color = Color.White.copy(0.6f),
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
                                onClick = {
                                    viewModel.unblockMusicFolder(folder)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.AddCircleOutline,
                                    null,
                                    tint = PremiumAccent,
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

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(
            title = "GOOGLE DRIVE",
            icon = Icons.Rounded.Cloud,
            isActive = true,
            statusDot = Color(0xFF1A73E8)
        ) {
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
                Spacer(Modifier.height(4.dp))
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

        SettingsSection(
            title = "TELEGRAM CHANNELS",
            icon = Icons.AutoMirrored.Rounded.Send,
            isActive = true,
            statusDot = Color(0xFF2AABEE)
        ) {
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
                        val isExisting = telegramChannels.any { it.url.contains(telegramUrl, true) || it.name.contains(telegramUrl, true) }
                        Text(if (isExisting) "Search" else "Join",
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
                Spacer(Modifier.height(4.dp))
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
            PremiumSwitch(
                checked = channel.enabled,
                onCheckedChange = onToggle
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
            PremiumSwitch(
                checked = account.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun parseMarkdown(text: String): AnnotatedString {
    val parts = text.split("**")
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                    append(part)
                }
            } else {
                append(part)
            }
        }
    }
}

@Composable
private fun WhatsNewSection(title: String, items: List<Pair<String, List<String>>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        items.forEach { (subTitle, bulletPoints) ->
            if (subTitle.isNotEmpty()) {
                Text(subTitle, color = Color.White.copy(0.8f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            bulletPoints.forEach { point ->
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text("• ", color = PremiumAccent)
                    Text(
                        text = parseMarkdown(point),
                        color = Color.White.copy(0.7f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun WhatsNewCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.03f)
        ),
        border = BorderStroke(
            1.dp,
            Color.White.copy(0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .glassIconBackground(
                            backgroundColor = PremiumAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            borderColor = PremiumAccent.copy(alpha = 0.25f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.NewReleases, null, tint = PremiumAccent, modifier = Modifier.size(20.dp))
                }
                Text(
                    "WHAT'S NEW",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    letterSpacing = 1.2.sp
                )
            }

            Text(
                "All notable updates and improvements to Beatraxus are documented here.",
                color = Color.White.copy(0.6f),
                fontSize = 12.sp
            )

            HorizontalDivider(color = Color.White.copy(0.08f))

            Text(
                "[June 2026 Update] - June 2026",
                color = PremiumAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            WhatsNewSection(
                title = "🚀 Added",
                items = listOf(
                    "🎵 Audio Features" to listOf(
                        "Added **Bit-Perfect Mode** for untouched, high-fidelity audio playback",
                        "Added **USB Direct Output** support for external DACs",
                        "Added **Dithering** for improved audio precision and playback quality"
                    ),
                    "🎚️ Equalizer" to listOf(
                        "Added **EQ Export & Import** functionality",
                        "Redesigned the **Equalizer UI** for a cleaner and improved experience"
                    ),
                    "☁️ Cloud & Metadata" to listOf(
                        "Added **Metadata Settings** for better cloud sync customization"
                    )
                )
            )

            WhatsNewSection(
                title = "⚡ Improved",
                items = listOf(
                    "🎧 Audio Processing" to listOf(
                        "Improved **Peak Limiter** for cleaner playback and better distortion control",
                        "Enhanced **ReplayGain** for more accurate and consistent volume normalization"
                    ),
                    "🖥️ UI & Performance" to listOf(
                        "Enhanced **overall UI smoothness**",
                        "Improved **app responsiveness and performance**",
                        "General optimization improvements for a smoother experience"
                    )
                )
            )

            WhatsNewSection(
                title = "⚠️ Known Issues",
                items = listOf(
                    "❗ Widget Sync Issue" to listOf(
                        "Widgets may not sync properly with the app in certain situations"
                    )
                )
            )

            WhatsNewSection(
                title = "🔮 Coming Soon",
                items = listOf(
                    "" to listOf(
                        "🎼 **Built-in Hi-Res Song Download Support**",
                        "☁️ **MEGA Cloud Integration**",
                        "More **audio enhancements, performance optimizations, and ecosystem improvements**"
                    )
                )
            )

            HorizontalDivider(color = Color.White.copy(0.08f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "❤️ Thank You",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your feedback helps shape Beatraxus into a smarter, smoother, and more powerful music experience.",
                    color = Color.White.copy(0.6f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "⭐ If you enjoy Beatraxus, consider starring the repository and sharing your feedback!",
                    color = PremiumAccent.copy(0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
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

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WhatsNewCard()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            PremiumAccent.copy(0.08f),
                            Color(0xFF0066FF).copy(0.06f),
                            Color.Transparent
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(PremiumAccent.copy(0.25f), Color.White.copy(0.06f))
                    ),
                    RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Beatraxus Music player",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("HarbertRaj", color = Color.White.copy(0.5f), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(PremiumAccent.copy(0.18f), Color(0xFF0066FF).copy(0.14f))
                                )
                            )
                            .border(1.dp, PremiumAccent.copy(0.3f), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "v$versionName",
                            color = PremiumAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = { uriHandler.openUri("https://github.com/Harbertraj/Beatraxus") },
                    modifier = Modifier
                        .align(Alignment.Top)
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.07f))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = "GitHub",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
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
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(16.dp, CircleShape, ambientColor = PremiumAccent.copy(0.4f), spotColor = PremiumAccent.copy(0.4f))
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.25f), Color.Transparent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, null, tint = PremiumAccent, modifier = Modifier.size(28.dp))
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "Syncing Music",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = Color.White.copy(0.45f), fontSize = 11.sp)
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean = false,
    statusDot: Color? = null,
    subtitle: String? = null,
    headerActions: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) PremiumAccent.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f)
        ),
        border = BorderStroke(
            1.dp,
            if (isActive) PremiumAccent.copy(0.35f) else Color.White.copy(0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val iconAccent = if (isActive) PremiumAccent else Color.White
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .glassIconBackground(
                                backgroundColor = iconAccent.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                borderColor = iconAccent.copy(alpha = 0.18f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            null,
                            tint = if (isActive) PremiumAccent else Color.White.copy(0.75f),
                            modifier = Modifier.size(19.dp)
                        )

                        if (statusDot != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 3.dp, y = 3.dp)
                                    .size(10.dp)
                                    .background(Color(0xFF121212), CircleShape)
                                    .padding(1.5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(statusDot, CircleShape)
                                )
                            }
                        }
                    }
                    Column {
                        Text(
                            title.uppercase(),
                            color = if (isActive) PremiumAccent else Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.2.sp
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                color = Color.White.copy(0.45f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                headerActions?.invoke()
            }
            content()
        }
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected)
                    Brush.verticalGradient(listOf(PremiumAccent.copy(0.25f), PremiumAccent.copy(0.10f)))
                else
                    Brush.verticalGradient(listOf(Color.White.copy(0.07f), Color.White.copy(0.03f)))
            )
            .border(
                width = 1.dp,
                color = if (selected) PremiumAccent.copy(0.6f) else Color.White.copy(0.09f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> Color.White.copy(0.28f)
                selected -> PremiumAccent
                else -> Color.White.copy(0.65f)
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
private fun DspToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(if (checked) 0.05f else 0.025f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (checked)
                            Brush.verticalGradient(listOf(PremiumAccent, PremiumAccent.copy(0.4f)))
                        else
                            Brush.verticalGradient(listOf(Color.White.copy(0.15f), Color.White.copy(0.05f)))
                    )
            )
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                color = if (checked) Color.White else Color.White.copy(0.75f),
                fontSize = 14.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        PremiumSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = Color.White.copy(alpha = if (enabled) 0.85f else 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(PremiumAccent.copy(alpha = if (enabled) 0.15f else 0.06f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    valueText(value),
                    color = PremiumAccent.copy(alpha = if (enabled) 1f else 0.35f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                activeTrackColor = PremiumAccent,
                inactiveTrackColor = Color.White.copy(alpha = 0.10f),
                thumbColor = PremiumAccent,
                disabledActiveTrackColor = PremiumAccent.copy(alpha = 0.2f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.06f),
                disabledThumbColor = PremiumAccent.copy(alpha = 0.28f)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.03f)
        ),
        border = BorderStroke(
            1.dp,
            Color.White.copy(0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .glassIconBackground(
                            backgroundColor = DownloadAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            borderColor = DownloadAccent.copy(alpha = 0.25f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = DownloadAccent, modifier = Modifier.size(18.dp))
                }
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp
                )
            }
            content()
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(if (checked) 0.06f else 0.025f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (checked)
                            Brush.verticalGradient(listOf(DownloadAccent, DownloadAccent.copy(0.3f)))
                        else
                            Brush.verticalGradient(listOf(Color.White.copy(0.12f), Color.White.copy(0.04f)))
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    color = if (checked) Color.White else Color.White.copy(0.8f),
                    fontSize = 14.sp,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color.White.copy(0.4f), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        PremiumSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.1f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color.White.copy(0.4f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = PremiumAccent, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun MetadataSyncContent(uiState: PlayerUiState, playerViewModel: PlayerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(
            title = "Network Control",
            icon = Icons.Rounded.Wifi
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                listOf(
                    com.beatflowy.app.model.NetworkType.WIFI_ONLY to "Wi-Fi Only",
                    com.beatflowy.app.model.NetworkType.WIFI_MOBILE to "Wi-Fi + Mobile Data",
                    com.beatflowy.app.model.NetworkType.MOBILE_ONLY to "Mobile Data Only",
                    com.beatflowy.app.model.NetworkType.ASK_MOBILE to "Ask Before Mobile Data"
                ).forEach { (type, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playerViewModel.setMetadataNetworkType(type) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.metadataNetworkType == type,
                            onClick = { playerViewModel.setMetadataNetworkType(type) },
                            colors = RadioButtonDefaults.colors(selectedColor = PremiumAccent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }

        SettingsSection(
            title = "Data Saver",
            icon = Icons.Rounded.DataUsage
        ) {
            DspToggleRow(
                title = "Enable Data Saver",
                checked = uiState.dataSaverEnabled,
                onCheckedChange = { playerViewModel.setDataSaverEnabled(it) }
            )
            Text(
                "Aggressively reuse cache and minimize enrichment requests.",
                color = Color.White.copy(0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        SettingsSection(
            title = "Enrichment Options",
            icon = Icons.Rounded.Image
        ) {
            DspToggleRow(
                title = "Album Art Enrichment",
                checked = uiState.artworkEnrichmentEnabled,
                onCheckedChange = { playerViewModel.setArtworkEnrichmentEnabled(it) }
            )
            
            Text(
                "Sync Quality",
                color = Color.White.copy(0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.beatflowy.app.model.SyncQuality.entries.forEach { quality ->
                    val selected = uiState.syncQuality == quality
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) PremiumAccent.copy(0.2f) else Color.White.copy(0.05f))
                            .border(1.dp, if (selected) PremiumAccent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { playerViewModel.setSyncQuality(quality) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(quality.name, color = if (selected) PremiumAccent else Color.White.copy(0.6f), fontSize = 12.sp)
                    }
                }
            }
        }

        SettingsSection(
            title = "Background Sync",
            icon = Icons.Rounded.Autorenew
        ) {
            DspToggleRow(
                title = "Allow Background Sync",
                checked = uiState.backgroundSyncEnabled,
                onCheckedChange = { playerViewModel.setBackgroundSyncEnabled(it) }
            )
        }
    }
}

@Composable
private fun DlDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        DownloadAccent.copy(alpha = 0.15f),
                        Color.Transparent
                    )
                )
            )
    )
}
