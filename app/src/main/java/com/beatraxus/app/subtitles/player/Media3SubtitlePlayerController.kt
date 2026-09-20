package com.beatraxus.app.subtitles.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.beatraxus.app.subtitles.data.SubtitleDelayShifter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class Media3SubtitlePlayerController(
    private val playerProvider: () -> ExoPlayer?
) : SubtitlePlayerController {

    private val attachMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _activeExternalTrackId = MutableStateFlow<String?>(null)
    override val activeExternalTrackId: StateFlow<String?> = _activeExternalTrackId.asStateFlow()

    private val _activeExternalSubtitleName = MutableStateFlow<String?>(null)
    override val activeExternalSubtitleName: StateFlow<String?> = _activeExternalSubtitleName.asStateFlow()

    private val _isDelaySupported = MutableStateFlow<Boolean>(false)
    override val isDelaySupported: StateFlow<Boolean> = _isDelaySupported.asStateFlow()

    private val _currentDelayMs = MutableStateFlow<Long>(0L)
    override val currentDelayMs: StateFlow<Long> = _currentDelayMs.asStateFlow()

    private var delayJob: Job? = null

    override suspend fun attachExternalSubtitle(
        videoId: String,
        file: File,
        language: String?,
        label: String,
        mimeType: String,
        subtitleId: String
    ) {
        attachMutex.withLock {
            withContext(Dispatchers.Main) {
                val player = playerProvider() ?: return@withContext
                val index = player.currentMediaItemIndex
                val currentItem = player.getMediaItemAt(index)

                if (currentItem.mediaId != videoId) return@withContext

                val trackId = "bx_ext_$subtitleId"
                
                if (currentItem.localConfiguration?.subtitleConfigurations?.any { it.id == trackId } == true) {
                    _activeExternalTrackId.value = trackId
                    _activeExternalSubtitleName.value = label
                    _isDelaySupported.value = true
                    enableAndSelectSideloadedTrack(player, trackId)
                    return@withContext
                }

                val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(mimeType)
                    .setLanguage(language)
                    .setLabel(label)
                    .setId(trackId)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()

                val currentPos = player.currentPosition
                val isPlaying = player.playWhenReady
                val currentSpeed = player.playbackParameters.speed

                var selectedAudioMime: String? = null
                var selectedAudioLang: String? = null
                player.currentTracks.groups.forEach { group ->
                    if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val fmt = group.getTrackFormat(i)
                                selectedAudioMime = fmt.sampleMimeType
                                selectedAudioLang = fmt.language
                            }
                        }
                    }
                }

                val newItem = currentItem.buildUpon()
                    .setSubtitleConfigurations(listOf(subConfig))
                    .build()

                player.replaceMediaItem(index, newItem)
                player.seekTo(index, currentPos)
                player.playbackParameters = PlaybackParameters(currentSpeed)
                player.playWhenReady = isPlaying

                _activeExternalTrackId.value = trackId
                _activeExternalSubtitleName.value = label
                _isDelaySupported.value = true

                enableAndSelectSideloadedTrack(player, trackId)

                if (selectedAudioMime != null || selectedAudioLang != null) {
                    restoreAudioTrack(player, selectedAudioMime, selectedAudioLang)
                }
            }
        }
    }

    override suspend fun removeExternalSubtitle(videoId: String) {
        attachMutex.withLock {
            withContext(Dispatchers.Main) {
                val player = playerProvider() ?: return@withContext
                val index = player.currentMediaItemIndex
                val currentItem = player.getMediaItemAt(index)

                if (currentItem.mediaId != videoId) return@withContext

                if (currentItem.localConfiguration?.subtitleConfigurations.isNullOrEmpty()) {
                    _activeExternalTrackId.value = null
                    _activeExternalSubtitleName.value = null
                    _isDelaySupported.value = false
                    _currentDelayMs.value = 0L
                    return@withContext
                }

                val currentPos = player.currentPosition
                val isPlaying = player.playWhenReady
                val currentSpeed = player.playbackParameters.speed

                val newItem = currentItem.buildUpon()
                    .setSubtitleConfigurations(emptyList())
                    .build()

                player.replaceMediaItem(index, newItem)
                player.seekTo(index, currentPos)
                player.playbackParameters = PlaybackParameters(currentSpeed)
                player.playWhenReady = isPlaying

                _activeExternalTrackId.value = null
                _activeExternalSubtitleName.value = null
                _isDelaySupported.value = false
                _currentDelayMs.value = 0L
            }
        }
    }

    override fun selectEmbeddedTrack(groupIndex: Int) {
        val player = playerProvider() ?: return
        var builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)

        if (groupIndex >= 0 && groupIndex < player.currentTracks.groups.size) {
            val group = player.currentTracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_TEXT) {
                builder = builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }

        player.trackSelectionParameters = builder.build()
        _activeExternalTrackId.value = null
        _activeExternalSubtitleName.value = null
        _isDelaySupported.value = false
        _currentDelayMs.value = 0L
    }

    override fun disableSubtitles() {
        val player = playerProvider() ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _activeExternalTrackId.value = null
        _activeExternalSubtitleName.value = null
        _isDelaySupported.value = false
        _currentDelayMs.value = 0L
    }

    override fun setSubtitleDelay(videoId: String, delayMs: Long, originalFileProvider: () -> File?) {
        if (!_isDelaySupported.value) return

        _currentDelayMs.value = delayMs
        delayJob?.cancel()
        delayJob = scope.launch {
            delay(600)
            val origFile = originalFileProvider() ?: return@launch
            val shiftedFile = SubtitleDelayShifter.getOrCreateShiftedFile(origFile, delayMs)
            val currentTrackId = _activeExternalTrackId.value?.removePrefix("bx_ext_") ?: "default"
            val currentLabel = _activeExternalSubtitleName.value ?: "Subtitle"

            attachExternalSubtitle(
                videoId = videoId,
                file = shiftedFile,
                language = null,
                label = currentLabel,
                mimeType = MimeTypes.APPLICATION_SUBRIP,
                subtitleId = currentTrackId
            )
        }
    }

    override fun detachPlayer() {
        delayJob?.cancel()
        _activeExternalTrackId.value = null
        _activeExternalSubtitleName.value = null
        _isDelaySupported.value = false
        _currentDelayMs.value = 0L
    }

    private fun enableAndSelectSideloadedTrack(player: ExoPlayer, targetTrackId: String) {
        var params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)

        player.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.id == targetTrackId) {
                        params = params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        break
                    }
                }
            }
        }

        player.trackSelectionParameters = params.build()
    }

    private fun restoreAudioTrack(player: ExoPlayer, mimeType: String?, lang: String?) {
        player.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if ((mimeType != null && fmt.sampleMimeType == mimeType) || (lang != null && fmt.language == lang)) {
                        val params = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        player.trackSelectionParameters = params
                        return
                    }
                }
            }
        }
    }
}
