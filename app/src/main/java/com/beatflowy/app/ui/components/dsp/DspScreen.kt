package com.beatflowy.app.ui.components.dsp

import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import java.util.Locale
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
import com.beatflowy.app.utils.PresetExporter
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.ui.components.PremiumSwitch
import com.beatflowy.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.*
import android.graphics.RenderEffect as AndroidRenderEffect

// Premium Color Palette
private val PremiumSurface = Color(0xFF080B10)
private val PremiumAccent = Color(0xFF00F2FF)

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0D1117), // Very Dark Blue Gray
                        Color(0xFF05070A)  // Near Black
                    )
                )
            )
    ) {
        // Subtle glow effect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(PremiumAccent.copy(alpha = 0.03f), Color.Transparent),
                        radius = 2000f
                    )
                )
        )

        val pagerState = rememberPagerState(pageCount = { 4 })

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.background(Color.Transparent),
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DSP STUDIO", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                            Surface(
                                modifier = Modifier.padding(top = 4.dp),
                                color = Color.White.copy(0.05f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    val pageIcons = listOf(
                                        Icons.Rounded.GraphicEq,
                                        Icons.Rounded.SurroundSound,
                                        Icons.Rounded.AutoAwesome,
                                        Icons.Rounded.Waves
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
                    verticalArrangement = Arrangement.spacedBy(if (page == 0) 6.dp else 4.dp)
                ) {
                    if (page == 0) {
                        Text(
                            text = "Settings for: ${uiState.dsp.activeOutputDeviceLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(0.5f),
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 0.dp)
                        )

                        val currentGains = uiState.dsp.config.eqBands.map { it.gainDb }
                        val activePresetName = uiState.dsp.customEqPresets.find { it.bands.map { b -> b.gainDb } == currentGains }?.name
                            ?: builtInPresets.find { it.gains == currentGains }?.name
                            ?: "Custom"

                        PremiumGraphicCard(uiState, activePresetName)

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
                        PremiumSoundStageCard(uiState, viewModel, onEditValue = { editingValue = it })
                    } else if (page == 2) {
                        PremiumMasteringCard(uiState, viewModel, onEditValue = { editingValue = it })
                    } else {
                        PremiumReverbCard(uiState, viewModel, onEditValue = { editingValue = it })
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
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = 1.sp
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "AUTO-EQ DATABASE",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                letterSpacing = 1.sp
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
                                        .height(46.dp)
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
                    "AI EQ OPTIONS",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    letterSpacing = 1.sp
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
                            onCheckedChange = { viewModel.setAiEqEnabled(it) }
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
private fun PremiumGraphicCard(uiState: PlayerUiState, presetName: String) {
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
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 1.sp
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
            EqPreviewGraph(displayBands, displayEnabled)
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
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(24.dp)
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                    onCheckedChange = { viewModel.setEqEnabled(it) }
                )
            }
        }

        // Preamp Horizontal Line Control
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
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
            Box(modifier = Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.Center) {
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                .padding(vertical = 2.dp)
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
private fun EqPreviewGraph(bands: List<ParametricEqBand>, enabled: Boolean, showDots: Boolean = true) {
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
            if (showDots) {
                points.forEachIndexed { i, pt ->
                    // Only draw dots for actual bands, not for the extra end point if we added one
                    if (i < bands.size) {
                        val band = bands[i]
                        if (band.enabled) {
                            val dotColor = if (enabled) PremiumAccent else Color.White.copy(0.4f)
                            drawCircle(color = dotColor, radius = 3.dp.toPx(), center = pt)
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
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(24.dp)
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AUDIO PRESETS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
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
        // 0. Master Toggle Centered Card
        Box(
            modifier = Modifier
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    Text(
                        "SPATIAL AUDIO",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        letterSpacing = 1.5.sp
                    )
                    PremiumSwitch(
                        checked = config.spatialAudioEnabled,
                        onCheckedChange = { viewModel.setSpatialAudioEnabled(it) }
                    )
                }
            }
        }



        // 2. Main Visualization
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp) // Minimal padding to maximize space
                .aspectRatio(1.1f),
            contentAlignment = Alignment.Center
        ) {
            val viewWidth = with(density) { maxWidth.toPx() }
            val viewHeight = with(density) { maxHeight.toPx() }
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            val maxOrbitRadius = (min(viewWidth, viewHeight) / 2f) * 0.72f // Increased back to prevent node overlap

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

            // Central Listener Area (Stylized 3D Character representation)
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .graphicsLayer { alpha = if (config.spatialAudioEnabled) 1f else 0.4f },
                contentAlignment = Alignment.Center
            ) {
                // Background Glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(PremiumAccent.copy(0.1f), Color.Transparent),
                                radius = 350f
                            )
                        )
                )
                
                // Character Container with Depth
                Surface(
                    modifier = Modifier.size(90.dp),
                    shape = CircleShape,
                    color = Color(0xFF161B22),
                    border = BorderStroke(1.5.dp, Color.White.copy(0.1f)),
                    shadowElevation = 16.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Inner Glow
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.05f), Color.Transparent)))
                        )
                        // Listener Icon (Person with Headphones/SupportAgent fits perfectly)
                        Icon(
                            Icons.Rounded.SupportAgent,
                            null,
                            tint = PremiumAccent.copy(alpha = 0.8f),
                            modifier = Modifier.size(54.dp)
                        )
                    }
                }
            }

            // Nodes & Dots
            nodes.forEach { nodeTemplate ->
                val nodePos = config.soundStageNodePositions[nodeTemplate.name]
                    ?: com.beatflowy.app.model.SoundStageNodePosition()

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
                if (config.spatialAudioEnabled) {
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
                
                // Increased offset slightly to ensure labels don't touch the outer radar circle
                val cardRadius = maxOrbitRadius + with(density) { 36.dp.toPx() }

                Surface(
                    onClick = { viewModel.selectSoundStageNode(nodeTemplate.name) },
                    enabled = config.spatialAudioEnabled,
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = cardRadius * cos(cardAngleRad)
                            translationY = cardRadius * sin(cardAngleRad)
                            alpha = if (config.spatialAudioEnabled) 1f else 0.5f
                            if (isSelected) {
                                scaleX = 1.05f
                                scaleY = 1.05f
                            }
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        if (isSelected) Color(0xFF1E2632) else Color(0xFF0F1218).copy(0.85f),
                                        if (isSelected) Color(0xFF161B22) else Color(0xFF0D0F14).copy(0.9f)
                                    )
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = if (isSelected) 1.2.dp else 0.8.dp,
                                brush = if (isSelected) {
                                    Brush.linearGradient(listOf(nodeTemplate.color, nodeTemplate.color.copy(0.3f)))
                                } else {
                                    SolidColor(Color.White.copy(0.1f))
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(nodeTemplate.color.copy(0.15f), CircleShape)
                                .border(0.5.dp, nodeTemplate.color.copy(0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                nodeTemplate.icon, 
                                null, 
                                tint = nodeTemplate.color, 
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Column(modifier = Modifier.width(IntrinsicSize.Min)) {
                            @OptIn(ExperimentalLayoutApi::class)
                            Text(
                                text = nodeTemplate.name.uppercase().replace(" ", "\n"),
                                color = Color.White.copy(0.6f),
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 2,
                                lineHeight = 9.sp,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = String.format(Locale.US, "%.2fm", nodePos.distance),
                                color = if (isSelected) nodeTemplate.color else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
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
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0D1117).copy(0.6f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(24.dp))
                .padding(16.dp)
                .graphicsLayer { alpha = if (config.spatialAudioEnabled) 1f else 0.4f },
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SPATIAL PARAMETERS",
                    color = PremiumAccent.copy(0.6f),
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Compact Text Buttons with Icons
                    SoundStageActionChip("RESET", Icons.Rounded.Refresh, onClick = { viewModel.setSoundStagePosition(0f, 0f, 2.0f) })
                    SoundStageActionChip("AUTO", Icons.Rounded.AutoAwesome, isAccent = true, onClick = { viewModel.setSoundStagePosition(0f, 0f, 3.5f) })
                    SoundStageActionChip("DIST", Icons.Rounded.Straighten, onClick = { viewModel.setSoundStageDistance(2.0f) })
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val selectedNodePos = config.soundStageNodePositions[config.soundStageSelectedNode] ?: com.beatflowy.app.model.SoundStageNodePosition()
                
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
                        enabled = config.spatialAudioEnabled,
                        onEditValue = onEditValue
                    )
                    SoundStageSliderRow(
                        title = "Distance",
                        value = selectedNodePos.distance,
                        range = 0.3f..15f,
                        valueText = { String.format(Locale.US, "%.2f m", it) },
                        onValueChange = viewModel::setSoundStageDistance,
                        enabled = config.spatialAudioEnabled,
                        onEditValue = onEditValue
                    )
                    SoundStageSliderRow(
                        title = "Elevation",
                        value = selectedNodePos.elevation,
                        range = -90f..90f,
                        valueText = { "${it.toInt()}°" },
                        onValueChange = viewModel::setSoundStageElevation,
                        enabled = config.spatialAudioEnabled,
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
                        .graphicsLayer { alpha = if (config.spatialAudioEnabled) 1f else 0.4f }
                        .pointerInput(config.spatialAudioEnabled, config.soundStageSelectedNode) {
                            if (!config.spatialAudioEnabled) return@pointerInput
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
                        .pointerInput(config.spatialAudioEnabled, config.soundStageSelectedNode) {
                            if (!config.spatialAudioEnabled) return@pointerInput
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
                            .border(2.dp, if (config.spatialAudioEnabled) PremiumAccent else Color.Gray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(if (config.spatialAudioEnabled) PremiumAccent else Color.Gray, CircleShape))
                    }
                }
            }
        }

        // 5. Global Settings
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0D1117).copy(0.6f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(24.dp))
                .padding(14.dp)
                .graphicsLayer { alpha = if (config.spatialAudioEnabled) 1f else 0.4f },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "GLOBAL SETTINGS",
                color = PremiumAccent.copy(0.6f),
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )

            Column {
                SoundStageSliderRow(
                    title = "Overall Intensity",
                    value = config.spatialAudioIntensity,
                    range = 0f..1f,
                    valueText = { "${(it * 100).toInt()}%" },
                    onValueChange = viewModel::setSpatialAudioIntensity,
                    enabled = config.spatialAudioEnabled,
                    onEditValue = onEditValue
                )
                Text(
                    "Overall blend intensity.",
                    color = if (config.spatialAudioEnabled) Color.White.copy(0.4f) else Color.White.copy(0.2f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("HRTF Mode", modifier = Modifier.weight(1f), color = if (config.spatialAudioEnabled) Color.White.copy(0.9f) else Color.White.copy(0.3f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (config.spatialAudioEnabled) expanded = it },
                    modifier = Modifier.weight(1.8f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(if (config.spatialAudioEnabled) 0.1f else 0.05f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(config.hrtfMode.displayName, color = if (config.spatialAudioEnabled) Color.White else Color.White.copy(0.3f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        com.beatflowy.app.model.HrtfMode.entries.forEach { mode ->
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
                    Text("Center Vocal Lock", color = if (config.spatialAudioEnabled) Color.White else Color.White.copy(0.3f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Keep mono content anchored", color = Color.White.copy(if (config.spatialAudioEnabled) 0.5f else 0.2f), fontSize = 10.sp)
                }
                PremiumSwitch(
                    checked = config.soundStageCenterLock > 0.5f,
                    enabled = config.spatialAudioEnabled,
                    onCheckedChange = { viewModel.setSoundStageCenterLock(if (it) 1f else 0f) }
                )
            }

            SoundStageSliderRow(
                title = "Stage Width",
                value = config.soundStageWidth,
                range = 0f..2f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setSoundStageWidth,
                enabled = config.spatialAudioEnabled,
                onEditValue = onEditValue
            )

            Text("ROOM ACOUSTICS", color = Color.White.copy(if (config.spatialAudioEnabled) 0.4f else 0.2f), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)
            
            SoundStageSliderRow(
                title = "Room Size",
                value = config.reverbRoomSize,
                range = 0f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setReverbRoomSize,
                enabled = config.spatialAudioEnabled,
                onEditValue = onEditValue
            )

            SoundStageSliderRow(
                title = "Reflection",
                value = config.reverbDamping,
                range = 0f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setReverbDamping,
                enabled = config.spatialAudioEnabled,
                onEditValue = onEditValue
            )

            SoundStageSliderRow(
                title = "Ambience",
                value = config.reverbAmount,
                range = 0f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = viewModel::setReverbAmount,
                enabled = config.spatialAudioEnabled,
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
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Waves, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                Text(
                    "REVERB ENGINE",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
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
                    onCheckedChange = { viewModel.setReverbEnabled(it) }
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

        val knobSizeSmall = 76.dp
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
                knobSize = 115.dp,
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
                    KnobControl("PRE-DELAY", config.reverbPredelayMs, { newVal ->
                        viewModel.setReverbPredelay(newVal * config.reverbPredelayMix * 100f)
                    }, 0f..100f, "ms", knobSizeSmall, false, !isReverbBypassed, reverbActive, {}, { onEditValue(EditingValue("PRE-DELAY", config.reverbPredelayMs, 0f..100f, { v -> viewModel.setReverbPredelay(v * config.reverbPredelayMix * 100f) })) })
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = PremiumAccent, modifier = Modifier.size(16.dp))
                Text(
                    "MASTERING & ENHANCEMENTS",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
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
        val knobSize = 92.dp
        val controlsEnabled = !isBypassed
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Section 1: Tone
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MasteringSectionHeader("TONAL BALANCE", Icons.Rounded.Equalizer, isActive = config.midBassEnabled || config.trebleEnabled)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    KnobControl("MID-BASS", config.midBassDb, viewModel::setMidBassDb, -12f..12f, "dB", knobSize, true, controlsEnabled, config.midBassEnabled, { viewModel.setMidBassEnabled(!config.midBassEnabled) }, { onEditValue(EditingValue("MID-BASS", config.midBassDb, -12f..12f, viewModel::setMidBassDb)) })
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
                MasteringSectionHeader("ENHANCEMENTS", Icons.Rounded.AutoAwesome, isActive = config.airEnabled || config.crossfeedEnabled)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    KnobControl("AIR", config.airDb, viewModel::setAirDb, -12f..12f, "dB", knobSize, true, controlsEnabled, config.airEnabled, { viewModel.setAirEnabled(!config.airEnabled) }, { onEditValue(EditingValue("AIR", config.airDb, -12f..12f, viewModel::setAirDb)) })
                    KnobControl("CROSSFEED", config.crossfeedLevel, viewModel::setCrossfeedLevel, 0f..1f, "x", knobSize, false, controlsEnabled, config.crossfeedEnabled, { viewModel.setCrossfeedEnabled(!config.crossfeedEnabled) }, { onEditValue(EditingValue("CROSSFEED", config.crossfeedLevel, 0f..1f, viewModel::setCrossfeedLevel)) })
                }
            }
        }
    }
}

@Composable
private fun MasteringSectionHeader(label: String, icon: ImageVector, isActive: Boolean = false) {
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(knobSize + 24.dp)
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(isActive, range) {
                    if (!isActive) return@pointerInput
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val centerThreshold = (size.width / 2f) * 0.5f
                    
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
                                    if (abs(newValue - internalValue) > (range.endInclusive - range.start) / 500f) {
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
            // Sophisticated background ring with glow
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - 8.dp.toPx()
                
                // Outer subtle glow
                if (isActive) {
                    drawArc(
                        brush = Brush.radialGradient(
                            colors = listOf(sliderColor.copy(0.15f), Color.Transparent),
                            center = center,
                            radius = r + 12.dp.toPx()
                        ),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Background track
                drawArc(
                    color = Color.White.copy(0.04f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                )

                // Tick marks
                val tickCount = 11
                for (i in 0 until tickCount) {
                    val angle = 135f + (i * 270f / (tickCount - 1))
                    val angleRad = angle * PI.toFloat() / 180f
                    val innerR = r - 12.dp.toPx()
                    val outerR = r - 8.dp.toPx()
                    
                    drawLine(
                        color = if (isActive && progress >= i.toFloat()/(tickCount-1)) sliderColor.copy(0.5f) else Color.White.copy(0.1f),
                        start = Offset(center.x + innerR * cos(angleRad), center.y + innerR * sin(angleRad)),
                        end = Offset(center.x + outerR * cos(angleRad), center.y + outerR * sin(angleRad)),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Active arc
                val sweepAngle = 270f
                val startAngle = 135f
                val currentSweep = if (isBipolar) sweepAngle * (progress - 0.5f) else sweepAngle * progress
                val arcStart = if (isBipolar) startAngle + (sweepAngle * 0.5f) else startAngle

                drawArc(
                    color = sliderColor,
                    startAngle = arcStart,
                    sweepAngle = currentSweep,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                )
            }

            // Knob Body
            Surface(
                modifier = Modifier
                    .size(knobSize * 0.65f)
                    .graphicsLayer {
                        rotationZ = (progress * 270f) - 135f
                    }
                    .shadow(if (isActive) 12.dp else 4.dp, CircleShape, spotColor = sliderColor)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onToggle() },
                        onLongClick = { onLongPress() }
                    ),
                shape = CircleShape,
                color = if (isActive) Color(0xFF1A1F26) else Color(0xFF12151A),
                border = BorderStroke(1.5.dp, Brush.linearGradient(
                    if (isActive) listOf(Color.White.copy(0.2f), Color.Transparent)
                    else listOf(Color.White.copy(0.05f), Color.Transparent)
                ))
            ) {
                Box(contentAlignment = Alignment.TopCenter) {
                    // Modern indicator line
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .width(3.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (!isActive) Color.White.copy(0.2f)
                                else if (isNeutralValue) Color.White.copy(0.4f)
                                else sliderColor
                            )
                    )
                    
                    // Center subtle texture
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.radialGradient(
                                colors = listOf(Color.White.copy(0.03f), Color.Transparent)
                            ))
                    )
                }
            }
        }

        // Labels with enhanced typography
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                text = label,
                color = if (isActive) Color.White.copy(0.9f) else Color.White.copy(0.3f),
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
            Surface(
                onClick = { if (enabled) onLongPress() },
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = when (unit) {
                        "dB" -> String.format(Locale.US, "%s%.1f%s", if (internalValue > 0) "+" else "", internalValue, unit)
                        "L/R" -> when {
                            internalValue < -0.05f -> String.format(Locale.US, "L %.2f", -internalValue)
                            internalValue > 0.05f -> String.format(Locale.US, "R %.2f", internalValue)
                            else -> "CENTER"
                        }
                        "x" -> String.format(Locale.US, "%.2fx", internalValue)
                        else -> String.format(Locale.US, "%.1f%s", internalValue, unit)
                    },
                    color = if (isActive) sliderColor else Color.White.copy(0.2f),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
