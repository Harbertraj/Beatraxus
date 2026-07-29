package com.beatraxus.app.model

import android.net.Uri
import com.beatraxus.app.model.OutputMode
import com.beatraxus.app.repository.LyricsSource
import com.beatraxus.app.telegram.AuthState
import com.beatraxus.app.utils.DeviceUtils

enum class SongSource { LOCAL, GDRIVE, WEB, TELEGRAM, DROPBOX, ONEDRIVE, BOX, NEXTCLOUD, SMB, FTP }

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
) {
    fun isCloud(): Boolean = source in setOf(
        SongSource.GDRIVE, SongSource.TELEGRAM,
        SongSource.DROPBOX, SongSource.ONEDRIVE, SongSource.BOX, SongSource.NEXTCLOUD
    )
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
    dropboxFileId = dropboxFileId,
    dropboxAccountEmail = dropboxAccountEmail,
    onedriveFileId = onedriveFileId,
    onedriveAccountEmail = onedriveAccountEmail,
    boxFileId = boxFileId,
    boxAccountEmail = boxAccountEmail,
    nextcloudFileId = nextcloudFileId,
    nextcloudAccountEmail = nextcloudAccountEmail,
    telegramChannelUrl = telegramChannelUrl,
    telegramChatId = telegramChatId,
    telegramMessageId = telegramMessageId,
    telegramFileId = telegramFileId,
    isEnriched = isEnriched,
    albumArtFetchAttempted = albumArtFetchAttempted,
    lastSyncTimestamp = lastSyncTimestamp,
    lyricsOffsetMs = lyricsOffsetMs
)

data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList()
)

enum class NowPlayingBackgroundMode { BLACK, SOLID, BLUR }

enum class AlbumArtTransform {
    NONE, FADE, SLIDE, SCALE, ROTATE, FLIP, ZOOM,
    CROSSFADE_BLUR, VINYL_SPIN, PARALLAX_DEPTH, SHUTTER, GLITCH_SHIFT
}

enum class QualityBadgeStyle { GOLDEN_SHIMMER, MINIMAL_OUTLINE, GLASSMORPHIC, NEON_PULSE }

enum class NowPlayingIconStyle { FILLED, OUTLINED, ROUNDED, SHARP_MINIMAL }

enum class SeekbarStyle {
    WAVEFORM,          // existing WaveformSeekBar, kept as-is, default
    SPECTRUM_TIMELINE,
    SMART_CHAPTER,
    PARTICLE_TRAIL,
    MORPHING_BLOB,
    ALBUM_ART_GRADIENT,
    LOUDNESS_HEATMAP,
    LYRICS_MARKER,
    MINI_SPECTRUM_THUMB,
    GLASS_TUBE,
    LIVE_WAVEFORM,
    VINYL_GROOVE,
    LIQUID_FLOW,
    FREQUENCY_SPECTRUM,
    CONSTELLATION,
    HEARTBEAT,
    GALAXY,
    ROPE,
    SOUND_PARTICLES,
    CRYSTAL_PRISM,
    MAGNETIC_FLOATING
}

