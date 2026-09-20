package com.beatraxus.app.subtitles.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatraxus.app.model.Video
import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.data.SubtitleAuthManager
import com.beatraxus.app.subtitles.data.SubtitleCache
import com.beatraxus.app.subtitles.data.SubtitleCredentialsStore
import com.beatraxus.app.subtitles.data.SubtitleFileImporter
import com.beatraxus.app.subtitles.domain.EncodingNormalizer
import com.beatraxus.app.subtitles.domain.FilenameParser
import com.beatraxus.app.subtitles.domain.MediaKey
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleException
import com.beatraxus.app.subtitles.domain.SubtitleMatchScorer
import com.beatraxus.app.subtitles.domain.SubtitlePreferences
import com.beatraxus.app.subtitles.domain.SubtitleQueryBuilder
import com.beatraxus.app.subtitles.domain.SubtitleRepository
import com.beatraxus.app.subtitles.domain.SubtitleResult
import com.beatraxus.app.subtitles.domain.SubtitleSearchQuery
import com.beatraxus.app.subtitles.domain.SubtitleValidator
import com.beatraxus.app.subtitles.domain.VideoHashCalculator
import com.beatraxus.app.subtitles.player.SubtitlePlayerController
import com.beatraxus.app.subtitles.util.SubtitleErrorMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class SubtitleViewModel(
    application: Application,
    private val repository: SubtitleRepository,
    private val authManager: SubtitleAuthManager,
    val subtitleController: SubtitlePlayerController,
    private val credentialsStore: SubtitleCredentialsStore,
    private val subtitleCache: SubtitleCache = SubtitleCache(application)
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
    private val fileImporter = SubtitleFileImporter(application, subtitleCache)

    private val _uiState = MutableStateFlow(SubtitleUiState())
    val uiState: StateFlow<SubtitleUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SubtitleUiEvent>()
    val events: SharedFlow<SubtitleUiEvent> = _events.asSharedFlow()

    private var currentVideo: Video? = null
    private var currentMediaKey: String = ""
    private var searchJob: Job? = null
    private val autoSearchedVideoIds = mutableSetOf<String>()

    init {
        loadSettings()
        loadLanguages()
        observeControllerState()
    }

    private fun loadSettings() {
        val enabled = prefs.getBoolean("video_subtitle_enabled", true)
        val prefLangsString = prefs.getString("video_subtitle_preferred_languages", "en") ?: "en"
        val prefLangs = prefLangsString.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val autoSearchModeName = prefs.getString("video_subtitle_auto_search_mode", AutoSearchMode.OFF.name)
        val autoSearchMode = AutoSearchMode.entries.find { it.name == autoSearchModeName } ?: AutoSearchMode.OFF

        val isSignedIn = credentialsStore.isSignedIn()
        val username = credentialsStore.getUsername()
        val remaining = repository.getLastKnownRemainingDownloads().takeIf { it >= 0 }
        val resetTime = repository.getLastKnownResetTime()

        _uiState.update {
            it.copy(
                enabled = enabled,
                selectedLanguages = prefLangs,
                autoSearchMode = autoSearchMode,
                isSignedIn = isSignedIn,
                username = username,
                downloadsRemaining = remaining,
                downloadsResetTime = resetTime
            )
        }
    }

    private fun loadLanguages() {
        viewModelScope.launch {
            val result = repository.getLanguages()
            if (result.isSuccess) {
                val apiLangs = result.getOrDefault(emptyList())
                _uiState.update { it.copy(languages = apiLangs) }
            }
        }
    }

    private fun observeControllerState() {
        viewModelScope.launch {
            subtitleController.activeExternalSubtitleName.collect { name ->
                _uiState.update {
                    it.copy(
                        selectedSubtitleName = name,
                        selectedSubtitleType = if (name != null) SelectedSubtitleType.EXTERNAL else it.selectedSubtitleType
                    )
                }
            }
        }
        viewModelScope.launch {
            subtitleController.isDelaySupported.collect { supported ->
                _uiState.update { it.copy(delaySupported = supported) }
            }
        }
        viewModelScope.launch {
            subtitleController.currentDelayMs.collect { delay ->
                _uiState.update { it.copy(delayMs = delay) }
            }
        }
    }

    fun onVideoChanged(video: Video) {
        currentVideo = video
        viewModelScope.launch(Dispatchers.IO) {
            val hash = VideoHashCalculator.calculateHash(video.id, video.uri, getApplication<Application>().contentResolver)
            val mediaKey = MediaKey.of(video, hash)
            currentMediaKey = mediaKey

            val cachedList = subtitleCache.getCachedSubtitles(mediaKey)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(cachedSubtitles = cachedList, results = emptyList(), bestMatchId = null, error = null, errorMessage = null) }
            }

            val activeCached = cachedList.firstOrNull()
            if (activeCached != null && _uiState.value.enabled) {
                val file = File(activeCached.localPath)
                if (file.exists()) {
                    subtitleController.attachExternalSubtitle(
                        videoId = video.id,
                        file = file,
                        language = activeCached.language,
                        label = activeCached.releaseName ?: "External Subtitle",
                        subtitleId = activeCached.subtitleId
                    )
                    _uiState.update {
                        it.copy(
                            selectedSubtitleId = activeCached.subtitleId,
                            selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                            selectedSubtitleName = activeCached.releaseName ?: "External Subtitle"
                        )
                    }
                    return@launch
                }
            }

            // Auto-search logic
            val autoMode = _uiState.value.autoSearchMode
            if (autoMode != AutoSearchMode.OFF && !autoSearchedVideoIds.contains(video.id)) {
                autoSearchedVideoIds.add(video.id)
                searchOnline()
            }
        }
    }

    fun searchOnline(manualQuery: String? = null) {
        val video = currentVideo ?: return
        searchJob?.cancel()

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isSearching = true, error = null, errorMessage = null) }
            }

            val hash = VideoHashCalculator.calculateHash(video.id, video.uri, getApplication<Application>().contentResolver)
            val preferredLangs = _uiState.value.selectedLanguages

            val query = if (!manualQuery.isNullOrBlank()) {
                SubtitleSearchQuery(
                    query = manualQuery,
                    movieHash = hash,
                    languages = preferredLangs
                )
            } else {
                SubtitleQueryBuilder.build(video, hash, preferredLangs)
            }

            val res = repository.searchSubtitles(query)

            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val rawResults = res.getOrDefault(emptyList())
                    val parsedMedia = FilenameParser.parse(video.displayName.ifBlank { video.title })
                    val scoredList = SubtitleMatchScorer.scoreAndSort(
                        rawResults,
                        query,
                        parsedMedia,
                        SubtitlePreferences(preferredLanguages = preferredLangs)
                    )
                    val bestId = scoredList.firstOrNull { it.isBestMatch }?.subtitle?.id

                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            results = scoredList,
                            bestMatchId = bestId
                        )
                    }
                } else {
                    val ex = res.exceptionOrNull()
                    val error = (ex as? SubtitleException)?.error ?: SubtitleError.Unknown(ex)
                    val message = SubtitleErrorMapper.toUserMessage(error)

                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            error = error,
                            errorMessage = message
                        )
                    }
                    _events.emit(SubtitleUiEvent.Error(message))
                }
            }
        }
    }

    fun downloadAndApply(result: SubtitleResult) {
        val video = currentVideo ?: return
        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isDownloading = result.id) }
            }

            // Check if already cached
            val cachedList = subtitleCache.getCachedSubtitles(mediaKey)
            val existing = cachedList.firstOrNull { it.subtitleId == result.id || it.subtitleId == result.fileId.toString() }

            if (existing != null) {
                val file = File(existing.localPath)
                if (file.exists()) {
                    subtitleController.attachExternalSubtitle(
                        videoId = video.id,
                        file = file,
                        language = existing.language,
                        label = result.fileName,
                        subtitleId = existing.subtitleId
                    )
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isDownloading = null,
                                selectedSubtitleId = existing.subtitleId,
                                selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                                selectedSubtitleName = result.fileName
                            )
                        }
                        _events.emit(SubtitleUiEvent.DownloadSuccess(result.fileName))
                    }
                    return@launch
                }
            }

            val requestRes = repository.requestDownload(result.fileId)
            if (requestRes.isFailure) {
                val ex = requestRes.exceptionOrNull()
                val error = (ex as? SubtitleException)?.error ?: SubtitleError.Unknown(ex)
                val msg = SubtitleErrorMapper.toUserMessage(error)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDownloading = null, error = error, errorMessage = msg) }
                    when (error) {
                        is SubtitleError.NotSignedIn, is SubtitleError.InvalidCredentials -> _events.emit(SubtitleUiEvent.NeedSignIn)
                        is SubtitleError.ApiQuotaExceeded -> _events.emit(SubtitleUiEvent.SignInForHigherLimit(error.resetTime))
                        else -> _events.emit(SubtitleUiEvent.Error(msg))
                    }
                }
                return@launch
            }

            val downloadInfo = requestRes.getOrThrow()
            val tempFile = File.createTempFile("sub_dl_", ".tmp", getApplication<Application>().cacheDir)
            tempFile.deleteOnExit()

            val dlFileRes = repository.downloadToFile(downloadInfo.link, tempFile)
            if (dlFileRes.isFailure) {
                withContext(Dispatchers.Main) {
                    val msg = SubtitleErrorMapper.toUserMessage(SubtitleError.DownloadFailed("File write failed"))
                    _uiState.update { it.copy(isDownloading = null, error = SubtitleError.DownloadFailed("File write failed"), errorMessage = msg) }
                    _events.emit(SubtitleUiEvent.Error(msg))
                }
                return@launch
            }

            val bytes = tempFile.readBytes()
            val normalized = EncodingNormalizer.normalizeToUtf8(bytes, result.language)
            val validRes = SubtitleValidator.validate(normalized)
            if (validRes.isFailure) {
                withContext(Dispatchers.Main) {
                    val msg = SubtitleErrorMapper.toUserMessage(SubtitleError.InvalidSubtitle)
                    _uiState.update { it.copy(isDownloading = null, error = SubtitleError.InvalidSubtitle, errorMessage = msg) }
                    _events.emit(SubtitleUiEvent.Error(msg))
                }
                return@launch
            }

            val subId = result.id.ifBlank { result.fileId.toString() }
            val cachedSub = CachedSubtitle(
                subtitleId = subId,
                language = result.language,
                format = "SRT",
                releaseName = result.fileName,
                localPath = ""
            )

            val savedFile = subtitleCache.saveSubtitle(mediaKey, cachedSub, normalized)
            val updatedCachedList = subtitleCache.getCachedSubtitles(mediaKey)

            subtitleController.attachExternalSubtitle(
                videoId = video.id,
                file = savedFile,
                language = result.language,
                label = result.fileName,
                subtitleId = subId
            )

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isDownloading = null,
                        cachedSubtitles = updatedCachedList,
                        selectedSubtitleId = subId,
                        selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                        selectedSubtitleName = result.fileName,
                        downloadsRemaining = downloadInfo.remaining,
                        downloadsResetTime = downloadInfo.resetTime
                    )
                }
                _events.emit(SubtitleUiEvent.DownloadSuccess(result.fileName))
            }
        }
    }

    fun importLocalSubtitle(uri: Uri) {
        val video = currentVideo ?: return
        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }

        viewModelScope.launch {
            val res = fileImporter.importSubtitleFromUri(mediaKey, uri, _uiState.value.selectedLanguages.firstOrNull() ?: "en")
            if (res.isSuccess) {
                val cached = res.getOrThrow()
                val file = File(cached.localPath)
                subtitleController.attachExternalSubtitle(
                    videoId = video.id,
                    file = file,
                    language = cached.language,
                    label = cached.releaseName ?: "Local Subtitle",
                    subtitleId = cached.subtitleId
                )
                val updatedList = subtitleCache.getCachedSubtitles(mediaKey)
                _uiState.update {
                    it.copy(
                        cachedSubtitles = updatedList,
                        selectedSubtitleId = cached.subtitleId,
                        selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                        selectedSubtitleName = cached.releaseName ?: "Local Subtitle"
                    )
                }
                _events.emit(SubtitleUiEvent.DownloadSuccess(cached.releaseName ?: "Local Subtitle"))
            } else {
                val ex = res.exceptionOrNull()
                val error = (ex as? SubtitleException)?.error ?: SubtitleError.Unknown(ex)
                val msg = SubtitleErrorMapper.toUserMessage(error)
                _uiState.update { it.copy(error = error, errorMessage = msg) }
                _events.emit(SubtitleUiEvent.Error(msg))
            }
        }
    }

    fun selectEmbeddedTrack(groupIndex: Int) {
        subtitleController.selectEmbeddedTrack(groupIndex)
        _uiState.update {
            it.copy(
                selectedSubtitleId = "embedded_$groupIndex",
                selectedSubtitleType = SelectedSubtitleType.EMBEDDED,
                selectedSubtitleName = "Embedded Track ${groupIndex + 1}"
            )
        }
    }

    fun disableSubtitles() {
        subtitleController.disableSubtitles()
        prefs.edit().putBoolean("video_subtitle_enabled", false).apply()
        _uiState.update {
            it.copy(
                enabled = false,
                selectedSubtitleId = "none",
                selectedSubtitleType = SelectedSubtitleType.NONE,
                selectedSubtitleName = null
            )
        }
    }

    fun enableSubtitles() {
        prefs.edit().putBoolean("video_subtitle_enabled", true).apply()
        _uiState.update { it.copy(enabled = true) }
    }

    fun setSubtitleDelay(delayMs: Long) {
        val video = currentVideo ?: return
        val activeSubId = _uiState.value.selectedSubtitleId ?: return
        val mediaKey = currentMediaKey

        subtitleController.setSubtitleDelay(video.id, delayMs) {
            val cachedList: List<CachedSubtitle> = runBlocking { subtitleCache.getCachedSubtitles(mediaKey) }
            val item: CachedSubtitle? = cachedList.firstOrNull { it.subtitleId == activeSubId } ?: cachedList.firstOrNull()
            item?.let { File(it.localPath) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            subtitleCache.updateDelay(mediaKey, activeSubId, delayMs)
        }
    }

    fun resetSubtitleDelay() {
        setSubtitleDelay(0L)
    }

    fun signIn(username: String, password: String) {
        viewModelScope.launch {
            val res = authManager.login(username, password)
            if (res.isSuccess) {
                _uiState.update { it.copy(isSignedIn = true, username = username) }
                _events.emit(SubtitleUiEvent.ShowToast("Signed in as $username"))
            } else {
                val msg = SubtitleErrorMapper.toUserMessage(SubtitleError.InvalidCredentials)
                _events.emit(SubtitleUiEvent.Error(msg))
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.logout()
            _uiState.update { it.copy(isSignedIn = false, username = null) }
            _events.emit(SubtitleUiEvent.ShowToast("Signed out"))
        }
    }

    fun setPreferredLanguages(languages: List<String>) {
        val str = languages.joinToString(",")
        prefs.edit().putString("video_subtitle_preferred_languages", str).apply()
        _uiState.update { it.copy(selectedLanguages = languages) }
    }

    fun setAutoSearchMode(mode: AutoSearchMode) {
        prefs.edit().putString("video_subtitle_auto_search_mode", mode.name).apply()
        _uiState.update { it.copy(autoSearchMode = mode) }
    }

    fun clearSubtitleCache() {
        viewModelScope.launch(Dispatchers.IO) {
            subtitleCache.clearCache()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(cachedSubtitles = emptyList(), selectedSubtitleId = null, selectedSubtitleType = SelectedSubtitleType.NONE, selectedSubtitleName = null) }
                _events.emit(SubtitleUiEvent.ShowToast("Subtitle cache cleared"))
            }
        }
    }
}

class SubtitleViewModelFactory(
    private val application: Application,
    private val repository: SubtitleRepository,
    private val authManager: SubtitleAuthManager,
    private val subtitleController: SubtitlePlayerController,
    private val credentialsStore: SubtitleCredentialsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SubtitleViewModel(application, repository, authManager, subtitleController, credentialsStore) as T
    }
}
