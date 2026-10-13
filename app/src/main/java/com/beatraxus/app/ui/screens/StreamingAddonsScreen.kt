package com.beatraxus.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.addons.AddonManager
import com.beatraxus.app.addons.AddonPresets
import com.beatraxus.app.addons.MediaServerAddon
import com.beatraxus.app.addons.AddonConnectionState
import com.beatraxus.app.addons.MusicServiceAddon
import com.beatraxus.app.ui.components.glassIconBackground
import com.beatraxus.app.viewmodel.PlayerViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatraxus.app.ui.theme.appFontFamily

private val TextWhite = Color(0xFFF4F6F8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingAddonsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToBrowse: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    
    val addedAddons by AddonManager.addedAddons.collectAsStateWithLifecycle()
    val availableAddons by AddonManager.availableAddons.collectAsStateWithLifecycle()
    
    var showAddSheet by remember { mutableStateOf(false) }
    var addonToRemove by remember { mutableStateOf<MusicServiceAddon?>(null) }
    var addonToSignIn by remember { mutableStateOf<MediaServerAddon?>(null) }
    
    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                val result = AddonManager.installAddon(it)
                if (result.isSuccess) {
                    val pkg = result.getOrNull()!!
                    val newAddon = AddonManager.findById(pkg.manifest.id)
                    if (newAddon != null) {
                        AddonManager.addAddon(context, newAddon)
                    }
                    Toast.makeText(context, "Installed ${pkg.manifest.displayName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to install addon", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black.copy(0.15f),
                    scrolledContainerColor = Color.Black.copy(0.3f)
                ),
                title = {
                    Text(
                        text = "ADD-ONS",
                        color = TextWhite,
                        style = TextStyle(
                            fontFamily = appFontFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        },
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .glassIconBackground(
                                    backgroundColor = Color.White.copy(alpha = 0.05f),
                                    shape = CircleShape,
                                    borderColor = Color.White.copy(alpha = 0.1f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                "Back",
                                tint = TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                actions = {
                    Spacer(Modifier.width(60.dp))
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedMeshBackground()
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Connect your own media servers and catalogs. Content streams straight from the source into Beatraxus — Beatraxus doesn't host or store any of it. Long-press an add-on to remove it.",
                    color = TextWhite.copy(alpha = 0.55f),
                    style = TextStyle(
                        fontFamily = appFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )

                if (addedAddons.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No add-ons yet — tap below to add one.",
                            color = TextWhite.copy(alpha = 0.35f),
                            style = TextStyle(fontFamily = appFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        )
                    }
                }

                addedAddons.forEach { addon ->
                    StreamingServiceCard(
                        addon = addon,
                        isAvailable = false,
                        onClick = {
                            if (addon is MediaServerAddon) {
                                if (addon.connectionState.value == AddonConnectionState.CONNECTED) {
                                    onNavigateToBrowse(addon.id)
                                } else {
                                    addonToSignIn = addon
                                }
                            } else {
                                addon.play(context)
                            }
                        },
                        onLongClick = { addonToRemove = addon }
                    )
                }

                // "+ Add Streaming Addon" button styled as a glass card
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showAddSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.03f),
                    border = BorderStroke(
                        width = 0.8.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.1f),
                                Color.White.copy(alpha = 0.02f)
                            )
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            tint = TextWhite.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "ADD AN ADD-ON",
                            color = TextWhite.copy(alpha = 0.7f),
                            style = TextStyle(
                                fontFamily = appFontFamily,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }
                
                Spacer(Modifier.height(40.dp))
            }
        }

        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddSheet = false },
                containerColor = Color(0xFF121316),
                scrimColor = Color.Black.copy(alpha = 0.6f),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.1f)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 40.dp, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "ADD AN ADD-ON",
                        color = TextWhite,
                        style = TextStyle(fontFamily = appFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    )

                    val installedIds = (addedAddons + availableAddons).map { it.id }.toSet()
                    val presets = AddonPresets.all.filter { it.id !in installedIds }

                    presets.forEach { preset ->
                        ServiceCardContent(
                            title = preset.displayName,
                            subtitle = preset.blurb,
                            icon = preset.icon,
                            color = preset.brandColor,
                            isAvailable = true,
                            onClick = {
                                val addon = AddonManager.installPreset(preset)
                                if (addon != null) {
                                    AddonManager.addAddon(context, addon)
                                    showAddSheet = false
                                } else {
                                    Toast.makeText(context, "Couldn't add ${preset.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    availableAddons.forEach { addon ->
                        StreamingServiceCard(
                            addon = addon,
                            isAvailable = true,
                            onClick = {
                                AddonManager.addAddon(context, addon)
                                showAddSheet = false
                            }
                        )
                    }

                    ServiceCardContent(
                        title = "Install from ZIP",
                        subtitle = "A custom add-on package with a manifest.json",
                        icon = Icons.Rounded.Add,
                        color = TextWhite,
                        isAvailable = true,
                        onClick = {
                            showAddSheet = false
                            zipLauncher.launch(arrayOf("application/zip"))
                        }
                    )
                }
            }
        }

        addonToRemove?.let { addon ->
            AlertDialog(
                onDismissRequest = { addonToRemove = null },
                containerColor = Color(0xFF1A1B1F),
                title = {
                    Text("Remove Addon?", color = TextWhite)
                },
                text = {
                    Text("Remove ${addon.displayName} from your active addons?", color = TextWhite.copy(alpha = 0.7f))
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Fully uninstall the addon from disk
                        AddonManager.uninstallAddon(addon.id)
                        addonToRemove = null
                    }) {
                        Text("REMOVE", color = Color.Red.copy(alpha = 0.8f))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { addonToRemove = null }) {
                        Text("CANCEL", color = TextWhite)
                    }
                }
            )
        }
        
        addonToSignIn?.let { addon ->
            AddonSignInScreen(
                addon = addon,
                onDismiss = { addonToSignIn = null },
                onSuccess = {
                    val signedIn = addon
                    addonToSignIn = null
                    Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                    onNavigateToBrowse(signedIn.id)
                }
            )
        }
    }
}