data class AppearanceConfig(
    val nowPlayingBackgroundMode: NowPlayingBackgroundMode = if (DeviceUtils.isClassicDevice()) NowPlayingBackgroundMode.SOLID else NowPlayingBackgroundMode.BLUR,
    val nowPlayingSolidColorIntensity: Float = 0.6f,
    val nowPlayingSolidColorDarkness: Float = 0.4f,
    val nowPlayingBlurIntensity: Float = 210f,
    val nowPlayingBlurDarkness: Float = 0.3f,
    val albumArtTransform: AlbumArtTransform = AlbumArtTransform.NONE,
    val qualityBadgeStyle: QualityBadgeStyle = QualityBadgeStyle.GOLDEN_SHIMMER,
    val nowPlayingIconStyle: NowPlayingIconStyle = NowPlayingIconStyle.FILLED,

    val seekbarStyle: SeekbarStyle = SeekbarStyle.WAVEFORM,

    // Main Screen Background
    val mainBackgroundMode: NowPlayingBackgroundMode = if (DeviceUtils.isClassicDevice()) NowPlayingBackgroundMode.BLACK else NowPlayingBackgroundMode.BLUR,
    val mainSolidColorIntensity: Float = 0.6f,
    val mainSolidColorDarkness: Float = 0.4f,
    val mainBlurIntensity: Float = 120f,
    val mainBlurDarkness: Float = 0.5f,

    // Home Screen Background
    val homeBackgroundMode: NowPlayingBackgroundMode = if (DeviceUtils.isClassicDevice()) NowPlayingBackgroundMode.BLACK else NowPlayingBackgroundMode.BLUR,
    val homeSolidColorIntensity: Float = 0.6f,
    val homeSolidColorDarkness: Float = 0.4f,
    val homeBlurIntensity: Float = 120f,
    val homeBlurDarkness: Float = 0.5f,

    // Settings Screen Background
    val settingsBackgroundMode: NowPlayingBackgroundMode = if (DeviceUtils.isClassicDevice()) NowPlayingBackgroundMode.BLACK else NowPlayingBackgroundMode.BLUR,
    val settingsSolidColorIntensity: Float = 0.6f,
    val settingsSolidColorDarkness: Float = 0.4f,
    val settingsBlurIntensity: Float = 120f,
    val settingsBlurDarkness: Float = 0.5f,

    // Mini Player Background
    val miniPlayerBackgroundMode: NowPlayingBackgroundMode = if (DeviceUtils.isClassicDevice()) NowPlayingBackgroundMode.SOLID else NowPlayingBackgroundMode.BLUR,
    val miniPlayerSolidColorIntensity: Float = 0.6f,
    val miniPlayerSolidColorDarkness: Float = 0.4f,
    val miniPlayerBlurIntensity: Float = 70f,
    val miniPlayerBlurDarkness: Float = 0.5f,

    val showAudioQualityBadge: Boolean = true,
    val showAudioPipelineOverlay: Boolean = true,
    val showTechnicalInfoPanel: Boolean = true,
    val showLyricsButton: Boolean = true,

    // Home Screen Sections
    val showGreetingHeader: Boolean = true,
    val showBrowseByMood: Boolean = true,
    val showMadeForYou: Boolean = true,
    val showListenAgain: Boolean = true,
    val showRecentlyAddedHome: Boolean = true,
    val showYourFavoritesHome: Boolean = true,
    val showFeaturedAlbums: Boolean = true,
    val showArtistsYouLove: Boolean = true,
    val showYourPlaylists: Boolean = true,

    // Now Playing Shortcuts
    val showFavoriteButton: Boolean = true,
    val showEqualizerShortcut: Boolean = true,
    val showQueueButton: Boolean = true,
    val showSleepTimerIcon: Boolean = true,

    // Home Screen Layout
    val homeScreenSectionsOrder: List<String> = listOf(
        "GREETING", "ACTION_CHIPS", "CLOUD_LIBRARY", "MOODS",
        "MADE_FOR_YOU", "LISTEN_AGAIN", "RECENTLY_ADDED",
        "YOUR_FAVORITES", "FEATURED_ALBUMS", "ARTISTS_YOU_LOVE", "YOUR_PLAYLISTS"
    )
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
    CLOUD, RADIO, SMB_NAS, FTP_SFTP
}

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val band: String,   // "FM" or "AM"
    val favicon: String? = null
)

fun RadioStation.toSong() = Song(
    id = "radio_$id",
    uri = android.net.Uri.parse(streamUrl),
    title = name,
    artist = "$band Radio • $country",
    album = "Live Radio",
    durationMs = 0L,
    format = "MP3",
    sampleRateHz = 44100,
    albumArtUri = favicon?.let { android.net.Uri.parse(it) },
    source = SongSource.WEB
)

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

