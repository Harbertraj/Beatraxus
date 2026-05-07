package com.beatflowy.app.ui.components.dsp

import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.ui.screens.MainBackground
import com.beatflowy.app.viewmodel.PlayerViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
    var customPresetName by remember { mutableStateOf("") }
    val builtInPresets = remember { builtInEqPresets() }
    var showPresetsSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

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

        val pagerState = rememberPagerState(pageCount = { 2 })

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
                                Box(modifier = Modifier.size(4.dp).background(if (pagerState.currentPage == 0) PremiumAccent else Color.White.copy(alpha = 0.3f), CircleShape))
                                Box(modifier = Modifier.size(4.dp).background(if (pagerState.currentPage == 1) PremiumAccent else Color.White.copy(alpha = 0.3f), CircleShape))
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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            }
                        )

                        PremiumEqualizerCard(uiState, viewModel, onShowPresets = { showPresetsSheet = true })
                    } else {
                        PremiumEffectsCard(uiState, viewModel)
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
                            AutoEqResultRow(result) {
                                viewModel.applyAutoEqProfile(result)
                                showPresetsSheet = false
                            }
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
    }
}

@Composable
private fun PipelineEqHeader(uiState: PlayerUiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .border(
                1.dp, 
                Color.White.copy(alpha = 0.1f), 
                RoundedCornerShape(24.dp)
            )
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PremiumAccent.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Tune, null, tint = PremiumAccent, modifier = Modifier.size(20.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("AUDIO PIPELINE", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    uiState.dsp.config.activeEffects().joinToString(" → ").ifEmpty { "PURE BYPASS" },
                    color = PremiumAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PremiumGraphicCard(uiState: PlayerUiState) {
    val config = uiState.dsp.config
    val displayBands = if (config.autoEqEnabled) config.autoEqProfile?.bands.orEmpty() else config.eqBands
    val displayEnabled = config.autoEqEnabled || config.eqEnabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp, 
                Color.White.copy(alpha = 0.1f), 
                RoundedCornerShape(28.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("GRAPHIC RESPONSE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
        ) {
            EqPreviewGraph(displayBands, displayEnabled)
        }
    }
}

@Composable
private fun PremiumEqualizerCard(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onShowPresets: () -> Unit
) {
    val config = uiState.dsp.config
    val manualEqEnabled = config.eqEnabled && !config.autoEqEnabled
    val autoEqActive = config.autoEqEnabled && config.autoEqProfile != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp, 
                Color.White.copy(alpha = 0.1f), 
                RoundedCornerShape(28.dp)
            )
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EQUALIZER", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.width(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PremiumAccent.copy(alpha = 0.1f))
                            .clickable { onShowPresets() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            null,
                            tint = PremiumAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "AUTO-EQ",
                            color = PremiumAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text("${config.eqBands.size}-BAND PARAMETRIC", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                if (autoEqActive) {
                    Text("AutoEQ active: manual EQ locked", color = PremiumAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Switch(
                checked = config.eqEnabled,
                onCheckedChange = { 
                    if (autoEqActive) {
                        viewModel.setAutoEqEnabled(it)
                    } else {
                        viewModel.setEqEnabled(it)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PremiumAccent,
                    checkedTrackColor = PremiumAccent.copy(alpha = 0.2f),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.2f)
                )
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("PRE-AMPLIFICATION", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    String.format("%.1f dB", config.preampDb),
                    color = if (config.preampDb > 0) Color(0xFFFF5252) else PremiumAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            PremiumSlider(
                value = config.preampDb,
                onValueChange = { viewModel.setPreampDb((it * 10f).roundToInt() / 10f) },
                valueRange = -15f..15f,
                enabled = true
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            config.eqBands.forEachIndexed { index, band ->
                PremiumVerticalBand(
                    band = band,
                    enabled = config.eqEnabled,
                    onToggle = { viewModel.setEqBandEnabled(index, it) },
                    onGainChange = { viewModel.setEqBandGain(index, (it * 10f).roundToInt() / 10f) }
                )
            }
        }
    }
}

@Composable
private fun PremiumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier,
        colors = SliderDefaults.colors(
            activeTrackColor = PremiumAccent,
            inactiveTrackColor = Color.White.copy(alpha = 0.1f),
            thumbColor = Color.White,
            disabledActiveTrackColor = Color.Gray.copy(alpha = 0.3f),
            disabledThumbColor = Color.Gray
        )
    )
}

@Composable
private fun PremiumVerticalBand(
    band: ParametricEqBand,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onGainChange: (Float) -> Unit
) {
    val isActive = enabled && band.enabled
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(52.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isActive) PremiumAccent.copy(alpha = 0.1f) else Color.Transparent)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = String.format("%.1f", band.gainDb),
                color = if (isActive) PremiumAccent else Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )
        }

        Box(
            modifier = Modifier
                .height(130.dp)
                .width(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.2f))
                .border(1.dp, Color.White.copy(alpha = 0.03f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight(0.8f)
                    .background(Color.White.copy(alpha = 0.05f))
            )
            
            Slider(
                value = band.gainDb,
                onValueChange = onGainChange,
                valueRange = -12f..12f,
                enabled = isActive,
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = -90f
                        transformOrigin = TransformOrigin.Center
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints(
                                minWidth = constraints.minHeight,
                                maxWidth = constraints.maxHeight,
                                minHeight = constraints.minWidth,
                                maxHeight = constraints.maxWidth
                            )
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(-((placeable.width - placeable.height) / 2), -((placeable.height - placeable.width) / 2))
                        }
                    }
                    .width(130.dp)
                    .height(44.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = if (isActive) Color.White else Color.Gray,
                    disabledThumbColor = Color.Gray
                )
            )
        }

        Text(
            text = if (band.frequencyHz >= 1000f) String.format("%.1fk", band.frequencyHz / 1000f) else "${band.frequencyHz.toInt()}",
            color = if (isActive) Color.White else Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EqPreviewGraph(bands: List<ParametricEqBand>, enabled: Boolean) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        val dbScale = height / 30f

        val gridColor = Color.White.copy(alpha = 0.05f)
        drawLine(gridColor, Offset(0f, midY), Offset(width, midY), strokeWidth = 1.dp.toPx())
        
        if (bands.isNotEmpty()) {
            val points = bands.mapIndexed { i, band ->
                Offset((i.toFloat() / (bands.size - 1)) * width, midY - (band.gainDb * dbScale))
            }
            
            // Draw Gradient Area
            val fillPath = Path()
            fillPath.moveTo(0f, midY)
            points.forEach { fillPath.lineTo(it.x, it.y) }
            fillPath.lineTo(width, midY)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        PremiumAccent.copy(alpha = if (enabled) 0.3f else 0.1f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = height
                )
            )

            // Draw Main Line
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i+1]
                val controlX = (p0.x + p1.x) / 2f
                path.quadraticTo(p0.x, p0.y, controlX, (p0.y + p1.y) / 2f)
            }
            path.lineTo(points.last().x, points.last().y)

            drawPath(
                path = path,
                color = if (enabled) PremiumAccent else Color.White.copy(alpha = 0.2f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Dynamic frequency markers / dots
            points.forEachIndexed { i, point ->
                val band = bands[i]
                val dotColor = if (!enabled) Color.White.copy(0.2f)
                else if (band.gainDb > 0) Color(0xFF00FF88).copy(0.8f) 
                else if (band.gainDb < 0) Color(0xFFFF4444).copy(0.8f)
                else PremiumAccent.copy(0.5f)

                drawCircle(
                    color = dotColor,
                    radius = (2.dp.toPx() + (kotlin.math.abs(band.gainDb) / 12f) * 3.dp.toPx()),
                    center = point
                )
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
    onSave: () -> Unit
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
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp, 
                Color.White.copy(alpha = 0.1f), 
                RoundedCornerShape(28.dp)
            )
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AUDIO PRESETS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
            if (isTweak) {
                IconButton(onClick = { onToggleSaveDialog(!showSaveDialog) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Add, "Save", tint = PremiumAccent)
                }
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { preset ->
                val isActive = activeBuiltIn == preset.name && activeCustom == null
                Card(
                    onClick = { viewModel.setAllEqGains(preset.gains) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
                    ),
                    border = BorderStroke(1.dp, if (isActive) PremiumAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f))
                ) {
                    Text(
                        preset.name,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = if (isActive) PremiumAccent else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Box {
                val isAnyCustomActive = activeCustom != null
                Card(
                    onClick = { showCustomPopup = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAnyCustomActive) PremiumAccent.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
                    ),
                    border = BorderStroke(1.dp, if (isAnyCustomActive) PremiumAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            activeCustom ?: "CUSTOM",
                            color = if (isAnyCustomActive) PremiumAccent else Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (isAnyCustomActive) PremiumAccent else Color.White.copy(0.4f), modifier = Modifier.size(16.dp))
                    }
                }

                if (showCustomPopup) {
                    Popup(
                        onDismissRequest = { showCustomPopup = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Card(
                            modifier = Modifier
                                .width(200.dp)
                                .padding(top = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                            elevation = CardDefaults.cardElevation(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (uiState.dsp.customEqPresets.isEmpty()) {
                                    Text("No custom presets", color = Color.White.copy(0.4f), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
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
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(preset.name, color = if (isActive) PremiumAccent else Color.White, fontSize = 14.sp)
                                        IconButton(onClick = { viewModel.deleteCustomEqPreset(preset.name) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Rounded.Delete, null, tint = Color.Red.copy(0.4f), modifier = Modifier.size(16.dp))
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
                placeholder = { Text("Preset name...", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                trailingIcon = {
                    if (customPresetName.isNotBlank()) {
                        IconButton(onClick = onSave) {
                            Icon(Icons.Rounded.Check, "Save", tint = PremiumAccent)
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
private fun PremiumEffectsCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp, 
                Color.White.copy(alpha = 0.1f), 
                RoundedCornerShape(28.dp)
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            "TONE & ENHANCEMENTS",
            color = Color.White.copy(alpha = 0.6f),
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )

        // 2x3 Grid of Knobs
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("BASS", uiState.dsp.config.bassDb, viewModel::setBassDb, -12f..12f, "dB", knobSize = 85.dp)
                KnobControl("MID BASS", uiState.dsp.config.midBassDb, viewModel::setMidBassDb, -12f..12f, "dB", knobSize = 85.dp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("TREBLE", uiState.dsp.config.trebleDb, viewModel::setTrebleDb, -12f..12f, "dB", knobSize = 85.dp)
                KnobControl("AIR", uiState.dsp.config.airDb, viewModel::setAirDb, -12f..12f, "dB", knobSize = 85.dp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("BALANCE", uiState.dsp.config.balance, viewModel::setBalance, -1f..1f, "L/R", knobSize = 85.dp)
                KnobControl("STEREO", uiState.dsp.config.stereoWidth, viewModel::setStereoWidth, 0.5f..2f, "x", knobSize = 85.dp)
            }
        }

        // Reverb Section at the bottom
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("REVERB", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(PremiumAccent.copy(0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${(uiState.dsp.config.reverbAmount * 100).toInt()}%", color = PremiumAccent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
                Switch(
                    checked = uiState.dsp.config.reverbEnabled,
                    onCheckedChange = viewModel::setReverbEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PremiumAccent,
                        checkedTrackColor = PremiumAccent.copy(alpha = 0.2f)
                    )
                )
            }

            PremiumSlider(
                value = uiState.dsp.config.reverbAmount,
                onValueChange = viewModel::setReverbAmount,
                valueRange = 0f..1f,
                enabled = uiState.dsp.config.reverbEnabled
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("FLAT", "ROOM", "HALL", "PLATE", "CATHEDRAL").forEach { preset ->
                    val isSelected = uiState.dsp.config.reverbPreset == preset
                    Card(
                        onClick = { viewModel.setReverbPreset(preset) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) PremiumAccent.copy(0.1f) else Color.White.copy(0.05f)
                        ),
                        border = BorderStroke(1.dp, if (isSelected) PremiumAccent.copy(0.5f) else Color.White.copy(0.1f))
                    ) {
                        Text(
                            preset,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = if (isSelected) PremiumAccent else Color.White.copy(0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
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
    knobSize: androidx.compose.ui.unit.Dp = 80.dp
) {
    var gestureValue by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) {
        gestureValue = value
    }
    val progress = ((gestureValue - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(knobSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(range) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val sensitivity = 0.0035f
                            val delta = (-dragAmount.y + dragAmount.x) * sensitivity
                            val newValue = (gestureValue + delta * (range.endInclusive - range.start))
                                .coerceIn(range)
                            gestureValue = newValue
                            currentOnValueChange(newValue)
                        }
                    }
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f - 8.dp.toPx()
                
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color.White.copy(alpha = 0.05f),
                        1.0f to Color.White.copy(alpha = 0.05f)
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )

                drawArc(
                    color = PremiumAccent,
                    startAngle = 135f,
                    sweepAngle = 270f * progress,
                    useCenter = false,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )

                val angle = 135f + 270f * progress
                val angleRad = angle * PI / 180f
                val indicatorRadius = radius - 4.dp.toPx()
                val indicatorPos = Offset(
                    center.x + cos(angleRad).toFloat() * indicatorRadius,
                    center.y + sin(angleRad).toFloat() * indicatorRadius
                )
                
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = indicatorPos
                )
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (unit == "%") "${(gestureValue * 100).toInt()}" else "%.1f".format(gestureValue),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun AutoEqResultRow(result: AutoEqProfileSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text(result.source.uppercase(), color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black)
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
