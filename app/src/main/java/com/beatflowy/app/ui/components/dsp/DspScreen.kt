package com.beatflowy.app.ui.components.dsp

import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.beatflowy.app.utils.PresetExporter
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.ui.components.PremiumSwitch
import com.beatflowy.app.ui.screens.MainBackground
import com.beatflowy.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.*
import android.graphics.RenderEffect as AndroidRenderEffect

// Premium Color Palette
private val PremiumSurface = Color(0xFF121821)
private val PremiumAccent = Color(0xFF00F2FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DspScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var customPresetName by remember { mutableStateOf("") }
    val builtInPresets = remember { builtInEqPresets() }
    var showPresetsSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var editingValue by remember { mutableStateOf<EditingValue?>(null) }

    // State to hold JSON for export callback
    var exportJson by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(exportJson)
                    }
                }
                android.widget.Toast.makeText(context, "Presets exported successfully", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "Export failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val json = reader.readText()
                        val presets = PresetExporter.parseJson(json)
                        if (presets != null && presets.isNotEmpty()) {
                            viewModel.importEqPresets(presets)
                            android.widget.Toast.makeText(context, "Imported ${presets.size} preset(s)", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "No valid presets found in file", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "Import failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val blurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(64f, 64f, Shader.TileMode.DECAL)
        } else null
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PremiumSurface)
    ) {
        MainBackground(
            albumArtUri = uiState.currentSong?.albumArtUri,
            blurEffect = blurEffect
        )

        val pagerState = rememberPagerState(pageCount = { 3 })

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.background(Color.Transparent),
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DSP STUDIO", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (0..2).forEach { i ->
                                    val isActive = pagerState.currentPage == i
                                    val width by animateDpAsState(
                                        targetValue = if (isActive) 12.dp else 4.dp,
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                        label = "dot_width"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(width, 4.dp)
                                            .background(
                                                if (isActive) PremiumAccent else Color.White.copy(alpha = 0.3f),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                )
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalAlignment = Alignment.Top
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (page == 0) {
                        PremiumGraphicCard(uiState)

                        UnifiedPresetSection(
                            uiState = uiState,
                            viewModel = viewModel,
                            presets = builtInPresets,
                            customPresetName = customPresetName,
                            onNameChange = { customPresetName = it },
                            showSaveDialog = showSaveDialog,
                            onToggleSaveDialog = { showSaveDialog = it },
                            onSave = {
                                if (customPresetName.isNotBlank()) {
                                    viewModel.saveCustomEqPreset(customPresetName)
                                    customPresetName = ""
                                    showSaveDialog = false
                                }
                            },
                            onImport = { importLauncher.launch(arrayOf("application/json")) },
                            onExport = {
                                val currentConfig = uiState.dsp.config
                                exportJson = PresetExporter.exportToCurrentJson(
                                    name = "Beatraxus Preset",
                                    preamp = currentConfig.preampDb,
                                    bands = currentConfig.eqBands
                                )
                                exportLauncher.launch("eq_preset.json")
                            }
                        )

                        PremiumEqualizerCard(uiState, viewModel, onShowPresets = { showPresetsSheet = true }, onEditValue = { editingValue = it })
                    } else if (page == 1) {
                        PremiumReverbCard(uiState, viewModel, onEditValue = { editingValue = it })
                    } else {
                        PremiumMasteringCard(uiState, viewModel, onEditValue = { editingValue = it })
                    }
                }
            }
        }

        if (showPresetsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPresetsSheet = false },
                containerColor = Color(0xFF121218),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .size(36.dp, 4.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        "AUTO-EQ SEARCH",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        letterSpacing = 1.sp
                    )

                    OutlinedTextField(
                        value = uiState.dsp.autoEqQuery,
                        onValueChange = {
                            viewModel.setAutoEqQuery(it)
                            if (it.isBlank()) viewModel.clearAutoEqResults()
                            else viewModel.searchAutoEqProfiles()
                        },
                        placeholder = { Text("Search headphone model...", color = Color.White.copy(alpha = 0.3f)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchAutoEqProfiles() }),
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.4f))
                        },
                        trailingIcon = {
                            if (uiState.dsp.autoEqQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.setAutoEqQuery("")
                                    viewModel.clearAutoEqResults()
                                }) {
                                    Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.4f))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PremiumAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            cursorColor = PremiumAccent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                        )
                    )

                    // Offline/Recent Presets horizontal list
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "POPULAR MODELS",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        val popular = listOf("Sony WH-1000XM4", "Sennheiser HD 600", "Apple AirPods Pro", "Bose QC35")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            popular.forEach { model ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .clickable {
                                            viewModel.setAutoEqQuery(model)
                                            viewModel.searchAutoEqProfiles()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(model, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = PremiumAccent, modifier = Modifier.size(18.dp))
                            Text("AUTO-EQ PROFILES", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                        }

                        if (uiState.dsp.autoEqLoading) {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PremiumAccent, strokeWidth = 3.dp)
                            }
                        }

                        uiState.dsp.config.autoEqProfile?.let {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PremiumAccent.copy(alpha = 0.1f))
                                    .border(1.dp, PremiumAccent.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text("ACTIVE PROFILE", color = PremiumAccent, fontWeight = FontWeight.Black, fontSize = 9.sp)
                                    Text(it.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }

                        uiState.dsp.autoEqResults.forEach { result ->
                            AutoEqResultRow(
                                result = result,
                                isLoading = uiState.dsp.autoEqLoading,
                                onClick = {
                                    viewModel.applyAutoEqProfile(result)
                                }
                            )
                        }

                        if (uiState.dsp.autoEqResults.isEmpty() && !uiState.dsp.autoEqLoading) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f))
                            ) {
                                Text(
                                    "Enter your headphone model above to find optimized EQ profiles from the AutoEQ database.",
                                    modifier = Modifier.padding(20.dp),
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
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

private data class EditingValue(
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
    val format = when {
        abs(range.endInclusive - range.start) <= 2f -> "%.2f"
        else -> "%.1f"
    }
    var textValue by remember { mutableStateOf(format.format(initialValue)) }
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
private fun PremiumGraphicCard(uiState: PlayerUiState) {
    val config = uiState.dsp.config
    val displayBands = config.eqBands
    val displayEnabled = config.eqEnabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(24.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("GRAPHIC RESPONSE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
        ) {
            EqPreviewGraph(displayBands, displayEnabled)
        }
    }
}

@Composable
private fun PremiumEqualizerCard(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onShowPresets: () -> Unit,
    onEditValue: (EditingValue) -> Unit
) {
    val config = uiState.dsp.config
    val isLocked = config.settingsLocked
    val isEqBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassEq
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(24.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EQUALIZER", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.width(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PremiumAccent.copy(alpha = 0.1f))
                            .clickable { onShowPresets() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            null,
                            tint = PremiumAccent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "AUTO-EQ",
                            color = PremiumAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            val isEqBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassEq
            if (isEqBypassed) {
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
                    checked = config.eqEnabled,
                    onCheckedChange = { viewModel.setEqEnabled(it) }
                )
            }
        }

        // Preamp Horizontal Line Control
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "PREAMP",
                color = Color.White.copy(alpha = 0.5f),
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
            Box(modifier = Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                // Background track line
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                
                Slider(
                    value = config.preampDb,
                    onValueChange = { viewModel.setPreampDb(it) },
                    valueRange = -15f..15f,
                    enabled = !isEqBypassed && config.eqEnabled && !isLocked,
                    colors = SliderDefaults.colors(
                        thumbColor = if (config.eqEnabled && !isLocked) PremiumAccent else Color.Gray,
                        activeTrackColor = PremiumAccent.copy(alpha = 0.5f),
                        inactiveTrackColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = if (config.preampDb >= 0) "+%.1f dB".format(config.preampDb) else "%.1f dB".format(config.preampDb),
                color = if (config.eqEnabled) (if (isLocked) PremiumAccent.copy(0.4f) else PremiumAccent) else Color.White.copy(0.3f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .width(54.dp)
                    .clickable(enabled = config.eqEnabled && !isLocked) { 
                        onEditValue(EditingValue("PREAMP", config.preampDb, -15f..15f, viewModel::setPreampDb))
                    },
                textAlign = TextAlign.End
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            config.eqBands.forEachIndexed { index, band ->
                PremiumVerticalBand(
                    band = band,
                    enabled = config.eqEnabled && !isEqBypassed && !isLocked,
                    onToggle = { viewModel.setEqBandEnabled(index, it) },
                    onGainChange = { viewModel.setEqBandGain(index, (it * 10f).roundToInt() / 10f) },
                    onLongPress = {
                        onEditValue(
                            EditingValue(
                                label = "${band.frequencyHz.toInt()}Hz Band",
                                value = band.gainDb,
                                range = -12f..12f,
                                onConfirm = { viewModel.setEqBandGain(index, it) }
                            )
                        )
                    },
                    onFrequencyEdit = {
                        onEditValue(
                            EditingValue(
                                label = "Band Frequency",
                                value = band.frequencyHz,
                                range = 20f..20000f,
                                onConfirm = { viewModel.setEqBandFrequency(index, it) }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PremiumVerticalBand(
    band: ParametricEqBand,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onGainChange: (Float) -> Unit,
    onLongPress: () -> Unit,
    onFrequencyEdit: () -> Unit
) {
    val isActive = enabled && band.enabled
    val gainProgress by animateFloatAsState(
        targetValue = ((band.gainDb + 12f) / 24f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "gain_anim"
    )
    
    val gainColor = when {
        !isActive -> Color.White.copy(0.12f)
        band.gainDb > 0.5f -> Color(0xFF00FFCC)
        band.gainDb < -0.5f -> Color(0xFFFF4D4D)
        else -> PremiumAccent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(46.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (band.gainDb >= 0) "+%.1f".format(band.gainDb) else "%.1f".format(band.gainDb),
            color = if (isActive) gainColor else Color.White.copy(0.2f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp).clickable { onLongPress() }
        )

        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .height(280.dp)
                .width(32.dp)
                .pointerInput(isActive) {
                    if (!isActive) return@pointerInput
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val pos = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onGainChange((pos * 24f) - 12f)
                    }
                }
                .pointerInput(isActive) {
                    if (!isActive) return@pointerInput
                    detectTapGestures { offset ->
                        val pos = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        onGainChange((pos * 24f) - 12f)
                    }
                }
                .combinedClickable(
                    onClick = { if (enabled) onToggle(!band.enabled) },
                    onLongClick = onLongPress
                ),
            contentAlignment = Alignment.Center
        ) {
            // Track Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(0.45f))
                    .border(1.5.dp, Color.Black.copy(0.8f), RoundedCornerShape(16.dp))
            )

            // Track markings
            Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                val step = size.height / 10f
                for (i in 0..10) {
                    val y = i * step
                    val isCenter = i == 5
                    drawLine(
                        color = Color.White.copy(if (isCenter) 0.2f else 0.06f),
                        start = Offset(if (isCenter) 4.dp.toPx() else 8.dp.toPx(), y),
                        end = Offset(size.width - (if (isCenter) 4.dp.toPx() else 8.dp.toPx()), y),
                        strokeWidth = if (isCenter) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val midY = size.height / 2f
                val targetY = size.height * (1f - gainProgress)
                
                if (isActive && abs(gainProgress - 0.5f) > 0.01f) {
                    val rectTop = min(midY, targetY)
                    val rectBottom = max(midY, targetY)
                    val rectHeight = rectBottom - rectTop
                    val rectWidth = size.width * 0.4f
                    val rectLeft = (size.width - rectWidth) / 2f

                    // Main Fill
                    drawRect(
                        brush = Brush.verticalGradient(
                            if (gainProgress > 0.5f) listOf(gainColor.copy(0.7f), gainColor.copy(0.2f))
                            else listOf(gainColor.copy(0.2f), gainColor.copy(0.7f)),
                            startY = rectTop,
                            endY = rectBottom
                        ),
                        topLeft = Offset(rectLeft, rectTop),
                        size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight)
                    )

                    // Dark Outline
                    drawRect(
                        color = Color.Black.copy(0.6f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            // Fader Knob
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (280.dp * (1f - gainProgress)))
                    .size(32.dp, 32.dp)
                    .graphicsLayer {
                        translationY = -16.dp.toPx()
                    }
                    .shadow(8.dp, CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF3A3A4A), Color(0xFF1A1A24))
                        ),
                        CircleShape
                    )
                    .border(1.dp, Color.White.copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.radialGradient(listOf(gainColor.copy(0.15f), Color.Transparent)))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(2.dp)
                        .background(if (isActive) gainColor else Color.White.copy(0.2f), RoundedCornerShape(1.dp))
                )
            }
        }

        Text(
            text = if (band.frequencyHz >= 1000f)
                "%.1fk".format(band.frequencyHz / 1000f).removeSuffix(".0")
            else "${band.frequencyHz.toInt()}",
            color = if (isActive) Color.White.copy(0.85f) else Color.White.copy(0.3f),
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.clickable(enabled = isActive) {
                onFrequencyEdit()
            }
        )
    }
}

@Composable
private fun EqPreviewGraph(bands: List<ParametricEqBand>, enabled: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val dbScale = h / 28f // Scale for +/- 12dB plus margin

        // Draw grid lines
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            val y = midY - db * dbScale
            drawLine(
                color = Color.White.copy(alpha = if (db == 0f) 0.15f else 0.05f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (db == 0f) 1.5f else 1f
            )
        }

        if (bands.isNotEmpty()) {
            val points = bands.mapIndexed { i, band ->
                val x = (i.toFloat() / (bands.size - 1)) * w
                val y = midY - (band.gainDb * dbScale)
                Offset(x, y)
            }

            // Smoothed Curve logic (Cubic Spline interpolation)
            val path = Path()
            path.moveTo(points[0].x, points[0].y)

            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i+1]
                val controlX = (p0.x + p1.x) / 2f
                path.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
            }

            // Draw line
            drawPath(
                path = path,
                color = if (enabled) PremiumAccent else Color.White.copy(0.2f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // Draw gradient fill under curve
            val fillPath = Path()
            fillPath.addPath(path)
            fillPath.lineTo(points.last().x, midY)
            fillPath.lineTo(points.first().x, midY)
            fillPath.close()
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        (if (enabled) PremiumAccent else Color.White).copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    startY = midY - 12f * dbScale,
                    endY = midY + 12f * dbScale
                )
            )

            // Band dots
            points.forEachIndexed { i, pt ->
                val band = bands[i]
                if (band.enabled) {
                    val dotColor = if (enabled) PremiumAccent else Color.White.copy(0.4f)
                    drawCircle(color = dotColor, radius = 3.dp.toPx(), center = pt)
                }
            }
        }
    }
}

@Composable
private fun UnifiedPresetSection(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    presets: List<BuiltInEqPreset>,
    customPresetName: String,
    onNameChange: (String) -> Unit,
    showSaveDialog: Boolean,
    onToggleSaveDialog: (Boolean) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val currentGains = uiState.dsp.config.eqBands.map { it.gainDb }
    val activeBuiltIn = presets.find { it.gains == currentGains }?.name
    val activeCustom = uiState.dsp.customEqPresets.find { custom ->
        custom.bands.map { it.gainDb } == currentGains
    }?.name
    val isTweak = activeBuiltIn == null && activeCustom == null
    var showCustomPopup by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(24.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AUDIO PRESETS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
            
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        "Menu",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1A1A24)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Import Presets", color = Color.White, fontSize = 13.sp) },
                        onClick = { 
                            showMenu = false
                            onImport()
                        },
                        leadingIcon = { Icon(Icons.Rounded.FileDownload, null, tint = PremiumAccent, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Export Presets", color = Color.White, fontSize = 13.sp) },
                        onClick = { 
                            showMenu = false
                            onExport()
                        },
                        leadingIcon = { Icon(Icons.Rounded.FileUpload, null, tint = PremiumAccent, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (uiState.dsp.config.settingsLocked) "Unlock EQ & Preamp" else "Lock EQ & Preamp", color = Color.White, fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            viewModel.setSettingsLocked(!uiState.dsp.config.settingsLocked)
                        },
                        leadingIcon = {
                            Icon(
                                if (uiState.dsp.config.settingsLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                null,
                                tint = PremiumAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    if (isTweak) {
                        DropdownMenuItem(
                            text = { Text("Save Current Preset", color = Color.White, fontSize = 13.sp) },
                            onClick = { 
                                showMenu = false
                                onToggleSaveDialog(true)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Save, null, tint = PremiumAccent, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { preset ->
                val isActive = activeBuiltIn == preset.name && activeCustom == null
                Card(
                    onClick = { viewModel.setAllEqGains(preset.gains) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
                    ),
                    border = BorderStroke(1.dp, if (isActive) PremiumAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f))
                ) {
                    Text(
                        preset.name,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (isActive) PremiumAccent else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            Box {
                val isAnyCustomActive = activeCustom != null
                Card(
                    onClick = { showCustomPopup = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAnyCustomActive) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
                    ),
                    border = BorderStroke(1.dp, if (isAnyCustomActive) PremiumAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            activeCustom ?: "CUSTOM",
                            color = if (isAnyCustomActive) PremiumAccent else Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (isAnyCustomActive) PremiumAccent else Color.White.copy(0.4f), modifier = Modifier.size(14.dp))
                    }
                }

                if (showCustomPopup) {
                    Popup(
                        onDismissRequest = { showCustomPopup = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Card(
                            modifier = Modifier
                                .width(180.dp)
                                .padding(top = 40.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                            elevation = CardDefaults.cardElevation(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                if (uiState.dsp.customEqPresets.isEmpty()) {
                                    Text("No custom presets", color = Color.White.copy(0.4f), modifier = Modifier.padding(10.dp), fontSize = 11.sp)
                                }
                                uiState.dsp.customEqPresets.forEach { preset ->
                                    val isActive = activeCustom == preset.name
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isActive) PremiumAccent.copy(0.1f) else Color.Transparent)
                                            .clickable {
                                                viewModel.applySavedEqPreset(preset.name)
                                                showCustomPopup = false
                                            }
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(preset.name, color = if (isActive) PremiumAccent else Color.White, fontSize = 13.sp)
                                        IconButton(onClick = { viewModel.deleteCustomEqPreset(preset.name) }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Rounded.Delete, null, tint = Color.Red.copy(0.4f), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSaveDialog) {
            OutlinedTextField(
                value = customPresetName,
                onValueChange = onNameChange,
                placeholder = { Text("Preset name...", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
                trailingIcon = {
                    if (customPresetName.isNotBlank()) {
                        IconButton(onClick = onSave) {
                            Icon(Icons.Rounded.Check, "Save", tint = PremiumAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PremiumAccent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    cursorColor = PremiumAccent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                )
            )
        }
    }
}


@Composable
private fun PremiumReverbCard(uiState: PlayerUiState, viewModel: PlayerViewModel, onEditValue: (EditingValue) -> Unit) {
    val config = uiState.dsp.config
    val isReverbBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassReverb
    val presets = listOf("FLAT", "ROOM", "HALL", "PLATE", "CATHEDRAL", "STUDIO", "CHAMBER")
    var showPresetPicker by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "reverb_glow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer { alpha = pulseAlpha }
                            .background(PremiumAccent.copy(0.4f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(if (config.reverbEnabled && !isReverbBypassed) PremiumAccent else Color.White.copy(0.3f), CircleShape)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "REVERB ENGINE",
                    color = PremiumAccent.copy(0.7f),
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
            }

            if (isReverbBypassed) {
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

        // MOVED TO TOP: BUTTON ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isEnabled = config.reverbEnabled && !isReverbBypassed
            Button(
                onClick = { if (!isReverbBypassed) viewModel.setReverbEnabled(!config.reverbEnabled) },
                enabled = !isReverbBypassed,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEnabled) PremiumAccent.copy(0.1f) else Color.Transparent,
                    contentColor = if (isEnabled) PremiumAccent else Color.White,
                    disabledContainerColor = Color.White.copy(0.02f),
                    disabledContentColor = Color.White.copy(0.2f)
                ),
                border = BorderStroke(1.dp, if (isEnabled) PremiumAccent else Color.White.copy(if (isReverbBypassed) 0.05f else 0.15f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Circle, null, modifier = Modifier.size(6.dp), tint = if (isEnabled) PremiumAccent else Color.Transparent)
                    Text("REVERB", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = { showPresetPicker = true },
                enabled = !isReverbBypassed,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(0.05f),
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(0.02f),
                    disabledContentColor = Color.White.copy(0.2f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(if (isReverbBypassed) 0.05f else 0.15f))
            ) {
                Text(
                    config.reverbPreset.let { if (it.length > 10) it.take(7) + "..." else it },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { viewModel.setReverbPreset("CUSTOM") },
                enabled = !isReverbBypassed,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(32.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(0.05f),
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(0.02f),
                    disabledContentColor = Color.White.copy(0.2f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(if (isReverbBypassed) 0.05f else 0.15f))
            ) {
                Text("Save", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    viewModel.setReverbPreset("FLAT")
                    viewModel.setReverbAmount(0f)
                    viewModel.setReverbEnabled(false)
                },
                enabled = !isReverbBypassed,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(32.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(0.05f),
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(0.02f),
                    disabledContentColor = Color.White.copy(0.2f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(if (isReverbBypassed) 0.05f else 0.15f))
            ) {
                Text("Reset", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.12f), Color.Transparent)))
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            KnobControl(
                label = "WET MIX",
                value = config.reverbAmount,
                onValueChange = viewModel::setReverbAmount,
                range = 0f..1f,
                unit = "x",
                knobSize = 110.dp,
                isBipolar = false,
                enabled = !isReverbBypassed,
                active = config.reverbEnabled,
                onToggle = { viewModel.setReverbEnabled(!config.reverbEnabled) },
                onLongPress = { onEditValue(EditingValue("WET MIX", config.reverbAmount, 0f..1f, viewModel::setReverbAmount)) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(0.03f))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.06f), Color.Transparent), radius = 160f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                .padding(vertical = 12.dp)
        ) {
            val reverbCanInteract = !isReverbBypassed
            val reverbActive = config.reverbEnabled && !isReverbBypassed
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("DAMPING", config.reverbDamping, viewModel::setReverbDamping, 0f..1f, "x", knobSize = 84.dp, enabled = reverbCanInteract, active = reverbActive, onToggle = {}, onLongPress = { onEditValue(EditingValue("Damp", config.reverbDamping, 0f..1f, viewModel::setReverbDamping)) })
                KnobControl("WIDTH", config.reverbWidth, viewModel::setReverbWidth, 0f..1f, "x", knobSize = 84.dp, enabled = reverbCanInteract, active = reverbActive, onToggle = {}, onLongPress = { onEditValue(EditingValue("WIDTH", config.reverbWidth, 0f..1f, viewModel::setReverbWidth)) })
                KnobControl("DECAY", config.reverbRoomSize, viewModel::setReverbRoomSize, 0f..1f, "x", knobSize = 84.dp, enabled = reverbCanInteract, active = reverbActive, onToggle = {}, onLongPress = { onEditValue(EditingValue("DECAY", config.reverbRoomSize, 0f..1f, viewModel::setReverbRoomSize)) })
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(0.03f))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.06f), Color.Transparent), radius = 160f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                .padding(vertical = 12.dp)
        ) {
            val reverbCanInteract = !isReverbBypassed
            val reverbActive = config.reverbEnabled && !isReverbBypassed
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("PRE-DELAY", config.reverbPredelayMs, { newVal ->
                    viewModel.setReverbPredelay(newVal * config.reverbPredelayMix * 100f)
                }, 0f..100f, "ms", knobSize = 84.dp, enabled = reverbCanInteract, active = reverbActive, onToggle = {}, onLongPress = { onEditValue(EditingValue("Pre-Delay", config.reverbPredelayMs, 0f..100f, { v -> viewModel.setReverbPredelay(v * config.reverbPredelayMix * 100f) })) })

                KnobControl("DRY/WET", config.reverbPredelayMix, viewModel::setReverbPredelayMix, 0f..1f, "x", knobSize = 84.dp, enabled = reverbCanInteract, active = reverbActive, onToggle = {}, onLongPress = { onEditValue(EditingValue("Pre-Delay Mix", config.reverbPredelayMix, 0f..1f, viewModel::setReverbPredelayMix)) })

                KnobControl("SIZE", config.reverbRoomSize, viewModel::setReverbRoomSize, 0f..1f, "x", knobSize = 84.dp, enabled = reverbCanInteract, active = reverbActive, onToggle = {}, onLongPress = { onEditValue(EditingValue("Size", config.reverbRoomSize, 0f..1f, viewModel::setReverbRoomSize)) })
            }
        }


        // STAT CHIPS ROW (Condensed)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatChip("WET MIX", "${(config.reverbAmount * 100).roundToInt()}%")
            StatChip("DECAY", "%.1f s".format(config.reverbRoomSize * 6f))
            StatChip("STATUS", if (isReverbBypassed) "BYPASSED" else if (config.reverbEnabled) "ACTIVE" else "OFF")
        }

        if (showPresetPicker) {
            AlertDialog(
                onDismissRequest = { showPresetPicker = false },
                containerColor = Color(0xFF1A1A24),
                title = { Text("Reverb Environment", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        presets.forEach { preset ->
                            val isSelected = config.reverbPreset == preset
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) PremiumAccent.copy(0.1f) else Color.Transparent)
                                    .clickable {
                                        viewModel.setReverbPreset(preset)
                                        showPresetPicker = false
                                    }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(preset, color = if (isSelected) PremiumAccent else Color.White)
                                if (isSelected) Icon(Icons.Rounded.Check, null, tint = PremiumAccent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPresetPicker = false }) { Text("CLOSE", color = PremiumAccent) }
                }
            )
        }
    }
}

@Composable
private fun PremiumMasteringCard(uiState: PlayerUiState, viewModel: PlayerViewModel, onEditValue: (EditingValue) -> Unit) {
    val config = uiState.dsp.config
    val isBypassed = config.bitPerfectEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MASTERING & ENHANCEMENTS",
                color = PremiumAccent.copy(0.7f),
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
            if (isBypassed) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("BYPASSED", color = Color.White.copy(0.3f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        // GRID 3x2 (3 rows, 2 columns)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val knobSize = 130.dp
            val controlsEnabled = !isBypassed
            // Row 1
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KnobControl(
                    label = "MID-BASS",
                    value = config.midBassDb,
                    onValueChange = viewModel::setMidBassDb,
                    range = -12f..12f,
                    unit = "dB",
                    knobSize = knobSize,
                    isBipolar = true,
                    enabled = controlsEnabled,
                    active = config.midBassEnabled,
                    onToggle = { viewModel.setMidBassEnabled(!config.midBassEnabled) },
                    onLongPress = { onEditValue(EditingValue("MID-BASS", config.midBassDb, -12f..12f, viewModel::setMidBassDb)) }
                )
                KnobControl(
                    label = "TREBLE",
                    value = config.trebleDb,
                    onValueChange = viewModel::setTrebleDb,
                    range = -12f..12f,
                    unit = "dB",
                    knobSize = knobSize,
                    isBipolar = true,
                    enabled = controlsEnabled,
                    active = config.trebleEnabled,
                    onToggle = { viewModel.setTrebleEnabled(!config.trebleEnabled) },
                    onLongPress = { onEditValue(EditingValue("TREBLE", config.trebleDb, -12f..12f, viewModel::setTrebleDb)) }
                )
            }
            
            // Row 2
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KnobControl(
                    label = "AIR",
                    value = config.airDb,
                    onValueChange = viewModel::setAirDb,
                    range = -12f..12f,
                    unit = "dB",
                    knobSize = knobSize,
                    isBipolar = true,
                    enabled = controlsEnabled,
                    active = config.airEnabled,
                    onToggle = { viewModel.setAirEnabled(!config.airEnabled) },
                    onLongPress = { onEditValue(EditingValue("AIR", config.airDb, -12f..12f, viewModel::setAirDb)) }
                )
                KnobControl(
                    label = "BALANCE",
                    value = config.balance,
                    onValueChange = viewModel::setBalance,
                    range = -1f..1f,
                    unit = "L/R",
                    knobSize = knobSize,
                    isBipolar = true,
                    enabled = controlsEnabled,
                    active = config.balanceEnabled,
                    onToggle = { viewModel.setBalanceEnabled(!config.balanceEnabled) },
                    onLongPress = { onEditValue(EditingValue("BALANCE", config.balance, -1f..1f, viewModel::setBalance)) }
                )
            }

            // Row 3
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KnobControl(
                    label = "STEREO",
                    value = config.stereoWidth,
                    onValueChange = viewModel::setStereoWidth,
                    range = 0.5f..2.0f,
                    unit = "x",
                    knobSize = knobSize,
                    isBipolar = false,
                    enabled = controlsEnabled,
                    active = config.stereoExpansionEnabled,
                    onToggle = { viewModel.setStereoExpansionEnabled(!config.stereoExpansionEnabled) },
                    onLongPress = { onEditValue(EditingValue("STEREO WIDTH", config.stereoWidth, 0.5f..2.0f, viewModel::setStereoWidth)) }
                )
                KnobControl(
                    label = "CROSSFEED",
                    value = config.crossfeedLevel,
                    onValueChange = viewModel::setCrossfeedLevel,
                    range = 0f..1f,
                    unit = "x",
                    knobSize = knobSize,
                    isBipolar = false,
                    enabled = controlsEnabled,
                    active = config.crossfeedEnabled,
                    onToggle = { viewModel.setCrossfeedEnabled(!config.crossfeedEnabled) },
                    onLongPress = { onEditValue(EditingValue("CROSSFEED", config.crossfeedLevel, 0f..1f, viewModel::setCrossfeedLevel)) }
                )
            }
        }
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
private fun KnobControl(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    knobSize: androidx.compose.ui.unit.Dp = 90.dp,
    isBipolar: Boolean = false,
    enabled: Boolean = true,
    active: Boolean = true,
    onToggle: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    var isDragging by remember { mutableStateOf(false) }
    // Local copy to handle immediate feedback during circular drag
    var internalValue by remember { mutableFloatStateOf(value) }
    
    // Sync with external value only when not dragging
    LaunchedEffect(value, isDragging) {
        if (!isDragging) {
            // Small delay to ensure any pending ViewModel updates have "settled" 
            // and avoid jumping back to a stale value right after release
            delay(50)
            internalValue = value
        }
    }

    val progress = ((internalValue - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val isActive = enabled && active
    
    val isNeutralValue = when (unit) {
        "dB", "L/R" -> abs(internalValue) <= 0.05f
        "x" -> abs(internalValue - 1f) <= 0.02f
        else -> abs(internalValue) <= 0.05f
    }

    val sliderColor = when {
        !isActive -> Color.White.copy(0.15f)
        isNeutralValue -> Color.Transparent
        unit == "dB" || unit == "L/R" -> if (internalValue > 0.05f) Color(0xFF00FF88) else Color(0xFFFF5252)
        else -> PremiumAccent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(knobSize + 20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(isActive, range) {
                    if (!isActive) return@pointerInput
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val centerThreshold = (size.width / 2f) * 0.7f
                    
                    detectDragGestures(
                        onDragStart = { offset ->
                            val dist = sqrt((offset.x - centerX).pow(2) + (offset.y - centerY).pow(2))
                            isDragging = dist >= centerThreshold
                        },
                        onDragEnd = { 
                            isDragging = false 
                            onValueChange(internalValue) // Ensure final value is set
                        },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, _ ->
                            if (isDragging) {
                                change.consume()
                                
                                val pos = change.position
                                val angleRad = atan2(pos.y - centerY, pos.x - centerX)
                                val angleDeg = (angleRad * 180f / PI.toFloat())
                                
                                // Normalize angle to start from 135deg (Bottom Left)
                                var normalizedAngle = angleDeg - 135f
                                if (normalizedAngle < 0) normalizedAngle += 360f
                                
                                if (normalizedAngle <= 270f) {
                                    val p = normalizedAngle / 270f
                                    val newValue = range.start + p * (range.endInclusive - range.start)
                                    // Use a small threshold to avoid flooding updates
                                    if (abs(newValue - internalValue) > (range.endInclusive - range.start) / 200f) {
                                        internalValue = newValue
                                        onValueChange(newValue)
                                    }
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Ring Background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - 6.dp.toPx()
                
                drawArc(
                    color = Color.White.copy(0.05f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                )

                val sweepAngle = 270f
                val startAngle = 135f
                val currentSweep = if (isBipolar) sweepAngle * (progress - 0.5f) else sweepAngle * progress
                val arcStart = if (isBipolar) startAngle + (sweepAngle * 0.5f) else startAngle

                drawArc(
                    color = sliderColor,
                    startAngle = arcStart,
                    sweepAngle = currentSweep,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                )
            }

            // Knob Body
            Surface(
                modifier = Modifier
                    .size(knobSize * 0.7f)
                    .graphicsLayer {
                        rotationZ = (progress * 270f) - 135f
                    }
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onToggle() },
                        onLongClick = { onLongPress() }
                    ),
                shape = CircleShape,
                color = if (isActive) Color(0xFF1E2632) else Color(0xFF161B22),
                border = BorderStroke(1.dp, if (isActive) Color.White.copy(0.15f) else Color.White.copy(0.05f)),
                shadowElevation = if (isDragging) 8.dp else 2.dp
            ) {
                Box(contentAlignment = Alignment.TopCenter) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (!isActive) Color.White.copy(0.3f)
                                else if (isNeutralValue) Color.White.copy(0.4f)
                                else sliderColor.takeIf { it != Color.Transparent } ?: PremiumAccent
                            )
                    )
                }
            }
        }

        // Labels
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = if (isActive) Color.White else Color.White.copy(0.3f),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            )
            Text(
                text = when (unit) {
                    "dB" -> "${if (internalValue > 0) "+" else ""}${"%.1f".format(internalValue)}$unit"
                    "L/R" -> when {
                        internalValue < -0.05f -> "L ${"%.2f".format(-internalValue)}"
                        internalValue > 0.05f -> "R ${"%.2f".format(internalValue)}"
                        else -> "CENTER"
                    }
                    "x" -> "${"%.2f".format(internalValue)}x"
                    else -> "${"%.1f".format(internalValue)}$unit"
                },
                color = if (isActive) sliderColor.takeIf { it != Color.Transparent } ?: PremiumAccent.copy(0.7f) else Color.White.copy(0.2f),
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier.clickable(enabled = enabled) { onLongPress() }
            )
        }
    }
}

@Composable
private fun AutoEqResultRow(
    result: AutoEqProfileSummary,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                val isGithub = result.source.startsWith("GITHUB:")
                val badgeText = if (isGithub) "LIVE" else "BUILT-IN"
                val badgeColor = if (isGithub) Color(0xFF00FF88).copy(0.8f) else PremiumAccent.copy(0.6f)

                Surface(
                    color = badgeColor.copy(0.12f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.5.dp, badgeColor.copy(0.3f))
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }

            if (isLoading && result.source.startsWith("GITHUB:")) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = PremiumAccent,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Rounded.Add,
                    null,
                    tint = Color.White.copy(0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private data class BuiltInEqPreset(val name: String, val gains: List<Float>)

private fun builtInEqPresets() = listOf(
    BuiltInEqPreset("FLAT", List(10) { 0f }),
    BuiltInEqPreset("BASS BOOST", listOf(5f, 4f, 3f, 2f, 1f, 0f, 0f, 0f, 0f, 0f)),
    BuiltInEqPreset("TREBLE", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f)),
    BuiltInEqPreset("ROCK", listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 3f)),
    BuiltInEqPreset("ELECTRONIC", listOf(4f, 3f, 1f, 0f, 0f, 2f, 1f, 2f, 4f, 5f))
)
