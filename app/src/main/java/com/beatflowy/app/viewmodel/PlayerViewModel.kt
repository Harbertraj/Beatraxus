package com.beatflowy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatflowy.app.model.LibraryView
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SortType
import com.beatflowy.app.model.ViewMode
import com.beatflowy.app.repository.MusicRepository
import com.beatflowy.app.service.AudioPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val lyricsRepository = com.beatflowy.app.repository.LyricsRepository()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _songs.asStateFlow()

    val albums = allSongs.map { songs ->
        songs.groupBy { it.album }
            .map { (name, list) -> Triple(name, list.first().artist, list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists = allSongs.map { songs ->
        songs.groupBy { it.artist }
            .map { (name, list) -> Triple(name, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders = allSongs.map { songs ->
        songs.groupBy { it.folder }
            .map { (path, list) -> Triple(path, path.substringAfterLast("/"), list.first().albumArtUri) }
            .sortedBy { it.second.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val years = allSongs.map { songs ->
        songs.groupBy { it.year }
            .map { (year, list) -> Triple(year.toString(), "${list.size} songs", list.first().albumArtUri) }
            .sortedByDescending { it.first }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genres = allSongs.map { songs ->
        songs.groupBy { it.genre }
            .map { (genre, list) -> Triple(genre, "${list.size} songs", list.first().albumArtUri) }
            .sortedBy { it.first.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _lyrics = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val lyrics: StateFlow<List<Pair<Long, String>>> = _lyrics.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<String>>(emptyList())

    val songs: StateFlow<List<Song>> = combine(_songs, _uiState, _recentlyPlayed) { all, state, recentIds ->
        var filtered = when (state.currentView) {
            LibraryView.ALL_SONGS -> all
            LibraryView.ALBUMS -> emptyList() // Handled by UI showing cards
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
            LibraryView.FOLDER_DETAIL -> all.filter { it.folder == state.selectedItemName }
            LibraryView.YEAR_DETAIL -> all.filter { it.year.toString() == state.selectedItemName }
            LibraryView.GENRE_DETAIL -> all.filter { it.genre == state.selectedItemName }
        }
        
        if (state.searchQuery.isNotEmpty()) {
            filtered = filtered.filter { 
                it.title.contains(state.searchQuery, ignoreCase = true) || 
                it.artist.contains(state.searchQuery, ignoreCase = true) ||
                it.album.contains(state.searchQuery, ignoreCase = true)
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

    private var service: AudioPlaybackService? = null
    private var progressJob: Job? = null

    fun attachService(svc: AudioPlaybackService) {
        service = svc
        viewModelScope.launch {
            svc.audioStateFlow.collect { audioState ->
                _uiState.update { it.copy(
                    inputSampleRate  = audioState.inputSampleRateHz,
                    outputSampleRate = audioState.outputSampleRateHz,
                    outputDevice     = audioState.outputDevice
                )}
            }
        }
        viewModelScope.launch {
            svc.playbackStateFlow.collect { pbState ->
                val prevSongId = _uiState.value.currentSong?.id
                _uiState.update { it.copy(
                    isPlaying   = pbState.isPlaying,
                    isBuffering = pbState.isBuffering,
                    currentSong = pbState.currentSong,
                    shuffleMode = pbState.shuffleMode,
                    repeatMode  = pbState.repeatMode
                )}

                if (pbState.currentSong?.id != prevSongId) {
                    pbState.currentSong?.let { song ->
                        updateRecentlyPlayed(song.id)
                        viewModelScope.launch {
                            _lyrics.value = lyricsRepository.fetchLyrics(song)
                        }
                    }
                }

                if (pbState.isPlaying) startProgressPolling() else stopProgressPolling()
            }
        }
    }

    fun loadLibrary() {
        val prefs = getApplication<Application>().getSharedPreferences("beatraxus", Application.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run", true)

        viewModelScope.launch {
            // Prevent multiple simultaneous scans
            if (_uiState.value.isScanning) return@launch

            // If not first run and we already have songs, we can skip
            if (!isFirstRun && _songs.value.isNotEmpty()) return@launch

            // Only show scanning UI (popup) on first run
            if (isFirstRun) {
                _uiState.update { it.copy(isScanning = true, scanProgress = 0f, scanCount = 0) }
            } else {
                _uiState.update { it.copy(isLoadingLibrary = true) }
            }

            try {
                // Perform full scan on first run to get accurate metadata, 
                // otherwise use quick scan in background.
                val results = repository.scanAudioFiles(fullScan = isFirstRun) { count, albums, artists, progress ->
                    if (isFirstRun) {
                        _uiState.update { it.copy(
                            scanCount = count,
                            albumCount = albums,
                            artistCount = artists,
                            scanProgress = progress
                        )}
                        service?.updateScanningProgress(progress, count, false)
                    }
                }
                
                _songs.value = results
                
                if (isFirstRun && results.isNotEmpty()) {
                    prefs.edit().putBoolean("first_run", false).apply()
                }
                
                if (isFirstRun) {
                    service?.updateScanningProgress(1.0f, results.size, true)
                }
            } catch (e: Exception) {
                if (isFirstRun) {
                    _uiState.update { it.copy(errorMessage = "Failed to load library: ${e.message}") }
                }
            } finally {
                if (isFirstRun) {
                    delay(500)
                    _uiState.update { it.copy(isScanning = false) }
                } else {
                    _uiState.update { it.copy(isLoadingLibrary = false) }
                }
            }
        }
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionDenied = true) }
    }

    fun playSong(song: Song) {
        val list = songs.value // Use the current filtered list
        val index = list.indexOf(song)
        if (index >= 0) {
            service?.playList(list, index)
            _uiState.update { it.copy(currentSong = song, progressMs = 0L) }
        } else {
            service?.playSong(song)
        }
        updateRecentlyPlayed(song.id)
    }

    private fun updateRecentlyPlayed(songId: String) {
        val current = _recentlyPlayed.value.toMutableList()
        current.remove(songId)
        current.add(0, songId)
        if (current.size > 50) current.removeAt(current.size - 1)
        _recentlyPlayed.value = current
    }

    fun setLibraryView(view: LibraryView, itemName: String? = null) {
        _uiState.update { it.copy(currentView = view, selectedItemName = itemName) }
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
            it.copy(selectedSongIds = if (updated.isEmpty() && it.isMultiSelectMode) {
                // Keep multi-select mode even if empty, or auto-exit?
                // Standard behavior is to stay in mode until user cancels or acts.
                updated
            } else updated)
        }
    }

    fun getNextSongPreview(): Song? {
        return service?.getNextSong()
    }

    fun deleteSelectedSongs() {
        val selectedIds = _uiState.value.selectedSongIds
        if (selectedIds.isEmpty()) return
        
        _songs.update { currentSongs ->
            currentSongs.filterNot { it.id in selectedIds }
        }
        setMultiSelectMode(false)
    }

    fun addSelectedToPlaylist() {
        // TODO: Implement playlist logic
        setMultiSelectMode(false)
    }

    fun setShowFullPlayer(show: Boolean) {
        _uiState.update { it.copy(showFullPlayer = show) }
    }

    fun toggleLyrics() {
        _uiState.update { it.copy(showLyrics = !it.showLyrics) }
    }

    fun toggleFavorite(song: Song) {
        val updated = _songs.value.map {
            if (it.id == song.id) it.copy(isFavorite = !it.isFavorite) else it
        }
        _songs.value = updated
    }

    fun togglePlayPause() {
        service?.togglePlayPause()
            ?: _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun skipToNext() {
        service?.next()
    }

    fun skipToPrevious() {
        service?.previous()
    }

    fun seekTo(positionMs: Long) {
        service?.seekTo(positionMs)
        _uiState.update { it.copy(progressMs = positionMs) }
    }

    fun toggleResampling() {
        // Feature removed.
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
            _uiState.update { it.copy(shuffleMode = true) }
            service?.setShuffleMode(true)
            val shuffled = currentSongs.shuffled()
            service?.playList(shuffled, 0)
        }
    }

    fun toggleEqualizer() {
        val enabled = !_uiState.value.equalizerEnabled
        _uiState.update { it.copy(equalizerEnabled = enabled) }
        service?.setEqualizerEnabled(enabled)
    }

    fun setEqBandGain(band: Int, gain: Float) {
        val newGains = _uiState.value.eqGains.copyOf()
        newGains[band] = gain.coerceIn(-12f, 12f)
        _uiState.update { it.copy(eqGains = newGains) }
        service?.setEqBandGain(band, gain)
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val pos = service?.currentPositionMs ?: break
                _uiState.update { it.copy(progressMs = pos) }
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun computeOutputRate(inputHz: Int, resamplingOn: Boolean): Int {
        // Feature removed. Always return input rate.
        return inputHz
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
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
