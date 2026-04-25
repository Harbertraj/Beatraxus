package com.beatflowy.app.model

import android.net.Uri
import com.beatflowy.app.engine.OutputMode
import com.beatflowy.app.repository.LyricsSource

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

data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList()
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
    ALBUM_DETAIL, ARTIST_DETAIL, FOLDER_DETAIL, YEAR_DETAIL, GENRE_DETAIL, PLAYLISTS, PLAYLIST_DETAIL
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
    val inputSampleRate: Int = 44_100,
    val outputSampleRate: Int = 44_100,
    val outputDevice: String = AudioOutputDevice.SPEAKER.displayName,
    val outputMode: String = OutputMode.STANDARD_AUDIO_TRACK.name,
    val hiResDirectSupported: Boolean = false,
    val hiResCapabilitySummary: String = "Direct hi-res not available on this route",
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
    val showQueue: Boolean = false,
    val upcomingSongs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val isMultiSelectMode: Boolean = false,
    val selectedSongIds: Set<String> = emptySet(),
    val sortType: SortType = SortType.NAME,
    val isAscending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val isSearchActive: Boolean = false,
    val bitDepth: Int = 16,
    val bitrate: Int = 0,
    val format: String = "",
    val pipelineOutputPath: String = "AudioTrack",
    val pipelineDvcEnabled: Boolean = false,
    val pipelineResamplerEnabled: Boolean = false,
    val pipelineActiveEffects: List<String> = emptyList(),
    val autoEqProfileName: String? = null,
    val dsp: DspUiState = DspUiState(),
    val resamplingEnabled: Boolean = true,
    val currentFolderPath: String? = null,
    val isFirstRun: Boolean = true,
    val previousView: LibraryView? = null,
    val wasSearchingBeforeDetail: Boolean = false,
    val useOriginalQualityArt: Boolean = false,
    val showLyrics: Boolean = false,
    val lyrics: List<LrcLine> = emptyList(),
    val lyricsCurrentIndex: Int = -1,
    val lyricsOffsetMs: Long = 0L,
    val isLoadingLyrics: Boolean = false,
    val lyricsCurrentSongId: String? = null,
    val lyricsSource: LyricsSource? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerUiState) return false
        return currentSong == other.currentSong &&
                isPlaying == other.isPlaying &&
                progressMs == other.progressMs &&
                isBuffering == other.isBuffering &&
                inputSampleRate == other.inputSampleRate &&
                outputSampleRate == other.outputSampleRate &&
                outputDevice == other.outputDevice &&
                outputMode == other.outputMode &&
                hiResDirectSupported == other.hiResDirectSupported &&
                hiResCapabilitySummary == other.hiResCapabilitySummary &&
                isLoadingLibrary == other.isLoadingLibrary &&
                isScanning == other.isScanning &&
                scanProgress == other.scanProgress &&
                scanCount == other.scanCount &&
                albumCount == other.albumCount &&
                artistCount == other.artistCount &&
                permissionDenied == other.permissionDenied &&
                errorMessage == other.errorMessage &&
                shuffleMode == other.shuffleMode &&
                repeatMode == other.repeatMode &&
                currentView == other.currentView &&
                selectedItemName == other.selectedItemName &&
                showFullPlayer == other.showFullPlayer &&
                showQueue == other.showQueue &&
                upcomingSongs == other.upcomingSongs &&
                searchQuery == other.searchQuery &&
                isMultiSelectMode == other.isMultiSelectMode &&
                selectedSongIds == other.selectedSongIds &&
                sortType == other.sortType &&
                isAscending == other.isAscending &&
                viewMode == other.viewMode &&
                isSearchActive == other.isSearchActive &&
                bitDepth == other.bitDepth &&
                bitrate == other.bitrate &&
                format == other.format &&
                pipelineOutputPath == other.pipelineOutputPath &&
                pipelineDvcEnabled == other.pipelineDvcEnabled &&
                pipelineResamplerEnabled == other.pipelineResamplerEnabled &&
                pipelineActiveEffects == other.pipelineActiveEffects &&
                autoEqProfileName == other.autoEqProfileName &&
                dsp == other.dsp &&
                resamplingEnabled == other.resamplingEnabled &&
                currentFolderPath == other.currentFolderPath &&
                isFirstRun == other.isFirstRun &&
                previousView == other.previousView &&
                wasSearchingBeforeDetail == other.wasSearchingBeforeDetail &&
                useOriginalQualityArt == other.useOriginalQualityArt &&
                showLyrics == other.showLyrics &&
                lyrics == other.lyrics &&
                lyricsCurrentIndex == other.lyricsCurrentIndex &&
                lyricsOffsetMs == other.lyricsOffsetMs &&
                isLoadingLyrics == other.isLoadingLyrics &&
                lyricsCurrentSongId == other.lyricsCurrentSongId &&
                lyricsSource == other.lyricsSource
    }

    override fun hashCode(): Int {
        var result = currentSong?.hashCode() ?: 0
        // ... (truncated for brevity, ensure all fields are included in implementation)
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + progressMs.hashCode()
        result = 31 * result + isBuffering.hashCode()
        result = 31 * result + inputSampleRate
        result = 31 * result + outputSampleRate
        result = 31 * result + outputDevice.hashCode()
        result = 31 * result + outputMode.hashCode()
        result = 31 * result + hiResDirectSupported.hashCode()
        result = 31 * result + hiResCapabilitySummary.hashCode()
        result = 31 * result + isLoadingLibrary.hashCode()
        result = 31 * result + isScanning.hashCode()
        result = 31 * result + scanProgress.hashCode()
        result = 31 * result + scanCount
        result = 31 * result + albumCount
        result = 31 * result + artistCount
        result = 31 * result + permissionDenied.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + shuffleMode.hashCode()
        result = 31 * result + repeatMode
        result = 31 * result + currentView.hashCode()
        result = 31 * result + (selectedItemName?.hashCode() ?: 0)
        result = 31 * result + showFullPlayer.hashCode()
        result = 31 * result + showQueue.hashCode()
        result = 31 * result + upcomingSongs.hashCode()
        result = 31 * result + searchQuery.hashCode()
        result = 31 * result + isMultiSelectMode.hashCode()
        result = 31 * result + selectedSongIds.hashCode()
        result = 31 * result + sortType.hashCode()
        result = 31 * result + isAscending.hashCode()
        result = 31 * result + viewMode.hashCode()
        result = 31 * result + isSearchActive.hashCode()
        result = 31 * result + bitDepth
        result = 31 * result + bitrate
        result = 31 * result + format.hashCode()
        result = 31 * result + pipelineOutputPath.hashCode()
        result = 31 * result + pipelineDvcEnabled.hashCode()
        result = 31 * result + pipelineResamplerEnabled.hashCode()
        result = 31 * result + pipelineActiveEffects.hashCode()
        result = 31 * result + (autoEqProfileName?.hashCode() ?: 0)
        result = 31 * result + dsp.hashCode()
        result = 31 * result + resamplingEnabled.hashCode()
        result = 31 * result + (currentFolderPath?.hashCode() ?: 0)
        result = 31 * result + isFirstRun.hashCode()
        result = 31 * result + (previousView?.hashCode() ?: 0)
        result = 31 * result + wasSearchingBeforeDetail.hashCode()
        result = 31 * result + useOriginalQualityArt.hashCode()
        result = 31 * result + showLyrics.hashCode()
        result = 31 * result + lyrics.hashCode()
        result = 31 * result + lyricsCurrentIndex
        result = 31 * result + lyricsOffsetMs.hashCode()
        result = 31 * result + isLoadingLyrics.hashCode()
        result = 31 * result + (lyricsCurrentSongId?.hashCode() ?: 0)
        result = 31 * result + (lyricsSource?.hashCode() ?: 0)
        return result
    }
}
