package com.beatflowy.app.model

import android.net.Uri
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.repository.LyricsSource
import com.beatflowy.app.telegram.AuthState

enum class SongSource { LOCAL, GDRIVE, WEB, TELEGRAM }

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
    val albumArtist: String? = null,
    val composer: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val lyrics: String? = null,
    val folder: String = "",
    val dateAdded: Long = 0,
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumPeak: Float? = null,
    val isFavorite: Boolean = false,
    val source: SongSource = SongSource.LOCAL,
    val driveFileId: String? = null,
    val driveAccountEmail: String? = null,
    val telegramChannelUrl: String? = null,
    val telegramChatId: Long? = null,
    val telegramMessageId: Long? = null,
    val telegramFileId: Int? = null,
    val isEnriched: Boolean = false,
    val lastSyncTimestamp: Long = 0L
) {
    fun isCloud(): Boolean = source == SongSource.GDRIVE || 
                            source == SongSource.TELEGRAM || 
                            source == SongSource.WEB
}

fun Song.toEntity() = SongEntity(
    id = id,
    uriString = uri.toString(),
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    format = format,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    bitrate = bitrate,
    fileSizeBytes = fileSizeBytes,
    albumArtUriString = albumArtUri?.toString(),
    year = year,
    genre = genre,
    albumArtist = albumArtist,
    composer = composer,
    trackNumber = trackNumber,
    discNumber = discNumber,
    lyrics = lyrics,
    folder = folder,
    dateAdded = dateAdded,
    replayGainTrackDb = replayGainTrackDb,
    replayGainAlbumDb = replayGainAlbumDb,
    replayGainTrackPeak = replayGainTrackPeak,
    replayGainAlbumPeak = replayGainAlbumPeak,
    source = source.name,
    driveFileId = driveFileId,
    driveAccountEmail = driveAccountEmail,
    telegramChannelUrl = telegramChannelUrl,
    telegramChatId = telegramChatId,
    telegramMessageId = telegramMessageId,
    telegramFileId = telegramFileId,
    isEnriched = isEnriched,
    lastSyncTimestamp = lastSyncTimestamp
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
    HOME, ALL_SONGS, ALBUMS, ARTISTS, FOLDERS, YEARS, GENRES, FAVORITES, RECENTLY_PLAYED, RECENTLY_ADDED,
    ALBUM_DETAIL, ARTIST_DETAIL, FOLDER_DETAIL, YEAR_DETAIL, GENRE_DETAIL, PLAYLISTS, PLAYLIST_DETAIL,
    CLOUD
}

enum class SortType {
    NAME, DATE_ADDED, FILE_SIZE, DURATION
}

enum class ViewMode {
    LIST, GRID_2, GRID_3, GRID_4
}

enum class LibraryMode {
    LOCAL, CLOUD, COMBINED
}

enum class NetworkType {
    WIFI_ONLY, WIFI_MOBILE, MOBILE_ONLY, ASK_MOBILE
}

