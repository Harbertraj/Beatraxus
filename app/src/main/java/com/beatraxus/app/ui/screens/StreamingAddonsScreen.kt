package com.beatraxus.app.ui.screens

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
import com.beatraxus.app.addons.MusicServiceAddon
import com.beatraxus.app.ui.components.glassIconBackground
import com.beatraxus.app.viewmodel.PlayerViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val TextWhite = Color(0xFFF4F6F8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingAddonsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    val addedAddons by AddonManager.addedAddons.collectAsStateWithLifecycle()
    val availableAddons by AddonManager.availableAddons.collectAsStateWithLifecycle()
    
    var showAddSheet by remember { mutableStateOf(false) }
    var addonToRemove by remember { mutableStateOf<MusicServiceAddon?>(null) }

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
                        text = "STREAMING ADD-ONS",
                        color = TextWhite,
                        style = TextStyle(
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
                    text = "Connect or open your streaming apps from Beatraxus. These link to or control the official app for each service — Beatraxus does not stream or store content from them.",
                    color = TextWhite.copy(alpha = 0.55f),
                    style = TextStyle(
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
                            text = "No streaming addons added yet.",
                            color = TextWhite.copy(alpha = 0.35f),
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        )
                    }
                }

                addedAddons.forEach { addon ->
                    StreamingServiceCard(
                        addon = addon,
                        isAvailable = false,
                        onClick = { addon.play(context) },
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
                            text = "ADD STREAMING ADDON",
                            color = TextWhite.copy(alpha = 0.7f),
                            style = TextStyle(
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
                        text = "Available Addons",
                        color = TextWhite,
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    )
                    
                    if (availableAddons.isEmpty()) {
                        Text(
                            text = "No more addons available.",
                            color = TextWhite.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    } else {
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
                    }
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
                        AddonManager.removeAddon(addon)
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StreamingServiceCard(
    addon: MusicServiceAddon,
    isAvailable: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val iconColor = if (isAvailable) addon.brandColor.copy(alpha = 0.6f) else addon.brandColor
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
                    imageVector = addon.icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayName,
                    color = TextWhite,
                    style = TextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = addon.description,
                    color = TextWhite.copy(alpha = 0.55f),
                    style = TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
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
                        text = addon.buttonLabel().uppercase(),
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }
        }
    }
}
