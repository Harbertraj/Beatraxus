package com.beatraxus.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.features.PtNearbyTransfer
import com.beatraxus.app.features.PtRadios
import com.beatraxus.app.features.PtMember
import com.beatraxus.app.features.PtStatus
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.viewmodel.PlayerViewModel

private val PtBg = Color(0xFF07060B)
private val PtGreen = Color(0xFF43E97B)
private val PtAmber = Color(0xFFFFC94A)

private val RULES_TEXT = """{
  "rules": {
    "playTogether": {
      "rooms": {
        "${'$'}room": {
          ".read": "auth != null",
          ".write": "auth != null"
        }
      }
    }
  }
}"""

@Composable
fun PlayTogetherScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val pt = remember { viewModel.playTogether }
    val ui by pt.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var name by remember(ui.displayName) { mutableStateOf(ui.displayName) }
    var joinCode by remember { mutableStateOf("") }

    val inRoom = ui.roomCode != null

    // ---- Nearby: permissions -> Bluetooth -> Wi-Fi -> (Location on Android 11-) -> switch on ----
    // 0 = idle, 1 = permissions, 2 = Bluetooth, 3 = Wi-Fi, 4 = Location, 5 = finish
    var nearbyStep by remember { mutableIntStateOf(0) }

    val nearbyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it } && PtNearbyTransfer.hasPermissions(context)) nearbyStep = 2
        else {
            nearbyStep = 0
            pt.setNearbyEnabled(false)
            Toast.makeText(context, "Nearby needs the Bluetooth / Nearby devices permission", Toast.LENGTH_LONG).show()
        }
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Bluetooth is off, so phones nearby cannot be found", Toast.LENGTH_LONG).show()
        }
        nearbyStep = 3
    }
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        nearbyStep = 4
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        nearbyStep = 5
    }

    LaunchedEffect(nearbyStep) {
        when (nearbyStep) {
            1 -> {
                if (PtNearbyTransfer.hasPermissions(context)) nearbyStep = 2
                else nearbyPermLauncher.launch(PtNearbyTransfer.requiredPermissions())
            }
            2 -> {
                if (PtRadios.bluetoothOn(context)) nearbyStep = 3
                else try { btLauncher.launch(PtRadios.bluetoothEnableIntent()) } catch (_: Exception) { nearbyStep = 3 }
            }
            3 -> {
                if (PtRadios.wifiOn(context) || PtRadios.enableWifiSilently(context) || Build.VERSION.SDK_INT < 29) {
                    nearbyStep = 4
                } else {
                    try { wifiLauncher.launch(PtRadios.wifiPanelIntent()) } catch (_: Exception) { nearbyStep = 4 }
                }
            }
            4 -> {
                if (!PtRadios.locationOff(context)) nearbyStep = 5
                else try { locationLauncher.launch(PtRadios.locationSettingsIntent()) } catch (_: Exception) { nearbyStep = 5 }
            }
            5 -> {
                pt.setNearbyEnabled(true)
                nearbyStep = 0
            }
        }
    }

    // Creating or joining a room switches Nearby on by itself (unless it was turned off by hand):
    // asks for the permissions, turns Bluetooth and Wi-Fi on, then connects with everyone in the room.
    LaunchedEffect(ui.roomCode) {
        if (ui.roomCode != null && nearbyStep == 0 && !pt.nearbyOptedOut &&
            (!ui.nearbyEnabled || PtRadios.anyOff(context))
        ) nearbyStep = 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PtBg)
    ) {
        // soft ambient glow
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(AccentBlue.copy(alpha = 0.18f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(300f, 200f),
                        radius = 1100f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- top bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("PLAY TOGETHER", color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("Listen in sync", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                !ui.configured -> SetupCard(
                    problem = ui.configProblem,
                    onCopyRules = {
                        clipboard.setText(AnnotatedString(RULES_TEXT))
                        Toast.makeText(context, "Rules copied", Toast.LENGTH_SHORT).show()
                    }
                )

                inRoom -> RoomCard(
                    code = ui.roomCode.orEmpty(),
                    status = ui.status,
                    members = ui.members,
                    title = ui.nowPlayingTitle,
                    artist = ui.nowPlayingArtist,
                    controller = ui.controllerName,
                    playing = ui.roomPlaying,
                    songMissing = ui.songMissing,
                    streaming = ui.streaming,
                    transferPercent = ui.transferPercent,
                    message = ui.message,
                    nearbyEnabled = ui.nearbyEnabled,
                    nearbyPeers = ui.nearbyPeers,
                    onNearbyToggle = { on ->
                        if (!on) { nearbyStep = 0; pt.setNearbyEnabled(false, byUser = true) }
                        else {
                            pt.setNearbyEnabled(true, byUser = true) // clears "turned off by hand"
                            nearbyStep = 1
                        }
                    },
                    onCopy = {
                        clipboard.setText(AnnotatedString(ui.roomCode.orEmpty()))
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join my Beatraxus room: ${ui.roomCode}  (Drawer > Play Together > Join)")
                        }
                        context.startActivity(Intent.createChooser(send, "Invite friends"))
                    },
                    onLeave = { pt.leave() }
                )

                else -> StartCard(
                    name = name,
                    onName = { name = it },
                    joinCode = joinCode,
                    onJoinCode = { joinCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                    busy = ui.busy,
                    message = ui.message,
                    info = ui.info,
                    onCreate = { pt.createRoom(name) },
                    onJoin = { pt.joinRoom(joinCode, name) },
                    onSetDbUrl = { pt.setDatabaseUrl(it) }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.08f), Color.White.copy(0.03f))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(0.24f), Color.White.copy(0.05f))),
                RoundedCornerShape(24.dp)
            )
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.White.copy(0.5f), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
}

