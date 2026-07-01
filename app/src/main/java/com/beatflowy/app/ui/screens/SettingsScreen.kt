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
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
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
import com.beatflowy.app.BuildConfig
import com.beatflowy.app.ui.components.PremiumSwitch
import com.beatflowy.app.ui.components.glassIconBackground
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.model.SoxrQuality
import com.beatflowy.app.model.DitherType
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.SoxrQuality as SoxrQualityEnum
import com.beatflowy.app.telegram.AuthState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.beatflowy.app.repository.DriveAccount
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.viewmodel.PlayerViewModel

private val PremiumAccent = Color(0xFFD4A24C)
private val PrimaryCyan = Color(0xFFD4A24C)
private val SecondaryCyan = Color(0xFFB8860B)
private val TextWhite = Color(0xFFF4F6F8)
private val SecondaryText = Color(0xFFAAB3BC)
private val BgColor = Color(0xFF0A0A0C)
private val CardSurface = Color(0xFF15161A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToDsp: () -> Unit,
    onRequestGDriveAccount: () -> Unit
) {
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    var showInfoPopup by remember { mutableStateOf(false) }
    val sectionStack = remember { mutableStateListOf<String>() }
    val currentSection = sectionStack.lastOrNull()
    var editingValue by remember { mutableStateOf<EditingValue?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { playerViewModel.exportSettings(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { playerViewModel.importSettings(it) }
    }

    var lastBackClickTime by remember { mutableStateOf(0L) }
    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackClickTime < 500) return@BackHandler
        lastBackClickTime = currentTime

        if (sectionStack.isNotEmpty()) sectionStack.removeAt(sectionStack.size - 1) else onBack()
    }

    val blurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidRenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.DECAL)
        } else null
    }

    val bgGradient = Brush.verticalGradient(listOf(Color(0xFF0A0A0C), Color(0xFF14110C)))
    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
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
            modifier = Modifier.blur(if (uiState.isFullScanning) 20.dp else 0.dp),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    title = {
                        Text(
                            text = (currentSection ?: "SETTINGS").uppercase(Locale.getDefault()),
                            color = TextWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (currentSection == null) 24.sp else 18.sp,
                            letterSpacing = if (currentSection == null) (-0.2).sp else 1.5.sp,
                            modifier = Modifier.animateContentSize()
                        )
                    },
                    navigationIcon = {
                        var lastClickTime by remember { mutableLongStateOf(0L) }
                        IconButton(
                            onClick = {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastClickTime < 500) return@IconButton
                                lastClickTime = currentTime
                                if (sectionStack.isNotEmpty()) sectionStack.removeAt(sectionStack.size - 1) else onBack()
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .glassIconBackground(
                                        backgroundColor = Color.White.copy(alpha = 0.07f),
                                        shape = CircleShape,
                                        borderColor = Color.White.copy(alpha = 0.12f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    "Back",
                                    tint = TextWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        // Empty balancing spacer for perfect centering
                        Spacer(Modifier.width(50.dp))
                    }
                )
            }
        )
{ padding ->
            Crossfade(
                targetState = currentSection,
                modifier = Modifier.padding(padding),
                animationSpec = tween(220, easing = EaseInOutCubic),
                label = "settings_transition"
            ) { section ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                        if (section == null) {
                            SettingMenuItem(
                                title = "Audio Engine",
                                subtitle = "Configure output, sample rates, Resampler, Dither...",
                                icon = Icons.Rounded.GraphicEq,
                                iconColor = Color(0xFF4CAF50),
                                onClick = { sectionStack.add("Audio Engine") }
                            )
                            SettingMenuItem(
                                title = "DSP Enhancements",
                                subtitle = "USB Direct, Bit-Perfect, DVC, Limiter",
                                icon = Icons.Rounded.Tune,
                                iconColor = Color(0xFFFF9800),
                                onClick = { sectionStack.add("DSP Enhancements") }
                            )
                            SettingMenuItem(
                                title = "Replay Gain",
                                subtitle = "Normalize volume across tracks",
                                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                                iconColor = Color(0xFF2196F3),
                                onClick = { sectionStack.add("Replay Gain") }
                            )
                            SettingMenuItem(
                                title = "Library",
                                subtitle = "Manage music folders and scanning",
                                icon = Icons.Rounded.AudioFile,
                                iconColor = Color(0xFFE91E63),
                                onClick = { sectionStack.add("Library") }
                            )
                            SettingMenuItem(
                                title = "Cloud Account (Admin Only)",
                                subtitle = "Cloud, Telegram and Metadata Sync",
                                icon = Icons.Rounded.Cloud,
                                iconColor = Color(0xFF1A73E8),
                                onClick = { sectionStack.add("Cloud") }
                            )
                            SettingMenuItem(
                                title = "Last.fm",
                                subtitle = "Scrobble your music and sync data",
                                icon = Icons.Rounded.MusicNote,
                                iconColor = Color(0xFFD32F2F),
                                onClick = { sectionStack.add("Last.fm") }
                            )
                            SettingMenuItem(
                                title = "Backup & Restore",
                                subtitle = "Export/Import settings and assign to devices",
                                icon = Icons.Rounded.Backup,
                                iconColor = Color(0xFF4CAF50),
                                onClick = { sectionStack.add("Backup & Restore") }
                            )
                            SettingMenuItem(
                                title = "About",
                                subtitle = "App version and information",
                                icon = Icons.Rounded.Info,
                                iconColor = Color(0xFF9C27B0),
                                onClick = { sectionStack.add("About") }
                            )
                        } else {
                            when (section) {
                                "Audio Engine" -> AudioEngineContent(uiState, playerViewModel, onEditValue = { editingValue = it })
                                "DSP Enhancements" -> DspEnhancementsContent(uiState, playerViewModel, onEditValue = { editingValue = it })
                                "Replay Gain" -> ReplayGainContent(uiState, playerViewModel, onEditValue = { editingValue = it })
                                "Library" -> LibraryContent(uiState, playerViewModel, onShowInfo = { showInfoPopup = true })
                                "Cloud" -> CloudContent(uiState, playerViewModel, onRequestGDriveAccount = onRequestGDriveAccount, onNavigateToGDriveSettings = { sectionStack.add("GDrive Settings") })
                                "GDrive Settings" -> MetadataSyncContent(uiState, playerViewModel)
                                "Last.fm" -> LastFmContent(uiState, playerViewModel)
                                "About" -> AboutContent()
                                "Backup & Restore" -> BackupRestoreContent(
                                    playerViewModel = playerViewModel,
                                    onExport = { exportLauncher.launch("beatflowy_backup.json") },
                                    onImport = { importLauncher.launch("application/json") }
                                )
                            }
                        }
                    }
                }
            }

        if (uiState.isFullScanning) {
            FullScanPopup(
                progress = uiState.scanProgress,
                count = uiState.scanCount,
                albums = uiState.albumCount,
                artists = uiState.artistCount,
                onDismiss = { playerViewModel.cancelScan() }
            )
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
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .shadow(12.dp, CircleShape, ambientColor = PremiumAccent.copy(0.3f), spotColor = PremiumAccent.copy(0.3f))
                                    .clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.22f), Color.Transparent))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = PremiumAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Original Quality Art",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "If you enable this, the app will store high-resolution album art which increases storage usage.",
                                color = Color.White.copy(0.65f),
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(20.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(PremiumAccent.copy(0.9f), Color(0xFF0066FF).copy(0.85f))
                                        )
                                    )
                                    .clickable { showInfoPopup = false }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
