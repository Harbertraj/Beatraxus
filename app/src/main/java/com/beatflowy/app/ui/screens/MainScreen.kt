package com.beatflowy.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.ui.components.*
import com.beatflowy.app.ui.theme.*
import com.beatflowy.app.viewmodel.PlayerViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onNavigateToEqualizer: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val songs   by viewModel.songs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDeep),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AccentRed, AccentBlue))))
                        Spacer(Modifier.width(8.dp))
                        Text("Beatflowy", fontWeight = FontWeight.Black,
                            fontSize = 22.sp, letterSpacing = (-0.5).sp, color = TextPrimary)
                    }
                },
                actions = {
                    Row(
                        Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnimatedContent(
                            targetState = uiState.resamplingEnabled,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "resLabel"
                        ) { on ->
                            Text(if (on) "HI-RES" else "NATIVE",
                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = if (on) AccentBlue else TextMuted)
                        }
                        Switch(
                            checked = uiState.resamplingEnabled,
                            onCheckedChange = { viewModel.toggleResampling() },
                            modifier = Modifier.height(24.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor   = androidx.compose.ui.graphics.Color.White,
                                checkedTrackColor   = AccentBlue,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = ToggleOff
                            )
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            AudioInfoBar(
                inputSampleRate   = uiState.inputSampleRate,
                outputSampleRate  = uiState.outputSampleRate,
                outputDevice      = uiState.outputDevice,
                resamplingEnabled = uiState.resamplingEnabled,
                modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider(color = Divider, thickness = 0.5.dp)

            Box(Modifier.weight(1f)) {
                if (songs.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, null,
                            tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No music found", style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("Add audio files to your device\nto start playing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        state           = listState,
                        modifier        = Modifier.fillMaxSize(),
                        contentPadding  = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(songs, key = { _, s -> s.id }) { idx, song ->
                            SongListItem(
                                song        = song,
                                isPlaying   = uiState.currentSong?.id == song.id && uiState.isPlaying,
                                trackNumber = idx + 1,
                                onClick     = { viewModel.playSong(song) }
                            )
                        }
                    }
                }
            }

            NowPlayingSection(
                song            = uiState.currentSong,
                isPlaying       = uiState.isPlaying,
                progressMs      = uiState.progressMs,
                durationMs      = uiState.currentSong?.durationMs ?: 0L,
                onPlayPause     = { viewModel.togglePlayPause() },
                onNext          = { viewModel.skipToNext() },
                onPrevious      = { viewModel.skipToPrevious() },
                onSeek          = { viewModel.seekTo(it) },
                onOpenEqualizer = onNavigateToEqualizer
            )
        }
    }
}
