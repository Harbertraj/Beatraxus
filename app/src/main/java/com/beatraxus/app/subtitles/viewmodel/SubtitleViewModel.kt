package com.beatraxus.app.subtitles.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatraxus.app.BuildConfig
import com.beatraxus.app.model.Video
import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.data.SiblingSubtitleFinder
import com.beatraxus.app.subtitles.data.SubtitleAuthManager
import com.beatraxus.app.subtitles.data.SubtitleCache
import com.beatraxus.app.subtitles.data.SubtitleCredentialsStore
import com.beatraxus.app.subtitles.data.SubtitleFileImporter
import com.beatraxus.app.subtitles.data.SubtitleFontStore
import com.beatraxus.app.subtitles.data.SubtitleSource
import com.beatraxus.app.subtitles.generate.ChatCompletionsTranslator
import com.beatraxus.app.subtitles.generate.FfmpegChunkProvider
import com.beatraxus.app.subtitles.generate.GenerationException
import com.beatraxus.app.subtitles.generate.GenerationService
import com.beatraxus.app.subtitles.generate.OpenAiSpeechTranscriber
import com.beatraxus.app.subtitles.generate.WhisperLanguages
import com.beatraxus.app.subtitles.domain.AutoSubtitlePicker
import com.beatraxus.app.subtitles.domain.EncodingNormalizer
import com.beatraxus.app.subtitles.domain.FilenameParser
import com.beatraxus.app.subtitles.domain.MediaKey
import com.beatraxus.app.subtitles.domain.ScoredSubtitle
import com.beatraxus.app.subtitles.domain.SecondarySubtitlePlacement
import com.beatraxus.app.subtitles.domain.SecondarySubtitlePrefs
import com.beatraxus.app.subtitles.domain.SecondarySubtitleStyle
import com.beatraxus.app.subtitles.domain.SubtitleCue
import com.beatraxus.app.subtitles.domain.SubtitleCueParser
import com.beatraxus.app.subtitles.domain.SubtitleCueTimeline
import com.beatraxus.app.subtitles.domain.SubtitleTapSync
import com.beatraxus.app.subtitles.domain.SubtitleEncodingPrefs
import com.beatraxus.app.subtitles.domain.SubtitleEncodings
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
import kotlinx.coroutines.CancellationException
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
    private val siblingFinder = SiblingSubtitleFinder(application)
    private val fontStore = SubtitleFontStore(application, prefs)

    private val _uiState = MutableStateFlow(SubtitleUiState())
    val uiState: StateFlow<SubtitleUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SubtitleUiEvent>()
    val events: SharedFlow<SubtitleUiEvent> = _events.asSharedFlow()

    /** Cues of the second subtitle (video feature 22), or null when none is loaded for the current video. */
    private val _secondaryTimeline = MutableStateFlow<SubtitleCueTimeline?>(null)
    val secondaryTimeline: StateFlow<SubtitleCueTimeline?> = _secondaryTimeline.asStateFlow()

    /** The viewer's custom subtitle font (video feature 23), or null for the default one. */
    private val _customTypeface = MutableStateFlow<Typeface?>(null)
    val customTypeface: StateFlow<Typeface?> = _customTypeface.asStateFlow()

    /** Where each subtitle the viewer loaded from a file came from, so a new text encoding can re-read it. */
    private val sourceUris = mutableMapOf<String, Uri>()
    private var secondarySourceUri: Uri? = null

    /** The second subtitle's cues as they are in its file, and the saved subtitle it came from (null when loaded as a file). */
    private var secondaryRawCues: List<SubtitleCue> = emptyList()
    private var secondaryCachedId: String? = null

    private var currentVideo: Video? = null
    private var currentMediaKey: String = ""
    private var searchJob: Job? = null
    private val autoSearchedVideoIds = mutableSetOf<String>()
    /** An automatic download that fails says why once, not on every video the viewer opens. */
    private var autoDownloadFailureShown = false

    init {
        loadSettings()
        loadLanguages()
        loadCustomFont()
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
                dualEnabled = SecondarySubtitlePrefs.isEnabled(prefs),
                generateEnabled = prefs.getBoolean(KEY_GENERATE, false),
                generateTranslateTo = prefs.getString(KEY_GENERATE_TRANSLATE_TO, null)?.takeIf { it.isNotBlank() },
                encodingId = SubtitleEncodingPrefs.read(prefs),
                secondaryStyle = SecondarySubtitlePrefs.readStyle(prefs),
                selectedLanguages = prefLangs,
                autoSearchMode = autoSearchMode,
                autoDownload = autoSearchMode != AutoSearchMode.OFF && prefs.getBoolean(KEY_AUTO_DOWNLOAD, false),
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
        // The second subtitle belongs to the video it was picked for.
        clearSecondarySubtitle()
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

            // A subtitle file next to the video (video feature 21) comes before any online search.
            if (_uiState.value.enabled && SiblingSubtitleFinder.isAutoLoadEnabled(prefs)) {
                if (autoLoadSiblingSubtitle(video, mediaKey)) return@launch
            }

            // Auto-generated subtitles (from the speech in the audio) come before the online search, when switched on.
            if (_uiState.value.enabled && _uiState.value.generateEnabled && isSpeechConfigured()) {
                generateSubtitles()
                return@launch
            }

            // Auto-search logic
            val autoMode = _uiState.value.autoSearchMode
            if (autoMode != AutoSearchMode.OFF && !autoSearchedVideoIds.contains(video.id)) {
                autoSearchedVideoIds.add(video.id)
                searchOnline(autoDownload = _uiState.value.autoDownload)
            }
        }
    }

    /**
     * Video feature 21: loads the subtitle that sits next to the video, if there is one. The best match is imported
     * through the normal importer (so encoding and format handling are the same as "Select file") and saved in the
     * subtitle cache, which means later plays of this video find it there without looking at the folder again.
     * Returns true when a subtitle was attached. Failures are silent: the caller carries on to online search.
     */
    private suspend fun autoLoadSiblingSubtitle(video: Video, mediaKey: String): Boolean {
        val languages = _uiState.value.selectedLanguages
        val found = try {
            siblingFinder.find(video, languages)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        // Forced-only tracks (foreign dialogue only) stay available for a manual pick but are never loaded on their own.
        for (sibling in found.filter { !it.candidate.isForced }.take(MAX_SIBLING_ATTEMPTS)) {
            val language = sibling.candidate.languageCode ?: languages.firstOrNull() ?: "en"
            val cached = fileImporter.importSubtitleFromUri(mediaKey, sibling.uri, language, currentCharset()).getOrNull() ?: continue
            sourceUris[cached.subtitleId] = sibling.uri
            // The viewer may have moved on to another video while the file was being read.
            if (currentVideo?.id != video.id) return false

            val label = cached.releaseName ?: sibling.candidate.fileName
            subtitleController.attachExternalSubtitle(
                videoId = video.id,
                file = File(cached.localPath),
                language = cached.language,
                label = label,
                subtitleId = cached.subtitleId
            )
            val updatedList = subtitleCache.getCachedSubtitles(mediaKey)
            _uiState.update {
                it.copy(
                    cachedSubtitles = updatedList,
                    selectedSubtitleId = cached.subtitleId,
                    selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                    selectedSubtitleName = label
                )
            }
            _events.emit(SubtitleUiEvent.DownloadSuccess(label))
            return true
        }
        return false
    }

    /** @param autoDownload true only for the search that starts by itself when a video opens (video feature 19). */
    fun searchOnline(manualQuery: String? = null, autoDownload: Boolean = false) {
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
                    if (autoDownload) autoDownloadBestMatch(video, scoredList)
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

    /**
     * Video feature 19: downloads the search result that is safe to take without asking, if there is one.
     * Does nothing when subtitles are switched off, the viewer has already moved to another video, or no result
     * passes [AutoSubtitlePicker]; the list stays in the sheet for a manual pick either way.
     */
    private fun autoDownloadBestMatch(video: Video, scored: List<ScoredSubtitle>) {
        if (!_uiState.value.enabled || currentVideo?.id != video.id) return
        val pick = AutoSubtitlePicker.pick(scored, _uiState.value.selectedLanguages) ?: return
        downloadAndApply(pick.subtitle, auto = true)
    }

    /**
     * @param auto true when the app picked this subtitle itself. Such a download never opens the sign-in dialog or
     * shows an error banner; a failure is reported by one short toast per session (the viewer did not ask for it).
     */
    fun downloadAndApply(result: SubtitleResult, auto: Boolean = false) {
        val video = currentVideo ?: return
        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isDownloading = result.id) }
            }

            // An automatic download reports its failure quietly and leaves the sheet's error state alone.
            suspend fun failAuto(error: SubtitleError) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDownloading = null) }
                    if (!autoDownloadFailureShown) {
                        autoDownloadFailureShown = true
                        _events.emit(SubtitleUiEvent.ShowToast("Auto-download skipped: " + SubtitleErrorMapper.toUserMessage(error)))
                    }
                }
            }

            // Check if already cached
            val cachedList = subtitleCache.getCachedSubtitles(mediaKey)
            val existing = cachedList.firstOrNull { it.subtitleId == result.id || it.subtitleId == result.fileId.toString() }

            // The viewer loaded a subtitle by hand while the search ran: theirs stays.
            if (auto && existing == null && cachedList.isNotEmpty()) {
                withContext(Dispatchers.Main) { _uiState.update { it.copy(isDownloading = null) } }
                return@launch
            }

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

                if (auto) {
                    failAuto(error)
                    return@launch
                }
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
                if (auto) {
                    failAuto(SubtitleError.DownloadFailed("File write failed"))
                    return@launch
                }
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
                if (auto) {
                    failAuto(SubtitleError.InvalidSubtitle)
                    return@launch
                }
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

            // The viewer moved on while an automatic download ran. The file is cached for that video's next play,
            // but it must not be attached to the one that is on screen now.
            if (auto && currentVideo?.id != video.id) {
                withContext(Dispatchers.Main) { _uiState.update { it.copy(isDownloading = null) } }
                return@launch
            }

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
            val res = fileImporter.importSubtitleFromUri(mediaKey, uri, _uiState.value.selectedLanguages.firstOrNull() ?: "en", currentCharset())
            if (res.isSuccess) {
                val cached = res.getOrThrow()
                sourceUris[cached.subtitleId] = uri
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
        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }
        val activeSubId = _uiState.value.selectedSubtitleId
            ?.takeIf { it != "none" && it.isNotBlank() }

        subtitleController.setSubtitleDelay(video.id, delayMs) {
            val cachedList: List<CachedSubtitle> = runBlocking { subtitleCache.getCachedSubtitles(mediaKey) }
            val item: CachedSubtitle? = cachedList.firstOrNull { it.subtitleId == activeSubId } ?: cachedList.firstOrNull()
            item?.let { File(it.localPath) }?.takeIf { it.exists() }
        }

        if (activeSubId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                subtitleCache.updateDelay(mediaKey, activeSubId, delayMs)
            }
        }
    }

    fun resetSubtitleDelay() {
        setSubtitleDelay(0L)
    }

    // ---- Custom font and text encoding (video feature 23) ----

    /** The charset to force when reading a subtitle file, or null for auto-detect. */
    private fun currentCharset() = SubtitleEncodings.charsetFor(_uiState.value.encodingId)

    /**
     * Sets the text encoding for subtitle files and re-reads the subtitles the viewer already loaded from a file
     * (the main one, and the second one of a dual pair) so the change shows straight away.
     */
    fun setEncoding(encodingId: String) {
        if (encodingId == _uiState.value.encodingId) return
        SubtitleEncodingPrefs.write(prefs, encodingId)
        _uiState.update { it.copy(encodingId = encodingId) }
        reloadMainSubtitleWithEncoding()
        reloadSecondarySubtitleWithEncoding()
    }

    private fun reloadMainSubtitleWithEncoding() {
        val video = currentVideo ?: return
        val state = _uiState.value
        val oldId = state.selectedSubtitleId ?: return
        val uri = sourceUris[oldId] ?: return
        if (state.selectedSubtitleType != SelectedSubtitleType.EXTERNAL) return

        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }
        val language = state.selectedLanguages.firstOrNull() ?: "en"
        val delayMs = state.delayMs

        viewModelScope.launch {
            val res = fileImporter.importSubtitleFromUri(mediaKey, uri, language, currentCharset())
            val cached = res.getOrNull()
            if (cached == null) {
                val error = (res.exceptionOrNull() as? SubtitleException)?.error ?: SubtitleError.Unknown(res.exceptionOrNull())
                _events.emit(SubtitleUiEvent.Error(SubtitleErrorMapper.toUserMessage(error)))
                return@launch
            }
            // The viewer may have moved on to another video while the file was being read.
            if (currentVideo?.id != video.id) return@launch

            val label = cached.releaseName ?: "Local Subtitle"
            subtitleController.attachExternalSubtitle(
                videoId = video.id,
                file = File(cached.localPath),
                language = cached.language,
                label = label,
                subtitleId = cached.subtitleId
            )
            // Only now is the previous copy (decoded with the old encoding) out of use.
            subtitleCache.deleteSubtitle(mediaKey, oldId)
            sourceUris.remove(oldId)
            sourceUris[cached.subtitleId] = uri

            val updatedList = subtitleCache.getCachedSubtitles(mediaKey)
            _uiState.update {
                it.copy(
                    cachedSubtitles = updatedList,
                    selectedSubtitleId = cached.subtitleId,
                    selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                    selectedSubtitleName = label
                )
            }
            // The new file has no delay applied; put back the one the viewer had set.
            if (delayMs != 0L) setSubtitleDelay(delayMs)
        }
    }

    private fun reloadSecondarySubtitleWithEncoding() {
        val video = currentVideo ?: return
        val uri = secondarySourceUri ?: return
        if (_secondaryTimeline.value == null) return
        val language = _uiState.value.selectedLanguages.firstOrNull() ?: "en"

        viewModelScope.launch {
            val read = fileImporter.readSubtitleFromUri(uri, language, currentCharset()).getOrNull() ?: return@launch
            val cues = SubtitleCueParser.parse(read.srtContent)
            if (currentVideo?.id != video.id || cues.isEmpty()) return@launch
            // Same subtitle, new encoding: the delay the viewer set stays.
            applySecondary(cues, read.displayName, _uiState.value.secondaryDelayMs)
        }
    }

    private fun loadCustomFont() {
        viewModelScope.launch {
            val font = fontStore.load() ?: return@launch
            _customTypeface.value = font.typeface
            _uiState.update { it.copy(customFontName = font.displayName) }
        }
    }

    /** Copies the picked TTF/OTF file into the app and uses it for subtitles. */
    fun importCustomFont(uri: Uri) {
        viewModelScope.launch {
            val res = fontStore.import(uri)
            val font = res.getOrNull()
            if (font != null) {
                _customTypeface.value = font.typeface
                _uiState.update { it.copy(customFontName = font.displayName) }
                _events.emit(SubtitleUiEvent.ShowToast("Subtitle font: ${font.displayName}"))
            } else {
                val error = (res.exceptionOrNull() as? SubtitleFontStore.FontImportException)?.error
                _events.emit(SubtitleUiEvent.Error(SubtitleErrorMapper.toFontMessage(error)))
            }
        }
    }

    /** Goes back to the default subtitle font. */
    fun clearCustomFont() {
        viewModelScope.launch {
            fontStore.clear()
            _customTypeface.value = null
            _uiState.update { it.copy(customFontName = null) }
        }
    }

    // ---- Dual subtitles (video feature 22) ----

    fun setDualSubtitlesEnabled(enabled: Boolean) {
        SecondarySubtitlePrefs.setEnabled(prefs, enabled)
        _uiState.update { it.copy(dualEnabled = enabled) }
        if (!enabled) clearSecondarySubtitle()
    }

    /** Uses one of the subtitles already saved for this video as the second subtitle. */
    fun selectSecondaryFromCache(cached: CachedSubtitle) {
        val video = currentVideo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val cues = readCues(File(cached.localPath))
            withContext(Dispatchers.Main) {
                if (currentVideo?.id != video.id) return@withContext
                if (cues.isEmpty()) {
                    _events.emit(SubtitleUiEvent.Error("Could not read that subtitle."))
                } else {
                    secondarySourceUri = null
                    secondaryCachedId = cached.subtitleId
                    applySecondary(cues, cached.releaseName ?: "Subtitle")
                }
            }
        }
    }

    /** The cues of a saved subtitle file (always UTF-8 SRT), or an empty list when it is missing or unreadable. */
    private fun readCues(file: File): List<SubtitleCue> = try {
        if (file.exists()) SubtitleCueParser.parse(file.readText(Charsets.UTF_8)) else emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** Loads a subtitle file picked by the viewer as the second subtitle. It is not saved to the video's subtitle list. */
    fun importSecondarySubtitle(uri: Uri) {
        val video = currentVideo ?: return
        viewModelScope.launch {
            val language = _uiState.value.selectedLanguages.firstOrNull() ?: "en"
            val res = fileImporter.readSubtitleFromUri(uri, language, currentCharset())
            if (currentVideo?.id != video.id) return@launch
            val read = res.getOrNull()
            val cues = read?.let { SubtitleCueParser.parse(it.srtContent) }.orEmpty()
            if (read != null && cues.isNotEmpty()) {
                secondarySourceUri = uri
                secondaryCachedId = null
                applySecondary(cues, read.displayName)
            } else {
                val error = (res.exceptionOrNull() as? SubtitleException)?.error ?: SubtitleError.InvalidSubtitle
                _events.emit(SubtitleUiEvent.Error(SubtitleErrorMapper.toUserMessage(error)))
            }
        }
    }

    fun clearSecondarySubtitle() {
        secondarySourceUri = null
        secondaryCachedId = null
        secondaryRawCues = emptyList()
        _secondaryTimeline.value = null
        _uiState.update { it.copy(secondarySubtitleName = null, secondaryDelayMs = 0L) }
    }

    fun setSecondaryPlacement(placement: SecondarySubtitlePlacement) =
        updateSecondaryStyle { it.copy(placement = placement) }

    fun setSecondaryTextColor(color: Int) = updateSecondaryStyle { it.copy(textColor = color) }

    fun setSecondarySizePercent(percent: Int) = updateSecondaryStyle {
        it.copy(sizePercent = percent.coerceIn(SecondarySubtitleStyle.MIN_SIZE_PERCENT, SecondarySubtitleStyle.MAX_SIZE_PERCENT))
    }

    /** Shows [cues] as the second subtitle, moved by [delayMs]. The cues are kept as they are in the file. */
    private fun applySecondary(cues: List<SubtitleCue>, name: String, delayMs: Long = 0L) {
        secondaryRawCues = cues
        _secondaryTimeline.value = SubtitleCueTimeline(SubtitleTapSync.shift(cues, delayMs))
        _uiState.update { it.copy(secondarySubtitleName = name, secondaryDelayMs = delayMs) }
    }

    /** Moves the second subtitle against the video, the way the delay buttons move the main one (video feature 20). */
    fun setSecondaryDelay(delayMs: Long) {
        if (secondaryRawCues.isEmpty()) return
        _secondaryTimeline.value = SubtitleCueTimeline(SubtitleTapSync.shift(secondaryRawCues, delayMs))
        _uiState.update { it.copy(secondaryDelayMs = delayMs) }
    }

    // ---- Swap and tap-to-sync (video feature 20) ----

    /**
     * Makes the second subtitle the main one and the main one the second, each keeping its delay.
     * Needs a main subtitle from a file (built-in tracks have no cues to read) and a loaded second subtitle.
     * A second subtitle that was only picked as a file is saved to this video's list first, because the player
     * can only show a saved file as the main subtitle.
     */
    fun swapSubtitles() {
        val video = currentVideo ?: return
        val state = _uiState.value
        val primaryId = state.selectedSubtitleId
        if (!state.dualEnabled || _secondaryTimeline.value == null ||
            state.selectedSubtitleType != SelectedSubtitleType.EXTERNAL || primaryId == null
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                _events.emit(SubtitleUiEvent.ShowToast("Load a main subtitle from a file and a second subtitle to swap them"))
            }
            return
        }

        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }
        val primaryDelay = state.delayMs
        val secondaryDelay = state.secondaryDelayMs
        val language = state.selectedLanguages.firstOrNull() ?: "en"

        viewModelScope.launch(Dispatchers.IO) {
            val saved = subtitleCache.getCachedSubtitles(mediaKey)
            val oldPrimary = saved.firstOrNull { it.subtitleId == primaryId }
            val oldPrimaryCues = oldPrimary?.let { readCues(File(it.localPath)) }.orEmpty()

            var newPrimary = secondaryCachedId?.let { id -> saved.firstOrNull { it.subtitleId == id } }
            if (newPrimary == null) {
                val uri = secondarySourceUri
                if (uri != null) {
                    newPrimary = fileImporter.importSubtitleFromUri(mediaKey, uri, language, currentCharset()).getOrNull()
                        ?.also { sourceUris[it.subtitleId] = uri }
                }
            }

            val promoted = newPrimary
            if (oldPrimary == null || oldPrimaryCues.isEmpty() || promoted == null || promoted.subtitleId == oldPrimary.subtitleId) {
                _events.emit(SubtitleUiEvent.Error("Could not swap the subtitles."))
                return@launch
            }

            withContext(Dispatchers.Main) {
                // The viewer may have moved on to another video while the files were being read.
                if (currentVideo?.id != video.id) return@withContext

                val label = promoted.releaseName ?: "External Subtitle"
                subtitleController.attachExternalSubtitle(
                    videoId = video.id,
                    file = File(promoted.localPath),
                    language = promoted.language,
                    label = label,
                    subtitleId = promoted.subtitleId
                )
                val updatedList = subtitleCache.getCachedSubtitles(mediaKey)
                _uiState.update {
                    it.copy(
                        cachedSubtitles = updatedList,
                        selectedSubtitleId = promoted.subtitleId,
                        selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                        selectedSubtitleName = label
                    )
                }

                secondarySourceUri = null
                secondaryCachedId = oldPrimary.subtitleId
                applySecondary(oldPrimaryCues, oldPrimary.releaseName ?: "Subtitle", primaryDelay)

                // The new main subtitle carries the delay the second one had. The player keeps showing the old delay
                // until it is told otherwise, so this also runs when only the old main subtitle had one.
                if (primaryDelay != 0L || secondaryDelay != 0L) setSubtitleDelay(secondaryDelay)
            }
        }
    }

    /**
     * The cues of the chosen subtitle as they are in its file, for the "tap the line you hear" list.
     * Empty when there is nothing to list (no file-based subtitle loaded).
     */
    suspend fun loadCuesForSync(target: SyncTarget): List<SubtitleCue> = when (target) {
        SyncTarget.SECOND -> secondaryRawCues
        SyncTarget.MAIN -> withContext(Dispatchers.IO) {
            val video = currentVideo ?: return@withContext emptyList<SubtitleCue>()
            val state = _uiState.value
            val id = state.selectedSubtitleId
            if (state.selectedSubtitleType != SelectedSubtitleType.EXTERNAL || id == null) {
                emptyList<SubtitleCue>()
            } else {
                val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }
                val item = subtitleCache.getCachedSubtitles(mediaKey).firstOrNull { it.subtitleId == id }
                item?.let { readCues(File(it.localPath)) }.orEmpty()
            }
        }
    }

    /**
     * Syncs [target] so that [cue] appears at [playerPositionMs]: the viewer tapped the line they are hearing right now.
     * The cue must come from [loadCuesForSync], i.e. carry its time from the file, because the delay is set from that.
     */
    fun syncToCue(target: SyncTarget, cue: SubtitleCue, playerPositionMs: Long) {
        val delay = SubtitleTapSync.delayFor(cue, playerPositionMs)
        when (target) {
            SyncTarget.MAIN -> {
                if (!_uiState.value.delaySupported) return
                setSubtitleDelay(delay)
            }
            SyncTarget.SECOND -> setSecondaryDelay(delay)
        }
        val seconds = delay / 1000f
        val shown = if (delay > 0L) "+%.1f s".format(seconds) else "%.1f s".format(seconds)
        viewModelScope.launch(Dispatchers.IO) { _events.emit(SubtitleUiEvent.ShowToast("Subtitles synced ($shown)")) }
    }

    private fun updateSecondaryStyle(change: (SecondarySubtitleStyle) -> SecondarySubtitleStyle) {
        val updated = change(_uiState.value.secondaryStyle)
        SecondarySubtitlePrefs.writeStyle(prefs, updated)
        _uiState.update { it.copy(secondaryStyle = updated) }
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
        // Downloading what the automatic search finds makes no sense without the search.
        val keepAutoDownload = mode != AutoSearchMode.OFF && _uiState.value.autoDownload
        prefs.edit()
            .putString("video_subtitle_auto_search_mode", mode.name)
            .putBoolean(KEY_AUTO_DOWNLOAD, keepAutoDownload)
            .apply()
        _uiState.update { it.copy(autoSearchMode = mode, autoDownload = keepAutoDownload) }
    }

    /** Video feature 19. Turning it on also turns the automatic search on, since the download is that search's result. */
    fun setAutoDownload(enabled: Boolean) {
        val mode = if (enabled && _uiState.value.autoSearchMode == AutoSearchMode.OFF) {
            AutoSearchMode.ON_PREFERRED_LANGUAGE
        } else {
            _uiState.value.autoSearchMode
        }
        prefs.edit()
            .putString("video_subtitle_auto_search_mode", mode.name)
            .putBoolean(KEY_AUTO_DOWNLOAD, enabled)
            .apply()
        _uiState.update { it.copy(autoSearchMode = mode, autoDownload = enabled) }
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

    // ---- Auto-generated subtitles from the audio track ----

    /** Position of the video's own audio track the subtitles are made from (the one the viewer is listening to). */
    private var generationAudioOrdinal = 0

    fun setGenerationAudioTrack(audioOrdinal: Int) {
        generationAudioOrdinal = audioOrdinal.coerceAtLeast(0)
    }

    fun setGenerateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GENERATE, enabled).apply()
        _uiState.update { it.copy(generateEnabled = enabled) }
    }

    /** The language to translate the generated subtitles into, or null to keep the spoken language. */
    fun setGenerateTranslateTo(languageCode: String?) {
        prefs.edit().putString(KEY_GENERATE_TRANSLATE_TO, languageCode).apply()
        _uiState.update { it.copy(generateTranslateTo = languageCode) }
    }

    /** False when this build has no speech key; the sheet says so instead of failing on each video. */
    fun isSpeechConfigured(): Boolean = BuildConfig.SPEECH_API_KEY.isNotBlank()

    /**
     * Makes subtitles from the speech in the video's audio track, translated when a language was chosen. The result is
     * saved in the subtitle cache like a downloaded file, so a later play of this video (same track, same language)
     * shows it at once without recognising the speech again.
     */
    fun generateSubtitles() {
        val video = currentVideo ?: return
        if (_uiState.value.isGenerating) return
        if (!isSpeechConfigured()) {
            viewModelScope.launch { _events.emit(SubtitleUiEvent.Error("Auto subtitles need SPEECH_API_KEY in this build.")) }
            return
        }

        val target = _uiState.value.generateTranslateTo
        val ordinal = generationAudioOrdinal
        val mediaKey = currentMediaKey.ifBlank { MediaKey.of(video) }
        val subtitleId = "gen_${ordinal}_${target ?: "orig"}"
        val label = if (target == null) "Auto subtitles" else "Auto subtitles (${WhisperLanguages.displayName(target)})"

        viewModelScope.launch(Dispatchers.IO) {
            val saved = subtitleCache.getCachedSubtitles(mediaKey).firstOrNull { it.subtitleId == subtitleId }
            if (saved != null && File(saved.localPath).exists()) {
                showGenerated(video, mediaKey, saved, label)
                return@launch
            }

            val workDir = File(getApplication<Application>().cacheDir, "subtitle_gen")
            _uiState.update { it.copy(isGenerating = true, generationStatus = "Reading the audio…") }
            try {
                val audio = FfmpegChunkProvider(getApplication(), video.uri, ordinal, workDir)
                val duration = video.durationMs.takeIf { it > 0L } ?: audio.durationMs()
                val generated = buildGenerationService().generate(audio, duration, target) { done, total ->
                    _uiState.update { it.copy(generationStatus = progressText(done, total)) }
                }

                subtitleCache.saveSubtitle(
                    mediaKey,
                    CachedSubtitle(
                        subtitleId = subtitleId,
                        language = generated.languageCode,
                        format = "SRT",
                        releaseName = label,
                        localPath = "",
                        source = SubtitleSource.LOCAL
                    ),
                    generated.srt
                )
                val entry = subtitleCache.getCachedSubtitles(mediaKey).first { it.subtitleId == subtitleId }

                val note = when {
                    generated.untranslatedLines > 0 -> "Done. ${generated.untranslatedLines} lines could not be translated and stay in the spoken language."
                    generated.translated -> "Done. Translated to ${WhisperLanguages.displayName(target)}."
                    else -> "Done. ${generated.cueCount} lines."
                }
                _uiState.update { it.copy(isGenerating = false, generationStatus = note) }
                showGenerated(video, mediaKey, entry, label)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isGenerating = false, generationStatus = null) }
                throw e
            } catch (e: Exception) {
                val message = generationMessage(e)
                _uiState.update { it.copy(isGenerating = false, generationStatus = message) }
                _events.emit(SubtitleUiEvent.Error(message))
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    private fun buildGenerationService(): GenerationService {
        val transcriber = OpenAiSpeechTranscriber(BuildConfig.SPEECH_ENDPOINT, BuildConfig.SPEECH_API_KEY, BuildConfig.SPEECH_MODEL)
        val translator = BuildConfig.LLM_API_KEY.takeIf { it.isNotBlank() }
            ?.let { ChatCompletionsTranslator(BuildConfig.LLM_ENDPOINT, it, BuildConfig.LLM_MODEL) }
        return GenerationService(transcriber, translator)
    }

    private fun progressText(done: Int, total: Int?): String {
        val part = done + 1
        return if (total != null && total > 0) "Recognising speech: part ${minOf(part, total)} of $total" else "Recognising speech: part $part"
    }

    private fun generationMessage(e: Throwable): String {
        val kind = (e as? GenerationException)?.kind
        return when (kind) {
            GenerationException.Kind.NOT_CONFIGURED -> "Translation needs LLM_API_KEY in this build."
            GenerationException.Kind.AUTH -> "The speech service rejected the API key."
            GenerationException.Kind.RATE_LIMITED -> "The speech service is busy. Try again in a minute."
            GenerationException.Kind.NETWORK -> "No internet connection, so subtitles could not be generated."
            GenerationException.Kind.NO_AUDIO -> "This video has no audio track to read."
            GenerationException.Kind.NO_SPEECH -> "No speech was found in this audio track."
            GenerationException.Kind.UNSUPPORTED_SOURCE -> "Could not read the audio of this video."
            else -> "Generating subtitles failed. Try again."
        }
    }

    /** Attaches a generated subtitle to the player and lists it with the others for this video. */
    private suspend fun showGenerated(video: Video, mediaKey: String, entry: CachedSubtitle, label: String) {
        if (currentVideo?.id != video.id) return
        subtitleController.attachExternalSubtitle(
            videoId = video.id,
            file = File(entry.localPath),
            language = entry.language,
            label = label,
            subtitleId = entry.subtitleId
        )
        val list = subtitleCache.getCachedSubtitles(mediaKey)
        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    cachedSubtitles = list,
                    selectedSubtitleId = entry.subtitleId,
                    selectedSubtitleType = SelectedSubtitleType.EXTERNAL,
                    selectedSubtitleName = label
                )
            }
            _events.emit(SubtitleUiEvent.DownloadSuccess(label))
        }
    }

    private companion object {
        /** How many matching files next to a video are tried before giving up (an unreadable one moves on to the next). */
        const val MAX_SIBLING_ATTEMPTS = 3

        const val KEY_AUTO_DOWNLOAD = "video_subtitle_auto_download"
        const val KEY_GENERATE = "video_subtitle_generate"
        const val KEY_GENERATE_TRANSLATE_TO = "video_subtitle_generate_translate_to"
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
