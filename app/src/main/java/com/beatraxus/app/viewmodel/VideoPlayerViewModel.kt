package com.beatraxus.app.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.engine.VideoRenderersFactory
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

data class VideoPlayerUiState(
    val currentVideo: Video? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPercentage: Int = 0,
    val videoSize: VideoSize = VideoSize.UNKNOWN,
    val isHdr: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val isLocked: Boolean = false,
    val availableAudioTracks: List<VideoTrackInfo> = emptyList(),
    val availableSubtitleTracks: List<VideoTrackInfo> = emptyList(),
    val selectedAudioTrackIndex: Int = -1,
    val selectedSubtitleTrackIndex: Int = -1,
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.FIT,
    val aspectRatioMessage: String? = null,
    val isVolumeBoost: Boolean = false,
    val subtitleOffset: Float = 0f,
    val showTotalTime: Boolean = false,
    val isEqEnabled: Boolean = true,
    val eqGains: List<Float> = List(10) { 0f },
    val availablePresets: List<SavedEqPreset> = emptyList(),
    val selectedPreset: String = "Manual",
    val volume: Int = 10,
    val maxVolume: Int = 15,
    val error: String? = null,
    val orientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
    val subtitleSize: Float = 18f,
    val subtitleTextColor: Int = android.graphics.Color.WHITE,
    val subtitleBackgroundColor: Int = android.graphics.Color.TRANSPARENT,
    val subtitleOutlineColor: Int = android.graphics.Color.BLACK,
    val subtitleWindowColor: Int = android.graphics.Color.TRANSPARENT,
    val isBackgroundPlayEnabled: Boolean = false
)

enum class VolumeBoostMode {
    NORMAL, BOOST
}

data class VideoTrackInfo(
    val index: Int,
    val name: String,
    val language: String?,
    val format: String?,
    val isSelected: Boolean
)

enum class VideoAspectRatio(val displayName: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
    FOUR_THREE("4:3"),
    SIXTEEN_NINE("16:9")
}

