package com.beatraxus.app.viewmodel

import java.io.File

import android.app.Application
import android.util.Log
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlin.math.roundToInt
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.OutputMode
import com.beatraxus.app.model.PlaylistEntity
import com.beatraxus.app.model.FavoriteEntity
import com.beatraxus.app.model.AutoEqProfileSummary
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.AlbumArtTransform
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.DvcMode
import com.beatraxus.app.model.ParametricEqBand
import com.beatraxus.app.model.EqBandType
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.ReplayGainOption
import com.beatraxus.app.model.ReplayGainSource
import com.beatraxus.app.model.ResamplerMode
import com.beatraxus.app.model.LibraryView
import com.beatraxus.app.model.defaultEqBands
import com.beatraxus.app.model.Audio3DStagePreset
import com.beatraxus.app.model.Playlist
import com.beatraxus.app.engine.DecoderFactory
import com.beatraxus.app.engine.FfmpegAlacDecoder
import com.beatraxus.app.engine.MediaCodecAudioDecoder
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.beatraxus.app.drive.DrivePlaybackHelper
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.parseTelegramChannelName
import com.beatraxus.app.repository.DriveAccountRepository
import com.beatraxus.app.engine.AudioSpectrumAnalyzer
import com.beatraxus.app.engine.smart.LibraryGrouping
import com.beatraxus.app.engine.smart.SongProfile
import com.beatraxus.app.engine.smart.SongProfiler
import com.beatraxus.app.engine.smart.smartProfile
import com.beatraxus.app.engine.smart.basicProfile
import com.beatraxus.app.engine.smart.toProfileInput
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.isVideoFile
import com.beatraxus.app.model.toEntity
import com.beatraxus.app.model.SortType
import com.beatraxus.app.model.ViewMode
import com.beatraxus.app.model.LibraryMode
import com.beatraxus.app.model.NetworkType
import com.beatraxus.app.model.SyncQuality
import com.beatraxus.app.repository.MusicRepository
import com.beatraxus.app.repository.AutoEqRepository
import com.beatraxus.app.repository.LyricsRepository
import com.beatraxus.app.repository.LrcParser
import com.beatraxus.app.repository.LyricsSource
import com.beatraxus.app.repository.LyricsState
import com.beatraxus.app.repository.TelegramChannelRepository
import com.beatraxus.app.util.ArtistNameUtils
import com.beatraxus.app.util.PlaybackGlobalState
import com.beatraxus.app.telegram.AuthState
import com.beatraxus.app.telegram.TdLibManager
import com.beatraxus.app.ui.screens.AppOpenSplashState
import org.drinkless.tdlib.TdApi
import com.beatraxus.app.service.AudioPlaybackService
import com.beatraxus.app.cast.CastManager
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.beatraxus.app.model.PlaybackMode
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import com.beatraxus.app.repository.AppearancePreferences
import com.beatraxus.app.repository.LyricsCandidate
import com.beatraxus.app.repository.LyricsProviderConfig
import com.beatraxus.app.repository.DspPreferences
import com.beatraxus.app.repository.DriveAccount
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PlayerViewModel"

    private val musicRepository = MusicRepository(application)
    private val autoEqRepository = AutoEqRepository(application)
    private val autoEqApiService = com.beatraxus.app.repository.AutoEqApiService(application)
    private val dspPreferences = DspPreferences(application)
    private val appearancePreferences = AppearancePreferences(application)
    private val lyricsRepository = LyricsRepository(application, (application as BeatraxusApplication).database) {
        LyricsProviderConfig(
            order = _uiState.value.appearance.lyricsProviderOrder,
            enabled = _uiState.value.appearance.lyricsEnabledProviders
        )
    }
    private val app = application as BeatraxusApplication
    private val driveAccountRepository = app.driveAccountRepository

    private val db = app.database
    private val bookmarkRepository = com.beatraxus.app.repository.BookmarkRepository(db.bookmarkDao())
    private val chapterRepository = com.beatraxus.app.repository.ChapterRepository(db.chapterDao())
    private val highlightRepository = com.beatraxus.app.repository.HighlightRepository(db.highlightDao())
    private val loudnessRepository = com.beatraxus.app.repository.LoudnessRepository(db.loudnessDao())
    private val dropboxAccountRepository = app.dropboxAccountRepository
    private val onedriveAccountRepository = app.onedriveAccountRepository
    private val boxAccountRepository = app.boxAccountRepository
    private val nextcloudAccountRepository = app.nextcloudAccountRepository
    private val smbConnectionRepository = app.smbConnectionRepository
    private val ftpConnectionRepository = app.ftpConnectionRepository
    private val smbFolderBrowser = app.smbFolderBrowser
    private val ftpFolderBrowser = app.ftpFolderBrowser
    private val telegramChannelRepository = TelegramChannelRepository(application)
    private val cloudCacheManager = app.cloudCacheManager
    private val metadataExtractor = com.beatraxus.app.repository.MetadataExtractor(application)
    private val tdLibManager = (application as BeatraxusApplication).tdLibManager
    private val lastFmRepository = com.beatraxus.app.repository.lastfm.LastFmRepository(application)
    private val pendingLastFmAuth = AtomicBoolean(false)
    private val networkObserver = com.beatraxus.app.util.NetworkObserver(application)
    private val backupRepository = com.beatraxus.app.repository.BackupRepository(
        application,
        dspPreferences,
        lastFmRepository,
        telegramChannelRepository,
        driveAccountRepository
    )

    private val database = (application as BeatraxusApplication).database
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val songDao = database.songDao()
    private val folderDao = database.folderDao()
    private val videoFolderDao = database.videoFolderDao()
    private val aiAnalysisDao = database.aiAnalysisDao()
    private val artistArtDao = database.artistArtDao()
    private val songQualityDao = database.songQualityDao()
    private val recentlyPlayedDao = database.recentlyPlayedDao()
    private val videoRecentlyPlayedDao = database.videoRecentlyPlayedDao()
    // Lazy: it needs decoderFactory (declared below). Songs the native extractor declines (ALAC, Dolby, DTS...)
    // are decoded with the same decoders playback uses and analysed from that PCM.
    private val aiAnalysisEngine by lazy {
        com.beatraxus.app.engine.AiAnalysisEngine(application) { song, dsp ->
            com.beatraxus.app.engine.PcmFeatureExtractor(decoderFactory).extract(song, dsp)
        }
    }
    private val cloudAccountManager = com.beatraxus.app.repository.CloudAccountManager(application, database)
    private val libraryScanner = com.beatraxus.app.repository.LibraryScanner(application, musicRepository, songDao, viewModelScope)
    private val videoScanner = com.beatraxus.app.repository.VideoLibraryScanner(application)
    
    private var enrichmentJob: Job? = null
    private var folderWatchJob: Job? = null

    private val decoderFactory = DecoderFactory(
        context = application,
        driveAccountRepository = driveAccountRepository,
        cloudCacheManager = cloudCacheManager,
        tdLibManager = tdLibManager,
        ffmpegAlacDecoder = FfmpegAlacDecoder(application, cloudCacheManager, tdLibManager),
        mediaCodecDecoder = MediaCodecAudioDecoder(application, cloudCacheManager, tdLibManager)
    )

    private val audioSpectrumAnalyzer by lazy { AudioSpectrumAnalyzer(application, decoderFactory) }

    val smbServers = smbConnectionRepository.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ftpServers = ftpConnectionRepository.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var pendingPermissionAction: (() -> Unit)? = null
    private val aiAnalysisChannel = kotlinx.coroutines.channels.Channel<Song>(100, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // AI EQ: free, offline per-song EQ prediction. Predicts the playing song at once and preloads the next 8
    // songs of the queue, so every song starts with its own EQ already in place (see engine/aieq).
    private val aiEqManager by lazy {
        com.beatraxus.app.engine.aieq.AiEqManager(
            context = application,
            scope = viewModelScope,
            pcmFeatureSource = { song, dsp ->
                com.beatraxus.app.engine.PcmFeatureExtractor(decoderFactory).extract(song, dsp)
            },
            onProfileReady = { profile ->
                if (profile.songId == _uiState.value.currentSong?.id) service?.applyAiEq(profile)
            }
        )
    }
    /** Predicted AI EQ profile per song id. */
    val aiEqProfiles: StateFlow<Map<String, com.beatraxus.app.engine.aieq.AiEqProfile>> get() = aiEqManager.profiles
    /** Ids of songs whose AI EQ is being predicted right now or is waiting in the preload queue. */
    val aiEqBusy: StateFlow<Set<String>> get() = aiEqManager.busy
    private val musicBrainzService = com.beatraxus.app.repository.MusicBrainzService() // NEW
    private val yearEnrichmentChannel = kotlinx.coroutines.channels.Channel<Song>(kotlinx.coroutines.channels.Channel.UNLIMITED) // NEW

    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)

    /**
     * Reads an enum stored by name in SharedPreferences. A value written by an older app version
     * (enum entry since renamed or removed) used to throw IllegalArgumentException from valueOf()
     * while this ViewModel was being constructed, which crashed the app on every launch until the
     * app data was cleared. Fall back to the default instead.
     */
    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, default.name) ?: default.name) }
            .getOrDefault(default)

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            "data_saver_enabled" -> {
                val enabled = p.getBoolean(key, false)
                _uiState.update { it.copy(dataSaverEnabled = enabled) }
            }
            "artwork_enrichment_enabled" -> {
                val enabled = p.getBoolean(key, true)
                _uiState.update { it.copy(artworkEnrichmentEnabled = enabled) }
            }
            "scrobbling_enabled" -> {
                val enabled = p.getBoolean(key, true)
                _uiState.update { it.copy(scrobblingEnabled = enabled) }
            }
            "playback_mode" -> {
                val modeStr = p.getString(key, com.beatraxus.app.model.PlaybackMode.AUDIO.name)
                val mode = runCatching {
                    com.beatraxus.app.model.PlaybackMode.valueOf(modeStr ?: com.beatraxus.app.model.PlaybackMode.AUDIO.name)
                }.getOrDefault(com.beatraxus.app.model.PlaybackMode.AUDIO)
                _uiState.update { it.copy(playbackMode = mode) }
            }
        }
    }

    private val _uiState = MutableStateFlow(PlayerUiState(
        isFirstRun = prefs.getBoolean("first_run", true),
        useOriginalQualityArt = prefs.getBoolean("use_original_quality_art", false),
        outputMode = OutputMode.fromName(prefs.getString(KEY_OUTPUT_MODE, null)).name,
        musicFolders = emptyList(),
        blockedFolders = musicRepository.getBlockedFolders(),
        dsp = com.beatraxus.app.model.DspUiState(
            customEqPresets = loadCustomEqPresets()
        ),
        libraryMode = enumPref("library_mode", LibraryMode.LOCAL),
        videoSortType = enumPref("video_sort_type", SortType.NAME),
        videoSortAscending = prefs.getBoolean("video_sort_ascending", true),
        hiddenSongIds = prefs.getStringSet("hidden_song_ids", emptySet())?.toSet() ?: emptySet(),
        minSongDurationSec = com.beatraxus.app.model.SongVisibility.normalizeMinDurationSec(prefs.getInt("min_song_duration_sec", 0)),
        playbackMode = enumPref("playback_mode", com.beatraxus.app.model.PlaybackMode.AUDIO),
        metadataNetworkType = enumPref("metadata_network_type", NetworkType.ASK_MOBILE),
        dataSaverEnabled = prefs.getBoolean("data_saver_enabled", false),
        artworkEnrichmentEnabled = prefs.getBoolean("artwork_enrichment_enabled", true),
        syncQuality = enumPref("sync_quality", SyncQuality.MEDIUM),
        backgroundSyncEnabled = prefs.getBoolean("background_sync_enabled", true),
        scrobblingEnabled = prefs.getBoolean("scrobbling_enabled", true),
        gdriveAllowedFormats = prefs.getStringSet("gdrive_allowed_formats", emptySet()) ?: emptySet(),
        telegramAllowedFormats = prefs.getStringSet("telegram_allowed_formats", emptySet()) ?: emptySet(),
        shuffleMode = prefs.getBoolean("last_shuffle_mode", false),
        repeatMode = enumPref("last_repeat_mode", com.beatraxus.app.engine.RepeatMode.OFF).ordinal
    ))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _realtimeLevels = MutableStateFlow(floatArrayOf(0f, 0f))
    val realtimeLevels: StateFlow<FloatArray> = _realtimeLevels.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    private val _deleteRequest = MutableStateFlow<android.app.PendingIntent?>(null)
    val deleteRequest: StateFlow<android.app.PendingIntent?> = _deleteRequest.asStateFlow()

    val playlists: StateFlow<List<Playlist>> = playlistDao.getAllPlaylists()
        .map { entities ->
            entities.map { entity ->
                Playlist(
                    id = entity.id,
                    name = entity.name,
                    songIds = entity.songIds.split(",").filter { it.isNotBlank() }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<Set<String>> = favoriteDao.getAllFavorites()
        .map { it.map { f -> f.songId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val aiAnalysis: StateFlow<Map<String, com.beatraxus.app.model.AiAnalysisEntity>> = aiAnalysisDao.getAllAnalysisFlow()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val songQuality: StateFlow<Map<String, com.beatraxus.app.model.SongQualityEntity>> = songQualityDao.getAllQualityFlow()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Loads a single song + its quality entity by id, for the Music Detail Inspector screen. */
    fun songQualityFlow(songId: String): Flow<com.beatraxus.app.model.SongQualityEntity?> =
        songQualityDao.getQualityFlow(songId)

    /** Looks up a single song by id as a Flow, for the Music Detail Inspector screen. */
    fun songByIdFlow(songId: String): Flow<Song?> =
        allSongsWithFavorites.map { list -> list.find { it.id == songId } }

    private val _songs = MutableStateFlow<List<Song>>(emptyList())

    /**
     * The library as the listener wants to see it (audio feature 13): without hidden songs and without songs shorter
     * than the chosen minimum. Every list, the home screen and smart playlists build on this, so they all agree.
     */
    val allSongs: StateFlow<List<Song>> = combine(
        _songs,
        _uiState.map { it.hiddenSongIds }.distinctUntilChanged(),
        _uiState.map { it.minSongDurationSec }.distinctUntilChanged()
    ) { songs, hidden, minSec ->
        com.beatraxus.app.model.SongVisibility.visible(songs, hidden, minSec, { it.id }, { it.durationMs })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Songs the listener hid (A to Z), for the "Hidden songs" list in Settings where they can be shown again. */
    val hiddenSongs: StateFlow<List<Song>> = combine(
        _songs,
        _uiState.map { it.hiddenSongIds }.distinctUntilChanged()
    ) { songs, hidden ->
        if (hidden.isEmpty()) emptyList() else songs.filter { it.id in hidden }.sortedBy { it.title.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** How many songs the minimum duration is leaving out right now (shown next to the setting). */
    val songsBelowMinDuration: StateFlow<Int> = combine(
        _songs,
        _uiState.map { it.minSongDurationSec }.distinctUntilChanged()
    ) { songs, minSec ->
        songs.count { com.beatraxus.app.model.SongVisibility.isTooShort(it.durationMs, minSec) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ---- In-app AI profiles (cleaned genres, sound styles, moods) -------------------------------
    // Derived from the song's tag + stored analysis whenever either changes; an unchanged song reuses
    // its last profile, so a big library is not re-scored on every list update.
    private val profileCache = HashMap<String, Pair<com.beatraxus.app.engine.smart.ProfileInput, SongProfile>>()

    /** songId -> AI profile. Feeds the Genres library, the genre detail lists and the mood tiles. */
    val songProfiles: StateFlow<Map<String, SongProfile>> = combine(_songs, aiAnalysis) { songs, analysis ->
        val out = HashMap<String, SongProfile>(songs.size * 2)
        for (song in songs) {
            val input = song.toProfileInput(analysis[song.id])
            val cached = profileCache[song.id]
            out[song.id] = if (cached != null && cached.first == input) cached.second
            else SongProfiler.profile(input).also { profileCache[song.id] = input to it }
        }
        if (profileCache.size > songs.size * 2 + 64) profileCache.keys.retainAll(out.keys)
        out
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun profileFor(song: Song): SongProfile =
        songProfiles.value[song.id] ?: song.basicProfile()

    /** True when [song] belongs to the genre / "✦ style" group called [label]. */
    private fun inGenreGroup(song: Song, label: String?): Boolean =
        label != null && profileFor(song).labels.contains(label)

    // On-device videos (MediaStore scan).
    private val _videos = MutableStateFlow<List<com.beatraxus.app.model.Video>>(emptyList())

    // Videos streamed from cloud sources: Telegram channels and cloud drives (Drive, Dropbox, OneDrive, Box, Nextcloud).
    private val telegramVideoRepository = com.beatraxus.app.repository.TelegramVideoRepository(application)
    private val cloudVideoRepository = com.beatraxus.app.repository.CloudVideoRepository(application)
    private val _cloudVideos = MutableStateFlow<List<com.beatraxus.app.model.Video>>(emptyList())

    /** Every connected cloud-drive account (enabled or not), as the video library sees them. */
    private val cloudVideoAccounts: Flow<List<com.beatraxus.app.repository.CloudVideoAccount>> = combine(
        driveAccountRepository.accounts,
        dropboxAccountRepository.accounts,
        onedriveAccountRepository.accounts,
        boxAccountRepository.accounts,
        nextcloudAccountRepository.accounts
    ) { drive, dropbox, onedrive, box, nextcloud ->
        buildList {
            drive.forEach { add(com.beatraxus.app.repository.CloudVideoAccount(VideoSource.GDRIVE, it.email, it.enabled)) }
            dropbox.forEach { add(com.beatraxus.app.repository.CloudVideoAccount(VideoSource.DROPBOX, it.email, it.enabled)) }
            onedrive.forEach { add(com.beatraxus.app.repository.CloudVideoAccount(VideoSource.ONEDRIVE, it.email, it.enabled)) }
            box.forEach { add(com.beatraxus.app.repository.CloudVideoAccount(VideoSource.BOX, it.email, it.enabled)) }
            nextcloud.forEach { add(com.beatraxus.app.repository.CloudVideoAccount(VideoSource.NEXTCLOUD, it.username, it.enabled)) }
        }
    }

    /** Local + cloud videos. Cloud videos of removed / disabled Telegram channels and cloud accounts are left out. */
    val allVideos: StateFlow<List<Video>> = combine(
        _videos,
        _cloudVideos,
        telegramChannelRepository.channels,
        cloudVideoAccounts
    ) { local, cloud, channels, accounts ->
        val enabledTelegram = channels.filter { it.enabled }.map { it.url.trim().removeSuffix("/") }.toSet()
        val enabledAccounts = accounts.filter { it.enabled }.map { it.id }.toSet()
        local + cloud.filter { v ->
            when (v.source) {
                VideoSource.LOCAL -> true
                VideoSource.TELEGRAM -> v.sourceId == null || v.sourceId in enabledTelegram
                else -> v.sourceId != null &&
                    com.beatraxus.app.repository.CloudVideoAccount.idOf(v.source, v.sourceId) in enabledAccounts
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun videosForMode(all: List<Video>, mode: LibraryMode): List<Video> = when (mode) {
        LibraryMode.LOCAL -> all.filter { it.source == VideoSource.LOCAL }
        LibraryMode.CLOUD -> all.filter { it.source != VideoSource.LOCAL }
        LibraryMode.COMBINED -> all
        LibraryMode.ADDONS -> emptyList()
    }

    /** Videos for the current library mode (Local / Cloud / Combined), before any view, sort or chip filtering. */
    val modeVideos: StateFlow<List<Video>> = combine(
        allVideos,
        _uiState.map { it.libraryMode }.distinctUntilChanged()
    ) { all, mode -> videosForMode(all, mode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _recentlyPlayed = MutableStateFlow<List<String>>(emptyList())
    private val _recentlyPlayedVideos = MutableStateFlow<List<String>>(emptyList())

    private val filteredVideos: StateFlow<List<com.beatraxus.app.model.Video>> = combine(
        allVideos,
        _uiState.map { it.currentView }.distinctUntilChanged(),
        _uiState.map { it.currentFolderPath }.distinctUntilChanged(),
        _uiState.map { it.searchQuery }.distinctUntilChanged(),
        _uiState.map { it.libraryMode }.distinctUntilChanged(),
        _uiState.map { it.videoSortType }.distinctUntilChanged(),
        _uiState.map { it.videoSortAscending }.distinctUntilChanged(),
        _recentlyPlayedVideos,
        _uiState.map { it.selectedVideoSource }.distinctUntilChanged()
    ) { args ->
        val all = args[0] as List<com.beatraxus.app.model.Video>
        val view = args[1] as LibraryView
        val folder = args[2] as String?
        val query = args[3] as String
        val libMode = args[4] as LibraryMode
        val sortType = args[5] as SortType
        val isAscending = args[6] as Boolean
        val recentIds = args[7] as List<String>
        val sourceFilter = args[8] as VideoSource?

        // LOCAL = on-device only, CLOUD = streamed sources only, COMBINED = both.
        // A selected source chip narrows CLOUD / COMBINED down to that one source.
        val modeFiltered = videosForMode(all, libMode).let { list ->
            if (libMode == LibraryMode.LOCAL || sourceFilter == null) list
            else list.filter { it.source == sourceFilter }
        }

        var filtered = when (view) {
            LibraryView.VIDEO_ALL -> modeFiltered
            LibraryView.VIDEO_FOLDER_DETAIL -> modeFiltered.filter { it.folderPath == folder }
            LibraryView.VIDEO_RECENTLY_ADDED -> modeFiltered.sortedByDescending { it.dateAdded }
            LibraryView.VIDEO_RECENTLY_PLAYED -> {
                recentIds.mapNotNull { id -> modeFiltered.find { it.id == id } }
            }
            else -> modeFiltered
        }

        // Apply the video sort (name / date / size / duration) unless the view defines its own order
        if (view !in listOf(LibraryView.VIDEO_RECENTLY_ADDED, LibraryView.VIDEO_RECENTLY_PLAYED)) {
            filtered = com.beatraxus.app.model.VideoSort.sortVideos(filtered, sortType, isAscending)
        }

        if (query.isNotEmpty()) {
            filtered = filtered.filter { it.title.contains(query, ignoreCase = true) }
        }
        filtered
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val videos: StateFlow<List<com.beatraxus.app.model.Video>> = filteredVideos

    /** Folders with their totals, in no particular order; [videoFolders] sorts them. */
    private val unsortedVideoFolders: Flow<List<com.beatraxus.app.model.VideoFolder>> = combine(
        allVideos,
        videoFolderDao.getActiveFolders(),
        _uiState.map { it.blockedFolders }.distinctUntilChanged(),
        _uiState.map { it.libraryMode }.distinctUntilChanged(),
        _uiState.map { it.selectedVideoSource }.distinctUntilChanged()
    ) { all, managed, blocked, libMode, sourceFilter ->
        val modeFiltered = videosForMode(all, libMode).let { list ->
            if (libMode == LibraryMode.LOCAL || sourceFilter == null) list
            else list.filter { it.source == sourceFilter }
        }

        val managedPaths = managed.map { it.path }
        val discoveredPaths = modeFiltered.map { it.folderPath }.distinct()
        val allPaths = (managedPaths + discoveredPaths).distinct()
            .filter { path -> !blocked.any { path.startsWith(it) } }

        allPaths.map { path ->
            val folderVideos = modeFiltered.filter { it.folderPath.startsWith(path) }
            com.beatraxus.app.model.VideoFolder(
                name = path.substringAfterLast("/"),
                path = path,
                videoCount = folderVideos.size,
                previewThumbnails = folderVideos.take(4).mapNotNull { it.thumbnailUri ?: it.uri.takeUnless { _ -> it.isCloud } },
                totalSizeBytes = folderVideos.sumOf { it.sizeBytes },
                totalDurationMs = folderVideos.sumOf { it.durationMs },
                latestDateAdded = folderVideos.maxOfOrNull { it.dateAdded } ?: 0L
            )
        }.filter { it.videoCount > 0 }
    }

    val videoFolders: StateFlow<List<com.beatraxus.app.model.VideoFolder>> = combine(
        unsortedVideoFolders,
        _uiState.map { it.videoSortType }.distinctUntilChanged(),
        _uiState.map { it.videoSortAscending }.distinctUntilChanged()
    ) { folders, sortType, ascending ->
        com.beatraxus.app.model.VideoSort.sortFolders(folders, sortType, ascending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allSongsWithFavorites: StateFlow<List<Song>> = combine(
        allSongs,
        favorites,
        driveAccountRepository.accounts,
        dropboxAccountRepository.accounts,
        onedriveAccountRepository.accounts,
        boxAccountRepository.accounts,
        nextcloudAccountRepository.accounts,
        telegramChannelRepository.channels
    ) { args ->
        val songs = args[0] as List<Song>
        val favoriteIds = args[1] as Set<String>
        val driveAccounts = args[2] as List<com.beatraxus.app.repository.DriveAccount>
        val dropboxAccounts = args[3] as List<com.beatraxus.app.repository.DropboxAccount>
        val onedriveAccounts = args[4] as List<com.beatraxus.app.repository.OneDriveAccount>
        val boxAccounts = args[5] as List<com.beatraxus.app.repository.BoxAccount>
        val nextcloudAccounts = args[6] as List<com.beatraxus.app.repository.NextcloudAccount>
        val tgChannels = args[7] as List<com.beatraxus.app.model.TelegramChannel>

        val enabledDriveEmails = driveAccounts.filter { it.enabled }.map { it.email.lowercase() }.toSet()
        val enabledDropboxEmails = dropboxAccounts.filter { it.enabled }.map { it.email.lowercase() }.toSet()
        val enabledOnedriveEmails = onedriveAccounts.filter { it.enabled }.map { it.email.lowercase() }.toSet()
        val enabledBoxEmails = boxAccounts.filter { it.enabled }.map { it.email.lowercase() }.toSet()
        val enabledNextcloudUsernames = nextcloudAccounts.filter { it.enabled }.map { it.username.lowercase() }.toSet()
        val enabledTgUrls = tgChannels.filter { it.enabled }.map { it.url }.toSet()

        songs.filter { song ->
            when (song.source) {
                SongSource.GDRIVE -> song.driveAccountEmail == null || song.driveAccountEmail.lowercase() in enabledDriveEmails
                SongSource.DROPBOX -> song.dropboxAccountEmail == null || song.dropboxAccountEmail.lowercase() in enabledDropboxEmails
                SongSource.ONEDRIVE -> song.onedriveAccountEmail == null || song.onedriveAccountEmail.lowercase() in enabledOnedriveEmails
                SongSource.BOX -> song.boxAccountEmail == null || song.boxAccountEmail.lowercase() in enabledBoxEmails
                SongSource.NEXTCLOUD -> song.nextcloudAccountEmail == null || song.nextcloudAccountEmail.lowercase() in enabledNextcloudUsernames
                SongSource.TELEGRAM -> song.telegramChannelUrl == null || song.telegramChannelUrl in enabledTgUrls
                else -> true
            }
        }.map { it.copy(isFavorite = favoriteIds.contains(it.id)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val filteredSongsByMode: StateFlow<List<Song>> = combine(
        allSongsWithFavorites,
        _uiState.map { it.libraryMode }.distinctUntilChanged()
    ) { all, mode ->
        when (mode) {
            LibraryMode.LOCAL -> all.filter { it.source == SongSource.LOCAL }
            LibraryMode.CLOUD -> all.filter { it.source != SongSource.LOCAL }
            LibraryMode.COMBINED -> all
            LibraryMode.ADDONS -> emptyList()
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val driveLibrarySongs: StateFlow<List<Song>> = allSongs.map { songs ->
        songs.filter { it.isCloud() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums = filteredSongsByMode.map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<Triple<String, String, Uri?>>> = filteredSongsByMode.map { songs ->
        // Each song contributes to every artist it credits
        val exploded = songs.flatMap { song ->
            ArtistNameUtils.splitArtists(song.artist).map { artistName -> artistName to song }
        }

        exploded
            .groupBy { (name, _) -> ArtistNameUtils.normalizeKey(name) }
            .map { (_, pairs) ->
                // pick the most common display-name spelling as canonical
                val displayName = pairs.map { it.first }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }!!.key
                val uniqueSongs = pairs.map { it.second }.distinctBy { it.id }
                // Use embedded album art from one of the artist's own tracks as the tile image.
                // null here signals the UI to render an initials avatar instead.
                Triple(displayName, "${uniqueSongs.size} songs", uniqueSongs.first().albumArtUri)
            }
            .sortedBy { it.first.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders = combine(filteredSongsByMode, _uiState) { songs, state ->
        val parentPath = state.currentFolderPath
        if (parentPath == null) {
            songs.groupBy { it.folder }
                .map { (path, list) ->
                    val firstSong = list.first()
                    if (firstSong.source == com.beatraxus.app.model.SongSource.GDRIVE) {
                        Triple(path, "GDRIVE", firstSong.albumArtUri)
                    } else if (firstSong.source == com.beatraxus.app.model.SongSource.TELEGRAM) {
                        Triple(path, "TELEGRAM", firstSong.albumArtUri)
                    } else {
                        Triple(path, path.substringAfterLast("/"), list.first().albumArtUri)
                    }
                }
                .sortedBy { it.second.lowercase() }
        } else {
            emptyList()
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val years = filteredSongsByMode.map { songs ->
        songs.groupBy { it.year }
            .map { (year, list) ->
                val yearStr = if (year == 0) "Unknown" else year.toString()
                Triple(yearStr, "${list.size} songs", list.first().albumArtUri)
            }
            .sortedWith(compareByDescending<Triple<String, String, Uri?>> {
                if (it.first == "Unknown") "0000" else it.first
            })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Genres library: one clean group per genre (tags like "Pop/Rock", "hip hop", "(13)" are merged),
     * plus the in-app AI's "✦" sound styles. A song shows up in every group it belongs to.
     */
    val genres = combine(filteredSongsByMode, songProfiles) { songs, profiles ->
        LibraryGrouping.byLabels(songs, { profiles[it.id] ?: it.basicProfile() }).map { g ->
            Triple(
                g.label,
                "${g.items.size} songs" + if (g.isStyle) " \u00b7 AI" else "",
                g.items.firstOrNull { it.albumArtUri != null }?.albumArtUri ?: g.items.first().albumArtUri
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val homeRecentlyPlayed: StateFlow<List<Song>> = combine(allSongs, _recentlyPlayed) { all, ids ->
        ids.mapNotNull { id -> all.find { it.id == id } }.take(10)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val homeQuickPicks: StateFlow<List<Song>> = allSongs.map { all ->
        if (all.isEmpty()) emptyList() else all.shuffled().take(12)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val debouncedSearchQuery: StateFlow<String> = _uiState
        .map { it.searchQuery }
        .debounce(280)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val searchResults = combine(
        filteredSongsByMode,
        filteredVideos,
        debouncedSearchQuery,
        _uiState.map { it.currentView }.distinctUntilChanged()
    ) { songs, videos, query, currentView ->
        if (query.isEmpty()) return@combine emptyList<Any>()
        val list = mutableListOf<Any>()

        val isVideoMode = currentView in listOf(
            LibraryView.VIDEO_ALL, LibraryView.VIDEO_FOLDERS,
            LibraryView.VIDEO_RECENTLY_ADDED, LibraryView.VIDEO_RECENTLY_PLAYED,
            LibraryView.VIDEO_FOLDER_DETAIL
        )

        if (!isVideoMode) {
            val matchedSongs = songs.filter { it.title.contains(query, ignoreCase = true) }
            if (matchedSongs.isNotEmpty()) {
                list.add("Songs")
                list.addAll(matchedSongs.take(20))
            }

            val matchedAlbums = songs.filter { it.album.contains(query, ignoreCase = true) }
                .distinctBy { it.album }
            if (matchedAlbums.isNotEmpty()) {
                list.add("Albums")
                matchedAlbums.take(10).forEach {
                    list.add(Triple(it.album, it.artist, it.albumArtUri))
                }
            }

            val matchedArtists = songs.filter { it.artist.contains(query, ignoreCase = true) }
                .distinctBy { it.artist }
            if (matchedArtists.isNotEmpty()) {
                list.add("Artists")
                matchedArtists.take(10).forEach {
                    list.add(Pair(it.artist, it.albumArtUri))
                }
            }
        } else {
            val matchedVideos = videos.filter { it.title.contains(query, ignoreCase = true) }
            if (matchedVideos.isNotEmpty()) {
                list.add("Videos")
                list.addAll(matchedVideos.take(20))
            }

            val matchedFolders = videos.filter { it.folderPath.substringAfterLast("/").contains(query, ignoreCase = true) }
                .distinctBy { it.folderPath }
            if (matchedFolders.isNotEmpty()) {
                list.add("Folders")
                matchedFolders.take(5).forEach { v ->
                    list.add(com.beatraxus.app.model.VideoFolder(
                        name = v.folderPath.substringAfterLast("/"),
                        path = v.folderPath,
                        videoCount = videos.count { it.folderPath == v.folderPath },
                        previewThumbnails = videos.filter { it.folderPath == v.folderPath }.take(1).mapNotNull { it.thumbnailUri }
                    ))
                }
            }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pairs the song list with the quality map so the library filter (Phase 4) can
    // join on qualityTier without a 6-argument combine().
    private val allSongsWithQuality = combine(allSongsWithFavorites, songQualityDao.getAllQualityFlow()) { list, qualityList ->
        list to qualityList.associateBy { it.songId }
    }

    /** songId -> quality tier ("Excellent" / "Good" / "Fair" / "Poor"); lets Smart Playlists use the same quality filter as All Songs. */
    val songQualityTiers: StateFlow<Map<String, String>> = songQualityDao.getAllQualityFlow()
        .map { list -> list.associate { it.songId to it.qualityTier } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val songs: StateFlow<List<Song>> = combine(allSongsWithQuality, _uiState, debouncedSearchQuery, _recentlyPlayed, playlists) { (allSongsList, qualityMap), state, debouncedQuery, recentIds, pls ->
        val mode = state.libraryMode
        val all = when (mode) {
            LibraryMode.LOCAL -> allSongsList.filter { it.source == SongSource.LOCAL }
            LibraryMode.CLOUD -> allSongsList.filter { it.source != SongSource.LOCAL }
            LibraryMode.COMBINED -> allSongsList
            LibraryMode.ADDONS -> emptyList()
        }

        var filtered = when (state.currentView) {
            LibraryView.HOME -> all.take(20)
            LibraryView.ALL_SONGS -> all
            LibraryView.ALBUMS -> emptyList()
            LibraryView.ARTISTS -> emptyList()
            LibraryView.FOLDERS -> emptyList()
            LibraryView.YEARS -> emptyList()
            LibraryView.GENRES -> emptyList()
            LibraryView.FAVORITES -> all.filter { it.isFavorite }
            LibraryView.RECENTLY_ADDED -> all.filter { !it.isVideoFile }.sortedByDescending { it.dateAdded }
            LibraryView.RADIO -> emptyList()
            LibraryView.RECENTLY_PLAYED -> {
                recentIds.filter { it != state.currentSong?.id }
                    .mapNotNull { id -> all.find { it.id == id } }
            }
            LibraryView.ALBUM_DETAIL -> all.filter { it.album == state.selectedItemName }
            LibraryView.ARTIST_DETAIL -> all.filter { song ->
                val target = state.selectedItemName ?: return@filter false
                ArtistNameUtils.splitArtists(song.artist)
                    .any { ArtistNameUtils.normalizeKey(it) == ArtistNameUtils.normalizeKey(target) }
            }
            LibraryView.FOLDER_DETAIL -> all.filter { it.folder == state.currentFolderPath }
            LibraryView.YEAR_DETAIL -> all.filter {
                val yearStr = if (it.year == 0) "Unknown" else it.year.toString()
                yearStr == state.selectedItemName
            }
            LibraryView.GENRE_DETAIL -> all.filter { inGenreGroup(it, state.selectedItemName) }
            LibraryView.PLAYLISTS -> emptyList()
            LibraryView.PLAYLIST_DETAIL -> {
                val playlist = pls.find { it.name == state.selectedItemName }
                playlist?.songIds?.mapNotNull { id -> allSongsList.find { it.id == id } } ?: emptyList()
            }
            LibraryView.CLOUD -> allSongsList.filter {
                if (state.selectedTelegramChannelUrl != null) {
                    it.source == com.beatraxus.app.model.SongSource.TELEGRAM && it.telegramChannelUrl == state.selectedTelegramChannelUrl
                } else if (state.selectedItemName != null) {
                    val target = state.selectedItemName.lowercase()
                    when (it.source) {
                        SongSource.GDRIVE -> it.driveAccountEmail?.lowercase() == target
                        SongSource.DROPBOX -> it.dropboxAccountEmail?.lowercase() == target
                        SongSource.ONEDRIVE -> it.onedriveAccountEmail?.lowercase() == target
                        SongSource.BOX -> it.boxAccountEmail?.lowercase() == target
                        SongSource.NEXTCLOUD -> it.nextcloudAccountEmail?.lowercase() == target
                        else -> false
                    }
                } else {
                    it.isCloud()
                }
            }
            else -> emptyList()
        }

        if (debouncedQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(debouncedQuery, ignoreCase = true) ||
                        it.artist.contains(debouncedQuery, ignoreCase = true) ||
                        it.album.contains(debouncedQuery, ignoreCase = true)
            }
        }

        if (state.qualityTierFilter != null) {
            filtered = filtered.filter { qualityMap[it.id]?.qualityTier == state.qualityTierFilter }
        }

        val comparator = when (state.sortType) {
            com.beatraxus.app.model.SortType.NAME -> compareBy<Song> { it.title.lowercase() }
            com.beatraxus.app.model.SortType.DATE_ADDED -> compareBy { it.dateAdded }
            com.beatraxus.app.model.SortType.FILE_SIZE -> compareBy { it.fileSizeBytes }
            com.beatraxus.app.model.SortType.DURATION -> compareBy { it.durationMs }
        }

        if (state.isAscending) filtered.sortedWith(comparator)
        else filtered.sortedWith(comparator).reversed()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingDeleteIds = emptyList<String>()

    private var libraryLoadJob: Job? = null
    private var serviceObserversJob: Job? = null

    // Dedicated low-priority thread for the background AI genre/mood/quality scan.
    // Using Dispatchers.Default here made this heavy work (MediaCodec + TFLite +
    // network calls per song) compete for CPU scheduling with the actual audio
    // decoder/render threads, which is what was causing playback to stall and
    // auto-skip while the library scan was running.
    private val aiAnalysisDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "AiAnalysisWorker")
    }.asCoroutineDispatcher()

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        // Loading FFmpegKit's native libraries is slow; keep it off the main thread so it can't
        // hold up the first frame. (runCatching also covers UnsatisfiedLinkError on odd ABIs.)
        Thread({ runCatching { FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR) } }, "FFmpegKitInit").start()

        viewModelScope.launch {
            musicRepository.getMusicFoldersFlow().collect { folders ->
                _uiState.update { it.copy(musicFolders = folders) }
            }
        }

        folderWatchJob = viewModelScope.launch {
            videoFolderDao.getActiveFolders().collect { folders ->
                val paths = folders.map { it.path }
                // Trigger video scan when folders change
                loadVideos(paths)
            }
        }

        // Reactive Music Folder Stats
        viewModelScope.launch {
            combine(allSongs, _uiState.map { it.musicFolders }.distinctUntilChanged()) { songs, folders ->
                folders.associateWith { folder ->
                    songs.count { it.source == SongSource.LOCAL && it.folder.startsWith(folder) }
                }
            }.collect { stats ->
                _uiState.update { it.copy(musicFolderStats = stats) }
            }
        }

        // Reactive Video Folder Stats
        viewModelScope.launch {
            combine(allVideos, videoFolderDao.getActiveFolders()) { vids, folders ->
                folders.associate { folder ->
                    folder.path to vids.count { it.folderPath.startsWith(folder.path) }
                }
            }.collect { stats ->
                _uiState.update { it.copy(videoFolderStats = stats) }
            }
        }

        // Trigger video scan on init
        loadVideos()

        // Cloud videos: show the cached Telegram + cloud-drive videos immediately, refresh them once the sources are ready.
        viewModelScope.launch(Dispatchers.IO) {
            val cached = telegramVideoRepository.loadCached() + cloudVideoRepository.loadCached()
            _cloudVideos.update { current -> if (current.isEmpty()) cached else current }
        }
        viewModelScope.launch {
            cloudVideoAccounts
                .map { accounts -> accounts.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { ids ->
                    // Drop videos of cloud accounts that were removed; disabled ones are only hidden (see allVideos).
                    var removedAny = false
                    _cloudVideos.update { current ->
                        val kept = current.filter { v ->
                            v.source !in com.beatraxus.app.repository.CloudVideoRepository.CLOUD_SOURCES ||
                                (v.sourceId != null && com.beatraxus.app.repository.CloudVideoAccount.idOf(v.source, v.sourceId) in ids)
                        }
                        removedAny = kept.size != current.size
                        kept
                    }
                    if (removedAny) {
                        val snapshot = _cloudVideos.value
                        withContext(Dispatchers.IO) { cloudVideoRepository.saveCached(snapshot) }
                    }
                    // A new / re-enabled account may bring new videos.
                    maybeRefreshCloudVideos(force = true)
                }
        }
        viewModelScope.launch {
            // The video player learned the real runtime / size of a streamed video: show it in the library too.
            com.beatraxus.app.repository.CloudVideoMetadataBus.updates.collect { u ->
                var changed = false
                _cloudVideos.update { current ->
                    current.map { v ->
                        if (v.id == u.videoId && (v.durationMs != u.durationMs || v.resolutionHeight != u.height)) {
                            changed = true
                            v.copy(durationMs = u.durationMs, resolutionWidth = u.width, resolutionHeight = u.height)
                        } else v
                    }
                }
                if (changed) withContext(Dispatchers.IO) { persistCloudVideos() }
            }
        }
        viewModelScope.launch {
            telegramChannelRepository.channels
                .map { channels -> channels.map { it.url.trim().removeSuffix("/") }.toSet() }
                .distinctUntilChanged()
                .collect { urls ->
                    // Drop videos of channels that were removed; disabled ones are only hidden (see allVideos).
                    var removedAny = false
                    _cloudVideos.update { current ->
                        val kept = current.filter { it.source != VideoSource.TELEGRAM || it.sourceId in urls }
                        removedAny = kept.size != current.size
                        kept
                    }
                    if (removedAny) {
                        val snapshot = _cloudVideos.value
                        withContext(Dispatchers.IO) { telegramVideoRepository.saveCached(snapshot) }
                    }
                    // A new / re-enabled channel may bring new videos.
                    refreshTelegramVideos(force = true)
                }
        }

        // Load persistent play history
        viewModelScope.launch {
            recentlyPlayedDao.getAllRecentlyPlayed().collect { entities ->
                _recentlyPlayed.value = entities.map { it.songId }
            }
        }

        viewModelScope.launch {
            videoRecentlyPlayedDao.getAllRecentlyPlayed().collect { entities ->
                _recentlyPlayedVideos.value = entities.map { it.videoId }
            }
        }

        viewModelScope.launch {
            combine(
                modeVideos,
                videoRecentlyPlayedDao.getContinueWatching(10)
            ) { vids, entities ->
                entities.mapNotNull { entity ->
                    val video = vids.find { it.id == entity.videoId }
                    if (video != null) video to entity else null
                }
            }.collect { list ->
                _uiState.update { it.copy(continueWatching = list) }
            }
        }

        // Observe Telegram auth state
        viewModelScope.launch {
            tdLibManager.authState.collect { state ->
                _uiState.update { it.copy(telegramAuthState = state) }
                if (state is AuthState.Ready) {
                    startTelegramLiveObservers()
                    refreshTelegramVideos(force = true)
                    // Auto-sync all enabled channels when Telegram becomes ready, 
                    // but ONLY if they haven't been synced in the last 6 hours.
                    viewModelScope.launch {
                        val currentTime = System.currentTimeMillis()
                        val sixHoursMs = 6 * 60 * 60 * 1000L

                        telegramChannelRepository.channels.first().forEach { channel ->
                            if (channel.enabled && (currentTime - channel.lastSyncTimestamp > sixHoursMs)) {
                                syncTelegramChannel(channel.url)
                            }
                        }
                    }
                }
            }
        }

        // Start AI Analysis worker
        viewModelScope.launch(aiAnalysisDispatcher) {
            // Delay AI analysis at startup until 5s after moving to main screen
            awaitUiSettled(5000L)

            for (song in aiAnalysisChannel) {
                // ROOT-CAUSE FIX: don't run heavy work while media is playing.
                while (_uiState.value.isPlaying || PlaybackGlobalState.isAnyPlaybackActive.value) {
                    delay(1500)
                }

                try {
                    analyzeAndStoreQuality(song)
                } catch (t: Throwable) {
                    Log.e("PlayerViewModel", "AI Analysis failed for ${song.title}: ${t.message}", t)
                }
                // Cooldown to prevent CPU/MediaCodec hogging between songs (was 100ms,
                // which wasn't enough breathing room given the decoder+TFLite+network cost)
                delay(750)
            }
        }

        // AI EQ: whenever it is on, predict the playing song right away and preload the next 8 queued songs.
        viewModelScope.launch {
            _uiState
                .map { Triple(it.dsp.config.aiEqEnabled, it.currentSong, it.upcomingSongs) }
                .distinctUntilChanged { old, new ->
                    old.first == new.first && old.second?.id == new.second?.id &&
                        old.third.take(com.beatraxus.app.engine.aieq.AiEqManager.PRELOAD_COUNT).map { it.id } ==
                        new.third.take(com.beatraxus.app.engine.aieq.AiEqManager.PRELOAD_COUNT).map { it.id }
                }
                .collect { (enabled, song, upcoming) ->
                    if (!enabled) {
                        aiEqManager.cancelPending()
                    } else {
                        aiEqManager.preload(song, upcoming)
                        // A profile cached earlier is in the audio store already, but push it in case the song
                        // started before AI EQ was switched on.
                        song?.id?.let { id -> aiEqManager.cached(id)?.let { service?.applyAiEq(it) } }
                    }
                }
        }

        // Year enrichment via MusicBrainz for songs with no tagged year
        // (these currently show up bundled together under "0" in the Years library).
        viewModelScope.launch(Dispatchers.Default) {
            delay(3000)
            for (song in yearEnrichmentChannel) {
                try {
                    val year = musicBrainzService.fetchReleaseYear(song.artist, song.title, song.album)
                    if (year != null && year != song.year) {
                        val updatedSong = song.copy(year = year)
                        withContext(Dispatchers.IO) {
                            songDao.insertSong(updatedSong.toEntity())
                        }
                        _songs.update { current ->
                            current.map { if (it.id == song.id) updatedSong else it }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("PlayerViewModel", "MusicBrainz year lookup failed for ${song.title}: ${t.message}", t)
                }
                // MusicBrainz anonymous rate limit: ~1 request/second
                delay(1100)
            }
        }

        // Collect SMB/FTP servers and update UI state
        viewModelScope.launch {
            smbServers.collect { servers ->
                _uiState.update { it.copy(smbServers = servers) }
            }
        }
        viewModelScope.launch {
            ftpServers.collect { servers ->
                _uiState.update { it.copy(ftpServers = servers) }
            }
        }

        // DSP settings
        viewModelScope.launch {
            dspPreferences.dspConfig.collect { config ->
                _uiState.update {
                    it.copy(dsp = it.dsp.copy(
                        config = config,
                        activeOutputDeviceLabel = dspPreferences.getCurrentDeviceLabel()
                    ))
                }
                service?.updateDspConfig(config)
            }
        }

        // Per-device EQ: load the preset assigned to the output that just became active
        viewModelScope.launch {
            dspPreferences.deviceIdFlow.distinctUntilChanged().collect { deviceId ->
                applyAssignedEq(deviceId)
            }
        }

        // Appearance settings
        viewModelScope.launch {
            appearancePreferences.appearanceConfig.collect { config ->
                com.beatraxus.app.ui.theme.DspPaletteState.apply(config)
                _uiState.update { it.copy(appearance = config) }
            }
        }

        // Drive Accounts and Telegram Channels
        viewModelScope.launch {
            driveAccountRepository.accounts.collect { accounts ->
                _uiState.update { it.copy(driveAccounts = accounts) }
            }
        }
        viewModelScope.launch {
            dropboxAccountRepository.accounts.collect { accounts ->
                _uiState.update { it.copy(dropboxAccounts = accounts) }
            }
        }
        viewModelScope.launch {
            onedriveAccountRepository.accounts.collect { accounts ->
                _uiState.update { it.copy(onedriveAccounts = accounts) }
            }
        }
        viewModelScope.launch {
            boxAccountRepository.accounts.collect { accounts ->
                _uiState.update { it.copy(boxAccounts = accounts) }
            }
        }
        viewModelScope.launch {
            nextcloudAccountRepository.accounts.collect { accounts ->
                _uiState.update { it.copy(nextcloudAccounts = accounts) }
            }
        }
        viewModelScope.launch {
            telegramChannelRepository.channels.collect { channels ->
                _uiState.update { it.copy(telegramChannels = channels) }
            }
        }
        viewModelScope.launch {
            com.beatraxus.app.drive.DrivePlaybackHelper.authRecoveryFlow.collect { intent ->
                _uiState.update { it.copy(authRecoveryIntent = intent) }
            }
        }
        viewModelScope.launch {
            com.beatraxus.app.drive.DrivePlaybackHelper.errorState.collect { error ->
                _uiState.update { it.copy(driveErrorMessage = error) }
            }
        }
        viewModelScope.launch {
            com.beatraxus.app.drive.DropboxPlaybackHelper.errorState.collect { error ->
                _uiState.update { it.copy(dropboxErrorMessage = error) }
            }
        }
        viewModelScope.launch {
            com.beatraxus.app.drive.OneDrivePlaybackHelper.errorState.collect { error ->
                _uiState.update { it.copy(onedriveErrorMessage = error) }
            }
        }
        viewModelScope.launch {
            com.beatraxus.app.drive.BoxPlaybackHelper.errorState.collect { error ->
                _uiState.update { it.copy(boxErrorMessage = error) }
            }
        }
        viewModelScope.launch {
            com.beatraxus.app.drive.NextcloudPlaybackHelper.errorState.collect { error ->
                _uiState.update { it.copy(nextcloudErrorMessage = error) }
            }
        }
        viewModelScope.launch {
            lastFmRepository.username.collect { name ->
                _uiState.update { it.copy(lastFmUsername = name) }
            }
        }

        // Auto-switch to COMBINED mode when first cloud account is added
        viewModelScope.launch {
            combine(
                driveAccountRepository.accounts,
                dropboxAccountRepository.accounts,
                onedriveAccountRepository.accounts,
                boxAccountRepository.accounts,
                nextcloudAccountRepository.accounts,
                telegramChannelRepository.channels
            ) { args ->
                val drive = args[0] as List<com.beatraxus.app.repository.DriveAccount>
                val dropbox = args[1] as List<com.beatraxus.app.repository.DropboxAccount>
                val onedrive = args[2] as List<com.beatraxus.app.repository.OneDriveAccount>
                val box = args[3] as List<com.beatraxus.app.repository.BoxAccount>
                val nextcloud = args[4] as List<com.beatraxus.app.repository.NextcloudAccount>
                val tg = args[5] as List<com.beatraxus.app.model.TelegramChannel>

                drive.isNotEmpty() || dropbox.isNotEmpty() || onedrive.isNotEmpty() ||
                        box.isNotEmpty() || nextcloud.isNotEmpty() || tg.isNotEmpty()
            }
                .distinctUntilChanged()
                .drop(1) // Skip initial value to avoid switching on app start if accounts already exist
                .collect { hasCloud ->
                    if (hasCloud && _uiState.value.libraryMode == LibraryMode.LOCAL) {
                        setLibraryMode(LibraryMode.COMBINED)
                    }
                }
        }

        // Cloud Library Counts (Reactive to selection)
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                allSongs,
                _uiState.map { it.selectedCloudEmail }.distinctUntilChanged(),
                _uiState.map { it.selectedTelegramChannelUrl }.distinctUntilChanged()
            ) { songs, email, telegramUrl ->
                songs.filter { s ->
                    s.isCloud() && when {
                        email != null -> s.driveAccountEmail == email ||
                                s.dropboxAccountEmail == email ||
                                s.onedriveAccountEmail == email ||
                                s.boxAccountEmail == email ||
                                s.nextcloudAccountEmail == email
                        telegramUrl != null -> s.telegramChannelUrl == telegramUrl
                        else -> true
                    }
                }
            }.collect { cloudSongs ->
                _uiState.update {
                    it.copy(
                        cloudSongCount = cloudSongs.size,
                        cloudAlbumCount = cloudSongs.map { s -> s.album }.toSet().size,
                        cloudArtistCount = cloudSongs.map { s -> s.artist }.toSet().size
                    )
                }
            }
        }

        // Network observer
        viewModelScope.launch {
            networkObserver.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
                if (!online && _uiState.value.isCloudScanning) {
                    val msg = "Sync paused: No internet connection"
                    _uiState.update { it.copy(
                        driveErrorMessage = if (it.selectedItemName != null) msg else it.driveErrorMessage,
                        telegramSyncErrorMessage = if (it.selectedTelegramChannelUrl != null) msg else it.telegramSyncErrorMessage
                    ) }
                } else if (online && (_uiState.value.driveErrorMessage?.contains("Sync paused") == true || _uiState.value.telegramSyncErrorMessage?.contains("Sync paused") == true)) {
                    val msg = "Network restored, continuing sync..."
                    _uiState.update { it.copy(
                        driveErrorMessage = if (it.driveErrorMessage?.contains("Sync paused") == true) msg else it.driveErrorMessage,
                        telegramSyncErrorMessage = if (it.telegramSyncErrorMessage?.contains("Sync paused") == true) msg else it.telegramSyncErrorMessage
                    ) }
                }
            }
        }

        // Background Video Observer
        viewModelScope.launch {
            while (isActive) {
                val active = prefs.getBoolean("bg_video_active", false)
                if (active != _uiState.value.bgVideoActive) {
                    val id = prefs.getString("bg_video_id", null)
                    val pos = prefs.getLong("bg_video_pos", 0L)
                    _uiState.update { it.copy(bgVideoActive = active, bgVideoId = id, bgVideoPos = pos) }
                    
                    if (active && id != null) {
                        val video = _videos.value.find { it.id == id }
                        if (video != null) {
                            val song = com.beatraxus.app.model.Song(
                                id = "video_${video.id}",
                                uri = video.uri,
                                title = video.title,
                                artist = video.folderPath.substringAfterLast("/"),
                                album = "Video Playback",
                                durationMs = video.durationMs,
                                format = video.mimeType.substringAfter("/").uppercase(),
                                sampleRateHz = 44100,
                                albumArtUri = video.thumbnailUri,
                                isFromVideo = true,
                                videoUri = video.uri
                            )
                            
                            // Transfer to audio player
                            while (service == null) delay(100)
                            service?.prepareSong(song, pos)
                            service?.togglePlayPause() // Start playing
                        }
                    }
                }
                delay(1000)
            }
        }

        checkBatteryOptimizations()

        // Observe Library Scanner state
        viewModelScope.launch {
            libraryScanner.isScanning.collect { value -> _uiState.update { it.copy(isScanning = value) } }
        }
        viewModelScope.launch {
            libraryScanner.scanProgress.collect { value -> _uiState.update { it.copy(scanProgress = value) } }
        }
        viewModelScope.launch {
            libraryScanner.scanCount.collect { value -> _uiState.update { it.copy(scanCount = value) } }
        }
        viewModelScope.launch {
            libraryScanner.albumCount.collect { value -> _uiState.update { it.copy(albumCount = value) } }
        }
        viewModelScope.launch {
            libraryScanner.artistCount.collect { value -> _uiState.update { it.copy(artistCount = value) } }
        }
        viewModelScope.launch {
            libraryScanner.isFullScanning.collect { value -> _uiState.update { it.copy(isFullScanning = value) } }
        }
        viewModelScope.launch {
            libraryScanner.errorMessage.collect { value -> if (value != null) _uiState.update { it.copy(errorMessage = value) } }
        }

        viewModelScope.launch {
            videos.collect { videos ->
                _uiState.update { it.copy(videos = videos) }
            }
        }
        viewModelScope.launch {
            videoFolders.collect { folders ->
                _uiState.update { it.copy(videoFolders = folders) }
            }
        }

        // Start level polling loop for spatial UI animations
        viewModelScope.launch {
            while (isActive) {
                val serviceRef = service
                if (serviceRef != null && uiState.value.isPlaying && uiState.value.dsp.config.audio3DStageEnabled) {
                    _realtimeLevels.value = serviceRef.getLevels()
                    delay(16) // ~60fps
                } else {
                    if (_realtimeLevels.value[0] != 0f || _realtimeLevels.value[1] != 0f) {
                        _realtimeLevels.value = floatArrayOf(0f, 0f)
                    }
                    delay(150)
                }
            }
        }
    }

    /**
     * Triggers AI Analysis and Year Enrichment for local songs that need it.
     * This is called manually when the user triggers a sync/scan, rather than automatically on startup.
     */
    fun triggerMetadataEnrichment() {
        // Trigger AI analysis for local songs with missing mood data OR a missing quality entity
        viewModelScope.launch(Dispatchers.Default) {
            val analyzed = aiAnalysisDao.getAllAnalysisFlow().first().associateBy { it.songId }
            val qualityDone = songQualityDao.getAllQualityFlow().first().associateBy { it.songId }
            _songs.value
                .filter {
                    it.source == SongSource.LOCAL &&
                            ((analyzed[it.id] == null || analyzed[it.id]?.moodTags.isNullOrBlank()) ||
                                    qualityDone[it.id] == null)
                }
                .forEach { aiAnalysisChannel.send(it) }
        }

        // Catch-up pass: enqueue any already-scanned song that's still missing a year
        viewModelScope.launch(Dispatchers.Default) {
            _songs.value.filter { it.source == SongSource.LOCAL && it.year == 0 }
                .forEach { yearEnrichmentChannel.send(it) }
        }
    }

    private fun checkBatteryOptimizations() {
        val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
        val packageName = getApplication<Application>().packageName
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)

        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val isOem = manufacturer.contains("oppo") ||
                manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") ||
                manufacturer.contains("oplus")

        _uiState.update { it.copy(
            isIgnoringBatteryOptimizations = ignoring,
            isOemBatteryManagerDetected = isOem
        ) }
    }

    fun requestIgnoreBatteryOptimizations() {
        val packageName = getApplication<Application>().packageName
        val intent = android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)

        // Refresh state after a delay as the user might have accepted
        viewModelScope.launch {
            delay(2000)
            checkBatteryOptimizations()
        }
    }

    fun openAppBatterySettings() {
        val packageName = getApplication<Application>().packageName
        val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    fun setErrorMessage(message: String?) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun setCastErrorMessage(message: String?) {
        _uiState.update { it.copy(castErrorMessage = message) }
    }


    fun consumeAuthRecoveryIntent() {
        _uiState.update { it.copy(authRecoveryIntent = null) }
    }

    fun consumeDeleteRequest() {
        _deleteRequest.value = null
    }

    fun onDeleteSuccess() {
        val ids = pendingDeleteIds
        if (ids.isEmpty()) return

        viewModelScope.launch {
            // Try deleting from both DAOs just in case
            songDao.deleteSongsByIds(ids)
            
            _songs.update { currentSongs ->
                currentSongs.filterNot { it.id in ids }
            }
            _videos.update { currentVideos ->
                currentVideos.filterNot { it.id in ids }
            }
            
            pendingDeleteIds = emptyList()
            setMultiSelectMode(false)

            // If the current song was deleted, skip it
            if (ids.contains(_uiState.value.currentSong?.id)) {
                skipToNext()
            }
            // Remove from queue
            ids.forEach { id -> service?.removeFromQueue(id) }
            
            // Refresh video folders if needed
            loadVideos() 
        }
    }

    fun setUseOriginalQualityArt(enabled: Boolean) {
        viewModelScope.launch {
            // 1. Update preferences and state synchronously for the next scan
            withContext(Dispatchers.IO) {
                prefs.edit().putBoolean("use_original_quality_art", enabled).commit()
            }
            _uiState.update { it.copy(useOriginalQualityArt = enabled) }

            // 2. Clear cached album art so it can be re-extracted with the new quality setting
            withContext(Dispatchers.IO) {
                try {
                    val artDir = File(getApplication<android.app.Application>().filesDir, "embedded_album_art")
                    if (artDir.exists()) {
                        artDir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear art cache", e)
                }
            }

            // 3. Force a full scan to re-cache images with new quality setting
            startFullScan()
        }
    }

    fun showScanOptions() {
        _uiState.update { it.copy(showScanOptions = true) }
    }

    /**
     * The startup library scan is heavy (it opens every changed audio file and decodes embedded
     * art). Starting it while the app-open screen is still animating starved the main thread and
     * froze the first seconds of the app, so it waits until that screen is gone plus 5 seconds.
     */
    private suspend fun awaitUiSettled(delayAfterMainScreenMs: Long = 5000L) {
        var waited = 0L
        while (!AppOpenSplashState.shown && waited < 10_000L) {
            delay(100)
            waited += 100
        }
        delay(delayAfterMainScreenMs)
    }

    fun loadLibrary() {
        if (libraryLoadJob?.isActive == true) return

        libraryLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(permissionDenied = false, isLoadingLibrary = true) }
            try {
                val dbSongs = withContext(Dispatchers.IO) {
                    songDao.getAllSongs().map { entity ->
                        Song(
                            id = entity.id,
                            uri = Uri.parse(entity.uriString),
                            title = entity.title,
                            artist = entity.artist,
                            album = entity.album,
                            durationMs = entity.durationMs,
                            format = entity.format,
                            sampleRateHz = entity.sampleRateHz,
                            bitDepth = entity.bitDepth,
                            bitrate = entity.bitrate,
                            fileSizeBytes = entity.fileSizeBytes,
                            albumArtUri = entity.albumArtUriString?.let { Uri.parse(it) },
                            year = entity.year,
                            genre = entity.genre,
                            albumArtist = entity.albumArtist,
                            composer = entity.composer,
                            trackNumber = entity.trackNumber,
                            discNumber = entity.discNumber,
                            lyrics = entity.lyrics,
                            folder = entity.folder,
                            dateAdded = entity.dateAdded,
                            replayGainTrackDb = entity.replayGainTrackDb,
                            replayGainAlbumDb = entity.replayGainAlbumDb,
                            replayGainTrackPeak = entity.replayGainTrackPeak,
                            replayGainAlbumPeak = entity.replayGainAlbumPeak,
                            // One row with an unknown source must not abort the whole library load.
                            source = runCatching { SongSource.valueOf(entity.source) }.getOrDefault(SongSource.LOCAL),
                            driveFileId = entity.driveFileId,
                            driveAccountEmail = entity.driveAccountEmail,
                            dropboxFileId = entity.dropboxFileId,
                            dropboxAccountEmail = entity.dropboxAccountEmail,
                            onedriveFileId = entity.onedriveFileId,
                            onedriveAccountEmail = entity.onedriveAccountEmail,
                            boxFileId = entity.boxFileId,
                            boxAccountEmail = entity.boxAccountEmail,
                            nextcloudFileId = entity.nextcloudFileId,
                            nextcloudAccountEmail = entity.nextcloudAccountEmail,
                            telegramChannelUrl = entity.telegramChannelUrl,
                            telegramChatId = entity.telegramChatId,
                            telegramMessageId = entity.telegramMessageId,
                            telegramFileId = entity.telegramFileId,
                            isEnriched = entity.isEnriched,
                            albumArtFetchAttempted = entity.albumArtFetchAttempted,
                            lastSyncTimestamp = entity.lastSyncTimestamp,
                            lyricsOffsetMs = entity.lyricsOffsetMs,
                            isFromVideo = entity.isFromVideo,
                            videoUri = entity.videoUriString?.let { Uri.parse(it) }
                        )
                    }
                }

                if (_uiState.value.isFirstRun) {
                    if (dbSongs.isNotEmpty()) {
                        setFirstRunComplete()
                    } else {
                        _uiState.update {
                            if (it.isScanning) it.copy(isLoadingLibrary = false)
                            else it.copy(isLoadingLibrary = false, showScanOptions = true)
                        }
                        return@launch
                    }
                }

                if (dbSongs.isNotEmpty()) {
                    val sortedSongs = withContext(Dispatchers.Default) {
                        dbSongs.sortedBy { it.title }
                    }

                    // Check if cached album art still exists. If not, we need a refresh.
                    // File.exists() for every song: keep it off the main thread (this coroutine runs on it).
                    val cacheWiped = withContext(Dispatchers.IO) {
                        dbSongs.any { song ->
                            val artUri = song.albumArtUri
                            if (artUri != null && artUri.scheme == "file") {
                                val exists = File(artUri.path ?: "").exists()
                                if (!exists) Log.d("PlayerViewModel", "Cache wipe detected for song: ${song.title}")
                                !exists
                            } else false
                        }
                    }

                    _songs.value = sortedSongs

                    // Restore last queue and playing song
                    val lastSongId = prefs?.getString("last_song_id", null)
                    val lastQueueIds = prefs?.getString("last_queue_ids", null)?.split(",")?.filter { it.isNotBlank() }
                    val lastOriginalQueueIds = prefs?.getString("last_original_queue_ids", null)?.split(",")?.filter { it.isNotBlank() }
                    val lastIndex = prefs?.getInt("last_queue_index", -1) ?: -1
                    val lastPos = 0L // Start song from beginning on app open as requested by user

                    if (!lastQueueIds.isNullOrEmpty()) {
                        val songMap = dbSongs.associateBy { it.id }
                        val restoredPlaylist = lastQueueIds.mapNotNull { id -> songMap[id] }
                        val restoredOriginalPlaylist = lastOriginalQueueIds?.mapNotNull { id -> songMap[id] } ?: restoredPlaylist

                        if (restoredPlaylist.isNotEmpty()) {
                            viewModelScope.launch {
                                // Wait for service to be ready. 
                                // We no longer block on TDLib readiness here as TelegramFileDataSource 
                                // handles its own wait state internally now.
                                if (!awaitService()) return@launch

                                // Ensure currently playing ID is set for cache exclusion BEFORE restore
                                if (lastIndex in restoredPlaylist.indices) {
                                    cloudCacheManager.setCurrentlyPlayingId(restoredPlaylist[lastIndex].id)
                                } else if (lastSongId != null) {
                                    cloudCacheManager.setCurrentlyPlayingId(lastSongId)
                                }

                                service?.restorePlaylist(restoredPlaylist, restoredOriginalPlaylist, lastIndex, lastPos)
                                _progressMs.value = lastPos
                            }
                        }
                    } else if (lastSongId != null) {
                        // Fallback for older versions that only saved last_song_id
                        dbSongs.find { it.id == lastSongId }?.let { lastSong ->
                            viewModelScope.launch {
                                if (!awaitService()) return@launch
                                cloudCacheManager.setCurrentlyPlayingId(lastSong.id)
                                service?.prepareSong(lastSong, lastPos)
                                _progressMs.value = lastPos
                            }
                        }
                    }

                    if (cacheWiped) {
                        // This branch used to return without ever clearing isLoadingLibrary, which
                        // left the splash screen up forever after the album-art cache was wiped.
                        _uiState.update { it.copy(isLoadingLibrary = false) }
                        return@launch
                    }

                    // Loaded from the DB only: scans run solely when the user taps Full/Quick scan.
                    _uiState.update { it.copy(isLoadingLibrary = false) }
                    return@launch
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadLibrary failed", e)
            } finally {
                // Every exit path (empty library, permission denied, DB error, cancellation) must
                // release the splash screen, which MainActivity keeps up while this flag is true.
                _uiState.update { if (it.isLoadingLibrary) it.copy(isLoadingLibrary = false) else it }
            }

        }
    }



    fun loadVideos(targetFolders: List<String>? = null, forceImmediate: Boolean = false) {
        enrichmentJob?.cancel()

        enrichmentJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingVideos = true) }
            try {
                val blocked = musicRepository.getBlockedFolders()
                val folders = targetFolders ?: withContext(Dispatchers.IO) {
                    videoFolderDao.getActiveFoldersList().map { it.path }
                }

                val scannedVideos = videoScanner.scanVideos(folders, blocked)

                _videos.value = scannedVideos
                _uiState.update { it.copy(isLoadingVideos = false) }

                // Delay thumbnail and HDR extraction until UI settles
                if (!forceImmediate) {
                    awaitUiSettled(2000L)
                }

                // Background enrichment (Local Thumbnails + HDR)
                val context = getApplication<Application>()
                val semaphore = Semaphore(3) // Process 3 at a time

                scannedVideos.forEach { video ->
                    launch(Dispatchers.Default) {
                        semaphore.withPermit {
                            // Don't enrich while playback is active to avoid MediaCodec contention
                            while (PlaybackGlobalState.isAnyPlaybackActive.value) {
                                delay(2000)
                            }

                            val result = com.beatraxus.app.utils.VideoThumbnailHelper.enrichVideo(context, video.uri, video.id)

                            if (result.thumbnailUri != null || result.isHdr) {
                                _videos.update { current ->
                                    current.map {
                                        if (it.id == video.id) it.copy(
                                            thumbnailUri = result.thumbnailUri ?: it.thumbnailUri,
                                            isHdr = result.isHdr
                                        ) else it
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load videos", e)
                _uiState.update { it.copy(isLoadingVideos = false) }
            }
        }
    }

    fun reloadVideosIfEmpty() {
        if (_uiState.value.playbackMode == PlaybackMode.VIDEO && _videos.value.isEmpty()) {
            loadVideos()
        }
    }

    /** Pull-to-refresh for the video library: rescans local folders and re-reads the cloud sources. */
    fun refreshVideos() {
        loadVideos(forceImmediate = true)
        refreshTelegramVideos(force = true)
        refreshCloudVideos(force = true)
    }

    /** Called by the source chips. Pass the already-selected source (or null) to show all sources again. */
    fun setVideoSourceFilter(source: VideoSource?) {
        _uiState.update { it.copy(selectedVideoSource = source) }
    }

    private var telegramVideoJob: Job? = null
    private var lastTelegramVideoRefresh = 0L

    /** Saves the Telegram and cloud-drive videos (each repository keeps only its own). Blocking: call off the main thread. */
    private fun persistCloudVideos() {
        val snapshot = _cloudVideos.value
        telegramVideoRepository.saveCached(snapshot)
        cloudVideoRepository.saveCached(snapshot)
    }

    /** Replaces a cloud video by its improved version (new thumbnail / runtime found in the background). */
    private fun applyCloudVideoUpdate(updated: Video) {
        _cloudVideos.update { current -> current.map { if (it.id == updated.id) updated else it } }
    }

    /**
     * Heavy background work (reading a few MB of each video to get a frame / runtime) follows the same
     * network rule as the cloud metadata sync, so it doesn't use mobile data unless the user allowed it.
     */
    private fun heavyVideoWorkAllowed(): Boolean =
        com.beatraxus.app.util.NetworkUtils.isNetworkAllowed(getApplication<Application>(), _uiState.value.metadataNetworkType)

    /**
     * Looks for videos in the enabled Telegram channels and merges them into the cloud library.
     * Silent when Telegram isn't logged in yet (the cached list stays), and throttled unless [force].
     *
     * The list shows up right away; thumbnails and runtimes are fetched afterwards (with retries)
     * and appear one by one.
     */
    fun refreshTelegramVideos(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTelegramVideoRefresh < TELEGRAM_VIDEO_REFRESH_MIN_MS) return
        if (!tdLibManager.isReady()) return
        lastTelegramVideoRefresh = now

        telegramVideoJob?.cancel()
        telegramVideoJob = viewModelScope.launch(Dispatchers.IO) {
            val channels = telegramChannelRepository.channels.first().filter { it.enabled }
            val enabledUrls = channels.map { it.url.trim().removeSuffix("/") }
            val scans = mutableListOf<com.beatraxus.app.repository.TelegramVideoRepository.ChannelScan>()
            for (url in enabledUrls) {
                val known = _cloudVideos.value.filter { it.source == VideoSource.TELEGRAM && it.sourceId == url }
                val scan = try {
                    telegramVideoRepository.scanChannel(tdLibManager, url, known.associateBy { it.id })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Telegram video scan failed for $url", e)
                    null
                } ?: continue // couldn't read the channel: keep what we already have

                _cloudVideos.update { current ->
                    current.filterNot { it.source == VideoSource.TELEGRAM && it.sourceId == url } + scan.videos
                }
                telegramVideoRepository.saveCached(_cloudVideos.value)
                scans.add(scan)
            }

            try {
                for (scan in scans) {
                    telegramVideoRepository.enrich(
                        tdLib = tdLibManager,
                        scan = scan,
                        onUpdated = { applyCloudVideoUpdate(it) },
                        allowHeavy = heavyVideoWorkAllowed(),
                        isPlaybackActive = { PlaybackGlobalState.isAnyPlaybackActive.value }
                    )
                }
            } finally {
                // Keep what was found even when this refresh is cancelled half way.
                withContext(kotlinx.coroutines.NonCancellable) { persistCloudVideos() }
            }
        }
    }

    private var cloudVideoJob: Job? = null
    private var lastCloudVideoRefresh = 0L

    /** Cloud-drive videos are only listed while the video library is on screen in Cloud / Combined mode. */
    private fun maybeRefreshCloudVideos(force: Boolean = false) {
        val state = _uiState.value
        if (state.playbackMode != PlaybackMode.VIDEO || state.libraryMode == LibraryMode.LOCAL) return
        refreshCloudVideos(force)
    }

    /**
     * Lists the videos of the connected cloud drives (Google Drive, Dropbox, OneDrive, Box, Nextcloud) and
     * merges them into the cloud library. Silent on failure (the cached list stays), throttled unless [force].
     * Thumbnails and runtimes are filled in afterwards.
     */
    fun refreshCloudVideos(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCloudVideoRefresh < CLOUD_VIDEO_REFRESH_MIN_MS) return
        lastCloudVideoRefresh = now

        cloudVideoJob?.cancel()
        cloudVideoJob = viewModelScope.launch(Dispatchers.IO) {
            val accounts = cloudVideoRepository.connectedAccounts().filter { it.enabled }
            if (accounts.isEmpty()) return@launch

            val scanned = mutableListOf<Video>()
            for (account in accounts) {
                val known = _cloudVideos.value.filter { it.source == account.source && it.sourceId == account.key }
                val found = cloudVideoRepository.scan(account, known.associateBy { it.id })
                    ?: continue // couldn't list this account: keep what we already have

                _cloudVideos.update { current ->
                    current.filterNot { it.source == account.source && it.sourceId == account.key } + found
                }
                cloudVideoRepository.saveCached(_cloudVideos.value)
                scanned.addAll(found)
            }

            try {
                cloudVideoRepository.enrich(
                    videos = scanned,
                    onUpdated = { applyCloudVideoUpdate(it) },
                    allowHeavy = heavyVideoWorkAllowed(),
                    isPlaybackActive = { PlaybackGlobalState.isAnyPlaybackActive.value }
                )
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { cloudVideoRepository.saveCached(_cloudVideos.value) }
            }
        }
    }

    private var service: AudioPlaybackService? = null

    /** Waits for the playback service to bind, giving up after [timeoutMs] instead of spinning forever. */
    private suspend fun awaitService(timeoutMs: Long = 20_000L): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (service == null) delay(200)
            true
        } ?: false

    fun attachService(svc: AudioPlaybackService) {
        if (service === svc) return
        service = svc

        svc.updateDspConfig(_uiState.value.dsp.config)
        svc.setOutputMode(OutputMode.fromName(_uiState.value.outputMode))
        serviceObserversJob?.cancel()
        serviceObserversJob = viewModelScope.launch {
            launch {
                svc.audioStateFlow.collect { audioState ->
                    _uiState.update {
                        // Only update if the engine is reporting for the same song we think is current
                        if (audioState.songId != null && audioState.songId != it.currentSong?.id) {
                            return@update it
                        }
                        it.copy(
                            inputSampleRate = audioState.sampleRate,
                            outputSampleRate = audioState.outputSampleRate,
                            outputBitDepth = audioState.outputBitDepth,
                            bitDepth = if (audioState.bitDepth > 0) audioState.bitDepth else it.currentSong?.bitDepth ?: 16,
                            bitrate = if (audioState.bitrate > 0) audioState.bitrate else it.currentSong?.bitrate ?: 0,
                            format = audioState.codec.ifBlank { it.currentSong?.format ?: "" },
                            outputDevice = audioState.outputDevice,
                            pipelineOutputPath = audioState.outputPath,
                            pipelineDvcEnabled = audioState.dynamicVolumeControlActive,
                            pipelineResamplerEnabled = audioState.resamplerActive,
                            pipelineResamplerType = audioState.resamplerType,
                            pipelineActiveEffects = audioState.activeEffects,
                            pipelineSummary = audioState.pipelineSummary,
                            autoEqProfileName = audioState.autoEqProfileName,
                            underrunCount = audioState.underrunCount,
                            dsp = it.dsp.copy(
                                currentHeadroomDb = audioState.headroomDb,
                                currentLatencyFrames = audioState.latencyFrames,
                                currentDitherType = audioState.ditherType,
                                currentEqMode = audioState.eqMode,
                                surroundOutputChannels = audioState.outputChannels,
                                surroundFallbackActive = audioState.surroundFallback
                            )
                        )
                    }
                }
            }
            launch {
                svc.playbackStateFlow.collect { pbState ->
                    val prevSongId = _uiState.value.currentSong?.id
                    val nextSongId = pbState.currentSong?.id
                    val resetProgress = nextSongId == null || nextSongId != prevSongId

                    // Reset progress BEFORE updating UI state to avoid race condition
                    if (resetProgress) {
                        _progressMs.value = 0L
                    }

                    _uiState.update {
                        val sameSong = it.currentSong?.id == pbState.currentSong?.id
                        if (!sameSong) {
                            cloudCacheManager.setCurrentlyPlayingId(pbState.currentSong?.id)
                            // Fetch markers and loudness for the new song
                            pbState.currentSong?.id?.let { songId ->
                                viewModelScope.launch {
                                    combine(
                                        chapterRepository.getChapters(songId),
                                        highlightRepository.getHighlights(songId),
                                        bookmarkRepository.getBookmarks(songId)
                                    ) { c, h, b ->
                                        Triple(c, h, b)
                                    }.collect { (c, h, b) ->
                                        _uiState.update { state ->
                                            state.copy(chapters = c, highlights = h, bookmarks = b)
                                        }
                                    }
                                }
                                viewModelScope.launch {
                                    val loudness = loudnessRepository.getLoudness(songId)
                                    _uiState.update { state ->
                                        state.copy(loudnessData = loudness?.data)
                                    }
                                }
                            }
                        }

                        it.copy(
                            isPlaying = pbState.isPlaying,
                            currentSong = pbState.currentSong,
                            shuffleMode = pbState.shuffleMode,
                            repeatMode = pbState.repeatMode.ordinal,
                            // If it's a new song, we can't trust 'it.bitrate' etc. yet as they might belong to the previous song.
                            // But if the engine has already updated for the new song, we should keep it.
                            bitrate = if (sameSong) (if (it.bitrate > 0) it.bitrate else pbState.currentSong?.bitrate ?: 0) else pbState.currentSong?.bitrate ?: 0,
                            format = if (sameSong) it.format.ifBlank { pbState.currentSong?.format ?: "" } else pbState.currentSong?.format ?: "",
                            bitDepth = if (sameSong) it.bitDepth else pbState.currentSong?.bitDepth ?: 16,
                            inputSampleRate = if (sameSong) it.inputSampleRate else pbState.currentSong?.sampleRateHz ?: 44100
                        )
                    }

                    PlaybackGlobalState.setPlaybackActive(pbState.isPlaying)

                    if (resetProgress) {
                        if (pbState.currentSong != null) {
                            if (!pbState.currentSong.isFromVideo) {
                                prefs.edit().putBoolean("bg_video_active", false).apply()
                            }
                            updateRecentlyPlayed(pbState.currentSong)
                            handleSongChangeForSleepTimer(pbState.currentSong)
                            fetchOnlineInfo(pbState.currentSong)
                            loadLyrics(pbState.currentSong)
                            // Ensure preloading is triggered on song change
                            service?.let { svc ->
                                preloadUpcomingLyrics(svc.getUpcomingSongs().take(15))
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    lyrics = emptyList(),
                                    lyricsCurrentIndex = -1,
                                    lyricsCurrentSongId = null,
                                    lastFmTrackInfo = null,
                                    lastFmArtistInfo = null,
                                    lastFmAlbumInfo = null
                                )
                            }
                        }
                    }

                    if (pbState.isPlaying) startProgressPolling() else stopProgressPolling()
                }
            }
            launch {
                svc.upcomingSongs.collect { songs ->
                    _uiState.update { it.copy(upcomingSongs = songs) }
                    if (songs.isNotEmpty()) {
                        preloadUpcomingLyrics(songs.take(15))
                    }
                }
            }
            launch {
                svc.previousSongs.collect { songs ->
                    _uiState.update { it.copy(previousSongs = songs) }
                }
            }
            launch {
                networkObserver.isOnline.collect { online ->
                    _uiState.update { it.copy(isOnline = online) }
                }
            }
            launch {
                svc.outputRouteStateFlow.collect { routeState ->
                    _uiState.update {
                        it.copy(
                            outputMode = routeState.selectedMode.name,
                            outputDevice = routeState.outputDevice,
                            hiResDirectSupported = routeState.hiResDirectSupported,
                            hiResCapabilitySummary = routeState.capabilitySummary,
                            usbExclusiveActive = routeState.usbExclusiveActive,
                            usbDeviceName = routeState.usbDeviceName,
                            outputMaxSampleRate = routeState.deviceMaxSampleRate,
                            outputMaxBitDepth = routeState.deviceMaxBitDepth
                        )
                    }
                }
            }
        }
    }


    fun removeDriveAccount(email: String) {
        viewModelScope.launch {
            cloudAccountManager.removeDriveAccount(email) { removedIds ->
                _songs.update { it.filterNot { s -> s.id in removedIds } }
            }
        }
    }

    fun removeDropboxAccount(email: String) {
        viewModelScope.launch {
            cloudAccountManager.removeDropboxAccount(email) { removedIds ->
                _songs.update { it.filterNot { s -> s.id in removedIds } }
            }
        }
    }

    fun removeOneDriveAccount(email: String) {
        viewModelScope.launch {
            cloudAccountManager.removeOneDriveAccount(email) { removedIds ->
                _songs.update { it.filterNot { s -> s.id in removedIds } }
            }
        }
    }

    fun removeBoxAccount(email: String) {
        viewModelScope.launch {
            cloudAccountManager.removeBoxAccount(email) { removedIds ->
                _songs.update { it.filterNot { s -> s.id in removedIds } }
            }
        }
    }

    fun removeNextcloudAccount(serverUrl: String, username: String) {
        viewModelScope.launch {
            cloudAccountManager.removeNextcloudAccount(serverUrl, username) { removedIds ->
                _songs.update { it.filterNot { s -> s.id in removedIds } }
            }
        }
    }

    fun removeTelegramChannel(url: String) {
        viewModelScope.launch {
            cloudAccountManager.removeTelegramChannel(url) { removedIds ->
                _songs.update { it.filterNot { s -> s.id in removedIds } }
            }
        }
    }

    // Removed cleanupCloudAccountData as it's now in cloudAccountManager

    private val enrichmentJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val activeEnrichmentCount = AtomicInteger(0)

    private fun incrementEnrichment() {
        activeEnrichmentCount.incrementAndGet()
        _uiState.update { it.copy(isCloudScanning = true, showSyncStatusOnHome = true, isSyncFinishedRecently = false) }
    }

    private fun decrementEnrichment() {
        if (activeEnrichmentCount.decrementAndGet() <= 0) {
            activeEnrichmentCount.set(0)
            _uiState.update { it.copy(
                isCloudScanning = false,
                scanProgress = 1f,
                enrichmentStatus = null
            ) }
        }
    }

    fun scanDriveAccount(email: String) {
        val svc = service ?: return
        val networkType = _uiState.value.metadataNetworkType
        val context = getApplication<Application>()

        if (networkType == com.beatraxus.app.model.NetworkType.ASK_MOBILE &&
            com.beatraxus.app.util.NetworkUtils.isMobileConnected(context) &&
            !com.beatraxus.app.util.NetworkUtils.isWifiConnected(context)) {
            _uiState.update { it.copy(driveErrorMessage = "Confirmation needed: Use mobile data for sync?") }
            return
        }

        if (!com.beatraxus.app.util.NetworkUtils.isNetworkAllowed(context, networkType)) {
            _uiState.update { it.copy(driveErrorMessage = "Waiting for allowed network (Rule: $networkType)") }
            return
        }

        incrementEnrichment()
        _uiState.update { it.copy(scanProgress = 0f, driveErrorMessage = "Drive scan queued...") }

        svc.runDriveScan(
            email = email,
            allowedFormats = _uiState.value.gdriveAllowedFormats,
            onProgress = { progress ->
                _uiState.update { it.copy(scanProgress = progress) }
            },
            onDiscoveryComplete = { discoveredSongs ->
                _songs.update { current ->
                    val discoveredIds = discoveredSongs.map { it.id }.toSet()
                    val unchanged = current.filter { it.id !in discoveredIds }
                    (unchanged + discoveredSongs).sortedBy { it.title }
                }
            },
            onEnrichmentProgress = { progress, current, total ->
                // service handles notification, we handle UI
            },
            onStatusUpdate = { status ->
                _uiState.update { it.copy(enrichmentStatus = status) }
            },
            onSongUpdated = { updatedSong ->
                // AI Analysis for cloud song after enrichment
                viewModelScope.launch(Dispatchers.Default) {
                    aiAnalysisChannel.send(updatedSong)
                    if (updatedSong.year == 0) yearEnrichmentChannel.send(updatedSong)
                }

                _songs.update { current ->
                    if (current.any { it.id == updatedSong.id }) {
                        current.map { if (it.id == updatedSong.id) updatedSong else it }
                    } else {
                        (current + updatedSong).sortedBy { it.title }
                    }
                }
            },
            onComplete = { message ->
                _uiState.update { it.copy(driveErrorMessage = message, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            },
            onError = { error, intent ->
                if (intent != null) {
                    _uiState.update { it.copy(authRecoveryIntent = intent, enrichmentStatus = null) }
                } else {
                    _uiState.update { it.copy(driveErrorMessage = "Drive scan failed: $error", enrichmentStatus = null, isSyncFinishedRecently = true) }
                    startSyncDismissTimer()
                }
                decrementEnrichment()
            }
        )
    }

    fun addDriveAccount(account: DriveAccount) {
        viewModelScope.launch {
            driveAccountRepository.addAccount(account)
            // Trigger an initial scan for the new account
            scanDriveAccount(account.email)
        }
    }

    fun toggleDriveAccountEnabled(email: String, enabled: Boolean) {
        viewModelScope.launch {
            driveAccountRepository.updateAccountEnabled(email, enabled)
        }
    }

    fun startDropboxLogin(context: android.content.Context, preferGoogle: Boolean = false) {
        val appKey = com.beatraxus.app.BuildConfig.DROPBOX_APP_KEY
        if (appKey.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Dropbox App Key is not configured") }
            return
        }
        // Dropbox authenticates every method (email/password, Google SSO, Apple SSO)
        // through its own hosted authorize page — there is no separate native API to
        // sign in with a Google account directly the way GDrive's SDK allows. When the
        // user picks "Continue with Google" we still launch the same OAuth flow, but
        // pass a hint so Dropbox's page can jump straight to its Google button instead
        // of showing the email/password form first.
        try {
            com.dropbox.core.android.Auth.startOAuth2Authentication(context, appKey)
        } catch (e: Exception) {
            Log.e(TAG, "Dropbox auth launch failed", e)
            _uiState.update { it.copy(errorMessage = "Could not open Dropbox sign-in: ${e.message}") }
        }
    }

    private var lastProcessedDropboxToken: String? = null

    fun handleDropboxAuth() {
        val token = com.dropbox.core.android.Auth.getOAuth2Token()
        // Auth.getOAuth2Token() keeps returning the same cached token on every
        // onResume() (app reopen, alt-tab back), not just right after the OAuth
        // redirect. Without this guard, every resume re-adds the account and
        // re-runs a full enrichment scan.
        if (token != null && token != lastProcessedDropboxToken) {
            lastProcessedDropboxToken = token
            viewModelScope.launch {
                try {
                    val client = com.dropbox.core.v2.DbxClientV2(
                        com.dropbox.core.DbxRequestConfig.newBuilder("Beatraxus").build(),
                        token
                    )
                    val account = withContext(Dispatchers.IO) { client.users().currentAccount }
                    val dropboxAccount = com.beatraxus.app.repository.DropboxAccount(
                        email = account.email,
                        accountName = account.name.displayName,
                        photoUrl = account.profilePhotoUrl,
                        enabled = true
                    )
                    val credential = com.dropbox.core.oauth.DbxCredential(token)
                    val credentialJson = com.dropbox.core.oauth.DbxCredential.Writer.writeToString(credential)
                    dropboxAccountRepository.addAccount(dropboxAccount, credentialJson)
                    scanDropboxAccount(dropboxAccount.email)
                } catch (e: Exception) {
                    Log.e(TAG, "Dropbox login failed", e)
                    _uiState.update { it.copy(errorMessage = "Dropbox login failed: ${e.message}") }
                }
            }
        }
    }

    fun scanDropboxAccount(email: String) {
        val svc = service ?: return
        val account = _uiState.value.dropboxAccounts.find { it.email == email } ?: return

        incrementEnrichment()
        _uiState.update { it.copy(scanProgress = 0f, dropboxErrorMessage = "Dropbox scan queued...") }

        svc.runDropboxScan(
            account = account,
            allowedFormats = _uiState.value.gdriveAllowedFormats, // Reuse GDrive formats for now
            onProgress = { progress -> _uiState.update { it.copy(scanProgress = progress) } },
            onDiscoveryComplete = { discovered ->
                _songs.update { current ->
                    val discoveredIds = discovered.map { it.id }.toSet()
                    (current.filter { it.id !in discoveredIds } + discovered).sortedBy { it.title }
                }
            },
            onEnrichmentProgress = { progress, current, total ->
                // service handles notification, we handle UI status if needed
            },
            onStatusUpdate = { status ->
                _uiState.update { it.copy(enrichmentStatus = status) }
            },
            onSongUpdated = { updated ->
                // AI Analysis for cloud song after enrichment
                viewModelScope.launch(Dispatchers.Default) {
                    aiAnalysisChannel.send(updated)
                    if (updated.year == 0) yearEnrichmentChannel.send(updated)
                }

                _songs.update { current ->
                    current.map { if (it.id == updated.id) updated else it }
                }
            },
            onComplete = { msg ->
                _uiState.update { it.copy(dropboxErrorMessage = msg, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            },
            onError = { err ->
                _uiState.update { it.copy(dropboxErrorMessage = "Dropbox scan failed: $err", enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            }
        )
    }

    private var onedriveAuthClient: com.microsoft.identity.client.ISingleAccountPublicClientApplication? = null

    fun startOneDriveLogin(activity: android.app.Activity) {
        viewModelScope.launch {
            try {
                if (onedriveAuthClient == null) {
                    onedriveAuthClient = com.microsoft.identity.client.PublicClientApplication.createSingleAccountPublicClientApplication(
                        activity,
                        com.beatraxus.app.R.raw.msal_config
                    )
                }

                onedriveAuthClient?.signIn(activity, null, arrayOf("Files.Read", "User.Read"), object : com.microsoft.identity.client.AuthenticationCallback {
                    override fun onSuccess(authenticationResult: com.microsoft.identity.client.IAuthenticationResult) {
                        viewModelScope.launch {
                            val account = authenticationResult.account
                            val onedriveAccount = com.beatraxus.app.repository.OneDriveAccount(
                                email = account.username,
                                accountName = account.username,
                                photoUrl = null,
                                tenantId = account.tenantId,
                                enabled = true
                            )
                            onedriveAccountRepository.addAccount(onedriveAccount)
                            scanOneDriveAccount(onedriveAccount.email)
                        }
                    }

                    override fun onError(exception: com.microsoft.identity.client.exception.MsalException) {
                        _uiState.update { it.copy(errorMessage = "OneDrive login failed: ${exception.message}") }
                    }

                    override fun onCancel() {
                        Log.d(TAG, "OneDrive login cancelled")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "OneDrive init failed", e)
                _uiState.update { it.copy(errorMessage = "OneDrive init failed: ${e.message}") }
            }
        }
    }

    fun scanOneDriveAccount(email: String) {
        val svc = service ?: return
        val account = _uiState.value.onedriveAccounts.find { it.email == email } ?: return

        incrementEnrichment()
        _uiState.update { it.copy(scanProgress = 0f, onedriveErrorMessage = "OneDrive scan queued...") }

        svc.runOneDriveScan(
            account = account,
            allowedFormats = _uiState.value.gdriveAllowedFormats,
            onProgress = { progress -> _uiState.update { it.copy(scanProgress = progress) } },
            onDiscoveryComplete = { discovered ->
                _songs.update { current ->
                    val discoveredIds = discovered.map { it.id }.toSet()
                    (current.filter { it.id !in discoveredIds } + discovered).sortedBy { it.title }
                }
            },
            onEnrichmentProgress = { progress, current, total ->
                // service handles notification, we handle UI status if needed
            },
            onStatusUpdate = { status ->
                _uiState.update { it.copy(enrichmentStatus = status) }
            },
            onSongUpdated = { updated ->
                // AI Analysis for cloud song after enrichment
                viewModelScope.launch(Dispatchers.Default) {
                    aiAnalysisChannel.send(updated)
                    if (updated.year == 0) yearEnrichmentChannel.send(updated)
                }

                _songs.update { current ->
                    current.map { if (it.id == updated.id) updated else it }
                }
            },
            onComplete = { msg ->
                _uiState.update { it.copy(onedriveErrorMessage = msg, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            },
            onError = { err ->
                _uiState.update { it.copy(onedriveErrorMessage = "OneDrive scan failed: $err", enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            }
        )
    }

    fun startBoxLogin(activity: android.app.Activity) {
        val clientId = com.beatraxus.app.BuildConfig.BOX_CLIENT_ID
        val clientSecret = com.beatraxus.app.BuildConfig.BOX_CLIENT_SECRET
        if (clientId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Box Client ID is not configured") }
            return
        }

        // Initialize BoxConfig
        com.box.androidsdk.content.BoxConfig.CLIENT_ID = clientId
        com.box.androidsdk.content.BoxConfig.CLIENT_SECRET = clientSecret

        val session = com.box.androidsdk.content.models.BoxSession(activity)
        session.authenticate().addOnCompletedListener {
            if (it.isSuccess) {
                viewModelScope.launch {
                    try {
                        val user = withContext(Dispatchers.IO) {
                            com.box.androidsdk.content.BoxApiUser(session).currentUserInfoRequest.send()
                        }
                        val boxUser = user as com.box.androidsdk.content.models.BoxUser
                        val boxAccount = com.beatraxus.app.repository.BoxAccount(
                            email = boxUser.login,
                            accountName = boxUser.name ?: "Box User",
                            photoUrl = null,
                            userId = boxUser.id,
                            enabled = true
                        )
                        boxAccountRepository.addAccount(boxAccount)
                        scanBoxAccount(boxAccount.email)
                    } catch (e: Exception) {
                        Log.e(TAG, "Box user fetch failed", e)
                    }
                }
            } else {
                _uiState.update { it.copy(errorMessage = "Box login failed") }
            }
        }
    }

    fun scanBoxAccount(email: String) {
        val svc = service ?: return
        val account = _uiState.value.boxAccounts.find { it.email == email } ?: return

        incrementEnrichment()
        _uiState.update { it.copy(scanProgress = 0f, boxErrorMessage = "Box scan queued...") }

        svc.runBoxScan(
            account = account,
            allowedFormats = _uiState.value.gdriveAllowedFormats,
            onProgress = { progress -> _uiState.update { it.copy(scanProgress = progress) } },
            onDiscoveryComplete = { discovered ->
                _songs.update { current ->
                    val discoveredIds = discovered.map { it.id }.toSet()
                    (current.filter { it.id !in discoveredIds } + discovered).sortedBy { it.title }
                }
            },
            onEnrichmentProgress = { progress, current, total ->
                // service handles notification, we handle UI status if needed
            },
            onStatusUpdate = { status ->
                _uiState.update { it.copy(enrichmentStatus = status) }
            },
            onSongUpdated = { updated ->
                // AI Analysis for cloud song after enrichment
                viewModelScope.launch(Dispatchers.Default) {
                    aiAnalysisChannel.send(updated)
                    if (updated.year == 0) yearEnrichmentChannel.send(updated)
                }

                _songs.update { current ->
                    current.map { if (it.id == updated.id) updated else it }
                }
            },
            onComplete = { msg ->
                _uiState.update { it.copy(boxErrorMessage = msg, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            },
            onError = { err ->
                _uiState.update { it.copy(boxErrorMessage = "Box scan failed: $err", enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            }
        )
    }

    fun addNextcloudAccount(server: String, user: String, pass: String) {
        viewModelScope.launch {
            val nextcloudAccount = com.beatraxus.app.repository.NextcloudAccount(
                serverUrl = server,
                username = user,
                appPassword = pass,
                displayName = "$user @ ${server.substringAfter("://").substringBefore("/")}",
                enabled = true
            )
            nextcloudAccountRepository.addAccount(nextcloudAccount)
            scanNextcloudAccount(nextcloudAccount.serverUrl, nextcloudAccount.username)
        }
    }

    fun scanNextcloudAccount(serverUrl: String, username: String) {
        val svc = service ?: return
        val account = _uiState.value.nextcloudAccounts.find { it.serverUrl == serverUrl && it.username == username } ?: return

        incrementEnrichment()
        _uiState.update { it.copy(scanProgress = 0f, nextcloudErrorMessage = "Nextcloud scan queued...") }

        svc.runNextcloudScan(
            account = account,
            allowedFormats = _uiState.value.gdriveAllowedFormats,
            onProgress = { progress -> _uiState.update { it.copy(scanProgress = progress) } },
            onDiscoveryComplete = { discovered ->
                _songs.update { current ->
                    val discoveredIds = discovered.map { it.id }.toSet()
                    (current.filter { it.id !in discoveredIds } + discovered).sortedBy { it.title }
                }
            },
            onEnrichmentProgress = { progress, current, total ->
                // service handles notification, we handle UI status if needed
            },
            onStatusUpdate = { status ->
                _uiState.update { it.copy(enrichmentStatus = status) }
            },
            onSongUpdated = { updated ->
                // AI Analysis for cloud song after enrichment
                viewModelScope.launch(Dispatchers.Default) {
                    aiAnalysisChannel.send(updated)
                    if (updated.year == 0) yearEnrichmentChannel.send(updated)
                }

                _songs.update { current ->
                    current.map { if (it.id == updated.id) updated else it }
                }
            },
            onComplete = { msg ->
                _uiState.update { it.copy(nextcloudErrorMessage = msg, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            },
            onError = { err ->
                _uiState.update { it.copy(nextcloudErrorMessage = "Nextcloud scan failed: $err", enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            }
        )
    }

    fun toggleDropboxAccountEnabled(email: String, enabled: Boolean) {
        viewModelScope.launch {
            dropboxAccountRepository.updateAccountEnabled(email, enabled)
        }
    }

    fun toggleOneDriveAccountEnabled(email: String, enabled: Boolean) {
        viewModelScope.launch {
            onedriveAccountRepository.updateAccountEnabled(email, enabled)
        }
    }

    fun toggleBoxAccountEnabled(email: String, enabled: Boolean) {
        viewModelScope.launch {
            boxAccountRepository.updateAccountEnabled(email, enabled)
        }
    }

    // SMB
    fun addSmbServer(server: com.beatraxus.app.repository.SmbServer) {
        viewModelScope.launch {
            smbConnectionRepository.addConnection(server)
        }
    }

    fun removeSmbServer(id: String) {
        viewModelScope.launch {
            smbConnectionRepository.removeConnection(id)
        }
    }

    fun updateSmbServerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            smbConnectionRepository.updateConnectionEnabled(id, enabled)
        }
    }

    suspend fun connectSmb(server: com.beatraxus.app.repository.SmbServer): Boolean {
        return smbFolderBrowser.connect(server)
    }

    suspend fun listSmbFolder(path: String): List<com.beatraxus.app.network.SmbEntry> {
        return smbFolderBrowser.listFolder(path)
    }

    // FTP
    fun addFtpServer(server: com.beatraxus.app.repository.FtpServer) {
        viewModelScope.launch {
            ftpConnectionRepository.addConnection(server)
        }
    }

    fun removeFtpServer(id: String) {
        viewModelScope.launch {
            ftpConnectionRepository.removeConnection(id)
        }
    }

    fun updateFtpServerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            ftpConnectionRepository.updateConnectionEnabled(id, enabled)
        }
    }

    suspend fun connectFtp(server: com.beatraxus.app.repository.FtpServer): Boolean {
        return ftpFolderBrowser.connect(server)
    }

    suspend fun listFtpFolder(path: String): List<com.beatraxus.app.network.FtpEntry> {
        return ftpFolderBrowser.listFolder(path)
    }

    fun playSmbFile(server: com.beatraxus.app.repository.SmbServer, entry: com.beatraxus.app.network.SmbEntry) {
        val song = Song(
            id = "smb_${server.id}_${entry.fullPath}".hashCode().toString(),
            uri = Uri.parse("smb://${server.host}/${server.shareName}/${entry.fullPath}"),
            title = entry.name,
            artist = server.displayName,
            album = "SMB Share",
            durationMs = 0, // Unknown
            format = entry.name.substringAfterLast(".", "Unknown").uppercase(),
            sampleRateHz = 44100,
            fileSizeBytes = entry.size,
            source = SongSource.SMB
        )
        playSong(song)
    }

    fun playFtpFile(server: com.beatraxus.app.repository.FtpServer, entry: com.beatraxus.app.network.FtpEntry) {
        val scheme = when (server.protocol) {
            com.beatraxus.app.repository.FtpProtocol.SFTP -> "sftp"
            com.beatraxus.app.repository.FtpProtocol.FTPS -> "ftps"
            com.beatraxus.app.repository.FtpProtocol.FTP -> "ftp"
        }
        val song = Song(
            id = "ftp_${server.id}_${entry.fullPath}".hashCode().toString(),
            uri = Uri.parse("$scheme://${server.host}/${entry.fullPath}"),
            title = entry.name,
            artist = server.displayName,
            album = "FTP Server",
            durationMs = 0,
            format = entry.name.substringAfterLast(".", "Unknown").uppercase(),
            sampleRateHz = 44100,
            fileSizeBytes = entry.size,
            source = SongSource.FTP
        )
        playSong(song)
    }

    fun toggleNextcloudAccountEnabled(serverUrl: String, username: String, enabled: Boolean) {
        viewModelScope.launch {
            nextcloudAccountRepository.updateAccountEnabled(serverUrl, username, enabled)
        }
    }

    private var scanJob: Job? = null
    private var lyricsJob: Job? = null
    private var preloadLyricsJob: Job? = null

    fun quickScan() {
        val svc = service ?: return
        triggerMetadataEnrichment()
        libraryScanner.quickScan(svc, _songs.value) { results, newSongs, _, message, hasChanges ->
            if (hasChanges) {
                val cloudSongs = _songs.value.filter { it.source != SongSource.LOCAL }
                _songs.value = (results + cloudSongs).sortedBy { it.title }

                if (newSongs.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.Default) {
                        val toAnalyze = if (_uiState.value.isFirstRun) newSongs.take(20) else newSongs
                        toAnalyze.forEach { song ->
                            try {
                                aiAnalysisChannel.send(song)
                                if (song.year == 0) yearEnrichmentChannel.send(song)
                            } catch (e: Exception) {
                                Log.e("PlayerViewModel", "Failed to queue song for analysis", e)
                            }
                        }
                    }
                }
            }
            updateLibraryCounts(results)
            viewModelScope.launch {
                // Folder list comes from the folder table, NOT from the folders of scanned songs.
                val folders = musicRepository.getMusicFolders()
                _uiState.update { it.copy(
                    musicFolders = folders,
                    blockedFolders = musicRepository.getBlockedFolders()
                ) }
            }
        }
    }

    fun startFullScan() {
        val svc = service ?: return
        triggerMetadataEnrichment()
        libraryScanner.startFullScan(svc, _songs.value) { results, message ->
            try {
                val cloudSongs = _songs.value.filter { it.source != SongSource.LOCAL }
                _songs.value = (results + cloudSongs).sortedBy { it.title }

                viewModelScope.launch(Dispatchers.Default) {
                    results.forEachIndexed { index, song ->
                        if (index % 10 == 0) aiAnalysisChannel.send(song)
                        if (song.year == 0) yearEnrichmentChannel.send(song)
                    }
                }

                updateLibraryCounts(results)
                viewModelScope.launch {
                    val folders = musicRepository.getMusicFolders()
                    _uiState.update {
                        it.copy(
                            musicFolders = folders,
                            blockedFolders = musicRepository.getBlockedFolders()
                        )
                    }
                }

                if (_uiState.value.isFirstRun) setFirstRunComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Error in startFullScan callback", e)
            }
        }
    }

    fun startAddedFoldersScan() {
        val folders = _uiState.value.musicFolders
        if (folders.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No folders added to scan") }
            return
        }
        val svc = service ?: return
        triggerMetadataEnrichment()
        libraryScanner.startFolderScan(svc, folders) { results, message ->
            val currentSongs = _songs.value
            val newLocalIds = results.map { it.id }.toSet()
            val unchanged = currentSongs.filter { it.id !in newLocalIds }
            _songs.value = (unchanged + results).sortedBy { it.title }

            viewModelScope.launch(Dispatchers.Default) {
                results.forEachIndexed { index, song ->
                    if (index < 30) aiAnalysisChannel.send(song)
                    if (song.year == 0) yearEnrichmentChannel.send(song)
                }
            }

            updateLibraryCounts(_songs.value.filter { it.source == SongSource.LOCAL })
            if (_uiState.value.isFirstRun) setFirstRunComplete()
        }
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionDenied = true) }
        // Even without permissions, we can load cloud library
        loadLibrary()
    }

    fun playSong(song: Song) {
        val list = songs.value
        val index = list.indexOfFirst { it.id == song.id }

        // Save song ID immediately to prefs so it persists even if playback fails/service isn't ready
        prefs?.edit()?.putString("last_song_id", song.id)?.apply()

        if (index >= 0) {
            // Check if we are already playing this song to handle resume correctly
            if (_uiState.value.currentSong?.id == song.id) {
                service?.togglePlayPause()
            } else {
                service?.playList(list, index)
                saveQueueToPrefs(list, list, index)
            }
        } else {
            if (_uiState.value.currentSong?.id == song.id) {
                service?.togglePlayPause()
            } else {
                service?.playSong(song)
                saveQueueToPrefs(listOf(song), listOf(song), 0)
            }
        }
        updateRecentlyPlayed(song)
        loadLyrics(song)
    }

    fun playExternalUri(uri: Uri) {
        viewModelScope.launch {
            // Try to find if this song is already in our loaded library
            val existing = allSongs.value.find { it.uri.toString() == uri.toString() }

            if (existing != null) {
                playSong(existing)
            } else {
                val context = getApplication<Application>()
                var tempTitle = uri.lastPathSegment ?: "External Song"
                var tempArtist = "External Source"
                var tempAlbum = "External"
                var tempDurationMs = 0L
                var tempAlbumArtUri: Uri? = null

                withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { tempTitle = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { tempArtist = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { tempAlbum = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { tempDurationMs = it }

                        retriever.embeddedPicture?.let { picture ->
                            val hash = uri.toString().hashCode()
                            val cacheFile = File(context.cacheDir, "ext_art_$hash.jpg")
                            if (!cacheFile.exists()) {
                                FileOutputStream(cacheFile).use { fos ->
                                    fos.write(picture)
                                }
                            }
                            tempAlbumArtUri = Uri.fromFile(cacheFile)
                        }
                    } catch (e: Exception) {
                        Log.e("PlayerViewModel", "Failed to read external metadata", e)
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }
                }

                // Not in DB, create a temporary song object
                val tempSong = Song(
                    id = "external_${System.currentTimeMillis()}",
                    uri = uri,
                    title = tempTitle,
                    artist = tempArtist,
                    album = tempAlbum,
                    durationMs = tempDurationMs,
                    format = uri.toString().substringAfterLast('.', "mp3"),
                    sampleRateHz = 44100,
                    source = SongSource.LOCAL,
                    albumArtUri = tempAlbumArtUri
                )

                // Wait for service
                while (service == null) delay(100)

                service?.playSong(tempSong)
                setShowFullPlayer(true)
            }
        }
    }

    fun playExternalVideoUri(uri: Uri, type: String?) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            var durationMs = 0L
            var width = 0
            var height = 0
            
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { width = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { height = it }
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "Failed to read video metadata", e)
                } finally {
                    try { retriever.release() } catch (e: Exception) {}
                }
            }

            val video = Video(
                id = "ext_video_${System.currentTimeMillis()}",
                uri = uri,
                title = uri.lastPathSegment ?: "External Video",
                displayName = uri.lastPathSegment ?: "External Video",
                folderPath = "External",
                durationMs = durationMs,
                sizeBytes = 0L,
                resolutionWidth = width,
                resolutionHeight = height,
                mimeType = type ?: "video/mp4",
                dateAdded = System.currentTimeMillis()
            )
            playVideo(video)
        }
    }

    fun playList(songs: List<Song>, startIndex: Int) {
        service?.playList(songs, startIndex)
        saveQueueToPrefs(songs, songs, startIndex)
    }

    private fun saveQueueToPrefs(playlist: List<Song>, originalPlaylist: List<Song>, index: Int) {
        if (playlist.isEmpty()) return
        prefs?.edit()?.apply {
            putString("last_queue_ids", playlist.joinToString(",") { it.id })
            putString("last_original_queue_ids", originalPlaylist.joinToString(",") { it.id })
            putInt("last_queue_index", index)
            apply()
        }
    }

    private fun updateRecentlyPlayed(song: Song) {
        val songId = song.id
        com.beatraxus.app.features.PlayStatsStore.record(getApplication(), songId)
        val current = _recentlyPlayed.value.toMutableList()
        current.remove(songId)
        current.add(0, songId)
        if (current.size > 200) current.removeAt(current.size - 1)
        _recentlyPlayed.value = current

        // Persist to database
        viewModelScope.launch(Dispatchers.IO) {
            recentlyPlayedDao.addRecentlyPlayed(
                com.beatraxus.app.model.RecentlyPlayedEntity(
                    songId = songId,
                    timestamp = System.currentTimeMillis(),
                    accountEmail = when (song.source) {
                        SongSource.GDRIVE -> song.driveAccountEmail
                        SongSource.DROPBOX -> song.dropboxAccountEmail
                        SongSource.ONEDRIVE -> song.onedriveAccountEmail
                        SongSource.BOX -> song.boxAccountEmail
                        SongSource.NEXTCLOUD -> song.nextcloudAccountEmail
                        else -> null
                    }
                )
            )
        }
    }


    // Album the user came from when they tapped the album's artist link (so Back can return to it)
    private data class AlbumReturn(val album: String?, val previousView: LibraryView?)
    private var albumReturn: AlbumReturn? = null

    /** Opens [artistName]'s screen from the current album; Back returns to that album. */
    fun openArtistFromAlbum(artistName: String) {
        val s = _uiState.value
        val ret = if (s.currentView == LibraryView.ALBUM_DETAIL) AlbumReturn(s.selectedItemName, s.previousView) else null
        setLibraryView(LibraryView.ARTIST_DETAIL, artistName)
        albumReturn = ret
    }

    /** If the artist screen was opened from an album, goes back to that album and returns true. */
    fun consumeAlbumReturn(): Boolean {
        val ret = albumReturn ?: return false
        albumReturn = null
        if (_uiState.value.currentView != LibraryView.ARTIST_DETAIL) return false
        setLibraryView(LibraryView.ALBUM_DETAIL, ret.album)
        _uiState.update { it.copy(previousView = ret.previousView) }
        return true
    }

    fun setLibraryView(view: LibraryView, itemName: String? = null) {
        albumReturn = null
        _uiState.update {
            val isDetailView = view in listOf(
                LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL,
                LibraryView.FOLDER_DETAIL, LibraryView.GENRE_DETAIL, LibraryView.YEAR_DETAIL,
                LibraryView.PLAYLIST_DETAIL, LibraryView.VIDEO_FOLDER_DETAIL
            )
            it.copy(
                previousView = it.currentView,
                currentView = view,
                selectedItemName = itemName,
                isSearchActive = false,
                isMultiSelectMode = false,
                selectedIds = emptySet(),
                selectedTelegramChannelUrl = null, // Clear telegram when changing view or account
                currentFolderPath = if (view == LibraryView.FOLDER_DETAIL || view == LibraryView.VIDEO_FOLDER_DETAIL) it.currentFolderPath else null,
                wasSearchingBeforeDetail = if (isDetailView) it.isSearchActive else it.wasSearchingBeforeDetail
            )
        }
    }

    fun navigateToVideoFolder(path: String, name: String) {
        _uiState.update {
            it.copy(
                previousView = it.currentView,
                currentView = LibraryView.VIDEO_FOLDER_DETAIL,
                selectedItemName = name,
                currentFolderPath = path,
                wasSearchingBeforeDetail = it.isSearchActive,
                isSearchActive = false
            )
        }
    }

    fun playLastPlayedVideo() {
        val lastId = _recentlyPlayedVideos.value.firstOrNull() ?: allVideos.value.firstOrNull()?.id ?: return
        val video = allVideos.value.find { it.id == lastId } ?: return
        playVideo(video)
    }

    fun resumeBackgroundVideo() {
        val id = _uiState.value.bgVideoId ?: return
        val video = allVideos.value.find { it.id == id } ?: return
        playVideo(video)
    }

    fun playVideo(video: com.beatraxus.app.model.Video) {
        if (_uiState.value.isPlaying) {
            service?.togglePlayPause()
        }

        val list = _uiState.value.videos
        var index = list.indexOfFirst { it.id == video.id }
        
        val finalQueue = if (index == -1) {
            Log.w(TAG, "Tapped video ${video.id} not found in current list, falling back to single-item queue")
            index = 0
            listOf(video)
        } else {
            list
        }

        _uiState.update { it.copy(
            activeVideoQueue = finalQueue,
            navigateToVideoPlayer = video.id
        ) }
        
        // Clear background state when starting fresh
        prefs.edit().apply {
            putBoolean("bg_video_active", false)
            apply()
        }
        
        Log.d(TAG, "Navigating to video player: ${video.title} from list of ${finalQueue.size} at index $index")
    }

    /**
     * Opens the video player on a Live TV channel. The channels around the tapped one become the player's queue, so
     * next / previous in the player zap between them.
     */
    fun playLiveChannels(channels: List<com.beatraxus.app.livetv.LiveChannel>, startIndex: Int) {
        val queue = com.beatraxus.app.livetv.LiveTvPlayback.queue(channels, startIndex) ?: return

        if (_uiState.value.isPlaying) {
            service?.togglePlayPause()
        }

        _uiState.update { it.copy(
            activeVideoQueue = queue.videos,
            navigateToVideoPlayer = queue.startVideoId
        ) }

        prefs.edit().apply {
            putBoolean("bg_video_active", false)
            apply()
        }

        Log.d(TAG, "Navigating to live channel ${queue.startVideoId} (queue of ${queue.videos.size})")
    }

    /**
     * Feature 27: opens the video player on a link the user pasted. The link becomes a one-item queue; the same link
     * always gets the same video id, so its resume position is remembered the next time it is opened.
     */
    fun playNetworkStream(url: String) {
        val video = com.beatraxus.app.util.NetworkStreamLink.toVideo(url)

        if (_uiState.value.isPlaying) {
            service?.togglePlayPause()
        }

        _uiState.update { it.copy(
            activeVideoQueue = listOf(video),
            navigateToVideoPlayer = video.id
        ) }

        prefs.edit().apply {
            putBoolean("bg_video_active", false)
            apply()
        }

        Log.d(TAG, "Navigating to network stream ${video.id} (${video.title})")
    }

    fun consumeVideoNavigation() {
        _uiState.update { it.copy(navigateToVideoPlayer = null) }
    }

    fun setLibraryViewTelegram(url: String) {
        val normalizedUrl = url.trim().removeSuffix("/")
        _uiState.update { it.copy(
            selectedTelegramChannelUrl = normalizedUrl,
            selectedItemName = null, // Clear drive account
            currentView = LibraryView.CLOUD,
            isSearchActive = false
        ) }
    }

    fun setCloudAccount(email: String?) {
        _uiState.update { it.copy(
            selectedCloudEmail = email,
            selectedItemName = email,
            selectedTelegramChannelUrl = null
        ) }
    }

    fun setCloudTelegram(url: String?) {
        val normalizedUrl = url?.trim()?.removeSuffix("/")
        _uiState.update { it.copy(
            selectedTelegramChannelUrl = normalizedUrl,
            selectedCloudEmail = null,
            selectedItemName = null
        ) }
    }

    fun refreshCloudLibrary() {
        val state = _uiState.value
        val email = state.selectedItemName
        if (state.selectedTelegramChannelUrl != null) {
            syncTelegramChannel(state.selectedTelegramChannelUrl)
        } else if (state.currentView == LibraryView.CLOUD && email != null) {
            when {
                state.driveAccounts.any { it.email == email } -> scanDriveAccount(email)
                state.dropboxAccounts.any { it.email == email } -> scanDropboxAccount(email)
                state.onedriveAccounts.any { it.email == email } -> scanOneDriveAccount(email)
                state.boxAccounts.any { it.email == email } -> scanBoxAccount(email)
                else -> {
                    val nc = state.nextcloudAccounts.find { it.username == email }
                    if (nc != null) scanNextcloudAccount(nc.serverUrl, nc.username)
                }
            }
        }
    }

    fun navigateToFolder(path: String, name: String) {
        _uiState.update {
            it.copy(
                previousView = it.currentView,
                currentView = LibraryView.FOLDER_DETAIL,
                selectedItemName = name,
                currentFolderPath = path,
                wasSearchingBeforeDetail = it.isSearchActive,
                isSearchActive = false
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _uiState.update { it.copy(isMultiSelectMode = enabled, selectedIds = emptySet()) }
    }

    fun toggleSongSelection(songId: String) {
        _uiState.update {
            val current = it.selectedIds
            val updated = if (current.contains(songId)) current - songId else current + songId
            it.copy(selectedIds = updated)
        }
    }

    fun toggleItemSelection(id: String) {
        _uiState.update {
            val current = it.selectedIds
            val updated = if (current.contains(id)) current - id else current + id
            it.copy(selectedIds = updated)
        }
    }

    fun selectAll() {
        val currentView = _uiState.value.currentView
        val itemsToSelect = when (currentView) {
            LibraryView.HOME, LibraryView.ALL_SONGS, LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL,
            LibraryView.FOLDER_DETAIL, LibraryView.YEAR_DETAIL, LibraryView.GENRE_DETAIL,
            LibraryView.PLAYLIST_DETAIL, LibraryView.FAVORITES, LibraryView.RECENTLY_ADDED,
            LibraryView.RECENTLY_PLAYED, LibraryView.CLOUD, LibraryView.RADIO -> songs.value.map { it.id }

            LibraryView.ALBUMS -> albums.value.map { it.first }
            LibraryView.ARTISTS -> artists.value.map { it.first }
            LibraryView.FOLDERS -> folders.value.map { it.first }
            LibraryView.YEARS -> years.value.map { it.first }
            LibraryView.GENRES -> genres.value.map { it.first }
            LibraryView.PLAYLISTS -> playlists.value.map { it.id }
            LibraryView.VIDEO_ALL, LibraryView.VIDEO_RECENTLY_ADDED, LibraryView.VIDEO_RECENTLY_PLAYED ->
                _uiState.value.videos.map { it.id }
            LibraryView.VIDEO_FOLDER_DETAIL ->
                _uiState.value.videos.filter { it.folderPath == _uiState.value.currentFolderPath }.map { it.id }
            else -> emptyList()
        }

        _uiState.update { state ->
            val allSelected = itemsToSelect.all { state.selectedIds.contains(it) }
            val newSelection = if (allSelected) emptySet() else itemsToSelect.toSet()
            state.copy(selectedIds = newSelection)
        }
    }

    fun deleteSelected() {
        val state = _uiState.value
        val selected = state.selectedIds
        if (selected.isEmpty()) return

        when (state.currentView) {
            LibraryView.PLAYLISTS -> {
                viewModelScope.launch {
                    selected.forEach { id -> playlistDao.deletePlaylist(id) }
                    setMultiSelectMode(false)
                }
            }
            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS, LibraryView.YEARS, LibraryView.GENRES -> {
                // For categories, delete all songs in those categories
                val songsToDelete = filteredSongsByMode.value.filter { song ->
                    when (state.currentView) {
                        LibraryView.ALBUMS -> selected.contains(song.album)
                        LibraryView.ARTISTS -> selected.contains(song.artist)
                        LibraryView.FOLDERS -> selected.contains(song.folder)
                        LibraryView.YEARS -> {
                            val yearStr = if (song.year == 0) "Unknown" else song.year.toString()
                            selected.contains(yearStr)
                        }
                        // Deleting only touches songs whose MAIN genre was selected (never AI styles / side genres).
                        LibraryView.GENRES -> selected.contains(profileFor(song).primaryGenre)
                        else -> false
                    }
                }
                if (songsToDelete.isNotEmpty()) {
                    viewModelScope.launch {
                        pendingDeleteIds = songsToDelete.map { it.id }
                        val intent = musicRepository.deleteSongs(songsToDelete.map { it.uri })
                        if (intent != null) {
                            _deleteRequest.value = intent
                        } else {
                            onDeleteSuccess()
                        }
                    }
                }
            }
            else -> deleteSelectedSongs()
        }
    }

    fun playNextSelected() {
        val state = _uiState.value
        val selected = state.selectedIds
        if (selected.isEmpty()) return

        val songsToQueue = when (state.currentView) {
            LibraryView.PLAYLISTS -> {
                val pls = playlists.value.filter { selected.contains(it.id) }
                val songIds = pls.flatMap { it.songIds }.distinct()
                filteredSongsByMode.value.filter { songIds.contains(it.id) }
            }
            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS, LibraryView.YEARS, LibraryView.GENRES -> {
                filteredSongsByMode.value.filter { song ->
                    when (state.currentView) {
                        LibraryView.ALBUMS -> selected.contains(song.album)
                        LibraryView.ARTISTS -> selected.contains(song.artist)
                        LibraryView.FOLDERS -> selected.contains(song.folder)
                        LibraryView.YEARS -> {
                            val yearStr = if (song.year == 0) "Unknown" else song.year.toString()
                            selected.contains(yearStr)
                        }
                        LibraryView.GENRES -> profileFor(song).labels.any { it in selected }
                        else -> false
                    }
                }
            }
            else -> filteredSongsByMode.value.filter { selected.contains(it.id) }
        }

        if (songsToQueue.isNotEmpty()) {
            songsToQueue.reversed().forEach { service?.playNext(it) }
            setMultiSelectMode(false)
        }
    }

    fun shareSelected() {
        val state = _uiState.value
        val selected = state.selectedIds
        if (selected.isEmpty()) return

        val songsToShare = when (state.currentView) {
            LibraryView.PLAYLISTS -> {
                val pls = playlists.value.filter { selected.contains(it.id) }
                val songIds = pls.flatMap { it.songIds }.distinct()
                filteredSongsByMode.value.filter { songIds.contains(it.id) }
            }
            LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS, LibraryView.YEARS, LibraryView.GENRES -> {
                filteredSongsByMode.value.filter { song ->
                    when (state.currentView) {
                        LibraryView.ALBUMS -> selected.contains(song.album)
                        LibraryView.ARTISTS -> selected.contains(song.artist)
                        LibraryView.FOLDERS -> selected.contains(song.folder)
                        LibraryView.YEARS -> {
                            val yearStr = if (song.year == 0) "Unknown" else song.year.toString()
                            selected.contains(yearStr)
                        }
                        LibraryView.GENRES -> profileFor(song).labels.any { it in selected }
                        else -> false
                    }
                }
            }
            else -> filteredSongsByMode.value.filter { selected.contains(it.id) }
        }

        if (songsToShare.isNotEmpty()) {
            val uris = ArrayList<Uri>()
            songsToShare.forEach { uris.add(it.uri) }

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(intent, "Share Music").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(chooser)
            setMultiSelectMode(false)
        }
    }

    fun getNextSongPreview(): Song? {
        return service?.getNextSong()
    }

    fun deleteSelectedSongs() {
        val selectedIds = _uiState.value.selectedIds.toList()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            val songsToDelete = allSongs.value.filter { it.id in selectedIds }
            pendingDeleteIds = selectedIds
            val intent = musicRepository.deleteSongs(songsToDelete.map { it.uri })
            if (intent != null) {
                _deleteRequest.value = intent
            } else {
                // Success for < Android 10 or pre-granted permissions
                onDeleteSuccess()
            }
        }
    }

    fun addSelectedToPlaylist(playlistName: String) {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            val currentPlaylists = playlists.value
            val existing = currentPlaylists.find { it.name == playlistName }

            val songIdsToAdd = when (_uiState.value.currentView) {
                LibraryView.PLAYLISTS -> {
                    playlists.value.filter { selectedIds.contains(it.id) }.flatMap { it.songIds }
                }
                LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.FOLDERS, LibraryView.YEARS, LibraryView.GENRES -> {
                    filteredSongsByMode.value.filter { song ->
                        when (_uiState.value.currentView) {
                            LibraryView.ALBUMS -> selectedIds.contains(song.album)
                            LibraryView.ARTISTS -> selectedIds.contains(song.artist)
                            LibraryView.FOLDERS -> selectedIds.contains(song.folder)
                            LibraryView.YEARS -> {
                                val yearStr = if (song.year == 0) "Unknown" else song.year.toString()
                                selectedIds.contains(yearStr)
                            }
                            LibraryView.GENRES -> profileFor(song).labels.any { it in selectedIds }
                            else -> false
                        }
                    }.map { it.id }
                }
                else -> selectedIds.toList()
            }

            val playlist = if (existing != null) {
                existing.copy(songIds = (existing.songIds + songIdsToAdd).toList().distinct())
            } else {
                Playlist(id = System.currentTimeMillis().toString(), name = playlistName, songIds = songIdsToAdd.distinct())
            }
            playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, playlist.songIds.joinToString(",")))
            setMultiSelectMode(false)
        }
    }

    fun addSongToPlaylist(playlistName: String, songId: String) {
        viewModelScope.launch {
            val currentPlaylists = playlists.value
            val existing = currentPlaylists.find { it.name == playlistName }
            if (existing != null) {
                val updatedSongIds = (existing.songIds + songId).toList().distinct()
                playlistDao.insertPlaylist(PlaylistEntity(existing.id, existing.name, updatedSongIds.joinToString(",")))
            } else {
                val playlist = Playlist(id = System.currentTimeMillis().toString(), name = playlistName, songIds = listOf(songId))
                playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, playlist.songIds.joinToString(",")))
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            playlists.value.find { it.id == playlistId }?.let { playlist ->
                val newSongIds = playlist.songIds - songId
                playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, newSongIds.joinToString(",")))
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            playlistDao.deletePlaylist(playlistId)
            if (_uiState.value.currentView == LibraryView.PLAYLIST_DETAIL) {
                setLibraryView(LibraryView.PLAYLISTS)
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            if (playlists.value.any { it.name == name }) return@launch
            val playlist = Playlist(id = System.currentTimeMillis().toString(), name = name)
            playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, ""))
        }
    }

    fun setCameFromNowPlaying(value: Boolean) {
        _uiState.update { it.copy(cameFromNowPlaying = value) }
    }

    fun setShowFullPlayer(show: Boolean) {
        _uiState.update { it.copy(showFullPlayer = show) }
    }

    fun setShowSongInfo(show: Boolean) {
        _uiState.update { it.copy(showSongInfo = show) }
    }

    /** True when the Inspector was opened via the song menu's "Inspect" entry (return = song menu). */
    @Volatile var inspectorReturnToMenu: Boolean = false

    fun setPendingInspectorReturn(song: com.beatraxus.app.model.Song?) {
        _uiState.update { it.copy(pendingInspectorReturnSong = song) }
    }

    fun setSettingsIconPosition(x: Float, y: Float) {
        _uiState.update { it.copy(settingsIconX = x, settingsIconY = y) }
    }

    fun toggleQueue() {
        _uiState.update { it.copy(showQueue = !it.showQueue) }
    }

    fun getUpcomingSongs(): List<Song> {
        return service?.getUpcomingSongs() ?: emptyList()
    }

    fun removeFromQueue(songId: String) {
        service?.removeFromQueue(songId)
    }

    fun moveInQueue(from: Int, to: Int) {
        service?.moveInUpcomingQueue(from, to)
    }

    fun playFromQueue(songId: String) {
        service?.playFromQueue(songId)
    }

    /** Writes an encrypted backup. [passphrase] is wiped once the export finishes. */
    fun exportSettings(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            android.widget.Toast.makeText(app, "Encrypting backup…", android.widget.Toast.LENGTH_SHORT).show()
            try {
                backupRepository.exportSettings(uri, passphrase, prefs.getString(KEY_CUSTOM_EQ_PRESETS, null))
                android.widget.Toast.makeText(app, "Encrypted backup exported", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(app, "Export failed: ${e.message ?: "unknown error"}", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    /** True when the picked backup file needs a passphrase before it can be imported. */
    suspend fun isBackupEncrypted(uri: android.net.Uri): Boolean =
        try {
            backupRepository.isEncrypted(uri)
        } catch (e: Exception) {
            false
        }

    /**
     * Restores a backup. Pass the passphrase for encrypted files; plain files from older versions
     * need none. [onResult] gets true on success, false when the passphrase was wrong (so the UI
     * can ask again). The passphrase is wiped afterwards.
     */
    fun importSettings(uri: android.net.Uri, passphrase: CharArray?, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (passphrase != null) {
                android.widget.Toast.makeText(app, "Decrypting backup…", android.widget.Toast.LENGTH_SHORT).show()
            }
            try {
                val extras = backupRepository.importSettings(uri, passphrase)
                extras.customEqPresetsJson?.let { json ->
                    val imported = parseCustomEqPresets(json)
                    if (imported.isNotEmpty()) importEqPresets(imported)
                }
                android.widget.Toast.makeText(app, "Settings imported successfully", android.widget.Toast.LENGTH_SHORT).show()
                onResult(true)
            } catch (e: com.beatraxus.app.repository.BackupCrypto.WrongPassphraseException) {
                android.widget.Toast.makeText(app, "Wrong passphrase, or the backup file is damaged", android.widget.Toast.LENGTH_LONG).show()
                onResult(false)
            } catch (e: com.beatraxus.app.repository.BackupCrypto.InvalidBackupException) {
                android.widget.Toast.makeText(app, e.message ?: "Not a valid backup file", android.widget.Toast.LENGTH_LONG).show()
                onResult(true) // nothing to retry with a different passphrase
            } catch (e: Exception) {
                android.widget.Toast.makeText(app, "Import failed: ${e.message ?: "unknown error"}", android.widget.Toast.LENGTH_LONG).show()
                onResult(true)
            } finally {
                passphrase?.fill('\u0000')
            }
        }
    }

    fun applyCurrentConfigToAllDevices() {
        viewModelScope.launch {
            val config = dspPreferences.dspConfig.first()
            dspPreferences.applyConfigToAllDevices(config)
            android.widget.Toast.makeText(getApplication(), "Settings assigned to all devices", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun playNext(song: Song) {
        service?.playNext(song)
    }

    fun addToQueue(song: Song) {
        service?.addToQueue(song)
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            pendingDeleteIds = listOf(song.id)
            val intent = musicRepository.deleteSongs(listOf(song.uri))
            if (intent != null) {
                _deleteRequest.value = intent
            } else {
                onDeleteSuccess()
            }
        }
    }

    fun deleteVideo(video: com.beatraxus.app.model.Video) {
        if (video.isCloud) return // cloud videos aren't files on this device
        viewModelScope.launch {
            pendingDeleteIds = listOf(video.id)
            // reuse musicRepository logic as it's generic for MediaStore URIs
            val intent = musicRepository.deleteSongs(listOf(video.uri))
            if (intent != null) {
                _deleteRequest.value = intent
            } else {
                onDeleteSuccess()
            }
        }
    }

    fun renameVideo(video: com.beatraxus.app.model.Video, newName: String) {
        if (video.isCloud) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<android.app.Application>()
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, newName)
                }
                context.contentResolver.update(video.uri, values, null, null)
                withContext(Dispatchers.Main) {
                    loadVideos(forceImmediate = true) // Refresh
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename video", e)
            }
        }
    }

    fun getShareVideoIntent(video: com.beatraxus.app.model.Video): android.content.Intent {
        if (video.isCloud) {
            // There's no file to hand over; share the title instead of a Uri other apps can't open.
            return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, video.title)
            }
        }
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = video.mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, video.uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            if (favorites.value.contains(song.id)) {
                favoriteDao.removeFavorite(song.id)
            } else {
                favoriteDao.addFavorite(FavoriteEntity(song.id))
            }
        }
    }

    internal suspend fun analyzeSpectrum(song: Song): AudioSpectrumAnalyzer.SpectrumAnalysisResult? {
        return withContext(Dispatchers.IO) {
            audioSpectrumAnalyzer.getOrAnalyze(song)
        }
    }

    /** "Play Together" rooms. Created lazily so it costs nothing until the screen is opened. */
    val playTogether: com.beatraxus.app.features.PlayTogetherManager by lazy {
        com.beatraxus.app.features.PlayTogetherManager(
            getApplication(),
            viewModelScope,
            object : com.beatraxus.app.features.PlayTogetherManager.Host {
                override fun librarySongs(): List<Song> = allSongs.value
                override fun currentSong(): Song? = _uiState.value.currentSong
                // Read the player itself: the UI copies lag, and a lagging "isPlaying" made play/pause toggles misfire.
                override fun isPlaying(): Boolean = service?.wantsPlaying() ?: _uiState.value.isPlaying
                override fun positionMs(): Long = service?.currentPositionMs ?: _progressMs.value
                override fun queue(): List<Song> = service?.getPlaylist().orEmpty()

                override fun playQueue(songs: List<Song>, index: Int, positionMs: Long, playing: Boolean) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val svc = service
                        if (svc != null) {
                            svc.playListAt(songs, index, positionMs.coerceAtLeast(0L), playing)
                            saveQueueToPrefs(songs, songs, index)
                        } else {
                            playList(songs, index)
                        }
                    }
                }

                override fun setPlaying(play: Boolean) {
                    viewModelScope.launch(Dispatchers.Main) {
                        // compare with the player's real state, so "play" can never turn into a pause
                        val now = service?.wantsPlaying() ?: _uiState.value.isPlaying
                        if (now != play) togglePlayPause()
                    }
                }

                override fun seekTo(ms: Long) {
                    viewModelScope.launch(Dispatchers.Main) { this@PlayerViewModel.seekTo(ms) }
                }

                override fun isReady(): Boolean = service?.isEngineReady() ?: true

                override fun nudge(driftMs: Long): Boolean {
                    val svc = service ?: return false
                    svc.nudgePositionMs(driftMs)
                    return true
                }
            }
        )
    }

    fun togglePlayPause() {
        if (CastManager.isConnected) {
            if (_uiState.value.isPlaying) CastManager.pause() else CastManager.play()
            return
        }
        service?.let { svc ->
            svc.togglePlayPause()
        } ?: _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun skipToNext() {
        service?.next()
    }

    /** Pauses only if currently playing (used when handing playback over to a DLNA device). */
    fun pausePlayback() {
        if (_uiState.value.isPlaying) togglePlayPause()
    }

    /** Updates the library (memory + database) after the tag editor saved a song. */
    fun applyEditedTags(song: Song, tags: com.beatraxus.app.features.TagData) {
        val updated = with(com.beatraxus.app.features.TagEditorHelper) { song.withEditedTags(tags) }
        viewModelScope.launch(Dispatchers.IO) { songDao.insertSong(updated.toEntity()) }
        _songs.update { list -> list.map { if (it.id == song.id) updated else it } }
        _uiState.update { st ->
            if (st.currentSong?.id == song.id) st.copy(currentSong = updated) else st
        }
    }

    fun skipToPrevious() {
        service?.previous()
    }

    fun seekTo(positionMs: Long) {
        _progressMs.value = positionMs
        service?.seekTo(positionMs)
    }

    fun setHighQualityResampler(enabled: Boolean) {
        applyDspConfig { it.copy(highQualityResampler = enabled) }
    }

    fun setResamplerMode(mode: ResamplerMode) {
        applyDspConfig { it.copy(resamplerMode = mode) }
    }

    fun setSampleFormat(format: com.beatraxus.app.model.SampleFormat) {
        applyDspConfig { it.copy(sampleFormat = format) }
    }

    fun setResamplerCutoffRatio(value: Float) {
        applyDspConfig { it.copy(resamplerCutoffRatio = value.coerceIn(0.01f, 0.995f)) }
    }

    fun setOutputMode(mode: OutputMode) {
        prefs.edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
        _uiState.update { it.copy(outputMode = mode.name) }
        applyDspConfig { it.copy(outputMode = mode) }
        service?.setOutputMode(mode)
    }

    private var dspSaveJob: Job? = null
    private fun applyDspConfig(transform: (DspConfig) -> DspConfig) {
        val updated = transform(_uiState.value.dsp.config)
        _uiState.update { it.copy(dsp = it.dsp.copy(config = updated, autoEqError = null)) }
        service?.updateDspConfig(updated)

        dspSaveJob?.cancel()
        dspSaveJob = viewModelScope.launch {
            delay(500) // Debounce saving to disk
            dspPreferences.saveConfig(updated, dspPreferences.getCurrentDeviceId())
        }
    }

    fun setHeadroomManagement(enabled: Boolean) {
        applyDspConfig { it.copy(headroomManagementEnabled = enabled) }
    }

    fun setNoHeadroomGainEnabled(enabled: Boolean) {
        applyDspConfig { it.copy(noHeadroomGainEnabled = enabled) }
    }

    fun setBypassAll(bypass: Boolean) {
        applyDspConfig { it.copy(bypassAll = bypass) }
    }

    fun resetCurrentDevicePreset() {
        viewModelScope.launch {
            dspPreferences.clearDeviceOverrides(dspPreferences.getCurrentDeviceId())
        }
    }

    fun copySettingsFromDevice(otherDeviceId: String) {
        viewModelScope.launch {
            val otherConfig = dspPreferences.dspConfigForDevice(otherDeviceId).first()
            applyDspConfig { otherConfig }
        }
    }

    fun listKnownDevices(): Flow<Set<String>> = dspPreferences.listKnownDeviceIds()

    // ---- Per-device EQ preset assignment ----

    /** deviceId -> saved EQ preset name that loads whenever that output becomes active. */
    val deviceEqAssignments: Flow<Map<String, String>> = dspPreferences.deviceEqAssignments

    suspend fun assignableDevices(): List<DspPreferences.OutputDeviceInfo> =
        withContext(Dispatchers.IO) { dspPreferences.assignableDevices() }

    /** Assigns a saved EQ preset to an output device ([presetName] null clears it). */
    fun assignEqPresetToDevice(device: DspPreferences.OutputDeviceInfo, presetName: String?) {
        viewModelScope.launch {
            dspPreferences.setDeviceEqAssignment(device.id, presetName, device.label)
            // If that output is playing right now, switch to the preset immediately.
            if (presetName != null && device.id == dspPreferences.getCurrentDeviceId()) {
                applyAssignedEq(device.id)
            }
        }
    }

    /**
     * Loads the EQ preset assigned to [deviceId] (if any) into that device's DSP profile. Runs at
     * startup and whenever the active output changes, so plugging in headphones restores their EQ.
     * Only EQ bands and preamp are replaced; every other DSP setting of the device is kept.
     */
    private suspend fun applyAssignedEq(deviceId: String) {
        val presetName = dspPreferences.getDeviceEqAssignment(deviceId) ?: return
        val preset = _uiState.value.dsp.customEqPresets.firstOrNull { it.name == presetName } ?: return
        val base = dspPreferences.dspConfigForDevice(deviceId).first()
        if (base.settingsLocked) return

        // A debounced save queued for the previous output would otherwise land on this device.
        dspSaveJob?.cancel()
        val updated = base.copy(
            eqEnabled = true,
            eqBands = preset.bands,
            preampDb = preset.preampDb,
            preampEnabled = true,
            autoEqEnabled = false,
            autoEqProfile = null
        )
        _uiState.update { it.copy(dsp = it.dsp.copy(config = updated, autoEqError = null)) }
        service?.updateDspConfig(updated)
        dspPreferences.saveConfig(updated, deviceId)
    }

    fun getCurrentDeviceId(): String = dspPreferences.getCurrentDeviceId()

    fun setPlaybackSpeed(speed: Float) {
        applyDspConfig { it.copy(playbackSpeed = speed.coerceIn(com.beatraxus.app.model.PLAYBACK_SPEED_RANGE)) }
    }

    fun setPreservePitch(preserve: Boolean) {
        applyDspConfig { it.copy(preservePitch = preserve) }
    }

    fun setCrossfadeDuration(seconds: Int) {
        applyDspConfig { it.copy(crossfadeDurationS = seconds) }
    }

    fun exportDspPreset(): String {
        val config = _uiState.value.dsp.config
        return JSONObject().apply {
            put("name", "Beatraxus Preset")
            put("preampDb", config.preampDb)
            put("eqEnabled", config.eqEnabled)
            put("eqPhaseMode", config.eqPhaseMode.name)
            put("bassDb", config.bassDb)
            put("trebleDb", config.trebleDb)
            put("airDb", config.airDb)
            put("limiterEnabled", config.limiterEnabled)
            put("limiterThresholdDb", config.limiterThresholdDb)

            val bandsArray = JSONArray()
            config.eqBands.forEach { band ->
                bandsArray.put(JSONObject().apply {
                    put("freq", band.frequencyHz)
                    put("gain", band.gainDb)
                    put("q", band.q)
                })
            }
            put("bands", bandsArray)
        }.toString()
    }

    fun importDspPreset(json: String) {
        try {
            val obj = JSONObject(json)
            applyDspConfig { config ->
                val bandsArray = obj.optJSONArray("bands")
                val importedBands = if (bandsArray != null) {
                    List(bandsArray.length()) { i ->
                        val b = bandsArray.getJSONObject(i)
                        ParametricEqBand(
                            id = i,
                            frequencyHz = com.beatraxus.app.utils.PresetExporter.snapToStandardBand(b.getDouble("freq").toFloat()),
                            gainDb = b.getDouble("gain").toFloat().coerceIn(-12f, 12f),
                            q = b.getDouble("q").toFloat().coerceIn(0.1f, 10f)
                        )
                    }
                        .groupBy { it.frequencyHz }
                        .map { (_, group) -> group.first().copy(gainDb = group.map { it.gainDb }.average().toFloat()) }
                        .sortedBy { it.frequencyHz }
                        .mapIndexed { i, band -> band.copy(id = i) }
                } else config.eqBands

                config.copy(
                    preampDb = obj.optDouble("preampDb", config.preampDb.toDouble()).toFloat().coerceIn(-20f, 20f),
                    eqEnabled = obj.optBoolean("eqEnabled", config.eqEnabled),
                    bassDb = obj.optDouble("bassDb", obj.optDouble("midBassDb", config.bassDb.toDouble())).toFloat().coerceIn(-12f, 12f),
                    trebleDb = obj.optDouble("trebleDb", config.trebleDb.toDouble()).toFloat().coerceIn(-12f, 12f),
                    airDb = obj.optDouble("airDb", config.airDb.toDouble()).toFloat().coerceIn(-12f, 12f),
                    eqBands = importedBands
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(dsp = it.dsp.copy(autoEqError = "Invalid preset file")) }
        }
    }

    fun openFolderPicker() {
        // Launch SAF folder picker via Activity result
        _uiState.update { it.copy(triggerFolderPicker = true) }
    }

    fun openVideoFolderPicker() {
        _uiState.update { it.copy(triggerVideoFolderPicker = true) }
    }

    fun consumeFolderPickerTrigger() {
        _uiState.update { it.copy(triggerFolderPicker = false) }
    }

    fun consumeVideoFolderPickerTrigger() {
        _uiState.update { it.copy(triggerVideoFolderPicker = false) }
    }

    fun addMusicFolder(uri: String) {
        viewModelScope.launch {
            val resolved = musicRepository.resolveUriToPath(uri) ?: uri
            musicRepository.removeBlockedFolder(resolved)
            musicRepository.addMusicFolder(resolved)
            // Picked on purpose: if this is a call recording folder, a full scan must not exclude it again.
            musicRepository.allowCallRecordingFolder(resolved)
            val folders = musicRepository.getMusicFolders()
            _uiState.update { it.copy(
                triggerFolderPicker = false, 
                musicFolders = folders,
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            // No automatic scan: the user runs Quick Scan to index the new folder.
        }
    }

    fun addVideoFolder(uri: String) {
        viewModelScope.launch {
            val resolved = musicRepository.resolveUriToPath(uri) ?: uri
            musicRepository.removeBlockedFolder(resolved)
            musicRepository.consumeBlockedVideoFolder(resolved)
            videoFolderDao.insertFolder(com.beatraxus.app.model.VideoFolderEntity(resolved))
            _uiState.update { it.copy(
                triggerVideoFolderPicker = false,
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            loadVideos(forceImmediate = true)
        }
    }

    fun removeMusicFolder(path: String) {
        viewModelScope.launch {
            val normalized = withContext(Dispatchers.IO) { musicRepository.normalizePath(path) }
            val targets = setOf(path.trimEnd('/'), normalized.trimEnd('/')).filter { it.isNotBlank() }

            // 1) Instantly drop the folder row and its songs from the UI.
            _uiState.update { st ->
                st.copy(musicFolders = st.musicFolders.filterNot { it == path || it.trimEnd('/') in targets })
            }
            _songs.update { list ->
                list.filterNot { song ->
                    song.source == SongSource.LOCAL &&
                        targets.any { t -> song.folder == t || song.folder.startsWith("$t/") }
                }
            }

            // 2) Persist: remove the folder, block it, delete its songs from the DB.
            musicRepository.removeMusicFolder(path)
            targets.forEach { songDao.deleteSongsInFolder(it) }

            val folders = musicRepository.getMusicFolders()
            _uiState.update { it.copy(
                musicFolders = folders,
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            updateLibraryCounts(_songs.value.filter { it.source == SongSource.LOCAL })
        }
    }

    fun removeVideoFolder(path: String) {
        viewModelScope.launch {
            videoFolderDao.deleteFolder(path)
            musicRepository.addBlockedFolder(path)
            musicRepository.markBlockedVideoFolder(path)
            
            // Instant removal from UI state
            _videos.update { vids -> vids.filterNot { it.folderPath.startsWith(path) } }
            
            _uiState.update { it.copy(
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            
            // Optionally we could also call loadVideos() but _videos.update is faster
        }
    }

    fun unblockMusicFolder(path: String) {
        viewModelScope.launch {
            val wasVideoFolder = musicRepository.consumeBlockedVideoFolder(path)
            musicRepository.removeBlockedFolder(path)
            // Put the folder back where it came from: Video Folders for a video folder, Music Folders otherwise.
            if (wasVideoFolder) {
                videoFolderDao.insertFolder(com.beatraxus.app.model.VideoFolderEntity(path))
            } else {
                musicRepository.addMusicFolder(path)
                // The listener wants this folder (e.g. a call recording folder): a full scan must not exclude it again.
                musicRepository.allowCallRecordingFolder(path)
            }
            val folders = musicRepository.getMusicFolders()
            _uiState.update { it.copy(
                musicFolders = folders,
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            loadVideos(forceImmediate = true)
        }
    }

    fun cancelScan() {
        libraryScanner.cancelScan(service)
    }



    fun setLibraryMode(mode: LibraryMode) {
        if (_uiState.value.libraryMode == mode) return
        prefs.edit().putString("library_mode", mode.name).apply()
        _uiState.update { it.copy(
            libraryMode = mode,
            selectedVideoSource = null, // a chip picked in one mode shouldn't silently filter the next
            currentView = if (mode == LibraryMode.ADDONS) LibraryView.ADDONS else it.currentView
        ) }
        if (mode == LibraryMode.CLOUD || mode == LibraryMode.COMBINED) {
            refreshTelegramVideos()
            maybeRefreshCloudVideos()
        }
    }





    fun setPlaybackMode(mode: com.beatraxus.app.model.PlaybackMode) {
        if (_uiState.value.playbackMode == mode) return
        prefs.edit().putString("playback_mode", mode.name).apply()
        _uiState.update { it.copy(
            playbackMode = mode,
            currentView = LibraryView.HOME
        ) }
        
        // Auto-scan videos if entering video mode and list is empty
        if (mode == com.beatraxus.app.model.PlaybackMode.VIDEO && _videos.value.isEmpty()) {
            loadVideos(forceImmediate = true)
        }
        if (mode == com.beatraxus.app.model.PlaybackMode.VIDEO) maybeRefreshCloudVideos()
    }

    fun setMetadataNetworkType(type: com.beatraxus.app.model.NetworkType) {
        prefs.edit().putString("metadata_network_type", type.name).apply()
        _uiState.update { it.copy(metadataNetworkType = type) }
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("data_saver_enabled", enabled).apply()
    }

    fun setArtworkEnrichmentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("artwork_enrichment_enabled", enabled).apply()
    }

    fun setSyncQuality(quality: com.beatraxus.app.model.SyncQuality) {
        prefs.edit().putString("sync_quality", quality.name).apply()
        _uiState.update { it.copy(syncQuality = quality) }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("background_sync_enabled", enabled).apply()
        _uiState.update { it.copy(backgroundSyncEnabled = enabled) }
    }

    fun setGdriveAllowedFormats(formats: Set<String>) {
        prefs.edit().putStringSet("gdrive_allowed_formats", formats).apply()
        _uiState.update { it.copy(gdriveAllowedFormats = formats) }
    }

    fun setTelegramAllowedFormats(formats: Set<String>) {
        prefs.edit().putStringSet("telegram_allowed_formats", formats).apply()
        _uiState.update { it.copy(telegramAllowedFormats = formats) }
    }

    fun setPreampEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        it.copy(preampEnabled = enabled, autoEqProfile = null, autoEqEnabled = false)
    }
    fun setPreampDb(value: Float) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        val db = value.coerceIn(-15f, 15f)
        // AI EQ stays on while the preamp is changed; the preamp simply adds on top of it.
        val eqOn = it.eqEnabled || it.aiEqEnabled
        it.copy(preampDb = db, preampEnabled = true, eqEnabled = eqOn, autoEqProfile = null, autoEqEnabled = false)
    }
    fun setEqEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        eqEnabledByAi = false
        if (enabled) {
            it.copy(eqEnabled = true, preampEnabled = true)
        } else {
            // Switching the equalizer off switches AI EQ off with it.
            it.copy(eqEnabled = false, aiEqEnabled = false, preampEnabled = false, autoEqEnabled = false, autoEqProfile = null)
        }
    }
    fun setEqPhaseMode(mode: com.beatraxus.app.model.EqPhaseMode) = applyDspConfig {
        it.copy(eqPhaseMode = mode)
    }
    fun setAutoEqEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        if (enabled) it.copy(autoEqEnabled = true, eqEnabled = true, preampEnabled = true) else it.copy(autoEqEnabled = false)
    }
    /** True when turning AI EQ on is what switched the equalizer on, so turning AI off can switch it back off. */
    private var eqEnabledByAi = false

    fun setAiEqEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        if (enabled) {
            // AI EQ drives the equalizer faders, so the equalizer section is switched on with it.
            if (!it.eqEnabled) {
                eqEnabledByAi = true
                it.copy(aiEqEnabled = true, eqEnabled = true, preampEnabled = true)
            } else it.copy(aiEqEnabled = true)
        } else {
            val restore = eqEnabledByAi && it.eqEnabled
            eqEnabledByAi = false
            if (restore) it.copy(aiEqEnabled = false, eqEnabled = false, preampEnabled = false)
            else it.copy(aiEqEnabled = false)
        }
    }
    fun saveCustomEqPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val currentConfig = _uiState.value.dsp.config
        val updated = loadCustomEqPresets()
            .filterNot { it.name.equals(trimmed, ignoreCase = true) }
            .plus(SavedEqPreset(trimmed, currentConfig.eqBands, currentConfig.preampDb))
            .sortedBy { it.name.lowercase() }
        persistCustomEqPresets(updated)
        _uiState.update { it.copy(dsp = it.dsp.copy(customEqPresets = updated)) }
    }

    fun applySavedEqPreset(name: String) {
        if (_uiState.value.dsp.config.settingsLocked) return
        val preset = _uiState.value.dsp.customEqPresets.firstOrNull { it.name == name } ?: return
        applyDspConfig { it.copy(
            eqEnabled = true,
            eqBands = preset.bands,
            preampDb = preset.preampDb,
            preampEnabled = true,
            autoEqEnabled = false,
            autoEqProfile = null
        ) }
    }

    fun deleteCustomEqPreset(name: String) {
        val updated = _uiState.value.dsp.customEqPresets.filterNot { it.name == name }
        persistCustomEqPresets(updated)
        _uiState.update { it.copy(dsp = it.dsp.copy(customEqPresets = updated)) }
        viewModelScope.launch { dspPreferences.clearAssignmentsForPreset(name) }
    }

    fun renameCustomEqPreset(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || oldName == trimmed) return
        val current = loadCustomEqPresets().toMutableList()
        val index = current.indexOfFirst { it.name == oldName }
        if (index != -1) {
            val preset = current[index]
            current.removeAt(index)
            // Check if new name already exists, if so remove it to overwrite
            current.removeAll { it.name.equals(trimmed, ignoreCase = true) }
            current.add(preset.copy(name = trimmed))
            val updated = current.sortedBy { it.name.lowercase() }
            persistCustomEqPresets(updated)
            _uiState.update { it.copy(dsp = it.dsp.copy(customEqPresets = updated)) }
            viewModelScope.launch { dspPreferences.renameAssignedPreset(oldName, trimmed) }
        }
    }

    fun importEqPresets(presets: List<SavedEqPreset>) {
        val current = loadCustomEqPresets().toMutableList()
        presets.forEach { imported ->
            current.removeAll { it.name.equals(imported.name, ignoreCase = true) }
            current.add(imported)
        }
        val updated = current.sortedBy { it.name.lowercase() }
        persistCustomEqPresets(updated)
        _uiState.update { it.copy(dsp = it.dsp.copy(customEqPresets = updated)) }
    }
    fun setBassEnabled(enabled: Boolean) = applyDspConfig { it.copy(bassEnabled = enabled) }
    fun setBassDb(value: Float) = applyDspConfig { it.copy(bassDb = value.coerceIn(-12f, 12f), bassEnabled = true) }
    fun setTrebleEnabled(enabled: Boolean) = applyDspConfig { it.copy(trebleEnabled = enabled) }
    fun setTrebleDb(value: Float) = applyDspConfig { it.copy(trebleDb = value.coerceIn(-12f, 12f), trebleEnabled = true) }
    fun setAirEnabled(enabled: Boolean) = applyDspConfig { it.copy(airEnabled = enabled) }
    fun setAirDb(value: Float) = applyDspConfig { it.copy(airDb = value.coerceIn(-12f, 12f), airEnabled = true) }
    fun setBalanceEnabled(enabled: Boolean) = applyDspConfig { it.copy(balanceEnabled = enabled) }
    fun setBalance(value: Float) = applyDspConfig { it.copy(balance = value.coerceIn(-1f, 1f), balanceEnabled = true) }
    fun setStereoExpansionEnabled(enabled: Boolean) = applyDspConfig { it.copy(stereoExpansionEnabled = enabled) }
    fun setStereoWidth(value: Float) = applyDspConfig { it.copy(stereoWidth = value.coerceIn(0.5f, 2f), stereoExpansionEnabled = true) }
    fun setReverbEnabled(enabled: Boolean) = applyDspConfig { it.copy(reverbEnabled = enabled) }
    fun setReverbAmount(value: Float) = applyDspConfig { it.copy(reverbAmount = value.coerceIn(0f, 1f), reverbEnabled = true) }
    /**
     * Selecting a named environment also loads its values into the knobs so the UI shows what is
     * actually playing. "FLAT"/"CUSTOM" keep the current knob values.
     */
    fun setReverbPreset(preset: String) = applyDspConfig { cfg ->
        val values = com.beatraxus.app.model.REVERB_NAMED_PRESETS[preset]
        if (values == null) {
            cfg.copy(reverbPreset = preset)
        } else {
            cfg.copy(
                reverbPreset = preset,
                reverbRoomSize = values.room,
                reverbDecay = values.room,
                reverbDamping = values.damping,
                reverbWidth = values.width,
                reverbPredelayMs = values.predelayMs
            )
        }
    }

    /** Moving a knob while a named or saved environment is active turns it into a custom setting (the engine ignores knobs for built-in presets). */
    private fun com.beatraxus.app.model.DspConfig.customReverbPresetName(): String =
        if (reverbPreset == "FLAT" || reverbPreset == "CUSTOM") reverbPreset else "CUSTOM"

    fun setReverbDamping(value: Float) = applyDspConfig { it.copy(reverbDamping = value.coerceIn(0f, 1f), reverbPreset = it.customReverbPresetName()) }
    fun setReverbWidth(value: Float) = applyDspConfig { it.copy(reverbWidth = value.coerceIn(0f, 1f), reverbPreset = it.customReverbPresetName()) }
    fun setReverbRoomSize(value: Float) = applyDspConfig { it.copy(reverbRoomSize = value.coerceIn(0f, 1f), reverbPreset = it.customReverbPresetName()) }
    fun setReverbDecay(value: Float) = applyDspConfig { it.copy(reverbDecay = value.coerceIn(0f, 1f), reverbPreset = it.customReverbPresetName()) }
    fun setReverbParams(roomSize: Float, damping: Float) = applyDspConfig {
        it.copy(
            reverbRoomSize = roomSize.coerceIn(0f, 1f),
            reverbDamping = damping.coerceIn(0f, 1f),
            reverbPreset = it.customReverbPresetName()
        )
    }
    fun setReverbPredelayMix(value: Float) = applyDspConfig { it.copy(reverbPredelayMix = value.coerceIn(0f, 1f)) }
    fun setReverbPredelay(value: Float) = applyDspConfig { it.copy(reverbPredelayMs = value.coerceIn(0f, 1000f), reverbPreset = it.customReverbPresetName()) }

    /** Saves the current reverb knobs under [name]. Names that clash with built-in environments get a suffix. */
    fun saveReverbPreset(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty()) return
        val reserved = com.beatraxus.app.model.REVERB_NAMED_PRESETS.keys + setOf("FLAT", "CUSTOM")
        val finalName = if (trimmed.uppercase() in reserved) "$trimmed (mine)" else trimmed
        applyDspConfig { cfg ->
            val preset = com.beatraxus.app.model.SavedReverbPreset(
                name = finalName,
                room = cfg.reverbRoomSize,
                decay = cfg.reverbDecay,
                damping = cfg.reverbDamping,
                width = cfg.reverbWidth,
                predelayMs = cfg.reverbPredelayMs,
                mix = cfg.reverbPredelayMix
            )
            cfg.copy(
                reverbCustomPresets = (cfg.reverbCustomPresets.filter { !it.name.equals(finalName, ignoreCase = true) } + preset)
                    .sortedBy { it.name.lowercase() },
                reverbPreset = finalName
            )
        }
    }

    fun loadReverbPreset(name: String) = applyDspConfig { cfg ->
        val p = cfg.reverbCustomPresets.firstOrNull { it.name == name } ?: return@applyDspConfig cfg
        cfg.copy(
            reverbPreset = p.name,
            reverbRoomSize = p.room,
            reverbDecay = p.decay,
            reverbDamping = p.damping,
            reverbWidth = p.width,
            reverbPredelayMs = p.predelayMs,
            reverbPredelayMix = p.mix
        )
    }

    fun deleteReverbPreset(name: String) = applyDspConfig { cfg ->
        cfg.copy(
            reverbCustomPresets = cfg.reverbCustomPresets.filter { it.name != name },
            reverbPreset = if (cfg.reverbPreset == name) "CUSTOM" else cfg.reverbPreset
        )
    }

    /** Restores every reverb control to its default. Unlike setReverbAmount(0f) this does not switch the reverb on. */
    fun resetReverb() = applyDspConfig {
        val d = com.beatraxus.app.model.DspConfig()
        it.copy(
            reverbPreset = d.reverbPreset,
            reverbAmount = d.reverbAmount,
            reverbPredelayMs = d.reverbPredelayMs,
            reverbWidth = d.reverbWidth,
            reverbDamping = d.reverbDamping,
            reverbRoomSize = d.reverbRoomSize,
            reverbDecay = d.reverbDecay,
            reverbPredelayMix = d.reverbPredelayMix
        )
    }
    fun setCrossfeedEnabled(enabled: Boolean) = applyDspConfig { it.copy(crossfeedEnabled = enabled) }
    fun setCrossfeedLevel(value: Float) = applyDspConfig { it.copy(crossfeedLevel = value.coerceIn(0f, 1f), crossfeedEnabled = true) }

    fun setSpatialAudioEnabled(enabled: Boolean) = applyDspConfig { it.copy(spatialAudioEnabled = enabled) }
    fun setSpatialAudioIntensity(value: Float) = applyDspConfig { it.copy(spatialAudioIntensity = value.coerceIn(0f, 1f)) }
    /** Stage Width on the Spatial tab. When the Master SOUNDSTAGE knob is active it owns the engine width, so keep both in step. */
    fun setSpatialStageWidth(value: Float) = applyDspConfig {
        val v = value.coerceIn(0f, 2f)
        it.copy(spatialStageWidth = v, soundStageWidth = if (it.soundStageEnabled) v else it.soundStageWidth)
    }
    fun setHrtfMode(mode: com.beatraxus.app.model.HrtfMode) = applyDspConfig { it.copy(hrtfMode = mode) }
    fun setSpatialUiMode(mode: com.beatraxus.app.model.SpatialUiMode) = applyDspConfig { it.copy(spatialUiMode = mode) }
    fun setSoundStageEnabled(enabled: Boolean) = applyDspConfig { it.copy(soundStageEnabled = enabled) }
    fun setSoundStageWidth(value: Float) = applyDspConfig { it.copy(soundStageWidth = value.coerceIn(0f, 2f), soundStageEnabled = true) }
    fun setSoundStageCenterLock(value: Float) = applyDspConfig { it.copy(soundStageCenterLock = value) }
    fun selectSoundStageNode(node: String) = applyDspConfig { it.copy(soundStageSelectedNode = node) }

    fun setSoundStagePosition(azimuth: Float, elevation: Float, distance: Float) {
        applyDspConfig { cfg ->
            val node = cfg.soundStageSelectedNode
            val updatedMap = cfg.soundStageNodePositions.toMutableMap()
            updatedMap[node] = com.beatraxus.app.model.SoundStageNodePosition(azimuth, elevation, distance)
            cfg.copy(soundStageNodePositions = updatedMap)
        }
    }

    /** RESET on the Spatial tab: put every instrument back at its own default azimuth/distance. */
    fun resetSoundStagePositions() {
        applyDspConfig { cfg ->
            cfg.copy(soundStageNodePositions = com.beatraxus.app.model.DEFAULT_SOUND_STAGE_NODE_POSITIONS)
        }
    }

    /**
     * AUTO on the Spatial tab: re-distribute the instruments evenly around the listener, keeping their
     * current left-to-right order and distances. Useful after dragging several of them into a cluster.
     */
    fun autoSpreadSoundStageNodes() {
        applyDspConfig { cfg ->
            val ordered = cfg.soundStageNodePositions.entries.sortedWith(compareBy({ it.value.azimuth }, { it.key }))
            if (ordered.isEmpty()) return@applyDspConfig cfg
            val step = 360f / ordered.size
            val start = ordered.first().value.azimuth
            val spread = LinkedHashMap<String, com.beatraxus.app.model.SoundStageNodePosition>()
            ordered.forEachIndexed { i, entry ->
                spread[entry.key] = entry.value.copy(azimuth = (start + i * step) % 360f)
            }
            cfg.copy(soundStageNodePositions = spread)
        }
    }

    fun setSoundStageAzimuth(value: Float) {
        applyDspConfig { cfg ->
            val node = cfg.soundStageSelectedNode
            val pos = cfg.soundStageNodePositions[node] ?: com.beatraxus.app.model.SoundStageNodePosition()
            val updatedMap = cfg.soundStageNodePositions.toMutableMap()
            updatedMap[node] = pos.copy(azimuth = value)
            cfg.copy(soundStageNodePositions = updatedMap)
        }
    }

    fun setSoundStageElevation(value: Float) {
        applyDspConfig { cfg ->
            val node = cfg.soundStageSelectedNode
            val pos = cfg.soundStageNodePositions[node] ?: com.beatraxus.app.model.SoundStageNodePosition()
            val updatedMap = cfg.soundStageNodePositions.toMutableMap()
            updatedMap[node] = pos.copy(elevation = value)
            cfg.copy(soundStageNodePositions = updatedMap)
        }
    }

    fun setSoundStageDistance(value: Float) {
        applyDspConfig { cfg ->
            val node = cfg.soundStageSelectedNode
            val pos = cfg.soundStageNodePositions[node] ?: com.beatraxus.app.model.SoundStageNodePosition()
            val updatedMap = cfg.soundStageNodePositions.toMutableMap()
            updatedMap[node] = pos.copy(distance = value)
            cfg.copy(soundStageNodePositions = updatedMap)
        }
    }

    fun setAudio3DStageEnabled(enabled: Boolean) = applyDspConfig { it.copy(audio3DStageEnabled = enabled) }

    // ── 5.1 / 7.1 Surround Simulator ────────────────────────────────────────────────────────────────────
    /** Master switch. Off = the output is exactly what it was before the simulator existed. */
    fun setSurroundEnabled(enabled: Boolean) = applyDspConfig { it.copy(surroundEnabled = enabled) }

    /** Stereo / 5.1 / 7.1 / Auto. Choosing a layout other than Stereo does not switch the master toggle on. */
    fun setSurroundLayout(layout: com.beatraxus.app.model.SurroundLayout) =
        applyDspConfig { it.copy(surroundLayout = layout) }

    fun setSurroundMode(mode: com.beatraxus.app.model.SurroundMode) =
        applyDspConfig { it.copy(surroundMode = mode) }

    /** One speaker's level trim in dB (index = [com.beatraxus.app.model.SurroundChannel] ordinal). */
    fun setSurroundChannelTrim(index: Int, db: Float) = applyDspConfig { cfg ->
        if (index !in 0 until com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB.size) return@applyDspConfig cfg
        val trims = com.beatraxus.app.model.sanitizeSurroundTrims(cfg.surroundChannelTrimsDb).toMutableList()
        trims[index] = db.coerceIn(com.beatraxus.app.model.SURROUND_TRIM_MIN_DB, com.beatraxus.app.model.SURROUND_TRIM_MAX_DB)
        // Moving a slider turns whatever preset was active into an unsaved mix (no preset highlighted).
        cfg.copy(surroundChannelTrimsDb = trims, surroundActivePreset = "")
    }

    fun resetSurroundTrims() = applyDspConfig {
        it.copy(
            surroundChannelTrimsDb = com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB,
            surroundActivePreset = "Default"
        )
    }

    fun applySurroundPreset(name: String) = applyDspConfig { cfg ->
        if (name == com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME) {
            val saved = cfg.surroundCustomTrimsDb ?: return@applyDspConfig cfg
            cfg.copy(
                surroundChannelTrimsDb = saved,
                surroundMode = cfg.surroundCustomMode,
                surroundActivePreset = name
            )
        } else {
            val preset = com.beatraxus.app.model.BUILT_IN_SURROUND_PRESETS.firstOrNull { it.name == name }
                ?: return@applyDspConfig cfg
            cfg.copy(
                surroundChannelTrimsDb = preset.trimsDb,
                surroundMode = preset.mode,
                surroundActivePreset = name
            )
        }
    }

    /** Stores the current trims + mode in the user slot ("Custom 1"). */
    fun saveSurroundCustomPreset() = applyDspConfig {
        it.copy(
            surroundCustomTrimsDb = com.beatraxus.app.model.sanitizeSurroundTrims(it.surroundChannelTrimsDb),
            surroundCustomMode = it.surroundMode,
            surroundActivePreset = com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME
        )
    }

    fun setAudio3DWidth(value: Float) = applyDspConfig { it.copy(audio3DWidth = value.coerceIn(0f, 2f)) }
    fun setAudio3DDepth(value: Float) = applyDspConfig { it.copy(audio3DDepth = value.coerceIn(0f, 1f)) }
    fun setAudio3DHeight(value: Float) = applyDspConfig { it.copy(audio3DHeight = value.coerceIn(-1f, 1f)) }
    fun setAudio3DDistance(value: Float) = applyDspConfig { it.copy(audio3DDistance = value.coerceIn(0.3f, 3f)) }
    fun setAudio3DCenterFocus(value: Float) = applyDspConfig { it.copy(audio3DCenterFocus = value.coerceIn(0f, 1f)) }
    fun setAudio3DRoomReflections(value: Float) = applyDspConfig { it.copy(audio3DRoomReflections = value.coerceIn(0f, 1f)) }

    fun setSpeakerPosition(id: String, azimuth: Float, elevation: Float, distance: Float) {
        applyDspConfig { cfg ->
            val exists = cfg.audio3DSpeakerPositions.any { it.id == id }
            val newList = if (exists) {
                cfg.audio3DSpeakerPositions.map {
                    if (it.id == id) it.copy(azimuthDeg = azimuth, elevationDeg = elevation, distance = distance) else it
                }
            } else {
                cfg.audio3DSpeakerPositions + com.beatraxus.app.model.Audio3DSpeakerPosition(id, azimuth, elevation, distance)
            }
            cfg.copy(audio3DSpeakerPositions = newList)
        }
    }

    fun saveAudio3DPreset(name: String) {
        applyDspConfig { cfg ->
            val newPreset = Audio3DStagePreset(
                name = name,
                width = cfg.audio3DWidth,
                depth = cfg.audio3DDepth,
                height = cfg.audio3DHeight,
                distance = cfg.audio3DDistance,
                centerFocus = cfg.audio3DCenterFocus,
                roomReflections = cfg.audio3DRoomReflections,
                speakerPositions = cfg.audio3DSpeakerPositions
            )
            cfg.copy(audio3DPresets = cfg.audio3DPresets.filter { it.name != name } + newPreset)
        }
    }

    fun loadAudio3DPreset(name: String) {
        applyDspConfig { cfg ->
            val preset = cfg.audio3DPresets.find { it.name == name } ?: return@applyDspConfig cfg
            cfg.copy(
                audio3DWidth = preset.width,
                audio3DDepth = preset.depth,
                audio3DHeight = preset.height,
                audio3DDistance = preset.distance,
                audio3DCenterFocus = preset.centerFocus,
                audio3DRoomReflections = preset.roomReflections,
                audio3DSpeakerPositions = preset.speakerPositions
            )
        }
    }

    fun deleteAudio3DPreset(name: String) {
        applyDspConfig { cfg ->
            cfg.copy(audio3DPresets = cfg.audio3DPresets.filter { it.name != name })
        }
    }

    fun setDcBlockerEnabled(enabled: Boolean) = applyDspConfig { it.copy(dcBlockerEnabled = enabled) }
    fun setMonoEnabled(enabled: Boolean) = applyDspConfig { it.copy(monoEnabled = enabled) }

    fun setSettingsLocked(locked: Boolean) = applyDspConfig { it.copy(settingsLocked = locked) }

    fun setUsbExclusiveMode(enabled: Boolean) {
        applyDspConfig { it.copy(usbExclusiveEnabled = enabled) }
    }

    fun setHardwareVolumeMode(enabled: Boolean) {
        applyDspConfig { it.copy(hardwareVolumeEnabled = enabled) }
    }

    fun setBitPerfectMode(enabled: Boolean) {
        applyDspConfig { config ->
            if (!enabled) {
                // Reset unbypass options when turning OFF Bit-Perfect mode
                config.copy(
                    bitPerfectEnabled = false,
                    bitPerfectUnbypassEq = false,
                    bitPerfectUnbypassResample = false,
                    bitPerfectUnbypassSoxr = false,
                    bitPerfectUnbypassReverb = false,
                    bitPerfectUnbypass3DStage = false,
                    bitPerfectUnbypassDithering = false,
                    bitPerfectUnbypassFloat64 = false,
                    bitPerfectUnbypassLimiter = false
                )
            } else {
                config.copy(bitPerfectEnabled = true)
            }
        }
    }

    private fun checkBitPerfectUnbypassLogic(config: DspConfig): DspConfig {
        val allEnabled = config.bitPerfectUnbypassEq &&
                config.bitPerfectUnbypassResample &&
                config.bitPerfectUnbypassSoxr &&
                config.bitPerfectUnbypassReverb &&
                config.bitPerfectUnbypass3DStage &&
                config.bitPerfectUnbypassDithering &&
                config.bitPerfectUnbypassFloat64 &&
                config.bitPerfectUnbypassLimiter

        return if (allEnabled) {
            config.copy(
                bitPerfectEnabled = false,
                bitPerfectUnbypassEq = false,
                bitPerfectUnbypassResample = false,
                bitPerfectUnbypassSoxr = false,
                bitPerfectUnbypassReverb = false,
                bitPerfectUnbypass3DStage = false,
                bitPerfectUnbypassDithering = false,
                bitPerfectUnbypassFloat64 = false,
                bitPerfectUnbypassLimiter = false
            )
        } else {
            config
        }
    }

    fun setBitPerfectUnbypassEq(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassEq = enabled))
    }

    fun setBitPerfectUnbypassResample(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassResample = enabled))
    }

    fun setBitPerfectUnbypassSoxr(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassSoxr = enabled))
    }

    fun setBitPerfectUnbypassReverb(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassReverb = enabled))
    }

    fun setBitPerfectUnbypass3DStage(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypass3DStage = enabled))
    }

    fun setBitPerfectUnbypassDithering(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassDithering = enabled))
    }

    fun setBitPerfectUnbypassFloat64(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassFloat64 = enabled))
    }

    fun setBitPerfectUnbypassLimiter(enabled: Boolean) = applyDspConfig {
        checkBitPerfectUnbypassLogic(it.copy(bitPerfectUnbypassLimiter = enabled))
    }

    fun setSoxrQuality(quality: com.beatraxus.app.model.SoxrQuality) {
        applyDspConfig { it.copy(soxrQuality = quality) }
    }

    fun setFloat64Enabled(enabled: Boolean) {
        applyDspConfig { it.copy(float64Enabled = enabled) }
    }

    fun setMmapBufferSize(frames: Int) {
        applyDspConfig { it.copy(mmapRequestedBufferSizeFrames = frames) }
    }

    fun setOutputBufferMs(ms: Int) {
        applyDspConfig { it.copy(outputBufferMs = ms.coerceIn(10, 200)) }
    }

    fun setOutputBufferCount(count: Int) {
        applyDspConfig { it.copy(outputBufferCount = count.coerceIn(2, 4)) }
    }

    fun setPostFadeBufferMs(ms: Int) {
        applyDspConfig { it.copy(postFadeBufferMs = ms.coerceIn(0, 100)) }
    }

    fun setDitherEnabled(enabled: Boolean) {
        applyDspConfig { it.copy(ditherEnabled = enabled) }
    }

    fun setDitherType(type: com.beatraxus.app.model.DitherType) {
        applyDspConfig { it.copy(ditherType = type) }
    }

    // Replay Gain
    fun setReplayGainEnabled(enabled: Boolean) = applyDspConfig { it.copy(replayGainEnabled = enabled) }
    fun setReplayGainOption(option: ReplayGainOption) = applyDspConfig { it.copy(replayGainOption = option) }
    fun setReplayGainSource(source: ReplayGainSource) = applyDspConfig { it.copy(replayGainSource = source) }
    fun setReplayGainPreamp(db: Float) = applyDspConfig { it.copy(replayGainPreamp = db) }
    fun setDvcEnabled(enabled: Boolean) = applyDspConfig { it.copy(dvcEnabled = enabled) }
    fun setDvcBluetoothEnabled(enabled: Boolean) = applyDspConfig { it.copy(dvcBluetoothEnabled = enabled) }
    fun setRmsDvcEnabled(enabled: Boolean) = applyDspConfig { it.copy(rmsDvcEnabled = enabled) }
    fun setRmsLevelerEnabled(enabled: Boolean) = applyDspConfig { it.copy(rmsLevelerEnabled = enabled) }
    fun setDvcMode(mode: DvcMode) = applyDspConfig { it.copy(dvcMode = mode) }
    fun setDvcLevel(level: Float) = applyDspConfig { it.copy(dvcLevel = level.coerceIn(0f, 1f)) }
    fun setCompensateDvcVolumeEnabled(enabled: Boolean) = applyDspConfig { it.copy(compensateDvcVolumeEnabled = enabled) }
    fun setDvcCompensationDb(db: Float) = applyDspConfig { it.copy(dvcCompensationDb = db) }
    fun setSoftLimiterEnabled(enabled: Boolean) = applyDspConfig {
        if (enabled) it.copy(softLimiterEnabled = true, limiterEnabled = false)
        else it.copy(softLimiterEnabled = false)
    }

    fun setLimiterEnabled(enabled: Boolean) = applyDspConfig {
        if (enabled) it.copy(limiterEnabled = true, softLimiterEnabled = false)
        else it.copy(limiterEnabled = false)
    }
    fun setLimiterThresholdDb(db: Float) = applyDspConfig { it.copy(limiterThresholdDb = db) }
    fun setLimiterAttackMs(ms: Float) = applyDspConfig { it.copy(limiterAttackMs = ms) }
    fun setLimiterReleaseMs(ms: Float) = applyDspConfig { it.copy(limiterReleaseMs = ms) }
    fun setLimiterHardModeEnabled(enabled: Boolean) = applyDspConfig { it.copy(limiterHardModeEnabled = enabled) }

    fun setSystemVolume(normalizedVolume: Float) {
        val am = getApplication<Application>().getSystemService(AudioManager::class.java)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (normalizedVolume * maxVol).roundToInt(), 0)
        // Also update DSP internal volume (for DVC path)
        // Use square-law for internal gain as requested for perceptual taper
        setDvcLevel(normalizedVolume * normalizedVolume)
        if (_uiState.value.showVolumeOverlay) resetVolumeHideTimer()
    }

    private var volumeHideJob: Job? = null

    fun toggleVolumeOverlay() {
        _uiState.update { it.copy(showVolumeOverlay = !it.showVolumeOverlay) }
        if (_uiState.value.showVolumeOverlay) {
            resetVolumeHideTimer()
        }
    }

    fun showVolumeOverlay() {
        _uiState.update { it.copy(showVolumeOverlay = true) }
        resetVolumeHideTimer()
    }

    private fun resetVolumeHideTimer() {
        volumeHideJob?.cancel()
        volumeHideJob = viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(showVolumeOverlay = false) }
        }
    }

    fun incrementVolume() {
        val current = _uiState.value.dsp.config.dvcLevel
        val sliderPos = kotlin.math.sqrt(current)
        val nextSliderPos = (sliderPos + 0.01f).coerceIn(0f, 1f)
        setSystemVolume(nextSliderPos)
        showVolumeOverlay()
    }

    fun decrementVolume() {
        val current = _uiState.value.dsp.config.dvcLevel
        val sliderPos = kotlin.math.sqrt(current)
        val nextSliderPos = (sliderPos - 0.01f).coerceIn(0f, 1f)
        setSystemVolume(nextSliderPos)
        showVolumeOverlay()
    }

    fun setEqBandEnabled(index: Int, enabled: Boolean) {
        applyEqBand(index) { it.copy(enabled = enabled) }
    }

    fun setEqMasterGainDb(gain: Float) {
        applyDspConfig {
            if (it.settingsLocked) return@applyDspConfig it
            it.copy(eqMasterGainDb = gain)
        }
    }

    fun setEqBandFrequency(index: Int, frequencyHz: Float) {
        applyEqBand(index) { it.copy(frequencyHz = frequencyHz.coerceIn(20f, 20_000f)) }
    }

    fun setEqBandGain(index: Int, gainDb: Float) {
        applyEqBand(index) { it.copy(gainDb = gainDb.coerceIn(-12f, 12f)) }
    }

    fun setAllEqGains(gains: List<Float>) {
        if (_uiState.value.dsp.config.settingsLocked) return
        applyDspConfig { config ->
            val defaultBands = defaultEqBands()
            config.copy(
                eqEnabled = true,
                autoEqEnabled = false,
                autoEqProfile = null,
                eqBands = defaultBands.mapIndexed { i, band ->
                    if (i < gains.size) {
                        band.copy(gainDb = gains[i].coerceIn(-12f, 12f))
                    } else {
                        band
                    }
                }
            )
        }
    }

    fun setEqBandQ(index: Int, q: Float) {
        applyEqBand(index) { it.copy(q = q.coerceIn(0.2f, 8f)) }
    }

    fun setEqBandType(index: Int, type: com.beatraxus.app.model.EqBandType) {
        applyEqBand(index) { it.copy(type = type) }
    }

    private fun applyEqBand(index: Int, transform: (ParametricEqBand) -> ParametricEqBand) {
        applyDspConfig { config ->
            if (config.settingsLocked) return@applyDspConfig config
            config.copy(
                eqEnabled = true,
                autoEqEnabled = false, // Disable AutoEQ flag when manually overriding
                autoEqProfile = null,
                eqBands = config.eqBands.mapIndexed { bandIndex, band ->
                    if (bandIndex == index) transform(band) else band
                }
            )
        }
    }

    fun setAutoEqQuery(query: String) {
        _uiState.update { it.copy(dsp = it.dsp.copy(autoEqQuery = query)) }
    }

    fun clearAutoEqResults() {
        _uiState.update { it.copy(dsp = it.dsp.copy(autoEqResults = emptyList(), autoEqError = null)) }
    }

    fun searchAutoEqProfiles() {
        val query = _uiState.value.dsp.autoEqQuery

        viewModelScope.launch {
            // 1. Show local results immediately (returns all if query is blank)
            val localResults = withContext(Dispatchers.Default) {
                autoEqRepository.searchProfiles(query)
            }
            _uiState.update { state ->
                state.copy(dsp = state.dsp.copy(autoEqResults = localResults))
            }

            if (query.isBlank()) {
                _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = false)) }
                return@launch
            }

            // 2. Online search
            _uiState.update { state -> state.copy(dsp = state.dsp.copy(autoEqLoading = true)) }
            try {
                val onlineResults = autoEqApiService.searchProfiles(query)
                val filteredOnline = onlineResults.filter { online ->
                    localResults.none { it.name.equals(online.name, ignoreCase = true) }
                }

                _uiState.update { state ->
                    val combined = (state.dsp.autoEqResults + filteredOnline)
                        .sortedBy { it.name.lowercase(java.util.Locale.US) }
                    state.copy(dsp = state.dsp.copy(
                        autoEqResults = combined,
                        autoEqLoading = false
                    ))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = false)) }
            }
        }
    }

    fun applyAutoEqProfile(summary: AutoEqProfileSummary) {
        viewModelScope.launch {
            _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = true)) }
            try {
                val profile = if (summary.source.startsWith("GITHUB:")) {
                    autoEqApiService.fetchProfile(summary)
                } else {
                    autoEqRepository.loadProfile(summary)
                }

                if (profile == null) {
                    _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = false, autoEqError = "Failed to load profile")) }
                    return@launch
                }

                applyDspConfig { config ->
                    config.copy(
                        autoEqEnabled = true, // FIX: Use full high-precision bands in engine
                        autoEqProfile = profile,
                        eqEnabled = true,
                        // Update visual bands for UI feedback (optional but helpful)
                        eqBands = config.eqBands.map { localBand ->
                            val closest = profile.bands.minByOrNull {
                                kotlin.math.abs(it.frequencyHz - localBand.frequencyHz)
                            }
                            if (closest != null && kotlin.math.abs(closest.frequencyHz - localBand.frequencyHz) < localBand.frequencyHz * 0.4f) {
                                // Important: maintain the type (Shelf vs Peaking) correctly if we were to use these for processing,
                                // but here we are using the profile directly in the engine instead.
                                localBand.copy(gainDb = closest.gainDb, q = closest.q, enabled = true)
                            } else {
                                localBand.copy(gainDb = 0f, q = 1.0f, enabled = true)
                            }
                        },
                        preampDb = profile.preampDb,
                        preampEnabled = true // AutoEQ requires its preamp to prevent clipping
                    )
                }
                _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = false)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(dsp = it.dsp.copy(autoEqLoading = false, autoEqError = e.message)) }
            }
        }
    }
    fun clearAutoEqProfile() {
        applyDspConfig { it.copy(autoEqEnabled = false, autoEqProfile = null) }
        _uiState.update {
            it.copy(
                dsp = it.dsp.copy(
                    autoEqError = null,
                    autoEqResults = emptyList()
                )
            )
        }
    }

    private fun loadCustomEqPresets(): List<SavedEqPreset> =
        parseCustomEqPresets(prefs.getString(KEY_CUSTOM_EQ_PRESETS, null))

    private fun parseCustomEqPresets(raw: String?): List<SavedEqPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val name = item.optString("name")
                    val preampDb = item.optDouble("preampDb", 0.0).toFloat()
                    val bandsJson = item.optJSONArray("bands") ?: JSONArray()
                    val bands = buildList {
                        for (bandIndex in 0 until bandsJson.length()) {
                            val band = bandsJson.getJSONObject(bandIndex)
                            add(
                                ParametricEqBand(
                                    id = band.optInt("id", bandIndex),
                                    enabled = band.optBoolean("enabled", true),
                                    frequencyHz = band.optDouble("frequencyHz", 1000.0).toFloat(),
                                    gainDb = band.optDouble("gainDb", 0.0).toFloat(),
                                    q = band.optDouble("q", 1.0).toFloat(),
                                    type = runCatching {
                                        EqBandType.valueOf(band.optString("type", EqBandType.PEAKING.name))
                                    }.getOrDefault(EqBandType.PEAKING)
                                )
                            )
                        }
                    }
                    if (name.isNotBlank() && bands.isNotEmpty()) {
                        add(SavedEqPreset(name, bands, preampDb))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistCustomEqPresets(presets: List<SavedEqPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            val presetObject = JSONObject()
            presetObject.put("name", preset.name)
            presetObject.put("preampDb", preset.preampDb.toDouble())
            val bandsArray = JSONArray()
            preset.bands.forEach { band ->
                val bandObject = JSONObject()
                bandObject.put("id", band.id)
                bandObject.put("enabled", band.enabled)
                bandObject.put("frequencyHz", band.frequencyHz.toDouble())
                bandObject.put("gainDb", band.gainDb.toDouble())
                bandObject.put("q", band.q.toDouble())
                bandObject.put("type", band.type.name)
                bandsArray.put(bandObject)
            }
            presetObject.put("bands", bandsArray)
            array.put(presetObject)
        }
        prefs.edit().putString(KEY_CUSTOM_EQ_PRESETS, array.toString()).apply()
    }

    fun toggleShuffle() {
        service?.toggleShuffle()
    }

    fun toggleRepeat() {
        service?.toggleRepeat()
    }

    fun setSortType(sortType: SortType) {
        _uiState.update { it.copy(sortType = sortType) }
    }

    /** Video library feature 21: the video sort is separate from the music sort and remembered for the next launch. */
    fun setVideoSortType(sortType: SortType) {
        prefs.edit().putString("video_sort_type", sortType.name).apply()
        _uiState.update { it.copy(videoSortType = sortType) }
    }

    fun toggleVideoSortOrder() {
        val ascending = !_uiState.value.videoSortAscending
        prefs.edit().putBoolean("video_sort_ascending", ascending).apply()
        _uiState.update { it.copy(videoSortAscending = ascending) }
    }

    /** Current AudioTrack session id for the Music Detail Inspector's live meters (Phase 6),
     *  or 0 if playback isn't active / the service isn't bound yet. */
    fun getCurrentAudioSessionId(): Int = service?.getAudioSessionId() ?: 0

    /** MMAP-safe live PCM window for the Inspector's Live Meters — see
     *  [com.beatraxus.app.engine.AudioOutput.captureLiveWindow]. */
    fun captureLiveWindow(): com.beatraxus.app.engine.AudioOutput.LiveCapture? = service?.captureLiveWindow()

    /** Runs the DSP + quality scoring for one song and stores SongQualityEntity (+ AI entity). */
    private suspend fun analyzeAndStoreQuality(song: Song) {
            // Runs the native DSP feature extraction ONCE per song; both the AI
            // entity and the quality entity below are built from this same result
            // so scanning doesn't get twice as slow.
            val result = try { aiAnalysisEngine.analyzeSong(song) } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("PlayerViewModel", "DSP analysis failed for ${song.title}: ${t.message}")
                null
            }
            val analysis = result?.aiAnalysis
            if (analysis != null) {
                aiAnalysisDao.insertAnalysis(analysis)

                // The AI only fills a MISSING genre tag; it never overwrites one the user's files already carry
                // (the library groups by the cleaned-up tag + AI profile anyway, see SongProfiler).
                if (analysis.genre.isNotEmpty() && analysis.genre != song.genre &&
                    com.beatraxus.app.engine.smart.GenreTaxonomy.isUnknown(song.genre) &&
                    !com.beatraxus.app.engine.smart.GenreTaxonomy.isUnknown(analysis.genre)
                ) {
                    val updatedSong = song.copy(genre = analysis.genre)
                    withContext(Dispatchers.IO) {
                        songDao.insertSong(updatedSong.toEntity())
                    }
                    _songs.update { current ->
                        current.map { if (it.id == song.id) updatedSong else it }
                    }
                }
            }

            // Quality analysis (Phase 3) — same LOCAL-only guard as analyzeSong,
            // since result.features is null for skipped/failed songs.
            val features = result?.features
            var resolutionFromSpectrum: AudioSpectrumAnalyzer.SpectrumAnalysisResult? = null

            if (features == null) {
                // Task 4: Fallback for ALAC + cloud formats that NativeDsp skips
                resolutionFromSpectrum = try { audioSpectrumAnalyzer.getOrAnalyze(song) } catch (t: Throwable) { null }
            }

            // ALWAYS store a result. When neither analysis could run (unsupported codec,
            // unreadable cloud file...) we still score from the file metadata so the
            // Inspector never sits on "Analyzing..." forever.
            run {
                val scored = com.beatraxus.app.engine.QualityScorer.score(
                    bitrateKbps = song.bitrate,
                    sampleRateHz = resolutionFromSpectrum?.sampleRateHz ?: song.sampleRateHz,
                    bitDepth = resolutionFromSpectrum?.bitDepth ?: song.bitDepth,
                    codec = song.format,
                    lufs = features?.lufs ?: -14.0f,
                    dynamicRange = features?.dynamicRange ?: 10.0f,
                    truePeakDb = features?.truePeakDb ?: -1.0f,
                    clippedSamplePct = features?.clippedSamplePct ?: 0.0f,
                    stereoWidth = features?.stereoWidth ?: 1.0f
                )
                songQualityDao.upsertQuality(
                    com.beatraxus.app.model.SongQualityEntity(
                        songId = song.id,
                        bitrateKbps = song.bitrate,
                        sampleRateHz = resolutionFromSpectrum?.sampleRateHz ?: song.sampleRateHz,
                        bitDepth = resolutionFromSpectrum?.bitDepth ?: song.bitDepth,
                        codec = song.format,
                        lufs = features?.lufs ?: -14.0f,
                        dynamicRange = features?.dynamicRange ?: 10.0f,
                        truePeakDb = features?.truePeakDb ?: -1.0f,
                        clippedSamplePct = features?.clippedSamplePct ?: 0.0f,
                        stereoWidth = features?.stereoWidth ?: 1.0f,
                        freqRangeLowHz = features?.freqRangeLowHz ?: 0f,
                        freqRangeHighHz = features?.freqRangeHighHz ?: (resolutionFromSpectrum?.spectralCutoffHz?.toFloat() ?: 0f),
                        qualityScore = scored.score,
                        qualityTier = scored.tier,
                        analysisVersion = 1,
                        lastAnalyzed = System.currentTimeMillis()
                    )
                )
            }
    }

    private val qualityInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Kicks off quality analysis for a single song right away instead of waiting for the
     *  next periodic catch-up pass — used by the Inspector screen when it opens on a song
     *  that has no SongQualityEntity yet, so "Analyzing on next scan…" doesn't get stuck
     *  waiting for a scan that may never queue that particular song again (see the
     *  qualityDone filter below). Safe to call repeatedly; upserts on completion. */
    fun requestQualityAnalysis(song: Song) {
        // Runs immediately (not behind the library queue, and not paused while music plays),
        // because the person is staring at the Inspector waiting for it.
        if (!qualityInFlight.add(song.id)) return
        viewModelScope.launch(aiAnalysisDispatcher) {
            try {
                analyzeAndStoreQuality(song)
            } catch (t: Throwable) {
                Log.e("PlayerViewModel", "Quality analysis failed for ${song.title}: ${t.message}", t)
            } finally {
                qualityInFlight.remove(song.id)
            }
        }
    }

    /** Throws away every predicted AI EQ profile; the playing song and queue are predicted again straight away. */
    fun resetAiEqPredictions() {
        aiEqManager.resetAll()
        val state = _uiState.value
        if (state.dsp.config.aiEqEnabled) aiEqManager.preload(state.currentSong, state.upcomingSongs)
    }

    /** True while an Inspector-triggered analysis for [songId] is running. */
    fun isQualityAnalysisRunning(songId: String): Boolean = qualityInFlight.contains(songId)

    // ---- Audio feature 13: hide songs, minimum duration ----

    private fun updateHiddenSongIds(change: (Set<String>) -> Set<String>) {
        var result: Set<String> = emptySet()
        _uiState.update { state ->
            result = change(state.hiddenSongIds)
            state.copy(hiddenSongIds = result)
        }
        prefs.edit().putStringSet("hidden_song_ids", result.toSet()).apply()
    }

    /** Takes the songs out of every list until they are shown again from Settings. */
    fun hideSongs(ids: Collection<String>) {
        if (ids.isEmpty()) return
        updateHiddenSongIds { it + ids }
    }

    fun unhideSongs(ids: Collection<String>) {
        if (ids.isEmpty()) return
        updateHiddenSongIds { it - ids.toSet() }
    }

    fun unhideAllSongs() = updateHiddenSongIds { emptySet() }

    /** Hides the songs picked in multi-select. Ids that are not songs (an album or artist name) are ignored. */
    fun hideSelectedSongs() {
        val songIds = allSongs.value.mapTo(HashSet<String>()) { it.id }
        hideSongs(_uiState.value.selectedIds.filter { it in songIds })
        setMultiSelectMode(false)
    }

    /** Songs shorter than this many seconds are left out of the library. 0 turns the filter off. */
    fun setMinSongDurationSec(sec: Int) {
        val value = com.beatraxus.app.model.SongVisibility.normalizeMinDurationSec(sec)
        prefs.edit().putInt("min_song_duration_sec", value).apply()
        _uiState.update { it.copy(minSongDurationSec = value) }
    }

    /** Phase 4: library filter by audio-quality tier. Pass null for "All". */
    fun setQualityTierFilter(tier: String?) {
        _uiState.update { it.copy(qualityTierFilter = tier) }
    }

    fun toggleSortOrder() {
        _uiState.update { it.copy(isAscending = !it.isAscending) }
    }

    fun setViewMode(viewMode: ViewMode) {
        _uiState.update { it.copy(viewMode = viewMode) }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active) }
        if (!active) setSearchQuery("")
    }

    fun shuffleAndPlay() {
        val state = _uiState.value
        val allSongsList = allSongs.value
        val mode = state.libraryMode

        val modeSongs = when (mode) {
            LibraryMode.LOCAL -> allSongsList.filter { it.source == com.beatraxus.app.model.SongSource.LOCAL }
            LibraryMode.CLOUD -> allSongsList.filter { it.source != com.beatraxus.app.model.SongSource.LOCAL }
            LibraryMode.COMBINED -> allSongsList
            LibraryMode.ADDONS -> emptyList()
        }

        val songsToShuffle = when (state.currentView) {
            LibraryView.HOME, LibraryView.ALL_SONGS -> modeSongs
            LibraryView.FAVORITES -> modeSongs.filter { it.isFavorite }
            LibraryView.RECENTLY_ADDED -> modeSongs.filter { !it.isVideoFile }.sortedByDescending { it.dateAdded }
            LibraryView.RECENTLY_PLAYED -> {
                _recentlyPlayed.value.mapNotNull { id -> modeSongs.find { it.id == id } }
            }
            LibraryView.ALBUM_DETAIL -> modeSongs.filter { it.album == state.selectedItemName }
            LibraryView.ARTIST_DETAIL -> modeSongs.filter { song ->
                val target = state.selectedItemName ?: return@filter false
                ArtistNameUtils.splitArtists(song.artist)
                    .any { ArtistNameUtils.normalizeKey(it) == ArtistNameUtils.normalizeKey(target) }
            }
            LibraryView.FOLDER_DETAIL -> modeSongs.filter { it.folder == state.currentFolderPath }
            LibraryView.YEAR_DETAIL -> modeSongs.filter { it.year.toString() == state.selectedItemName }
            LibraryView.GENRE_DETAIL -> modeSongs.filter { inGenreGroup(it, state.selectedItemName) }
            LibraryView.PLAYLIST_DETAIL -> {
                val playlist = playlists.value.find { it.name == state.selectedItemName }
                playlist?.songIds?.mapNotNull { id -> allSongsList.find { it.id == id } } ?: emptyList()
            }
            LibraryView.CLOUD -> allSongsList.filter {
                if (state.selectedTelegramChannelUrl != null) {
                    it.source == com.beatraxus.app.model.SongSource.TELEGRAM && it.telegramChannelUrl == state.selectedTelegramChannelUrl
                } else if (state.selectedItemName != null) {
                    val target = state.selectedItemName.lowercase()
                    when (it.source) {
                        SongSource.GDRIVE -> it.driveAccountEmail?.lowercase() == target
                        SongSource.DROPBOX -> it.dropboxAccountEmail?.lowercase() == target
                        SongSource.ONEDRIVE -> it.onedriveAccountEmail?.lowercase() == target
                        SongSource.BOX -> it.boxAccountEmail?.lowercase() == target
                        SongSource.NEXTCLOUD -> it.nextcloudAccountEmail?.lowercase() == target
                        else -> false
                    }
                } else {
                    it.isCloud()
                }
            }
            else -> modeSongs
        }

        if (songsToShuffle.isNotEmpty()) {
            service?.setShuffleMode(true)
            val shuffled = smartShuffle(songsToShuffle)
            service?.playList(shuffled, 0)
            saveQueueToPrefs(shuffled, songsToShuffle, 0)
        }
    }

    private fun smartShuffle(songs: List<Song>): List<Song> {
        // If library is small, standard shuffle is fine
        if (songs.size <= 20) return songs.shuffled()

        // Deprioritize the last 150 songs played to ensure variety
        val deprioritizeLimit = 150
        val recentlyPlayedIds = _recentlyPlayed.value.take(deprioritizeLimit).toSet()

        val (recent, fresh) = songs.partition { it.id in recentlyPlayedIds }

        // Use a high-precision seed for randomization
        val random = java.util.Random(System.nanoTime())
        val shuffledFresh = fresh.shuffled(random)
        val shuffledRecent = recent.shuffled(random)

        // Fresh songs first, recently played songs last
        return shuffledFresh + shuffledRecent
    }

    // ── Lyrics translation (original line on top, translation below) ───────────────────────────
    private val lyricsTranslator by lazy {
        com.beatraxus.app.repository.lyrics.LyricsTranslator(getApplication<android.app.Application>())
    }
    private var lyricsTranslationJob: Job? = null
    /** songId|language the lines currently shown were translated for. */
    private var translatedFor: String? = null
    private val _lyricsTranslationStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lyricsTranslationStatus: kotlinx.coroutines.flow.StateFlow<String?> = _lyricsTranslationStatus

    init {
        // One observer covers every way lyrics can change (auto load, online search, picking a
        // source) and the on/off + language setting, so no call site has to remember to translate.
        viewModelScope.launch {
            _uiState
                .map { Triple(it.lyrics, it.appearance.lyricsTranslateEnabled, it.appearance.lyricsTranslateLanguage) }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second && a.third == b.third }
                .collect { (lyrics, enabled, language) -> onLyricsOrTranslationSettingChanged(lyrics, enabled, language) }
        }
    }

    private fun onLyricsOrTranslationSettingChanged(lyrics: List<LrcLine>, enabled: Boolean, language: String) {
        lyricsTranslationJob?.cancel()
        if (lyrics.isEmpty()) {
            translatedFor = null
            _lyricsTranslationStatus.value = null
            return
        }
        val hasTranslation = lyrics.any { it.translation != null }
        if (!enabled) {
            _lyricsTranslationStatus.value = null
            translatedFor = null
            if (hasTranslation) {
                _uiState.update { st -> if (st.lyrics === lyrics) st.copy(lyrics = lyrics.map { it.copy(translation = null) }) else st }
            }
            return
        }
        val songId = _uiState.value.lyricsCurrentSongId ?: return
        val marker = "$songId|$language"
        if (hasTranslation && translatedFor == marker) return // these are already our translated lines

        val base = if (hasTranslation) lyrics.map { it.copy(translation = null) } else lyrics
        _lyricsTranslationStatus.value = "Translating…"
        lyricsTranslationJob = viewModelScope.launch {
            val result = try {
                lyricsTranslator.translate(songId, base, language)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "lyrics translation failed", e)
                com.beatraxus.app.repository.lyrics.LyricsTranslator.Result.Unavailable
            }
            when (result) {
                is com.beatraxus.app.repository.lyrics.LyricsTranslator.Result.Translated -> {
                    _lyricsTranslationStatus.value = null
                    _uiState.update { st ->
                        // only if the song / lines did not change while we were translating
                        if (st.lyricsCurrentSongId == songId && st.lyrics === lyrics) {
                            translatedFor = marker
                            st.copy(lyrics = result.lines)
                        } else st
                    }
                }
                is com.beatraxus.app.repository.lyrics.LyricsTranslator.Result.SameLanguage ->
                    _lyricsTranslationStatus.value = "Lyrics are already in this language"
                com.beatraxus.app.repository.lyrics.LyricsTranslator.Result.Unavailable ->
                    _lyricsTranslationStatus.value = "Couldn't translate — check your connection"
            }
        }
    }

    fun setLyricsTranslation(enabled: Boolean, language: String) {
        viewModelScope.launch { appearancePreferences.setLyricsTranslation(enabled, language) }
    }

    fun toggleLyrics() {
        _uiState.update { it.copy(showLyrics = !it.showLyrics) }
        if (_uiState.value.showLyrics && (_uiState.value.lyrics.isEmpty() || _uiState.value.lyricsCurrentSongId != _uiState.value.currentSong?.id)) {
            loadLyrics(_uiState.value.currentSong)
        }
    }

    fun forceSearchLyricsOnline() {
        val song = _uiState.value.currentSong ?: return
        lyricsJob?.cancel()

        _uiState.update { it.copy(isLoadingLyrics = true, lyricsErrorMessage = null) }

        lyricsJob = viewModelScope.launch {
            try {
                // forceRefresh = true: this is an explicit user action, so it must always hit
                // the network — bypassing the 24h "recently not found" cache that's there to
                // avoid spamming the API on automatic/background lookups.
                val result = lyricsRepository.fetchOnline(song, forceRefresh = true)
                if (result != null) {
                    _uiState.update {
                        it.copy(
                            lyrics = result.lines,
                            lyricsCurrentIndex = -1,
                            isLoadingLyrics = false,
                            lyricsSource = result.source,
                            lyricsErrorMessage = null,
                            lyricsProviderId = result.providerId
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoadingLyrics = false,
                            lyricsErrorMessage = "No lyrics found online for this song",
                            lyricsProviderId = null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "forceSearchLyricsOnline failed for ${song.title}", e)
                _uiState.update {
                    it.copy(
                        isLoadingLyrics = false,
                        lyricsErrorMessage = "Couldn't reach the lyrics service — check your connection and try again",
                        lyricsProviderId = null
                    )
                }
            }
        }
    }

    fun loadLyricsCandidates() {
        val song = _uiState.value.currentSong ?: return
        
        _uiState.update { it.copy(isLoadingLyricsCandidates = true, lyricsCandidates = emptyList()) }
        
        viewModelScope.launch {
            val embeddedCandidate = lyricsRepository.fetchEmbeddedCandidate(song)
            if (embeddedCandidate != null) {
                _uiState.update { 
                    it.copy(lyricsCandidates = listOf(embeddedCandidate))
                }
            }

            val onlineCandidates = lyricsRepository.fetchAllCandidates(song)
            val allCandidates = onlineCandidates.filter { it.providerId != "embedded" } + listOfNotNull(embeddedCandidate)

            _uiState.update { 
                it.copy(
                    lyricsCandidates = allCandidates,
                    isLoadingLyricsCandidates = false
                ) 
            }
        }
    }

    fun applyLyricsCandidate(candidate: LyricsCandidate) {
        val song = _uiState.value.currentSong ?: return
        val lines = LrcParser.parse(candidate.content)
        
        val isEmbedded = candidate.providerId == "embedded"
        val source = if (isEmbedded) LyricsSource.EMBEDDED else LyricsSource.ONLINE

        _uiState.update {
            it.copy(
                lyrics = lines,
                lyricsCurrentIndex = -1,
                lyricsSource = source,
                lyricsProviderId = candidate.providerId
            )
        }
        
        viewModelScope.launch {
            lyricsRepository.saveLyrics(song.id, candidate.content, 0L)
            app.database.songDao().updateLyrics(song.id, candidate.content)
        }
    }

    fun clearSavedLyrics(clearFileTag: Boolean = false) {
        val song = _uiState.value.currentSong ?: return
        viewModelScope.launch {
            lyricsRepository.clearSavedLyrics(song, clearFileTag)
            loadLyrics(song)
        }
    }

    private fun loadLyrics(song: Song?) {
        lyricsJob?.cancel()

        if (song == null) {
            _uiState.update {
                it.copy(
                    lyrics = emptyList(),
                    lyricsCurrentIndex = -1,
                    lyricsCurrentSongId = null,
                    isLoadingLyrics = false,
                    lyricsSource = null
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                lyrics = emptyList(),
                lyricsCurrentIndex = -1,
                lyricsCurrentSongId = song.id,
                lyricsOffsetMs = song.lyricsOffsetMs,
                // Start in the Loading state (not false) as soon as the song changes. If this
                // stays false here, the lyrics screen briefly renders its "No lyrics found"
                // empty state (isLoading == false && lyrics.isEmpty()) for the one frame before
                // the flow below emits LyricsState.Loading — visible as a flash of "no lyrics"
                // on every song change even when embedded/online lyrics are about to load fine.
                isLoadingLyrics = true,
                lyricsSource = null
            )
        }

        lyricsJob = viewModelScope.launch {
            lyricsRepository.getLyrics(song).collect { state ->
                if (!isActive || _uiState.value.currentSong?.id != song.id) return@collect

                when (state) {
                    is LyricsState.Loading -> {
                        _uiState.update { it.copy(isLoadingLyrics = true) }
                    }
                    is LyricsState.Success -> {
                        _uiState.update {
                            it.copy(
                                lyrics = state.result.lines,
                                lyricsCurrentIndex = -1,
                                isLoadingLyrics = false,
                                lyricsSource = state.result.source,
                                lyricsProviderId = state.result.providerId
                            )
                        }
                    }
                    is LyricsState.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoadingLyrics = false,
                                lyrics = emptyList(),
                                lyricsSource = null,
                                lyricsProviderId = null,
                                // Only the .catch{}-driven crash path sets a message here (an
                                // unexpected exception); the routine "no lyrics found anywhere"
                                // case is not itself an error worth alarming the user about, and
                                // is left for the empty-state "tap to search online" hint instead.
                                lyricsErrorMessage = state.message.takeIf { msg -> msg.startsWith("Lyrics lookup failed") }
                            )
                        }
                    }
                }
            }
        }
    }

    private var lastPreloadKey: List<String> = emptyList()

    /**
     * Preloads lyric sources for the next 10 songs. The window is only (re)started when it
     * actually changes, and running work is never cancelled, so rapid playlist emissions can
     * no longer keep restarting (and wasting) the provider requests.
     */
    private fun preloadUpcomingLyrics(songs: List<Song>) {
        val targets = songs.take(10)
        val key = targets.map { it.id }
        if (key.isEmpty() || key == lastPreloadKey) return
        lastPreloadKey = key
        preloadLyricsJob?.cancel()
        preloadLyricsJob = viewModelScope.launch {
            awaitUiSettled(5000L)
            lyricsRepository.preloadLyrics(targets)
        }
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        val song = _uiState.value.currentSong ?: return
        val newOffset = _uiState.value.lyricsOffsetMs + deltaMs
        _uiState.update { it.copy(lyricsOffsetMs = newOffset) }

        viewModelScope.launch {
            val updatedSong = song.copy(lyricsOffsetMs = newOffset)
            withContext(Dispatchers.IO) {
                songDao.insertSong(updatedSong.toEntity())
            }
            _songs.update { current ->
                current.map { if (it.id == song.id) updatedSong else it }
            }
        }
    }

    fun setLyricsOffset(offset: Long) {
        val song = _uiState.value.currentSong ?: return
        _uiState.update { it.copy(lyricsOffsetMs = offset) }

        viewModelScope.launch {
            val updatedSong = song.copy(lyricsOffsetMs = offset)
            withContext(Dispatchers.IO) {
                songDao.insertSong(updatedSong.toEntity())
            }
            _songs.update { current ->
                current.map { if (it.id == song.id) updatedSong else it }
            }
        }
    }

    fun saveLyrics(songId: String, lyricsText: String) {
        viewModelScope.launch {
            lyricsRepository.saveLyrics(songId, lyricsText)
            // Reload lyrics if it's the current song
            if (_uiState.value.currentSong?.id == songId) {
                val lines = LrcParser.parse(lyricsText)
                _uiState.update {
                    it.copy(
                        lyrics = lines,
                        lyricsSource = LyricsSource.CACHE,
                        isLoadingLyrics = false
                    )
                }
            }
        }
    }

    private fun updateLyricsIndex(currentMs: Long) {
        val state = _uiState.value
        if (state.lyrics.isEmpty()) return

        if (state.lyrics.none { it.isTimed }) {
            if (state.lyricsCurrentIndex != -1) {
                _uiState.update { it.copy(lyricsCurrentIndex = -1) }
            }
            return
        }

        val adjustedMs = currentMs + state.lyricsOffsetMs
        val index = state.lyrics.indexOfLast { it.startTime <= adjustedMs }

        val activeIndex = if (index in state.lyrics.indices) {
            val line = state.lyrics[index]
            if (adjustedMs >= line.endTime) -1 else index
        } else {
            -1
        }

        if (activeIndex != state.lyricsCurrentIndex) {
            _uiState.update { it.copy(lyricsCurrentIndex = activeIndex) }
        }
    }

    private var lastPositionSaveTime = 0L

    private val progressFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val svc = service ?: return
            // Use service state directly to avoid being killed by stale UI state
            if (!svc.playbackStateFlow.value.isPlaying) {
                // Check if we should still try for a few frames in case of state lag
                return
            }
            val pos = svc.currentPositionMs
            if (_progressMs.value != pos) {
                _progressMs.value = pos
                updateLyricsIndex(pos)

                // Periodically save position (every 5 seconds)
                val now = System.currentTimeMillis()
                if (now - lastPositionSaveTime > 5000) {
                    prefs?.edit()?.putLong("last_song_pos", pos)?.apply()
                    lastPositionSaveTime = now
                }
            }
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_TICK_MS)
        }
    }

    private fun startProgressPolling() {
        Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
        Choreographer.getInstance().postFrameCallback(progressFrameCallback)
    }

    private fun stopProgressPolling() {
        Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
    }

    fun setFirstRunComplete() {
        prefs.edit().putBoolean("first_run", false).apply()
        _uiState.update { it.copy(isFirstRun = false) }
    }

    fun resetFirstRun() {
        prefs.edit().putBoolean("first_run", true).apply()
        _uiState.update { it.copy(isFirstRun = true) }
    }

    private var sleepTimerJob: Job? = null

    fun setSleepTimer(seconds: Int, finishTrack: Boolean = false, playCount: Int = 0) {
        sleepTimerJob?.cancel()
        if (seconds <= 0 && playCount <= 0) {
            _uiState.update { it.copy(
                isSleepTimerActive = false,
                sleepTimerRemainingSeconds = 0,
                sleepTimerPlayCount = 0,
                sleepTimerRemainingPlayCount = 0
            ) }
            return
        }

        _uiState.update { it.copy(
            isSleepTimerActive = true,
            sleepTimerRemainingSeconds = seconds,
            sleepTimerFinishTrack = finishTrack,
            sleepTimerPlayCount = playCount,
            sleepTimerRemainingPlayCount = playCount
        ) }

        if (seconds > 0) {
            sleepTimerJob = viewModelScope.launch {
                while (_uiState.value.sleepTimerRemainingSeconds > 0) {
                    delay(1000)
                    _uiState.update { it.copy(sleepTimerRemainingSeconds = it.sleepTimerRemainingSeconds - 1) }
                }

                // Timer expired
                if (_uiState.value.sleepTimerFinishTrack) {
                    // We wait for song completion - handled in playbackStateFlow observer
                } else {
                    if (_uiState.value.isPlaying) {
                        togglePlayPause()
                    }
                    _uiState.update { it.copy(isSleepTimerActive = false) }
                }
            }
        }
    }

    private var onlineInfoJob: Job? = null
    fun fetchOnlineInfo(song: Song) {
        val isCurrent = song.id == _uiState.value.currentSong?.id

        onlineInfoJob?.cancel()
        onlineInfoJob = viewModelScope.launch {
            if (isCurrent) {
                _uiState.update { it.copy(isLoadingOnlineInfo = true) }
            } else {
                _uiState.update { it.copy(isSelectedLoadingOnlineInfo = true) }
            }

            try {
                val username = lastFmRepository.username.first()
                val trackInfo = lastFmRepository.getTrackInfo(song.artist, song.title, username)

                var albumInfo: com.beatraxus.app.repository.lastfm.LastFmAlbum? = null
                if (song.album.isNotEmpty() && !song.album.contains("Unknown", ignoreCase = true)) {
                    albumInfo = lastFmRepository.getAlbumInfo(song.artist, song.album)
                }

                val artistInfo = lastFmRepository.getArtistInfo(song.artist)

                _uiState.update {
                    if (isCurrent) {
                        it.copy(
                            lastFmTrackInfo = trackInfo,
                            lastFmAlbumInfo = albumInfo,
                            lastFmArtistInfo = artistInfo,
                            isLoadingOnlineInfo = false
                        )
                    } else {
                        it.copy(
                            selectedLastFmTrackInfo = trackInfo,
                            selectedLastFmAlbumInfo = albumInfo,
                            selectedLastFmArtistInfo = artistInfo,
                            isSelectedLoadingOnlineInfo = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    if (isCurrent) it.copy(isLoadingOnlineInfo = false)
                    else it.copy(isSelectedLoadingOnlineInfo = false)
                }
            }
        }
    }

    private fun handleSongChangeForSleepTimer(newSong: Song?) {
        val state = _uiState.value
        if (!state.isSleepTimerActive) return

        var shouldStop = false

        // 1. Handle Play Count
        if (state.sleepTimerRemainingPlayCount > 0) {
            val remaining = state.sleepTimerRemainingPlayCount - 1
            _uiState.update { it.copy(sleepTimerRemainingPlayCount = remaining) }
            if (remaining <= 0) {
                shouldStop = true
            }
        }

        // 2. Handle Finish Track when time expired
        if (state.sleepTimerRemainingSeconds <= 0 && state.sleepTimerFinishTrack) {
            shouldStop = true
        }

        if (shouldStop) {
            if (state.isPlaying) {
                togglePlayPause()
            }
            _uiState.update { it.copy(
                isSleepTimerActive = false,
                sleepTimerRemainingSeconds = 0,
                sleepTimerRemainingPlayCount = 0
            ) }
        }
    }

    fun stopSleepTimer() {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(
            isSleepTimerActive = false,
            sleepTimerRemainingSeconds = 0,
            sleepTimerPlayCount = 0,
            sleepTimerRemainingPlayCount = 0
        ) }
    }

    fun showTelegramLoginForm() {
        tdLibManager.ensureClientStarted()
        _uiState.update { it.copy(showTelegramPhoneForm = true, telegramAuthError = null) }
    }

    fun restartTelegramAuth() {
        if (_uiState.value.isSubmittingTelegram) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingTelegram = true, telegramAuthError = null) }
            try {
                // To change number, we need to log out or restart the client.
                // TdLibManager.restart() handles the proper sequence.
                tdLibManager.restart()

                // Wait for the auth state to reach WaitPhoneNumber or Error, or timeout
                val success = withTimeoutOrNull(8000) {
                    tdLibManager.authState.first {
                        it is AuthState.WaitPhoneNumber || it is AuthState.Error
                    }
                }

                if (success == null) {
                    _uiState.update { it.copy(telegramAuthError = "Could not reset login, please try again") }
                }
            } catch (e: Exception) {
                Log.e("TDLib", "Failed to restart auth", e)
                _uiState.update { it.copy(telegramAuthError = e.message ?: "Failed to restart auth") }
            } finally {
                _uiState.update { it.copy(isSubmittingTelegram = false) }
            }
        }
    }

    fun resetTelegramLoginForm() {
        if (tdLibManager.authState.value !is AuthState.Ready) {
            _uiState.update { it.copy(showTelegramPhoneForm = false) }
        }
    }

    fun submitTelegramPhone(phone: String) {
        if (_uiState.value.isSubmittingTelegram) return

        val trimmedPhone = phone.trim()

        // Basic Validation
        if (!trimmedPhone.startsWith("+")) {
            _uiState.update { it.copy(telegramAuthError = "Phone number must include country code starting with '+' (e.g. +1234567890)") }
            return
        }

        if (trimmedPhone.length < 8) {
            _uiState.update { it.copy(telegramAuthError = "Phone number is too short.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingTelegram = true, telegramAuthError = null) }
            try {
                Log.d("TDLib", "Submitting phone number: $trimmedPhone")
                tdLibManager.submitPhoneNumber(trimmedPhone)
            } catch (e: Exception) {
                Log.e("TDLib", "submitPhoneNumber failed", e)
                val msg = when {
                    e.message?.contains("PHONE_NUMBER_INVALID") == true -> "The phone number is incorrect. Please check and try again."
                    e.message?.contains("TDLib error 400") == true -> "Incorrect phone number format."
                    else -> e.message ?: "Failed to submit phone number"
                }
                _uiState.update { it.copy(telegramAuthError = msg) }
                setErrorMessage(msg)
            } finally {
                _uiState.update { it.copy(isSubmittingTelegram = false) }
            }
        }
    }

    fun submitTelegramCode(code: String) {
        if (_uiState.value.isSubmittingTelegram) return
        val trimmedCode = code.trim()
        if (trimmedCode.isBlank()) {
            _uiState.update { it.copy(telegramAuthError = "Please enter the verification code.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingTelegram = true, telegramAuthError = null) }
            try {
                Log.d("TDLib", "Submitting code: $trimmedCode")
                tdLibManager.submitCode(trimmedCode)
            } catch (e: Exception) {
                Log.e("TDLib", "submitCode failed", e)
                val msg = when {
                    e.message?.contains("PHONE_CODE_INVALID") == true -> "Incorrect code — please check and try again."
                    e.message?.contains("PHONE_CODE_EXPIRED") == true -> "The verification code has expired. Please request a new one."
                    else -> "Incorrect code or TDLib error: ${e.message}"
                }
                _uiState.update { it.copy(telegramAuthError = msg) }
                setErrorMessage(msg)
            } finally {
                _uiState.update { it.copy(isSubmittingTelegram = false) }
            }
        }
    }

    fun submitTelegramPassword(password: String) {
        if (_uiState.value.isSubmittingTelegram) return
        if (password.isBlank()) {
            _uiState.update { it.copy(telegramAuthError = "Please enter your 2FA password.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingTelegram = true, telegramAuthError = null) }
            try {
                Log.d("TDLib", "Submitting password")
                tdLibManager.submitPassword(password)
            } catch (e: Exception) {
                Log.e("TDLib", "submitPassword failed", e)
                val msg = when {
                    e.message?.contains("PASSWORD_HASH_INVALID") == true -> "Incorrect password — please try again."
                    else -> "Incorrect password or TDLib error: ${e.message}"
                }
                _uiState.update { it.copy(telegramAuthError = msg) }
                setErrorMessage(msg)
            } finally {
                _uiState.update { it.copy(isSubmittingTelegram = false) }
            }
        }
    }

    fun addTelegramChannel(url: String) {
        viewModelScope.launch {
            telegramChannelRepository.addChannel(url)
            // Trigger an initial sync for the new channel
            syncTelegramChannel(url)
        }
    }

    fun syncTelegramChannel(url: String) {
        if (!tdLibManager.isReady()) {
            _uiState.update { it.copy(telegramSyncErrorMessage = "Telegram is not connected. Please log in first.") }
            return
        }

        val svc = service ?: return
        val normalizedUrl = url.trim().removeSuffix("/")
        val networkType = _uiState.value.metadataNetworkType
        val context = getApplication<android.app.Application>()

        if (networkType == com.beatraxus.app.model.NetworkType.ASK_MOBILE &&
            com.beatraxus.app.util.NetworkUtils.isMobileConnected(context) &&
            !com.beatraxus.app.util.NetworkUtils.isWifiConnected(context)) {
            _uiState.update { it.copy(telegramSyncErrorMessage = "Confirmation needed: Use mobile data for sync?") }
            return
        }

        if (!com.beatraxus.app.util.NetworkUtils.isNetworkAllowed(context, networkType)) {
            _uiState.update { it.copy(telegramSyncErrorMessage = "Waiting for allowed network (Rule: $networkType)") }
            return
        }

        incrementEnrichment()
        _uiState.update { it.copy(scanProgress = 0f, telegramSyncErrorMessage = "Telegram sync queued...") }

        svc.runTelegramScan(
            url = normalizedUrl,
            allowedFormats = _uiState.value.telegramAllowedFormats,
            onProgress = { progress ->
                _uiState.update { it.copy(scanProgress = progress) }
            },
            onDiscoveryComplete = { discoveredSongs ->
                _songs.update { current ->
                    val discoveredIds = discoveredSongs.map { it.id }.toSet()
                    val unchanged = current.filter { it.id !in discoveredIds }
                    (unchanged + discoveredSongs).sortedBy { it.title }
                }
            },
            onEnrichmentProgress = { progress, current, total ->
                // service handles notification
            },
            onStatusUpdate = { status ->
                _uiState.update { it.copy(enrichmentStatus = status) }
            },
            onSongUpdated = { updatedSong ->
                viewModelScope.launch(Dispatchers.Default) {
                    aiAnalysisChannel.send(updatedSong)
                    if (updatedSong.year == 0) yearEnrichmentChannel.send(updatedSong)
                }

                _songs.update { current ->
                    if (current.any { it.id == updatedSong.id }) {
                        current.map { if (it.id == updatedSong.id) updatedSong else it }
                    } else {
                        (current + updatedSong).sortedBy { it.title }
                    }
                }
            },
            onComplete = { message ->
                _uiState.update { it.copy(telegramSyncErrorMessage = message, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                viewModelScope.launch {
                    telegramChannelRepository.updateLastSyncTimestamp(normalizedUrl, System.currentTimeMillis())
                }
                decrementEnrichment()
                startSyncDismissTimer()
            },
            onError = { error ->
                _uiState.update { it.copy(telegramSyncErrorMessage = "Sync failed: $error", enrichmentStatus = null, isSyncFinishedRecently = true) }
                decrementEnrichment()
                startSyncDismissTimer()
            }
        )
    }

    private suspend fun extractTelegramMetadata(song: Song): Song? {
        val fileId = song.telegramFileId ?: return null

        try {
            // Download first 1MB for metadata
            val downloadSize = 1024 * 1024L
            tdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, downloadSize, true))

            // Wait for partial download reactively (Increased timeout for cold starts)
            val path = tdLibManager.waitForFile(fileId, downloadSize = downloadSize, timeoutMs = 10000)

            if (path == null) return null

            var tempFile = File(path)
            if (!tempFile.exists()) return null

            var enriched = metadataExtractor.extractMetadataFromLocalFile(song, tempFile)

            // WAV/M4A/ALAC Tail handling: Often tags and album art are stored in the footer.
            val format = song.format.lowercase()
            val isSpecial = format.contains("wav") || format == "alac" || format == "m4a" || format == "mp4"
            val totalSize = song.fileSizeBytes
            if (isSpecial && enriched.albumArtUri == null && totalSize > downloadSize) {
                val tailSize = 8 * 1024 * 1024L
                val offset = (totalSize - tailSize).coerceAtLeast(downloadSize)
                tdLibManager.send(TdApi.DownloadFile(fileId, 32, offset, totalSize - offset, true))

                // Request full download to ensure tail is reached and can be verified via prefix/completion
                tdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, true))

                val tailPath = tdLibManager.waitForFile(fileId, downloadSize = totalSize, timeoutMs = 10000)
                if (tailPath != null) {
                    tempFile = File(tailPath)
                }

                if (tempFile.exists()) {
                    enriched = metadataExtractor.extractMetadataFromLocalFile(song, tempFile)
                }
            }

            return enriched.copy(
                isEnriched = true,
                albumArtFetchAttempted = true
            )
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Failed to enrich Telegram song: ${song.title}", e)
            return null
        } finally {
            try { tdLibManager.send(TdApi.DeleteFile(fileId)) } catch (_: Exception) {}
        }
    }

    fun toggleTelegramChannelEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            telegramChannelRepository.toggleChannel(url, enabled)
        }
    }



    fun setScrobblingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("scrobbling_enabled", enabled).apply()
    }

    fun setNowPlayingBackgroundMode(mode: com.beatraxus.app.model.NowPlayingBackgroundMode) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingBackgroundMode(mode)
        }
    }

    fun setNowPlayingSolidColorIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingSolidColorIntensity(value)
        }
    }

    fun setNowPlayingSolidColorDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingSolidColorDarkness(value)
        }
    }

    fun setNowPlayingFullCoverArt(enabled: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingFullCoverArt(enabled)
        }
    }

    fun setNowPlayingHdCover(enabled: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingHdCover(enabled)
        }
    }

    fun setNowPlayingHideTopBar(enabled: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingHideTopBar(enabled)
        }
    }

    fun setNowPlayingBlurIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingBlurIntensity(value)
        }
    }

    fun setNowPlayingBlurDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setNowPlayingBlurDarkness(value)
        }
    }

    // Main Screen Background Setters
    fun setMainBackgroundMode(mode: com.beatraxus.app.model.NowPlayingBackgroundMode) {
        viewModelScope.launch {
            appearancePreferences.setMainBackgroundMode(mode)
        }
    }

    fun setMainSolidColorIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMainSolidColorIntensity(value)
        }
    }

    fun setMainSolidColorDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMainSolidColorDarkness(value)
        }
    }

    fun setMainBlurIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMainBlurIntensity(value)
        }
    }

    fun setMainBlurDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMainBlurDarkness(value)
        }
    }

    // Home Screen Background Setters
    fun setHomeBackgroundMode(mode: com.beatraxus.app.model.NowPlayingBackgroundMode) {
        viewModelScope.launch {
            appearancePreferences.setHomeBackgroundMode(mode)
        }
    }

    fun setHomeSolidColorIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setHomeSolidColorIntensity(value)
        }
    }

    fun setHomeSolidColorDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setHomeSolidColorDarkness(value)
        }
    }

    fun setHomeBlurIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setHomeBlurIntensity(value)
        }
    }

    fun setHomeBlurDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setHomeBlurDarkness(value)
        }
    }

    // Settings Screen Background Setters
    fun setSettingsBackgroundMode(mode: com.beatraxus.app.model.NowPlayingBackgroundMode) {
        viewModelScope.launch {
            appearancePreferences.setSettingsBackgroundMode(mode)
        }
    }

    fun setSettingsSolidColorIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setSettingsSolidColorIntensity(value)
        }
    }

    fun setSettingsSolidColorDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setSettingsSolidColorDarkness(value)
        }
    }

    fun setSettingsBlurIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setSettingsBlurIntensity(value)
        }
    }

    fun setDspPalette(id: String) {
        viewModelScope.launch { appearancePreferences.setDspPalette(id) }
    }

    fun setDspCustomColors(primary: Int, secondary: Int) {
        viewModelScope.launch { appearancePreferences.setDspCustomColors(primary, secondary) }
    }

    // Studio DSP > 5.1/7.1 screen appearance ("Customize UI")
    fun setSurroundRoomTheme(id: String) { viewModelScope.launch { appearancePreferences.setSurroundRoomTheme(id) } }
    fun setSurroundVisualizerEnabled(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundVisualizerEnabled(v) } }
    fun setSurroundVisualizerStyle(id: String) { viewModelScope.launch { appearancePreferences.setSurroundVisualizerStyle(id) } }
    fun setSurroundShowSpeakerIcons(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundShowSpeakerIcons(v) } }
    fun setSurroundShowLabels(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundShowLabels(v) } }
    fun setSurroundShowBeams(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundShowBeams(v) } }
    fun setSurroundAnimated(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundAnimated(v) } }
    fun setSurroundShowArcs(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundShowArcs(v) } }
    fun setSurroundShowScreen(v: Boolean) { viewModelScope.launch { appearancePreferences.setSurroundShowScreen(v) } }
    fun resetSurroundAppearance() { viewModelScope.launch { appearancePreferences.resetSurroundAppearance() } }
    fun setSurroundColor(group: String, argb: Int) { viewModelScope.launch { appearancePreferences.setSurroundColor(group, argb) } }

    fun setSettingsBlurDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setSettingsBlurDarkness(value)
        }
    }

    // Mini Player Background Setters
    fun setMiniPlayerBackgroundMode(mode: com.beatraxus.app.model.NowPlayingBackgroundMode) {
        viewModelScope.launch {
            appearancePreferences.setMiniPlayerBackgroundMode(mode)
        }
    }

    fun setMiniPlayerSolidColorIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMiniPlayerSolidColorIntensity(value)
        }
    }

    fun setMiniPlayerSolidColorDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMiniPlayerSolidColorDarkness(value)
        }
    }

    fun setMiniPlayerBlurIntensity(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMiniPlayerBlurIntensity(value)
        }
    }

    fun setMiniPlayerBlurDarkness(value: Float) {
        viewModelScope.launch {
            appearancePreferences.setMiniPlayerBlurDarkness(value)
        }
    }

    fun setMiniPlayerStyle(style: com.beatraxus.app.model.MiniPlayerStyle) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerStyle(style) }
    }

    fun setMiniPlayerHeight(value: Float) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerHeight(value) }
    }

    fun setMiniPlayerCornerRadius(value: Float) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerCornerRadius(value) }
    }

    fun setMiniPlayerArtCornerRadius(value: Float) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerArtCornerRadius(value) }
    }

    fun setMiniPlayerShowProgressRing(show: Boolean) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerShowProgressRing(show) }
    }

    fun setMiniPlayerShowTime(show: Boolean) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerShowTime(show) }
    }

    fun setMiniPlayerTintFromArt(tint: Boolean) {
        viewModelScope.launch { appearancePreferences.setMiniPlayerTintFromArt(tint) }
    }

    fun resetMiniPlayerLayout() {
        viewModelScope.launch { appearancePreferences.resetMiniPlayerLayout() }
    }

    fun resetNowPlayingBackground() {
        viewModelScope.launch {
            appearancePreferences.resetNowPlayingBackground()
        }
    }

    fun resetMainBackground() {
        viewModelScope.launch {
            appearancePreferences.resetMainBackground()
        }
    }

    fun resetHomeBackground() {
        viewModelScope.launch {
            appearancePreferences.resetHomeBackground()
        }
    }

    fun resetSettingsBackground() {
        viewModelScope.launch {
            appearancePreferences.resetSettingsBackground()
        }
    }

    fun resetMiniPlayerBackground() {
        viewModelScope.launch {
            appearancePreferences.resetMiniPlayerBackground()
        }
    }

    fun setSeekbarStyle(style: com.beatraxus.app.model.SeekbarStyle) {
        viewModelScope.launch {
            appearancePreferences.setSeekbarStyle(style)
        }
    }

    fun addBookmark(label: String) {
        val song = _uiState.value.currentSong ?: return
        val time = _progressMs.value
        viewModelScope.launch {
            bookmarkRepository.addBookmark(song.id, time, label)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(id)
        }
    }

    // Home Screen Sections
    fun setShowGreetingHeader(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowGreetingHeader(value)
        }
    }

    fun setHomeSectionVisible(sectionKey: String, visible: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setHomeSectionVisible(sectionKey, visible)
        }
    }

    fun setShowBrowseByMood(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowBrowseByMood(value)
        }
    }

    fun setShowMadeForYou(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowMadeForYou(value)
        }
    }

    fun setShowListenAgain(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowListenAgain(value)
        }
    }

    fun setShowRecentlyAddedHome(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowRecentlyAddedHome(value)
        }
    }

    fun setShowYourFavoritesHome(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowYourFavoritesHome(value)
        }
    }

    fun setShowFeaturedAlbums(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowFeaturedAlbums(value)
        }
    }

    fun setShowArtistsYouLove(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowArtistsYouLove(value)
        }
    }

    fun setShowYourPlaylists(value: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setShowYourPlaylists(value)
        }
    }

    // Now Playing Shortcuts
    fun setAlbumArtTransform(transform: AlbumArtTransform) {
        viewModelScope.launch {
            appearancePreferences.setAlbumArtTransform(transform)
        }
    }

    fun setQualityBadgeStyle(style: com.beatraxus.app.model.QualityBadgeStyle) {
        viewModelScope.launch {
            appearancePreferences.setQualityBadgeStyle(style)
        }
    }

    fun setHomeScreenSectionsOrder(order: List<String>) {
        viewModelScope.launch {
            appearancePreferences.setHomeScreenSectionsOrder(order)
        }
    }

    fun setLyricsProviderOrder(order: List<String>) {
        viewModelScope.launch {
            appearancePreferences.setLyricsProviderOrder(order)
        }
    }

    fun setLyricsProviderEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setLyricsProviderEnabled(id, enabled)
        }
    }

    fun setLyricsShowAll(showAll: Boolean) {
        viewModelScope.launch {
            appearancePreferences.setLyricsShowAll(showAll)
        }
    }

    fun resetLyricsProviderOrder() {
        viewModelScope.launch {
            appearancePreferences.resetLyricsProviderOrder()
        }
    }

    fun setSelectedFontId(fontId: String) {
        viewModelScope.launch {
            appearancePreferences.setSelectedFontId(fontId)
        }
    }

    fun logoutLastFm() {
        viewModelScope.launch {
            lastFmRepository.logout()
        }
    }

    fun onLastFmAuthStarted() {
        pendingLastFmAuth.set(true)
        // Persisted too: the browser-based flow backgrounds the whole app, and on
        // real devices (unlike a debugger-attached debug session) the process is
        // often killed before the "beatraxus://lastfm" callback returns, which
        // would otherwise silently wipe this flag.
        com.beatraxus.app.repository.lastfm.LastFmRepository.markAuthPending(getApplication())
    }

    fun isPendingLastFmAuthRequest(): Boolean {
        val inMemory = pendingLastFmAuth.getAndSet(false)
        val persisted = com.beatraxus.app.repository.lastfm.LastFmRepository.consumePendingAuth(getApplication())
        return inMemory || persisted
    }

    fun fetchLastFmSession(token: String) {
        viewModelScope.launch {
            lastFmRepository.fetchSession(token)
        }
    }

    private var telegramObserversStarted = false
    private fun startTelegramLiveObservers() {
        if (telegramObserversStarted) return
        telegramObserversStarted = true
        viewModelScope.launch {
            telegramChannelRepository.channels.first().forEach { channel ->
                if (channel.enabled) {
                    try {
                        val username = parseTelegramChannelName(channel.url)
                        val chat = tdLibManager.send(TdApi.SearchPublicChat(username))
                        telegramChannelRepository.observeLiveChannel(
                            tdLibManager,
                            cloudCacheManager,
                            chat.id,
                            channel.url,
                            viewModelScope
                        ) { song ->
                            viewModelScope.launch(Dispatchers.IO) {
                                songDao.insertSong(song.toEntity())
                                _songs.update { it + song }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PlayerViewModel", "Failed to observe live channel ${channel.url}", e)
                    }
                }
            }
        }
    }

    private companion object {
        const val FRAME_TICK_MS = 8L // Reduced from 16ms to 8ms for 120Hz smoothness
        const val KEY_OUTPUT_MODE = "output_mode"
        const val KEY_CUSTOM_EQ_PRESETS = "custom_eq_presets"
        const val TELEGRAM_VIDEO_REFRESH_MIN_MS = 60_000L
        const val CLOUD_VIDEO_REFRESH_MIN_MS = 5 * 60_000L
    }

    private fun startSyncDismissTimer() {
        viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(showSyncStatusOnHome = false, isSyncFinishedRecently = false) }
        }
    }

    private fun updateLibraryCounts(songs: List<Song>) {
        _uiState.update {
            it.copy(
                scanCount = songs.size,
                albumCount = songs.map { song -> song.album }.toSet().size,
                artistCount = songs.map { song -> song.artist }.toSet().size
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        enrichmentJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        aiAnalysisDispatcher.close()
    }
}

class PlayerViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            return PlayerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}