package com.beatraxus.app.ui.components.dsp

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.min
import com.beatraxus.app.model.SpatialUiMode
import com.beatraxus.app.ui.components.PremiumSwitch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import com.beatraxus.app.utils.PresetExporter
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatraxus.app.model.AutoEqProfileSummary
import com.beatraxus.app.model.ParametricEqBand
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.ui.components.PremiumSwitch
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.*

// Premium Studio Color Palette
private val PremiumSurface = Color(0xFF08090C) // Onyx Black
private val PremiumAccent = Color(0xFF00C2A8)  // Teal
private val PremiumAccentSoft = Color(0xFF80E1D4)
private val GraphicGold = Color(0xFFD4A24C)   // Keep Graphic Line Gold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DspScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchFocused by remember { mutableStateOf(false) }
    var customPresetName by remember { mutableStateOf("") }
    val builtInPresets = remember { builtInEqPresets() }
    var showPresetsSheet by remember { mutableStateOf(false) }
    var showSavedPresetsSheet by remember { mutableStateOf(false) }
    var presetToRename by remember { mutableStateOf<String?>(null) }
    var newPresetName by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showAiPopup by remember { mutableStateOf(false) }
    var editingValue by remember { mutableStateOf<EditingValue?>(null) }
    var lastBackTime by remember { mutableLongStateOf(0L) }

    val safeBack = {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackTime > 500) {
            lastBackTime = currentTime
            if (editingValue == null && !showMenu && !showSaveDialog) {
                onBack()
            } else {
                editingValue = null
                showMenu = false
                showSaveDialog = false
            }
        }
    }

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
                            val summary = if (presets.size == 1) "'${presets[0].name}'" else "${presets.size} presets"
                            android.widget.Toast.makeText(context, "Imported $summary", android.widget.Toast.LENGTH_SHORT).show()
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


    BackHandler(onBack = safeBack)

    // Real-time FFT state for Graphic Response
    var liveFftBars by remember { mutableStateOf(FloatArray(32)) }
    LaunchedEffect(viewModel) {
        val fftSize = 512
        val ring = FloatArray(fftSize)
        var ringPos = 0
        while (true) {
            val capture = viewModel.captureLiveWindow()
            if (capture != null && capture.samples.isNotEmpty()) {
                val ch = capture.channels.coerceAtLeast(1)
                val frames = capture.samples.size / ch
                if (frames > 0) {
                    for (f in 0 until frames) {
                        val l = capture.samples[f * ch]
                        val r = if (ch > 1) capture.samples[f * ch + 1] else l
                        ring[ringPos] = (l + r) * 0.5f
                        ringPos = (ringPos + 1) % fftSize
                    }
                    val windowed = FloatArray(fftSize) { idx ->
                        val sample = ring[(ringPos + idx) % fftSize]
                        val hann = 0.5f - 0.5f * cos(2.0 * PI * idx / (fftSize - 1)).toFloat()
                        sample * hann
                    }
                    val mags = fftMagnitude(windowed)
                    val bars = FloatArray(32)
                    val numBins = mags.size
                    val logMin = log10(40.0)
                    val logMax = log10(20000.0)
                    for (b in bars.indices) {
                        val fStart = 10.0.pow(logMin + (b.toDouble() / bars.size) * (logMax - logMin))
                        val fEnd = 10.0.pow(logMin + ((b + 1).toDouble() / bars.size) * (logMax - logMin))
                        val binStart = (fStart * fftSize / 44100.0).toInt().coerceIn(0, numBins - 1)
                        val binEnd = (fEnd * fftSize / 44100.0).toInt().coerceIn(binStart + 1, numBins)
                        var s = 0f
                        var count = 0
                        for (i in binStart until binEnd) {
                            s += mags[i]
                            count++
                        }
                        if (count > 0) {
                            val boost = 1.0f + (b.toFloat() / bars.size) * 1.5f
                            bars[b] = (s / count / (fftSize / 8f) * boost).coerceIn(0f, 1f)
                        }
                    }
                    liveFftBars = bars
                }
            }
            delay(16) // ~60fps
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0E1116), // Studio Dark Gray
                        Color(0xFF050608)  // Near Black
                    )
                )
            )
    ) {
        // Subtle ambient gold glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(PremiumAccent.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(500f, 0f),
                        radius = 2500f
                    )
                )
        )

        val pagerState = rememberPagerState(pageCount = { 4 })

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.background(Color.Transparent),
            contentWindowInsets = WindowInsets.systemBars,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "STUDIO DSP",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 4.sp,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.graphicsLayer { alpha = 0.9f }
                            )
                            Text(
                                text = "Settings for: ${uiState.dsp.activeOutputDeviceLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(0.4f),
                                fontSize = 9.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Surface(
                                modifier = Modifier.padding(top = 8.dp),
                                color = Color.White.copy(0.03f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(0.5.dp, Color.White.copy(0.1f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    val pageIcons = listOf(
                                        Icons.Rounded.GraphicEq,
                                        Icons.Rounded.AutoAwesome,
                                        Icons.Rounded.Waves,
                                        Icons.Rounded.SurroundSound
                                    )
                                    pageIcons.forEachIndexed { i, icon ->
                                        val isActive = pagerState.currentPage == i
                                        val size by animateDpAsState(
                                            targetValue = if (isActive) 18.dp else 12.dp,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                            label = "icon_size"
                                        )
                                        val color by animateColorAsState(
                                            targetValue = if (isActive) PremiumAccent else Color.White.copy(alpha = 0.3f),
                                            label = "icon_color"
                                        )
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(size),
                                            tint = color
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = safeBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                )
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalAlignment = Alignment.Top
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(if (page == 0) 4.dp else 2.dp)
                ) {
                    if (page == 0) {
                        // Settings text moved to top bar
                        val currentGains = uiState.dsp.config.eqBands.map { it.gainDb }
                        val activePresetName = uiState.dsp.customEqPresets.find { it.bands.map { b -> b.gainDb } == currentGains }?.name
                            ?: builtInPresets.find { it.gains == currentGains }?.name
                            ?: "Custom"

                        PremiumGraphicCard(uiState, activePresetName, liveFftBars)

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
                                val currentGains = currentConfig.eqBands.map { it.gainDb }
                                val activeBuiltIn = builtInPresets.find { it.gains == currentGains }?.name
                                val activeCustom = uiState.dsp.customEqPresets.find { custom ->
                                    custom.bands.map { it.gainDb } == currentGains
                                }?.name

                                val exportName = activeCustom ?: activeBuiltIn ?: "Custom Preset"
                                val safeFileName = exportName.replace(Regex("[^a-zA-Z0-9\\.\\-]"), "_") + ".json"

                                exportJson = PresetExporter.exportToCurrentJson(
                                    name = exportName,
                                    preamp = currentConfig.preampDb,
                                    bands = currentConfig.eqBands
                                )
                                exportLauncher.launch(safeFileName)
                            },
                            showMenu = showMenu,
                            onShowMenuChange = { showMenu = it },
                            onShowSavedPresets = { showSavedPresetsSheet = true },
                            onShowAiOptions = { showAiPopup = true },
                            onShowDevicePicker = { showDevicePicker = true }
                        )

                        PremiumEqualizerCard(
                            uiState = uiState,
                            viewModel = viewModel,
                            onShowPresets = { showPresetsSheet = true },
                            onShowAiOptions = { showAiPopup = true },
                            onEditValue = { editingValue = it }
                        )
                    } else if (page == 1) {
                        PremiumMasteringCard(uiState, viewModel, onEditValue = { editingValue = it })
                    } else if (page == 2) {
                        PremiumReverbCard(uiState, viewModel, onEditValue = { editingValue = it })
                    } else {
                        PremiumSoundStageCard(uiState, viewModel, onEditValue = { editingValue = it })
                    }
                }
            }
        }

        if (showSavedPresetsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSavedPresetsSheet = false },
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            "MY SAVED PRESETS",
                            color = PremiumAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            letterSpacing = 2.sp
                        )
                    }

                    if (uiState.dsp.customEqPresets.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f))
                            ) {
                                Text(
                                    "No saved presets yet. Create one by adjusting the sliders and using the 'Save' option in the menu.",
                                    modifier = Modifier.padding(24.dp),
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    items(uiState.dsp.customEqPresets) { preset ->
                        val currentGains = uiState.dsp.config.eqBands.map { it.gainDb }
                        val presetGains = preset.bands.map { it.gainDb }
                        val isActive = currentGains == presetGains

                        Card(
                            onClick = {
                                viewModel.applySavedEqPreset(preset.name)
                                showSavedPresetsSheet = false
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
                            ),
                            border = BorderStroke(1.dp, if (isActive) PremiumAccent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.1f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            preset.name,
                                            color = if (isActive) PremiumAccent else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "PREAMP: ${if (preset.preampDb >= 0) "+" else ""}${preset.preampDb} dB",
                                            color = Color.White.copy(0.4f),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 0.5.sp
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Icon(
                                                Icons.Rounded.CheckCircle,
                                                null,
                                                tint = PremiumAccent,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }

                                        var showItemMenu by remember { mutableStateOf(false) }
                                        Box(contentAlignment = Alignment.Center) {
                                            IconButton(
                                                onClick = { showItemMenu = !showItemMenu },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.MoreVert,
                                                    null,
                                                    tint = if (showItemMenu) PremiumAccent else Color.White.copy(0.4f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = showItemMenu,
                                                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                                                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                                                modifier = Modifier.padding(end = 40.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .background(Color(0xFF1A1A24), RoundedCornerShape(12.dp))
                                                        .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
                                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            showItemMenu = false
                                                            presetToRename = preset.name
                                                            newPresetName = preset.name
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.Edit, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                                                    }
                                                    Box(Modifier.width(1.dp).height(16.dp).background(Color.White.copy(0.1f)))
                                                    IconButton(
                                                        onClick = {
                                                            showItemMenu = false
                                                            viewModel.deleteCustomEqPreset(preset.name)
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.DeleteOutline, null, tint = Color.Red.copy(0.7f), modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Graphic Preview below name
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(0.4f))
                                        .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp))
                                ) {
                                    EqPreviewGraph(bands = preset.bands, enabled = isActive)
                                }

                                // All Band Values
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    preset.bands.forEach { band ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = if (band.frequencyHz >= 1000f) "${(band.frequencyHz / 1000f).toInt()}k" else "${band.frequencyHz.toInt()}",
                                                color = Color.White.copy(0.2f),
                                                fontSize = 7.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${if (band.gainDb >= 0) "+" else ""}${"%.1f".format(band.gainDb)}",
                                                color = if (isActive) PremiumAccent.copy(0.7f) else Color.White.copy(0.4f),
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (presetToRename != null) {
            AlertDialog(
                onDismissRequest = { presetToRename = null },
                containerColor = Color(0xFF1A1A24),
                title = { Text("Rename Preset", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = PremiumAccent,
                            focusedBorderColor = PremiumAccent,
                            unfocusedBorderColor = Color.White.copy(0.1f)
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        presetToRename?.let { old ->
                            viewModel.renameCustomEqPreset(old, newPresetName)
                        }
                        presetToRename = null
                    }) {
                        Text("Rename", color = PremiumAccent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { presetToRename = null }) {
                        Text("Cancel", color = Color.White.copy(0.6f))
                    }
                }
            )
        }

        if (showPresetsSheet) {
            val autoEqListState = rememberLazyListState()
            var isSearchVisible by remember { mutableStateOf(true) }
            val autoEqNestedScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val delta = available.y
                        if (delta > 8f) isSearchVisible = true
                        else if (delta < -8f) isSearchVisible = false
                        return Offset.Zero
                    }
                }
            }

            LaunchedEffect(showPresetsSheet) {
                viewModel.searchAutoEqProfiles()
            }
            Dialog(
                onDismissRequest = { showPresetsSheet = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.85f)
                        .padding(vertical = 24.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
                    border = BorderStroke(1.dp, Color.White.copy(0.1f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(autoEqNestedScrollConnection)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "AUTO-EQ DATABASE",
                                color = PremiumAccent,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                letterSpacing = 2.sp
                            )
                            IconButton(onClick = { showPresetsSheet = false }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(20.dp))
                            }
                        }

                        BackHandler(enabled = isSearchFocused || showPresetsSheet) {
                            if (isSearchFocused) {
                                focusManager.clearFocus()
                            } else {
                                showPresetsSheet = false
                            }
                        }

                        AnimatedVisibility(
                            visible = isSearchVisible,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Box(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                                OutlinedTextField(
                                    value = uiState.dsp.autoEqQuery,
                                    onValueChange = {
                                        viewModel.setAutoEqQuery(it)
                                        viewModel.searchAutoEqProfiles()
                                    },
                                    placeholder = { Text("Search 1000+ models...", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .onFocusChanged { isSearchFocused = it.isFocused },
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        viewModel.searchAutoEqProfiles()
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }),
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                                    },
                                    trailingIcon = {
                                        if (uiState.dsp.autoEqQuery.isNotEmpty()) {
                                            IconButton(onClick = {
                                                viewModel.setAutoEqQuery("")
                                                viewModel.searchAutoEqProfiles()
                                            }) {
                                                Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
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

                        LazyColumn(
                            state = autoEqListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "POPULAR MODELS",
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                    val popular = listOf("Sony WH-1000XM4", "Sennheiser HD 600", "AirPods Pro", "Bose QC35")
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
                            }

                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.AutoAwesome, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                                    val count = uiState.dsp.autoEqResults.size
                                    Text(
                                        if (uiState.dsp.autoEqQuery.isBlank()) "ALL PROFILES ($count)" else "SEARCH RESULTS ($count)",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            if (uiState.dsp.autoEqLoading) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = PremiumAccent, strokeWidth = 3.dp)
                                    }
                                }
                            }

                            item {
                                uiState.dsp.config.autoEqProfile?.let {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(PremiumAccent.copy(alpha = 0.1f))
                                            .border(1.dp, PremiumAccent.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text("ACTIVE PROFILE", color = PremiumAccent, fontWeight = FontWeight.Black, fontSize = 8.sp)
                                            Text(it.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }

                            items(uiState.dsp.autoEqResults) { result ->
                                val isSelected = uiState.dsp.config.autoEqEnabled && uiState.dsp.config.autoEqProfile?.name == result.name
                                AutoEqResultRow(
                                    result = result,
                                    isLoading = uiState.dsp.autoEqLoading,
                                    isSelected = isSelected,
                                    onClick = {
                                        viewModel.applyAutoEqProfile(result)
                                    }
                                )
                            }

                            if (uiState.dsp.autoEqResults.isEmpty() && !uiState.dsp.autoEqLoading) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f))
                                    ) {
                                        Text(
                                            "No profiles found. Try a different search term.",
                                            modifier = Modifier.padding(20.dp),
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            LaunchedEffect(showPresetsSheet) {}
        }

        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showMenu = false }
                    .zIndex(10f)
            )
        }

        if (showAiPopup) {
            AiOptionsPopup(
                uiState = uiState,
                viewModel = viewModel,
                onDismiss = { showAiPopup = false }
            )
        }

        if (showDevicePicker) {
            val knownDevices by viewModel.listKnownDevices().collectAsStateWithLifecycle(initialValue = emptySet())

            AlertDialog(
                onDismissRequest = { showDevicePicker = false },
                containerColor = Color(0xFF1A1A24),
                title = { Text("Copy Settings From", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (knownDevices.isEmpty()) {
                            Text("No other device settings found.", color = Color.White.copy(0.5f), fontSize = 13.sp)
                        } else {
                            knownDevices.filter { it != viewModel.getCurrentDeviceId() }.forEach { deviceId ->
                                Card(
                                    onClick = {
                                        viewModel.copySettingsFromDevice(deviceId)
                                        showDevicePicker = false
                                    },
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.05f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        deviceId.substringBeforeLast("_"),
                                        modifier = Modifier.padding(16.dp),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDevicePicker = false }) { Text("CANCEL", color = PremiumAccent) }
                }
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiOptionsPopup(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = PremiumAccent, modifier = Modifier.size(24.dp))
                Text(
                    "AI EQ STUDIO",
                    color = PremiumAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable AI EQ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Real-time intelligent correction", color = Color.White.copy(0.5f), fontSize = 12.sp)
                        }
                        PremiumSwitch(
                            checked = uiState.dsp.config.aiEqEnabled,
                            onCheckedChange = { viewModel.setAiEqEnabled(it) },
                            accentColor = PremiumAccent
                        )
                    }

                    if (uiState.dsp.config.aiEqEnabled) {
                        HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Genre", color = Color.White.copy(0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("AI Verified", color = PremiumAccent, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Mood", color = Color.White.copy(0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("AI Verified", color = PremiumAccent, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("EQ Profile", color = Color.White.copy(0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Optimized", color = PremiumAccent, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(0.05f))

                    Text(
                        "How it works:",
                        color = PremiumAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        "Our AI analyzes each song's spectral signature (genre, mood, dynamics) to apply a specialized EQ profile that compensates for common recording imbalances, providing a more consistent and immersive listening experience.",
                        color = Color.White.copy(0.7f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PremiumAccent.copy(alpha = 0.05f))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Info, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                            Text(
                                "AI corrections are applied on top of your current EQ settings.",
                                color = Color.White.copy(0.6f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent, contentColor = Color.Black)
            ) {
                Text("DONE", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AnalysisDetailsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = PremiumAccent, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PremiumGraphicCard(uiState: PlayerUiState, presetName: String, fftBars: FloatArray = FloatArray(0)) {
    val config = uiState.dsp.config
    val displayBands = config.eqBands
    val displayEnabled = config.eqEnabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PremiumSurface.copy(alpha = 0.4f))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.1f), Color.Transparent)),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "GRAPHIC RESPONSE",
                color = PremiumAccent.copy(0.6f),
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
            Text(
                text = presetName.uppercase(),
                color = PremiumAccent,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 24.dp)
                    .basicMarquee(),
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(102.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
        ) {
            EqPreviewGraph(displayBands, displayEnabled, fftBars = fftBars)
        }
    }
}

@Composable
private fun PremiumEqualizerCard(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onShowPresets: () -> Unit,
    onShowAiOptions: () -> Unit,
    onEditValue: (EditingValue) -> Unit
) {
    val config = uiState.dsp.config
    val isLocked = config.settingsLocked
    val isEqBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypassEq
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PremiumSurface.copy(alpha = 0.4f))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.1f), Color.Transparent)),
                RoundedCornerShape(16.dp)
            )
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EQUALIZER", color = PremiumAccent, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.width(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (config.autoEqEnabled) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f))
                            .clickable { onShowPresets() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            null,
                            tint = if (config.autoEqEnabled) PremiumAccent else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "AUTO-EQ",
                            color = if (config.autoEqEnabled) PremiumAccent else Color.White.copy(alpha = 0.3f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (config.aiEqEnabled) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f))
                            .clickable { onShowAiOptions() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            null,
                            tint = if (config.aiEqEnabled) PremiumAccent else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "AI",
                            color = if (config.aiEqEnabled) PremiumAccent else Color.White.copy(alpha = 0.3f),
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
                    onCheckedChange = { viewModel.setEqEnabled(it) },
                    accentColor = PremiumAccent
                )
            }
        }

        // Custom Premium Preamp Slider
        PremiumPreampSlider(
            value = config.preampDb,
            onValueChange = { viewModel.setPreampDb(it) },
            enabled = !isEqBypassed && config.eqEnabled && !isLocked,
            onEditValue = {
                onEditValue(EditingValue("PREAMP", config.preampDb, -15f..15f, viewModel::setPreampDb))
            }
        )

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
private fun PremiumPreampSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    onEditValue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PREAMP GAIN",
                color = PremiumAccent.copy(0.6f),
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp
            )
            Surface(
                onClick = { if (enabled) onEditValue() },
                color = Color.White.copy(0.04f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(0.5.dp, Color.White.copy(0.1f))
            ) {
                Text(
                    text = if (value >= 0) "+%.1f dB".format(value) else "%.1f dB".format(value),
                    color = if (value != 0f) (if (value > 0) Color(0xFFDDDDDD) else Color(0xFF888888)) else Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val pos = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange((pos * 30f) - 15f)
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val pos = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange((pos * 30f) - 15f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midX = w / 2f
                val trackH = 6.dp.toPx()
                val corner = CornerRadius(trackH / 2f, trackH / 2f)

                // 1. Recessed Track
                drawRoundRect(
                    color = Color.Black.copy(0.4f),
                    topLeft = Offset(0f, (h - trackH) / 2f),
                    size = Size(w, trackH),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = Color.White.copy(0.05f),
                    topLeft = Offset(0f, (h - trackH) / 2f),
                    size = Size(w, trackH),
                    cornerRadius = corner,
                    style = Stroke(1.dp.toPx())
                )

                // 2. Center Detent (0dB)
                drawLine(
                    color = Color.White.copy(0.2f),
                    start = Offset(midX, (h - 20.dp.toPx()) / 2f),
                    end = Offset(midX, (h + 20.dp.toPx()) / 2f),
                    strokeWidth = 2.dp.toPx()
                )

                // 3. Etched Micro-Ticks
                val tickStep = w / 30f
                for (i in 0..30) {
                    val x = i * tickStep
                    val isMajor = i % 5 == 0
                    val tickH = if (isMajor) 12.dp.toPx() else 6.dp.toPx()
                    drawLine(
                        color = Color.White.copy(if (isMajor) 0.15f else 0.05f),
                        start = Offset(x, (h - tickH) / 2f),
                        end = Offset(x, (h + tickH) / 2f),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 4. Bipolar Gradient Fill
                val progressX = ((value + 15f) / 30f) * w
                if (value != 0f) {
                    val startX = midX
                    val endX = progressX
                    val color = if (value > 0) Color(0xFFDDDDDD) else Color(0xFF888888)
                    
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = if (value > 0) listOf(color.copy(0.2f), color) else listOf(color, color.copy(0.2f)),
                            startX = min(startX, endX),
                            endX = max(startX, endX)
                        ),
                        topLeft = Offset(min(startX, endX), (h - trackH) / 2f),
                        size = Size(abs(endX - startX), trackH),
                        cornerRadius = corner
                    )

                    // Active Glow
                    drawRoundRect(
                        color = color.copy(0.15f),
                        topLeft = Offset(min(startX, endX) - 2f, (h - trackH) / 2f - 2f),
                        size = Size(abs(endX - startX) + 4f, trackH + 4f),
                        cornerRadius = corner
                    )
                }

                // 5. Machined Aluminum Thumb
                val thumbW = 42.dp.toPx()
                val thumbH = 28.dp.toPx()
                val thumbX = progressX - thumbW / 2f
                val thumbY = (h - thumbH) / 2f

                // Thumb Body
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF4A4E58), Color(0xFF2A2E38), Color(0xFF1A1E26))
                    ),
                    topLeft = Offset(thumbX, thumbY),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawRoundRect(
                    color = Color.White.copy(0.15f),
                    topLeft = Offset(thumbX, thumbY),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )

                // Neon Indicator Line on Thumb
                val indicatorColor = if (value == 0f) Color.White.copy(0.4f) else (if (value > 0) Color(0xFFDDDDDD) else Color(0xFF888888))
                drawLine(
                    color = indicatorColor,
                    start = Offset(progressX, thumbY + 4.dp.toPx()),
                    end = Offset(progressX, thumbY + thumbH - 4.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Glow for indicator
                if (value != 0f) {
                    drawLine(
                        color = indicatorColor.copy(0.4f),
                        start = Offset(progressX, thumbY + 2.dp.toPx()),
                        end = Offset(progressX, thumbY + thumbH - 2.dp.toPx()),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Thumb Texture (Grip)
                for (j in 0..2) {
                    val gripX = thumbX + (j + 1) * (thumbW / 4f)
                    if (abs(gripX - progressX) > 4.dp.toPx()) {
                        drawLine(
                            color = Color.Black.copy(0.3f),
                            start = Offset(gripX, thumbY + 8.dp.toPx()),
                            end = Offset(gripX, thumbY + thumbH - 8.dp.toPx()),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                }
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
        band.gainDb > 0.1f -> Color(0xFF00FF88) // High-end Level Green
        band.gainDb < -0.1f -> Color(0xFFFF5252) // Warning Red
        else -> PremiumAccent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(42.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp) // Increased spacing to prevent knob overlap
    ) {
        Text(
            text = if (band.gainDb >= 0) "+%.1f".format(band.gainDb) else "%.1f".format(band.gainDb),
            color = if (isActive) gainColor else Color.White.copy(0.2f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(38.dp).clickable { onLongPress() }
        )

        Box(
            modifier = Modifier
                .height(250.dp) 
                .width(36.dp)
                .padding(vertical = 12.dp)
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
            // Fader Track - Studio Metal Style
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0A0C10), Color(0xFF1A1F26), Color(0xFF0A0C10))
                        )
                    )
                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(4.dp))
            )

            // Etched Center Line
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(Color.Black.copy(0.6f))
            )

            // Scale Markings
            Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                val step = size.height / 12f
                for (i in 0..12) {
                    val y = i * step
                    val isMajor = i % 3 == 0
                    val isCenter = i == 6
                    
                    drawLine(
                        color = when {
                            isCenter -> PremiumAccent.copy(0.4f)
                            isMajor -> Color.White.copy(0.15f)
                            else -> Color.White.copy(0.05f)
                        },
                        start = Offset(if (isMajor) 4.dp.toPx() else 10.dp.toPx(), y),
                        end = Offset(size.width - (if (isMajor) 4.dp.toPx() else 10.dp.toPx()), y),
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }
            }

            // Glow from under the knob
            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (202.dp * (1f - gainProgress)) - 20.dp)
                        .size(40.dp, 60.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(gainColor.copy(0.1f), Color.Transparent)
                            )
                        )
                )
            }

            // Studio Fader Knob
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (202.dp * (1f - gainProgress)))
                    .graphicsLayer { translationY = -24.dp.toPx() }
                    .size(40.dp, 48.dp)
                    .shadow(12.dp, RoundedCornerShape(4.dp), spotColor = Color.Black)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF4A4E58), Color(0xFF2A2E38), Color(0xFF1A1E26))
                        ),
                        RoundedCornerShape(4.dp)
                    )
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Knob Center Line (Indicator)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            if (isActive) gainColor else Color.White.copy(0.2f),
                            RoundedCornerShape(1.dp)
                        )
                        .shadow(if (isActive) 8.dp else 0.dp, spotColor = gainColor, ambientColor = gainColor)
                )
                
                // Finger Grip Texture
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    repeat(3) {
                        Box(Modifier.size(16.dp, 1.dp).background(Color.Black.copy(0.3f)))
                    }
                }
            }
        }

        Text(
            text = if (band.frequencyHz >= 1000f)
                "%.1fk".format(band.frequencyHz / 1000f).removeSuffix(".0")
            else "${band.frequencyHz.toInt()}",
            color = if (isActive) Color.White.copy(0.9f) else Color.White.copy(0.3f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            modifier = Modifier.clickable(enabled = isActive) {
                onFrequencyEdit()
            }
        )
    }
}

