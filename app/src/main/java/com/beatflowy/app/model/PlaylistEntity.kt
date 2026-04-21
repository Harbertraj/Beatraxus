package com.beatflowy.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val songIds: String // Comma-separated IDs
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: String
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val songId: String,
    val timestamp: Long
)

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrate: Int,
    val fileSizeBytes: Long,
    val albumArtUriString: String?,
    val year: Int,
    val genre: String,
    val folder: String,
    val dateAdded: Long
)
