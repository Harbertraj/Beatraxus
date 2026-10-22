package com.beatraxus.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beatraxus.app.ui.theme.Icon
import com.beatraxus.app.util.NetworkStreamLink

/**
 * Feature 27: "Open network stream". The user pastes (or types) a link; it is checked as they type and
 * [onPlay] receives the cleaned address once they confirm.
 */
@Composable
fun OpenNetworkStreamDialog(
    onDismiss: () -> Unit,
    onPlay: (url: String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var input by rememberSaveable { mutableStateOf("") }
    // Errors only appear once there is text, so an empty field isn't shouted at.
    val result = remember(input) { if (input.isBlank()) null else NetworkStreamLink.parse(input) }
    val valid = result as? NetworkStreamLink.Result.Valid
    val error = (result as? NetworkStreamLink.Result.Invalid)?.message

    fun submit() { valid?.let { onPlay(it.url) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open network stream") },
        text = {
            Column {
                Text(
                    "Paste a link to a video file, an HLS (.m3u8) or a DASH (.mpd) stream.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(NetworkStreamLink.MAX_LENGTH + 200) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://example.com/video.mp4") },
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    trailingIcon = {
                        IconButton(onClick = { input = clipboard.getText()?.text.orEmpty() }) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste link", modifier = Modifier.size(20.dp))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = valid != null) { Text("Play") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
