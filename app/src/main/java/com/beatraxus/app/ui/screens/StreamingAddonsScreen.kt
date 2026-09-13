package com.beatraxus.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.ui.components.glassIconBackground
import com.beatraxus.app.viewmodel.PlayerViewModel
import java.util.Locale

private val PremiumAccent = Color(0xFF00C2A8)
private val TextWhite = Color(0xFFF4F6F8)
private val CardSurface = Color(0xFF15161A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingAddonsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
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
                    val haptic = LocalHapticFeedback.current
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

                // TODO: replace with StreamingAddonRepository / SpotifyRemoteRepository / StreamingLinkResolver once those are implemented
                
                StreamingServiceCard(
                    name = "Spotify",
                    subtitle = "Control playback",
                    icon = Icons.Rounded.MusicNote, // Placeholder icon
                    iconColor = Color(0xFF1DB954),
                    initialState = "Connect"
                )

                StreamingServiceCard(
                    name = "YouTube Music",
                    subtitle = "Open in app",
                    icon = Icons.Rounded.PlayArrow, // Placeholder icon
                    iconColor = Color(0xFFFF0000),
                    initialState = "Open"
                )

                StreamingServiceCard(
                    name = "Apple Music",
                    subtitle = "Open in app",
                    icon = Icons.Rounded.MusicNote,
                    iconColor = Color(0xFFFA243C),
                    initialState = "Open"
                )

                StreamingServiceCard(
                    name = "Amazon Music",
                    subtitle = "Open in app",
                    icon = Icons.Rounded.Cloud, // Placeholder icon
                    iconColor = Color(0xFF00A8E1),
                    initialState = "Open"
                )
                
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun StreamingServiceCard(
    name: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    initialState: String
) {
    var buttonText by remember { mutableStateOf(initialState) }
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.15f),
        border = BorderStroke(
            width = 0.8.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.15f),
                    Color.White.copy(alpha = 0.02f),
                    iconColor.copy(alpha = 0.2f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
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
                    text = name,
                    color = TextWhite,
                    style = TextStyle(
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
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    buttonText = if (name == "Spotify") {
                        if (buttonText == "Connect") "Connected" else "Connect"
                    } else {
                        buttonText
                    }
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
                    text = buttonText.uppercase(),
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