enum class CloudProvider {
    GDRIVE, TELEGRAM, DROPBOX, ONEDRIVE, BOX, NEXTCLOUD
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
    val cloudSongCount: Int = 0,
    val cloudAlbumCount: Int = 0,
    val cloudArtistCount: Int = 0,
    val showSyncStatusOnHome: Boolean = false,
    val isSyncFinishedRecently: Boolean = false,
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
    val driveErrorMessage: String? = null,
    val dropboxErrorMessage: String? = null,
    val onedriveErrorMessage: String? = null,
    val boxErrorMessage: String? = null,
    val nextcloudErrorMessage: String? = null,
    val telegramSyncErrorMessage: String? = null,
    val shuffleMode: Boolean = false,
    val repeatMode: Int = 0, // 0: Off, 1: One, 2: All
    val currentView: LibraryView = LibraryView.HOME,
    val selectedItemName: String? = null, // For Album name, Artist name etc.
    val showFullPlayer: Boolean = false,
    val showQueue: Boolean = false,
    val showSongInfo: Boolean = false, // one-shot "sink" flag: tells NowPlayingScreen to reopen its info dialog
    val pendingInspectorReturnSong: Song? = null, // set when Inspector was opened from a song's options sheet (list context), so back-navigation reopens that sheet's info overlay instead of the Now Playing screen
    val previousSongs: List<Song> = emptyList(),
    val upcomingSongs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val authRecoveryIntent: android.content.Intent? = null,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val sortType: SortType = SortType.NAME,
    val isAscending: Boolean = true,
    val qualityTierFilter: String? = null, // null = "All"; else "Excellent"/"Good"/"Fair"/"Poor"
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
    val appearance: AppearanceConfig = AppearanceConfig(),
    val resamplingEnabled: Boolean = true,
    val currentFolderPath: String? = null,
    val isFirstRun: Boolean = true,
    val previousView: LibraryView? = null,
    val wasSearchingBeforeDetail: Boolean = false,
    val useOriginalQualityArt: Boolean = false,
    val showLyrics: Boolean = false,
    val cameFromNowPlaying: Boolean = false,

    // Online Metadata (Last.fm etc)
    val lastFmTrackInfo: com.beatraxus.app.repository.lastfm.LastFmTrack? = null,
    val lastFmArtistInfo: com.beatraxus.app.repository.lastfm.LastFmArtistDetail? = null,
    val lastFmAlbumInfo: com.beatraxus.app.repository.lastfm.LastFmAlbum? = null,
    val isLoadingOnlineInfo: Boolean = false,

    // Online Metadata for selected song (in lists/popups)
    val selectedLastFmTrackInfo: com.beatraxus.app.repository.lastfm.LastFmTrack? = null,
    val selectedLastFmArtistInfo: com.beatraxus.app.repository.lastfm.LastFmArtistDetail? = null,
    val selectedLastFmAlbumInfo: com.beatraxus.app.repository.lastfm.LastFmAlbum? = null,
    val isSelectedLoadingOnlineInfo: Boolean = false,

    val lyrics: List<LrcLine> = emptyList(),
    val lyricsCurrentIndex: Int = -1,
    val lyricsOffsetMs: Long = 0L,
    val isLoadingLyrics: Boolean = false,
    val lyricsCurrentSongId: String? = null,
    val lyricsSource: LyricsSource? = null,
    val lyricsErrorMessage: String? = null, // surfaced to UI on a failed manual "search online" (was previously swallowed silently)
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
    val driveAccounts: List<com.beatraxus.app.repository.DriveAccount> = emptyList(),
    val dropboxAccounts: List<com.beatraxus.app.repository.DropboxAccount> = emptyList(),
    val onedriveAccounts: List<com.beatraxus.app.repository.OneDriveAccount> = emptyList(),
    val boxAccounts: List<com.beatraxus.app.repository.BoxAccount> = emptyList(),
    val nextcloudAccounts: List<com.beatraxus.app.repository.NextcloudAccount> = emptyList(),
    val telegramChannels: List<TelegramChannel> = emptyList(),
    val smbServers: List<com.beatraxus.app.repository.SmbServer> = emptyList(),
    val ftpServers: List<com.beatraxus.app.repository.FtpServer> = emptyList(),
    // Last.fm
    val lastFmUsername: String? = null,
    val scrobblingEnabled: Boolean = true,
    // Sync Settings
    val metadataNetworkType: NetworkType = NetworkType.ASK_MOBILE,
    val dataSaverEnabled: Boolean = false,
    val artworkEnrichmentEnabled: Boolean = true,
    val syncQuality: SyncQuality = SyncQuality.MEDIUM,
    val backgroundSyncEnabled: Boolean = true,
    val isEnrichmentPaused: Boolean = false,
    val enrichmentStatus: String? = null,
    val telegramAuthState: AuthState = AuthState.NotReady,
    val isSubmittingTelegram: Boolean = false,
    val telegramAuthError: String? = null,
    val castErrorMessage: String? = null,
    val showTelegramPhoneForm: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val isOemBatteryManagerDetected: Boolean = false,
    val gdriveAllowedFormats: Set<String> = emptySet(),
    val telegramAllowedFormats: Set<String> = emptySet(),

