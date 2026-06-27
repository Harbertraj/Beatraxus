package com.beatflowy.app.viewmodel

import java.io.File

import android.app.Application
import android.util.Log
import android.media.AudioManager
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlin.math.roundToInt
import com.beatflowy.app.BeatraxusApplication
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.PlaylistEntity
import com.beatflowy.app.model.FavoriteEntity
import com.beatflowy.app.model.AutoEqProfileSummary
import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.HrtfMode
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.SavedEqPreset
import com.beatflowy.app.model.SoundStageNodePosition
import com.beatflowy.app.model.ReplayGainOption
import com.beatflowy.app.model.ReplayGainSource
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.defaultEqBands
import com.beatflowy.app.model.Playlist
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.beatflowy.app.drive.DrivePlaybackHelper
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SortType
import com.beatflowy.app.model.ViewMode
import com.beatflowy.app.model.LibraryMode
import com.beatflowy.app.repository.MusicRepository
import com.beatflowy.app.repository.AutoEqRepository
import com.beatflowy.app.repository.LyricsRepository
import com.beatflowy.app.repository.LrcParser
import com.beatflowy.app.repository.LyricsSource
import com.beatflowy.app.repository.LyricsState
import com.beatflowy.app.repository.LyricsType
import com.beatflowy.app.repository.DspPreferences
import com.beatflowy.app.repository.DriveAccount
import com.beatflowy.app.repository.TelegramChannelRepository
import com.beatflowy.app.service.AudioPlaybackService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val musicRepository = MusicRepository(application)
    private val autoEqRepository = AutoEqRepository(application)
    private val autoEqApiService = com.beatflowy.app.repository.AutoEqApiService(application)
    private val lyricsRepository = LyricsRepository(application, (application as BeatraxusApplication).database)
    private val dspPreferences = DspPreferences(application)
    private val driveAccountRepository = DriveAccountRepository(application)
    private val telegramChannelRepository = TelegramChannelRepository(application)
    private val lastFmRepository = com.beatflowy.app.repository.lastfm.LastFmRepository(application)
    private val networkObserver = com.beatflowy.app.util.NetworkObserver(application)
    private val backupRepository = com.beatflowy.app.repository.BackupRepository(
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
    private val aiAnalysisEngine = com.beatflowy.app.engine.AiAnalysisEngine(application)

    private val aiAnalysisChannel = kotlinx.coroutines.channels.Channel<Song>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    
    init {
        // Start AI Analysis worker
        viewModelScope.launch(Dispatchers.Default) {
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
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "AI Analysis failed for ${song.title}", e)
                }
                // Small delay to prevent CPU hogging
                delay(100)
            }
        }
    }

    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(PlayerUiState(
        isFirstRun = prefs.getBoolean("first_run", true),
        useOriginalQualityArt = prefs.getBoolean("use_original_quality_art", false),
        outputMode = OutputMode.fromName(prefs.getString(KEY_OUTPUT_MODE, null)).name,
        musicFolders = musicRepository.getMusicFolders(),
        blockedFolders = musicRepository.getBlockedFolders(),
        dsp = com.beatflowy.app.model.DspUiState(
            customEqPresets = loadCustomEqPresets()
        ),
        libraryMode = LibraryMode.valueOf(prefs.getString("library_mode", LibraryMode.COMBINED.name) ?: LibraryMode.COMBINED.name),
        metadataNetworkType = com.beatflowy.app.model.NetworkType.valueOf(prefs.getString("metadata_network_type", com.beatflowy.app.model.NetworkType.WIFI_ONLY.name) ?: com.beatflowy.app.model.NetworkType.WIFI_ONLY.name),
        dataSaverEnabled = prefs.getBoolean("data_saver_enabled", false),
        artworkEnrichmentEnabled = prefs.getBoolean("artwork_enrichment_enabled", true),
        syncQuality = com.beatflowy.app.model.SyncQuality.valueOf(prefs.getString("sync_quality", com.beatflowy.app.model.SyncQuality.MEDIUM.name) ?: com.beatflowy.app.model.SyncQuality.MEDIUM.name),
        backgroundSyncEnabled = prefs.getBoolean("background_sync_enabled", true),
        scrobblingEnabled = prefs.getBoolean("scrobbling_enabled", true)
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

    val aiAnalysis: StateFlow<Map<String, com.beatflowy.app.model.AiAnalysisEntity>> = aiAnalysisDao.getAllAnalysisFlow()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _songs.asStateFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allSongsWithFavorites: StateFlow<List<Song>> = combine(allSongs, favorites) { songs, favoriteIds ->
        songs.map { it.copy(isFavorite = favoriteIds.contains(it.id)) }
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
        songs.filter { it.source == SongSource.GDRIVE }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums = filteredSongsByMode.map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists = filteredSongsByMode.map { songs ->
        songs.groupBy { it.artist }
            .map { (name, list) -> Triple(name, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders = combine(filteredSongsByMode, _uiState) { songs, state ->
        val parentPath = state.currentFolderPath
        if (parentPath == null) {
            songs.groupBy { it.folder }
                .map { (path, list) -> 
                    val firstSong = list.first()
                    if (firstSong.source == com.beatflowy.app.model.SongSource.GDRIVE) {
                        Triple(path, "GDRIVE", firstSong.albumArtUri)
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
            LibraryView.RECENTLY_PLAYED -> {
                recentIds.filter { it != state.currentSong?.id }
                    .mapNotNull { id -> all.find { it.id == id } }
            }
            LibraryView.ALBUM_DETAIL -> all.filter { it.album == state.selectedItemName }
            LibraryView.ARTIST_DETAIL -> all.filter { it.artist == state.selectedItemName }
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
                    it.source == com.beatflowy.app.model.SongSource.TELEGRAM && it.telegramChannelUrl == state.selectedTelegramChannelUrl
                } else {
                    it.source == com.beatflowy.app.model.SongSource.GDRIVE && 
                    (state.selectedItemName == null || it.driveAccountEmail?.lowercase() == state.selectedItemName.lowercase())
                }
            }
        }
        
        if (debouncedQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(debouncedQuery, ignoreCase = true) ||
                    it.artist.contains(debouncedQuery, ignoreCase = true) ||
                    it.album.contains(debouncedQuery, ignoreCase = true)
            }
        }

        val comparator = when (state.sortType) {
            com.beatflowy.app.model.SortType.NAME -> compareBy<Song> { it.title.lowercase() }
            com.beatflowy.app.model.SortType.DATE_ADDED -> compareBy { it.dateAdded }
            com.beatflowy.app.model.SortType.FILE_SIZE -> compareBy { it.fileSizeBytes }
            com.beatflowy.app.model.SortType.DURATION -> compareBy { it.durationMs }
        }

        if (state.isAscending) filtered.sortedWith(comparator)
        else filtered.sortedWith(comparator).reversed()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingDeleteIds = emptyList<String>()

    private var libraryLoadJob: Job? = null
    private var serviceObserversJob: Job? = null

    init {
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
        viewModelScope.launch {
            com.beatflowy.app.drive.DrivePlaybackHelper.authRecoveryFlow.collect { intent ->
                _uiState.update { it.copy(authRecoveryIntent = intent) }
            }
        }
        viewModelScope.launch {
            com.beatflowy.app.drive.DrivePlaybackHelper.errorState.collect { error ->
                _uiState.update { it.copy(errorMessage = error) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                driveAccountRepository.accounts.collect { accounts ->
                    val accountEmails = accounts.map { it.email.lowercase() }.toSet()
                    _songs.update { current ->
                        current.filter { it.source != SongSource.GDRIVE || (it.driveAccountEmail?.lowercase() ?: "") in accountEmails }
                    }
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error collecting drive accounts", e)
            }
        }
        viewModelScope.launch {
            lastFmRepository.username.collect { name ->
                _uiState.update { it.copy(lastFmUsername = name) }
            }
        }
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
                } catch (e: Exception) {}
            }
            
            // 3. Force a full scan to re-cache images with new quality setting
            startFullScan()
        }
    }

    fun loadLibrary() {
        if (libraryLoadJob?.isActive == true) return
        
        libraryLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(permissionDenied = false, isScanning = true) }
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
                            isEnriched = entity.isEnriched,
                            lastSyncTimestamp = entity.lastSyncTimestamp
                        )
                    }
                }

                if (_uiState.value.isFirstRun) {
                    if (dbSongs.isNotEmpty()) {
                        setFirstRunComplete()
                    } else {
                        _uiState.update { it.copy(isScanning = false, showScanOptions = true) }
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
                        artUri != null && artUri.scheme == "file" && !File(artUri.path ?: "").exists()
                    }
                    
                    _songs.value = sortedSongs

                    // Restore last queue and playing song
                    val lastSongId = prefs?.getString("last_song_id", null)
                    val lastQueueIds = prefs?.getString("last_queue_ids", null)?.split(",")?.filter { it.isNotBlank() }
                    val lastOriginalQueueIds = prefs?.getString("last_original_queue_ids", null)?.split(",")?.filter { it.isNotBlank() }
                    val lastIndex = prefs?.getInt("last_queue_index", -1) ?: -1
                    val lastPos = prefs?.getLong("last_song_pos", 0L) ?: 0L

                    if (!lastQueueIds.isNullOrEmpty()) {
                        val songMap = dbSongs.associateBy { it.id }
                        val restoredPlaylist = lastQueueIds.mapNotNull { id -> songMap[id] }
                        val restoredOriginalPlaylist = lastOriginalQueueIds?.mapNotNull { id -> songMap[id] } ?: restoredPlaylist

                        if (restoredPlaylist.isNotEmpty()) {
                            viewModelScope.launch {
                                // Wait for service to be attached
                                while (service == null) {
                                    delay(100)
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
                                    delay(100)
                                }
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
                    _uiState.update { it.copy(isScanning = false) }
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
                            
                            it.copy(
                                isPlaying = pbState.isPlaying,
                                currentSong = pbState.currentSong,
                                shuffleMode = pbState.shuffleMode,
                                repeatMode = pbState.repeatMode.ordinal,
                                // If it's a new song, we can't trust 'it.bitrate' etc. yet as they might belong to the previous song.
                                // But if the engine has already updated for the new song, we should keep it.
                                bitrate = if (sameSong) (if (it.bitrate > 0) it.bitrate else pbState.currentSong?.bitrate ?: 0) else pbState.currentSong?.bitrate ?: 0,
                                format = if (sameSong) (if (it.format.isNotBlank()) it.format else pbState.currentSong?.format ?: "") else pbState.currentSong?.format ?: "",
                                bitDepth = if (sameSong) it.bitDepth else pbState.currentSong?.bitDepth ?: 16,
                                inputSampleRate = if (sameSong) it.inputSampleRate else pbState.currentSong?.sampleRateHz ?: 44100
                            )
                        }

                        if (resetProgress) {
                            if (pbState.currentSong != null) {
                                updateRecentlyPlayed(pbState.currentSong.id)
                                handleSongChangeForSleepTimer(pbState.currentSong)
                                if (_uiState.value.showLyrics) {
                                    loadLyrics(pbState.currentSong)
                                }
                            } else {
                                _uiState.update {
                                    it.copy(lyrics = emptyList(), lyricsCurrentIndex = -1, lyricsCurrentSongId = null)
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

    val driveAccounts = driveAccountRepository.accounts
    val telegramChannels = telegramChannelRepository.channels

    fun removeDriveAccount(email: String) {
        viewModelScope.launch {
            driveAccountRepository.removeAccount(email)
            // Optionally remove songs from this account from database
            songDao.deleteSongsByAccount(email.lowercase())
            _songs.update { current -> current.filterNot { it.driveAccountEmail?.lowercase() == email.lowercase() } }
        }
    }

    init {
        viewModelScope.launch {
            networkObserver.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
                if (!online && _uiState.value.isCloudScanning) {
                    _uiState.update { it.copy(errorMessage = "Sync paused: No internet connection") }
                } else if (online && _uiState.value.errorMessage?.contains("Sync paused") == true) {
                    _uiState.update { it.copy(errorMessage = "Network restored, continuing sync...") }
                }
            }
        }
    }

    private var enrichmentJob: Job? = null

    fun scanDriveAccount(email: String) {
        if (_uiState.value.isCloudScanning) return // Already scanning, don't restart

        val networkType = _uiState.value.metadataNetworkType
        val context = getApplication<Application>()
        
        if (networkType == com.beatflowy.app.model.NetworkType.ASK_MOBILE && 
            com.beatflowy.app.util.NetworkUtils.isMobileConnected(context) && 
            !com.beatflowy.app.util.NetworkUtils.isWifiConnected(context)) {
            _uiState.update { it.copy(errorMessage = "Confirmation needed: Use mobile data for sync?") }
            // In a real app, this would trigger a dialog. For now, we'll block and show the message.
            return
        }

        if (!com.beatflowy.app.util.NetworkUtils.isNetworkAllowed(context, networkType)) {
            _uiState.update { it.copy(errorMessage = "Waiting for allowed network (Rule: $networkType)") }
            return
        }

        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch {
            Log.d("PlayerViewModel", "Scanning drive account: $email")
            _uiState.update { it.copy(isCloudScanning = true, scanProgress = 0f, errorMessage = null) }
            try {
                val credential = driveAccountRepository.getCredential(email)
                val scanner = com.beatflowy.app.drive.DriveLibraryScanner(getApplication())
                val newSongs = scanner.scanAccount(credential)
                
                Log.d("PlayerViewModel", "Found ${newSongs.size} songs from drive")
                
                // Get existing songs for this account to preserve metadata and handle deletions
                val existingSongs = withContext(Dispatchers.IO) {
                    songDao.getSongsByAccount(email.lowercase()).associateBy { it.id }
                }

                // Identify missing songs (were in DB but NOT in new scan)
                val newSongIds = newSongs.map { it.id }.toSet()
                // Disabled automatic deletion to preserve "old sync data" as requested.
                // Missing songs will stay in DB but might fail to play if deleted on Drive.
                /*
                val songsToDelete = existingSongs.filterKeys { it !in newSongIds }.keys.toList()
                if (songsToDelete.isNotEmpty()) {
                    Log.d("PlayerViewModel", "Removing ${songsToDelete.size} missing songs from DB")
                    withContext(Dispatchers.IO) {
                        songDao.deleteSongsByIds(songsToDelete)
                    }
                }
                */

                if (newSongs.isNotEmpty()) {
                    val updatedNewSongs = newSongs.map { song ->
                        val existing = existingSongs[song.id]
                        // Preserve metadata if it was already enriched
                        if (existing != null && (existing.isEnriched || existing.durationMs > 0)) {
                            // Restore metadata from existing entity to avoid data loss
                            song.copy(
                                durationMs = existing.durationMs,
                                bitrate = existing.bitrate,
                                sampleRateHz = existing.sampleRateHz,
                                bitDepth = existing.bitDepth,
                                albumArtUri = existing.albumArtUriString?.let { Uri.parse(it) } ?: song.albumArtUri,
                                format = existing.format,
                                album = existing.album,
                                artist = existing.artist,
                                genre = existing.genre,
                                year = existing.year,
                                lyrics = existing.lyrics,
                                replayGainTrackDb = existing.replayGainTrackDb,
                                replayGainAlbumDb = existing.replayGainAlbumDb,
                                replayGainTrackPeak = existing.replayGainTrackPeak,
                                replayGainAlbumPeak = existing.replayGainAlbumPeak,
                                isEnriched = existing.isEnriched,
                                lastSyncTimestamp = existing.lastSyncTimestamp
                            )
                        } else {
                            song
                        }
                    }

                    // 1. Initial Quick Insert/Update in DB
                    val entities = updatedNewSongs.map { it.toEntity() }
                    songDao.insertSongs(entities)
                    
                    // Update current song list in memory (Merge with existing to avoid losing data)
                    _songs.update { current ->
                        val updatedIds = updatedNewSongs.map { it.id }.toSet()
                        val unchanged = current.filter { it.id !in updatedIds }
                        (unchanged + updatedNewSongs).sortedBy { it.title }
                    }

                    // 2. Deep Enrichment (fetch metadata, duration, etc. from files)
                    // ONLY enrich truly new songs or those that failed enrichment before.
                    // Removed the 7-day automatic re-sync to respect user preference.
                    val toEnrich = updatedNewSongs.filter { !it.isEnriched }

                    if (toEnrich.isNotEmpty()) {
                        val extractor = com.beatflowy.app.repository.MetadataExtractor(getApplication())
                        var processed = 0
                        val total = toEnrich.size
                        
                        _uiState.update { it.copy(enrichmentStatus = "Enriching $total new songs...") }

                        extractor.extractCloudMetadataBatch(toEnrich, credential) { updatedSong ->
                            processed++
                            val progress = processed.toFloat() / total.toFloat()
                            _uiState.update { it.copy(scanProgress = progress) }
                            service?.updateEnrichingProgress(progress, processed, total)
                            
                            // Update DB and Memory for each song as it finishes
                            songDao.insertSong(updatedSong.toEntity())
                            
                            // AI Analysis for cloud song after enrichment
                            viewModelScope.launch(Dispatchers.Default) {
                                aiAnalysisChannel.send(updatedSong)
                            }

                            _songs.update { current ->
                                current.map { if (it.id == updatedSong.id) updatedSong else it }
                            }
                        }
                        _uiState.update { it.copy(enrichmentStatus = null) }
                        service?.updateEnrichingProgress(1.0f, total, total)
                    }

                    _uiState.update { it.copy(errorMessage = "Synced ${newSongs.size} songs from $email", scanProgress = 1f) }
                } else {
                    // Update memory even if no songs found (might have been all deleted)
                    _songs.update { current ->
                        current.filterNot { it.driveAccountEmail?.lowercase() == email.lowercase() }
                    }
                    _uiState.update { it.copy(errorMessage = "No songs found for $email") }
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Drive scan error for $email", e)
                if (e !is UserRecoverableAuthIOException) {
                    val message = e.message ?: e.javaClass.simpleName
                    _uiState.update { it.copy(errorMessage = "Drive scan failed: $message") }
                }
            } finally {
                _uiState.update { it.copy(isCloudScanning = false) }
            }
        }
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
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLibrary = true, isScanning = true) }
            try {
                val blocked = musicRepository.getBlockedFolders()
                val currentSongs = _songs.value
                val currentLocalSongsMap = currentSongs.filter { it.source == SongSource.LOCAL }.associateBy { it.id }
                
                val resultsFromMediaStore = musicRepository.scanAudioFiles(fullScan = false, excludedPaths = blocked) { count, albums, artists, progress ->
                    _uiState.update { it.copy(
                        scanCount = count,
                        albumCount = albums,
                        artistCount = artists,
                        scanProgress = progress
                    )}
                    service?.updateScanningProgress(progress, count, false)
                }
                
                // Merge: Keep existing deep-scanned metadata if available to prevent info regression
                val results = resultsFromMediaStore.map { scanned ->
                    currentLocalSongsMap[scanned.id] ?: scanned
                }

                val currentLocalIds = currentLocalSongsMap.keys
                val resultIds = results.map { it.id }.toSet()
                val newSongs = results.filter { it.id !in currentLocalIds }
                val removedLocalIds = currentLocalIds - resultIds
                
                // Check if anything actually changed (new files, removed files, or total count)
                val hasChanges = currentLocalSongsMap.size != results.size || newSongs.isNotEmpty() || removedLocalIds.isNotEmpty()

                if (hasChanges) {
                    val cloudSongs = currentSongs.filter { it.source != SongSource.LOCAL }
                    _songs.value = (results + cloudSongs).sortedBy { it.title }
                    
                    val entities = results.map { song -> song.toEntity() }
                    withContext(Dispatchers.IO) {
                        if (removedLocalIds.isNotEmpty()) {
                            songDao.deleteSongsByIds(removedLocalIds.toList())
                        }
                        entities.chunked(200).forEach { chunk ->
                            songDao.insertSongs(chunk)
                        }
                    }
                    
                    // Trigger AI Analysis for new songs
                    if (newSongs.isNotEmpty()) {
                        viewModelScope.launch(Dispatchers.Default) {
                            newSongs.forEach { song ->
                                aiAnalysisChannel.send(song)
                            }
                        }
                    }
                }

                updateLibraryCounts(results)
                
                // Auto-add folders containing music (minimal set), avoiding blocked ones
                val allFolders = results.map { it.folder }.filter { it != "Unknown" }.toSet()
                val sortedFolders = allFolders.sortedBy { it.length }
                val minimalFolders = mutableListOf<String>()
                val blockedSet = blocked.toSet()
                
                for (folder in sortedFolders) {
                    if (blockedSet.any { folder.startsWith(it + "/") || folder == it }) continue
                    
                    if (minimalFolders.none { folder.startsWith(it + "/") || folder == it }) {
                        minimalFolders.add(folder)
                    }
                }
                musicRepository.addMusicFolders(minimalFolders)
                _uiState.update { it.copy(
                    musicFolders = musicRepository.getMusicFolders(),
                    blockedFolders = musicRepository.getBlockedFolders()
                ) }

                val message = when {
                    newSongs.isNotEmpty() && removedLocalIds.isNotEmpty() -> "Added ${newSongs.size} songs, removed ${removedLocalIds.size}"
                    newSongs.isNotEmpty() -> "Added ${newSongs.size} new songs"
                    removedLocalIds.isNotEmpty() -> "Removed ${removedLocalIds.size} missing songs"
                    hasChanges -> "Library updated"
                    else -> "No changes found"
                }

                _uiState.update { it.copy(errorMessage = message) }
                service?.updateScanningProgress(1.0f, results.size, true)
                delay(2000)
                _uiState.update { it.copy(errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Scan failed: ${e.message}") }
                service?.updateScanningProgress(1.0f, _uiState.value.scanCount, true)
            } finally {
                _uiState.update { it.copy(isLoadingLibrary = false, isScanning = false) }
            }
        }
    }

    fun startFullScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, isFullScanning = true, scanProgress = 0f, scanCount = 0, showScanOptions = false, errorMessage = null) }
            
            try {
                val blocked = musicRepository.getBlockedFolders()
                // Use fullScan = true for "Full Rescan" to ensure MediaExtractor/MediaMetadataRetriever are used
                val results = musicRepository.scanAudioFiles(fullScan = true, excludedPaths = blocked) { count, albums, artists, progress ->
                    _uiState.update { it.copy(
                        scanCount = count,
                        albumCount = albums,
                        artistCount = artists,
                        scanProgress = progress
                    )}
                    service?.updateScanningProgress(progress, count, false)
                }
                
                val currentSongs = _songs.value
                val cloudSongs = currentSongs.filter { it.source != SongSource.LOCAL }
                _songs.value = (results + cloudSongs).sortedBy { it.title }
                
                val entities = results.map { it.toEntity() }
                
                // Perform DB insertion on a background thread and handle chunks to avoid locking the UI
                withContext(Dispatchers.IO) {
                    songDao.deleteLocalSongs()
                    entities.chunked(100).forEach { chunk ->
                        songDao.insertSongs(chunk)
                    }
                }

                // AI Analysis for all songs in full scan
                viewModelScope.launch(Dispatchers.Default) {
                    results.forEach { song ->
                        aiAnalysisChannel.send(song)
                    }
                }

                updateLibraryCounts(results)
                
                // Auto-add folders containing music (minimal set), avoiding blocked ones
                val allFolders = results.map { it.folder }.filter { it != "Unknown" }.toSet()
                val sortedFolders = allFolders.sortedBy { it.length }
                val minimalFolders = mutableListOf<String>()
                val blockedSet = blocked.toSet()

                for (folder in sortedFolders) {
                    if (blockedSet.any { folder.startsWith(it + "/") || folder == it }) continue

                    if (minimalFolders.none { folder.startsWith(it + "/") || folder == it }) {
                        minimalFolders.add(folder)
                    }
                }
                musicRepository.addMusicFolders(minimalFolders)

                _uiState.update {
                    it.copy(
                        scanProgress = 1.0f,
                        scanCount = results.size,
                        albumCount = results.map { song -> song.album }.toSet().size,
                        artistCount = results.map { song -> song.artist }.toSet().size,
                        musicFolders = musicRepository.getMusicFolders(),
                        blockedFolders = musicRepository.getBlockedFolders()
                    )
                }
                service?.updateScanningProgress(1.0f, results.size, true)

                // Mark first run as complete if we finished a full scan successfully
                if (_uiState.value.isFirstRun) {
                    setFirstRunComplete()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Full scan failed: ${e.message}") }
                service?.updateScanningProgress(1.0f, _uiState.value.scanCount, true)
            } finally {
                _uiState.update { it.copy(isScanning = false, isFullScanning = false) }
            }
        }
    }

    fun startAddedFoldersScan() {
        val folders = _uiState.value.musicFolders
        if (folders.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No folders added to scan") }
            return
        }
        
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = 0f, scanCount = 0, albumCount = 0, artistCount = 0, showScanOptions = false, errorMessage = null) }
            try {
                val allResults = mutableListOf<Song>()
                var totalProcessed = 0
                val allAlbums = mutableSetOf<String>()
                val allArtists = mutableSetOf<String>()
                
                folders.forEachIndexed { index, folder ->
                    val results = musicRepository.scanAudioFiles(fullScan = false, targetPath = folder) { count, albums, artists, progress ->
                        // Calculate global progress across all folders
                        val folderWeight = 1f / folders.size
                        val currentGlobalProgress = (index * folderWeight) + (progress * folderWeight)

                        _uiState.update { it.copy(
                            scanCount = totalProcessed + count,
                            albumCount = allAlbums.size + albums,
                            artistCount = allArtists.size + artists,
                            scanProgress = currentGlobalProgress
                        )}
                        service?.updateScanningProgress(currentGlobalProgress, totalProcessed + count, false)
                    }
                    allResults.addAll(results)
                    totalProcessed += results.size
                    allAlbums.addAll(results.map { it.album })
                    allArtists.addAll(results.map { it.artist })
                }

                val currentSongs = _songs.value
                val cloudSongs = currentSongs.filter { it.source != SongSource.LOCAL }
                
                // For "Scan Added Folders", we only keep songs from those folders + cloud
                // Or maybe we merge? The user said "SCAN ONLY ADDED ALL FOLDERS SONGS".
                // Usually this means the library should consist of these.
                _songs.value = (allResults + cloudSongs).sortedBy { it.title }

                val entities = allResults.map { it.toEntity() }
                withContext(Dispatchers.IO) {
                    songDao.deleteLocalSongs()
                    entities.chunked(100).forEach { chunk ->
                        songDao.insertSongs(chunk)
                    }
                }

                // AI Analysis for added folders scan
                viewModelScope.launch(Dispatchers.Default) {
                    allResults.forEach { song ->
                        aiAnalysisChannel.send(song)
                    }
                }

                updateLibraryCounts(allResults)
                _uiState.update { it.copy(scanProgress = 1.0f, errorMessage = "Scan complete: ${allResults.size} songs found") }
                service?.updateScanningProgress(1.0f, allResults.size, true)
                
                // Mark first run as complete if we finished a folder scan successfully
                if (_uiState.value.isFirstRun) {
                    setFirstRunComplete()
                }

                delay(2000)
                _uiState.update { it.copy(errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Scan failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionDenied = true) }
    }

    fun playSong(song: Song) {
        val list = songs.value
        val index = list.indexOfFirst { it.id == song.id }
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
        _uiState.update { it.copy(
            selectedTelegramChannelUrl = url, 
            selectedItemName = null, // Clear drive account
            currentView = LibraryView.CLOUD,
            isSearchActive = false
        ) }
    }

    fun refreshCloudLibrary() {
        _uiState.value.selectedCloudEmail?.let { scanDriveAccount(it) }
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
            LibraryView.RECENTLY_PLAYED, LibraryView.CLOUD -> songs.value.map { it.id }
            
            LibraryView.ALBUMS -> albums.value.map { it.first }
            LibraryView.ARTISTS -> artists.value.map { it.first }
            LibraryView.FOLDERS -> folders.value.map { it.first }
            LibraryView.YEARS -> years.value.map { it.first }
            LibraryView.GENRES -> genres.value.map { it.first }
            LibraryView.PLAYLISTS -> playlists.value.map { it.id }
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

    fun setSampleFormat(format: com.beatflowy.app.model.SampleFormat) {
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
            put("midBassDb", config.midBassDb)
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
                            frequencyHz = b.getDouble("freq").toFloat(),
                            gainDb = b.getDouble("gain").toFloat().coerceIn(-12f, 12f),
                            q = b.getDouble("q").toFloat().coerceIn(0.1f, 10f)
                        )
                    }
                } else config.eqBands

                config.copy(
                    preampDb = obj.optDouble("preampDb", config.preampDb.toDouble()).toFloat().coerceIn(-20f, 20f),
                    eqEnabled = obj.optBoolean("eqEnabled", config.eqEnabled),
                    midBassDb = obj.optDouble("midBassDb", config.midBassDb.toDouble()).toFloat().coerceIn(-12f, 12f),
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

    fun setMetadataNetworkType(type: com.beatflowy.app.model.NetworkType) {
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

    fun setSyncQuality(quality: com.beatflowy.app.model.SyncQuality) {
        prefs.edit().putString("sync_quality", quality.name).apply()
        _uiState.update { it.copy(syncQuality = quality) }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("background_sync_enabled", enabled).apply()
        _uiState.update { it.copy(backgroundSyncEnabled = enabled) }
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
    fun setEqPhaseMode(mode: com.beatflowy.app.model.EqPhaseMode) = applyDspConfig {
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
    fun setMidBassEnabled(enabled: Boolean) = applyDspConfig { it.copy(midBassEnabled = enabled) }
    fun setMidBassDb(value: Float) = applyDspConfig { it.copy(midBassDb = value.coerceIn(-12f, 12f), midBassEnabled = true) }
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
    fun setSoundStageWidth(value: Float) { applyDspConfig { it.copy(soundStageWidth = value.coerceIn(0f, 2f)) } }
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

    fun setSoxrQuality(quality: com.beatflowy.app.model.SoxrQuality) {
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

    fun setDitherType(type: com.beatflowy.app.model.DitherType) {
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

    fun setEqBandType(index: Int, type: com.beatflowy.app.model.EqBandType) {
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
            LibraryMode.LOCAL -> allSongsList.filter { it.source == com.beatflowy.app.model.SongSource.LOCAL }
            LibraryMode.CLOUD -> allSongsList.filter { it.source != com.beatflowy.app.model.SongSource.LOCAL }
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
            LibraryView.ARTIST_DETAIL -> modeSongs.filter { it.artist == state.selectedItemName }
            LibraryView.FOLDER_DETAIL -> modeSongs.filter { it.folder == state.currentFolderPath }
            LibraryView.YEAR_DETAIL -> modeSongs.filter { it.year.toString() == state.selectedItemName }
            LibraryView.GENRE_DETAIL -> modeSongs.filter { it.genre == state.selectedItemName }
            LibraryView.PLAYLIST_DETAIL -> {
                val playlist = playlists.value.find { it.name == state.selectedItemName }
                playlist?.songIds?.mapNotNull { id -> allSongsList.find { it.id == id } } ?: emptyList()
            }
            LibraryView.CLOUD -> allSongsList.filter {
                if (state.selectedTelegramChannelUrl != null) {
                    it.source == com.beatflowy.app.model.SongSource.TELEGRAM && it.telegramChannelUrl == state.selectedTelegramChannelUrl
                } else {
                    it.source == com.beatflowy.app.model.SongSource.GDRIVE &&
                            (state.selectedItemName == null || it.driveAccountEmail?.lowercase() == state.selectedItemName.lowercase())
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

    fun addTelegramChannel(url: String) {
        viewModelScope.launch {
            telegramChannelRepository.addChannel(url)
        }
    }

    fun syncTelegramChannel(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCloudScanning = true, scanProgress = 0f) }
            try {
                // Basic scraper for Telegram channel web preview
                val channelName = url.trim().removeSuffix("/").substringAfterLast("/")
                val previewUrl = if (url.contains("/s/")) url else "https://t.me/s/$channelName"
                
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(previewUrl).build()
                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""
                
                val songs = mutableListOf<Song>()
                // Matches the audio widget in Telegram web preview
                // It usually looks like this:
                // <div class="tgme_widget_message_inline_audio_performer">Artist</div>
                // <div class="tgme_widget_message_inline_audio_title">Title</div>
                val audioWidgetRegex = Regex("""tgme_widget_message_inline_audio_wrap.*?tgme_widget_message_inline_audio_performer">([^<]+)</div>.*?tgme_widget_message_inline_audio_title">([^<]+)</div>.*?tgme_widget_message_inline_audio_duration">([^<]+)</div>""", RegexOption.DOT_MATCHES_ALL)
                
                audioWidgetRegex.findAll(html).forEach { match ->
                    val artist = match.groupValues[1].trim()
                    val title = match.groupValues[2].trim()
                    val durationStr = match.groupValues[3].trim()
                    
                    val durationParts = durationStr.split(":")
                    val durationMs = try {
                        if (durationParts.size == 2) {
                            (durationParts[0].toLong() * 60 + durationParts[1].toLong()) * 1000
                        } else if (durationParts.size == 3) {
                            (durationParts[0].toLong() * 3600 + durationParts[1].toLong() * 60 + durationParts[2].toLong()) * 1000
                        } else 0L
                    } catch (e: Exception) { 0L }
                    
                    // Generate a unique ID
                    val id = "tg_${channelName}_${(artist + title + durationMs).hashCode()}"
                    
                    songs.add(Song(
                        id = id,
                        uri = Uri.parse(url), // Placeholder
                        title = title,
                        artist = artist,
                        album = "Telegram: $channelName",
                        durationMs = durationMs,
                        format = "MP3",
                        sampleRateHz = 44100,
                        source = SongSource.TELEGRAM,
                        telegramChannelUrl = url,
                        isEnriched = false,
                        lastSyncTimestamp = System.currentTimeMillis()
                    ))
                }

                if (songs.isNotEmpty()) {
                    val entities = songs.map { it.toEntity() }
                    songDao.insertSongs(entities)
                    _songs.update { current ->
                        val filtered = current.filter { it.telegramChannelUrl != url }
                        filtered + songs
                    }
                    _uiState.update { it.copy(errorMessage = "Synced ${songs.size} songs from $channelName") }
                } else {
                    _uiState.update { it.copy(errorMessage = "No audio files found in channel preview") }
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Telegram sync failed", e)
                _uiState.update { it.copy(errorMessage = "Sync failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isCloudScanning = false) }
            }
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

    fun fetchLastFmSession(token: String) {
        viewModelScope.launch {
            lastFmRepository.fetchSession(token)
        }
    }

    private companion object {
        const val FRAME_TICK_MS = 8L // Reduced from 16ms to 8ms for 120Hz smoothness
        const val KEY_OUTPUT_MODE = "output_mode"
        const val KEY_CUSTOM_EQ_PRESETS = "custom_eq_presets"
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

    private fun Song.toEntity() = com.beatflowy.app.model.SongEntity(
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
        isEnriched = isEnriched,
        lastSyncTimestamp = lastSyncTimestamp
    )
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
