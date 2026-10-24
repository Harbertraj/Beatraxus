package com.beatraxus.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.ui.theme.Icon
import com.beatraxus.app.util.NetworkStreamLink

/**
 * Feature 27: "Open network stream". The user pastes (or types) a link; it is checked as they type and
 * [onPlay] receives the cleaned address once they confirm.
 *
 * Built on [InfoStyleDialog], so it looks like every other popup in the app (blurred backdrop, glass card,
 * accent line, round icon badge, footer buttons).
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

    InfoStyleDialog(
        title = "Open network stream",
        icon = Icons.Rounded.Link,
        onDismiss = onDismiss,
        footer = {
            InfoActionButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            InfoActionButton("Play", onClick = ::submit, modifier = Modifier.weight(1f), primary = true, enabled = valid != null)
        }
    ) {
        Text(
            "Paste a link to a video file, an HLS (.m3u8) or a DASH (.mpd) stream.",
            color = Color.White.copy(0.6f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        val fieldShape = RoundedCornerShape(14.dp)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(NetworkStreamLink.MAX_LENGTH + 200) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = fieldShape,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            placeholder = { Text("https://example.com/video.mp4", fontSize = 13.sp) },
            isError = error != null,
            trailingIcon = {
                IconButton(onClick = { input = clipboard.getText()?.text.orEmpty() }) {
                    Icon(
                        Icons.Rounded.ContentPaste,
                        contentDescription = "Paste link",
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                errorTextColor = Color.White,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = Color.White.copy(0.14f),
                errorBorderColor = Color(0xFFFF5252),
                focusedPlaceholderColor = Color.White.copy(0.35f),
                unfocusedPlaceholderColor = Color.White.copy(0.35f),
                errorPlaceholderColor = Color.White.copy(0.35f),
                cursorColor = AccentBlue,
                errorCursorColor = Color(0xFFFF5252),
                focusedContainerColor = Color.White.copy(0.05f),
                unfocusedContainerColor = Color.White.copy(0.05f),
                errorContainerColor = Color.White.copy(0.05f)
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() })
        )

        // One line of feedback: what is wrong, or what will play.
        when {
            error != null -> Text(
                error,
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            valid != null -> Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF30D158), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    NetworkStreamLink.titleFor(valid.url),
                    color = Color.White.copy(0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
