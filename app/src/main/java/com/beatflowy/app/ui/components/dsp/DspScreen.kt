package com.beatflowy.app.ui.components.dsp

import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.draw.scale
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
import androidx.compose.animation.core.*
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.ui.screens.MainBackground
import com.beatflowy.app.viewmodel.PlayerViewModel
import kotlin.math.PI
import kotlin.math.atan2
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
    var editingValue by remember { mutableStateOf<EditingValue?>(null) }

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
                        .then(if (page == 0) Modifier.verticalScroll(rememberScrollState()) else Modifier)
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
                            }
                        )

                        PremiumEqualizerCard(uiState, viewModel, onShowPresets = { showPresetsSheet = true }, onEditValue = { editingValue = it })
                    } else if (page == 1) {
                        PremiumEffectsCard(uiState, viewModel, onEditValue = { editingValue = it })
                    } else {
                        PremiumReverbCard(uiState, viewModel, onEditValue = { editingValue = it })
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
            ) { Text("SET VALUE") }
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
            Switch(
                checked = config.eqEnabled,
                onCheckedChange = { viewModel.setEqEnabled(it) },
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PremiumAccent,
                    checkedTrackColor = PremiumAccent.copy(alpha = 0.2f)
                )
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
                    enabled = config.eqEnabled,
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
    onLongPress: () -> Unit
) {
    val isActive = enabled && band.enabled
    val gainColor = when {
        !isActive -> Color.White.copy(0.2f)
        band.gainDb > 0.5f -> Color(0xFF00FF88)
        band.gainDb < -0.5f -> Color(0xFFFF5252)
        else -> Color.White.copy(0.4f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(44.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (band.gainDb >= 0) "+%.1f".format(band.gainDb) else "%.1f".format(band.gainDb),
            color = gainColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.pointerInput(band.id) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
        )

        Box(
            modifier = Modifier
                .height(220.dp)
                .width(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(0.3f))
                .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(18.dp))
                .clickable { if (enabled) onToggle(!band.enabled) },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.width(36.dp).height(1.dp).background(Color.White.copy(0.12f)))

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
                            placeable.place(
                                -((placeable.width - placeable.height) / 2),
                                -((placeable.height - placeable.width) / 2)
                            )
                        }
                    }
                    .width(220.dp).height(36.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = gainColor,
                    disabledThumbColor = Color.Gray.copy(0.3f)
                )
            )
        }

        Text(
            text = if (band.frequencyHz >= 1000f)
                "%.0fk".format(band.frequencyHz / 1000f)
            else "${band.frequencyHz.toInt()}",
            color = if (isActive) Color.White.copy(0.8f) else Color.White.copy(0.25f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
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
            if (isTweak) {
                IconButton(onClick = { onToggleSaveDialog(!showSaveDialog) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Rounded.Add, "Save", tint = PremiumAccent, modifier = Modifier.size(16.dp))
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
                modifier = Modifier.fillMaxWidth().height(48.dp),
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
private fun PremiumEffectsCard(uiState: PlayerUiState, viewModel: PlayerViewModel, onEditValue: (EditingValue) -> Unit) {
    val config = uiState.dsp.config
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // (a) Glowing accent strip
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, PremiumAccent.copy(0.8f), Color.Transparent))))

        Text(
            "TONE & ENHANCEMENTS",
            color = PremiumAccent.copy(0.7f),
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )

        // Integrated Grid of Knobs
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // BASS & MID BASS
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(0.03f))
                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.04f), Color.Transparent), radius = 120f))
                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    KnobControl("BASS", config.bassDb, viewModel::setBassDb, -12f..12f, "dB", knobSize = 100.dp, isBipolar = true, enabled = config.bassEnabled, onToggle = { viewModel.setBassEnabled(!config.bassEnabled) }, onLongPress = { onEditValue(EditingValue("Bass", config.bassDb, -12f..12f, viewModel::setBassDb)) })
                    KnobControl("MID BASS", config.midBassDb, viewModel::setMidBassDb, -12f..12f, "dB", knobSize = 100.dp, isBipolar = true, enabled = config.midBassEnabled, onToggle = { viewModel.setMidBassEnabled(!config.midBassEnabled) }, onLongPress = { onEditValue(EditingValue("Mid Bass", config.midBassDb, -12f..12f, viewModel::setMidBassDb)) })
                }
            }

            // TREBLE & AIR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(0.03f))
                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.04f), Color.Transparent), radius = 120f))
                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    KnobControl("TREBLE", config.trebleDb, viewModel::setTrebleDb, -12f..12f, "dB", knobSize = 100.dp, isBipolar = true, enabled = config.trebleEnabled, onToggle = { viewModel.setTrebleEnabled(!config.trebleEnabled) }, onLongPress = { onEditValue(EditingValue("Treble", config.trebleDb, -12f..12f, viewModel::setTrebleDb)) })
                    KnobControl("AIR", config.airDb, viewModel::setAirDb, -12f..12f, "dB", knobSize = 100.dp, isBipolar = true, enabled = config.airEnabled, onToggle = { viewModel.setAirEnabled(!config.airEnabled) }, onLongPress = { onEditValue(EditingValue("Air", config.airDb, -12f..12f, viewModel::setAirDb)) })
                }
            }

            // BALANCE & STEREO
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(0.03f))
                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.04f), Color.Transparent), radius = 120f))
                    .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    KnobControl("BALANCE", config.balance, viewModel::setBalance, -1f..1f, "L/R", knobSize = 100.dp, isBipolar = true, enabled = config.balanceEnabled, onToggle = { viewModel.setBalanceEnabled(!config.balanceEnabled) }, onLongPress = { onEditValue(EditingValue("Balance", config.balance, -1f..1f, viewModel::setBalance)) })
                    KnobControl("STEREO", config.stereoWidth, viewModel::setStereoWidth, 1f..2f, "x", knobSize = 100.dp, enabled = config.stereoExpansionEnabled, onToggle = { viewModel.setStereoExpansionEnabled(!config.stereoExpansionEnabled) }, onLongPress = { onEditValue(EditingValue("Stereo Width", config.stereoWidth, 1f..2f, viewModel::setStereoWidth)) })
                }
            }
        }

    }
}

