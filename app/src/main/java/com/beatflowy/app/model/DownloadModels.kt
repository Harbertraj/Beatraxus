package com.beatflowy.app.model

sealed class DownloadQuality(val label: String, val description: String, val qualityCode: Int) {
    object HiRes24Bit : DownloadQuality("Hi-Res 24-bit FLAC", "Up to 192kHz/24bit", 27)
    object Lossless : DownloadQuality("Lossless FLAC", "CD Quality 16-bit", 6)
}

enum class DownloadStatus {
    QUEUED, DOWNLOADING, DONE, FAILED
}

data class DownloadItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val quality: DownloadQuality,
    val coverUrl: String?,
    val status: DownloadStatus,
    val progressPercent: Int,
    val fileSizeBytes: Long
)

data class AlbumItem(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val tracksCount: Int
)

data class SearchResults(
    val tracks: List<DownloadItem>,
    val albums: List<AlbumItem>
)

enum class FilenameTemplate(val label: String, val example: String) {
    ARTIST_TITLE("Artist - Title", "Coldplay - Yellow.flac"),
    TITLE_ONLY("Title Only", "Yellow.flac"),
    ARTIST_ALBUM_TITLE("Artist - Album - Title", "Coldplay - A Rush of Blood - Yellow.flac")
}

data class DownloadSettings(
    val defaultQuality: DownloadQuality = DownloadQuality.HiRes24Bit,
    val filenameTemplate: FilenameTemplate = FilenameTemplate.ARTIST_TITLE,
    val createAlbumSubfolders: Boolean = true,
    val overwriteExisting: Boolean = false,
    val downloadLocation: String? = null
)
