package com.beatraxus.app.addons

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow

/**
 * Generic state every addon reports, regardless of which service it wraps.
 */
enum class AddonConnectionState {
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    NOT_INSTALLED,   // the official app for this service isn't on the device
    NOT_ELIGIBLE,    // e.g. Spotify Free (no Premium)
    ERROR
}

/**
 * What an addon can actually do once installed.
 * CONTROL  -> Beatraxus can drive playback in that service (needs an SDK/remote, e.g. Spotify App Remote)
 * LAUNCH   -> Beatraxus can only hand off to the official app (deep link / package launch)
 */
enum class AddonCapability { CONTROL, LAUNCH }

/**
 * Contract every streaming add-on file must implement.
 *
 * This file never references Spotify/YouTube Music/Apple Music/Amazon Music by name —
 * it is the generic plug the individual addon files snap into. Each concrete addon
 * (SpotifyAddon.kt, YoutubeMusicAddon.kt, ...) lives in its own file and is only wired
 * into the app when a user taps "Add" for it in AddonManager — nothing here hardcodes
 * a specific brand into the core app.
 */
interface MusicServiceAddon {
    /** Stable id used for persistence, e.g. "spotify" */
    val id: String
    val displayName: String
    val description: String
    val brandColor: Color
    val icon: ImageVector
    val capability: AddonCapability

    val connectionState: StateFlow<AddonConnectionState>

    /** Called once when the user adds/enables this addon. Safe to call multiple times. */
    fun connect(context: Context)

    /** Called when the user removes/disables this addon. */
    fun disconnect()

    /**
     * Ask the addon to play/search for something. For CONTROL addons this may play
     * directly; for LAUNCH addons this opens the official app (search intent if possible).
     */
    fun play(context: Context, query: String? = null)

    /** Label shown on the action button for the current state. */
    fun buttonLabel(): String
}
