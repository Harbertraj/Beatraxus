package com.beatraxus.app.addons

import android.content.Context

data class AuthToken(val token: String, val serverUrl: String, val userId: String? = null)

data class AddonMediaItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val mediaType: String, // "audio" or "video"
    val durationMs: Long,
    val directPlayUrl: String,
    // Optional richer metadata. Add-ons that don't know a value simply leave the default.
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    /** The item's own page on the provider's website (shown as a link for attribution). */
    val pageUrl: String? = null,
    /** Link to the licence the item is published under, when the provider tells us. */
    val licenseUrl: String? = null
) {
    /** "3:45" or "1:02:03"; null when the duration is unknown. */
    val durationLabel: String?
        get() {
            if (durationMs <= 0L) return null
            val total = durationMs / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            val ss = s.toString().padStart(2, '0')
            return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
        }
}

interface MediaServerAddon : MusicServiceAddon {
    val authType: String
    suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken>
    suspend fun browse(path: String?): List<AddonMediaItem>
    suspend fun search(query: String): List<AddonMediaItem>
    suspend fun streamUrl(item: AddonMediaItem): String

    /**
     * Audio items that are really albums or collections (an Internet Archive upload, say) expand into their
     * individual tracks here so they can be played as a queue. An empty list means "play the item itself".
     */
    suspend fun tracks(item: AddonMediaItem): List<AddonMediaItem> = emptyList()
}