@Composable
private fun EqPreviewGraph(bands: List<ParametricEqBand>, enabled: Boolean, showDots: Boolean = true, fftBars: FloatArray = FloatArray(0)) {
    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val dbScale = h / 28f // Scale for +/- 12dB plus margin

        // Draw FFT "Premium Spectral Beam" Spectrum behind the curve
        if (fftBars.isNotEmpty()) {
            val barGap = 4f
            val barW = (w / fftBars.size) - barGap
            val cornerRadius = CornerRadius(barW / 2f, barW / 2f)

            fftBars.forEachIndexed { i, mag ->
                val x = i * (barW + barGap)
                val barH = (mag * h * 0.45f).coerceAtLeast(2f)

                // Frequency-dependent color selection (Deep Gray -> Medium Gray -> Dim White)
                val colorFraction = i.toFloat() / fftBars.size
                val baseColor = when {
                    colorFraction < 0.5f -> lerp(Color(0xFF444444), Color(0xFF888888), colorFraction / 0.5f)
                    else -> lerp(Color(0xFF888888), Color(0xFFDDDDDD), (colorFraction - 0.5f) / 0.5f)
                }

                // 1. Neon Bloom (Glow)
                if (mag > 0.05f) {
                    drawRoundRect(
                        color = baseColor.copy(alpha = 0.15f * mag),
                        topLeft = Offset(x - 4f, midY - barH - 4f),
                        size = Size(barW + 8f, barH + 4f),
                        cornerRadius = cornerRadius
                    )
                }

                // 2. Main Spectral Beam
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            baseColor.copy(alpha = 0.9f),
                            baseColor.copy(alpha = 0.4f)
                        ),
                        startY = midY - barH,
                        endY = midY
                    ),
                    topLeft = Offset(x, midY - barH),
                    size = Size(barW, barH),
                    cornerRadius = cornerRadius
                )

                // 3. High-Intensity Luminous Tip
                if (mag > 0.4f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (mag - 0.4f).coerceIn(0f, 0.8f)),
                        radius = barW / 2.5f,
                        center = Offset(x + barW / 2f, midY - barH + barW / 2f)
                    )
                }

                // 4. Mirrored Studio Glass Reflection
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            baseColor.copy(alpha = 0.3f * mag),
                            Color.Transparent
                        ),
                        startY = midY,
                        endY = midY + barH * 0.8f
                    ),
                    topLeft = Offset(x, midY),
                    size = Size(barW, barH * 0.8f),
                    cornerRadius = cornerRadius
                )
            }
        }

        // Draw professional grid lines
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            val y = midY - db * dbScale
            drawLine(
                color = Color.White.copy(alpha = if (db == 0f) 0.15f else 0.04f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (db == 0f) 1.5f else 0.8f
            )
        }
        
        // Frequency grid lines (logarithmic-ish spacing for visual feel)
        val freqLines = listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)
        freqLines.forEach { p ->
            drawLine(
                color = Color.White.copy(alpha = 0.04f),
                start = Offset(w * p, 0f),
                end = Offset(w * p, h),
                strokeWidth = 0.8f
            )
        }

        if (bands.isNotEmpty()) {
            val points = if (bands.size == 1) {
                listOf(Offset(0f, midY - (bands[0].gainDb * dbScale)), Offset(w, midY - (bands[0].gainDb * dbScale)))
            } else {
                bands.mapIndexed { i, band ->
                    val x = (i.toFloat() / (bands.size - 1)) * w
                    val y = midY - (band.gainDb * dbScale)
                    Offset(x, y)
                }
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

            // Draw glowing line
            if (enabled) {
                drawPath(
                    path = path,
                    color = GraphicGold.copy(0.3f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            
            drawPath(
                path = path,
                color = if (enabled) GraphicGold else Color.White.copy(0.2f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Draw gradient fill under curve
            val fillPath = Path()
            fillPath.addPath(path)
            fillPath.lineTo(points.last().x, h) // Fill to bottom instead of midY for more drama
            fillPath.lineTo(points.first().x, h)
            fillPath.close()
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        (if (enabled) GraphicGold else Color.White).copy(alpha = 0.15f),
                        (if (enabled) GraphicGold else Color.White).copy(alpha = 0.02f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // High-precision Band dots
            if (showDots) {
                points.forEachIndexed { i, pt ->
                    if (i < bands.size) {
                        val band = bands[i]
                        if (band.enabled) {
                            val dotColor = if (enabled) GraphicGold else Color.White.copy(0.4f)
                            // Outer glow
                            drawCircle(color = dotColor.copy(0.3f), radius = 6.dp.toPx(), center = pt)
                            // Core
                            drawCircle(color = dotColor, radius = 3.dp.toPx(), center = pt)
                            drawCircle(color = Color.White, radius = 1.dp.toPx(), center = pt)
                        }
                    }
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
    onExport: () -> Unit,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onShowSavedPresets: () -> Unit,
    onShowAiOptions: () -> Unit,
    onShowDevicePicker: () -> Unit
) {
    val currentGains = uiState.dsp.config.eqBands.map { it.gainDb }
    val activeBuiltIn = presets.find { it.gains == currentGains }?.name
    val activeCustom = uiState.dsp.customEqPresets.find { custom ->
        custom.bands.map { it.gainDb } == currentGains
    }?.name
    val isTweak = activeBuiltIn == null && activeCustom == null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PremiumSurface.copy(alpha = 0.4f))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.1f), Color.Transparent)),
                RoundedCornerShape(16.dp)
            )
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AUDIO PRESETS", color = PremiumAccent.copy(0.7f), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (activeCustom != null) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f))
                        .clickable { onShowSavedPresets() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        null,
                        tint = if (activeCustom != null) PremiumAccent else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "CUSTOM EQ",
                        color = if (activeCustom != null) PremiumAccent else Color.White.copy(alpha = 0.3f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box {
                IconButton(onClick = { onShowMenuChange(true) }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        "Menu",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { onShowMenuChange(false) },
                    modifier = Modifier.width(180.dp).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = Color(0xFF1A1A24)
                ) {
                    val menuItemPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    DropdownMenuItem(
                        text = { Text("Import Presets", color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onShowMenuChange(false)
                            onImport()
                        },
                        leadingIcon = { Icon(Icons.Rounded.FileDownload, null, tint = PremiumAccent, modifier = Modifier.size(16.dp)) },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = { Text("Export Presets", color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onShowMenuChange(false)
                            onExport()
                        },
                        leadingIcon = { Icon(Icons.Rounded.FileUpload, null, tint = PremiumAccent, modifier = Modifier.size(16.dp)) },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = { Text("AI Options", color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onShowMenuChange(false)
                            onShowAiOptions()
                        },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = PremiumAccent, modifier = Modifier.size(16.dp)) },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = { Text(if (uiState.dsp.config.settingsLocked) "Unlock Config" else "Lock Config", color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onShowMenuChange(false)
                            viewModel.setSettingsLocked(!uiState.dsp.config.settingsLocked)
                        },
                        leadingIcon = {
                            Icon(
                                if (uiState.dsp.config.settingsLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                null,
                                tint = PremiumAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = { Text("Copy from Device", color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onShowMenuChange(false)
                            onShowDevicePicker()
                        },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = PremiumAccent, modifier = Modifier.size(16.dp)) },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = { Text("Reset this Device", color = Color.White, fontSize = 12.sp) },
                        onClick = {
                            onShowMenuChange(false)
                            viewModel.resetCurrentDevicePreset()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = PremiumAccent, modifier = Modifier.size(16.dp)) },
                        contentPadding = menuItemPadding
                    )
                    if (isTweak) {
                        DropdownMenuItem(
                            text = { Text("Save Preset", color = Color.White, fontSize = 12.sp) },
                            onClick = {
                                onShowMenuChange(false)
                                onToggleSaveDialog(true)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Save, null, tint = PremiumAccent, modifier = Modifier.size(16.dp)) },
                            contentPadding = menuItemPadding
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
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = if (isActive) PremiumAccent else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
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
private fun PremiumSoundStageCard(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val density = LocalDensity.current
    val config = uiState.dsp.config
    val isSpatialBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypass3DStage
    val spatialActive = config.spatialAudioEnabled && !isSpatialBypassed

    data class StageNode(
        val name: String,
        val icon: ImageVector,
        val az: Float,
        val dist: Float,
        val color: Color
    )

    val nodes = remember {
        listOf(
            StageNode("Vocals", Icons.Rounded.Person, 0f, 2.0f, Color(0xFF42A5F5)),
            StageNode("Drums", Icons.Rounded.LibraryMusic, 45f, 2.8f, Color(0xFFFF7043)),
            StageNode("Keys", Icons.Rounded.Piano, 90f, 1.8f, Color(0xFFEC407A)),
            StageNode("Lead Guitar", Icons.Rounded.MusicNote, 135f, 2.3f, Color(0xFF66BB6A)),
            StageNode("Ambience", Icons.Rounded.GraphicEq, 180f, 3.5f, Color(0xFFAB47BC)),
            StageNode("Backing Vocals", Icons.Rounded.Groups, 225f, 2.5f, Color(0xFFFFEE58)),
            StageNode("Bass", Icons.Rounded.Speaker, 270f, 2.2f, Color(0xFF26C6DA)),
            StageNode("Guitar", Icons.Rounded.MusicNote, 315f, 2.6f, Color(0xFF7E57C2))
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Spatial Audio Title
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Color.White.copy(0.05f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "SPATIAL AUDIO",
                    color = PremiumAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            }

            // UI Mode Toggle
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(0.3f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpatialModeChip("CLASSIC", config.spatialUiMode == SpatialUiMode.CLASSIC) {
                    viewModel.setSpatialUiMode(SpatialUiMode.CLASSIC)
                }
                SpatialModeChip("MODERN", config.spatialUiMode == SpatialUiMode.MODERN) {
                    viewModel.setSpatialUiMode(SpatialUiMode.MODERN)
                }
            }
        }

        if (config.spatialUiMode == SpatialUiMode.MODERN) {
            ModernSpatialAudioContent(uiState, viewModel, onEditValue)
        } else {
            ClassicSpatialAudioCard(uiState, viewModel, onEditValue)
        }
    }
}

@Composable
private fun SpatialModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) PremiumAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White.copy(0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun ClassicSpatialAudioCard(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val config = uiState.dsp.config
    val spatialActive = config.audio3DStageEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ClassicSoundStageView(uiState, viewModel)
        ClassicControlPanel(uiState, viewModel, onEditValue)
    }
}

@Composable
private fun ClassicSoundStageView(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel
) {
    val density = LocalDensity.current
    val config = uiState.dsp.config
    val spatialActive = config.audio3DStageEnabled
    val realtimeLevels by viewModel.realtimeLevels.collectAsState()
    val levelL = realtimeLevels[0]
    val levelR = realtimeLevels[1]

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 24.dp)
            .aspectRatio(1.0f),
        contentAlignment = Alignment.Center
    ) {
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val maxOrbitRadius = (min(viewWidth, viewHeight) / 2f) * 0.82f

        Canvas(modifier = Modifier.fillMaxSize()) {
            for (i in 1..4) {
                drawCircle(
                    color = Color.White.copy(0.04f + (i * 0.01f)),
                    radius = maxOrbitRadius * (i / 4f),
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Real-time Spatial Analyzer Visualizer
            if (spatialActive) {
                val barCount = 48
                val innerRadius = maxOrbitRadius * 0.22f
                for (i in 0 until barCount) {
                    val angle = (i.toFloat() / barCount) * 2f * PI.toFloat() - PI.toFloat() / 2f
                    // Map left half of circle to L level, right half to R level
                    val isLeft = cos(angle) < 0
                    val rawLevel = if (isLeft) levelL else levelR
                    val level = rawLevel.coerceIn(0f, 1.2f)
                    
                    val barLen = 4.dp.toPx() + (level * 24.dp.toPx())
                    val start = Offset(
                        centerX + innerRadius * cos(angle),
                        centerY + innerRadius * sin(angle)
                    )
                    val end = Offset(
                        centerX + (innerRadius + barLen) * cos(angle),
                        centerY + (innerRadius + barLen) * sin(angle)
                    )
                    
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(PremiumAccent.copy(0.2f), PremiumAccent.copy(0.5f + level * 0.4f)),
                            start = start,
                            end = end
                        ),
                        start = start,
                        end = end,
                        strokeWidth = (2.dp.toPx() + level * 1.5.dp.toPx()),
                        cap = StrokeCap.Round
                    )
                }
            }

            drawLine(Color.White.copy(0.05f), Offset(centerX, centerY - maxOrbitRadius * 1.02f), Offset(centerX, centerY + maxOrbitRadius * 1.02f), 1.dp.toPx())
            drawLine(Color.White.copy(0.05f), Offset(centerX - maxOrbitRadius * 1.02f, centerY), Offset(centerX + maxOrbitRadius * 1.02f, centerY), 1.dp.toPx())
        }

        listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°").forEach { (deg, label) ->
            val angleRad = (deg - 90f) * PI.toFloat() / 180f
            val labelRadius = if (deg == 90 || deg == 270) maxOrbitRadius * 0.80f else maxOrbitRadius * 0.96f
            Text(
                text = label,
                color = Color.White.copy(0.35f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer {
                    translationX = labelRadius * cos(angleRad)
                    translationY = labelRadius * sin(angleRad)
                }
            )
        }

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .graphicsLayer { alpha = if (spatialActive) 1f else 0.4f },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.15f), Color.Transparent), radius = 400f))
            )

            Surface(
                onClick = { viewModel.setAudio3DStageEnabled(!config.audio3DStageEnabled) },
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = Color(0xFF0D1117),
                border = BorderStroke(2.dp, if (spatialActive) PremiumAccent.copy(0.8f) else Color.White.copy(0.1f)),
                shadowElevation = if (spatialActive) 32.dp else 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (spatialActive) {
                        val infiniteTransition = rememberInfiniteTransition(label = "classic_pulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.85f,
                            targetValue = 1.25f,
                            animationSpec = infiniteRepeatable(tween(2500), RepeatMode.Reverse),
                            label = "pulse"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { 
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = 0.15f + (levelL + levelR) * 0.2f
                                }
                                .background(Brush.radialGradient(listOf(PremiumAccent, Color.Transparent)))
                        )
                    }

                    Icon(
                        Icons.Rounded.GraphicEq, 
                        null, 
                        tint = if (spatialActive) PremiumAccent else Color.White.copy(0.25f), 
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        val speakerL = config.audio3DSpeakerPositions.find { it.id == "L" } ?: com.beatraxus.app.model.Audio3DSpeakerPosition("L", 270f, 0f, 2.0f)
        val speakerR = config.audio3DSpeakerPositions.find { it.id == "R" } ?: com.beatraxus.app.model.Audio3DSpeakerPosition("R", 90f, 0f, 2.0f)

        listOf(speakerL to Color(0xFF42A5F5), speakerR to Color(0xFFFF7043)).forEach { (speaker, color) ->
            val level = if (speaker.id == "L") levelL else levelR
            ClassicSpeakerBubble(speaker, color, maxOrbitRadius, centerX, centerY, spatialActive, level) { az, dist ->
                viewModel.setSpeakerPosition(speaker.id, az, 0f, dist)
            }
        }
    }
}

@Composable
private fun ClassicSpeakerBubble(
    speaker: com.beatraxus.app.model.Audio3DSpeakerPosition,
    color: Color,
    maxOrbitRadius: Float,
    centerX: Float,
    centerY: Float,
    enabled: Boolean,
    level: Float,
    onPositionChange: (Float, Float) -> Unit
) {
    val minRadiusFactor = 0.35f
    val animAzimuth by animateFloatAsState(targetValue = speaker.azimuthDeg, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
    val animDistance by animateFloatAsState(targetValue = speaker.distance, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))

    val distNormalized = (minRadiusFactor + (animDistance / 15f) * (1f - minRadiusFactor)).coerceIn(minRadiusFactor, 1.0f)
    val dotRadius = maxOrbitRadius * distNormalized
    val angleRad = (animAzimuth - 90f) * PI.toFloat() / 180f

    val pulseScale by animateFloatAsState(
        targetValue = 1f + (level.coerceIn(0f, 1f) * 0.12f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (dotRadius * cos(angleRad)).toInt(),
                    (dotRadius * sin(angleRad)).toInt()
                )
            }
            .size(38.dp)
            .graphicsLayer {
                scaleX = if (enabled) pulseScale else 1f
                scaleY = if (enabled) pulseScale else 1f
            }
            .shadow(
                (8 + level * 16).dp, 
                CircleShape, 
                ambientColor = color.copy(alpha = if (enabled) 0.8f else 0.2f), 
                spotColor = color.copy(alpha = if (enabled) 0.8f else 0.2f)
            )
            .background(if (enabled) color else color.copy(0.3f), CircleShape)
            .border(1.5.dp, Color.White.copy(if (enabled) 0.5f else 0.1f), CircleShape)
            .pointerInput(enabled, speaker.id) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val touchPos = Offset(
                        centerX + dotRadius * cos(angleRad) + change.position.x - 19.dp.toPx(),
                        centerY + dotRadius * sin(angleRad) + change.position.y - 19.dp.toPx()
                    )
                    val dx = touchPos.x - centerX
                    val dy = touchPos.y - centerY
                    val newAngleRad = atan2(dy.toDouble(), dx.toDouble())
                    var az = (newAngleRad * 180.0 / PI) + 90.0
                    while (az < 0.0) az += 360.0
                    while (az >= 360.0) az -= 360.0
                    
                    val distPx = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val newDistNormalized = (distPx / maxOrbitRadius).coerceIn(minRadiusFactor, 1.0f)
                    val newDistance = (newDistNormalized - minRadiusFactor) / (1f - minRadiusFactor) * 15f
                    
                    onPositionChange(az.toFloat(), newDistance.toFloat())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(speaker.id, color = Color.White.copy(if (enabled) 1f else 0.4f), fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

@Composable
private fun ClassicControlPanel(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val config = uiState.dsp.config
    val spatialActive = config.audio3DStageEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF0D1117).copy(0.7f))
            .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(28.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SPATIAL ANALYZER (CLASSIC)",
                color = PremiumAccent.copy(0.8f),
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp
            )

            PremiumSwitch(
                checked = config.audio3DStageEnabled,
                onCheckedChange = { viewModel.setAudio3DStageEnabled(it) },
                accentColor = PremiumAccent
            )
        }

        SoundStageSliderRow(
            title = "Width",
            value = config.audio3DWidth,
            range = 0f..2f,
            valueText = { String.format(Locale.US, "%.2f", it) },
            onValueChange = { viewModel.setAudio3DWidth(it) },
            enabled = spatialActive,
            onEditValue = onEditValue
        )

        SoundStageSliderRow(
            title = "Distance",
            value = config.audio3DDistance,
            range = 0.3f..3f,
            valueText = { String.format(Locale.US, "%.1f m", it) },
            onValueChange = { viewModel.setAudio3DDistance(it) },
            enabled = spatialActive,
            onEditValue = onEditValue
        )

        SoundStageSliderRow(
            title = "Spatial Intensity",
            value = config.audio3DRoomReflections,
            range = 0f..1f,
            valueText = { "${(it * 100).toInt()}%" },
            onValueChange = { viewModel.setAudio3DRoomReflections(it) },
            enabled = spatialActive,
            onEditValue = onEditValue
        )
    }
}

@Composable
private fun ModernSpatialAudioContent(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
    val density = LocalDensity.current
    val config = uiState.dsp.config
    val isSpatialBypassed = config.bitPerfectEnabled && !config.bitPerfectUnbypass3DStage
    val spatialActive = config.spatialAudioEnabled && !isSpatialBypassed

    data class StageNode(
        val name: String,
        val icon: ImageVector,
        val az: Float,
        val dist: Float,
        val color: Color
    )

    val nodes = remember {
        listOf(
            StageNode("Vocals", Icons.Rounded.Person, 0f, 2.0f, Color(0xFF42A5F5)),
            StageNode("Drums", Icons.Rounded.LibraryMusic, 45f, 2.8f, Color(0xFFFF7043)),
            StageNode("Keys", Icons.Rounded.Piano, 90f, 1.8f, Color(0xFFEC407A)),
            StageNode("Lead Guitar", Icons.Rounded.MusicNote, 135f, 2.3f, Color(0xFF66BB6A)),
            StageNode("Ambience", Icons.Rounded.GraphicEq, 180f, 3.5f, Color(0xFFAB47BC)),
            StageNode("Backing Vocals", Icons.Rounded.Groups, 225f, 2.5f, Color(0xFFFFEE58)),
            StageNode("Bass", Icons.Rounded.Speaker, 270f, 2.2f, Color(0xFF26C6DA)),
            StageNode("Guitar", Icons.Rounded.MusicNote, 315f, 2.6f, Color(0xFF7E57C2))
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp) // Increased horizontal padding
                .aspectRatio(1.0f), // Square aspect ratio for better radial distribution
            contentAlignment = Alignment.Center
        ) {
            val viewWidth = with(density) { maxWidth.toPx() }
            val viewHeight = with(density) { maxHeight.toPx() }
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            val maxOrbitRadius = (min(viewWidth, viewHeight) / 2f) * 0.58f // Further reduced to pull in side cards

            // Background Orbits
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Concentric circles
                for (i in 1..4) {
                    drawCircle(
                        color = Color.White.copy(0.04f + (i * 0.01f)),
                        radius = maxOrbitRadius * (i / 4f),
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Cross lines
                drawLine(Color.White.copy(0.05f), Offset(centerX, centerY - maxOrbitRadius * 1.02f), Offset(centerX, centerY + maxOrbitRadius * 1.02f), 1.dp.toPx())
                drawLine(Color.White.copy(0.05f), Offset(centerX - maxOrbitRadius * 1.02f, centerY), Offset(centerX + maxOrbitRadius * 1.02f, centerY), 1.dp.toPx())
            }

            // Degree Labels
            listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°").forEach { (deg, label) ->
                val angleRad = (deg - 90f) * PI.toFloat() / 180f
                // Move 90 and 270 degrees further inside the circle
                val labelRadius = if (deg == 90 || deg == 270) maxOrbitRadius * 0.80f else maxOrbitRadius * 0.96f

                Text(
                    text = label,
                    color = Color.White.copy(0.35f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.graphicsLayer {
                        translationX = labelRadius * cos(angleRad)
                        translationY = labelRadius * sin(angleRad)
                    }
                )
            }

            // Central Listener Area (Stylized 3D Analyzer representation)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .graphicsLayer { alpha = if (spatialActive) 1f else 0.4f },
                contentAlignment = Alignment.Center
            ) {
                // Background Glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(PremiumAccent.copy(0.15f), Color.Transparent),
                                radius = 400f
                            )
                        )
                )

                Surface(
                    onClick = { if (!isSpatialBypassed) viewModel.setSpatialAudioEnabled(!config.spatialAudioEnabled) },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color(0xFF0D1117),
                    border = BorderStroke(1.5.dp, if (spatialActive) PremiumAccent.copy(0.6f) else Color.White.copy(0.1f)),
                    shadowElevation = 24.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Inner Pulse Effect
                        if (spatialActive) {
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 0.8f,
                                targetValue = 1.2f,
                                animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
                                label = "scale"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.2f }
                                    .background(Brush.radialGradient(listOf(PremiumAccent, Color.Transparent)))
                            )
                        }
                        
                        Icon(
                            Icons.Rounded.GraphicEq,
                            null,
                            tint = PremiumAccent,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            // Nodes & Dots
            nodes.forEach { nodeTemplate ->
                val nodePos = config.soundStageNodePositions[nodeTemplate.name]
                    ?: com.beatraxus.app.model.SoundStageNodePosition()

                // Animate positions for perfectly smooth movement tracking
                val animAzimuth by animateFloatAsState(
                    targetValue = nodePos.azimuth,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "az_anim"
                )
                val animDistance by animateFloatAsState(
                    targetValue = nodePos.distance,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "dist_anim"
                )

                // Dot Position (Dynamic - moves with animated azimuth/distance)
                val dotAngleRad = (animAzimuth - 90f) * PI.toFloat() / 180f

                // Start from the edge of the center listener area (approx 0.35 of radius)
                val minRadiusFactor = 0.35f
                val distNormalized = (minRadiusFactor + (animDistance / 15f) * (1f - minRadiusFactor)).coerceIn(minRadiusFactor, 1.0f)
                val dotRadius = maxOrbitRadius * distNormalized

                // Card Position (Static - stays at fixed angle around the circle)
                val cardAngleRad = (nodeTemplate.az - 90f) * PI.toFloat() / 180f

                // Premium Dot
                if (spatialActive) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .graphicsLayer {
                                translationX = dotRadius * cos(dotAngleRad)
                                translationY = dotRadius * sin(dotAngleRad)
                            }
                            .background(nodeTemplate.color, CircleShape)
                            .border(1.5.dp, Color.White.copy(0.4f), CircleShape)
                            .shadow(
                                14.dp,
                                CircleShape,
                                ambientColor = nodeTemplate.color,
                                spotColor = nodeTemplate.color
                            )
                    )
                }

                // Premium Info Card (Static position)
                val isSelected = config.soundStageSelectedNode == nodeTemplate.name

                val cardRadius = maxOrbitRadius + with(density) { 50.dp.toPx() }

                Surface(
                    onClick = { viewModel.selectSoundStageNode(nodeTemplate.name) },
                    enabled = spatialActive,
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = cardRadius * cos(cardAngleRad)
                            translationY = cardRadius * sin(cardAngleRad)
                            alpha = if (spatialActive) 1f else 0.5f
                            if (isSelected) {
                                scaleX = 1.1f
                                scaleY = 1.1f
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        if (isSelected) Color(0xFF252B33) else Color(0xFF0F1218).copy(0.8f),
                                        if (isSelected) Color(0xFF0D1117) else Color(0xFF050608).copy(0.9f)
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                brush = if (isSelected) {
                                    Brush.linearGradient(listOf(PremiumAccent, nodeTemplate.color))
                                } else {
                                    SolidColor(Color.White.copy(0.08f))
                                },
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(nodeTemplate.color.copy(0.1f), CircleShape)
                                .border(0.5.dp, nodeTemplate.color.copy(0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                nodeTemplate.icon,
                                null,
                                tint = nodeTemplate.color,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Column {
                            Text(
                                text = nodeTemplate.name.uppercase(),
                                color = Color.White.copy(0.5f),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f m", nodePos.distance),
                                color = if (isSelected) PremiumAccent else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
        // 3. Position Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF0D1117).copy(0.7f))
                .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(28.dp))
                .padding(14.dp)
                .graphicsLayer { alpha = if (spatialActive) 1f else 0.4f },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SPATIAL ANALYZER",
                    color = PremiumAccent.copy(0.8f),
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Compact Text Buttons with Icons
                    SoundStageActionChip("RESET", Icons.Rounded.Refresh, enabled = spatialActive, onClick = { viewModel.setSoundStagePosition(0f, 0f, 2.0f) })
                    SoundStageActionChip("AUTO", Icons.Rounded.AutoAwesome, isAccent = true, enabled = spatialActive, onClick = { viewModel.setSoundStagePosition(0f, 0f, 3.5f) })
                    SoundStageActionChip("DIST", Icons.Rounded.Straighten, enabled = spatialActive, onClick = { viewModel.setSoundStageDistance(2.0f) })
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val selectedNodePos = config.soundStageNodePositions[config.soundStageSelectedNode] ?: com.beatraxus.app.model.SoundStageNodePosition()

                // Joystick State: Decoupled from VM during interaction
                var displayAzimuth by remember(config.soundStageSelectedNode) { mutableFloatStateOf(selectedNodePos.azimuth) }
                var displayDistance by remember(config.soundStageSelectedNode) { mutableFloatStateOf(selectedNodePos.distance) }
                var interactionCount by remember(config.soundStageSelectedNode) { mutableIntStateOf(0) }

                // Sync UI -> VM
                LaunchedEffect(displayAzimuth, displayDistance) {
                    if (interactionCount > 0) {
                        viewModel.setSoundStageAzimuth(displayAzimuth)
                        viewModel.setSoundStageDistance(displayDistance)
                    }
                }

                // Sync VM -> UI (only when idle)
                LaunchedEffect(selectedNodePos.azimuth, selectedNodePos.distance) {
                    if (interactionCount == 0) {
                        displayAzimuth = selectedNodePos.azimuth
                        displayDistance = selectedNodePos.distance
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SoundStageSliderRow(
                        title = "Azimuth (${config.soundStageSelectedNode})",
                        value = selectedNodePos.azimuth,
                        range = 0f..360f,
                        valueText = { "${it.toInt()}°" },
                        onValueChange = viewModel::setSoundStageAzimuth,
                        enabled = spatialActive,
                        onEditValue = onEditValue
                    )
                    SoundStageSliderRow(
                        title = "Distance",
                        value = selectedNodePos.distance,
                        range = 0.3f..15f,
                        valueText = { String.format(Locale.US, "%.2f m", it) },
                        onValueChange = viewModel::setSoundStageDistance,
                        enabled = spatialActive,
                        onEditValue = onEditValue
                    )
                    SoundStageSliderRow(
                        title = "Elevation",
                        value = selectedNodePos.elevation,
                        range = -90f..90f,
                        valueText = { "${it.toInt()}°" },
                        onValueChange = viewModel::setSoundStageElevation,
                        enabled = spatialActive,
                        onEditValue = onEditValue
                    )
                }

                Spacer(Modifier.width(24.dp))

                // Joystick Module
                BoxWithConstraints(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(0.3f))
                        .border(1.5.dp, Color.White.copy(0.1f), CircleShape)
                        .graphicsLayer { alpha = if (spatialActive) 1f else 0.4f }
                        .pointerInput(spatialActive, config.soundStageSelectedNode) {
                            if (!spatialActive) return@pointerInput
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val maxRadius = size.width / 2f

                            fun updatePos(px: Float, py: Float) {
                                val dx = px - centerX
                                val dy = py - centerY
                                val angleRad = atan2(dy.toDouble(), dx.toDouble())
                                var az = (angleRad * 180.0 / PI) + 90.0
                                while (az < 0.0) az += 360.0
                                while (az >= 360.0) az -= 360.0
                                val distPx = sqrt(dx * dx + dy * dy)
                                val dist = ((distPx / maxRadius) * 15.0).coerceIn(0.3, 15.0)

                                displayAzimuth = az.toFloat()
                                displayDistance = dist.toFloat()
                            }
                            detectTapGestures(
                                onPress = {
                                    interactionCount++
                                    tryAwaitRelease()
                                    interactionCount--
                                },
                                onTap = { offset -> updatePos(offset.x, offset.y) }
                            )
                        }
                        .pointerInput(spatialActive, config.soundStageSelectedNode) {
                            if (!spatialActive) return@pointerInput
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val maxRadius = size.width / 2f
                            detectDragGestures(
                                onDragStart = { interactionCount++ },
                                onDragEnd = { interactionCount-- },
                                onDragCancel = { interactionCount-- },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pos = change.position
                                    val dx = pos.x - centerX
                                    val dy = pos.y - centerY
                                    val angleRad = atan2(dy.toDouble(), dx.toDouble())
                                    var az = (angleRad * 180.0 / PI) + 90.0
                                    while (az < 0.0) az += 360.0
                                    while (az >= 360.0) az -= 360.0
                                    val distPx = sqrt(dx * dx + dy * dy)
                                    val dist = ((distPx / maxRadius) * 15.0).coerceIn(0.3, 15.0)

                                    displayAzimuth = az.toFloat()
                                    displayDistance = dist.toFloat()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Joystick Grid
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(Color.White.copy(0.05f), radius = size.width / 4f, center = center, style = Stroke(1.dp.toPx()))
                        drawLine(Color.White.copy(0.08f), Offset(0f, size.height/2), Offset(size.width, size.height/2), 1.dp.toPx())
                        drawLine(Color.White.copy(0.08f), Offset(size.width/2, 0f), Offset(size.width/2, size.height), 1.dp.toPx())
                    }

                    // Joystick Thumb
                    val thumbAngleRad = (displayAzimuth - 90f) * PI.toFloat() / 180f
                    val thumbDistNorm = (displayDistance / 15f).coerceIn(0f, 1f)
                    val maxThumbRadius = 55.dp // half of 110dp

                    Box(
                        modifier = Modifier
                            .offset(
                                x = (maxThumbRadius.value * thumbDistNorm * cos(thumbAngleRad)).dp,
                                y = (maxThumbRadius.value * thumbDistNorm * sin(thumbAngleRad)).dp
                            )
                            .size(24.dp)
                            .shadow(8.dp, CircleShape, ambientColor = PremiumAccent, spotColor = PremiumAccent)
                            .background(Color(0xFF1A1A24), CircleShape)
                            .border(2.dp, if (spatialActive) PremiumAccent else Color.Gray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(if (spatialActive) PremiumAccent else Color.Gray, CircleShape))
                    }
                }
            }
        }

        // 5. Global Settings
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D1117).copy(0.6f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "3D SPATIAL AUDIO",
                    color = PremiumAccent.copy(0.6f),
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
                if (isSpatialBypassed) {
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
                    checked = config.spatialAudioEnabled,
                    onCheckedChange = { viewModel.setSpatialAudioEnabled(it) },
                    accentColor = PremiumAccent
                )
                }
            }

            Column {
                SoundStageSliderRow(
                    title = "Overall Intensity",
                    value = config.spatialAudioIntensity,
                    range = 0f..1f,
                    valueText = { "${(it * 100).toInt()}%" },
                    onValueChange = viewModel::setSpatialAudioIntensity,
                    enabled = spatialActive,
                    onEditValue = onEditValue
                )
                Text(
                    "Overall blend intensity.",
                    color = if (spatialActive) Color.White.copy(0.4f) else Color.White.copy(0.2f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("HRTF Mode", modifier = Modifier.weight(1f), color = if (spatialActive) Color.White.copy(0.9f) else Color.White.copy(0.3f), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (spatialActive) expanded = it },
                    modifier = Modifier.weight(1.8f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(if (spatialActive) 0.1f else 0.05f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(config.hrtfMode.displayName, color = if (spatialActive) Color.White else Color.White.copy(0.3f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .background(Color(0xFF1A1A24))
                            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(10.dp))
                    ) {
                        com.beatraxus.app.model.HrtfMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName, color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    viewModel.setHrtfMode(mode)
                                    expanded = false
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(0.05f))

            // Retained features
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Center Vocal Lock", color = if (spatialActive) Color.White else Color.White.copy(0.3f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Keep mono content anchored", color = Color.White.copy(if (spatialActive) 0.5f else 0.2f), fontSize = 10.sp)
                }
                PremiumSwitch(
                    checked = config.soundStageCenterLock > 0.5f,
                    enabled = (spatialActive || config.soundStageEnabled) && !isSpatialBypassed,
                    onCheckedChange = { viewModel.setSoundStageCenterLock(if (it) 1f else 0f) },
                    accentColor = PremiumAccent
                )
            }

            SoundStageSliderRow(
                title = "Stage Width",
                value = config.spatialStageWidth,
                range = 0f..2f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setSpatialStageWidth,
                enabled = spatialActive,
                onEditValue = onEditValue
            )

            Text("ROOM ACOUSTICS", color = Color.White.copy(if (spatialActive) 0.4f else 0.2f), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)

            SoundStageSliderRow(
                title = "Room Size",
                value = config.reverbRoomSize,
                range = 0f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setReverbRoomSize,
                enabled = spatialActive,
                onEditValue = onEditValue
            )

            SoundStageSliderRow(
                title = "Reflection",
                value = config.reverbDamping,
                range = 0f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setReverbDamping,
                enabled = spatialActive,
                onEditValue = onEditValue
            )

            SoundStageSliderRow(
                title = "Ambience",
                value = config.reverbAmount,
                range = 0f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setReverbAmount,
                enabled = spatialActive,
                onEditValue = onEditValue
            )
        }
    }
}

@Composable
private fun SoundStageActionChip(
    text: String,
    icon: ImageVector,
    isAccent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (isAccent) PremiumAccent.copy(0.12f) else Color.White.copy(0.04f),
        border = BorderStroke(
            0.6.dp,
            if (isAccent) PremiumAccent.copy(0.3f) else Color.White.copy(0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                icon,
                null,
                tint = if (isAccent) PremiumAccent else Color.White.copy(0.5f),
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = text,
                color = if (isAccent) PremiumAccent else Color.White.copy(0.8f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp
            )
        }
    }
}

@Composable
private fun SoundStageActionButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(0.06f),
        border = BorderStroke(1.dp, Color.White.copy(0.1f))
    ) {
        Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            @OptIn(ExperimentalLayoutApi::class)
            Text(text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun SoundStageSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    onEditValue: ((EditingValue) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    // De-couple internal state for absolute stability during and after interaction
    var internalValue by remember(value, enabled) { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }

    // ONLY sync from external value when NOT interacting and after a generous "settle" delay
    LaunchedEffect(value, isDragging) {
        if (!isDragging) {
            delay(300) // Increased settle time for janky UI thread
            internalValue = value
        }
    }

    Column(modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.uppercase(),
                color = Color.White.copy(0.4f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Surface(
                onClick = { if (enabled && onEditValue != null) onEditValue.invoke(EditingValue(title, value, range, onValueChange)) },
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = valueText(internalValue),
                    color = if (enabled) PremiumAccent else Color.White.copy(0.3f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(0.05f), CircleShape)
            )

            val progress = ((internalValue - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

            // Active Track with Glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .shadow(if (enabled) 6.dp else 0.dp, CircleShape, ambientColor = PremiumAccent, spotColor = PremiumAccent)
                        .background(
                            Brush.horizontalGradient(listOf(PremiumAccent.copy(0.3f), PremiumAccent)),
                            CircleShape
                        )
                )
            }

            Slider(
                value = internalValue,
                onValueChange = {
                    isDragging = true
                    internalValue = it
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    isDragging = false
                    onValueChange(internalValue) // Force final update
                },
                valueRange = range,
                enabled = enabled,
                interactionSource = interactionSource,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledThumbColor = Color.Gray,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
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
            .clip(RoundedCornerShape(16.dp))
            .background(PremiumSurface.copy(alpha = 0.4f))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.1f), Color.Transparent)),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Waves, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                Text(
                    "REVERB ENGINE",
                    color = PremiumAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
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
                    Text("BYPASSED", color = Color.White.copy(0.3f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            } else {
                PremiumSwitch(
                    checked = config.reverbEnabled,
                    onCheckedChange = { viewModel.setReverbEnabled(it) },
                    accentColor = PremiumAccent
                )
            }
        }

        // Control Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isEnabled = config.reverbEnabled && !isReverbBypassed

            // Preset Selection Button
            Surface(
                onClick = { if (!isReverbBypassed) showPresetPicker = true },
                enabled = !isReverbBypassed,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(40.dp),
                color = Color.White.copy(0.04f),
                border = BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(config.reverbPreset, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(16.dp))
                }
            }

            // Quick Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReverbActionIcon(Icons.Rounded.Save, onClick = { viewModel.setReverbPreset("CUSTOM") }, enabled = !isReverbBypassed)
                ReverbActionIcon(Icons.Rounded.RestartAlt, onClick = {
                    viewModel.setReverbPreset("FLAT")
                    viewModel.setReverbAmount(0f)
                }, enabled = !isReverbBypassed)
            }
        }

        val knobSizeSmall = 70.dp
        val reverbActive = config.reverbEnabled && !isReverbBypassed

        // Main Mix Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.08f), Color.Transparent)))
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            KnobControl(
                label = "WET MIX",
                value = config.reverbAmount,
                onValueChange = viewModel::setReverbAmount,
                range = 0f..1f,
                unit = "x",
                knobSize = 100.dp,
                isBipolar = false,
                enabled = !isReverbBypassed,
                active = config.reverbEnabled,
                onToggle = { viewModel.setReverbEnabled(!config.reverbEnabled) },
                onLongPress = { onEditValue(EditingValue("WET MIX", config.reverbAmount, 0f..1f, viewModel::setReverbAmount)) }
            )
        }

        // Section: Room Characteristics
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MasteringSectionHeader("ROOM ACOUSTICS", Icons.Rounded.HomeWork, isActive = reverbActive)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.06f), Color.Transparent)))
                    .padding(vertical = 2.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    KnobControl("DAMPING", config.reverbDamping, viewModel::setReverbDamping, 0f..1f, "x", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("DAMPING", config.reverbDamping, 0f..1f, viewModel::setReverbDamping)) })
                    KnobControl("WIDTH", config.reverbWidth, viewModel::setReverbWidth, 0f..1f, "x", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("WIDTH", config.reverbWidth, 0f..1f, viewModel::setReverbWidth)) })
                    KnobControl("DECAY", config.reverbDecay, viewModel::setReverbDecay, 0f..1f, "x", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("DECAY", config.reverbDecay, 0f..1f, viewModel::setReverbDecay)) })
                }
            }
        }

        // Section: Timing & Structure
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MasteringSectionHeader("TIMING & STRUCTURE", Icons.Rounded.Timer, isActive = reverbActive)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.06f), Color.Transparent)))
                    .padding(vertical = 2.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    KnobControl("PRE-DELAY", config.reverbPredelayMs, viewModel::setReverbPredelay, 0f..250f, "ms", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("PRE-DELAY", config.reverbPredelayMs, 0f..250f, viewModel::setReverbPredelay)) })
                    KnobControl("DRY/WET", config.reverbPredelayMix, viewModel::setReverbPredelayMix, 0f..1f, "x", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("DRY/WET MIX", config.reverbPredelayMix, 0f..1f, viewModel::setReverbPredelayMix)) })
                    KnobControl("SIZE", config.reverbRoomSize, viewModel::setReverbRoomSize, 0f..1f, "x", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("SIZE", config.reverbRoomSize, 0f..1f, viewModel::setReverbRoomSize)) })
                }
            }
        }

        // Footer Stats
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatChip("REVERB", "${(config.reverbAmount * 100).roundToInt()}%")
            StatChip("DECAY", String.format(Locale.US, "%.1f s", config.reverbRoomSize * 6f))
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
private fun ReverbActionIcon(icon: ImageVector, onClick: () -> Unit, enabled: Boolean) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(40.dp)
            .background(Color.White.copy(0.04f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
    ) {
        Icon(icon, null, tint = if (enabled) Color.White else Color.White.copy(0.2f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PremiumMasteringCard(uiState: PlayerUiState, viewModel: PlayerViewModel, onEditValue: (EditingValue) -> Unit) {
    val config = uiState.dsp.config
    val isBypassed = config.bitPerfectEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PremiumSurface.copy(alpha = 0.4f))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(0.1f), Color.Transparent)),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                Text(
                    "MASTERING & ENHANCEMENTS",
                    color = PremiumAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp
                )
            }
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

        // GRID 3x2 (3 rows, 2 columns) with enhanced styling
        val knobSize = 84.dp
        val controlsEnabled = !isBypassed

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Section 1: Tone
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MasteringSectionHeader("TONAL BALANCE", Icons.Rounded.Equalizer, isActive = config.bassEnabled || config.trebleEnabled)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    KnobControl("BASS", config.bassDb, viewModel::setBassDb, -12f..12f, "dB", knobSize, true, controlsEnabled, config.bassEnabled, { viewModel.setBassEnabled(!config.bassEnabled) }, { onEditValue(EditingValue("BASS", config.bassDb, -12f..12f, viewModel::setBassDb)) })
                    KnobControl("TREBLE", config.trebleDb, viewModel::setTrebleDb, -12f..12f, "dB", knobSize, true, controlsEnabled, config.trebleEnabled, { viewModel.setTrebleEnabled(!config.trebleEnabled) }, { onEditValue(EditingValue("TREBLE", config.trebleDb, -12f..12f, viewModel::setTrebleDb)) })
                }
            }

            // Section 2: Space
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MasteringSectionHeader("STEREO IMAGING", Icons.Rounded.CenterFocusWeak, isActive = config.stereoExpansionEnabled || config.balanceEnabled)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    KnobControl("STEREO", config.stereoWidth, viewModel::setStereoWidth, 0.5f..2.0f, "x", knobSize, false, controlsEnabled, config.stereoExpansionEnabled, { viewModel.setStereoExpansionEnabled(!config.stereoExpansionEnabled) }, { onEditValue(EditingValue("STEREO WIDTH", config.stereoWidth, 0.5f..2.0f, viewModel::setStereoWidth)) })
                    KnobControl("BALANCE", config.balance, viewModel::setBalance, -1f..1f, "L/R", knobSize, true, controlsEnabled, config.balanceEnabled, { viewModel.setBalanceEnabled(!config.balanceEnabled) }, { onEditValue(EditingValue("BALANCE", config.balance, -1f..1f, viewModel::setBalance)) })
                }
            }

            // Section 3: Fine Tune
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MasteringSectionHeader("ENHANCEMENTS", Icons.Rounded.AutoAwesome, isActive = config.airEnabled || config.soundStageEnabled || config.spatialAudioEnabled)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    KnobControl("AIR", config.airDb, viewModel::setAirDb, -12f..12f, "dB", knobSize, true, controlsEnabled, config.airEnabled, { viewModel.setAirEnabled(!config.airEnabled) }, { onEditValue(EditingValue("AIR", config.airDb, -12f..12f, viewModel::setAirDb)) })
                    KnobControl(
                        label = "SOUNDSTAGE",
                        value = config.soundStageWidth - 1f,
                        onValueChange = { viewModel.setSoundStageWidth(it + 1f) },
                        range = -1f..1f,
                        unit = "%",
                        knobSize = knobSize,
                        isBipolar = true,
                        enabled = controlsEnabled,
                        active = config.soundStageEnabled,
                        onToggle = { viewModel.setSoundStageEnabled(!config.soundStageEnabled) },
                        onLongPress = { onEditValue(EditingValue("SOUNDSTAGE", config.soundStageWidth - 1f, -1f..1f, { viewModel.setSoundStageWidth(it + 1f) })) }
                    )
                }

                // Cinema Mode: single toggle, self-contained preset chain. Does not read or
                // touch any of the EQ/tone/width/spatial/reverb knobs above — it drives its
                // own fixed targets in the native engine only while this switch is on.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "🎬 CINEMA MODE",
                            color = if (config.cinemaModeEnabled) PremiumAccent else Color.White.copy(0.6f),
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "Wider soundstage · clearer dialogue · enhanced bass · immersive surround",
                            color = Color.White.copy(0.4f),
                            fontSize = 9.sp
                        )
                    }
                    PremiumSwitch(
                        checked = config.cinemaModeEnabled,
                        onCheckedChange = { viewModel.setCinemaModeEnabled(it) },
                        accentColor = PremiumAccent
                    )
                }

                if (config.cinemaModeEnabled) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "INTENSITY",
                                color = Color.White.copy(0.4f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "${(config.cinemaIntensity * 100).toInt()}%",
                                color = PremiumAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = config.cinemaIntensity,
                            onValueChange = { viewModel.setCinemaIntensity(it) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PremiumAccent,
                                activeTrackColor = PremiumAccent,
                                inactiveTrackColor = Color.White.copy(0.1f)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MasteringSectionHeader(label: String, icon: ImageVector, isActive: Boolean = false) {
    val alpha = if (isActive) 1f else 0.3f
    val iconAlpha = if (isActive) 0.8f else 0.2f
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = Color.White.copy(iconAlpha), modifier = Modifier.size(10.dp))
        Text(
            label,
            color = Color.White.copy(alpha),
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
internal fun StatChip(label: String, value: String) {
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
    var internalValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value, isDragging) {
        if (!isDragging) {
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
        isNeutralValue -> Color.White.copy(0.1f)
        unit == "dB" || unit == "L/R" -> if (internalValue > 0.05f) Color(0xFF00FF88) else Color(0xFFFF5252)
        else -> PremiumAccent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(knobSize + 28.dp)
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(isActive, range) {
                    if (!isActive) return@pointerInput
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val centerThreshold = (size.width / 2f) * 0.4f

                    detectDragGestures(
                        onDragStart = { offset ->
                            val dist = sqrt((offset.x - centerX).pow(2) + (offset.y - centerY).pow(2))
                            isDragging = dist >= centerThreshold
                        },
                        onDragEnd = {
                            isDragging = false
                            onValueChange(internalValue)
                        },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, _ ->
                            if (isDragging) {
                                change.consume()
                                val pos = change.position
                                val angleRad = atan2(pos.y - centerY, pos.x - centerX)
                                var angleDeg = (angleRad * 180f / PI.toFloat())

                                var normalizedAngle = angleDeg - 135f
                                while (normalizedAngle < 0) normalizedAngle += 360f

                                if (normalizedAngle <= 270f) {
                                    val p = normalizedAngle / 270f
                                    val newValue = range.start + p * (range.endInclusive - range.start)
                                    if (abs(newValue - internalValue) > (range.endInclusive - range.start) / 1000f) {
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
            // Sophisticated background ring with high-end glow
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - 10.dp.toPx()

                // Outer ambient glow
                if (isActive) {
                    drawArc(
                        brush = Brush.radialGradient(
                            colors = listOf(sliderColor.copy(0.1f), Color.Transparent),
                            center = center,
                            radius = r + 16.dp.toPx()
                        ),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Background track with "etched" look
                drawArc(
                    color = Color.Black.copy(0.4f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                )

                // High-precision Tick marks
                val tickCount = 21
                for (i in 0 until tickCount) {
                    val angle = 135f + (i * 270f / (tickCount - 1))
                    val angleRad = angle * PI.toFloat() / 180f
                    val isMajor = i % 5 == 0
                    val innerR = r - (if (isMajor) 14.dp.toPx() else 10.dp.toPx())
                    val outerR = r - 6.dp.toPx()

                    drawLine(
                        color = if (isActive && progress >= i.toFloat()/(tickCount-1)) sliderColor.copy(0.6f) else Color.White.copy(0.1f),
                        start = Offset(center.x + innerR * cos(angleRad), center.y + innerR * sin(angleRad)),
                        end = Offset(center.x + outerR * cos(angleRad), center.y + outerR * sin(angleRad)),
                        strokeWidth = (if (isMajor) 2.dp.toPx() else 1.dp.toPx()),
                        cap = StrokeCap.Round
                    )
                }

                // Active glowing arc
                val sweepAngle = 270f
                val startAngle = 135f
                val currentSweep = if (isBipolar) sweepAngle * (progress - 0.5f) else sweepAngle * progress
                val arcStart = if (isBipolar) startAngle + (sweepAngle * 0.5f) else startAngle

                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(sliderColor.copy(0.4f), sliderColor, sliderColor.copy(0.4f)),
                        center = center
                    ),
                    startAngle = arcStart,
                    sweepAngle = currentSweep,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                )
            }

            // High-End Knob Body with Brushed Metal Texture
            Surface(
                modifier = Modifier
                    .size(knobSize * 0.7f)
                    .graphicsLayer {
                        rotationZ = (progress * 270f) - 135f
                    }
                    .shadow(if (isActive) 16.dp else 4.dp, CircleShape, spotColor = Color.Black)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onToggle() },
                        onLongClick = { onLongPress() }
                    ),
                shape = CircleShape,
                color = Color.Transparent
            ) {
                Box(contentAlignment = Alignment.TopCenter) {
                    // Brushed Metal Background
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.linearGradient(
                                listOf(Color(0xFF323842), Color(0xFF16191E), Color(0xFF262B33))
                            )
                        )
                        // Circular "Brushed" texture lines
                        for (i in 1..4) {
                            drawCircle(
                                color = Color.White.copy(0.02f),
                                radius = size.minDimension / 2f - (i * 4).dp.toPx(),
                                style = Stroke(width = 0.5.dp.toPx())
                            )
                        }
                    }

                    // Modern indicator dot with glow
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (!isActive) Color.White.copy(0.2f)
                                else sliderColor
                            )
                            .shadow(if (isActive) 8.dp else 0.dp, CircleShape, spotColor = sliderColor, ambientColor = sliderColor)
                    )
                }
            }
        }

        // Labels with refined typography
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label.uppercase(),
                color = if (isActive) Color.White.copy(0.9f) else Color.White.copy(0.3f),
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
            Surface(
                onClick = { if (enabled) onLongPress() },
                color = Color.White.copy(0.04f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(0.5.dp, Color.White.copy(0.1f))
            ) {
                Text(
                    text = when (unit) {
                        "dB" -> String.format(Locale.US, "%s%.1f %s", if (internalValue > 0) "+" else "", internalValue, unit)
                        "L/R" -> when {
                            internalValue < -0.05f -> String.format(Locale.US, "L %.2f", -internalValue)
                            internalValue > 0.05f -> String.format(Locale.US, "R %.2f", internalValue)
                            else -> "CENTER"
                        }
                        "x" -> String.format(Locale.US, "%.2fx", internalValue)
                        else -> String.format(Locale.US, "%.1f%s", internalValue, unit)
                    },
                    color = if (isActive) sliderColor else Color.White.copy(0.2f),
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AutoEqResultRow(
    result: AutoEqProfileSummary,
    isLoading: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PremiumAccent.copy(0.08f) else Color.White.copy(alpha = 0.05f)
        ),
        border = if (isSelected) BorderStroke(1.dp, PremiumAccent.copy(0.2f)) else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.name,
                        color = if (isSelected) PremiumAccent else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))

                    val isGithub = result.source.startsWith("GITHUB:")
                    val badgeText = if (isGithub) "LIVE" else "BUILT-IN"
                    val badgeColor = if (isSelected) PremiumAccent else (if (isGithub) Color(0xFF00FF88).copy(0.8f) else PremiumAccent.copy(0.6f))

                    Surface(
                        color = badgeColor.copy(0.12f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, badgeColor.copy(0.3f))
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
                        )
                    }
                }

                if (isLoading && result.source.startsWith("GITHUB:")) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = PremiumAccent,
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Icon(
                        if (isSelected) Icons.Rounded.Check else Icons.Rounded.Add,
                        null,
                        tint = if (isSelected) PremiumAccent else Color.White.copy(0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (result.bands.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(0.2f))
                ) {
                    EqPreviewGraph(result.bands, enabled = true, showDots = false)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Grafic Overview",
                    color = Color.White.copy(0.3f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

private data class BuiltInEqPreset(val name: String, val gains: List<Float>)

private fun builtInEqPresets() = listOf(
    BuiltInEqPreset("FLAT", List(10) { 0f }),
    BuiltInEqPreset("BASS BOOST", listOf(6f, 5f, 4f, 2f, 1f, 0f, 0f, 0f, 0f, 0f)),
    BuiltInEqPreset("TREBLE", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    BuiltInEqPreset("ROCK", listOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 1f, 3f, 4f)),
    BuiltInEqPreset("ELECTRONIC", listOf(5f, 4f, 2f, 0f, 0f, 2f, 1f, 3f, 5f, 6f)),
    BuiltInEqPreset("POP", listOf(-2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -2f)),
    BuiltInEqPreset("CLASSICAL", listOf(5f, 4f, 3f, 2f, 0f, 0f, 0f, 2f, 4f, 5f)),
    BuiltInEqPreset("JAZZ", listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f)),
    BuiltInEqPreset("DANCE", listOf(4f, 7f, 5f, 0f, 2f, 4f, 6f, 5f, 3f, 0f)),
    BuiltInEqPreset("METAL", listOf(5f, 4f, 3f, 1f, 0f, 1f, 3f, 4f, 5f, 6f)),
    BuiltInEqPreset("R&B", listOf(3f, 6f, 5f, 1f, -2f, -1f, 2f, 3f, 5f, 4f)),
    BuiltInEqPreset("VOCAL", listOf(-2f, -3f, -3f, 1f, 4f, 4f, 3f, 1f, -1f, -2f)),
    BuiltInEqPreset("ACOUSTIC", listOf(4f, 4f, 3f, 2f, 1f, 2f, 3f, 3f, 2f, 1f)),
    BuiltInEqPreset("DEEP", listOf(7f, 5f, 3f, 1f, 0f, -1f, -2f, -3f, -4f, -5f)),
    BuiltInEqPreset("BRIGHT", listOf(-5f, -4f, -3f, -2f, -1f, 1f, 3f, 5f, 7f, 8f))
)

private fun fftMagnitude(real: FloatArray): FloatArray {
    val n = real.size
    val imag = FloatArray(n)
    fftFloat(real, imag)
    val mag = FloatArray(n / 2)
    for (i in 0 until n / 2) {
        mag[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
    }
    return mag
}

private fun fftFloat(real: FloatArray, imag: FloatArray) {
    val n = real.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j or bit
        if (i < j) {
            val tr = real[i]; real[i] = real[j]; real[j] = tr
            val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wR = cos(ang).toFloat(); val wI = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var curR = 1.0f; var curI = 0.0f
            for (k in 0 until len / 2) {
                val uR = real[i + k]; val uI = imag[i + k]
                val vR = real[i + k + len / 2] * curR - imag[i + k + len / 2] * curI
                val vI = real[i + k + len / 2] * curI + imag[i + k + len / 2] * curR
                real[i + k] = uR + vR; imag[i + k] = uI + vI
                real[i + k + len / 2] = uR - vR; imag[i + k + len / 2] = uI - vI
                val nextR = curR * wR - curI * wI
                val nextI = curR * wI + curI * wR
                curR = nextR; curI = nextI
            }
            i += len
        }
        len = len shl 1
    }
}
