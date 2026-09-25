package com.beatraxus.app.subtitles.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.subtitles.viewmodel.AutoSearchMode
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel

@Composable
fun SubtitleSettingsSection(
    subtitleViewModel: SubtitleViewModel
) {
    val mxOrange = Color(0xFFFF8F00)
    val subUiState by subtitleViewModel.uiState.collectAsState()

    var showAuthDialog by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    if (showAuthDialog) {
        OpenSubtitlesAuthDialog(
            onDismiss = { showAuthDialog = false },
            onSignIn = { username, password -> subtitleViewModel.signIn(username, password) }
        )
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            availableLanguages = subUiState.languages,
            selectedCodes = subUiState.selectedLanguages,
            onDismiss = { showLanguagePicker = false },
            onLanguagesSelected = { subtitleViewModel.setPreferredLanguages(it) }
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            title = { Text("Clear Subtitle Cache", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = { Text("Clear all downloaded and imported subtitle files from cache?", color = Color.White.copy(0.8f), fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        subtitleViewModel.clearSubtitleCache()
                        showClearCacheConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = mxOrange, contentColor = Color.Black)
                ) {
                    Text("Clear Cache", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text("Cancel", color = Color.White.copy(0.7f))
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (BuildConfig.OPENSUBTITLES_API_KEY.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.05f))
                    .padding(12.dp)
            ) {
                Text(
                    "Online subtitles are not configured in this build.",
                    color = Color.White.copy(0.5f),
                    fontSize = 12.sp
                )
            }
        }

        // Auto-search Mode
        Column {
            Text("Auto-Search Online Subtitles", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "Searches OpenSubtitles automatically when video playback starts (uses API quota)",
                color = Color.White.copy(0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoSearchMode.entries.forEach { mode ->
                    val isSelected = subUiState.autoSearchMode == mode
                    MxFilterChip(
                        selected = isSelected,
                        onClick = { subtitleViewModel.setAutoSearchMode(mode) },
                        label = mode.displayName,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Preferred Languages
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.06f))
                .clickable { showLanguagePicker = true }
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Preferred Languages", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    subUiState.selectedLanguages.joinToString(", ") { it.uppercase() },
                    color = mxOrange,
                    fontSize = 12.sp
                )
            }
            Text("Change", color = mxOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        // OpenSubtitles Account
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.06f))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OpenSubtitles Account", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (subUiState.isSignedIn) {
                    Text("Signed in as ${subUiState.username}", color = mxOrange, fontSize = 12.sp)
                } else {
                    Text("Optional — Sign in for higher daily download limit", color = Color.White.copy(0.5f), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.width(8.dp))

            if (subUiState.isSignedIn) {
                OutlinedButton(
                    onClick = { subtitleViewModel.signOut() },
                    border = BorderStroke(1.dp, mxOrange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mxOrange)
                ) {
                    Text("Sign Out", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = { showAuthDialog = true },
                    enabled = BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = mxOrange, contentColor = Color.Black)
                ) {
                    Text("Sign In", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Subtitle Cache
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.06f))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Subtitle Cache", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Manage cached subtitle files", color = Color.White.copy(0.5f), fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { showClearCacheConfirm = true },
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Clear Cache", fontSize = 12.sp)
            }
        }
    }
}