@Composable
private fun PtField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    caps: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = if (caps) KeyboardOptions(capitalization = KeyboardCapitalization.Characters) else KeyboardOptions.Default,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontSize = 15.sp,
            letterSpacing = if (caps) 3.sp else 0.sp
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = Color.White.copy(0.16f),
            focusedLabelColor = AccentBlue,
            unfocusedLabelColor = Color.White.copy(0.5f),
            cursorColor = AccentBlue,
            focusedContainerColor = Color.White.copy(0.05f),
            unfocusedContainerColor = Color.White.copy(0.05f)
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AccentBlue.copy(alpha = if (enabled) 0.92f else 0.35f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Color(0xFFFF6B6B) else Color.White
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = 0.10f))
            .border(0.5.dp, c.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
private fun StartCard(
    name: String,
    onName: (String) -> Unit,
    joinCode: String,
    onJoinCode: (String) -> Unit,
    busy: Boolean,
    message: String?,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    info: String? = null,
    onSetDbUrl: (String) -> Unit = {}
) {
    var dbUrlText by remember { mutableStateOf("") }
    // Keep the URL box on screen once a "Database not found" error appeared, until a URL is accepted
    // (the error message itself is cleared whenever the person taps a button).
    var showDbUrl by remember { mutableStateOf(false) }
    LaunchedEffect(message) { if (message?.startsWith("Database not found") == true) showDbUrl = true }
    LaunchedEffect(info) { if (info != null) { showDbUrl = false; dbUrlText = "" } }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(AccentBlue.copy(0.16f), CircleShape)
                    .border(1.dp, AccentBlue.copy(0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Groups, null, tint = AccentBlue, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Same song, same moment", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    "Everyone in the room hears what the last person played.",
                    color = Color.White.copy(0.6f), fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("YOUR NAME")
        Spacer(Modifier.height(6.dp))
        PtField("Display name", name, onName)
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp, lineHeight = 17.sp)
        }
        info?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = PtGreen, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (showDbUrl) {
            Spacer(Modifier.height(10.dp))
            PtField("Database URL (https://...firebasedatabase.app)", dbUrlText, { v -> dbUrlText = v }, mono = true)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                "Save database URL",
                enabled = dbUrlText.isNotBlank(),
                busy = busy,
                onClick = { onSetDbUrl(dbUrlText) }
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Create a room", enabled = name.isNotBlank(), busy = busy, onClick = onCreate)
    }

    Spacer(Modifier.height(14.dp))

    GlassCard {
        SectionLabel("HAVE A CODE?")
        Spacer(Modifier.height(8.dp))
        PtField("Room code", joinCode, onJoinCode, mono = true, caps = true)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Join room", enabled = joinCode.length >= 4 && name.isNotBlank(), busy = busy, onClick = onJoin)
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "Each phone plays the song from its own library, so everyone needs the same tracks. " +
            "Songs are matched by title, artist and length.",
        color = Color.White.copy(0.45f), fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

@Composable
private fun RoomCard(
    code: String,
    status: PtStatus,
    members: List<PtMember>,
    title: String?,
    artist: String?,
    controller: String?,
    playing: Boolean,
    songMissing: Boolean,
    streaming: Boolean,
    transferPercent: Int = -1,
    message: String?,
    nearbyEnabled: Boolean,
    nearbyPeers: Int = 0,
    onNearbyToggle: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onLeave: () -> Unit
) {
    // ---- code ----
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ROOM CODE")
            Spacer(Modifier.weight(1f))
            val live = status == PtStatus.CONNECTED
            Box(Modifier.size(8.dp).background(if (live) PtGreen else PtAmber, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (live) "Live" else "Connecting…", color = if (live) PtGreen else PtAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            code.chunked(3).joinToString(" "),
            color = Color.White,
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Copy", Modifier.weight(1f), onClick = onCopy)
            GhostButton("Invite", Modifier.weight(1f), onClick = onShare)
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = PtAmber, fontSize = 12.sp)
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- now playing ----
    GlassCard {
        SectionLabel("NOW PLAYING IN THE ROOM")
        Spacer(Modifier.height(10.dp))
        if (title == null) {
            Text(
                "Nothing yet. Play any song and everyone will hear it.",
                color = Color.White.copy(0.6f), fontSize = 13.sp, lineHeight = 18.sp
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(AccentBlue.copy(0.16f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.MusicNote, null, tint = AccentBlue) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist.orEmpty(), color = Color.White.copy(0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (playing) "PLAYING" else "PAUSED",
                    color = if (playing) PtGreen else Color.White.copy(0.5f),
                    fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp
                )
            }
            controller?.let {
                Spacer(Modifier.height(8.dp))
                Text("Started by $it", color = Color.White.copy(0.5f), fontSize = 12.sp)
            }
            if (streaming) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "This song isn't in your library, so it is streaming online from ${controller ?: "the controller"}.",
                    color = PtGreen, fontSize = 12.sp, lineHeight = 17.sp
                )
            } else if (songMissing) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Getting this song from ${controller ?: "the controller"}" + (if (transferPercent in 0..100) " ($transferPercent%)…" else "…"),
                    color = PtAmber, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- same-place transfer ----
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("NEARBY (SAME PLACE)")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turns on Bluetooth and Wi-Fi and connects every phone in the same room, so songs are sent over Bluetooth / Wi-Fi Direct. Works without internet; falls back to online transfer.",
                    color = Color.White.copy(0.55f), fontSize = 12.sp, lineHeight = 17.sp
                )
                if (nearbyEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (nearbyPeers > 0) "Connected to $nearbyPeers nearby " + (if (nearbyPeers == 1) "phone" else "phones")
                        else "Looking for nearby phones\u2026",
                        color = if (nearbyPeers > 0) PtGreen else PtAmber,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = nearbyEnabled, onCheckedChange = onNearbyToggle)
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- members ----
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("LISTENING (${members.size})")
        }
        Spacer(Modifier.height(8.dp))
        if (members.size < 2) {
            Text("Waiting for friends to join…", color = Color.White.copy(0.55f), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
        members.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(34.dp).background(AccentBlue.copy(0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        m.name.take(1).uppercase().ifBlank { "?" },
                        color = AccentBlue, fontWeight = FontWeight.Black, fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    m.name + if (m.isMe) "  (you)" else "",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (m.isController) {
                    Text(
                        "CONTROLLING",
                        color = AccentBlue, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AccentBlue.copy(0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    GhostButton("Leave room", Modifier.fillMaxWidth(), danger = true, onClick = onLeave)
}

@Composable
private fun SetupCard(problem: String?, onCopyRules: () -> Unit) {
    GlassCard {
        Text("Firebase isn\u2019t connected yet", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            problem ?: "google-services.json was not found in the app.",
            color = PtAmber, fontSize = 12.sp, lineHeight = 17.sp
        )
    }

    Spacer(Modifier.height(14.dp))

    GlassCard {
        SectionLabel("ONE-TIME SETUP")
        Spacer(Modifier.height(8.dp))
        listOf(
            "1. Firebase console > Build > Realtime Database > Create database.",
            "2. Build > Authentication > Sign-in method > enable \"Anonymous\".",
            "3. Realtime Database > Rules: paste the rules below and Publish.",
            "4. Project settings > Your apps > add this app\u2019s SHA-1 (debug and release).",
            "5. Download google-services.json (after creating the database), put it in the app folder (app/google-services.json) and rebuild."
        ).forEach {
            Text(it, color = Color.White.copy(0.75f), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            RULES_TEXT,
            color = Color(0xFF9FE8B5),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(0.35f))
                .padding(12.dp)
        )
        Spacer(Modifier.height(10.dp))
        GhostButton("Copy rules", Modifier.fillMaxWidth(), onClick = onCopyRules)
    }
}
