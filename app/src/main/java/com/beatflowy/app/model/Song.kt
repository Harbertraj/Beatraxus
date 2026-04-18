package com.beatflowy.app.model

import android.net.Uri

data class Song(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val sampleRateHz: Int,
    val bitDepth: Int = 16,
    val bitrate: Int = 0,
    val fileSizeBytes: Long = 0L,
    val albumArtUri: Uri? = null,
    val year: Int = 0,
    val genre: String = "Unknown",
    val folder: String = "",
    val dateAdded: Long = 0,
    val isFavorite: Boolean = false
)

enum class AudioOutputDevice(val displayName: String) {
    SPEAKER("Speaker"),
    WIRED("Headphones"),
    BLUETOOTH("Bluetooth"),
    USB_DAC("USB DAC"),
    UNKNOWN("Unknown")
}

enum class LibraryView {
    ALL_SONGS, ALBUMS, ARTISTS, FOLDERS, YEARS, GENRES, FAVORITES, RECENTLY_PLAYED, RECENTLY_ADDED,
    ALBUM_DETAIL, ARTIST_DETAIL, FOLDER_DETAIL, YEAR_DETAIL, GENRE_DETAIL
}

enum class SortType {
    NAME, DATE_ADDED, FILE_SIZE, DURATION
}

enum class ViewMode {
    LIST, GRID_2, GRID_3, GRID_4
}

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val isBuffering: Boolean = false,
    val equalizerEnabled: Boolean = true,
    val inputSampleRate: Int = 44_100,
    val outputSampleRate: Int = 44_100,
    val outputDevice: String = AudioOutputDevice.SPEAKER.displayName,
    val eqGains: FloatArray = FloatArray(10) { 0f },
    val isLoadingLibrary: Boolean = false,
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scanCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
    val shuffleMode: Boolean = false,
    val repeatMode: Int = 0, // 0: Off, 1: One, 2: All
    val currentView: LibraryView = LibraryView.ALL_SONGS,
    val selectedItemName: String? = null, // For Album name, Artist name etc.
    val showFullPlayer: Boolean = false,
    val showLyrics: Boolean = false,
    val searchQuery: String = "",
    val isMultiSelectMode: Boolean = false,
    val selectedSongIds: Set<String> = emptySet(),
    val sortType: SortType = SortType.NAME,
    val isAscending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val isSearchActive: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerUiState) return false
        return currentSong == other.currentSong &&
                isPlaying == other.isPlaying &&
                progressMs == other.progressMs &&
                equalizerEnabled == other.equalizerEnabled &&
                inputSampleRate == other.inputSampleRate &&
                outputSampleRate == other.outputSampleRate &&
                outputDevice == other.outputDevice &&
                eqGains.contentEquals(other.eqGains) &&
                isLoadingLibrary == other.isLoadingLibrary &&
                isScanning == other.isScanning &&
                scanProgress == other.scanProgress &&
                scanCount == other.scanCount &&
                shuffleMode == other.shuffleMode &&
                repeatMode == other.repeatMode &&
                currentView == other.currentView &&
                selectedItemName == other.selectedItemName &&
                showFullPlayer == other.showFullPlayer &&
                showLyrics == other.showLyrics &&
                searchQuery == other.searchQuery &&
                isMultiSelectMode == other.isMultiSelectMode &&
                selectedSongIds == other.selectedSongIds
    }
    override fun hashCode(): Int {
        var result = currentSong?.hashCode() ?: 0
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + eqGains.contentHashCode()
        result = 31 * result + shuffleMode.hashCode()
        result = 31 * result + repeatMode.hashCode()
        result = 31 * result + isScanning.hashCode()
        result = 31 * result + scanProgress.hashCode()
        result = 31 * result + scanCount.hashCode()
        result = 31 * result + currentView.hashCode()
        result = 31 * result + (selectedItemName?.hashCode() ?: 0)
        result = 31 * result + showFullPlayer.hashCode()
        result = 31 * result + showLyrics.hashCode()
        result = 31 * result + searchQuery.hashCode()
        result = 31 * result + isMultiSelectMode.hashCode()
        result = 31 * result + selectedSongIds.hashCode()
        return result
    }
}
