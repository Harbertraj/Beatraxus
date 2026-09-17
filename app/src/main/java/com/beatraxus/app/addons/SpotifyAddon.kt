package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.ui.graphics.Color
import com.beatraxus.app.repository.SpotifyConnectionState
import com.beatraxus.app.repository.SpotifyRemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drop-in addon for Spotify. Only instantiated/connected once the user taps
 * "Add" for Spotify in the Streaming Add-ons screen — see AddonManager.
 */
class SpotifyAddon : MusicServiceAddon {

    override val id = "spotify"
    override val displayName = "Spotify"
    override val description = "Control playback"
    override val brandColor = Color(0xFF1DB954)
    override val icon = Icons.Rounded.MusicNote
    override val capability = AddonCapability.CONTROL

    private val repository = SpotifyRemoteRepository()
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _state = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _state.asStateFlow()

    init {
        scope.launch {
            repository.connectionState.collect { state ->
                _state.value = when (state) {
                    is SpotifyConnectionState.Disconnected -> AddonConnectionState.NOT_CONNECTED
                    is SpotifyConnectionState.Connecting -> AddonConnectionState.CONNECTING
                    is SpotifyConnectionState.Connected -> AddonConnectionState.CONNECTED
                    is SpotifyConnectionState.SpotifyNotInstalled -> AddonConnectionState.NOT_INSTALLED
                    is SpotifyConnectionState.NotPremium -> AddonConnectionState.NOT_ELIGIBLE
                    is SpotifyConnectionState.Error -> AddonConnectionState.ERROR
                }
            }
        }
    }

    override fun connect(context: Context) {
        if (_state.value == AddonConnectionState.CONNECTED) return
        repository.connect(context)
    }

    override fun disconnect() {
        repository.disconnect()
    }

    override fun play(context: Context, query: String?) {
        if (_state.value != AddonConnectionState.CONNECTED) {
            connect(context)
            return
        }
        // query here is expected to be a Spotify URI (spotify:track:...) when caller has one;
        // fall back to a no-op if not, since App Remote doesn't do free-text search.
        query?.let { repository.playUri(it) }
    }

    override fun buttonLabel(): String = when (_state.value) {
        AddonConnectionState.NOT_CONNECTED -> "Connect"
        AddonConnectionState.CONNECTING -> "Connecting…"
        AddonConnectionState.CONNECTED -> "Connected"
        AddonConnectionState.NOT_INSTALLED -> "Install Spotify"
        AddonConnectionState.NOT_ELIGIBLE -> "Not Premium"
        AddonConnectionState.ERROR -> "Error"
    }
}
