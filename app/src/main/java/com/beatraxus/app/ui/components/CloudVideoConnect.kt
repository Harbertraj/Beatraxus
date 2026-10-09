package com.beatraxus.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.LibraryMode
import com.beatraxus.app.model.PlayerUiState

/**
 * One-shot hand-off to SettingsScreen so "Connect" buttons elsewhere in the app can open
 * Settings directly on the right cloud page (e.g. ["Cloud"] or ["Cloud", "Telegram Channels"]).
 */
object SettingsDeepLink {
    @Volatile private var pending: List<String>? = null

    fun request(path: List<String>) { pending = path }

    /** Returns the requested section path once, then clears it. */
    fun consume(): List<String>? = pending.also { pending = null }
}

enum class CloudVideoProvider(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val settingsPath: List<String>
) {
    TELEGRAM("Telegram", Icons.AutoMirrored.Rounded.Send, Color(0xFF2AABEE), listOf("Cloud", "Telegram Channels")),
    GDRIVE("Google Drive", Icons.Rounded.Cloud, Color(0xFF34A853), listOf("Cloud")),
    ONEDRIVE("OneDrive", Icons.Rounded.CloudCircle, Color(0xFF00A1F1), listOf("Cloud")),
    DROPBOX("Dropbox", Icons.Rounded.CloudQueue, Color(0xFF0061FF), listOf("Cloud")),
    BOX("Box", Icons.Rounded.Cloud, Color(0xFF0075C9), listOf("Cloud")),
    NEXTCLOUD("Nextcloud", Icons.Rounded.Dns, Color(0xFF0082C9), listOf("Cloud"))
}

private fun connectedCount(state: PlayerUiState, p: CloudVideoProvider): Int = when (p) {
    CloudVideoProvider.TELEGRAM -> state.telegramChannels.size
    CloudVideoProvider.GDRIVE -> state.driveAccounts.size
    CloudVideoProvider.ONEDRIVE -> state.onedriveAccounts.size
    CloudVideoProvider.DROPBOX -> state.dropboxAccounts.size
    CloudVideoProvider.BOX -> state.boxAccounts.size
    CloudVideoProvider.NEXTCLOUD -> state.nextcloudAccounts.size
}

/**
 * Video-mode wrapper. LOCAL: untouched. COMBINED: compact connect strip above the local list.
 * CLOUD: no cloud videos exist yet, so show the full "connect cloud videos" panel instead of the local list.
 */
@Composable
fun VideoCloudGate(
    libraryMode: LibraryMode,
    uiState: PlayerUiState,
    onConnect: (CloudVideoProvider) -> Unit,
    content: @Composable () -> Unit
) {
    when (libraryMode) {
        LibraryMode.CLOUD -> Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            CloudVideoConnectCard(uiState, onConnect)
        }
        LibraryMode.COMBINED -> Column(Modifier.fillMaxSize()) {
            CloudVideoConnectBanner(uiState, onConnect)
            Box(Modifier.weight(1f)) { content() }
        }
        else -> content()
    }
}

/** Full panel, same glass/gradient language as the home cards. */
@Composable
fun CloudVideoConnectCard(
    uiState: PlayerUiState,
    onConnect: (CloudVideoProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(26.dp)
    val accent = Color(0xFF00F2FF)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(Color(0xFF0A0A12))
            .background(Brush.linearGradient(listOf(Color(0xFF3D8BFF).copy(0.28f), Color(0xFF8E6BFF).copy(0.12f), Color.Transparent)))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.22f), Color.White.copy(0.04f)))), shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp)
                    .background(accent.copy(0.16f), CircleShape)
                    .border(1.dp, accent.copy(0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.VideoLibrary, null, tint = accent, modifier = Modifier.size(15.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("CLOUD VIDEOS", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Connect your cloud", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Link Telegram or a cloud drive to bring your videos into the library.",
            color = Color.White.copy(0.6f), fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))

        CloudVideoProvider.values().toList().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { p ->
                    ProviderTile(p, connectedCount(uiState, p), Modifier.weight(1f)) { onConnect(p) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ProviderTile(
    provider: CloudVideoProvider,
    connected: Int,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val c = provider.color
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.copy(0.26f), c.copy(0.06f))))
            .border(0.5.dp, c.copy(0.35f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp)
                .background(c.copy(0.22f), CircleShape)
                .border(0.5.dp, c.copy(0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(provider.icon, null, tint = c, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(provider.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (connected > 0) "Connected · $connected" else "Connect",
                color = if (connected > 0) Color(0xFF4CAF50) else Color.White.copy(0.55f),
                fontSize = 11.sp, maxLines = 1
            )
        }
        Icon(
            if (connected > 0) Icons.Rounded.Check else Icons.Rounded.Add,
            null,
            tint = if (connected > 0) Color(0xFF4CAF50) else Color.White.copy(0.7f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Compact strip for COMBINED mode: provider chips in a horizontal row. */
@Composable
fun CloudVideoConnectBanner(
    uiState: PlayerUiState,
    onConnect: (CloudVideoProvider) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
        Text(
            "CONNECT CLOUD VIDEOS",
            color = Color(0xFF00F2FF), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CloudVideoProvider.values().forEach { p ->
                val n = connectedCount(uiState, p)
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .clip(shape)
                        .background(p.color.copy(0.12f))
                        .border(0.5.dp, p.color.copy(0.4f), shape)
                        .clickable(role = Role.Button) { onConnect(p) }
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(p.icon, null, tint = p.color, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (n > 0) "${p.label} · $n" else p.label,
                        color = Color.White.copy(0.88f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
        }
    }
}
