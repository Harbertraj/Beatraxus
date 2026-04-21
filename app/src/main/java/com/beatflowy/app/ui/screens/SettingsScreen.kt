package com.beatflowy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.engine.OutputMode
import com.beatflowy.app.ui.theme.AccentBlue
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Scaffold(
        containerColor = BgDeep,
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
                // Output Mode Selection
                Text(
                    "Output Method",
                    color = Color.White.copy(0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputModeButton(
                        text = "AudioTrack",
                        selected = true,
                        onClick = { },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Resampling Toggle
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Stable Engine", color = Color.White, fontSize = 16.sp)
                        Text("Using stable MediaCodec + AudioTrack", color = Color.White.copy(0.5f), fontSize = 12.sp)
                    }
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            SettingsSection(title = "About", icon = Icons.Rounded.Settings) {
                Text("Beatflowy Audio Player", color = Color.White, fontSize = 16.sp)
                Text("Version 1.1.0-stable", color = Color.White.copy(0.5f), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Stable MediaCodec + AudioTrack engine.", color = Color.White.copy(0.7f), fontSize = 13.sp)
            }
        }
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
            Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(0.05f), MaterialTheme.shapes.medium)
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun OutputModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AccentBlue else Color.White.copy(0.1f),
            contentColor = if (selected) Color.Black else Color.White
        ),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
