package com.beatraxus.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.beatraxus.app.addons.EmbyAddon
import com.beatraxus.app.addons.MediaServerAddon
import com.beatraxus.app.addons.StremioAddon
import com.beatraxus.app.addons.SubsonicAddon
import com.beatraxus.app.addons.YouTubeAddon
import com.beatraxus.app.ui.components.glassIconBackground
import kotlinx.coroutines.launch

private val SheetBg = Color(0xFF121316)
private val TextWhite = Color(0xFFF4F6F8)
private val ErrorRed = Color(0xFFFF5252)

@Composable
fun AddonSignInScreen(
    addon: MediaServerAddon,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val isOtt = addon is StremioAddon
    val isYt = addon is YouTubeAddon
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // A YouTube key supplied through local.properties needs no typing: connect straight away.
    val ytKeyBuiltIn = isYt && com.beatraxus.app.BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    LaunchedEffect(addon) {
        if (ytKeyBuiltIn) {
            val result = addon.login("", "", "")
            if (result.isSuccess) onSuccess() else errorMessage = result.exceptionOrNull()?.message ?: "YouTube API key was rejected"
        } else if (addon.authType == "none") {
            val result = addon.login("", "", "")
            if (result.isSuccess) {
                onSuccess()
            } else {
                errorMessage = "Failed to auto-login"
            }
        }
    }

    if (addon.authType == "none") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    val brand = addon.brandColor
    val needsCredentials = !isYt && !isOtt && addon.authType == "server_credentials"
    val needsApiKey = !isYt && !isOtt && addon.authType == "api_key"

    val urlLabel = when {
        isOtt -> "Add-on manifest URL"
        else -> "Server address"
    }
    val hint = when {
        isYt -> "Uses the official YouTube player. Create a free YouTube Data API v3 key in Google Cloud Console and paste it here."
        isOtt -> "Paste the add-on's manifest link (stremio:// or https://). Only streams with a direct link can play in Beatraxus."
        addon is SubsonicAddon -> "Works with Navidrome, Airsonic, Gonic and other Subsonic servers. Example: music.example.com or 192.168.1.20:4533"
        addon is EmbyAddon -> "Example: 192.168.1.20:8096. Use your Emby username and password."
        else -> "Example: https://jellyfin.example.com or 192.168.1.20:8096"
    }

    val canSubmit = !isLoading && when {
        isYt -> apiKey.isNotBlank()
        needsCredentials -> serverUrl.isNotBlank() && username.isNotBlank()
        else -> serverUrl.isNotBlank()
    }

    fun submit() {
        if (!canSubmit) return
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val result = when {
                isYt -> addon.login("", "", apiKey)
                isOtt -> addon.login(serverUrl, "", "")
                needsCredentials -> addon.login(serverUrl, username, password)
                else -> addon.login(serverUrl, "", apiKey) // password param carries the API key
            }
            isLoading = false
            if (result.isSuccess) {
                onSuccess()
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "Login failed"
            }
        }
    }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SheetBg,
            border = BorderStroke(
                width = 0.8.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.02f),
                        brand.copy(alpha = 0.3f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .glassIconBackground(
                                backgroundColor = brand.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp),
                                borderColor = brand.copy(alpha = 0.25f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(addon.icon, contentDescription = null, tint = brand, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "SIGN IN",
                            color = brand,
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        )
                        Text(
                            text = addon.displayName,
                            color = TextWhite,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Text(
                    text = hint,
                    color = TextWhite.copy(alpha = 0.55f),
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                )

                errorMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ErrorRed.copy(alpha = 0.12f))
                            .border(0.8.dp, ErrorRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = msg,
                            color = ErrorRed,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }

                if (isYt) {
                    GlassField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "YouTube Data API v3 key",
                        brand = brand,
                        imeAction = ImeAction.Done,
                        onDone = ::submit
                    )
                } else {
                    GlassField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = urlLabel,
                        brand = brand,
                        keyboardType = KeyboardType.Uri,
                        imeAction = if (isOtt) ImeAction.Done else ImeAction.Next,
                        onDone = ::submit
                    )
                }

                if (needsCredentials) {
                    GlassField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Username",
                        brand = brand,
                        imeAction = ImeAction.Next
                    )
                    GlassField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        brand = brand,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onDone = ::submit,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = TextWhite.copy(alpha = 0.6f)
                                )
                            }
                        }
                    )
                } else if (needsApiKey) {
                    GlassField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API key",
                        brand = brand,
                        imeAction = ImeAction.Done,
                        onDone = ::submit
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text(
                            "CANCEL",
                            color = TextWhite.copy(alpha = 0.7f),
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = ::submit,
                        enabled = canSubmit,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brand,
                            contentColor = Color.White,
                            disabledContainerColor = brand.copy(alpha = 0.25f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "SIGN IN",
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    brand: Color,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            cursorColor = brand,
            focusedBorderColor = brand,
            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            focusedLabelColor = brand,
            unfocusedLabelColor = TextWhite.copy(alpha = 0.55f),
            focusedContainerColor = Color.White.copy(alpha = 0.04f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
