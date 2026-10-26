package com.beatraxus.app.subtitles.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.beatraxus.app.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.data.SubtitleRepositoryImpl
import com.beatraxus.app.subtitles.domain.SecondarySubtitlePlacement
import com.beatraxus.app.subtitles.domain.SecondarySubtitleStyle
import com.beatraxus.app.subtitles.domain.SubtitleSourcePrefs
import com.beatraxus.app.subtitles.domain.SubtitlePositionPreset
import com.beatraxus.app.subtitles.viewmodel.SelectedSubtitleType
import com.beatraxus.app.subtitles.viewmodel.SubtitleUiState
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import com.beatraxus.app.subtitles.viewmodel.SyncTarget
import com.beatraxus.app.ui.screens.SubtitleColorRow
import com.beatraxus.app.viewmodel.VideoPlayerUiState
import com.beatraxus.app.viewmodel.VideoPlayerViewModel

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitlePlayerSheetSection(
    videoUiState: VideoPlayerUiState,
    videoViewModel: VideoPlayerViewModel,
    subtitleViewModel: SubtitleViewModel,
    onOpenAuthDialog: () -> Unit,
    onOpenGenerate: () -> Unit = {},
    onOpenTranslate: () -> Unit = {}
) {
    val mxOrange = Color(0xFFFF8F00)
    val subUiState by subtitleViewModel.uiState.collectAsState()

    var showOnlineResultsView by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var manualSearchText by remember { mutableStateOf("") }
    var tapSyncTarget by remember { mutableStateOf<SyncTarget?>(null) }
    // A generated / translated subtitle the viewer long-pressed, waiting for the delete to be confirmed.
    var deleteCandidate by remember { mutableStateOf<CachedSubtitle?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { subtitleViewModel.importLocalSubtitle(it) }
    }

    val secondaryFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { subtitleViewModel.importSecondarySubtitle(it) }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            availableLanguages = subUiState.languages,
            selectedCodes = subUiState.selectedLanguages,
            onDismiss = { showLanguagePicker = false },
            onLanguagesSelected = { subtitleViewModel.setPreferredLanguages(it) }
        )
    }

    deleteCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            title = { Text("Delete subtitle?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                val again = if (SubtitleSourcePrefs.isMadeHere(entry.subtitleId)) "generate it again" else "download or add it again"
                Text(
                    "\"${savedSubtitleLabel(entry)}\" will be removed from this video. You can $again later.",
                    color = Color.White.copy(0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    subtitleViewModel.deleteSavedSubtitle(entry.subtitleId)
                    deleteCandidate = null
                }) { Text("Delete", color = Color(0xFFFF5252)) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel", color = Color.White) } }
        )
    }

    tapSyncTarget?.let { target ->
        SubtitleTapSyncDialog(
            target = target,
            subtitleViewModel = subtitleViewModel,
            positionProvider = { videoViewModel.getPlayer()?.currentPosition ?: 0L },
            onDismiss = { tapSyncTarget = null }
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = mxOrange,
                    contentColor = Color.Black,
                    disabledContainerColor = mxOrange.copy(alpha = 0.5f),
                    disabledContentColor = Color.Black
                )
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search online", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

        // 2b. Auto-generate / translate: each opens its own view inside this sheet (like Extract Audio Track)
        SubtitleToolsSection(
            subUiState = subUiState,
            subtitleViewModel = subtitleViewModel,
            accent = mxOrange,
            onOpenGenerate = onOpenGenerate,
            onOpenTranslate = onOpenTranslate
        )

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
                    MxFilterChip(
                        selected = true,
                        onClick = { showLanguagePicker = true },
                        label = langCode.uppercase()
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

            // Every subtitle saved for this video is listed like the embedded tracks: the ones downloaded from the internet,
            // picked from a file, auto-generated or translated. Only one of them is loaded in the player at a time, so the
            // list comes from the saved ones, not from the player's tracks; nothing is shown until one is picked.
            val saved = subUiState.cachedSubtitles
            saved.forEach { g ->
                val label = savedSubtitleLabel(g)
                val picked = subUiState.enabled && subUiState.selectedSubtitleId == g.subtitleId &&
                    videoUiState.availableSubtitleTracks.any { it.isSelected && it.name == label }
                MadeSubtitleChip(
                    label = label,
                    picked = picked,
                    accent = mxOrange,
                    onClick = { subtitleViewModel.selectCachedSubtitle(g.subtitleId) },
                    onLongClick = { deleteCandidate = g }
                )
            }
            if (saved.isNotEmpty()) {
                Text("Long press a saved subtitle to delete it.", color = Color.White.copy(0.4f), fontSize = 11.sp)
            }

            // The loaded saved subtitle also shows up as a player track with the same name: list it once.
            val savedLabels = saved.map { savedSubtitleLabel(it) }.toSet()
            videoUiState.availableSubtitleTracks.filter { it.name !in savedLabels }.forEach { track ->
                FilterChip(
                    selected = track.isSelected && subUiState.enabled,
                    onClick = {
                        subtitleViewModel.enableSubtitles()
                        subtitleViewModel.selectEmbeddedTrack(track.index)
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = mxOrange, selectedLabelColor = Color.Black),
                    label = { Text(track.name + (track.language?.let { " ($it)" } ?: ""), color = if (track.isSelected && subUiState.enabled) Color.Black else Color.White) }
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
                        0L -> "Reset"
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
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, if (subUiState.delaySupported) mxOrange.copy(0.5f) else Color.White.copy(0.1f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Video feature 20: sync by tapping the line that is being spoken
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { tapSyncTarget = SyncTarget.MAIN },
                enabled = subUiState.delaySupported,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (subUiState.delaySupported) mxOrange.copy(0.5f) else Color.White.copy(0.1f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.Sync, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sync by tapping a line", fontSize = 13.sp)
            }
        }

        // 5b. Dual subtitles (video feature 22): a second subtitle shown together with the main one
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dual Subtitles", color = Color.White.copy(0.9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Show a second language at the same time",
                        color = Color.White.copy(0.5f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = subUiState.dualEnabled,
                    onCheckedChange = { subtitleViewModel.setDualSubtitlesEnabled(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = mxOrange, checkedTrackColor = mxOrange.copy(0.4f))
                )
            }

            if (subUiState.dualEnabled) {
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(0.06f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Second subtitle", color = Color.White.copy(0.6f), fontSize = 13.sp)
                    Text(
                        text = subUiState.secondarySubtitleName ?: "None",
                        color = mxOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Subtitles already saved for this video, other than the main one.
                val secondaryChoices = subUiState.cachedSubtitles.filter { it.subtitleId != subUiState.selectedSubtitleId }
                if (secondaryChoices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        secondaryChoices.forEach { cached ->
                            val label = cached.releaseName ?: cached.language
                            MxFilterChip(
                                selected = subUiState.secondarySubtitleName == label,
                                onClick = { subtitleViewModel.selectSecondaryFromCache(cached) },
                                label = label
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            secondaryFilePickerLauncher.launch(
                                arrayOf("text/plain", "application/x-subrip", "text/vtt", "application/octet-stream", "*/*")
                            )
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color.White.copy(0.3f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.Folder, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Select file", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { subtitleViewModel.clearSecondarySubtitle() },
                        enabled = subUiState.secondarySubtitleName != null,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color.White.copy(0.3f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Remove", fontSize = 13.sp)
                    }
                }

                // Video feature 20: swap the two subtitles, and sync the second one by tapping a line
                val hasSecond = subUiState.secondarySubtitleName != null
                val canSwap = hasSecond && subUiState.selectedSubtitleType == SelectedSubtitleType.EXTERNAL
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { subtitleViewModel.swapSubtitles() },
                        enabled = canSwap,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (canSwap) mxOrange.copy(0.5f) else Color.White.copy(0.1f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.SwapVert, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Swap", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { tapSyncTarget = SyncTarget.SECOND },
                        enabled = hasSecond,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (hasSecond) mxOrange.copy(0.5f) else Color.White.copy(0.1f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.Sync, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync second", fontSize = 13.sp)
                    }
                }
                if (hasSecond && !canSwap) {
                    Text(
                        "Swap needs a main subtitle from a file, not a built-in track",
                        color = Color.White.copy(0.4f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (hasSecond && subUiState.secondaryDelayMs != 0L) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val secondSec = subUiState.secondaryDelayMs / 1000f
                        Text(
                            "Second subtitle delay: " + if (secondSec > 0f) "+%.1f s".format(secondSec) else "%.1f s".format(secondSec),
                            color = mxOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Reset",
                            color = Color.White.copy(0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { subtitleViewModel.setSecondaryDelay(0L) }.padding(4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Placement", color = Color.White.copy(0.7f), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondarySubtitlePlacement.entries.forEach { placement ->
                        MxFilterChip(
                            selected = subUiState.secondaryStyle.placement == placement,
                            onClick = { subtitleViewModel.setSecondaryPlacement(placement) },
                            label = placement.displayName
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Second subtitle size", color = Color.White.copy(0.7f), fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${subUiState.secondaryStyle.sizePercent}%",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.width(48.dp)
                    )
                    Slider(
                        value = subUiState.secondaryStyle.sizePercent.toFloat(),
                        onValueChange = { subtitleViewModel.setSecondarySizePercent(it.toInt()) },
                        valueRange = SecondarySubtitleStyle.MIN_SIZE_PERCENT.toFloat()..SecondarySubtitleStyle.MAX_SIZE_PERCENT.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = mxOrange,
                            activeTrackColor = mxOrange,
                            inactiveTrackColor = Color.White.copy(0.2f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(10.dp))
                SubtitleColorRow(
                    "Second subtitle color",
                    subUiState.secondaryStyle.textColor,
                    onColorChange = { subtitleViewModel.setSecondaryTextColor(it) },
                    mxOrange
                )
            }
        }

        // 5c. Custom font and text encoding (video feature 23)
        SubtitleFontEncodingSection(subtitleViewModel = subtitleViewModel)

        // 6. Extended Subtitle Appearance
        Column {
            Text("Subtitle Appearance", color = Color.White.copy(0.9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(10.dp))

            // Subtitle Size
            Text("Size", color = Color.White.copy(0.7f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${videoUiState.subtitleSizePercent}%", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                Slider(
                    value = videoUiState.subtitleSizePercent.toFloat(),
                    onValueChange = { videoViewModel.setSubtitleSizePercent(it.toInt()) },
                    valueRange = 50f..200f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = mxOrange,
                        activeTrackColor = mxOrange,
                        inactiveTrackColor = Color.White.copy(0.2f)
                    ),
                    modifier = Modifier.weight(1f)
                )
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
                    MxFilterChip(
                        selected = videoUiState.subtitleEdgeType == edgeVal,
                        onClick = { videoViewModel.setSubtitleEdgeType(edgeVal) },
                        label = edgeLabel
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Position Preset: Bottom / Lower-Middle / Middle
            Text("Position Preset", color = Color.White.copy(0.7f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitlePositionPreset.entries.forEach { preset ->
                    MxFilterChip(
                        selected = videoUiState.subtitlePositionPreset == preset,
                        onClick = { videoViewModel.setSubtitlePositionPreset(preset) },
                        label = preset.displayName
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

/**
 * Two rows under the subtitle actions: "Auto-generate subtitles" and "Translate subtitles". Each opens its own view
 * inside this sheet (see SubtitleSubSheets.kt), laid out like the Extract Audio Track view of the Audio Tracks sheet.
 * While one is running the row shows its progress and a Stop button.
 */
@Composable
private fun SubtitleToolsSection(
    subUiState: SubtitleUiState,
    subtitleViewModel: SubtitleViewModel,
    accent: Color,
    onOpenGenerate: () -> Unit,
    onOpenTranslate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubtitleToolRow(
            icon = Icons.Rounded.Subtitles,
            title = if (!subUiState.isGenerating &&
                subtitleViewModel.hasGenerated(subUiState.cachedSubtitles, subUiState.generateTranslateTo)
            ) "Regenerate auto subtitles" else "Auto-generate subtitles",
            line = if (subUiState.isGenerating) subUiState.generationStatus ?: "Generating\u2026"
            else subUiState.generationStatus
                ?: if (subtitleViewModel.hasGenerated(subUiState.cachedSubtitles, subUiState.generateTranslateTo))
                    "Already generated. Tap to regenerate."
                else "Make subtitles from the speech in the audio",
            running = subUiState.isGenerating,
            accent = accent,
            onClick = onOpenGenerate,
            onStop = { subtitleViewModel.stopGeneration() }
        )
        SubtitleToolRow(
            icon = Icons.Rounded.Translate,
            title = "Translate subtitles",
            line = if (subUiState.isTranslating) subUiState.translationStatus ?: "Translating\u2026"
            else subUiState.translationStatus ?: "Translate a saved subtitle into another language",
            running = subUiState.isTranslating,
            accent = accent,
            onClick = onOpenTranslate,
            onStop = { subtitleViewModel.stopTranslation() }
        )
    }
}

@Composable
private fun SubtitleToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    line: String,
    running: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(line, color = Color.White.copy(0.6f), fontSize = 12.sp, maxLines = 2)
        }
        if (running) {
            CircularProgressIndicator(color = accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Stop", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStop).padding(horizontal = 6.dp, vertical = 4.dp)
            )
        } else {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The name a saved subtitle is listed under, and the label it carries once loaded in the player (the two must match, so the
 * player's own entry for it is not listed a second time). Falls back to what kind of subtitle it is when it has no name.
 */
private fun savedSubtitleLabel(entry: CachedSubtitle): String =
    entry.releaseName?.takeIf { it.isNotBlank() }
        ?: if (SubtitleSourcePrefs.isMadeHere(entry.subtitleId)) "Auto subtitles" else "External Subtitle"

/**
 * A track-list chip for a saved subtitle (downloaded, picked from a file, generated or translated). Tap picks it; long
 * press asks to delete it (a Material FilterChip has no long-press, so it is drawn here with the same shape and colours).
 */
@Composable
private fun MadeSubtitleChip(
    label: String,
    picked: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (picked) accent else Color.Transparent)
            .border(1.dp, if (picked) accent else Color.White.copy(0.3f), RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = if (picked) Color.Black else Color.White, fontSize = 14.sp, maxLines = 1)
    }
}
