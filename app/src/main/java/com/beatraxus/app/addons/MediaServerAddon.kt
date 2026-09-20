package com.beatraxus.app.addons

import android.content.Context

data class AuthToken(val token: String, val serverUrl: String)

data class AddonMediaItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val mediaType: String, // "audio" or "video"
    val durationMs: Long,
    val directPlayUrl: String
)

interface MediaServerAddon : MusicServiceAddon {
    val authType: String
    suspend fun login(serverUrl: String, username: String, password: String): Result<AuthToken>
    suspend fun browse(path: String?): List<AddonMediaItem>
    suspend fun search(query: String): List<AddonMediaItem>
    fun streamUrl(item: AddonMediaItem): String
}
