package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlexAddon(
    override val id: String,
    override val displayName: String,
    override val authType: String
) : MediaServerAddon {
    override val description: String = "Plex Media Server"
    override val brandColor: Color = Color(0xFFE5A00D)
    override val icon: ImageVector = Icons.Default.VideoLibrary
    override val capability: AddonCapability = AddonCapability.CONTROL

    private val _connectionState = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _connectionState.asStateFlow()

    private var currentToken: AuthToken? = null

    override fun connect(context: Context) {
        _connectionState.value = AddonConnectionState.CONNECTED
    }

    override fun disconnect() {
        _connectionState.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {}

    override fun buttonLabel(): String = "OPEN"

    override suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken> {
        // TODO: Plex PIN/OAuth authentication flow
        val token = AuthToken("plex_dummy_token", serverUrl)
        currentToken = token
        _connectionState.value = AddonConnectionState.CONNECTED
        return Result.success(token)
    }

    override suspend fun browse(path: String?): List<AddonMediaItem> {
        return emptyList()
    }

    override suspend fun search(query: String): List<AddonMediaItem> {
        return emptyList()
    }

    override fun streamUrl(item: AddonMediaItem): String {
        return "${currentToken?.serverUrl}/library/parts/${item.id}?X-Plex-Token=${currentToken?.token}"
    }
}
