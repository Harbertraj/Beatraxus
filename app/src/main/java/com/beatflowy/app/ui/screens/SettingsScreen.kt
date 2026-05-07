package com.beatflowy.app.ui.screens

import android.graphics.Shader
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.R
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.viewmodel.PlayerViewModel

private val PremiumAccent = Color(0xFF00F2FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToDsp: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showInfoPopup by remember { mutableStateOf(false) }

    val blurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(64f, 64f, Shader.TileMode.DECAL)
        } else null
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121821))) {
        MainBackground(
            albumArtUri = uiState.currentSong?.albumArtUri,
            blurEffect = blurEffect
        )

        // Only show settings if not showing info
        androidx.compose.animation.AnimatedVisibility(
            visible = !showInfoPopup,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                        title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    SettingsSection(title = "Audio Engine", icon = Icons.Rounded.GraphicEq) {
                        val sampleFormats = com.beatflowy.app.model.SampleFormat.entries

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

                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = uiState.hiResCapabilitySummary,
                            color = if (uiState.hiResDirectSupported) PremiumAccent else Color.White.copy(0.5f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        // Resampling Toggle
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
                        DspToggleRow("Peak Limiter", uiState.dsp.config.limiterEnabled) {
                            viewModel.setLimiterEnabled(it)
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    SettingsSection(title = "Replay Gain", icon = Icons.AutoMirrored.Rounded.VolumeUp) {
                        val config = uiState.dsp.config
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
                    
                    Spacer(Modifier.height(24.dp))
                    
                    SettingsSection(title = "Library", icon = Icons.Rounded.AudioFile) {
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
                                    onClick = { showInfoPopup = true },
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
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    SettingsSection(title = "About", icon = Icons.Rounded.Settings) {
                        val context = LocalContext.current
                        val uriHandler = LocalUriHandler.current
                        val versionName = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        } catch (e: Exception) {
                            "Unknown"
                        }

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
                    .wrapContentHeight()
                    .shadow(elevation = 20.dp, shape = RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1A24), Color(0xFF0F0F14))
                        )
                    )
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(28.dp))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = PremiumAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, color = PremiumAccent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.2f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
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