enum class SyncQuality {
    LOW, MEDIUM, HIGH
}

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val isBuffering: Boolean = false,
    val inputSampleRate: Int = 44_100,
    val outputSampleRate: Int = 44_100,
    val outputBitDepth: Int = 16,
    val outputDevice: String = AudioOutputDevice.SPEAKER.displayName,
    val outputMode: String = OutputMode.HI_RES.name,
    val hiResDirectSupported: Boolean = false,
    val hiResCapabilitySummary: String = "Direct hi-res not available on this route",
    val usbExclusiveActive: Boolean = false,
    val usbDeviceName: String = "",
    val isLoadingLibrary: Boolean = false,
    val isScanning: Boolean = false,
    val isFullScanning: Boolean = false,
    val isCloudScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scanCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
    val shuffleMode: Boolean = false,
    val repeatMode: Int = 0, // 0: Off, 1: One, 2: All
    val currentView: LibraryView = LibraryView.HOME,
    val selectedItemName: String? = null, // For Album name, Artist name etc.
    val showFullPlayer: Boolean = false,
    val showQueue: Boolean = false,
    val previousSongs: List<Song> = emptyList(),
    val upcomingSongs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val authRecoveryIntent: android.content.Intent? = null,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
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
    val pipelineResamplerType: String = "SW",
    val pipelineActiveEffects: List<String> = emptyList(),
    val isOnline: Boolean = true,
    val pipelineSummary: String = "",
    val autoEqProfileName: String? = null,
    val dsp: DspUiState = DspUiState(),
    val resamplingEnabled: Boolean = true,
    val currentFolderPath: String? = null,
    val isFirstRun: Boolean = true,
    val previousView: LibraryView? = null,
    val wasSearchingBeforeDetail: Boolean = false,
    val useOriginalQualityArt: Boolean = false,
    val showLyrics: Boolean = false,
    val cameFromNowPlaying: Boolean = false,
    
    // Online Metadata (Last.fm etc)
    val lastFmTrackInfo: com.beatflowy.app.repository.lastfm.LastFmTrack? = null,
    val lastFmArtistInfo: com.beatflowy.app.repository.lastfm.LastFmArtistDetail? = null,
    val lastFmAlbumInfo: com.beatflowy.app.repository.lastfm.LastFmAlbum? = null,
    val isLoadingOnlineInfo: Boolean = false,
    
    // Online Metadata for selected song (in lists/popups)
    val selectedLastFmTrackInfo: com.beatflowy.app.repository.lastfm.LastFmTrack? = null,
    val selectedLastFmArtistInfo: com.beatflowy.app.repository.lastfm.LastFmArtistDetail? = null,
    val selectedLastFmAlbumInfo: com.beatflowy.app.repository.lastfm.LastFmAlbum? = null,
    val isSelectedLoadingOnlineInfo: Boolean = false,

    val lyrics: List<LrcLine> = emptyList(),
    val lyricsCurrentIndex: Int = -1,
    val lyricsOffsetMs: Long = 0L,
    val isLoadingLyrics: Boolean = false,
    val lyricsCurrentSongId: String? = null,
    val lyricsSource: LyricsSource? = null,
    val sleepTimerRemainingSeconds: Int = 0,
    val isSleepTimerActive: Boolean = false,
    val sleepTimerFinishTrack: Boolean = false,
    val sleepTimerPlayCount: Int = 0, // 0 means play count timer inactive
    val sleepTimerRemainingPlayCount: Int = 0,
    val musicFolders: List<String> = emptyList(),
    val blockedFolders: List<String> = emptyList(),
    val triggerFolderPicker: Boolean = false,
    val showScanOptions: Boolean = false,
    val showVolumeOverlay: Boolean = false,
    val settingsIconX: Float = 0f,
    val settingsIconY: Float = 0f,
    val selectedCloudEmail: String? = null,
    val selectedTelegramChannelUrl: String? = null,
    val libraryMode: LibraryMode = LibraryMode.LOCAL,
    // Cloud
    val driveAccounts: List<com.beatflowy.app.repository.DriveAccount> = emptyList(),
    val telegramChannels: List<TelegramChannel> = emptyList(),
    // Last.fm
    val lastFmUsername: String? = null,
    val scrobblingEnabled: Boolean = true,
    // Sync Settings
    val metadataNetworkType: NetworkType = NetworkType.WIFI_ONLY,
    val dataSaverEnabled: Boolean = false,
    val artworkEnrichmentEnabled: Boolean = true,
    val syncQuality: SyncQuality = SyncQuality.MEDIUM,
    val backgroundSyncEnabled: Boolean = true,
    val isEnrichmentPaused: Boolean = false,
    val enrichmentStatus: String? = null,
    val telegramAuthState: AuthState = AuthState.LoggedOut,
    val isSubmittingTelegram: Boolean = false,
    val telegramAuthError: String? = null,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val isOemBatteryManagerDetected: Boolean = false
)
{
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerUiState) return false
        return currentSong == other.currentSong &&
                isPlaying == other.isPlaying &&
                progressMs == other.progressMs &&
                isBuffering == other.isBuffering &&
                inputSampleRate == other.inputSampleRate &&
                outputSampleRate == other.outputSampleRate &&
                outputBitDepth == other.outputBitDepth &&
                outputDevice == other.outputDevice &&
                outputMode == other.outputMode &&
                hiResDirectSupported == other.hiResDirectSupported &&
                hiResCapabilitySummary == other.hiResCapabilitySummary &&
                usbExclusiveActive == other.usbExclusiveActive &&
                usbDeviceName == other.usbDeviceName &&
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
                selectedIds == other.selectedIds &&
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
                pipelineResamplerType == other.pipelineResamplerType &&
                pipelineActiveEffects == other.pipelineActiveEffects &&
                pipelineSummary == other.pipelineSummary &&
                autoEqProfileName == other.autoEqProfileName &&
                dsp == other.dsp &&
                resamplingEnabled == other.resamplingEnabled &&
                currentFolderPath == other.currentFolderPath &&
                isFirstRun == other.isFirstRun &&
                previousView == other.previousView &&
                wasSearchingBeforeDetail == other.wasSearchingBeforeDetail &&
                useOriginalQualityArt == other.useOriginalQualityArt &&
                showLyrics == other.showLyrics &&
                cameFromNowPlaying == other.cameFromNowPlaying &&
                lastFmTrackInfo == other.lastFmTrackInfo &&
                lastFmArtistInfo == other.lastFmArtistInfo &&
                lastFmAlbumInfo == other.lastFmAlbumInfo &&
                isLoadingOnlineInfo == other.isLoadingOnlineInfo &&
                selectedLastFmTrackInfo == other.selectedLastFmTrackInfo &&
                selectedLastFmArtistInfo == other.selectedLastFmArtistInfo &&
                selectedLastFmAlbumInfo == other.selectedLastFmAlbumInfo &&
                isSelectedLoadingOnlineInfo == other.isSelectedLoadingOnlineInfo &&
                lyrics == other.lyrics &&
                lyricsCurrentIndex == other.lyricsCurrentIndex &&
                lyricsOffsetMs == other.lyricsOffsetMs &&
                isLoadingLyrics == other.isLoadingLyrics &&
                lyricsCurrentSongId == other.lyricsCurrentSongId &&
                lyricsSource == other.lyricsSource &&
                sleepTimerRemainingSeconds == other.sleepTimerRemainingSeconds &&
                isSleepTimerActive == other.isSleepTimerActive &&
                sleepTimerFinishTrack == other.sleepTimerFinishTrack &&
                sleepTimerPlayCount == other.sleepTimerPlayCount &&
                sleepTimerRemainingPlayCount == other.sleepTimerRemainingPlayCount &&
                musicFolders == other.musicFolders &&
                blockedFolders == other.blockedFolders &&
                triggerFolderPicker == other.triggerFolderPicker &&
                showScanOptions == other.showScanOptions &&
                selectedCloudEmail == other.selectedCloudEmail &&
                selectedTelegramChannelUrl == other.selectedTelegramChannelUrl &&
                libraryMode == other.libraryMode &&
                driveAccounts == other.driveAccounts &&
                telegramChannels == other.telegramChannels &&
                lastFmUsername == other.lastFmUsername &&
                scrobblingEnabled == other.scrobblingEnabled &&
                metadataNetworkType == other.metadataNetworkType &&
                dataSaverEnabled == other.dataSaverEnabled &&
                artworkEnrichmentEnabled == other.artworkEnrichmentEnabled &&
                syncQuality == other.syncQuality &&
                backgroundSyncEnabled == other.backgroundSyncEnabled &&
                isEnrichmentPaused == other.isEnrichmentPaused &&
                enrichmentStatus == other.enrichmentStatus &&
                telegramAuthState == other.telegramAuthState &&
                isSubmittingTelegram == other.isSubmittingTelegram &&
                telegramAuthError == other.telegramAuthError &&
                isIgnoringBatteryOptimizations == other.isIgnoringBatteryOptimizations &&
                isOemBatteryManagerDetected == other.isOemBatteryManagerDetected
    }

    override fun hashCode(): Int {
        var result = currentSong?.hashCode() ?: 0
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + progressMs.hashCode()
        result = 31 * result + isBuffering.hashCode()
        result = 31 * result + inputSampleRate
        result = 31 * result + outputSampleRate
        result = 31 * result + outputBitDepth
        result = 31 * result + outputDevice.hashCode()
        result = 31 * result + outputMode.hashCode()
        result = 31 * result + hiResDirectSupported.hashCode()
        result = 31 * result + hiResCapabilitySummary.hashCode()
        result = 31 * result + usbExclusiveActive.hashCode()
        result = 31 * result + usbDeviceName.hashCode()
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
        result = 31 * result + selectedIds.hashCode()
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
        result = 31 * result + pipelineResamplerType.hashCode()
        result = 31 * result + pipelineActiveEffects.hashCode()
        result = 31 * result + pipelineSummary.hashCode()
        result = 31 * result + (autoEqProfileName?.hashCode() ?: 0)
        result = 31 * result + dsp.hashCode()
        result = 31 * result + resamplingEnabled.hashCode()
        result = 31 * result + (currentFolderPath?.hashCode() ?: 0)
        result = 31 * result + isFirstRun.hashCode()
        result = 31 * result + (previousView?.hashCode() ?: 0)
        result = 31 * result + wasSearchingBeforeDetail.hashCode()
        result = 31 * result + useOriginalQualityArt.hashCode()
        result = 31 * result + showLyrics.hashCode()
        result = 31 * result + cameFromNowPlaying.hashCode()
        result = 31 * result + (lastFmTrackInfo?.hashCode() ?: 0)
        result = 31 * result + (lastFmArtistInfo?.hashCode() ?: 0)
        result = 31 * result + (lastFmAlbumInfo?.hashCode() ?: 0)
        result = 31 * result + isLoadingOnlineInfo.hashCode()
        result = 31 * result + (selectedLastFmTrackInfo?.hashCode() ?: 0)
        result = 31 * result + (selectedLastFmArtistInfo?.hashCode() ?: 0)
        result = 31 * result + (selectedLastFmAlbumInfo?.hashCode() ?: 0)
        result = 31 * result + isSelectedLoadingOnlineInfo.hashCode()
        result = 31 * result + lyrics.hashCode()
        result = 31 * result + lyricsCurrentIndex
        result = 31 * result + lyricsOffsetMs.hashCode()
        result = 31 * result + isLoadingLyrics.hashCode()
        result = 31 * result + (lyricsCurrentSongId?.hashCode() ?: 0)
        result = 31 * result + (lyricsSource?.hashCode() ?: 0)
        result = 31 * result + sleepTimerRemainingSeconds
        result = 31 * result + isSleepTimerActive.hashCode()
        result = 31 * result + sleepTimerFinishTrack.hashCode()
        result = 31 * result + sleepTimerPlayCount
        result = 31 * result + sleepTimerRemainingPlayCount
        result = 31 * result + musicFolders.hashCode()
        result = 31 * result + blockedFolders.hashCode()
        result = 31 * result + triggerFolderPicker.hashCode()
        result = 31 * result + showScanOptions.hashCode()
        result = 31 * result + (selectedCloudEmail?.hashCode() ?: 0)
        result = 31 * result + (selectedTelegramChannelUrl?.hashCode() ?: 0)
        result = 31 * result + libraryMode.hashCode()
        result = 31 * result + driveAccounts.hashCode()
        result = 31 * result + telegramChannels.hashCode()
        result = 31 * result + (lastFmUsername?.hashCode() ?: 0)
        result = 31 * result + scrobblingEnabled.hashCode()
        result = 31 * result + metadataNetworkType.hashCode()
        result = 31 * result + dataSaverEnabled.hashCode()
        result = 31 * result + artworkEnrichmentEnabled.hashCode()
        result = 31 * result + syncQuality.hashCode()
        result = 31 * result + backgroundSyncEnabled.hashCode()
        result = 31 * result + isEnrichmentPaused.hashCode()
        result = 31 * result + (enrichmentStatus?.hashCode() ?: 0)
        result = 31 * result + telegramAuthState.hashCode()
        result = 31 * result + isSubmittingTelegram.hashCode()
        result = 31 * result + (telegramAuthError?.hashCode() ?: 0)
        result = 31 * result + isIgnoringBatteryOptimizations.hashCode()
        result = 31 * result + isOemBatteryManagerDetected.hashCode()
        return result
    }
}