private val StatusGreen = Color(0xFF4ADE80)
private val StatusRed = Color(0xFFFF5252)

@Composable
fun StreamingServiceCard(
    addon: MusicServiceAddon,
    isAvailable: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val state by addon.connectionState.collectAsStateWithLifecycle()
    val (statusText, statusColor) = when (state) {
        AddonConnectionState.CONNECTED -> "Connected" to StatusGreen
        AddonConnectionState.CONNECTING -> "Connecting…" to TextWhite.copy(alpha = 0.5f)
        AddonConnectionState.ERROR -> "Couldn't connect" to StatusRed
        AddonConnectionState.NOT_INSTALLED -> "App not installed" to TextWhite.copy(alpha = 0.5f)
        AddonConnectionState.NOT_ELIGIBLE -> "Not eligible" to TextWhite.copy(alpha = 0.5f)
        AddonConnectionState.NOT_CONNECTED -> "Sign-in needed" to TextWhite.copy(alpha = 0.5f)
    }
    ServiceCardContent(
        title = addon.displayName,
        subtitle = addon.description,
        icon = addon.icon,
        color = addon.brandColor,
        isAvailable = isAvailable,
        buttonLabel = addon.buttonLabel(),
        statusText = if (isAvailable) null else statusText,
        statusColor = statusColor,
        onClick = onClick,
        onLongClick = onLongClick
    )
}

/** Shared glass card used for added add-ons, installable presets and the "install from ZIP" row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServiceCardContent(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    isAvailable: Boolean,
    onClick: () -> Unit,
    buttonLabel: String = "",
    statusText: String? = null,
    statusColor: Color = TextWhite.copy(alpha = 0.5f),
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val iconColor = if (isAvailable) color.copy(alpha = 0.75f) else color
    val surfaceAlpha = if (isAvailable) 0.08f else 0.15f
    val borderAlpha = if (isAvailable) 0.05f else 0.15f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = surfaceAlpha),
        border = BorderStroke(
            width = 0.8.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = borderAlpha),
                    Color.White.copy(alpha = 0.02f),
                    iconColor.copy(alpha = borderAlpha * 1.5f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                    onLongClick = onLongClick?.let {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    }
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .glassIconBackground(
                        backgroundColor = iconColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(14.dp),
                        borderColor = iconColor.copy(alpha = 0.25f)
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

            Spacer(Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextWhite,
                    style = TextStyle(
                        fontFamily = appFontFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = TextWhite.copy(alpha = 0.55f),
                    style = TextStyle(
                        fontFamily = appFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (statusText != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            color = statusColor,
                            style = TextStyle(fontFamily = appFontFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            if (!isAvailable) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = iconColor.copy(alpha = 0.15f),
                        contentColor = iconColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = buttonLabel.uppercase(),
                        style = TextStyle(
                            fontFamily = appFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
