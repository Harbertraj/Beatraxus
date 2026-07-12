package com.beatraxus.app.viewmodel

import java.io.File

import android.app.Application
import android.util.Log
import android.media.AudioManager
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlin.math.roundToInt
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.model.OutputMode
import com.beatraxus.app.model.PlaylistEntity
import com.beatraxus.app.model.FavoriteEntity
import com.beatraxus.app.model.AutoEqProfileSummary
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.HrtfMode
import com.beatraxus.app.model.DvcMode
import com.beatraxus.app.model.ParametricEqBand
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.SoundStageNodePosition
import com.beatraxus.app.model.ReplayGainOption
import com.beatraxus.app.model.ReplayGainSource
import com.beatraxus.app.model.ResamplerMode
import com.beatraxus.app.model.LibraryView
import com.beatraxus.app.model.defaultEqBands
import com.beatraxus.app.model.Playlist
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.beatraxus.app.drive.DrivePlaybackHelper
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.model.parseTelegramChannelName
import com.beatraxus.app.repository.DriveAccountRepository
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.Song
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
import com.beatraxus.app.repository.LyricsType
import com.beatraxus.app.repository.DspPreferences
import com.beatraxus.app.repository.DriveAccount
import com.beatraxus.app.repository.TelegramChannelRepository
import com.beatraxus.app.util.ArtistNameUtils
import com.beatraxus.app.telegram.AuthState
import com.beatraxus.app.telegram.TdLibManager
import org.drinkless.tdlib.TdApi
import com.beatraxus.app.service.AudioPlaybackService
import com.beatraxus.app.cast.CastManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PlayerViewModel"

    private val musicRepository = MusicRepository(application)
    private val autoEqRepository = AutoEqRepository(application)
    private val autoEqApiService = com.beatraxus.app.repository.AutoEqApiService(application)
    private val lyricsRepository = LyricsRepository(application, (application as BeatraxusApplication).database)
    private val dspPreferences = DspPreferences(application)
    private val driveAccountRepository = com.beatraxus.app.repository.DriveAccountRepository(application)
    private val telegramChannelRepository = TelegramChannelRepository(application)
    private val cloudCacheManager = com.beatraxus.app.drive.CloudCacheManager(application, driveAccountRepository)
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
    private val aiAnalysisDao = database.aiAnalysisDao()
    private val artistArtDao = database.artistArtDao()
    private val aiAnalysisEngine = com.beatraxus.app.engine.AiAnalysisEngine(application)

    private val aiAnalysisChannel = kotlinx.coroutines.channels.Channel<Song>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(PlayerUiState(
        isFirstRun = prefs.getBoolean("first_run", true),
        useOriginalQualityArt = prefs.getBoolean("use_original_quality_art", false),
        outputMode = OutputMode.fromName(prefs.getString(KEY_OUTPUT_MODE, null)).name,
        musicFolders = musicRepository.getMusicFolders(),
        blockedFolders = musicRepository.getBlockedFolders(),
        dsp = com.beatraxus.app.model.DspUiState(
            customEqPresets = loadCustomEqPresets()
        ),
        libraryMode = LibraryMode.valueOf(prefs.getString("library_mode", LibraryMode.LOCAL.name) ?: LibraryMode.LOCAL.name),
        metadataNetworkType = NetworkType.valueOf(prefs.getString("metadata_network_type", NetworkType.ASK_MOBILE.name) ?: NetworkType.ASK_MOBILE.name),
        dataSaverEnabled = prefs.getBoolean("data_saver_enabled", false),
        artworkEnrichmentEnabled = prefs.getBoolean("artwork_enrichment_enabled", true),
        syncQuality = SyncQuality.valueOf(prefs.getString("sync_quality", SyncQuality.MEDIUM.name) ?: SyncQuality.MEDIUM.name),
        backgroundSyncEnabled = prefs.getBoolean("background_sync_enabled", true),
        scrobblingEnabled = prefs.getBoolean("scrobbling_enabled", true),
        gdriveAllowedFormats = prefs.getStringSet("gdrive_allowed_formats", emptySet()) ?: emptySet(),
        telegramAllowedFormats = prefs.getStringSet("telegram_allowed_formats", emptySet()) ?: emptySet()
    ))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

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

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _songs.asStateFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allSongsWithFavorites: StateFlow<List<Song>> = combine(
        allSongs,
        favorites,
        driveAccountRepository.accounts,
        telegramChannelRepository.channels
    ) { songs, favoriteIds, driveAccounts, tgChannels ->
        val enabledDriveEmails = driveAccounts.filter { it.enabled }.map { it.email.lowercase() }.toSet()
        val enabledTgUrls = tgChannels.filter { it.enabled }.map { it.url }.toSet()

        songs.filter { song ->
            when (song.source) {
                SongSource.GDRIVE -> song.driveAccountEmail == null || song.driveAccountEmail.lowercase() in enabledDriveEmails
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
            .map { (year, list) -> Triple(year.toString(), "${list.size} songs", list.first().albumArtUri) }
            .sortedByDescending { it.first }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genres = filteredSongsByMode.map { songs ->
        songs.groupBy { it.genre }
            .map { (genre, list) -> Triple(genre, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recentlyPlayed = MutableStateFlow<List<String>>(emptyList())

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

    val searchResults = combine(filteredSongsByMode, debouncedSearchQuery) { all, query ->
        if (query.isEmpty()) return@combine emptyList<Any>()
        val list = mutableListOf<Any>()
        
        val matchedSongs = all.filter { it.title.contains(query, ignoreCase = true) }
        if (matchedSongs.isNotEmpty()) {
            list.add("Songs")
            list.addAll(matchedSongs.take(20))
        }
        
        val matchedAlbums = all.filter { it.album.contains(query, ignoreCase = true) }
            .distinctBy { it.album }
        if (matchedAlbums.isNotEmpty()) {
            list.add("Albums")
            matchedAlbums.take(10).forEach { 
                list.add(Triple(it.album, it.artist, it.albumArtUri)) 
            }
        }
        
        val matchedArtists = all.filter { it.artist.contains(query, ignoreCase = true) }
            .distinctBy { it.artist }
        if (matchedArtists.isNotEmpty()) {
            list.add("Artists")
            matchedArtists.take(10).forEach {
                list.add(Pair(it.artist, it.albumArtUri))
            }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val songs: StateFlow<List<Song>> = combine(allSongsWithFavorites, _uiState, debouncedSearchQuery, _recentlyPlayed, playlists) { allSongsList, state, debouncedQuery, recentIds, pls ->
        val mode = state.libraryMode
        val all = when (mode) {
            LibraryMode.LOCAL -> allSongsList.filter { it.source == SongSource.LOCAL }
            LibraryMode.CLOUD -> allSongsList.filter { it.source != SongSource.LOCAL }
            LibraryMode.COMBINED -> allSongsList
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
            LibraryView.RECENTLY_ADDED -> all.sortedByDescending { it.dateAdded }
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
            LibraryView.YEAR_DETAIL -> all.filter { it.year.toString() == state.selectedItemName }
            LibraryView.GENRE_DETAIL -> all.filter { it.genre == state.selectedItemName }
            LibraryView.PLAYLISTS -> emptyList()
            LibraryView.PLAYLIST_DETAIL -> {
                val playlist = pls.find { it.name == state.selectedItemName }
                playlist?.songIds?.mapNotNull { id -> allSongsList.find { it.id == id } } ?: emptyList()
            }
            LibraryView.CLOUD -> allSongsList.filter {
                if (state.selectedTelegramChannelUrl != null) {
                    it.source == com.beatraxus.app.model.SongSource.TELEGRAM && it.telegramChannelUrl == state.selectedTelegramChannelUrl
                } else if (state.selectedItemName != null) {
                    it.source == com.beatraxus.app.model.SongSource.GDRIVE &&
                            it.driveAccountEmail.equals(state.selectedItemName, ignoreCase = true)
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

    init {
        // Observe Telegram auth state
        viewModelScope.launch {
            tdLibManager.authState.collect { state ->
                _uiState.update { it.copy(telegramAuthState = state) }
                if (state is AuthState.Ready) {
                    startTelegramLiveObservers()
                    // Auto-sync all enabled channels when Telegram becomes ready
                    viewModelScope.launch {
                        telegramChannelRepository.channels.first().forEach { channel ->
                            if (channel.enabled) {
                                syncTelegramChannel(channel.url)
                            }
                        }
                    }
                }
            }
        }

        // Start AI Analysis worker
        viewModelScope.launch(Dispatchers.Default) {
            // Delay AI analysis at startup to prevent blocking the main thread during initial UI render
            delay(2000)

            for (song in aiAnalysisChannel) {
                try {
                    val analysis = aiAnalysisEngine.analyzeSong(song)
                    if (analysis != null) {
                        aiAnalysisDao.insertAnalysis(analysis)
                        
                        // If AI found a better genre, update the song in DB and Memory
                        if (analysis.genre.isNotEmpty() && analysis.genre != song.genre) {
                            val updatedSong = song.copy(genre = analysis.genre)
                            withContext(Dispatchers.IO) {
                                songDao.insertSong(updatedSong.toEntity())
                            }
                            _songs.update { current ->
                                current.map { if (it.id == song.id) updatedSong else it }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("PlayerViewModel", "AI Analysis failed for ${song.title}: ${t.message}", t)
                }
                // Small delay to prevent CPU hogging
                delay(100)
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

        // Drive Accounts and Telegram Channels
        viewModelScope.launch {
            driveAccountRepository.accounts.collect { accounts ->
                _uiState.update { it.copy(driveAccounts = accounts) }
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
                _uiState.update { it.copy(errorMessage = error) }
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
                telegramChannelRepository.channels
            ) { drive, tg -> drive.isNotEmpty() || tg.isNotEmpty() }
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
                        email != null -> s.driveAccountEmail == email
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

        // Trigger AI analysis for local songs with missing mood data
        viewModelScope.launch(Dispatchers.Default) {
            delay(4000)
            val analyzed = aiAnalysisDao.getAllAnalysisFlow().first().associateBy { it.songId }
            _songs.value.filter { it.source == SongSource.LOCAL }
                .filter { analyzed[it.id] == null || analyzed[it.id]?.moodTags.isNullOrBlank() }
                .forEach { aiAnalysisChannel.send(it) }
        }

        checkBatteryOptimizations()
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
            songDao.deleteSongsByIds(ids)
            _songs.update { currentSongs ->
                currentSongs.filterNot { it.id in ids }
            }
            pendingDeleteIds = emptyList()
            setMultiSelectMode(false)
            
            // If the current song was deleted, skip it
            if (ids.contains(_uiState.value.currentSong?.id)) {
                skipToNext()
            }
            // Remove from queue
            ids.forEach { id -> service?.removeFromQueue(id) }
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
                            source = SongSource.valueOf(entity.source),
                            driveFileId = entity.driveFileId,
                            driveAccountEmail = entity.driveAccountEmail,
                            telegramChannelUrl = entity.telegramChannelUrl,
                            telegramChatId = entity.telegramChatId,
                            telegramMessageId = entity.telegramMessageId,
                            telegramFileId = entity.telegramFileId,
                            isEnriched = entity.isEnriched,
                            albumArtFetchAttempted = entity.albumArtFetchAttempted,
                            lastSyncTimestamp = entity.lastSyncTimestamp
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
                    val cacheWiped = dbSongs.any { song ->
                        val artUri = song.albumArtUri
                        if (artUri != null && artUri.scheme == "file") {
                            val file = File(artUri.path ?: "")
                            val exists = file.exists()
                            if (!exists) {
                                Log.d("PlayerViewModel", "Cache wipe detected for song: ${song.title}")
                                Log.d("PlayerViewModel", "  - Missing file path: ${artUri.path}")
                                Log.d("PlayerViewModel", "  - FilesDir: ${getApplication<Application>().filesDir.absolutePath}")
                            }
                            !exists
                        } else false
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
                                while (service == null) {
                                    delay(200)
                                }
                                
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
                                while (service == null) {
                                    delay(200)
                                }
                                cloudCacheManager.setCurrentlyPlayingId(lastSong.id)
                                service?.prepareSong(lastSong, lastPos)
                                _progressMs.value = lastPos
                            }
                        }
                    }
                    
                    if (cacheWiped) {
                        startFullScan()
                        return@launch
                    }
                    
                    // After loading from DB, we stop here to avoid automatic "sync" (quickScan) on startup
                    _uiState.update { it.copy(isLoadingLibrary = false) }
                    return@launch
                }
            } catch (e: Exception) {
                // Ignore initial load errors
            }

            // Perform a quick scan ONLY if DB was empty
            quickScan()
        }
    }



    private var service: AudioPlaybackService? = null

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
                                dsp = it.dsp.copy(
                                    currentHeadroomDb = audioState.headroomDb,
                                    currentLatencyFrames = audioState.latencyFrames,
                                    currentDitherType = audioState.ditherType,
                                    currentEqMode = audioState.eqMode
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

                        if (resetProgress) {
                            if (pbState.currentSong != null) {
                                updateRecentlyPlayed(pbState.currentSong.id)
                                handleSongChangeForSleepTimer(pbState.currentSong)
                                fetchOnlineInfo(pbState.currentSong)
                                if (_uiState.value.showLyrics) {
                                    loadLyrics(pbState.currentSong)
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
                        preloadUpcomingLyrics(songs.take(10))
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
                            usbDeviceName = routeState.usbDeviceName
                        )
                    }
                }
            }
        }
    }


    fun removeDriveAccount(email: String) {
        viewModelScope.launch {
            driveAccountRepository.removeAccount(email)
            // Optionally remove songs from this account from database
            songDao.deleteSongsByAccount(email.lowercase())
            _songs.update { current -> current.filterNot { it.driveAccountEmail?.lowercase() == email.lowercase() } }
        }
    }


    private var enrichmentJob: Job? = null

    fun scanDriveAccount(email: String) {
        if (_uiState.value.isCloudScanning) return // Already scanning, don't restart

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

        _uiState.update { it.copy(isCloudScanning = true, scanProgress = 0f, driveErrorMessage = "Starting Drive scan...", showSyncStatusOnHome = true, isSyncFinishedRecently = false) }
        
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
                _uiState.update { it.copy(isCloudScanning = false, driveErrorMessage = message, scanProgress = 1f, enrichmentStatus = null, isSyncFinishedRecently = true) }
                startSyncDismissTimer()
            },
            onError = { error, intent ->
                if (intent != null) {
                    _uiState.update { it.copy(isCloudScanning = false, authRecoveryIntent = intent, enrichmentStatus = null, showSyncStatusOnHome = false) }
                } else {
                    _uiState.update { it.copy(isCloudScanning = false, driveErrorMessage = "Drive scan failed: $error", enrichmentStatus = null, isSyncFinishedRecently = true) }
                    startSyncDismissTimer()
                }
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

    private var scanJob: Job? = null
    private var lyricsJob: Job? = null
    private var preloadLyricsJob: Job? = null

    fun quickScan() {
        if (scanJob?.isActive == true) return
        val svc = service ?: return

        _uiState.update { it.copy(isLoadingLibrary = true, isScanning = true) }
        svc.runLocalScan(
            fullScan = false,
            currentSongs = _songs.value,
            onProgress = { progress, count, albums, artists ->
                _uiState.update { it.copy(
                    scanCount = count,
                    albumCount = albums,
                    artistCount = artists,
                    scanProgress = progress
                )}
            },
            onComplete = { results, newSongs, removedLocalIds, message, hasChanges ->
                if (hasChanges) {
                    val cloudSongs = _songs.value.filter { it.source != SongSource.LOCAL }
                    _songs.value = (results + cloudSongs).sortedBy { it.title }
                    
                    if (newSongs.isNotEmpty()) {
                        viewModelScope.launch(Dispatchers.Default) {
                            val toAnalyze = if (_uiState.value.isFirstRun) newSongs.take(20) else newSongs
                            toAnalyze.forEach { song ->
                                try {
                                    aiAnalysisChannel.send(song)
                                } catch (e: Exception) {
                                    Log.e("PlayerViewModel", "Failed to queue song for analysis", e)
                                }
                            }
                        }
                    }
                }

                updateLibraryCounts(results)
                
                _uiState.update { it.copy(
                    isLoadingLibrary = false, 
                    isScanning = false,
                    errorMessage = message,
                    musicFolders = musicRepository.getMusicFolders(),
                    blockedFolders = musicRepository.getBlockedFolders()
                ) }
                
                viewModelScope.launch {
                    delay(2000)
                    _uiState.update { it.copy(errorMessage = null) }
                }
            },
            onError = { error ->
                _uiState.update { it.copy(isLoadingLibrary = false, isScanning = false, errorMessage = "Scan failed: $error") }
            }
        )
    }

    fun startFullScan() {
        val svc = service ?: return
        
        _uiState.update { it.copy(isScanning = true, isFullScanning = true, scanProgress = 0f, scanCount = 0, showScanOptions = false, errorMessage = null) }
        
        svc.runLocalScan(
            fullScan = true,
            currentSongs = _songs.value,
            onProgress = { progress, count, albums, artists ->
                _uiState.update { it.copy(
                    scanCount = count,
                    albumCount = albums,
                    artistCount = artists,
                    scanProgress = progress
                )}
            },
            onComplete = { results, _, _, message, _ ->
                val cloudSongs = _songs.value.filter { it.source != SongSource.LOCAL }
                _songs.value = (results + cloudSongs).sortedBy { it.title }
                
                // AI Analysis for all songs in full scan
                viewModelScope.launch(Dispatchers.Default) {
                    results.forEachIndexed { index, song ->
                        if (index % 10 == 0) {
                            aiAnalysisChannel.send(song)
                        }
                    }
                }

                updateLibraryCounts(results)
                
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        isFullScanning = false,
                        scanProgress = 1.0f,
                        scanCount = results.size,
                        albumCount = results.map { song -> song.album }.toSet().size,
                        artistCount = results.map { song -> song.artist }.toSet().size,
                        musicFolders = musicRepository.getMusicFolders(),
                        blockedFolders = musicRepository.getBlockedFolders(),
                        errorMessage = message
                    )
                }

                if (_uiState.value.isFirstRun) {
                    setFirstRunComplete()
                }
            },
            onError = { error ->
                _uiState.update { it.copy(isScanning = false, isFullScanning = false, errorMessage = "Full scan failed: $error", showScanOptions = true) }
            }
        )
    }

    fun startAddedFoldersScan() {
        val folders = _uiState.value.musicFolders
        if (folders.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No folders added to scan") }
            return
        }
        
        val svc = service ?: return
        
        _uiState.update { it.copy(isScanning = true, scanProgress = 0f, scanCount = 0, albumCount = 0, artistCount = 0, showScanOptions = false, errorMessage = null) }
        
        svc.runFolderScan(
            folders = folders,
            onProgress = { progress, count, albums, artists ->
                _uiState.update { it.copy(
                    scanProgress = progress,
                    scanCount = count,
                    albumCount = albums,
                    artistCount = artists
                )}
            },
            onComplete = { results, message ->
                val currentSongs = _songs.value
                val newLocalIds = results.map { it.id }.toSet()
                val unchanged = currentSongs.filter { it.id !in newLocalIds }
                _songs.value = (unchanged + results).sortedBy { it.title }
                
                // AI Analysis for added folders scan
                viewModelScope.launch(Dispatchers.Default) {
                    results.forEachIndexed { index, song ->
                        if (index < 30) {
                            aiAnalysisChannel.send(song)
                        }
                    }
                }

                updateLibraryCounts(_songs.value.filter { it.source == SongSource.LOCAL })
                _uiState.update { it.copy(
                    isScanning = false, 
                    scanProgress = 1.0f,
                    errorMessage = message
                ) }

                if (_uiState.value.isFirstRun) {
                    setFirstRunComplete()
                }
                
                viewModelScope.launch {
                    delay(2000)
                    _uiState.update { it.copy(errorMessage = null) }
                }
            },
            onError = { error ->
                _uiState.update { it.copy(isScanning = false, errorMessage = "Scan failed: $error", showScanOptions = true) }
            }
        )
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
        updateRecentlyPlayed(song.id)
        loadLyrics(song)
    }

    fun playExternalUri(uri: Uri) {
        viewModelScope.launch {
            // Try to find if this song is already in our loaded library
            val existing = allSongs.value.find { it.uri.toString() == uri.toString() }
            
            if (existing != null) {
                playSong(existing)
            } else {
                // Not in DB, create a temporary song object
                val tempSong = Song(
                    id = "external_${System.currentTimeMillis()}",
                    uri = uri,
                    title = uri.lastPathSegment ?: "External Song",
                    artist = "External Source",
                    album = "External",
                    durationMs = 0,
                    format = uri.toString().substringAfterLast('.', "mp3"),
                    sampleRateHz = 44100,
                    source = SongSource.LOCAL
                )
                
                // Wait for service
                while (service == null) delay(100)
                
                service?.playSong(tempSong)
                setShowFullPlayer(true)
            }
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

    private fun updateRecentlyPlayed(songId: String) {
        val current = _recentlyPlayed.value.toMutableList()
        current.remove(songId)
        current.add(0, songId)
        if (current.size > 50) current.removeAt(current.size - 1)
        _recentlyPlayed.value = current
    }


    fun setLibraryView(view: LibraryView, itemName: String? = null) {
        _uiState.update { 
            val isDetailView = view in listOf(
                LibraryView.ALBUM_DETAIL, LibraryView.ARTIST_DETAIL, 
                LibraryView.FOLDER_DETAIL, LibraryView.GENRE_DETAIL, LibraryView.YEAR_DETAIL,
                LibraryView.PLAYLIST_DETAIL
            )
            it.copy(
                previousView = it.currentView,
                currentView = view, 
                selectedItemName = itemName,
                isSearchActive = false,
                isMultiSelectMode = false,
                selectedIds = emptySet(),
                selectedTelegramChannelUrl = null, // Clear telegram when changing view or account
                currentFolderPath = if (view == LibraryView.FOLDER_DETAIL) it.currentFolderPath else null,
                wasSearchingBeforeDetail = if (isDetailView) it.isSearchActive else it.wasSearchingBeforeDetail
            ) 
        }
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
        if (state.selectedTelegramChannelUrl != null) {
            syncTelegramChannel(state.selectedTelegramChannelUrl)
        } else if (state.currentView == LibraryView.CLOUD && state.selectedItemName != null) {
            scanDriveAccount(state.selectedItemName)
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
                        LibraryView.YEARS -> selected.contains(song.year.toString())
                        LibraryView.GENRES -> selected.contains(song.genre)
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
                        LibraryView.YEARS -> selected.contains(song.year.toString())
                        LibraryView.GENRES -> selected.contains(song.genre)
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
                        LibraryView.YEARS -> selected.contains(song.year.toString())
                        LibraryView.GENRES -> selected.contains(song.genre)
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
                            LibraryView.YEARS -> selectedIds.contains(song.year.toString())
                            LibraryView.GENRES -> selectedIds.contains(song.genre)
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

    fun exportSettings(uri: android.net.Uri) {
        viewModelScope.launch {
            backupRepository.exportSettings(uri)
            android.widget.Toast.makeText(getApplication(), "Settings exported successfully", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun importSettings(uri: android.net.Uri) {
        viewModelScope.launch {
            backupRepository.importSettings(uri)
            android.widget.Toast.makeText(getApplication(), "Settings imported successfully", android.widget.Toast.LENGTH_SHORT).show()
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

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            if (favorites.value.contains(song.id)) {
                favoriteDao.removeFavorite(song.id)
            } else {
                favoriteDao.addFavorite(FavoriteEntity(song.id))
            }
        }
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

    fun skipToPrevious() {
        service?.previous()
    }

    fun seekTo(positionMs: Long) {
        _progressMs.value = positionMs
        service?.seekTo(positionMs)
    }

    fun toggleResampling() {
        val newValue = !_uiState.value.dsp.config.highQualityResampler
        applyDspConfig { it.copy(highQualityResampler = newValue) }
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

    fun getCurrentDeviceId(): String = dspPreferences.getCurrentDeviceId()

    fun setPlaybackSpeed(speed: Float) {
        applyDspConfig { it.copy(playbackSpeed = speed) }
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

    fun consumeFolderPickerTrigger() {
        _uiState.update { it.copy(triggerFolderPicker = false) }
    }

    fun addMusicFolder(uri: String) {
        viewModelScope.launch {
            musicRepository.addMusicFolder(uri)
            _uiState.update { it.copy(triggerFolderPicker = false, musicFolders = musicRepository.getMusicFolders()) }
            if (!_uiState.value.isFirstRun) {
                startFullScan()
            }
        }
    }

    fun removeMusicFolder(path: String) {
        viewModelScope.launch {
            musicRepository.removeMusicFolder(path)
            val isFirstRun = _uiState.value.isFirstRun
            _uiState.update { it.copy(
                musicFolders = musicRepository.getMusicFolders(),
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            if (!isFirstRun) {
                quickScan()
            }
        }
    }

    fun unblockMusicFolder(path: String) {
        viewModelScope.launch {
            musicRepository.removeBlockedFolder(path)
            _uiState.update { it.copy(
                musicFolders = musicRepository.getMusicFolders(),
                blockedFolders = musicRepository.getBlockedFolders()
            ) }
            quickScan()
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        service?.cancelLibraryScan()
        _uiState.update { it.copy(
            isScanning = false,
            isFullScanning = false,
            isLoadingLibrary = false,
            isCloudScanning = false,
            scanProgress = 0f
        ) }
        service?.updateScanningProgress(1.0f, _uiState.value.scanCount, true)
    }



    fun setLibraryMode(mode: LibraryMode) {
        prefs.edit().putString("library_mode", mode.name).apply()
        _uiState.update { it.copy(libraryMode = mode) }
    }

    fun setMetadataNetworkType(type: com.beatraxus.app.model.NetworkType) {
        prefs.edit().putString("metadata_network_type", type.name).apply()
        _uiState.update { it.copy(metadataNetworkType = type) }
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("data_saver_enabled", enabled).apply()
        _uiState.update { it.copy(dataSaverEnabled = enabled) }
    }

    fun setArtworkEnrichmentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("artwork_enrichment_enabled", enabled).apply()
        _uiState.update { it.copy(artworkEnrichmentEnabled = enabled) }
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
        it.copy(preampDb = db, preampEnabled = true, autoEqProfile = null, autoEqEnabled = false)
    }
    fun setEqEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        if (enabled) {
            it.copy(eqEnabled = true, preampEnabled = true)
        } else {
            it.copy(eqEnabled = false, preampEnabled = false, autoEqEnabled = false, autoEqProfile = null)
        }
    }
    fun setEqPhaseMode(mode: com.beatraxus.app.model.EqPhaseMode) = applyDspConfig {
        it.copy(eqPhaseMode = mode)
    }
    fun setAutoEqEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        if (enabled) it.copy(autoEqEnabled = true, eqEnabled = true, preampEnabled = true) else it.copy(autoEqEnabled = false)
    }
    fun setAiEqEnabled(enabled: Boolean) = applyDspConfig {
        if (it.settingsLocked) return@applyDspConfig it
        it.copy(aiEqEnabled = enabled)
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
    fun setReverbPreset(preset: String) = applyDspConfig { it.copy(reverbPreset = preset) }
    fun setReverbDamping(value: Float) = applyDspConfig { it.copy(reverbDamping = value.coerceIn(0f, 1f)) }
    fun setReverbWidth(value: Float) = applyDspConfig { it.copy(reverbWidth = value.coerceIn(0f, 1f)) }
    fun setReverbRoomSize(value: Float) = applyDspConfig { it.copy(reverbRoomSize = value.coerceIn(0f, 1f)) }
    fun setReverbDecay(value: Float) = applyDspConfig { it.copy(reverbDecay = value.coerceIn(0f, 1f)) }
    fun setReverbParams(roomSize: Float, damping: Float) = applyDspConfig { it.copy(reverbRoomSize = roomSize, reverbDamping = damping) }
    fun setReverbPredelayMix(value: Float) = applyDspConfig { it.copy(reverbPredelayMix = value.coerceIn(0f, 1f)) }
    fun setReverbPredelay(value: Float) = applyDspConfig { it.copy(reverbPredelayMs = value.coerceIn(0f, 1000f)) }
    fun setCrossfeedEnabled(enabled: Boolean) = applyDspConfig { it.copy(crossfeedEnabled = enabled) }
    fun setCrossfeedLevel(value: Float) = applyDspConfig { it.copy(crossfeedLevel = value.coerceIn(0f, 1f), crossfeedEnabled = true) }
    fun setSpatialAudioEnabled(enabled: Boolean) { applyDspConfig { it.copy(spatialAudioEnabled = enabled) } }
    fun setSpatialTouchEnabled(enabled: Boolean) { 
        applyDspConfig { 
            if (enabled) {
                it.copy(spatialTouchEnabled = true, soundStageEnabled = true)
            } else {
                it.copy(spatialTouchEnabled = false)
            }
        } 
    }
    fun setSpatialAudioIntensity(value: Float) { applyDspConfig { it.copy(spatialAudioIntensity = value.coerceIn(0f, 1f)) } }

    fun selectSoundStageNode(name: String) { applyDspConfig { it.copy(soundStageSelectedNode = name) } }

    private fun updateSelectedNode(transform: (SoundStageNodePosition) -> SoundStageNodePosition) {
        applyDspConfig { cfg ->
            val current = cfg.soundStageNodePositions[cfg.soundStageSelectedNode] ?: SoundStageNodePosition()
            cfg.copy(soundStageNodePositions = cfg.soundStageNodePositions + (cfg.soundStageSelectedNode to transform(current)))
        }
    }

    fun setSoundStageAzimuth(value: Float) = updateSelectedNode { it.copy(azimuth = value.coerceIn(0f, 360f)) }
    fun setSoundStageElevation(value: Float) = updateSelectedNode { it.copy(elevation = value.coerceIn(-90f, 90f)) }
    fun setSoundStageDistance(value: Float) = updateSelectedNode { it.copy(distance = value.coerceIn(0.3f, 15f)) }

    fun setSoundStagePosition(azimuth: Float, elevation: Float, distance: Float) {
        updateSelectedNode { it.copy(
            azimuth = azimuth.coerceIn(0f, 360f),
            elevation = elevation.coerceIn(-90f, 90f),
            distance = distance.coerceIn(0.3f, 15f)
        ) }
    }
    fun setSoundStageEnabled(enabled: Boolean) = applyDspConfig { it.copy(soundStageEnabled = enabled) }
    fun setSoundStageWidth(value: Float) { applyDspConfig { it.copy(soundStageWidth = value.coerceIn(0f, 2f), soundStageEnabled = true) } }
    fun setSpatialStageWidth(value: Float) { applyDspConfig { it.copy(spatialStageWidth = value.coerceIn(0f, 2f)) } }
    fun setSoundStageCenterLock(value: Float) { applyDspConfig { it.copy(soundStageCenterLock = value.coerceIn(0f, 1f)) } }
    fun setHrtfMode(mode: HrtfMode) = applyDspConfig { it.copy(hrtfMode = mode) }
    fun setDcBlockerEnabled(enabled: Boolean) = applyDspConfig { it.copy(dcBlockerEnabled = enabled) }
    fun setMonoEnabled(enabled: Boolean) = applyDspConfig { it.copy(monoEnabled = enabled) }

    fun setSettingsLocked(locked: Boolean) = applyDspConfig { it.copy(settingsLocked = locked) }

    fun setUsbExclusiveMode(enabled: Boolean) {
        applyDspConfig { it.copy(usbExclusiveEnabled = enabled) }
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

    private fun loadCustomEqPresets(): List<SavedEqPreset> {
        val raw = prefs.getString(KEY_CUSTOM_EQ_PRESETS, null) ?: return emptyList()
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
                                    q = band.optDouble("q", 1.0).toFloat()
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
        }

        val songsToShuffle = when (state.currentView) {
            LibraryView.HOME, LibraryView.ALL_SONGS -> modeSongs
            LibraryView.FAVORITES -> modeSongs.filter { it.isFavorite }
            LibraryView.RECENTLY_ADDED -> modeSongs.sortedByDescending { it.dateAdded }
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
            LibraryView.GENRE_DETAIL -> modeSongs.filter { it.genre == state.selectedItemName }
            LibraryView.PLAYLIST_DETAIL -> {
                val playlist = playlists.value.find { it.name == state.selectedItemName }
                playlist?.songIds?.mapNotNull { id -> allSongsList.find { it.id == id } } ?: emptyList()
            }
            LibraryView.CLOUD -> allSongsList.filter {
                if (state.selectedTelegramChannelUrl != null) {
                    it.source == com.beatraxus.app.model.SongSource.TELEGRAM && it.telegramChannelUrl == state.selectedTelegramChannelUrl
                } else if (state.selectedItemName != null) {
                    it.source == com.beatraxus.app.model.SongSource.GDRIVE &&
                            it.driveAccountEmail.equals(state.selectedItemName, ignoreCase = true)
                } else {
                    it.isCloud()
                }
            }
            else -> modeSongs
        }

        if (songsToShuffle.isNotEmpty()) {
            service?.setShuffleMode(true)
            val shuffled = songsToShuffle.shuffled()
            service?.playList(shuffled, 0)
            saveQueueToPrefs(shuffled, songsToShuffle, 0)
        }
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
        
        _uiState.update { it.copy(isLoadingLyrics = true) }
        
        lyricsJob = viewModelScope.launch {
            try {
                val result = lyricsRepository.fetchOnline(song)
                if (result != null) {
                    _uiState.update {
                        it.copy(
                            lyrics = result.lines,
                            lyricsCurrentIndex = -1,
                            isLoadingLyrics = false,
                            lyricsSource = result.source
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoadingLyrics = false) }
                    // Maybe show a toast or error?
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingLyrics = false) }
            }
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
                isLoadingLyrics = false,
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
                                lyricsSource = state.result.source
                            )
                        }
                    }
                    is LyricsState.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoadingLyrics = false,
                                lyrics = emptyList(),
                                lyricsSource = null
                            )
                        }
                    }
                }
            }
        }
    }

    private fun preloadUpcomingLyrics(songs: List<Song>) {
        preloadLyricsJob?.cancel()
        preloadLyricsJob = viewModelScope.launch {
            lyricsRepository.preloadLyrics(songs)
        }
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        _uiState.update { it.copy(lyricsOffsetMs = it.lyricsOffsetMs + deltaMs) }
    }

    fun setLyricsOffset(offset: Long) {
        _uiState.update { it.copy(lyricsOffsetMs = offset) }
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
        
        val adjustedMs = currentMs + state.lyricsOffsetMs
        val index = state.lyrics.findLast { it.startTime <= adjustedMs }?.let { state.lyrics.indexOf(it) } ?: -1
        
        if (index != state.lyricsCurrentIndex) {
            _uiState.update { it.copy(lyricsCurrentIndex = index) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingTelegram = true) }
            try {
                // To change number, we need to log out or restart the client
                // For simplicity and reliability with TDLib, we restart the client
                tdLibManager.close()
                delay(500)
                tdLibManager.ensureClientStarted()
                _uiState.update { it.copy(telegramAuthError = null) }
            } catch (e: Exception) {
                Log.e("TDLib", "Failed to restart auth", e)
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

        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCloudScanning = true, scanProgress = 0f, telegramSyncErrorMessage = "Connecting...", showSyncStatusOnHome = true, isSyncFinishedRecently = false) }
            try {
                val channelName = normalizedUrl.substringAfterLast("/")
                Log.d("PlayerViewModel", "Starting Telegram sync for: $normalizedUrl")
                
                // 1. Get existing songs for this channel
                val existingSongs = songDao.getSongsByTelegramChannel(normalizedUrl).map { entity ->
                    // ... (rest of normalization logic)
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
                        source = SongSource.valueOf(entity.source),
                        driveFileId = entity.driveFileId,
                        driveAccountEmail = entity.driveAccountEmail,
                        telegramChannelUrl = entity.telegramChannelUrl,
                        telegramChatId = entity.telegramChatId,
                        telegramMessageId = entity.telegramMessageId,
                        telegramFileId = entity.telegramFileId,
                        isEnriched = entity.isEnriched,
                        albumArtFetchAttempted = entity.albumArtFetchAttempted,
                        lastSyncTimestamp = entity.lastSyncTimestamp
                    )
                }.associateBy { it.id }

                // 2. Fast scan messages
                Log.d("PlayerViewModel", "Scanning Telegram channel: $normalizedUrl")
                val songs = telegramChannelRepository.scanChannel(
                    tdLibManager, 
                    cloudCacheManager, 
                    normalizedUrl, 
                    existingSongs,
                    _uiState.value.telegramAllowedFormats
                ) { progress ->
                    _uiState.update { it.copy(scanProgress = progress * 0.1f, telegramSyncErrorMessage = "Scanning messages...") } // First 10% is scanning
                }
                
                Log.d("PlayerViewModel", "Scan complete. Found ${songs.size} songs.")
                
                if (songs.isNotEmpty()) {
                    Log.d("PlayerViewModel", "Updating UI with ${songs.size} songs")
                    
                    // Identify missing songs
                    val newSongIds = songs.map { it.id }.toSet()
                    val songsToDelete = existingSongs.filterKeys { it !in newSongIds }.keys.toList()
                    if (songsToDelete.isNotEmpty()) {
                        Log.d("PlayerViewModel", "Removing ${songsToDelete.size} missing songs from Telegram channel")
                        withContext(Dispatchers.IO) {
                            songDao.deleteSongsByIds(songsToDelete)
                        }
                    }

                    // Initial insert of all songs (metadata from messages)
                    val entities = songs.map { it.toEntity() }
                    withContext(Dispatchers.IO) {
                        songDao.insertSongs(entities)
                    }
                    
                    _songs.update { current ->
                        val updatedIds = songs.map { it.id }.toSet()
                        val unchanged = current.filter { it.id !in updatedIds && it.id !in songsToDelete }
                        (unchanged + songs).sortedBy { it.title }
                    }
                    
                    _uiState.update { it.copy(telegramSyncErrorMessage = "Found ${songs.size} songs. Starting enrichment...") }

                    // 3. Deep Enrichment for new songs (fetch duration, bitrate, album art)
                    val toEnrich = songs.filter {
                        !it.isEnriched || (it.albumArtUri == null && !it.albumArtFetchAttempted)
                    }
                    Log.d("PlayerViewModel", "Songs to enrich: ${toEnrich.size}")
                    if (toEnrich.isNotEmpty()) {
                        _uiState.update { it.copy(enrichmentStatus = "Enriching ${toEnrich.size} new Telegram songs...") }
                        
                        val enrichedCount = AtomicInteger(0)
                        val enrichmentSemaphore = Semaphore(30)
                        
                        toEnrich.map { song ->
                            async {
                                enrichmentSemaphore.withPermit {
                                    if (!isActive) return@async
                                    
                                    val currentCount = enrichedCount.get() + 1
                                    Log.d("PlayerViewModel", "Enriching [$currentCount/${toEnrich.size}]: ${song.title}")
                                    
                                    val enriched = extractTelegramMetadata(song)
                                    if (enriched != null) {
                                        withContext(Dispatchers.IO) {
                                            songDao.insertSong(enriched.toEntity())
                                        }
                                        _songs.update { current ->
                                            if (current.any { it.id == enriched.id }) {
                                                current.map { if (it.id == enriched.id) enriched else it }
                                            } else {
                                                (current + enriched).sortedBy { it.title }
                                            }
                                        }
                                    }
                                    
                                    val processedCount = enrichedCount.incrementAndGet()
                                    val enrichmentProgress = 0.1f + (processedCount.toFloat() / toEnrich.size.toFloat() * 0.9f)
                                    _uiState.update { it.copy(scanProgress = enrichmentProgress) }
                                    service?.updateEnrichingProgress(enrichmentProgress, processedCount, toEnrich.size)
                                }
                            }
                        }.awaitAll()
                    }

                    _uiState.update { it.copy(telegramSyncErrorMessage = "Synced ${songs.size} songs from $channelName", enrichmentStatus = null, isSyncFinishedRecently = true) }
                    startSyncDismissTimer()
                    service?.updateEnrichingProgress(1.0f, toEnrich.size, toEnrich.size)
                } else {
                    Log.d("PlayerViewModel", "No songs found for Telegram channel: $normalizedUrl")
                    // All songs might have been removed
                    if (existingSongs.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            songDao.deleteSongsByTelegramChannel(normalizedUrl)
                        }
                        _songs.update { current -> current.filterNot { it.telegramChannelUrl == normalizedUrl } }
                    }
                    _uiState.update { it.copy(telegramSyncErrorMessage = "No songs found in $channelName", isSyncFinishedRecently = true) }
                    startSyncDismissTimer()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("PlayerViewModel", "Telegram sync failed", e)
                _uiState.update { it.copy(telegramSyncErrorMessage = "Sync failed: ${e.message}", enrichmentStatus = null, isSyncFinishedRecently = true) }
                startSyncDismissTimer()
            } finally {
                _uiState.update { it.copy(isCloudScanning = false) }
            }
        }
    }

    private suspend fun extractTelegramMetadata(song: Song): Song? {
        val fileId = song.telegramFileId ?: return null
        
        try {
            // Download first 1MB for metadata
            val downloadSize = 1024 * 1024L
            tdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, downloadSize, true))
            
            // Wait for partial download
            var attempts = 0
            var path: String? = null
            var file: TdApi.File? = null
            while (attempts < 60) { // 3 seconds
                file = try { tdLibManager.send(TdApi.GetFile(fileId)) } catch (e: Exception) { null }
                if (file != null && file.local.path.isNotBlank() && (file.local.isDownloadingCompleted || file.local.downloadedPrefixSize >= downloadSize)) {
                    path = file.local.path
                    break
                }
                delay(50)
                attempts++
            }
            
            if (path == null) return null
            
            var tempFile = File(path)
            if (!tempFile.exists()) return null

            var enriched = metadataExtractor.extractMetadataFromLocalFile(song, tempFile)

            // WAV art often lives near the END of the file (after the audio "data" chunk),
            // which the 1MB header download won't contain. Fetch the tail too, same as
            // the Google Drive path does, before giving up.
            val isWav = song.format.lowercase().contains("wav")
            val totalSize = file?.size?.toLong() ?: 0L
            if (isWav && enriched.albumArtUri == null && totalSize > downloadSize) {
                val tailSize = 8 * 1024 * 1024L
                val offset = (totalSize - tailSize).coerceAtLeast(downloadSize)
                tdLibManager.send(TdApi.DownloadFile(fileId, 32, offset, totalSize - offset, true))

                var tailAttempts = 0
                while (tailAttempts < 60) {
                    val updated = try { tdLibManager.send(TdApi.GetFile(fileId)) } catch (e: Exception) { null }
                    if (updated != null && (updated.local.isDownloadingCompleted || updated.local.downloadedPrefixSize >= totalSize)) {
                        tempFile = File(updated.local.path)
                        break
                    }
                    delay(50)
                    tailAttempts++
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
        }
    }

    fun toggleTelegramChannelEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            telegramChannelRepository.toggleChannel(url, enabled)
        }
    }

    fun removeTelegramChannel(url: String) {
        viewModelScope.launch {
            telegramChannelRepository.removeChannel(url)
            songDao.deleteSongsByTelegramChannel(url)
            _songs.update { current -> current.filterNot { it.telegramChannelUrl == url } }
        }
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("scrobbling_enabled", enabled).apply()
        _uiState.update { it.copy(scrobblingEnabled = enabled) }
    }

    fun logoutLastFm() {
        viewModelScope.launch {
            lastFmRepository.logout()
        }
    }

    fun onLastFmAuthStarted() {
        pendingLastFmAuth.set(true)
    }

    fun isPendingLastFmAuthRequest(): Boolean = pendingLastFmAuth.getAndSet(false)

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
    }

    private fun startSyncDismissTimer() {
        viewModelScope.launch {
            delay(5000)
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
