package com.beatflowy.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.work.*
import com.beatflowy.app.model.AlbumItem
import com.beatflowy.app.model.DownloadItem
import com.beatflowy.app.model.DownloadQuality
import com.beatflowy.app.model.DownloadSettings
import com.beatflowy.app.model.DownloadStatus
import com.beatflowy.app.model.FilenameTemplate
import com.beatflowy.app.repository.DownloadWorker
import com.beatflowy.app.repository.QobuzRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class QobuzDownloadUiState(
    val searchQuery: String = "",
    val searchResults: List<DownloadItem> = emptyList(),
    val albumResults: List<AlbumItem> = emptyList(),
    val downloadQueue: List<DownloadItem> = emptyList(),
    val selectedQuality: DownloadQuality = DownloadQuality.Lossless,
    val isSearching: Boolean = false,
    val errorMessage: String? = null,
    val downloadSettings: DownloadSettings = DownloadSettings(),
    val expandedAlbumId: String? = null,
    val albumTracksMap: Map<String, List<DownloadItem>> = emptyMap(),
    val isLoadingAlbumTracks: Boolean = false,
    val snackbarMessage: String? = null,
    val showCaptchaDialog: Boolean = false,
    val captchaUrl: String? = null,
    val pendingRetryItem: DownloadItem? = null,
    val isAutoVerifying: Boolean = false,
    val autoVerificationQuery: String? = null,
    val verificationStep: String? = null
)

class QobuzDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
    val repository = QobuzRepository(application)
    private val _uiState = MutableStateFlow(QobuzDownloadUiState())
    val uiState: StateFlow<QobuzDownloadUiState> = _uiState.asStateFlow()
    private val workManager = WorkManager.getInstance(application)
    private val activeWorkIds = mutableMapOf<String, UUID>()

    init {
        val savedLocation = prefs.getString("download_location", null)
        if (savedLocation != null) {
            _uiState.update { it.copy(downloadSettings = it.downloadSettings.copy(downloadLocation = savedLocation)) }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedQuality(quality: DownloadQuality) {
        _uiState.update { it.copy(selectedQuality = quality) }
    }

    fun addToQueue(item: DownloadItem) {
        _uiState.update { state ->
            if (state.downloadQueue.any { it.id == item.id }) return@update state
            state.copy(downloadQueue = state.downloadQueue + item)
        }
    }

    fun addAllToQueue(tracks: List<DownloadItem>) {
        _uiState.update { state ->
            val existingIds = state.downloadQueue.map { it.id }.toSet()
            val newTracks = tracks.filter { it.id !in existingIds }
            if (newTracks.isEmpty()) {
                state.copy(snackbarMessage = "No new tracks to add")
            } else {
                state.copy(
                    downloadQueue = state.downloadQueue + newTracks,
                    snackbarMessage = "${newTracks.size} tracks added to queue"
                )
            }
        }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun removeFromQueue(id: String) {
        activeWorkIds[id]?.let { workManager.cancelWorkById(it) }
        activeWorkIds.remove(id)
        _uiState.update { state ->
            state.copy(downloadQueue = state.downloadQueue.filterNot { it.id == id })
        }
    }

    fun clearQueue() {
        _uiState.update { it.copy(downloadQueue = emptyList()) }
    }

    fun updateDownloadSettings(settings: DownloadSettings) {
        _uiState.update { it.copy(downloadSettings = settings) }
    }

    fun setDownloadLocation(uri: String?) {
        _uiState.update { state ->
            state.copy(downloadSettings = state.downloadSettings.copy(downloadLocation = uri))
        }
        prefs.edit().putString("download_location", uri).apply()
    }

    fun setDefaultQuality(quality: DownloadQuality) {
        _uiState.update { state ->
            state.copy(downloadSettings = state.downloadSettings.copy(defaultQuality = quality))
        }
    }

    fun setFilenameTemplate(template: FilenameTemplate) {
        _uiState.update { state ->
            state.copy(downloadSettings = state.downloadSettings.copy(filenameTemplate = template))
        }
    }

    fun setCreateAlbumSubfolders(value: Boolean) {
        _uiState.update { state ->
            state.copy(downloadSettings = state.downloadSettings.copy(createAlbumSubfolders = value))
        }
    }

    fun setOverwriteExisting(value: Boolean) {
        _uiState.update { state ->
            state.copy(downloadSettings = state.downloadSettings.copy(overwriteExisting = value))
        }
    }

    fun searchQobuz(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null, expandedAlbumId = null) }
            try {
                val results = repository.search(query.trim())
                _uiState.update {
                    it.copy(
                        searchResults = results.tracks,
                        albumResults = results.albums,
                        isSearching = false,
                        errorMessage = null,
                        isAutoVerifying = false
                    )
                }
            } catch (e: Exception) {
                val isCaptcha = e.message?.contains("Captcha required") == true
                _uiState.update {
                    it.copy(
                        isSearching = false, // Stop search spinner, transition to verification
                        searchResults = emptyList(),
                        albumResults = emptyList(),
                        errorMessage = if (isCaptcha) null else (e.message ?: "Search failed."),
                        captchaUrl = if (isCaptcha) "https://qobuz.squid.wtf/" else null,
                        isAutoVerifying = isCaptcha,
                        autoVerificationQuery = if (isCaptcha) query else null,
                        verificationStep = if (isCaptcha) "Security check detected. Verifying…" else null
                    )
                }
            }
        }
    }

    fun toggleAlbumExpand(albumId: String) {
        val currentExpanded = _uiState.value.expandedAlbumId
        if (currentExpanded == albumId) {
            _uiState.update { it.copy(expandedAlbumId = null) }
        } else {
            _uiState.update { it.copy(expandedAlbumId = albumId) }
            if (!_uiState.value.albumTracksMap.containsKey(albumId)) {
                loadAlbumTracks(albumId)
            }
        }
    }

    private fun loadAlbumTracks(albumId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAlbumTracks = true) }
            try {
                val tracks = repository.getAlbumTracks(albumId)
                _uiState.update { state ->
                    state.copy(
                        albumTracksMap = state.albumTracksMap + (albumId to tracks),
                        isLoadingAlbumTracks = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingAlbumTracks = false) }
            }
        }
    }

    fun startDownload(item: DownloadItem, onDownloadFinished: (Uri) -> Unit = {}) {
        val settings = _uiState.value.downloadSettings
        val downloadLocation = settings.downloadLocation ?: run {
            _uiState.update { it.copy(errorMessage = "Please select a download location first") }
            return
        }

        val inputData = workDataOf(
            "track_id" to item.id,
            "title" to item.title,
            "artist" to item.artist,
            "album" to item.album,
            "quality_code" to item.quality.qualityCode,
            "destination_uri" to downloadLocation,
            "filename_template" to settings.filenameTemplate.name,
            "create_subfolders" to settings.createAlbumSubfolders,
            "overwrite" to settings.overwriteExisting
        )

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag(item.id)
            .build()

        activeWorkIds[item.id] = downloadRequest.id
        workManager.enqueueUniqueWork(
            "download_${item.id}",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(downloadRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt("progress", 0)
                            updateItemStatus(item.id, DownloadStatus.DOWNLOADING, progress)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            updateItemStatus(item.id, DownloadStatus.DONE, 100)
                            val uriString = workInfo.outputData.getString("file_uri")
                            if (uriString != null) {
                                onDownloadFinished(Uri.parse(uriString))
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val error = workInfo.outputData.getString("error") ?: "Unknown error"
                            val isCaptcha = workInfo.outputData.getBoolean("is_captcha", false)
                            
                            if (isCaptcha) {
                                _uiState.update { it.copy(
                                    captchaUrl = "https://qobuz.squid.wtf/",
                                    isAutoVerifying = true,
                                    autoVerificationQuery = "${item.artist} ${item.title}",
                                    verificationStep = "Security check detected. Verifying…",
                                    pendingRetryItem = item
                                ) }
                            } else {
                                _uiState.update { it.copy(errorMessage = error) }
                            }
                            updateItemStatus(item.id, DownloadStatus.FAILED, 0)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun updateVerificationStep(step: String) {
        _uiState.update { it.copy(verificationStep = step) }
    }

    fun onVerificationSuccess() {
        val pendingItem = _uiState.value.pendingRetryItem
        val pendingQuery = _uiState.value.searchQuery
        
        _uiState.update { it.copy(
            isAutoVerifying = false,
            autoVerificationQuery = null,
            verificationStep = "Verification successful!",
            showCaptchaDialog = false,
            captchaUrl = null,
            pendingRetryItem = null
        ) }

        if (pendingItem != null) {
            startDownload(pendingItem)
        } else if (pendingQuery.isNotBlank()) {
            searchQobuz(pendingQuery)
        }
    }

    fun onVerificationFailed(error: String) {
        _uiState.update { it.copy(
            isAutoVerifying = false,
            verificationStep = null,
            errorMessage = "Auto-verification failed. Please solve manually.",
            showCaptchaDialog = true
        ) }
    }

    fun dismissCaptcha() {
        _uiState.update { it.copy(
            showCaptchaDialog = false, 
            captchaUrl = null, 
            pendingRetryItem = null,
            isAutoVerifying = false,
            autoVerificationQuery = null,
            verificationStep = null
        ) }
        
        if (_uiState.value.errorMessage?.contains("Captcha required") == true) {
             _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun updateItemStatus(id: String, status: DownloadStatus, progress: Int) {
        _uiState.update { state ->
            val updatedQueue = state.downloadQueue.map {
                if (it.id == id) it.copy(status = status, progressPercent = progress) else it
            }
            val updatedResults = state.searchResults.map {
                if (it.id == id) it.copy(status = status, progressPercent = progress) else it
            }
            val updatedAlbumTracksMap = state.albumTracksMap.mapValues { (_, tracks) ->
                tracks.map { if (it.id == id) it.copy(status = status, progressPercent = progress) else it }
            }
            state.copy(
                downloadQueue = updatedQueue,
                searchResults = updatedResults,
                albumTracksMap = updatedAlbumTracksMap
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return QobuzDownloadViewModel(application) as T
            }
        }
    }
}
