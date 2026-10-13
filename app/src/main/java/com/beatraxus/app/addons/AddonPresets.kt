package com.beatraxus.app.addons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One-tap add-ons for the protocols Beatraxus speaks natively, so users don't need to build a
 * manifest ZIP by hand. Tapping one writes the same manifest.json a ZIP install would.
 */
data class AddonPreset(
    val id: String,
    val displayName: String,
    val blurb: String,
    val mediaType: String,
    val protocol: String,
    val authType: String,
    val brandColor: Color,
    val icon: ImageVector
)

object AddonPresets {
    val all: List<AddonPreset> = listOf(
        AddonPreset(
            id = "preset_navidrome",
            displayName = "Navidrome / Subsonic",
            blurb = "Your own music server — Navidrome, Airsonic, Gonic",
            mediaType = "audio",
            protocol = "subsonic",
            authType = "server_credentials",
            brandColor = Color(0xFF38B6FF),
            icon = Icons.Default.LibraryMusic
        ),
        AddonPreset(
            id = "preset_jellyfin",
            displayName = "Jellyfin",
            blurb = "Movies, shows and music from your Jellyfin server",
            mediaType = "video",
            protocol = "jellyfin",
            authType = "server_credentials",
            brandColor = Color(0xFF00A4DC),
            icon = Icons.Default.Cloud
        ),
        AddonPreset(
            id = "preset_emby",
            displayName = "Emby",
            blurb = "Movies, shows and music from your Emby server",
            mediaType = "video",
            protocol = "emby",
            authType = "server_credentials",
            brandColor = Color(0xFF52B54B),
            icon = Icons.Default.Movie
        ),
        AddonPreset(
            id = "preset_stremio",
            displayName = "Stremio catalog",
            blurb = "Paste any Stremio add-on manifest URL (direct-link streams)",
            mediaType = "video",
            protocol = "stremio",
            authType = "manifest_url",
            brandColor = Color(0xFFFF8A00),
            icon = Icons.Default.Movie
        ),
        AddonPreset(
            id = "preset_archive",
            displayName = "Internet Archive",
            blurb = "Public-domain films, concerts and music — no sign-in",
            mediaType = "video",
            protocol = "archive_org",
            authType = "none",
            brandColor = Color(0xFF8E8E93),
            icon = Icons.Default.Archive
        )
    )
}
