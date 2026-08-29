package com.beatraxus.app.model

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
    @PrimaryKey val songId: String,
    val accountEmail: String? = null // null for local, email for cloud
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val songId: String,
    val timestamp: Long,
    val accountEmail: String? = null // null for local, email for cloud
)

@Entity(tableName = "recently_played_videos")
data class VideoRecentlyPlayedEntity(
    @PrimaryKey val videoId: String,
    val timestamp: Long,
    val accountEmail: String? = null
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
    val albumArtist: String? = null,
    val composer: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val lyrics: String? = null,
    val folder: String,
    val dateAdded: Long,
    val replayGainTrackDb: Float?,
    val replayGainAlbumDb: Float?,
    val replayGainTrackPeak: Float?,
    val replayGainAlbumPeak: Float?,
    val source: String = "LOCAL",
    val driveFileId: String? = null,
    val driveAccountEmail: String? = null,
    val dropboxFileId: String? = null,
    val dropboxAccountEmail: String? = null,
    val onedriveFileId: String? = null,
    val onedriveAccountEmail: String? = null,
    val boxFileId: String? = null,
    val boxAccountEmail: String? = null,
    val nextcloudFileId: String? = null,
    val nextcloudAccountEmail: String? = null,
    val telegramChannelUrl: String? = null,
    val telegramChatId: Long? = null,
    val telegramMessageId: Long? = null,
    val telegramFileId: Int? = null,
    val isEnriched: Boolean = false,
    val albumArtFetchAttempted: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val lyricsOffsetMs: Long = 0L
)
