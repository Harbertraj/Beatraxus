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
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.ParametricEqBand
import com.beatflowy.app.model.SavedEqPreset
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

    private val database = (application as BeatraxusApplication).database
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val songDao = database.songDao()

    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(PlayerUiState(
        isFirstRun = prefs.getBoolean("first_run", true),
        useOriginalQualityArt = prefs.getBoolean("use_original_quality_art", false),
        outputMode = OutputMode.fromName(prefs.getString(KEY_OUTPUT_MODE, null)).name,
        musicFolders = musicRepository.getMusicFolders(),
        dsp = com.beatflowy.app.model.DspUiState(
            customEqPresets = loadCustomEqPresets()
        ),
        libraryMode = LibraryMode.valueOf(prefs.getString("library_mode", LibraryMode.COMBINED.name) ?: LibraryMode.COMBINED.name)
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

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = combine(_songs, favorites) { songs, favoriteIds ->
        songs.map { it.copy(isFavorite = favoriteIds.contains(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val filteredSongsByMode: StateFlow<List<Song>> = combine(
        allSongs,
        _uiState.map { it.libraryMode }.distinctUntilChanged()
    ) { all, mode ->
        when (mode) {
            LibraryMode.LOCAL -> all.filter { it.source == SongSource.LOCAL }
            LibraryMode.CLOUD -> all.filter { it.source != SongSource.LOCAL }
            LibraryMode.COMBINED -> all
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val driveLibrarySongs: StateFlow<List<Song>> = allSongs.map { songs ->
        songs.filter { it.source == SongSource.GDRIVE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums = filteredSongsByMode.map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists = filteredSongsByMode.map { songs ->
        songs.groupBy { it.artist }
            .map { (name, list) -> Triple(name, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders = combine(filteredSongsByMode, _uiState) { songs, state ->
        val parentPath = state.currentFolderPath
        if (parentPath == null) {
            songs.groupBy { it.folder }
                .map { (path, list) -> Triple(path, path.substringAfterLast("/"), list.first().albumArtUri) }
                .sortedBy { it.second.lowercase() }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val years = filteredSongsByMode.map { songs ->
        songs.groupBy { it.year }
            .map { (year, list) -> Triple(year.toString(), "${list.size} songs", list.first().albumArtUri) }
            .sortedByDescending { it.first }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genres = filteredSongsByMode.map { songs ->
        songs.groupBy { it.genre }
            .map { (genre, list) -> Triple(genre, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private val _recentlyPlayed = MutableStateFlow<List<String>>(emptyList())

    val songs: StateFlow<List<Song>> = combine(allSongs, _uiState, debouncedSearchQuery, _recentlyPlayed, playlists) { allSongsList, state, debouncedQuery, recentIds, pls ->
        val mode = state.libraryMode
        val all = when (mode) {
            LibraryMode.LOCAL -> allSongsList.filter { it.source == SongSource.LOCAL }
            LibraryMode.CLOUD -> allSongsList.filter { it.source != SongSource.LOCAL }
            LibraryMode.COMBINED -> allSongsList
        }

        var filtered = when (state.currentView) {
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
                it.source == com.beatflowy.app.model.SongSource.GDRIVE && 
                (state.selectedItemName == null || it.driveAccountEmail?.lowercase() == state.selectedItemName.lowercase())
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingDeleteIds = emptyList<String>()

    private var libraryLoadJob: Job? = null
    private var serviceObserversJob: Job? = null

    init {
        viewModelScope.launch {
            dspPreferences.dspConfig.collect { config ->
                _uiState.update { it.copy(dsp = it.dsp.copy(config = config)) }
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
                    val cacheDir = File(getApplication<android.app.Application>().cacheDir, "embedded_album_art")
                    if (cacheDir.exists()) {
                        cacheDir.deleteRecursively()
                    }
                } catch (e: Exception) {}
            }
            
            // 3. Force a full scan to re-cache images with new quality setting
            startFullScan()
        }
    }

    fun loadLibrary() {
        if (libraryLoadJob?.isActive == true) return
        _uiState.update { it.copy(permissionDenied = false, isScanning = true) }
        libraryLoadJob = viewModelScope.launch {
            if (_uiState.value.isFirstRun) {
                quickScan()
                return@launch
            }
            
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
                            folder = entity.folder,
                            dateAdded = entity.dateAdded,
                            replayGainTrackDb = entity.replayGainTrackDb,
                            replayGainAlbumDb = entity.replayGainAlbumDb,
                            replayGainTrackPeak = entity.replayGainTrackPeak,
                            replayGainAlbumPeak = entity.replayGainAlbumPeak,
                            source = SongSource.valueOf(entity.source),
                            driveFileId = entity.driveFileId,
                            driveAccountEmail = entity.driveAccountEmail
                        )
                    }
                }
                if (dbSongs.isNotEmpty()) {
                    // Check if cached album art still exists. If not, we need a refresh.
                    val cacheWiped = dbSongs.any { song ->
                        val artUri = song.albumArtUri
                        artUri != null && artUri.scheme == "file" && !File(artUri.path ?: "").exists()
                    }
                    
                    _songs.value = dbSongs
                    
                    if (cacheWiped) {
                        startFullScan()
                        return@launch
                    }
                }
            } catch (e: Exception) {
                // Ignore initial load errors
            }

            // Perform a quick scan to find new files.
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
                                autoEqProfileName = audioState.autoEqProfileName
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
                }
            }
            launch {
                svc.outputRouteStateFlow.collect { routeState ->
                    _uiState.update {
                        it.copy(
                            outputMode = routeState.selectedMode.name,
                            outputDevice = routeState.outputDevice,
                            hiResDirectSupported = routeState.hiResDirectSupported,
                            hiResCapabilitySummary = routeState.capabilitySummary
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

    fun scanDriveAccount(email: String) {
        viewModelScope.launch {
            Log.d("PlayerViewModel", "Scanning drive account: $email")
            _uiState.update { it.copy(isCloudScanning = true, scanProgress = 0f, errorMessage = null) }
            try {
                val credential = driveAccountRepository.getCredential(email)
                val scanner = com.beatflowy.app.drive.DriveLibraryScanner(getApplication())
                val newSongs = scanner.scanAccount(credential)
                
                Log.d("PlayerViewModel", "Found ${newSongs.size} new songs from drive")
                if (newSongs.isNotEmpty()) {
                    // 1. Initial Quick Insert
                    val entities = newSongs.map { it.toEntity() }
                    songDao.insertSongs(entities)
                    
                    // Update current song list in memory
                    _songs.update { current ->
                        val filtered = current.filterNot { it.driveAccountEmail?.lowercase() == email.lowercase() }
                        filtered + newSongs
                    }

                    // 2. Deep Enrichment (fetch metadata, duration, etc. from files)
                    val extractor = com.beatflowy.app.repository.MetadataExtractor(getApplication())
                    var processed = 0
                    val total = newSongs.size
                    
                    extractor.extractCloudMetadataBatch(newSongs, credential) { updatedSong ->
                        processed++
                        val progress = processed.toFloat() / total.toFloat()
                        _uiState.update { it.copy(scanProgress = progress) }
                        
                        // Update DB and Memory for each song as it finishes
                        songDao.insertSong(updatedSong.toEntity())
                        _songs.update { current ->
                            current.map { if (it.id == updatedSong.id) updatedSong else it }
                        }
                    }

                    _uiState.update { it.copy(errorMessage = "Added and enriched ${newSongs.size} songs from $email", scanProgress = 1f) }
                } else {
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

    fun quickScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLibrary = true, isScanning = true) }
            try {
                val currentSongsMap = _songs.value.associateBy { it.id }
                val resultsFromMediaStore = musicRepository.scanAudioFiles(fullScan = false) { count, albums, artists, progress ->
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
                    currentSongsMap[scanned.id] ?: scanned
                }

                val currentIds = currentSongsMap.keys
                val resultIds = results.map { it.id }.toSet()
                val newSongs = results.filter { it.id !in currentIds }
                val removedIds = currentIds - resultIds
                
                // Check if anything actually changed (new files, removed files, or total count)
                val hasChanges = currentSongsMap.size != results.size || newSongs.isNotEmpty() || removedIds.isNotEmpty()

                if (hasChanges) {
                    _songs.value = results
                    val entities = results.map { song -> song.toEntity() }
                    withContext(Dispatchers.IO) {
                        if (removedIds.isNotEmpty()) {
                            songDao.deleteSongsByIds(removedIds.toList())
                        }
                        entities.chunked(200).forEach { chunk ->
                            songDao.insertSongs(chunk)
                        }
                    }
                }

                updateLibraryCounts(results)
                
                // Auto-add folders containing music (minimal set)
                val allFolders = results.map { it.folder }.filter { it != "Unknown" }.toSet()
                val sortedFolders = allFolders.sortedBy { it.length }
                val minimalFolders = mutableListOf<String>()
                for (folder in sortedFolders) {
                    if (minimalFolders.none { folder.startsWith(it + "/") || folder == it }) {
                        minimalFolders.add(folder)
                    }
                }
                musicRepository.addMusicFolders(minimalFolders)
                _uiState.update { it.copy(musicFolders = musicRepository.getMusicFolders()) }

                val message = when {
                    newSongs.isNotEmpty() && removedIds.isNotEmpty() -> "Added ${newSongs.size} songs, removed ${removedIds.size}"
                    newSongs.isNotEmpty() -> "Added ${newSongs.size} new songs"
                    removedIds.isNotEmpty() -> "Removed ${removedIds.size} missing songs"
                    hasChanges -> "Library updated"
                    else -> "No changes found"
                }

                _uiState.update { it.copy(errorMessage = message) }
                delay(2000)
                _uiState.update { it.copy(errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Scan failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoadingLibrary = false, isScanning = false) }
            }
        }
    }

    fun startFullScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, isFullScanning = true, scanProgress = 0f, scanCount = 0) }
            
            try {
                // Use fullScan = true for "Full Rescan" to ensure MediaExtractor/MediaMetadataRetriever are used
                val results = musicRepository.scanAudioFiles(fullScan = true) { count, albums, artists, progress ->
                    _uiState.update { it.copy(
                        scanCount = count,
                        albumCount = albums,
                        artistCount = artists,
                        scanProgress = progress
                    )}
                    service?.updateScanningProgress(progress, count, false)
                }
                
                _songs.value = results
                val entities = results.map { song ->
                    com.beatflowy.app.model.SongEntity(
                        id = song.id,
                        uriString = song.uri.toString(),
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                        format = song.format,
                        sampleRateHz = song.sampleRateHz,
                        bitDepth = song.bitDepth,
                        bitrate = song.bitrate,
                        fileSizeBytes = song.fileSizeBytes,
                        albumArtUriString = song.albumArtUri?.toString(),
                        year = song.year,
                        genre = song.genre,
                        folder = song.folder,
                        dateAdded = song.dateAdded,
                        replayGainTrackDb = song.replayGainTrackDb,
                        replayGainAlbumDb = song.replayGainAlbumDb,
                        replayGainTrackPeak = song.replayGainTrackPeak,
                        replayGainAlbumPeak = song.replayGainAlbumPeak,
                        source = song.source.name,
                        driveFileId = song.driveFileId,
                        driveAccountEmail = song.driveAccountEmail
                    )
                }
                
                // Perform DB insertion on a background thread and handle chunks to avoid locking the UI
                withContext(Dispatchers.IO) {
                    songDao.deleteAllSongs()
                    entities.chunked(100).forEach { chunk ->
                        songDao.insertSongs(chunk)
                    }
                }

                updateLibraryCounts(results)
                
                // Auto-add folders containing music (minimal set)
                val allFolders = results.map { it.folder }.filter { it != "Unknown" }.toSet()
                val sortedFolders = allFolders.sortedBy { it.length }
                val minimalFolders = mutableListOf<String>()
                for (folder in sortedFolders) {
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
                        musicFolders = musicRepository.getMusicFolders()
                    )
                }
                service?.updateScanningProgress(1.0f, results.size, true)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Full scan failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isScanning = false, isFullScanning = false) }
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
            }
        } else {
            if (_uiState.value.currentSong?.id == song.id) {
                service?.togglePlayPause()
            } else {
                service?.playSong(song)
            }
        }
        updateRecentlyPlayed(song.id)
        loadLyrics(song)
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
                currentFolderPath = if (view == LibraryView.FOLDER_DETAIL) it.currentFolderPath else null,
                wasSearchingBeforeDetail = if (isDetailView) it.isSearchActive else it.wasSearchingBeforeDetail
            ) 
        }
    }

    fun setLibraryViewTelegram(url: String) {
        _uiState.update { it.copy(selectedTelegramChannelUrl = url, currentView = LibraryView.CLOUD) }
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
                wasSearchingBeforeDetail = it.isSearchActive
            ) 
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _uiState.update { it.copy(isMultiSelectMode = enabled, selectedSongIds = emptySet()) }
    }

    fun toggleSongSelection(songId: String) {
        _uiState.update { 
            val current = it.selectedSongIds
            val updated = if (current.contains(songId)) current - songId else current + songId
            it.copy(selectedSongIds = updated)
        }
    }

    fun getNextSongPreview(): Song? {
        return service?.getNextSong()
    }

    fun deleteSelectedSongs() {
        val selectedIds = _uiState.value.selectedSongIds.toList()
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
        val selectedIds = _uiState.value.selectedSongIds
        if (selectedIds.isEmpty()) return
        
        viewModelScope.launch {
            val currentPlaylists = playlists.value
            val existing = currentPlaylists.find { it.name == playlistName }
            val playlist = if (existing != null) {
                existing.copy(songIds = (existing.songIds + selectedIds).toList().distinct())
            } else {
                Playlist(id = System.currentTimeMillis().toString(), name = playlistName, songIds = selectedIds.toList())
            }
            playlistDao.insertPlaylist(PlaylistEntity(playlist.id, playlist.name, playlist.songIds.joinToString(",")))
            setMultiSelectMode(false)
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

    private fun applyDspConfig(transform: (DspConfig) -> DspConfig) {
        val updated = transform(_uiState.value.dsp.config)
        _uiState.update { it.copy(dsp = it.dsp.copy(config = updated, autoEqError = null)) }
        service?.updateDspConfig(updated)
        viewModelScope.launch {
            dspPreferences.saveConfig(updated)
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
            startFullScan()
        }
    }

    fun removeMusicFolder(path: String) {
        viewModelScope.launch {
            musicRepository.removeMusicFolder(path)
            _uiState.update { it.copy(musicFolders = musicRepository.getMusicFolders()) }
            quickScan()
        }
    }

    fun setDownloadLocation(uri: String) {
        _uiState.update { it.copy(downloadLocation = uri) }
        prefs.edit().putString("download_location", uri).apply()
    }

    fun consumeDownloadFolderPickerTrigger() {
        _uiState.update { it.copy(triggerDownloadFolderPicker = false) }
    }

    fun setLibraryMode(mode: LibraryMode) {
        prefs.edit().putString("library_mode", mode.name).apply()
        _uiState.update { it.copy(libraryMode = mode) }
    }

    fun setPreampEnabled(enabled: Boolean) = applyDspConfig { it.copy(preampEnabled = enabled) }
    fun setPreampDb(value: Float) = applyDspConfig {
        val db = value.coerceIn(-15f, 15f)
        it.copy(preampDb = db, preampEnabled = true)
    }
    fun setEqEnabled(enabled: Boolean) = applyDspConfig {
        it.copy(eqEnabled = enabled)
    }
    fun setAutoEqEnabled(enabled: Boolean) = applyDspConfig {
        if (enabled) it.copy(autoEqEnabled = true, eqEnabled = true) else it.copy(autoEqEnabled = false)
    }
    fun saveCustomEqPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val updated = loadCustomEqPresets()
            .filterNot { it.name.equals(trimmed, ignoreCase = true) }
            .plus(SavedEqPreset(trimmed, _uiState.value.dsp.config.eqBands))
            .sortedBy { it.name.lowercase() }
        persistCustomEqPresets(updated)
        _uiState.update { it.copy(dsp = it.dsp.copy(customEqPresets = updated)) }
    }

    fun applySavedEqPreset(name: String) {
        val preset = _uiState.value.dsp.customEqPresets.firstOrNull { it.name == name } ?: return
        applyDspConfig { it.copy(eqEnabled = true, eqBands = preset.bands, autoEqEnabled = false) }
    }

    fun deleteCustomEqPreset(name: String) {
        val updated = _uiState.value.dsp.customEqPresets.filterNot { it.name == name }
        persistCustomEqPresets(updated)
        _uiState.update { it.copy(dsp = it.dsp.copy(customEqPresets = updated)) }
    }
    fun setBassEnabled(enabled: Boolean) = applyDspConfig { it.copy(bassEnabled = enabled) }
    fun setBassDb(value: Float) = applyDspConfig { it.copy(bassDb = value.coerceIn(-12f, 12f), bassEnabled = true) }
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
    fun setReverbPredelayMix(value: Float) = applyDspConfig { it.copy(reverbPredelayMix = value.coerceIn(0f, 1f)) }
    fun setReverbPredelay(value: Float) = applyDspConfig { it.copy(reverbPredelayMs = value.coerceIn(0f, 1000f)) }
    fun setCrossfeedEnabled(enabled: Boolean) = applyDspConfig { it.copy(crossfeedEnabled = enabled) }
    fun setCrossfeedLevel(value: Float) = applyDspConfig { it.copy(crossfeedLevel = value.coerceIn(0f, 1f), crossfeedEnabled = true) }
    fun setDcBlockerEnabled(enabled: Boolean) = applyDspConfig { it.copy(dcBlockerEnabled = enabled) }

    // Replay Gain
    fun setReplayGainEnabled(enabled: Boolean) = applyDspConfig { it.copy(replayGainEnabled = enabled) }
    fun setReplayGainOption(option: ReplayGainOption) = applyDspConfig { it.copy(replayGainOption = option) }
    fun setReplayGainSource(source: ReplayGainSource) = applyDspConfig { it.copy(replayGainSource = source) }
    fun setReplayGainPreamp(db: Float) = applyDspConfig { it.copy(replayGainPreamp = db) }
    fun setDvcEnabled(enabled: Boolean) = applyDspConfig { it.copy(dvcEnabled = enabled) }
    fun setDvcMode(mode: DvcMode) = applyDspConfig { it.copy(dvcMode = mode) }
    fun setDvcLevel(level: Float) = applyDspConfig { it.copy(dvcLevel = level.coerceIn(0f, 1f), dvcEnabled = true) }
    fun setLimiterEnabled(enabled: Boolean) = applyDspConfig { it.copy(limiterEnabled = enabled) }

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

    fun setEqBandFrequency(index: Int, frequencyHz: Float) {
        applyEqBand(index) { it.copy(frequencyHz = frequencyHz.coerceIn(20f, 20_000f)) }
    }

    fun setEqBandGain(index: Int, gainDb: Float) {
        applyEqBand(index) { it.copy(gainDb = gainDb.coerceIn(-12f, 12f)) }
    }

    fun setAllEqGains(gains: List<Float>) {
        applyDspConfig { config ->
            val defaultBands = defaultEqBands()
            config.copy(
                eqEnabled = true,
                autoEqEnabled = false,
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

    private fun applyEqBand(index: Int, transform: (ParametricEqBand) -> ParametricEqBand) {
        applyDspConfig { config ->
            config.copy(
                eqEnabled = true,
                autoEqEnabled = false, // Disable AutoEQ flag when manually overriding
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
        if (query.isBlank()) {
            clearAutoEqResults()
            return
        }

        viewModelScope.launch {
            // 1. Show local results immediately
            val localResults = withContext(Dispatchers.Default) {
                autoEqRepository.searchProfiles(query)
            }
            _uiState.update { state ->
                state.copy(dsp = state.dsp.copy(autoEqResults = localResults))
            }

            // 2. Online search
            _uiState.update { state -> state.copy(dsp = state.dsp.copy(autoEqLoading = true)) }
            try {
                val onlineResults = autoEqApiService.searchProfiles(query)
                val filteredOnline = onlineResults.filter { online ->
                    localResults.none { it.name.equals(online.name, ignoreCase = true) }
                }
                
                _uiState.update { state ->
                    state.copy(dsp = state.dsp.copy(
                        autoEqResults = state.dsp.autoEqResults + filteredOnline,
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
                        autoEqEnabled = false,
                        autoEqProfile = profile,
                        eqEnabled = true,
                        eqBands = config.eqBands.map { localBand ->
                            val closest = profile.bands.minByOrNull {
                                kotlin.math.abs(it.frequencyHz - localBand.frequencyHz)
                            }
                            if (closest != null && kotlin.math.abs(closest.frequencyHz - localBand.frequencyHz) < localBand.frequencyHz * 0.4f) {
                                localBand.copy(gainDb = closest.gainDb, q = closest.q, enabled = true)
                            } else {
                                localBand.copy(gainDb = 0f, q = 1.0f, enabled = true)
                            }
                        },
                        preampDb = profile.preampDb
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
                        add(SavedEqPreset(name, bands))
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
        val currentSongs = songs.value
        if (currentSongs.isNotEmpty()) {
            service?.setShuffleMode(true)
            val shuffled = currentSongs.shuffled()
            service?.playList(shuffled, 0)
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
        // Implementation for syncing telegram channel
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

    private companion object {
        const val FRAME_TICK_MS = 16L
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
        folder = folder,
        dateAdded = dateAdded,
        replayGainTrackDb = replayGainTrackDb,
        replayGainAlbumDb = replayGainAlbumDb,
        replayGainTrackPeak = replayGainTrackPeak,
        replayGainAlbumPeak = replayGainAlbumPeak,
        source = source.name,
        driveFileId = driveFileId,
        driveAccountEmail = driveAccountEmail
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
