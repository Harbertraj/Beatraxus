package com.beatraxus.app.subtitles.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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

    /** Waits for the tracks of a just-replaced media item, then selects the new subtitle track (see [selectSideloadedTrackWhenLoaded]). */
    private var pendingSelection: Player.Listener? = null

    override suspend fun currentPositionMs(): Long = withContext(Dispatchers.Main) {
        playerProvider()?.currentPosition ?: 0L
    }

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
                // The version query is ignored when the file is read, but it makes a rewritten file (the same path with
                // more subtitles in it) a different source, so the player loads it again instead of keeping the old one.
                val expectedUri = Uri.fromFile(file).buildUpon()
                    .appendQueryParameter("v", "${file.lastModified()}-${file.length()}")
                    .build()

                val existingConfig = currentItem.localConfiguration?.subtitleConfigurations?.find { it.id == trackId }
                if (existingConfig != null && existingConfig.uri == expectedUri) {
                    _activeExternalTrackId.value = trackId
                    _activeExternalSubtitleName.value = label
                    _isDelaySupported.value = true
                    enableAndSelectSideloadedTrack(player, trackId)
                    return@withContext
                }

                val subConfig = MediaItem.SubtitleConfiguration.Builder(expectedUri)
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

                selectSideloadedTrackWhenLoaded(player, trackId)

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
                cancelPendingSelection()

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
        cancelPendingSelection()
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
        cancelPendingSelection()
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
            delay(250)
            val origFile = withContext(Dispatchers.IO) { originalFileProvider() } ?: return@launch
            val shiftedFile = withContext(Dispatchers.IO) {
                SubtitleDelayShifter.getOrCreateShiftedFile(origFile, delayMs)
            }
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
        cancelPendingSelection()
        _activeExternalTrackId.value = null
        _activeExternalSubtitleName.value = null
        _isDelaySupported.value = false
        _currentDelayMs.value = 0L
    }

    /** Turns subtitles on and selects the sideloaded track [targetTrackId] when the player already lists it; true when it did. */
    private fun enableAndSelectSideloadedTrack(player: ExoPlayer, targetTrackId: String): Boolean {
        var params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        var found = false

        player.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    // Media3 can prefix the id of a sideloaded track, so an exact match is not required.
                    val id = format.id
                    if (id != null && (id == targetTrackId || id.endsWith(targetTrackId))) {
                        params = params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        found = true
                        break
                    }
                }
            }
        }

        // Not listed yet (the player still shows the old item's tracks): drop any text override left from the video's
        // own subtitle, otherwise it keeps that track selected next to the new one until the guard catches up.
        if (!found) params = params.clearOverridesOfType(C.TRACK_TYPE_TEXT)

        player.trackSelectionParameters = params.build()
        return found
    }

    /** True when the sideloaded track [targetTrackId] is the text track the player is showing right now. */
    private fun isSideloadedTrackSelected(player: ExoPlayer, targetTrackId: String): Boolean {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return false
        player.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (i in 0 until group.length) {
                val id = group.getTrackFormat(i).id
                if (id != null && (id == targetTrackId || id.endsWith(targetTrackId)) && group.isTrackSelected(i)) return true
            }
        }
        return false
    }

    /** Stops [selectSideloadedTrackWhenLoaded] from selecting anything more (the viewer chose another subtitle). */
    private fun cancelPendingSelection() {
        val listener = pendingSelection ?: return
        playerProvider()?.removeListener(listener)
        pendingSelection = null
    }

    /**
     * Right after the media item is replaced the player still lists the tracks of the OLD item, so selecting then can
     * miss the new subtitle track entirely (a generated subtitle was "done" yet never appeared, especially when subtitles
     * had been switched off before). Subtitles are switched on at once and the track is selected again as soon as the
     * new item's tracks arrive.
     *
     * The player may also pick its own text track again while the new item loads (the video's own subtitle, or an older
     * selection that is still stored), which showed up as the chosen subtitle switching back to the video's own. So the
     * new track stays guarded for [GUARD_MS] after it was first selected: whenever it is not the one on screen, it is
     * selected again. The guard ends early when the viewer picks another subtitle ([cancelPendingSelection]).
     */
    private fun selectSideloadedTrackWhenLoaded(player: ExoPlayer, trackId: String) {
        cancelPendingSelection()

        var guardUntil = 0L

        val listener = object : Player.Listener {
            private fun finish() {
                player.removeListener(this)
                if (pendingSelection === this) pendingSelection = null
            }

            private fun check() {
                val now = SystemClock.elapsedRealtime()
                if (guardUntil != 0L && now > guardUntil) {
                    finish()
                    return
                }
                if (isSideloadedTrackSelected(player, trackId)) {
                    if (guardUntil == 0L) guardUntil = now + GUARD_MS
                } else if (enableAndSelectSideloadedTrack(player, trackId) && guardUntil == 0L) {
                    guardUntil = now + GUARD_MS
                }
            }

            override fun onTracksChanged(tracks: Tracks) = check()

            // The new item is prepared a moment after it is swapped in; try again once it is ready to play.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) check()
            }
        }
        pendingSelection = listener
        player.addListener(listener)

        enableAndSelectSideloadedTrack(player, trackId)

        scope.launch {
            delay(15_000)
            if (pendingSelection === listener) {
                player.removeListener(listener)
                pendingSelection = null
            }
        }
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

    private companion object {
        /** How long a newly attached subtitle track is kept selected against the player picking another one. */
        const val GUARD_MS = 6_000L
    }
}
