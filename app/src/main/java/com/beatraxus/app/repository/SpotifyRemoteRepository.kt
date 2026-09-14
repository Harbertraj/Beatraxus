package com.beatraxus.app.repository

import android.content.Context
import android.content.pm.PackageManager
import com.beatraxus.app.util.Config
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SpotifyConnectionState {
    object Disconnected : SpotifyConnectionState()
    object Connecting : SpotifyConnectionState()
    object Connected : SpotifyConnectionState()
    object SpotifyNotInstalled : SpotifyConnectionState()
    object NotPremium : SpotifyConnectionState()
    data class Error(val message: String) : SpotifyConnectionState()
}

class SpotifyRemoteRepository {

    private var spotifyAppRemote: SpotifyAppRemote? = null

    private val _connectionState = MutableStateFlow<SpotifyConnectionState>(SpotifyConnectionState.Disconnected)
    val connectionState: StateFlow<SpotifyConnectionState> = _connectionState.asStateFlow()

    private val redirectUri = "beatraxus://auth"

    fun connect(context: Context) {
        if (!isSpotifyInstalled(context)) {
            _connectionState.value = SpotifyConnectionState.SpotifyNotInstalled
            return
        }

        if (spotifyAppRemote?.isConnected == true) {
            _connectionState.value = SpotifyConnectionState.Connected
            return
        }

        _connectionState.value = SpotifyConnectionState.Connecting

        val connectionParams = ConnectionParams.Builder(Config.SPOTIFY_CLIENT_ID)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                spotifyAppRemote = remote
                _connectionState.value = SpotifyConnectionState.Connected
            }

            override fun onFailure(throwable: Throwable) {
                val errorMessage = throwable.message ?: "Unknown error"
                if (errorMessage.contains("SpotifyNotInstalledException", ignoreCase = true)) {
                    _connectionState.value = SpotifyConnectionState.SpotifyNotInstalled
                } else if (errorMessage.contains("NotPremiumException", ignoreCase = true)) {
                    _connectionState.value = SpotifyConnectionState.NotPremium
                } else {
                    _connectionState.value = SpotifyConnectionState.Error(errorMessage)
                }
            }
        })
    }

    fun disconnect() {
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
            spotifyAppRemote = null
        }
        _connectionState.value = SpotifyConnectionState.Disconnected
    }

    fun playUri(uri: String) {
        spotifyAppRemote?.playerApi?.play(uri)
    }

    fun pause() {
        spotifyAppRemote?.playerApi?.pause()
    }

    fun resume() {
        spotifyAppRemote?.playerApi?.resume()
    }

    fun skipNext() {
        spotifyAppRemote?.playerApi?.skipNext()
    }

    fun skipPrevious() {
        spotifyAppRemote?.playerApi?.skipPrevious()
    }

    private fun isSpotifyInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