@UnstableApi
class VideoPlayerViewModel(
    application: Application,
    private val videoQueue: List<Video>,
    initialVideoId: String
) : AndroidViewModel(application) {
    private val TAG = "VideoPlayerViewModel"

    private var exoPlayer: ExoPlayer? = null
    private val database = (application as BeatraxusApplication).database
    private val videoRecentlyPlayedDao = database.videoRecentlyPlayedDao()
    private val dspPreferences = com.beatraxus.app.repository.DspPreferences(application)
    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null

    init {
        if (videoQueue.isEmpty()) {
            Log.e(TAG, "Video queue is empty, cannot initialize player")
            _uiState.update { it.copy(error = "Video queue is empty") }
        } else {
            val foundIndex = videoQueue.indexOfFirst { it.id == initialVideoId }
            if (foundIndex == -1) {
                Log.w(TAG, "Initial video ID $initialVideoId not found in queue of size ${videoQueue.size}")
            }
            val initialIndex = foundIndex.coerceAtLeast(0)
            setupPlayer(initialIndex)
        }

        loadCustomPresets()
    }

    private fun loadCustomPresets() {
        val raw = prefs.getString("custom_eq_presets", null)
        val allPresets = mutableListOf<SavedEqPreset>()
        allPresets.addAll(com.beatraxus.app.model.getBuiltInEqPresets())

        if (raw != null) {
            runCatching {
                val array = org.json.JSONArray(raw)
                val customPresets = mutableListOf<SavedEqPreset>()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val name = item.optString("name")
                    val preampDb = item.optDouble("preampDb", 0.0).toFloat()
                    val bandsJson = item.optJSONArray("bands") ?: org.json.JSONArray()
                    val bands = mutableListOf<com.beatraxus.app.model.ParametricEqBand>()
                    for (bandIndex in 0 until bandsJson.length()) {
                        val band = bandsJson.getJSONObject(bandIndex)
                        bands.add(
                            com.beatraxus.app.model.ParametricEqBand(
                                id = band.optInt("id", bandIndex),
                                enabled = band.optBoolean("enabled", true),
                                frequencyHz = band.optDouble("frequencyHz", 1000.0).toFloat(),
                                gainDb = band.optDouble("gainDb", 0.0).toFloat(),
                                q = band.optDouble("q", 1.0).toFloat()
                            )
                        )
                    }
                    if (name.isNotBlank() && bands.isNotEmpty()) {
                        customPresets.add(SavedEqPreset(name, bands, preampDb))
                    }
                }
                allPresets.addAll(customPresets)
            }
        }
        _uiState.update { it.copy(availablePresets = allPresets) }
    }

    private fun setupPlayer(startIndex: Int) {
        if (videoQueue.isEmpty()) return

        val video = videoQueue.getOrNull(startIndex)
        Log.d(TAG, "setupPlayer: startIndex=$startIndex queueSize=${videoQueue.size} " +
            "title=${video?.title} uri=${video?.uri} mime=${video?.mimeType}")

        val context = getApplication<Application>()
        val player = ExoPlayer.Builder(context, VideoRenderersFactory(context))
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                repeatMode = Player.REPEAT_MODE_OFF
                
                val mediaItems = videoQueue.map { video ->
                    MediaItem.Builder()
                        .setUri(video.uri)
                        .setMediaId(video.id)
                        .build()
                }
                setMediaItems(mediaItems)
                
                if (startIndex < videoQueue.size) {
                    val initialVideo = videoQueue[startIndex]
                    viewModelScope.launch(Dispatchers.IO) {
                        val recentlyPlayedList = videoRecentlyPlayedDao.getAllRecentlyPlayed().first()
                        val recentlyPlayed = recentlyPlayedList.find { it.videoId == initialVideo.id }
                        val lastPos = recentlyPlayed?.lastPosition ?: 0L
                        val lastRatio = recentlyPlayed?.lastAspectRatio?.let { ratioName ->
                            VideoAspectRatio.entries.find { it.name == ratioName }
                        } ?: VideoAspectRatio.FIT
                        
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(aspectRatio = lastRatio) }
                            seekTo(startIndex, lastPos)
                            prepare()
                            playWhenReady = true
                        }
                    }
                } else {
                    prepare()
                    playWhenReady = true
                }
            }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startProgressUpdate() else {
                    stopProgressUpdate()
                    _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update { it.copy(playbackState = playbackState) }
                if (playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(duration = player.duration) }
                    updateTracks()
                    setupLoudnessEnhancer(player.audioSessionId)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                updateCurrentVideo()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentVideo()
                updateTracks()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _uiState.update { it.copy(videoSize = videoSize) }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateTracks()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error: code=${error.errorCode} message=${error.message}", error)
                _uiState.update { it.copy(error = "Playback failed: ${error.errorCodeName}") }
            }
        })

        exoPlayer = player
        updateCurrentVideo()
    }

    private fun updateCurrentVideo() {
        val player = exoPlayer ?: return
        val currentId = player.currentMediaItem?.mediaId
        val video = videoQueue.find { it.id == currentId }
        
        // Save position of previous video before switching
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        
        _uiState.update { it.copy(currentVideo = video, isHdr = video?.isHdr ?: false) }
        
        video?.let { recordVideoPlayed(it) }
    }

    private fun recordVideoPlayed(video: Video) {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        val currentRatio = _uiState.value.aspectRatio.name
        viewModelScope.launch(Dispatchers.IO) {
            videoRecentlyPlayedDao.addRecentlyPlayed(
                VideoRecentlyPlayedEntity(
                    videoId = video.id,
                    timestamp = System.currentTimeMillis(),
                    lastPosition = currentPos,
                    lastAspectRatio = currentRatio
                )
            )
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _uiState.update { it.copy(
                        currentPosition = player.currentPosition,
                        bufferedPercentage = player.bufferedPercentage
                    ) }
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    private fun updateTracks() {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks
        
        val audioTracks = mutableListOf<VideoTrackInfo>()
        val subtitleTracks = mutableListOf<VideoTrackInfo>()

        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    audioTracks.add(VideoTrackInfo(
                        index = groupIndex,
                        name = format.label ?: "Audio ${audioTracks.size + 1}",
                        language = format.language,
                        format = format.sampleMimeType,
                        isSelected = group.isTrackSelected(i)
                    ))
                }
            } else if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    subtitleTracks.add(VideoTrackInfo(
                        index = groupIndex,
                        name = format.label ?: "Subtitle ${subtitleTracks.size + 1}",
                        language = format.language,
                        format = format.sampleMimeType,
                        isSelected = group.isTrackSelected(i)
                    ))
                }
            }
        }

        _uiState.update { it.copy(
            availableAudioTracks = audioTracks,
            availableSubtitleTracks = subtitleTracks
        ) }
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        _uiState.update { it.copy(currentPosition = position) }
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun setAspectRatio(ratio: VideoAspectRatio) {
        _uiState.update { it.copy(aspectRatio = ratio, aspectRatioMessage = ratio.displayName) }
        viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(aspectRatioMessage = null) }
        }
    }

    fun selectAudioTrack(track: VideoTrackInfo) {
        exoPlayer?.let { player ->
            val parameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(
                        player.currentTracks.groups[track.index].mediaTrackGroup,
                        0 
                    )
                )
                .build()
            player.trackSelectionParameters = parameters
        }
    }

    fun selectSubtitleTrack(track: VideoTrackInfo?) {
        exoPlayer?.let { player ->
            val builder = player.trackSelectionParameters.buildUpon()
            if (track == null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(
                            player.currentTracks.groups[track.index].mediaTrackGroup,
                            0
                        )
                    )
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    fun toggleLock() {
        _uiState.update { it.copy(isLocked = !it.isLocked) }
    }

    fun playNext() {
        exoPlayer?.let {
            if (it.hasNextMediaItem()) {
                it.seekToNext()
            }
        }
    }

    fun playPrevious() {
        exoPlayer?.let {
            if (it.hasPreviousMediaItem()) {
                it.seekToPrevious()
            }
        }
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) return
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                enabled = true
            }
            updateLoudness()

            equalizer?.release()
            equalizer = android.media.audiofx.Equalizer(0, audioSessionId).apply {
                enabled = true
                applyEqGains()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup Audio Effects", e)
        }
    }

    private fun applyEqGains() {
        val eq = equalizer ?: return
        val state = _uiState.value
        
        try {
            eq.enabled = state.isEqEnabled

            if (!state.isEqEnabled) return

            val gains = state.eqGains
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minLevel = range[0]
            val maxLevel = range[1]

            for (i in 0 until numBands.coerceAtMost(gains.size)) {
                val level = (gains[i] * 100).toInt().toShort() 
                val clampedLevel = level.coerceIn(minLevel, maxLevel)
                try {
                    eq.setBandLevel(i.toShort(), clampedLevel)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set EQ band $i", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hardware Equalizer error in applyEqGains", e)
        }
    }

    private fun updateLoudness() {
        val state = _uiState.value
        if (!state.isVolumeBoost) {
            loudnessEnhancer?.setTargetGain(0)
            return
        }
        
        if (state.volume > 15) {
            val gain = (state.volume - 15) * 200 
            loudnessEnhancer?.setTargetGain(gain)
        } else {
            loudnessEnhancer?.setTargetGain(500) 
        }
    }

    fun setVolume(volume: Int) {
        val max = if (!_uiState.value.isVolumeBoost) 15 else 30
        val newVol = volume.coerceIn(0, max)
        _uiState.update { it.copy(volume = newVol, maxVolume = max) }
        updateLoudness()
    }

    fun toggleVolumeBoost() {
        _uiState.update { 
            val nextBoost = !it.isVolumeBoost
            val newMax = if (!nextBoost) 15 else 30
            it.copy(
                isVolumeBoost = nextBoost,
                maxVolume = newMax,
                volume = it.volume.coerceAtMost(newMax)
            )
        }
        updateLoudness()
    }

    fun toggleTimeDisplay() {
        _uiState.update { it.copy(showTotalTime = !it.showTotalTime) }
    }

    fun toggleEqEnabled() {
        _uiState.update { it.copy(isEqEnabled = !it.isEqEnabled) }
        applyEqGains()
    }

    fun setEqPreset(preset: com.beatraxus.app.model.SavedEqPreset) {
        val standardFreqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val newGains = standardFreqs.map { freq ->
            preset.bands.minByOrNull { Math.abs(it.frequencyHz - freq) }?.gainDb ?: 0f
        }
        _uiState.update { it.copy(eqGains = newGains, selectedPreset = preset.name, isEqEnabled = true) }
        applyEqGains()
    }

    fun setEqGain(band: Int, gain: Float) {
        _uiState.update { state ->
            val newGains = state.eqGains.toMutableList()
            if (band in newGains.indices) {
                newGains[band] = gain
            }
            state.copy(eqGains = newGains, selectedPreset = "Manual")
        }
        applyEqGains()
    }

    fun setSubtitleOffset(offset: Float) {
        _uiState.update { it.copy(subtitleOffset = offset) }
    }

    fun setSubtitleSize(size: Float) {
        _uiState.update { it.copy(subtitleSize = size) }
    }

    fun setSubtitleTextColor(color: Int) {
        _uiState.update { it.copy(subtitleTextColor = color) }
    }

    fun setSubtitleBackgroundColor(color: Int) {
        _uiState.update { it.copy(subtitleBackgroundColor = color) }
    }

    fun setSubtitleOutlineColor(color: Int) {
        _uiState.update { it.copy(subtitleOutlineColor = color) }
    }

    fun resetSubtitleStyle() {
        _uiState.update { it.copy(
            subtitleSize = 18f,
            subtitleTextColor = android.graphics.Color.WHITE,
            subtitleBackgroundColor = android.graphics.Color.TRANSPARENT,
            subtitleOutlineColor = android.graphics.Color.BLACK,
            subtitleWindowColor = android.graphics.Color.TRANSPARENT,
            subtitleOffset = 0f
        ) }
    }

    fun toggleOrientation(activity: android.app.Activity) {
        val current = activity.requestedOrientation
        val next = when (current) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        activity.requestedOrientation = next
        _uiState.update { it.copy(orientation = next) }
    }

    fun toggleBackgroundPlay() {
        _uiState.update { it.copy(isBackgroundPlayEnabled = !it.isBackgroundPlayEnabled) }
    }

    fun enterBackgroundPlay(onClose: () -> Unit) {
        val currentVideo = _uiState.value.currentVideo ?: return
        val currentPos = exoPlayer?.currentPosition ?: 0L
        
        prefs.edit().apply {
            putString("bg_video_id", currentVideo.id)
            putLong("bg_video_pos", currentPos)
            putBoolean("bg_video_active", true)
            apply()
        }
        
        onClose()
    }

    fun getPlayer(): Player? = exoPlayer

    override fun onCleared() {
        super.onCleared()
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        equalizer?.release()
        equalizer = null
        exoPlayer?.release()
        exoPlayer = null
        viewModelScope.cancel()
    }
}

class VideoPlayerViewModelFactory(
    private val application: Application,
    private val videoQueue: List<Video>,
    private val initialVideoId: String
) : ViewModelProvider.Factory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoPlayerViewModel::class.java)) {
            return VideoPlayerViewModel(application, videoQueue, initialVideoId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
