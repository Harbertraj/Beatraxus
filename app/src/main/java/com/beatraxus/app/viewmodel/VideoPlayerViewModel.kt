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
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val error: String? = null
)

data class VideoTrackInfo(
    val index: Int,
    val name: String,
    val language: String?,
    val format: String?,
    val isSelected: Boolean
)

enum class VideoAspectRatio {
    FIT, FILL, ZOOM, FOUR_THREE, SIXTEEN_NINE
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
    
    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        if (videoQueue.isEmpty()) {
            Log.e(TAG, "Video queue is empty, cannot initialize player")
            _uiState.update { it.copy(error = "Video queue is empty") }
        } else {
            val foundIndex = videoQueue.indexOfFirst { it.id == initialVideoId }
            if (foundIndex == -1) {
                Log.w(TAG, "Initial video ID $initialVideoId not found in queue of size ${videoQueue.size}")
                // If it's a single-item fallback from PlayerViewModel, it SHOULD be in the queue.
                // If it's still not found, we could try to play index 0 or show error.
            }
            val initialIndex = foundIndex.coerceAtLeast(0)
            setupPlayer(initialIndex)
        }
    }

    private fun setupPlayer(startIndex: Int) {
        if (videoQueue.isEmpty()) return

        Log.d(TAG, "setupPlayer: startIndex=$startIndex queueSize=${videoQueue.size} " +
            "uri=${videoQueue.getOrNull(startIndex)?.uri} " +
            "mime=${videoQueue.getOrNull(startIndex)?.mimeType}")

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
                    seekTo(startIndex, 0L)
                }
                
                prepare()
                playWhenReady = true
            }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startProgressUpdate() else stopProgressUpdate()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update { it.copy(playbackState = playbackState) }
                if (playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(duration = player.duration) }
                    updateTracks()
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
                Log.e(TAG, "ExoPlayer error: code=${error.errorCode} " +
                    "codeName=${error.errorCodeName} message=${error.message}", error)
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
        _uiState.update { it.copy(currentVideo = video, isHdr = video?.isHdr ?: false) }
        
        video?.let { recordVideoPlayed(it) }
    }

    private fun recordVideoPlayed(video: Video) {
        viewModelScope.launch(Dispatchers.IO) {
            videoRecentlyPlayedDao.addRecentlyPlayed(
                VideoRecentlyPlayedEntity(
                    videoId = video.id,
                    timestamp = System.currentTimeMillis()
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
        _uiState.update { it.copy(aspectRatio = ratio) }
        // RESIZE_MODE will be handled in the View layer
    }

    fun selectAudioTrack(track: VideoTrackInfo) {
        exoPlayer?.let { player ->
            val parameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(
                        player.currentTracks.groups[track.index].mediaTrackGroup,
                        0 // Assuming first track in group for simplicity
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

    fun getPlayer(): Player? = exoPlayer

    override fun onCleared() {
        super.onCleared()
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
