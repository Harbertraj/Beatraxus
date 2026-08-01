package com.beatraxus.app.repository

import android.app.Application
import com.beatraxus.app.model.Song
import com.beatraxus.app.service.AudioPlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LibraryScanner(
    private val application: Application,
    private val musicRepository: MusicRepository,
    private val songDao: com.beatraxus.app.model.SongDao,
    private val scope: CoroutineScope
) {
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress = _scanProgress.asStateFlow()

    private val _scanCount = MutableStateFlow(0)
    val scanCount = _scanCount.asStateFlow()

    private val _albumCount = MutableStateFlow(0)
    val albumCount = _albumCount.asStateFlow()

    private val _artistCount = MutableStateFlow(0)
    val artistCount = _artistCount.asStateFlow()

    private val _isFullScanning = MutableStateFlow(false)
    val isFullScanning = _isFullScanning.asStateFlow()

    private val _isCloudScanning = MutableStateFlow(false)
    val isCloudScanning = _isCloudScanning.asStateFlow()

    private val _enrichmentStatus = MutableStateFlow<String?>(null)
    val enrichmentStatus = _enrichmentStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private var scanJob: Job? = null

    fun cancelScan(service: AudioPlaybackService?) {
        scanJob?.cancel()
        scanJob = null
        service?.cancelLibraryScan()
        _isScanning.value = false
        _isFullScanning.value = false
        _isCloudScanning.value = false
        _scanProgress.value = 0f
        service?.updateScanningProgress(1.0f, _scanCount.value, true)
    }

    fun quickScan(
        service: AudioPlaybackService?,
        currentSongs: List<Song>,
        onSongsUpdated: (List<Song>, List<Song>, List<String>, String, Boolean) -> Unit
    ) {
        if (_isScanning.value || service == null) return
        _isScanning.value = true
        _errorMessage.value = null

        service.runLocalScan(
            fullScan = false,
            currentSongs = currentSongs,
            onProgress = { progress, count, albums, artists ->
                _scanProgress.value = progress
                _scanCount.value = count
                _albumCount.value = albums
                _artistCount.value = artists
            },
            onComplete = { results, newSongs, removedLocalIds, message, hasChanges ->
                _isScanning.value = false
                _scanProgress.value = 1.0f
                _errorMessage.value = message
                onSongsUpdated(results, newSongs, removedLocalIds, message, hasChanges)
            },
            onError = { error ->
                _isScanning.value = false
                _errorMessage.value = "Scan failed: $error"
            }
        )
    }

    fun startFullScan(
        service: AudioPlaybackService?,
        currentSongs: List<Song>,
        onSongsUpdated: (List<Song>, String) -> Unit
    ) {
        if (service == null) return
        _isScanning.value = true
        _isFullScanning.value = true
        _scanProgress.value = 0f
        _scanCount.value = 0
        _errorMessage.value = null

        service.runLocalScan(
            fullScan = true,
            currentSongs = currentSongs,
            onProgress = { progress, count, albums, artists ->
                _scanProgress.value = progress
                _scanCount.value = count
                _albumCount.value = albums
                _artistCount.value = artists
            },
            onComplete = { results, _, _, message, _ ->
                _isScanning.value = false
                _isFullScanning.value = false
                _scanProgress.value = 1.0f
                _errorMessage.value = message
                onSongsUpdated(results, message)
            },
            onError = { error ->
                _isScanning.value = false
                _isFullScanning.value = false
                _errorMessage.value = "Full scan failed: $error"
            }
        )
    }

    fun startFolderScan(
        service: AudioPlaybackService?,
        folders: List<String>,
        onComplete: (List<Song>, String) -> Unit
    ) {
        if (service == null || folders.isEmpty()) return
        _isScanning.value = true
        _scanProgress.value = 0f
        _scanCount.value = 0
        _errorMessage.value = null

        service.runFolderScan(
            folders = folders,
            onProgress = { progress, count, albums, artists ->
                _scanProgress.value = progress
                _scanCount.value = count
                _albumCount.value = albums
                _artistCount.value = artists
            },
            onComplete = { results, message ->
                _isScanning.value = false
                _scanProgress.value = 1.0f
                _errorMessage.value = message
                onComplete(results, message)
            },
            onError = { error ->
                _isScanning.value = false
                _errorMessage.value = "Scan failed: $error"
            }
        )
    }
}