fun LastFmContent(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val uriHandler = LocalUriHandler.current
    val apiKey = BuildConfig.LASTFM_API_KEY

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(
            title = "ACCOUNT",
            icon = Icons.Rounded.Person,
            isActive = uiState.lastFmUsername != null,
            statusDot = if (uiState.lastFmUsername != null) Color(0xFFD32F2F) else null,
            subtitle = if (uiState.lastFmUsername != null) "Logged in as ${uiState.lastFmUsername}" else "Not connected"
        ) {
            if (uiState.lastFmUsername == null) {
                Text(
                    "Connect your Last.fm account to track your listening history and get music recommendations.",
                    color = Color.White.copy(0.6f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        uriHandler.openUri("https://www.last.fm/api/auth/?api_key=$apiKey&cb=beatflowy://lastfm")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Login with Last.fm", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(uiState.lastFmUsername, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Session active", color = Color(0xFF4CAF50), fontSize = 12.sp)
                    }
                    TextButton(onClick = { viewModel.logoutLastFm() }) {
                        Text("LOGOUT", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        SettingsSection(
            title = "SCROBBLING",
            icon = Icons.Rounded.Sync,
            isActive = uiState.scrobblingEnabled,
            headerActions = {
                PremiumSwitch(
                    checked = uiState.scrobblingEnabled,
                    onCheckedChange = { viewModel.setScrobblingEnabled(it) }
                )
            }
        ) {
            Text(
                "When enabled, tracks will be scrobbled to Last.fm after 50% or 4 minutes of playback.",
                color = Color.White.copy(0.5f),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.15f else 0.08f,
        animationSpec = tween(220, easing = EaseInOutCubic),
        label = "bg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(220, easing = EaseInOutCubic),
        label = "scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface.copy(alpha = bgAlpha))
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(
                        iconColor.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon with colored glass background
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .glassIconBackground(
                    backgroundColor = iconColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    borderColor = iconColor.copy(alpha = 0.18f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextWhite.copy(alpha = 0.7f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = TextWhite.copy(0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
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
        var showBufferSizeDialog by remember { mutableStateOf(false) }

        // ── 1. Output Configuration ──────────────────────────────────────────
        SettingsSection(
            title = "OUTPUT CONFIGURATION",
            icon = Icons.Rounded.SettingsInputComponent,
            isActive = true
        ) {
            Text(
                "OUTPUT METHOD",
                color = PrimaryCyan.copy(0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

            Spacer(Modifier.height(16.dp))

            val activeMode = OutputMode.fromName(uiState.outputMode)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(activeMode.title, color = PrimaryCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(activeMode.subtitle, color = TextWhite.copy(0.7f), fontSize = 14.sp, lineHeight = 18.sp)
                }
                
                Button(
                    onClick = { showBufferSizeDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryCyan.copy(0.12f),
                        contentColor = PrimaryCyan
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Rounded.SlowMotionVideo, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("BUFFER SIZE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            if (showBufferSizeDialog) {
                BufferSizeDialog(
                    uiState = uiState,
                    viewModel = playerViewModel,
                    onDismiss = { showBufferSizeDialog = false }
                )
            }

            if (activeMode == OutputMode.MMAP_EXCLUSIVE) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "MMAP BUFFER SIZE (FRAMES)",
                    color = PrimaryCyan.copy(0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                val bufferOptions = listOf(64, 96, 128, 192, 256)
                Row(
                    modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bufferOptions.forEach { frames ->
                        val isSelected = uiState.dsp.config.mmapRequestedBufferSizeFrames == frames
                        PremiumChip(
                            selected = isSelected,
                            onClick = { playerViewModel.setMmapBufferSize(frames) },
                            label = frames.toString()
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = TextWhite.copy(0.05f))
            Spacer(Modifier.height(16.dp))

            Text(
                text = uiState.hiResCapabilitySummary,
                color = if (uiState.hiResDirectSupported) PrimaryCyan.copy(0.8f) else TextWhite.copy(0.4f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(0.05f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "BYPASSED",
                            color = TextWhite.copy(0.3f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
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
                Text(
                    "TARGET SAMPLE RATE", 
                    color = PrimaryCyan.copy(if (isResamplerBypassed) 0.3f else 0.8f), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ResamplerMode.entries.forEach { mode ->
                        val isSelected = uiState.dsp.config.resamplerMode == mode
                        PremiumChip(
                            selected = isSelected,
                            onClick = { playerViewModel.setResamplerMode(mode) },
                            label = mode.displayName
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            DspSliderRow(
                title = "Cutoff Ratio",
                value = uiState.dsp.config.resamplerCutoffRatio,
                range = 0.5f..1.0f,
                enabled = uiState.dsp.config.highQualityResampler && !isResamplerBypassed,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChange = playerViewModel::setResamplerCutoffRatio,
                onValueClick = {
                    onEditValue(EditingValue("Cutoff Ratio", uiState.dsp.config.resamplerCutoffRatio, 0.5f..1.0f, playerViewModel::setResamplerCutoffRatio))
                }
            )
        }

        // ── 3. Bit Depth & Format ────────────────────────────────────────────
        SettingsSection(
            title = "DATA FORMAT",
            icon = Icons.Rounded.Memory,
            isActive = true
        ) {
            Text(
                "TARGET SAMPLE FORMAT", 
                color = PrimaryCyan.copy(0.8f), 
                fontSize = 11.sp, 
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sampleFormats.forEach { format ->
                    val isSelected = uiState.dsp.config.sampleFormat == format
                    PremiumChip(
                        selected = isSelected,
                        onClick = { playerViewModel.setSampleFormat(format) },
                        label = format.displayName
                    )
                }
            }
        }

        // Moved from DSP Enhancements
        SoxrQualityCard(uiState = uiState, viewModel = playerViewModel)

        Float64Card(uiState = uiState, viewModel = playerViewModel)

        DitherCard(uiState = uiState, viewModel = playerViewModel)

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
                color = TextWhite.copy(0.7f),
                fontSize = 14.sp,
                lineHeight = 18.sp
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

        LimiterCard(uiState = uiState, viewModel = viewModel, onEditValue = onEditValue)

        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsSection(
                title = "MONO DOWNMIX",
                icon = Icons.Rounded.Headset,
                isActive = config.monoEnabled,
                headerActions = {
                    PremiumSwitch(
                        checked = config.monoEnabled,
                        onCheckedChange = { viewModel.setMonoEnabled(it) }
                    )
                }
            ) {
                Text(
                    "Sums left and right channels into mono. Applied after EQ but before limiter.",
                    color = TextWhite.copy(0.7f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
            }

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
                        onValueChange = viewModel::setCrossfeedLevel,
                        onValueClick = {
                            onEditValue(EditingValue("Crossfeed Level", config.crossfeedLevel, 0f..1f, viewModel::setCrossfeedLevel))
                        }
                    )
                } else {
                    Text(
                        "Blends left and right channels to reduce listener fatigue on headphones.",
                        color = TextWhite.copy(0.7f),
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            SettingsSection(
                title = "SPATIAL AUDIO",
                icon = Icons.Rounded.SpatialAudioOff,
                isActive = config.spatialAudioEnabled,
                headerActions = {
                    PremiumSwitch(
                        checked = config.spatialAudioEnabled,
                        onCheckedChange = { viewModel.setSpatialAudioEnabled(it) }
                    )
                }
            ) {
                if (config.spatialAudioEnabled) {
                    DspSliderRow(
                        title = "Intensity",
                        value = config.spatialAudioIntensity,
                        range = 0f..1f,
                        enabled = true,
                        valueText = { "${(it * 100).toInt()}%" },
                        onValueChange = viewModel::setSpatialAudioIntensity,
                        onValueClick = {
                            onEditValue(EditingValue("Intensity", config.spatialAudioIntensity, 0f..1f, viewModel::setSpatialAudioIntensity))
                        }
                    )
                } else {
                    Text(
                        "Parametric binaural engine that simulates natural speaker placement. Recommended for headphones (pick either Spatial or Crossfeed, not both).",
                        color = TextWhite.copy(0.7f),
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                }
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
        statusDot = if (isUsbActive) PrimaryCyan else if (isUsbConnected) Color(0xFFFFAA00) else TextWhite.copy(0.2f),
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
        }

        if (config.usbExclusiveEnabled && !isUsbConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFF8800).copy(alpha = 0.08f))
                    .border(1.dp, Color(0xFFFF8800).copy(0.15f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = Color(0xFFFF8800),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "No USB DAC detected. Falling back to AAudio.",
                    color = Color(0xFFFF8800).copy(0.9f),
                    fontSize = 12.sp
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
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlight) PrimaryCyan.copy(alpha = 0.1f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                1.dp,
                if (highlight) PrimaryCyan.copy(0.2f) else Color.White.copy(0.07f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                color = TextWhite.copy(0.4f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                value,
                color = if (highlight) PrimaryCyan else TextWhite.copy(0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
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
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
private fun PremiumChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val background = if (selected) {
        Brush.linearGradient(listOf(Color.Black, Color.Black))
    } else {
        Brush.linearGradient(listOf(Color(0xFF1A232D), Color(0xFF1A232D)))
    }
    
    Box(
        modifier = modifier
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable { onClick() }
            .then(if (selected) Modifier.border(1.dp, PrimaryCyan, RoundedCornerShape(10.dp)) else Modifier)
            .then(if (selected) Modifier.shadow(elevation = 12.dp, shape = RoundedCornerShape(10.dp), ambientColor = PrimaryCyan.copy(0.2f), spotColor = PrimaryCyan.copy(0.2f)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(16.dp)
                    .height(2.dp)
                    .background(PrimaryCyan.copy(0.5f), CircleShape)
            )
        }
        Text(
            text = label,
            color = if (selected) PrimaryCyan else TextWhite.copy(0.7f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
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
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Hybrid RMS Leveler + 20ms Lookahead Limiter",
                    color = TextWhite.copy(0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "RMS DVC",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "RMS-based dynamic volume leveling",
                            color = Color.White.copy(0.5f),
                            fontSize = 11.sp
                        )
                    }
                    PremiumSwitch(
                        checked = config.rmsDvcEnabled,
                        onCheckedChange = { viewModel.setRmsDvcEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "RMS LEVELER",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Automatic volume balancing (Gain reduction)",
                            color = Color.White.copy(0.5f),
                            fontSize = 11.sp
                        )
                    }
                    PremiumSwitch(
                        checked = config.rmsLevelerEnabled,
                        onCheckedChange = { viewModel.setRmsLevelerEnabled(it) }
                    )
                }

                HorizontalDivider(color = Color.White.copy(0.05f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Headroom Management",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Prevents digital clipping from EQ/tone gains",
                            color = Color.White.copy(0.5f),
                            fontSize = 11.sp
                        )
                    }
                    PremiumSwitch(
                        checked = config.headroomManagementEnabled,
                        onCheckedChange = { viewModel.setHeadroomManagement(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Compensate DVC Volume",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Enable if you experience unexpectedly low volume on Android 15+ in DVC mode.",
                            color = Color.White.copy(0.5f),
                            fontSize = 11.sp
                        )
                    }
                    PremiumSwitch(
                        checked = config.compensateDvcVolumeEnabled,
                        onCheckedChange = { viewModel.setCompensateDvcVolumeEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "No Headroom Gain",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Don't reduce output gain when DVC is disabled. May cause distortion for high EQ/tone gains.",
                            color = Color.White.copy(0.5f),
                            fontSize = 11.sp
                        )
                    }
                    PremiumSwitch(
                        checked = config.noHeadroomGainEnabled,
                        onCheckedChange = { viewModel.setNoHeadroomGainEnabled(it) }
                    )
                }
            }

            if (isActive) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "DVC MODE", 
                        color = PrimaryCyan.copy(0.8f), 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DvcMode.entries.forEach { mode ->
                            val isSelected = config.dvcMode == mode
                            PremiumChip(
                                selected = isSelected,
                                onClick = { viewModel.setDvcMode(mode) },
                                label = mode.displayName
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

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
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (isBypassed) "BYPASSED" else "OFF",
                        color = TextWhite.copy(0.3f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryCyan.copy(0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        config.soxrQuality.displayName.uppercase(),
                        color = PrimaryCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            qualities.forEach { quality ->
                val isSelected = config.soxrQuality == quality
                val background = if (isSelected && canChange) {
                    Brush.linearGradient(listOf(Color.Black, Color.Black))
                } else {
                    Brush.linearGradient(listOf(Color(0xFF1A232D), Color(0xFF1A232D)))
                }

                Box(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(background)
                        .then(if (isSelected && canChange) Modifier.border(1.dp, PrimaryCyan, RoundedCornerShape(10.dp)) else Modifier)
                        .then(if (isSelected && canChange) Modifier.shadow(elevation = 12.dp, shape = RoundedCornerShape(10.dp), ambientColor = PrimaryCyan.copy(0.2f), spotColor = PrimaryCyan.copy(0.2f)) else Modifier)
                        .clickable(enabled = canChange) {
                            viewModel.setSoxrQuality(quality)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected && canChange) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .width(16.dp)
                                .height(2.dp)
                                .background(PrimaryCyan.copy(0.5f), CircleShape)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            quality.displayName.uppercase(),
                            color = if (isSelected && canChange) PrimaryCyan else TextWhite.copy(if (canChange) 0.8f else 0.3f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            when (quality) {
                                SoxrQualityEnum.QUICK    -> "Lowest CPU"
                                SoxrQualityEnum.LOW      -> "Light"
                                SoxrQualityEnum.MEDIUM   -> "Balanced"
                                SoxrQualityEnum.HIGH     -> "Recommended"
                                SoxrQualityEnum.VERY_HIGH -> "Max quality"
                            },
                            color = if (isSelected && canChange) PrimaryCyan.copy(0.7f) else TextWhite.copy(if (canChange) 0.45f else 0.15f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        if (config.soxrQuality == SoxrQualityEnum.VERY_HIGH && isResamplerOn && !config.bitPerfectEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryCyan.copy(0.05f))
                    .border(1.dp, PrimaryCyan.copy(0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Rounded.Info, null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
                Text(
                    "Very High quality uses significant CPU. Monitor for underruns.",
                    color = TextWhite.copy(0.8f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "BYPASSED",
                        color = TextWhite.copy(0.3f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            types.forEach { type ->
                val isSelected = currentType == type
                val canSelect = !isBypassed && config.ditherEnabled
                
                val background = if (isSelected && canSelect) {
                    Brush.linearGradient(listOf(Color.Black, Color.Black))
                } else {
                    Brush.linearGradient(listOf(Color(0xFF1A232D), Color(0xFF1A232D)))
                }

                Box(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(background)
                        .then(if (isSelected && canSelect) Modifier.border(1.dp, PrimaryCyan, RoundedCornerShape(10.dp)) else Modifier)
                        .then(if (isSelected && canSelect) Modifier.shadow(elevation = 12.dp, shape = RoundedCornerShape(10.dp), ambientColor = PrimaryCyan.copy(0.2f), spotColor = PrimaryCyan.copy(0.2f)) else Modifier)
                        .clickable(enabled = canSelect) {
                            viewModel.setDitherType(type)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected && canSelect) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .width(16.dp)
                                .height(2.dp)
                                .background(PrimaryCyan.copy(0.5f), CircleShape)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            type.displayName.uppercase(),
                            color = if (isSelected && canSelect) PrimaryCyan else TextWhite.copy(if (canSelect) 0.8f else 0.3f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            when (type) {
                                DitherType.TPDF -> "Standard"
                                DitherType.SHAPED -> "Low noise"
                                DitherType.HIGHPASS -> "Optimal"
                                else -> ""
                            },
                            color = if (isSelected && canSelect) Color.White.copy(0.8f) else TextWhite.copy(if (canSelect) 0.45f else 0.15f),
                            fontSize = 10.sp
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
    val isActive = (config.limiterEnabled || config.softLimiterEnabled) && !isBypassed

    val subtitle = when {
        isBypassed -> "Inactive — DSP bypassed"
        config.softLimiterEnabled -> "Soft Saturation active (fatigue-free)"
        config.limiterEnabled -> "Peak Limiter active (protection)"
        else -> "Digital clipping protection"
    }

    SettingsSection(
        title = "LIMITER & SATURATION",
        icon = Icons.Rounded.Security,
        isActive = isActive,
        subtitle = null,
        headerActions = {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PremiumAccent.copy(0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (config.limiterEnabled) {
                            "%.1f dB · %.0fms".format(config.limiterThresholdDb, config.limiterReleaseMs)
                        } else {
                            "SATURATION"
                        },
                        color = PremiumAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Smooth Soft-Knee + Dynamic Peak Protection",
                color = Color.White.copy(0.5f),
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic
            )

            if (!isBypassed) {
                // 1. Soft Limiter (Saturation)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Soft Limiter", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Gentle rounding for fatigue-free listening", color = Color.White.copy(0.5f), fontSize = 11.sp)
                    }
                    PremiumSwitch(
                        checked = config.softLimiterEnabled,
                        onCheckedChange = { viewModel.setSoftLimiterEnabled(it) }
                    )
                }

                HorizontalDivider(color = Color.White.copy(0.05f))

                // 2. Peak Limiter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Peak Limiter", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Hard lookahead transient protection", color = Color.White.copy(0.5f), fontSize = 11.sp)
                    }
                    PremiumSwitch(
                        checked = config.limiterEnabled,
                        onCheckedChange = { viewModel.setLimiterEnabled(it) }
                    )
                }

                if (config.limiterEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(0.03f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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

            // Move limiter subtitle below text
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(0.45f),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.02f else 1f,
        animationSpec = tween(220, easing = EaseInOutCubic),
        label = "SliderScale"
    )

    Column(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label.uppercase(Locale.getDefault()),
                color = Color.White.copy(0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (unit == "dB") "%.1f %s".format(value, unit) else "%.0f %s".format(value, unit),
                color = PremiumAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onLongPress() }
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = PremiumAccent,
                activeTrackColor = PremiumAccent,
                inactiveTrackColor = Color.White.copy(0.1f)
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}


@Composable
fun ReplayGainContent(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onEditValue: (EditingValue) -> Unit
) {
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
        Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column {
                Text(
                    "PROCESSING MODE",
                    color = PrimaryCyan.copy(if (config.replayGainEnabled) 0.8f else 0.3f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    com.beatflowy.app.model.ReplayGainOption.entries.forEach { option ->
                        val isSelected = config.replayGainOption == option
                        PremiumChip(
                            selected = isSelected,
                            onClick = { viewModel.setReplayGainOption(option) },
                            label = option.displayName
                        )
                    }
                }
            }

            Column {
                Text(
                    "SOURCE",
                    color = PrimaryCyan.copy(if (config.replayGainEnabled) 0.8f else 0.3f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    com.beatflowy.app.model.ReplayGainSource.entries.forEach { source ->
                        val isSelected = config.replayGainSource == source
                        Box(modifier = Modifier.weight(1f)) {
                            PremiumChip(
                                selected = isSelected,
                                onClick = { viewModel.setReplayGainSource(source) },
                                label = source.displayName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            DspSliderRow(
                title = "Pre-amplification",
                value = config.replayGainPreamp,
                range = -15f..15f,
                enabled = config.replayGainEnabled,
                valueText = { String.format(Locale.getDefault(), "%.1f dB", it) },
                onValueChange = viewModel::setReplayGainPreamp,
                onValueClick = {
                    onEditValue(EditingValue("Pre-amplification", config.replayGainPreamp, -15f..15f, viewModel::setReplayGainPreamp))
                }
            )

            if (!config.replayGainEnabled) {
                Text(
                    "Normalizes volume across tracks based on embedded ReplayGain tags. Prevents sudden volume jumps between albums.",
                    color = TextWhite.copy(0.5f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp
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
                .padding(vertical = 10.dp),
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
                    .padding(vertical = 10.dp),
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
fun CloudContent(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onRequestGDriveAccount: () -> Unit,
    onNavigateToGDriveSettings: () -> Unit
) {
    val driveAccounts = uiState.driveAccounts
    val telegramChannels = uiState.telegramChannels

    var driveQuery by remember { mutableStateOf("") }
    var telegramUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(
            title = "GOOGLE DRIVE",
            icon = Icons.Rounded.Cloud,
            isActive = true,
            statusDot = Color(0xFF1A73E8),
            subtitle = "Enrichment rules, network and data saver"
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
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1A73E8).copy(0.5f),
                    unfocusedBorderColor = Color.White.copy(0.1f),
                    focusedContainerColor = Color.Black.copy(0.2f),
                    unfocusedContainerColor = Color.Black.copy(0.2f),
                    cursorColor = Color(0xFF1A73E8)
                ),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp)
            )

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = if (error.contains("failed", ignoreCase = true)) Color.Red else Color(0xFF1A73E8),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            val filteredDrive = driveAccounts.filter {
                it.email.contains(driveQuery, ignoreCase = true) || it.accountName.contains(driveQuery, ignoreCase = true)
            }

            if (filteredDrive.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                filteredDrive.forEach { account ->
                    ConnectedAccountRow(
                        account = account,
                        onSync = { viewModel.scanDriveAccount(account.email) },
                        onToggle = { enabled -> viewModel.toggleDriveAccountEnabled(account.email, enabled) },
                        onRemove = { viewModel.removeDriveAccount(account.email) }
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(0.08f), modifier = Modifier.padding(vertical = 8.dp))
            
            CloudSettingsButton(
                title = "GDrive Settings",
                subtitle = "Network, Data Saver and Sync options",
                icon = Icons.Rounded.Settings,
                onClick = onNavigateToGDriveSettings
            )
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
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2AABEE).copy(0.5f),
                    unfocusedBorderColor = Color.White.copy(0.1f),
                    focusedContainerColor = Color.Black.copy(0.2f),
                    unfocusedContainerColor = Color.Black.copy(0.2f),
                    cursorColor = Color(0xFF2AABEE)
                ),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp)
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

            HorizontalDivider(color = Color.White.copy(0.08f), modifier = Modifier.padding(vertical = 8.dp))

            TelegramLoginCard(uiState, viewModel)
        }
    }
}

@Composable
private fun TelegramLoginCard(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val authState = uiState.telegramAuthState
    val isSubmitting = uiState.isSubmittingTelegram
    val authError = uiState.telegramAuthError

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(0.04f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = Color(0xFF2AABEE),
                modifier = Modifier.size(16.dp)
            )
            Text(
                "TELEGRAM ACCOUNT",
                color = Color(0xFF2AABEE),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Crossfade(targetState = authState, label = "telegram_auth_transition") { state ->
            when (state) {
                AuthState.LoggedOut -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Login to access private channels and faster downloads.",
                            color = Color.White.copy(0.6f),
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = { viewModel.submitTelegramPhone("") /* Trigger parameters step if needed, though usually automatic */ },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2AABEE)),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isSubmitting
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("LOGIN WITH TELEGRAM", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
                AuthState.WaitPhoneNumber -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Phone Number (+...)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSubmitting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { viewModel.submitTelegramPhone(phone) }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2AABEE),
                                unfocusedBorderColor = Color.White.copy(0.1f)
                            )
                        )
                        Button(
                            onClick = { viewModel.submitTelegramPhone(phone) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = phone.isNotBlank() && !isSubmitting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2AABEE))
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("SEND CODE")
                            }
                        }
                    }
                }
                AuthState.WaitCode -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("Verification Code") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSubmitting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { viewModel.submitTelegramCode(code) }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2AABEE),
                                unfocusedBorderColor = Color.White.copy(0.1f)
                            )
                        )
                        Button(
                            onClick = { viewModel.submitTelegramCode(code) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = code.isNotBlank() && !isSubmitting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2AABEE))
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("SUBMIT CODE")
                            }
                        }
                    }
                }
                AuthState.WaitPassword -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("2FA Password") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSubmitting,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { viewModel.submitTelegramPassword(password) }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2AABEE),
                                unfocusedBorderColor = Color.White.copy(0.1f)
                            )
                        )
                        Button(
                            onClick = { viewModel.submitTelegramPassword(password) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = password.isNotBlank() && !isSubmitting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2AABEE))
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("SUBMIT PASSWORD")
                            }
                        }
                    }
                }
                AuthState.Ready -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            Text("Authenticated", color = Color(0xFF4CAF50), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        authError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}


@Composable
private fun CloudSettingsButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(0.04f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF1A73E8).copy(0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color(0xFF1A73E8), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(0.5f), fontSize = 11.sp)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
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
            IconButton(onClick = onSync) {
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.03f)
        ),
        border = BorderStroke(
            1.dp,
            Color.White.copy(0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .glassIconBackground(
                            backgroundColor = PremiumAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            borderColor = PremiumAccent.copy(alpha = 0.25f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.NewReleases, null, tint = PremiumAccent, modifier = Modifier.size(18.dp))
                }
                Text(
                    "WHAT'S NEW",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
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
                .clip(RoundedCornerShape(14.dp))
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
                    RoundedCornerShape(14.dp)
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("HarbertRaj", color = Color.White.copy(0.5f), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(PremiumAccent.copy(0.18f), Color(0xFF0066FF).copy(0.14f))
                                )
                            )
                            .border(1.dp, PremiumAccent.copy(0.3f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "v$versionName",
                            color = PremiumAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = { uriHandler.openUri("https://github.com/Harbertraj/Beatraxus") },
                    modifier = Modifier
                        .align(Alignment.Top)
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
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
fun FullScanPopup(progress: Float, count: Int, albums: Int, artists: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight()
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
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(16.dp, CircleShape, ambientColor = PremiumAccent.copy(0.4f), spotColor = PremiumAccent.copy(0.4f))
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(PremiumAccent.copy(0.25f), Color.Transparent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, null, tint = PremiumAccent, modifier = Modifier.size(24.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Syncing Music",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ScanStatItem(Icons.Rounded.MusicNote, count.toString(), "Songs", Color(0xFFFF4081))
                        ScanStatItem(Icons.Rounded.Album, albums.toString(), "Albums", Color(0xFFB2FF59))
                        ScanStatItem(Icons.Rounded.Person, artists.toString(), "Artists", Color(0xFF7C4DFF))
                    }

                    Spacer(Modifier.height(24.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = PremiumAccent,
                        trackColor = Color.White.copy(0.1f)
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "${(progress * 100).toInt()}%",
                        color = PremiumAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
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
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = Color.White.copy(0.45f), fontSize = 10.sp)
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
        modifier = Modifier.fillMaxWidth().shadow(
            elevation = 8.dp,
            shape = RoundedCornerShape(24.dp),
            ambientColor = Color.Black.copy(0.35f),
            spotColor = Color.Black.copy(0.35f)
        ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardSurface.copy(alpha = 0.85f)
        ),
        border = BorderStroke(
            1.2.dp,
            if (isActive) PrimaryCyan.copy(0.3f) else Color(0xFF26D9FF).copy(0.15f)
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
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .glassIconBackground(
                                backgroundColor = PrimaryCyan.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                                borderColor = PrimaryCyan.copy(alpha = 0.2f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            null,
                            tint = PrimaryCyan,
                            modifier = Modifier.size(20.dp)
                        )

                        if (statusDot != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 2.dp, y = 2.dp)
                                    .size(8.dp)
                                    .background(Color(0xFF121212), CircleShape)
                                    .padding(1.2.dp)
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
                            title,
                            color = TextWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                color = TextWhite.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
                if (headerActions != null) {
                    headerActions()
                } else null
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
    val background = if (selected) {
        Brush.linearGradient(listOf(Color.Black, Color.Black))
    } else {
        Brush.linearGradient(listOf(Color(0xFF1A232D), Color(0xFF1A232D)))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .then(if (selected) Modifier.border(1.dp, PrimaryCyan, RoundedCornerShape(14.dp)) else Modifier)
            .then(if (selected) Modifier.shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp), ambientColor = PrimaryCyan.copy(0.2f), spotColor = PrimaryCyan.copy(0.2f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> TextWhite.copy(0.2f)
                selected -> PrimaryCyan
                else -> TextWhite.copy(0.7f)
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp,
            fontFamily = FontFamily.Monospace
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
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) PrimaryCyan.copy(0.08f) else TextWhite.copy(0.03f))
            .border(
                1.dp,
                if (checked) PrimaryCyan.copy(0.2f) else TextWhite.copy(0.07f),
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (checked)
                            Brush.verticalGradient(listOf(PrimaryCyan, SecondaryCyan))
                        else
                            Brush.verticalGradient(listOf(TextWhite.copy(0.2f), TextWhite.copy(0.05f)))
                    )
            )
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = if (checked) TextWhite else TextWhite.copy(0.7f),
                fontSize = 16.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium
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
    onValueChange: (Float) -> Unit,
    onValueClick: () -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = if (enabled) TextWhite else TextWhite.copy(alpha = 0.4f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryCyan.copy(alpha = if (enabled) 0.15f else 0.06f))
                    .clickable(enabled = enabled) { onValueClick() }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    valueText(value),
                    color = PrimaryCyan.copy(alpha = if (enabled) 1f else 0.35f),
                    fontSize = 12.sp,
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
                activeTrackColor = PrimaryCyan,
                inactiveTrackColor = Color(0xFF1E2B36),
                thumbColor = PrimaryCyan,
                disabledActiveTrackColor = PrimaryCyan.copy(alpha = 0.2f),
                disabledInactiveTrackColor = Color(0xFF1E2B36).copy(alpha = 0.5f),
                disabledThumbColor = PrimaryCyan.copy(alpha = 0.28f)
            ),
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}



@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        color = PrimaryCyan.copy(0.05f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.2.dp, PrimaryCyan.copy(0.15f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label.uppercase(), 
                color = TextWhite.copy(0.5f), 
                fontSize = 9.sp, 
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                value, 
                color = PrimaryCyan, 
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}


@Composable
fun BackupRestoreContent(
    playerViewModel: PlayerViewModel,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    SettingsSection(
        title = "Backup & Restore",
        icon = Icons.Rounded.Backup
    ) {
        SettingMenuItem(
            title = "Export Settings",
            subtitle = "Save all app settings to a file",
            icon = Icons.Rounded.Upload,
            iconColor = Color.White,
            onClick = onExport
        )
        SettingMenuItem(
            title = "Import Settings",
            subtitle = "Restore app settings from a file",
            icon = Icons.Rounded.Download,
            iconColor = Color.White,
            onClick = onImport
        )
    }

    SettingsSection(
        title = "Device Assignment",
        icon = Icons.Rounded.Devices
    ) {
        SettingMenuItem(
            title = "Assign to All Devices",
            subtitle = "Apply current DSP settings to all known devices",
            icon = Icons.Rounded.Sync,
            iconColor = Color.White,
            onClick = { playerViewModel.applyCurrentConfigToAllDevices() }
        )
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
fun BufferSizeDialog(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val config = uiState.dsp.config
    var pendingBufferMs by remember { mutableIntStateOf(config.outputBufferMs) }
    var pendingBufferCount by remember { mutableIntStateOf(config.outputBufferCount) }
    var pendingPostFadeMs by remember { mutableIntStateOf(config.postFadeBufferMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15161A),
        title = {
            Column {
                Text("Buffer Size", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Output buffer options for output device: ${uiState.dsp.activeOutputDeviceLabel}", color = Color.White.copy(0.6f), fontSize = 12.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text("PRESETS", color = PrimaryCyan.copy(0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Normal", "Fast", "Powersave").forEach { preset ->
                            val isSelected = when (preset) {
                                "Normal" -> pendingBufferMs == 50 && pendingBufferCount == 2 && pendingPostFadeMs == 0
                                "Fast" -> pendingBufferMs == 20 && pendingBufferCount == 2 && pendingPostFadeMs == 0
                                "Powersave" -> pendingBufferMs == 100 && pendingBufferCount == 4 && pendingPostFadeMs == 10
                                else -> false
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color.Black else Color.White.copy(0.05f))
                                    .border(1.dp, if (isSelected) PrimaryCyan else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        when (preset) {
                                            "Normal" -> { pendingBufferMs = 50; pendingBufferCount = 2; pendingPostFadeMs = 0 }
                                            "Fast" -> { pendingBufferMs = 20; pendingBufferCount = 2; pendingPostFadeMs = 0 }
                                            "Powersave" -> { pendingBufferMs = 100; pendingBufferCount = 4; pendingPostFadeMs = 10 }
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = preset,
                                    color = if (isSelected) PrimaryCyan else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                BufferSizeSliderRow("Buffer ms", pendingBufferMs.toFloat(), 10f..200f, 0, { "${it.toInt()}ms" }) { pendingBufferMs = it.toInt() }
                BufferSizeSliderRow("Buffers", pendingBufferCount.toFloat(), 2f..4f, 1, { it.toInt().toString() }) { pendingBufferCount = it.toInt() }
                BufferSizeSliderRow("Post-fade", pendingPostFadeMs.toFloat(), 0f..100f, 0, { "${it.toInt()}ms" }) { pendingPostFadeMs = it.toInt() }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.setOutputBufferMs(pendingBufferMs); viewModel.setOutputBufferCount(pendingBufferCount); viewModel.setPostFadeBufferMs(pendingPostFadeMs); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)) {
                Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(0.6f)) } },
        shape = RoundedCornerShape(24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f)
    )
}

@Composable
private fun BufferSizeSliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, valueText: (Float) -> String, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White.copy(0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(valueText(value), color = PrimaryCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps, colors = SliderDefaults.colors(activeTrackColor = PrimaryCyan, inactiveTrackColor = Color.White.copy(0.1f), thumbColor = PrimaryCyan), modifier = Modifier.padding(top = 4.dp))
    }
}