@Composable
private fun PremiumReverbCard(uiState: PlayerUiState, viewModel: PlayerViewModel, onEditValue: (EditingValue) -> Unit) {
    val config = uiState.dsp.config
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // (a) Glowing accent strip
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, PremiumAccent.copy(0.8f), Color.Transparent))))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    // (e) Animated breathing glow
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer { alpha = pulseAlpha }
                            .background(PremiumAccent.copy(0.4f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (config.reverbEnabled) PremiumAccent else Color.White.copy(0.3f), CircleShape)
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
        }

        // HERO BOX: WET MIX
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.08f), Color.Transparent)))
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
                enabled = config.reverbEnabled,
                onToggle = { viewModel.setReverbEnabled(!config.reverbEnabled) },
                onLongPress = { onEditValue(EditingValue("WET MIX", config.reverbAmount, 0f..1f, viewModel::setReverbAmount)) }
            )
        }

        // ROW 1: Damp | WIDTH | DECAY
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(0.03f))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.04f), Color.Transparent), radius = 120f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                .padding(vertical = 12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("Damp", config.reverbDamping, viewModel::setReverbDamping, 0f..1f, "x", knobSize = 90.dp, enabled = config.reverbEnabled, onToggle = {}, onLongPress = { onEditValue(EditingValue("Damp", config.reverbDamping, 0f..1f, viewModel::setReverbDamping)) })
                KnobControl("WIDTH", config.reverbWidth, viewModel::setReverbWidth, 0f..1f, "x", knobSize = 90.dp, enabled = config.reverbEnabled, onToggle = {}, onLongPress = { onEditValue(EditingValue("WIDTH", config.reverbWidth, 0f..1f, viewModel::setReverbWidth)) })
                KnobControl("DECAY", config.reverbRoomSize, viewModel::setReverbRoomSize, 0f..1f, "x", knobSize = 90.dp, enabled = config.reverbEnabled, onToggle = {}, onLongPress = { onEditValue(EditingValue("DECAY", config.reverbRoomSize, 0f..1f, viewModel::setReverbRoomSize)) })
            }
        }

        // Decay Readout
        Text(
            text = "Decay: %.1f s".format(config.reverbRoomSize * 6f),
            color = Color.White.copy(0.4f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // ROW 2: Pre-Delay | Pre-Delay Mix | Size
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(0.03f))
                .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.04f), Color.Transparent), radius = 120f))
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
                .padding(vertical = 12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                KnobControl("Pre-Delay", config.reverbPredelayMs, { 
                    viewModel.setReverbPredelay(it * config.reverbPredelayMix * 100f)
                }, 0f..100f, "ms", knobSize = 90.dp, enabled = config.reverbEnabled, onToggle = {}, onLongPress = { onEditValue(EditingValue("Pre-Delay", config.reverbPredelayMs, 0f..100f, { viewModel.setReverbPredelay(it * config.reverbPredelayMix * 100f) })) })
                
                KnobControl("Pre-Delay Mix", config.reverbPredelayMix, viewModel::setReverbPredelayMix, 0f..1f, "x", knobSize = 90.dp, enabled = config.reverbEnabled, onToggle = {}, onLongPress = { onEditValue(EditingValue("Pre-Delay Mix", config.reverbPredelayMix, 0f..1f, viewModel::setReverbPredelayMix)) })
                
                KnobControl("Size", config.reverbRoomSize, viewModel::setReverbRoomSize, 0f..1f, "x", knobSize = 90.dp, enabled = config.reverbEnabled, onToggle = {}, onLongPress = { onEditValue(EditingValue("Size", config.reverbRoomSize, 0f..1f, viewModel::setReverbRoomSize)) })
            }
        }

        // STAT CHIPS ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatChip("WET MIX", "${(config.reverbAmount * 100).roundToInt()}%")
            StatChip("DECAY", "%.1f s".format(config.reverbRoomSize * 6f))
            StatChip("STATUS", if (config.reverbEnabled) "ACTIVE" else "OFF")
        }

        // BUTTON ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isEnabled = config.reverbEnabled
            Button(
                onClick = { viewModel.setReverbEnabled(!isEnabled) },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEnabled) PremiumAccent.copy(0.1f) else Color.Transparent,
                    contentColor = if (isEnabled) PremiumAccent else Color.White
                ),
                border = BorderStroke(1.dp, if (isEnabled) PremiumAccent else Color.White.copy(0.15f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Circle, null, modifier = Modifier.size(8.dp), tint = PremiumAccent)
                    Text("REVERB", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = { showPresetPicker = true },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(0.05f),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.White.copy(0.15f))
            ) {
                Text(
                    config.reverbPreset.let { if (it.length > 10) it.take(7) + "..." else it },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { viewModel.setReverbPreset("CUSTOM") },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(36.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(0.05f),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.White.copy(0.15f))
            ) {
                Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { 
                    viewModel.setReverbPreset("FLAT")
                    viewModel.setReverbAmount(0f)
                    viewModel.setReverbEnabled(false)
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(36.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(0.05f),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.White.copy(0.15f))
            ) {
                Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Mix knob removed as it's now at the top as WET MIX

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
    knobSize: androidx.compose.ui.unit.Dp = 80.dp,
    isBipolar: Boolean = false,
    enabled: Boolean = true,
    onToggle: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    var isDragging by remember { mutableStateOf(false) }
    var gestureValue by remember { mutableStateOf(value) }
    
    LaunchedEffect(value) {
        if (!isDragging) gestureValue = value
    }
    
    val progress = ((gestureValue - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val rangeSpan = range.endInclusive - range.start
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnToggle by rememberUpdatedState(onToggle)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(range, enabled) {
                    if (!enabled) return@pointerInput
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val totalRadius = size.width / 2f
                    // Center area (45% of radius) is reserved for long-press toggle
                    val innerThreshold = totalRadius * 0.45f
                    
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitFirstDown(requireUnconsumed = false)
                            val distFromCenter = kotlin.math.sqrt(
                                (event.position.x - centerX) * (event.position.x - centerX) + 
                                (event.position.y - centerY) * (event.position.y - centerY)
                            )
                            
                            // Only initiate dragging if touch is in the outer "ring"
                            if (distFromCenter in innerThreshold..totalRadius) {
                                event.consume()
                                isDragging = true
                                
                                fun handlePointer(pos: Offset) {
                                    val angleRad = atan2(pos.y - centerY, pos.x - centerX)
                                    var angleDeg = (angleRad * 180f / PI.toFloat())
                                    if (angleDeg < 0) angleDeg += 360f
                                    if (angleDeg <= 45f) angleDeg += 360f
                                    if (angleDeg >= 135f && angleDeg <= 405f) {
                                        val p = (angleDeg - 135f) / 270f
                                        val newValue = range.start + p * rangeSpan
                                        gestureValue = newValue
                                        currentOnValueChange(newValue)
                                    } else if (angleDeg > 45f && angleDeg < 135f) {
                                        val newValue = if (angleDeg < 90f) range.endInclusive else range.start
                                        gestureValue = newValue
                                        currentOnValueChange(newValue)
                                    }
                                }

                                handlePointer(event.position)
                                
                                drag(event.id) { change ->
                                    handlePointer(change.position)
                                    change.consume()
                                }
                                isDragging = false
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { offset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val distFromCenter = kotlin.math.sqrt(
                            (offset.x - centerX) * (offset.x - centerX) + 
                            (offset.y - centerY) * (offset.y - centerY)
                        )
                        // Long press to toggle only works if pressing near the center
                        if (distFromCenter < (size.width / 2f) * 0.5f) {
                            currentOnToggle()
                        }
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f - 4.dp.toPx()
                val knobActiveColor = if (enabled) PremiumAccent else Color.White.copy(0.12f)

                // Ticks
                val tickCount = 40
                for (i in 0 until tickCount) {
                    val angle = (i * (360f / tickCount)) * (PI / 180f).toFloat()
                    val outer = radius
                    val inner = radius - 3.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = if (enabled) 0.1f else 0.05f),
                        start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                        end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val sweepAngle = 270f
                val startAngle = 135f
                
                // Dynamic Curve Line Logic
                // Show a dimmer curve if disabled
                val baseProgress = if (isBipolar) 0.5f else 0f
                val diff = progress - baseProgress
                
                if (kotlin.math.abs(diff) > 0.001f) {
                    val arcStartAngle = startAngle + sweepAngle * baseProgress
                    val arcSweepAngle = sweepAngle * diff
                    
                    drawArc(
                        color = if (enabled) PremiumAccent else PremiumAccent.copy(alpha = 0.2f),
                        startAngle = arcStartAngle,
                        sweepAngle = arcSweepAngle,
                        useCenter = false,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                    )
                }

                // Inner Body
                drawCircle(
                    color = if (enabled) Color.White.copy(0.04f) else Color.White.copy(0.01f),
                    radius = radius - 6.dp.toPx(),
                    center = center
                )
                
                // Dot
                val currentAngle = startAngle + sweepAngle * progress
                val dotAngle = currentAngle * (PI / 180f).toFloat()
                val dotOffset = (radius - 6.dp.toPx()) * 0.7f
                drawCircle(
                    color = if (enabled) Color.White else Color.White.copy(0.3f),
                    radius = 3.dp.toPx(),
                    center = Offset(center.x + cos(dotAngle) * dotOffset, center.y + sin(dotAngle) * dotOffset)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label, 
                color = if (isDragging) PremiumAccent else if (enabled) Color.White else Color.White.copy(0.45f), 
                fontWeight = FontWeight.Bold, 
                fontSize = 12.sp
            )
            Text(
                text = when (unit) {
                    "dB" -> if (gestureValue > 0) "+%.1f".format(gestureValue) else if (gestureValue < 0) "%.1f".format(gestureValue) else "0.0"
                    "L/R" -> if (gestureValue < -0.01f) "L%.2f".format(-gestureValue) else if (gestureValue > 0.01f) "R%.2f".format(gestureValue) else "C"
                    "x" -> "${((gestureValue - 1f) * 100f).roundToInt()}%"
                    else -> "%.1f".format(gestureValue)
                },
                color = if (isDragging) PremiumAccent else if (enabled) Color.White.copy(alpha = 0.5f) else Color.White.copy(0.3f),
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp
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
