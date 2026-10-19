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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.unit.sp
import com.beatraxus.app.features.PtNearbyTransfer
import com.beatraxus.app.features.PtRoute
import com.beatraxus.app.features.PtRadios
import com.beatraxus.app.features.PtMember
import com.beatraxus.app.features.PtStatus
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.viewmodel.PlayerViewModel

private val PtBg = Color(0xFF08070D)
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

    // Nearby is off by default: it only starts when the person switches it on (permissions -> Bluetooth ->
    // Wi-Fi -> connect, handled by nearbyStep above).


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PtBg)
            // two soft lights: cool from the top-left, a hint of "live" green from the bottom-right
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(AccentBlue.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.05f),
                        radius = size.width * 1.1f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(PtGreen.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.95f),
                        radius = size.width * 0.9f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ---- top bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.07f))
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(14.dp))
                Text("Play Together", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

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
                    route = ui.route,
                    routeProblem = ui.routeProblem,
                    // what stops same-place transfer right now (permission, Location, Bluetooth, Wi-Fi)
                    radioProblem = if (ui.nearbyEnabled) PtRadios.problem(context) else null,
                    syncOffsetMs = ui.syncOffsetMs,
                    onSyncOffset = { pt.setSyncOffsetMs(it) },
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

            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

/** One quiet surface that holds several sections separated by hairlines (instead of a stack of cards). */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(26.dp)),
        content = content
    )
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f))
    )
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, color = Color.White.copy(0.45f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.White.copy(0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = if (caps) KeyboardOptions(capitalization = KeyboardCapitalization.Characters) else KeyboardOptions.Default,
        textStyle = TextStyle(
            fontFamily = if (mono) FontFamily.Monospace else androidx.compose.material3.LocalTextStyle.current.fontFamily,
            fontSize = if (caps) 20.sp else 16.sp,
            fontWeight = if (caps) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = if (caps) 4.sp else 0.sp
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = Color.White.copy(0.10f),
            focusedLabelColor = AccentBlue,
            unfocusedLabelColor = Color.White.copy(0.5f),
            cursorColor = AccentBlue,
            focusedContainerColor = Color.White.copy(0.06f),
            unfocusedContainerColor = Color.White.copy(0.06f)
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(AccentBlue.copy(alpha = if (enabled) 1f else 0.30f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, color = Color.Black.copy(alpha = if (enabled) 1f else 0.6f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Secondary action: outlined pill. */
@Composable
private fun OutlineButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val a = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .border(1.5.dp, AccentBlue.copy(alpha = 0.8f * a), CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, color = AccentBlue.copy(alpha = a), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Color(0xFFFF6B6B) else Color.White
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(c.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = c, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

/** Pill with a leading icon, used for Copy / Invite on the room code. */
@Composable
private fun IconPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Small dot that breathes while the room is live. */
@Composable
private fun LiveDot(live: Boolean) {
    val color = if (live) PtGreen else PtAmber
    val t = rememberInfiniteTransition(label = "liveDot")
    val ring by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color.copy(alpha = 0.35f * (1f - ring)), radius = size.minDimension / 2f * (0.45f + 0.55f * ring))
            drawCircle(color, radius = size.minDimension * 0.22f)
        }
    }
}

/** Four bars that dance while the song plays and rest low while it is paused. */
@Composable
private fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.9f, 0.2f, infiniteRepeatable(tween(680, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.3f, 0.95f, infiniteRepeatable(tween(440, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    val d by t.animateFloat(0.8f, 0.25f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    Canvas(modifier.size(width = 26.dp, height = 22.dp)) {
        val levels = if (playing) listOf(a, b, c, d) else listOf(0.22f, 0.22f, 0.22f, 0.22f)
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * 3) / 4f
        levels.forEachIndexed { i, lv ->
            val h = size.height * lv
            drawRoundRect(
                color = color.copy(alpha = if (playing) 1f else 0.45f),
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}

private val AvatarTints = listOf(
    Color(0xFF7AA2FF), Color(0xFF43E97B), Color(0xFFFFC94A), Color(0xFFFF8FB1), Color(0xFFB388FF), Color(0xFF5CE1E6)
)

private fun avatarTint(name: String): Color = AvatarTints[(name.hashCode() and 0x7fffffff) % AvatarTints.size]

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, ring: Color = PtBg) {
    val tint = avatarTint(name)
    Box(
        Modifier
            .size(size)
            .background(ring, CircleShape)
            .padding(2.dp)
            .background(tint.copy(alpha = 0.28f), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1).uppercase().ifBlank { "?" }, color = tint, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Start (not in a room)
// ---------------------------------------------------------------------------------------------

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

    Spacer(Modifier.height(12.dp))
    Text(
        "Same song,\nsame moment.",
        color = Color.White, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Everyone in the room hears what the last person played.",
        color = Color.White.copy(0.62f), fontSize = 15.sp, lineHeight = 21.sp,
        modifier = Modifier.widthIn(max = 320.dp)
    )

    Spacer(Modifier.height(28.dp))
    PtField("Your name", name, onName)
    message?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, lineHeight = 18.sp)
    }
    info?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = PtGreen, fontSize = 13.sp, lineHeight = 18.sp)
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
    Spacer(Modifier.height(14.dp))
    PrimaryButton("Create a room", enabled = name.isNotBlank(), busy = busy, onClick = onCreate)

    Spacer(Modifier.height(26.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(0.10f)))
        Text("or join with a code", color = Color.White.copy(0.5f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(0.10f)))
    }
    Spacer(Modifier.height(18.dp))

    PtField("Room code", joinCode, onJoinCode, mono = true, caps = true)
    Spacer(Modifier.height(12.dp))
    OutlineButton("Join room", enabled = joinCode.length >= 4 && name.isNotBlank(), busy = busy, onClick = onJoin)

    Spacer(Modifier.height(22.dp))
    Text(
        "Each phone plays the song from its own library, so everyone needs the same tracks. " +
            "Songs are matched by title, artist and length.",
        color = Color.White.copy(0.45f), fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ---------------------------------------------------------------------------------------------
// In a room
// ---------------------------------------------------------------------------------------------

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
    route: PtRoute? = null,
    routeProblem: String? = null,
    radioProblem: String? = null,
    syncOffsetMs: Int = 0,
    onSyncOffset: (Int) -> Unit = {},
    onNearbyToggle: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onLeave: () -> Unit
) {
    val live = status == PtStatus.CONNECTED
    val heroShape = RoundedCornerShape(30.dp)

    // ---- hero: the room code is the thing to share ----
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(heroShape)
            .background(
                Brush.linearGradient(
                    listOf(AccentBlue.copy(alpha = 0.42f), AccentBlue.copy(alpha = 0.10f), Color(0xFF0E0D16))
                )
            )
            .border(1.dp, Brush.verticalGradient(listOf(AccentBlue.copy(0.55f), Color.White.copy(0.06f))), heroShape)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(live)
            Spacer(Modifier.width(6.dp))
            Text(
                if (live) "Live" else "Connecting…",
                color = if (live) PtGreen else PtAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text("Room code", color = Color.White.copy(0.6f), fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            code.chunked(3).joinToString("  "),
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconPill("Copy", Icons.Rounded.ContentCopy, Modifier.weight(1f), onClick = onCopy)
            IconPill("Invite", Icons.Rounded.Share, Modifier.weight(1f), onClick = onShare)
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = PtAmber, fontSize = 13.sp)
        }
    }

    Spacer(Modifier.height(18.dp))

    // ---- one panel: now playing, people, nearby, timing ----
    Panel {
        // now playing
        Column(Modifier.padding(20.dp)) {
            SectionTitle("Now playing")
            Spacer(Modifier.height(12.dp))
            if (title == null) {
                Text(
                    "Nothing yet. Play any song and everyone will hear it.",
                    color = Color.White.copy(0.6f), fontSize = 14.sp, lineHeight = 20.sp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(AccentBlue.copy(0.55f), AccentBlue.copy(0.12f)))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist.orEmpty(), color = Color.White.copy(0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    Equalizer(playing, if (playing) PtGreen else Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (playing) "Playing" else "Paused") + (controller?.let { " · started by $it" } ?: ""),
                    color = Color.White.copy(0.5f), fontSize = 13.sp
                )
                if (streaming) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This song isn't in your library, so it is streaming online from ${controller ?: "the controller"}.",
                        color = PtGreen, fontSize = 13.sp, lineHeight = 18.sp
                    )
                } else if (songMissing) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Getting this song from ${controller ?: "the controller"}" +
                            (route?.let { " via ${it.label}" } ?: "") +
                            (if (transferPercent in 0..100) " ($transferPercent%)…" else "…"),
                        color = PtAmber, fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
                // why the last route failed and which one is tried next
                routeProblem?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = PtAmber.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }

        Hairline()

        // people
        Column(Modifier.padding(20.dp)) {
            SectionTitle("Listening", "${members.size}")
            Spacer(Modifier.height(12.dp))
            if (members.size < 2) {
                Text("Waiting for friends to join…", color = Color.White.copy(0.55f), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
            }
            members.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(m.name, 38.dp, ring = Color.Transparent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        m.name + if (m.isMe) "  (you)" else "",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (m.isController) {
                        Text(
                            "Controlling",
                            color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(AccentBlue.copy(0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Hairline()

        // same-place transfer
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Nearby phones")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Turns on Bluetooth and Wi-Fi and connects every phone in the same room, so songs are sent over Bluetooth / Wi-Fi Direct. If that fails, the controller opens its own Wi-Fi network (Android 10+), then the internet is used.",
                        color = Color.White.copy(0.55f), fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.width(14.dp))
                Switch(
                    checked = nearbyEnabled,
                    onCheckedChange = onNearbyToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue, checkedThumbColor = Color.White)
                )
            }
            if (nearbyEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (nearbyPeers > 0) "Connected to $nearbyPeers nearby " + (if (nearbyPeers == 1) "phone" else "phones")
                    else "Looking for nearby phones…",
                    color = if (nearbyPeers > 0) PtGreen else PtAmber,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
                radioProblem?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = PtAmber, fontSize = 13.sp, lineHeight = 18.sp)
                }
                route?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Songs arrive via: ${it.label}", color = Color.White.copy(0.6f), fontSize = 13.sp)
                }
            }
        }

        Hairline()

        // sound timing fine-tune
        Column(Modifier.padding(20.dp)) {
            SectionTitle("Sound timing", "this phone")
            Spacer(Modifier.height(4.dp))
            Text(
                "Phones are kept in step with the controller automatically. If this phone still sounds a little " +
                    "late next to the others (for example through a Bluetooth speaker), press + to make its sound come sooner; " +
                    "press − if it is early.",
                color = Color.White.copy(0.55f), fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = (if (syncOffsetMs > 0) "+$syncOffsetMs" else "$syncOffsetMs") + " ms",
                color = if (syncOffsetMs != 0) PtAmber else Color.White,
                fontSize = 34.sp, fontWeight = FontWeight.Black,
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncStepButton("−10", Modifier.weight(1f)) { onSyncOffset(syncOffsetMs - 10) }
                SyncStepButton("−1", Modifier.weight(1f)) { onSyncOffset(syncOffsetMs - 1) }
                SyncStepButton("+1", Modifier.weight(1f)) { onSyncOffset(syncOffsetMs + 1) }
                SyncStepButton("+10", Modifier.weight(1f)) { onSyncOffset(syncOffsetMs + 10) }
            }
            if (syncOffsetMs != 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reset to 0",
                    color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .clickable { onSyncOffset(0) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    GhostButton("Leave room", Modifier.fillMaxWidth(), danger = true, onClick = onLeave)
}

@Composable
private fun SetupCard(problem: String?, onCopyRules: () -> Unit) {
    Text("Firebase isn’t connected yet", color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 30.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        problem ?: "google-services.json was not found in the app.",
        color = PtAmber, fontSize = 14.sp, lineHeight = 20.sp
    )

    Spacer(Modifier.height(22.dp))

    Panel {
        Column(Modifier.padding(20.dp)) {
            SectionTitle("One-time setup")
            Spacer(Modifier.height(12.dp))
            // a real sequence, so the steps carry numbers
            listOf(
                "Firebase console > Build > Realtime Database > Create database.",
                "Build > Authentication > Sign-in method > enable \"Anonymous\".",
                "Realtime Database > Rules: paste the rules below and Publish.",
                "Project settings > Your apps > add this app’s SHA-1 (debug and release).",
                "Download google-services.json (after creating the database), put it in the app folder (app/google-services.json) and rebuild."
            ).forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Box(
                        Modifier.size(22.dp).background(AccentBlue.copy(0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("${i + 1}", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Text(step, color = Color.White.copy(0.78f), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                RULES_TEXT,
                color = Color(0xFF9FE8B5),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(0.40f))
                    .padding(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Copy rules", Modifier.fillMaxWidth(), onClick = onCopyRules)
        }
    }
}

/** One fixed-height pill of the sound-timing row; the label never wraps. */
@Composable
private fun SyncStepButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}