    val chapters: List<ChapterEntity> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val loudnessData: FloatArray? = null,
    val spectrumData: FloatArray? = null,
    val bufferedProgress: Float = 0f
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
                cloudSongCount == other.cloudSongCount &&
                cloudAlbumCount == other.cloudAlbumCount &&
                cloudArtistCount == other.cloudArtistCount &&
                showSyncStatusOnHome == other.showSyncStatusOnHome &&
                isSyncFinishedRecently == other.isSyncFinishedRecently &&
                permissionDenied == other.permissionDenied &&
                errorMessage == other.errorMessage &&
                driveErrorMessage == other.driveErrorMessage &&
                dropboxErrorMessage == other.dropboxErrorMessage &&
                onedriveErrorMessage == other.onedriveErrorMessage &&
                boxErrorMessage == other.boxErrorMessage &&
                nextcloudErrorMessage == other.nextcloudErrorMessage &&
                telegramSyncErrorMessage == other.telegramSyncErrorMessage &&
                shuffleMode == other.shuffleMode &&
                repeatMode == other.repeatMode &&
                currentView == other.currentView &&
                selectedItemName == other.selectedItemName &&
                showFullPlayer == other.showFullPlayer &&
                showQueue == other.showQueue &&
                showSongInfo == other.showSongInfo &&
                pendingInspectorReturnSong == other.pendingInspectorReturnSong &&
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
                appearance == other.appearance &&
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
                lyricsErrorMessage == other.lyricsErrorMessage &&
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
                dropboxAccounts == other.dropboxAccounts &&
                onedriveAccounts == other.onedriveAccounts &&
                boxAccounts == other.boxAccounts &&
                nextcloudAccounts == other.nextcloudAccounts &&
                telegramChannels == other.telegramChannels &&
                smbServers == other.smbServers &&
                ftpServers == other.ftpServers &&
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
                castErrorMessage == other.castErrorMessage &&
                showTelegramPhoneForm == other.showTelegramPhoneForm &&
                isIgnoringBatteryOptimizations == other.isIgnoringBatteryOptimizations &&
                isOemBatteryManagerDetected == other.isOemBatteryManagerDetected &&
                gdriveAllowedFormats == other.gdriveAllowedFormats &&
                telegramAllowedFormats == other.telegramAllowedFormats
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
        result = 31 * result + cloudSongCount
        result = 31 * result + cloudAlbumCount
        result = 31 * result + cloudArtistCount
        result = 31 * result + showSyncStatusOnHome.hashCode()
        result = 31 * result + isSyncFinishedRecently.hashCode()
        result = 31 * result + permissionDenied.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (driveErrorMessage?.hashCode() ?: 0)
        result = 31 * result + (dropboxErrorMessage?.hashCode() ?: 0)
        result = 31 * result + (onedriveErrorMessage?.hashCode() ?: 0)
        result = 31 * result + (boxErrorMessage?.hashCode() ?: 0)
        result = 31 * result + (nextcloudErrorMessage?.hashCode() ?: 0)
        result = 31 * result + (telegramSyncErrorMessage?.hashCode() ?: 0)
        result = 31 * result + shuffleMode.hashCode()
        result = 31 * result + repeatMode
        result = 31 * result + currentView.hashCode()
        result = 31 * result + (selectedItemName?.hashCode() ?: 0)
        result = 31 * result + showFullPlayer.hashCode()
        result = 31 * result + showQueue.hashCode()
        result = 31 * result + showSongInfo.hashCode()
        result = 31 * result + (pendingInspectorReturnSong?.hashCode() ?: 0)
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
        result = 31 * result + appearance.hashCode()
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
        result = 31 * result + (lyricsErrorMessage?.hashCode() ?: 0)
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
        result = 31 * result + dropboxAccounts.hashCode()
        result = 31 * result + onedriveAccounts.hashCode()
        result = 31 * result + boxAccounts.hashCode()
        result = 31 * result + nextcloudAccounts.hashCode()
        result = 31 * result + telegramChannels.hashCode()
        result = 31 * result + smbServers.hashCode()
        result = 31 * result + ftpServers.hashCode()
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
        result = 31 * result + (castErrorMessage?.hashCode() ?: 0)
        result = 31 * result + showTelegramPhoneForm.hashCode()
        result = 31 * result + isIgnoringBatteryOptimizations.hashCode()
        result = 31 * result + isOemBatteryManagerDetected.hashCode()
        result = 31 * result + gdriveAllowedFormats.hashCode()
        result = 31 * result + telegramAllowedFormats.hashCode()
        return result
    }
}