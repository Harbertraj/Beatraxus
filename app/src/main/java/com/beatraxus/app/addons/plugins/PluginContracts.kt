package com.beatraxus.app.addons.plugins

import java.util.concurrent.ConcurrentHashMap

/** Stable, versioned capability names used by remote repository manifests. */
enum class PluginCategory(val wireName: String) {
    MUSIC_SOURCE("music_source"),
    METADATA("metadata"),
    LYRICS("lyrics"),
    ARTWORK("artwork"),
    PLAYLIST("playlist"),
    SCROBBLING("scrobbling"),
    VIDEO_SOURCE("video_source");

    companion object {
        fun fromWireName(value: String): PluginCategory? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

data class PluginTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val providerId: String
)

data class PluginStream(
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null
)

data class PluginLyrics(
    val plainText: String? = null,
    val syncedText: String? = null,
    val sourceUrl: String? = null
)

data class PluginArtwork(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val attributionUrl: String? = null
)

data class PluginPlaylist(
    val id: String,
    val title: String,
    val description: String? = null,
    val tracks: List<PluginTrack> = emptyList()
)

/** All provider implementations are compiled into BeatRaxus. Remote packages never supply code. */
interface BeatRaxusPlugin {
    val pluginId: String
    val displayName: String
    val version: String
    val category: PluginCategory
}

/** Runtime registry for reviewed provider implementations compiled into the application. */
object PluginRuntimeRegistry {
    private val providers = ConcurrentHashMap<String, BeatRaxusPlugin>()

    fun register(provider: BeatRaxusPlugin) {
        require(Regex("[A-Za-z0-9_]{1,64}").matches(provider.pluginId)) { "Invalid provider ID" }
        providers[provider.pluginId] = provider
    }

    fun unregister(pluginId: String) {
        providers.remove(pluginId)
    }

    fun find(pluginId: String): BeatRaxusPlugin? = providers[pluginId]

    fun providers(category: PluginCategory): List<BeatRaxusPlugin> =
        providers.values.filter { it.category == category }.sortedBy { it.displayName.lowercase() }
}

interface MusicSourcePlugin : BeatRaxusPlugin {
    suspend fun searchTracks(query: String, page: Int = 0): List<PluginTrack>
    suspend fun resolveStream(track: PluginTrack): PluginStream?
}

interface MetadataPlugin : BeatRaxusPlugin {
    suspend fun searchTracks(query: String): List<PluginTrack>
    suspend fun searchArtists(query: String): List<String>
    suspend fun searchAlbums(query: String): List<String>
}

interface LyricsPlugin : BeatRaxusPlugin {
    suspend fun fetchLyrics(track: PluginTrack): PluginLyrics?
}

interface ArtworkPlugin : BeatRaxusPlugin {
    suspend fun findArtwork(track: PluginTrack): PluginArtwork?
}

interface PlaylistPlugin : BeatRaxusPlugin {
    suspend fun searchPlaylists(query: String): List<PluginPlaylist>
    suspend fun loadPlaylist(playlistId: String): PluginPlaylist?
}

interface ScrobblePlugin : BeatRaxusPlugin {
    suspend fun onNowPlaying(track: PluginTrack)
    suspend fun onScrobble(track: PluginTrack, playedAtEpochSeconds: Long)
}

interface VideoSourcePlugin : BeatRaxusPlugin {
    suspend fun searchVideos(query: String, page: Int = 0): List<PluginVideo>
    suspend fun resolveVideo(video: PluginVideo): PluginStream?
}

data class PluginVideo(
    val id: String,
    val title: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val providerId: String
)
