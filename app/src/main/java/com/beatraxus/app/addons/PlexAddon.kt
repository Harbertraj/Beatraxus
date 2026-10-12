package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Plex needs the plex.tv PIN/token sign-in flow, which isn't implemented yet. Until it is, this
 * add-on says so honestly instead of pretending to connect (it used to report "connected" with a
 * dummy token and then show an empty library).
 */
class PlexAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Plex Media Server (coming soon)"
    override val brandColor: Color = Color(0xFFE5A00D)
    override val icon: ImageVector = Icons.Default.VideoLibrary
    override val capability: AddonCapability = AddonCapability.CONTROL

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    override fun connect(context: Context) {}

    override fun disconnect() {
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String = "SIGN IN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> =
        Result.failure(IOException("Plex sign-in isn't supported yet — use Jellyfin, Emby or a Subsonic server for now"))

    override suspend fun browse(path: String?): List<AddonMediaItem> = emptyList()

    override suspend fun search(query: String): List<AddonMediaItem> = emptyList()

    override suspend fun streamUrl(item: AddonMediaItem): String =
        throw UnsupportedOperationException("Plex isn't supported yet")
}
