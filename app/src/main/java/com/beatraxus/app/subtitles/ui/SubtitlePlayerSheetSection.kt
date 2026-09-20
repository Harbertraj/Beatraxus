package com.beatraxus.app.subtitles.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.subtitles.domain.SubtitlePositionPreset
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import com.beatraxus.app.ui.screens.SubtitleColorRow
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import com.beatraxus.app.viewmodel.VideoPlayerViewModel

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitlePlayerSheetSection(
    videoUiState: VideoPlayerUiState,
    videoViewModel: VideoPlayerViewModel,
    subtitleViewModel: SubtitleViewModel,
    onOpenAuthDialog: () -> Unit
) {
    val mxOrange = Color(0xFFFF8F00)
    val subUiState by subtitleViewModel.uiState.collectAsState()

    var showOnlineResultsView by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var manualSearchText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { subtitleViewModel.importLocalSubtitle(it) }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            availableLanguages = subUiState.languages,
            selectedCodes = subUiState.selectedLanguages,
            onDismiss = { showLanguagePicker = false },
            onLanguagesSelected = { subtitleViewModel.setPreferredLanguages(it) }
        )
    }

    if (showOnlineResultsView) {
        // Online Results Sub-View inside sheet
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showOnlineResultsView = false }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                    }
                    Text("Online Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                subUiState.downloadsRemaining?.let { remaining ->
                    Text(
                        "$remaining downloads left today",
                        color = mxOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                subUiState.isSearching -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = mxOrange)
                        Spacer(Modifier.height(12.dp))
                        Text("Searching subtitles...", color = Color.White.copy(0.7f), fontSize = 14.sp)
                    }
                }
                subUiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = subUiState.errorMessage ?: "Search failed",
                            color = Color.White.copy(0.8f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Button(
                            onClick = { subtitleViewModel.searchOnline() },
                            colors = ButtonDefaults.buttonColors(containerColor = mxOrange, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                subUiState.results.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No subtitles found for this video.", color = Color.White.copy(0.7f), fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualSearchText,
                            onValueChange = { manualSearchText = it },
                            label = { Text("Manual title search", color = Color.White.copy(0.6f)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = mxOrange,
                                unfocusedBorderColor = Color.White.copy(0.3f)
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { subtitleViewModel.searchOnline(manualSearchText) },
                            colors = ButtonDefaults.buttonColors(containerColor = mxOrange, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Search Manual Title", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        subUiState.results.forEach { scored ->
                            val result = scored.subtitle
                            val isDownloadingThis = subUiState.isDownloading == result.id || subUiState.isDownloading == result.fileId.toString()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(
                                        width = if (scored.isBestMatch) 1.5.dp else 1.dp,
                                        color = if (scored.isBestMatch) mxOrange else Color.White.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = result.language.uppercase(),
                                            color = mxOrange,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = result.fileName,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (scored.isBestMatch) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(mxOrange)
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text("Best Match", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                            }
                                        }

                                        if (result.isHearingImpaired) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.White.copy(0.2f))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text("HI", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        if (result.rating > 0f) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Rounded.Star, null, tint = mxOrange, modifier = Modifier.size(12.dp))
                                                Text("%.1f".format(result.rating), color = Color.White.copy(0.7f), fontSize = 10.sp)
                                            }
                                        }

                                        Text("${result.downloadCount} dl", color = Color.White.copy(0.5f), fontSize = 10.sp)
                                    }
                                }

                                if (isDownloadingThis) {
                                    CircularProgressIndicator(color = mxOrange, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(
                                        onClick = { subtitleViewModel.downloadAndApply(result) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Rounded.Download, "Download", tint = mxOrange)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }
    }

    // Normal Subtitles Sheet Section
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Header line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.06f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Current", color = Color.White.copy(0.6f), fontSize = 13.sp)
            Text(
                text = subUiState.selectedSubtitleName ?: if (subUiState.enabled) "Embedded Track" else "Off",
                color = mxOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Api Key Warning Note if blank
        if (BuildConfig.OPENSUBTITLES_API_KEY.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.05f))
                    .padding(10.dp)
            ) {
                Text(
                    "Online subtitles are not configured in this build.",
                    color = Color.White.copy(0.5f),
                    fontSize = 12.sp
                )
            }
        }

        // 2. Action Buttons: Search Online & Select File
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    showOnlineResultsView = true
                    subtitleViewModel.searchOnline()
                },
                enabled = BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = mxOrange, contentColor = Color.Black)
            ) {
                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search online", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf("text/plain", "application/x-subrip", "text/vtt", "application/octet-stream", "*/*")
                    )
                },
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Color.White.copy(0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Select file", fontSize = 13.sp)
            }
        }

        // 3. Preferred Languages filter row
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Languages", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "More languages...",
                    color = mxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showLanguagePicker = true }
                )
            }

            Spacer(Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                subUiState.selectedLanguages.forEach { langCode ->
                    FilterChip(
                        selected = true,
                        onClick = { showLanguagePicker = true },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = mxOrange,
                            selectedLabelColor = Color.Black
                        ),
                        label = { Text(langCode.uppercase()) }
                    )
                }
            }
        }

        // Existing Embedded Tracks Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Embedded Tracks", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)

            val isNoneSelected = !subUiState.enabled || videoUiState.availableSubtitleTracks.none { it.isSelected }
            FilterChip(
                selected = isNoneSelected,
                onClick = { subtitleViewModel.disableSubtitles() },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black),
                label = { Text("None", color = if (isNoneSelected) Color.Black else Color.White) }
            )

            videoUiState.availableSubtitleTracks.forEachIndexed { idx, track ->
                FilterChip(
                    selected = track.isSelected && subUiState.enabled,
                    onClick = {
                        subtitleViewModel.enableSubtitles()
                        subtitleViewModel.selectEmbeddedTrack(idx)
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black),
                    label = { Text(track.name + (track.language?.let { " ($it)" } ?: "")) }
                )
            }
        }

        // 5. Subtitle Delay Controls
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtitle Delay", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val delaySec = subUiState.delayMs / 1000f
                val delayText = if (delaySec > 0f) "+%.1f s".format(delaySec) else "%.1f s".format(delaySec)
                Text(delayText, color = mxOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            if (!subUiState.delaySupported) {
                Text(
                    "Delay works for downloaded/imported subtitles, not built-in tracks",
                    color = Color.White.copy(0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val delaySteps = listOf(-500L, -100L, 0L, 100L, 500L)
                delaySteps.forEach { step ->
                    val label = when (step) {
                        -500L -> "-0.5s"
                        -100L -> "-0.1s"
                        0L -> "0"
                        100L -> "+0.1s"
                        500L -> "+0.5s"
                        else -> "$step"
                    }
                    OutlinedButton(
                        onClick = {
                            if (step == 0L) subtitleViewModel.resetSubtitleDelay()
                            else subtitleViewModel.setSubtitleDelay(subUiState.delayMs + step)
                        },
                        enabled = subUiState.delaySupported,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (subUiState.delaySupported) mxOrange.copy(0.5f) else Color.White.copy(0.1f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        // 6. Extended Subtitle Appearance
        Column {
            Text("Subtitle Appearance", color = Color.White.copy(0.9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(10.dp))

            // Size Presets
            Text("Size Presets", color = Color.White.copy(0.7f), fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val sizePercents = listOf(75, 90, 100, 110, 125, 150)
                sizePercents.forEach { pct ->
                    FilterChip(
                        selected = videoUiState.subtitleSizePercent == pct,
                        onClick = { videoViewModel.setSubtitleSizePercent(pct) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black),
                        label = { Text("$pct%") }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Text Color
            SubtitleColorRow("Text Color", videoUiState.subtitleTextColor, onColorChange = { videoViewModel.setSubtitleTextColor(it) }, mxOrange)

            Spacer(Modifier.height(10.dp))

            // Edge Style: None / Outline / Shadow
            Text("Edge Style", color = Color.White.copy(0.7f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val edges = listOf(0 to "None", 1 to "Outline", 2 to "Shadow")
                edges.forEach { (edgeVal, edgeLabel) ->
                    FilterChip(
                        selected = videoUiState.subtitleEdgeType == edgeVal,
                        onClick = { videoViewModel.setSubtitleEdgeType(edgeVal) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black),
                        label = { Text(edgeLabel) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Position Preset: Bottom / Lower-Middle / Middle
            Text("Position Preset", color = Color.White.copy(0.7f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitlePositionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = videoUiState.subtitlePositionPreset == preset,
                        onClick = { videoViewModel.setSubtitlePositionPreset(preset) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black),
                        label = { Text(preset.displayName) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bold toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bold Text", color = Color.White.copy(0.8f), fontSize = 14.sp)
                Switch(
                    checked = videoUiState.subtitleBold,
                    onCheckedChange = { videoViewModel.setSubtitleBold(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = mxOrange, checkedTrackColor = mxOrange.copy(0.4f))
                )
            }

            Spacer(Modifier.height(10.dp))

            // Background Opacity
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bg Opacity", color = Color.White.copy(0.7f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                Slider(
                    value = videoUiState.subtitleBackgroundOpacity,
                    onValueChange = { videoViewModel.setSubtitleBackgroundOpacity(it) },
                    valueRange = 0.0f..1.0f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = mxOrange, activeTrackColor = mxOrange)
                )
                Text("${(videoUiState.subtitleBackgroundOpacity * 100).toInt()}%", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }

            Spacer(Modifier.height(14.dp))

            // Reset Button
            Button(
                onClick = { videoViewModel.resetSubtitleStyle() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f))
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset All Appearance Defaults", color = Color.White)
            }
        }
    }
}
