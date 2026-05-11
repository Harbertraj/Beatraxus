package com.beatflowy.app.ui.screens

import android.graphics.Shader
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beatflowy.app.R
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.repository.DriveAccount
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.viewmodel.PlayerViewModel

private val PremiumAccent = Color(0xFF00F2FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
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
                            if (currentSection != null) {
                                Text(
                                    text = currentSection!!.uppercase(),
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
                            title = "Google Cloud",
                            subtitle = "Stream music from your cloud storage",
                            icon = Icons.Rounded.Cloud,
                            iconColor = Color(0xFF1A73E8),
                            onClick = { currentSection = "Cloud" }
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
        // Output Mode Selection
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

        // Output Info
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
        
        // Sample Rate Buttons
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
        
        // Sample Format Buttons
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
            
            // Replay Gain Option Buttons
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

            // Source Buttons
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
                valueText = { String.format("%.1f dB", it) },
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
        
        if (uiState.errorMessage != null && (uiState.errorMessage!!.contains("Added") || uiState.errorMessage!!.contains("No new"))) {
            Text(
                uiState.errorMessage!!,
                color = PremiumAccent,
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

        // Folder Management Header
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

        // Folder List
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
    val accounts by viewModel.driveAccounts.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "GOOGLE CLOUD",
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
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF1A73E8).copy(0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Cloud,
                            contentDescription = null,
                            tint = Color(0xFF1A73E8),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Google Cloud", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Connect your account", color = Color.White.copy(0.5f), fontSize = 13.sp)
                    }
                    Button(
                        onClick = onRequestGDriveAccount,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text("Connect", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                if (accounts.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(0.08f), thickness = 1.dp)
                    accounts.forEach { account ->
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
                onClick = { /* Could do something here */ },
                onLongClick = { showDelete = !showDelete }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF1A73E8).copy(0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = Color(0xFF1A73E8))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(account.email, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (account.enabled) "Sync enabled" else "Sync disabled", color = Color.Gray, fontSize = 12.sp)
        }
        
        IconButton(
            onClick = onScan,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Rounded.Sync, contentDescription = "Sync", tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
        }
        
        Spacer(Modifier.width(8.dp))
        
        Switch(
            checked = account.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF1A73E8),
                checkedTrackColor = Color(0xFF1A73E8).copy(alpha = 0.5f)
            )
        )
        
        if (showDelete) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = Color.Red.copy(0.6f), modifier = Modifier.size(18.dp))
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
                    .heightIn(max = 300.dp) // Adjusted to be more compact like filter popup
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
            // Accent bar
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

private fun formatSampleRateLabel(sampleRate: Int): String {
    if (sampleRate == 0) return "Auto"
    return if (sampleRate % 1000 == 0) {
        "${sampleRate / 1000} kHz"
    } else {
        String.format("%.1f kHz", sampleRate / 1000f)
    }
}

@Composable
private fun EqBandEditor(
    band: ParametricEqBand,
    enabled: Boolean,
    onBandEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (Float) -> Unit,
    onGainChange: (Float) -> Unit,
    onQChange: (Float) -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Band ${band.id + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Switch(
                    checked = band.enabled,
                    onCheckedChange = onBandEnabledChange,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PremiumAccent,
                        checkedTrackColor = PremiumAccent.copy(alpha = 0.32f)
                    )
                )
            }
            DspSliderRow(
                title = "Frequency",
                value = band.frequencyHz,
                range = 20f..20_000f,
                enabled = enabled && band.enabled,
                valueText = {
                    if (it >= 1000f) String.format("%.1f kHz", it / 1000f) else "${it.toInt()} Hz"
                },
                onValueChange = onFrequencyChange
            )
            DspSliderRow(
                title = "Gain",
                value = band.gainDb,
                range = -12f..12f,
                enabled = enabled && band.enabled,
                valueText = { "${it.toInt()} dB" },
                onValueChange = onGainChange
            )
            DspSliderRow(
                title = "Q",
                value = band.q,
                range = 0.2f..8f,
                enabled = enabled && band.enabled,
                valueText = { String.format("%.2f", it) },
                onValueChange = onQChange
            )
        }
    }
}
