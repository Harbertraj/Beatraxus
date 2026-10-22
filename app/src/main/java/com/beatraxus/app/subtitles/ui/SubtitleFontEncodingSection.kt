package com.beatraxus.app.subtitles.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import com.beatraxus.app.subtitles.domain.SubtitleEncodings
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel

/**
 * Video feature 23: the subtitle font (a TTF or OTF file) and the text encoding used to read subtitle files.
 * Shown in the player's subtitle sheet and in Settings -> Video Settings -> Subtitles.
 */
@Composable
fun SubtitleFontEncodingSection(
    subtitleViewModel: SubtitleViewModel,
    modifier: Modifier = Modifier
) {
    val mxOrange = Color(0xFFFF8F00)
    val subUiState by subtitleViewModel.uiState.collectAsState()
    var showEncodingDialog by remember { mutableStateOf(false) }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { subtitleViewModel.importCustomFont(it) }
    }

    if (showEncodingDialog) {
        SubtitleEncodingDialog(
            selectedId = subUiState.encodingId,
            onDismiss = { showEncodingDialog = false },
            onSelected = {
                subtitleViewModel.setEncoding(it)
                showEncodingDialog = false
            }
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Text encoding
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.06f))
                .clickable { showEncodingDialog = true }
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Text Encoding", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    SubtitleEncodings.labelFor(subUiState.encodingId),
                    color = mxOrange,
                    fontSize = 12.sp
                )
                Text(
                    "Fixes wrong characters in subtitle files you load. Pick the file again if it was loaded earlier.",
                    color = Color.White.copy(0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Change", color = mxOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        // Custom font
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
                Text("Subtitle Font", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    subUiState.customFontName ?: "Default",
                    color = mxOrange,
                    fontSize = 12.sp
                )
                Text(
                    "Use your own .ttf or .otf font file, for scripts the default font does not cover.",
                    color = Color.White.copy(0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        fontPickerLauncher.launch(
                            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")
                        )
                    },
                    border = BorderStroke(1.dp, mxOrange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mxOrange)
                ) {
                    Text("Choose", fontSize = 12.sp)
                }
                if (subUiState.customFontName != null) {
                    TextButton(onClick = { subtitleViewModel.clearCustomFont() }) {
                        Text("Reset", color = Color.White.copy(0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleEncodingDialog(
    selectedId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val mxOrange = Color(0xFFFF8F00)
    val options = remember { SubtitleEncodings.options() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        title = { Text("Text Encoding", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(options, key = { it.id }) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option.id) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.id == selectedId,
                            onClick = { onSelected(option.id) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = mxOrange,
                                unselectedColor = Color.White.copy(0.5f)
                            )
                        )
                        Text(option.label, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(0.7f))
            }
        }
    )
}
