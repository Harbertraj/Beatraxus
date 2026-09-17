package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.ui.graphics.Color
import com.beatraxus.app.repository.StreamingLinkResolver
import com.beatraxus.app.repository.StreamingServiceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drop-in addon for Amazon Music. Only instantiated once the user taps "Add"
 * for it in the Streaming Add-ons screen — see AddonManager.
 */
class AmazonMusicAddon : MusicServiceAddon {

    override val id = "amazon_music"
    override val displayName = "Amazon Music"
    override val description = "Open in app"
    override val brandColor = Color(0xFF00A8E1)
    override val icon = Icons.Rounded.Cloud
    override val capability = AddonCapability.LAUNCH

    private val resolver = StreamingLinkResolver()

    private val _state = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _state.asStateFlow()

    override fun connect(context: Context) {
        _state.value = if (isInstalled(context)) {
            AddonConnectionState.CONNECTED
        } else {
            AddonConnectionState.NOT_INSTALLED
        }
    }

    override fun disconnect() {
        _state.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {
        resolver.openInExternalApp(context, StreamingServiceType.AMAZON_MUSIC, query)
    }

    override fun buttonLabel(): String = when (_state.value) {
        AddonConnectionState.NOT_INSTALLED -> "Install"
        else -> "Open"
    }

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(StreamingServiceType.AMAZON_MUSIC.packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}
