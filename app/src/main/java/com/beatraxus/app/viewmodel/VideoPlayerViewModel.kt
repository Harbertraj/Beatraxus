package com.beatraxus.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.engine.VideoRenderersFactory
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
<<<<<<< HEAD
import com.beatraxus.app.motionboost.*
=======
import com.beatraxus.app.util.PlaybackGlobalState
>>>>>>> 2d9abc7 (remove fps booster)
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
    val subtitleAlpha: Float = 1.0f,
    val isBackgroundPlayEnabled: Boolean = false,
    val motionBoostMode: MotionBoostMode = MotionBoostMode.ORIGINAL,
    val motionBoostQuality: MotionBoostQuality = MotionBoostQuality.BALANCED,
    val sourceFrameRateInfo: VideoFrameRateInfo? = null,
    val motionBoostCapabilities: MotionBoostCapabilities? = null,
    val isMotionBoostSupported: Boolean = false,
    val motionBoostLiveFps: Int = 0
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
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val newVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (!_uiState.value.isVolumeBoost || newVol < _uiState.value.volume) {
                    _uiState.update { it.copy(volume = newVol) }
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(VideoPlayerUiState(
        volume = (application.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC),
        maxVolume = (application.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        isEqEnabled = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getBoolean("video_eq_enabled", true),
        aspectRatio = VideoAspectRatio.entries.find { 
            it.name == application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getString("video_aspect_ratio", VideoAspectRatio.FIT.name) 
        } ?: VideoAspectRatio.FIT,
        showTotalTime = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getBoolean("video_show_remaining_time", false)
    ))
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null

    // Motion Boost Pipeline
    private var frameCapture: DecodedFrameCapture? = null
    private val frameBuffer = mutableListOf<VideoFrame>()
    private var renderLoopActive = false
    private val performanceMonitor = PerformanceMonitor()
    private val thermalMonitor = ThermalMonitor(application)
    private val motionBoostRenderer = MotionBoostRenderer()

    // Smooth Clock & FPS Tracking
    private var lastExoPositionMs = 0L
    private var lastSystemTimeNs = 0L
    private var frameCaptureIntervals = mutableListOf<Long>()
    private var lastCaptureTimeUs = 0L
    private val PRESENTATION_DELAY_US = 100_000L // 100ms lookahead delay

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!renderLoopActive) return
            renderFrame(frameTimeNanos)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        application.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        // Initial capabilities detection
        updateMotionBoostCapabilities()

        thermalMonitor.start { newState ->
            if (newState == ThermalState.HOT || newState == ThermalState.CRITICAL) {
                viewModelScope.launch {
                    if (_uiState.value.motionBoostMode != MotionBoostMode.ORIGINAL) {
                        Log.w(TAG, "Thermal throttle: Disabling Motion Boost")
                        setMotionBoostMode(MotionBoostMode.ORIGINAL)
                        _uiState.update { it.copy(error = "Motion Boost reduced to protect device performance") }
                    }
                }
            }
        }

        if (videoQueue.isNotEmpty()) {
            val initialIndex = videoQueue.indexOfFirst { it.id == initialVideoId }.coerceAtLeast(0)
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
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val name = item.optString("name")
                    val preampDb = item.optDouble("preampDb", 0.0).toFloat()
                    val bandsJson = item.optJSONArray("bands") ?: org.json.JSONArray()
                    val bands = mutableListOf<com.beatraxus.app.model.ParametricEqBand>()
                    for (bandIndex in 0 until bandsJson.length()) {
                        val band = bandsJson.getJSONObject(bandIndex)
                        bands.add(com.beatraxus.app.model.ParametricEqBand(
                            id = band.optInt("id", bandIndex),
                            enabled = band.optBoolean("enabled", true),
                            frequencyHz = band.optDouble("frequencyHz", 1000.0).toFloat(),
                            gainDb = band.optDouble("gainDb", 0.0).toFloat(),
                            q = band.optDouble("q", 1.0).toFloat()
                        ))
                    }
                    if (name.isNotBlank() && bands.isNotEmpty()) {
                        allPresets.add(SavedEqPreset(name, bands, preampDb))
                    }
                }
            }
        }
        _uiState.update { it.copy(availablePresets = allPresets) }
    }

    private fun setupPlayer(startIndex: Int) {
        if (videoQueue.isEmpty()) return
        val video = videoQueue[startIndex]
        val context = getApplication<Application>()
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(context, VideoRenderersFactory(context))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItems(videoQueue.map { v -> MediaItem.Builder().setUri(v.uri).setMediaId(v.id).build() })

<<<<<<< HEAD
                viewModelScope.launch(Dispatchers.IO) {
                    val recentlyPlayed = videoRecentlyPlayedDao.getRecentlyPlayedByVideoId(video.id)
                    val lastPos = recentlyPlayed?.lastPosition ?: 0L
                    withContext(Dispatchers.Main) {
                        seekTo(startIndex, lastPos)
                        prepare()
                        playWhenReady = true
                    }
=======
                val mediaItems = videoQueue.map { video ->
                    MediaItem.Builder()
                        .setUri(video.uri)
                        .setMediaId(video.id)
                        .build()
                }
                setMediaItems(mediaItems)
                
                // PERFORMANCE FIX: Prepare immediately before seeking/loading history
                prepare()
                
                if (startIndex < videoQueue.size) {
                    val initialVideo = videoQueue[startIndex]
                    viewModelScope.launch(Dispatchers.IO) {
                        val recentlyPlayed = videoRecentlyPlayedDao.getRecentlyPlayedByVideoId(initialVideo.id)
                        val lastPos = recentlyPlayed?.lastPosition ?: 0L
                        val lastRatio = recentlyPlayed?.lastAspectRatio?.let { ratioName ->
                            VideoAspectRatio.entries.find { it.name == ratioName }
                        } ?: VideoAspectRatio.FIT
                        
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(aspectRatio = lastRatio) }
                            seekTo(startIndex, lastPos)
                            playWhenReady = true
                        }
                    }
                } else {
                    playWhenReady = true
>>>>>>> 2d9abc7 (remove fps booster)
                }
            }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                PlaybackGlobalState.setPlaybackActive(isPlaying)
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
                    updateSourceFrameRate()
                    updateMotionBoostCapabilities()
                }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
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
                updateSourceFrameRate()
            }
        })

        exoPlayer = player
        updateCurrentVideo()

        frameCapture = DecodedFrameCapture { frame ->
            estimateSourceFps(frame.presentationTimeUs)
            synchronized(frameBuffer) {
                if (frameBuffer.size >= 15) frameBuffer.removeAt(0).release()
                frameBuffer.add(frame)
            }
        }
    }

    private fun estimateSourceFps(timestampUs: Long) {
        if (lastCaptureTimeUs > 0) {
            val interval = timestampUs - lastCaptureTimeUs
            if (interval in 5000..100000) {
                frameCaptureIntervals.add(interval)
                if (frameCaptureIntervals.size > 10) { // Fast detection
                    frameCaptureIntervals.removeAt(0)
                    val avgInterval = frameCaptureIntervals.average()
                    val fps = 1_000_000f / avgInterval.toFloat()

                    val current = _uiState.value.sourceFrameRateInfo
                    if (current?.sourceFrameRate == null || current.isEstimated) {
                        _uiState.update { it.copy(sourceFrameRateInfo = VideoFrameRateInfo(fps, avgInterval.toLong(), true)) }
                    }
                }
            }
        }
        lastCaptureTimeUs = timestampUs
    }

    private fun updateCurrentVideo() {
        val player = exoPlayer ?: return
        val currentId = player.currentMediaItem?.mediaId
        val video = videoQueue.find { it.id == currentId }
        
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        _uiState.update { it.copy(currentVideo = video, isHdr = video?.isHdr ?: false) }
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
                    val pos = player.currentPosition
                    lastExoPositionMs = pos
                    lastSystemTimeNs = System.nanoTime()
                    _uiState.update { it.copy(currentPosition = pos, bufferedPercentage = player.bufferedPercentage) }
                }
                delay(16) // ~60fps UI sync
            }
        }
    }

    private fun stopProgressUpdate() = progressJob?.cancel()

    private fun updateTracks() {
        val player = exoPlayer ?: return
        val audioTracks = mutableListOf<VideoTrackInfo>()
        val subtitleTracks = mutableListOf<VideoTrackInfo>()

        player.currentTracks.groups.forEachIndexed { index, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val info = VideoTrackInfo(index, format.label ?: "Track ${i+1}", format.language, format.sampleMimeType, group.isTrackSelected(i))
                if (group.type == C.TRACK_TYPE_AUDIO) audioTracks.add(info)
                else if (group.type == C.TRACK_TYPE_TEXT) subtitleTracks.add(info)
            }
        }
        _uiState.update { it.copy(availableAudioTracks = audioTracks, availableSubtitleTracks = subtitleTracks) }
    }

    fun togglePlayPause() = exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        flushMotionBoost()
        _uiState.update { it.copy(currentPosition = position) }
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun setAspectRatio(ratio: VideoAspectRatio) {
        _uiState.update { it.copy(aspectRatio = ratio, aspectRatioMessage = ratio.displayName) }
        viewModelScope.launch { delay(2000); _uiState.update { it.copy(aspectRatioMessage = null) } }
    }

    fun selectAudioTrack(track: VideoTrackInfo) {
        exoPlayer?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(p.currentTracks.groups[track.index].mediaTrackGroup, 0))
                .build()
        }
    }

    fun selectSubtitleTrack(track: VideoTrackInfo?) {
        exoPlayer?.let { p ->
            val builder = p.trackSelectionParameters.buildUpon()
            if (track == null) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(p.currentTracks.groups[track.index].mediaTrackGroup, 0))
            p.trackSelectionParameters = builder.build()
        }
    }

<<<<<<< HEAD
=======
    fun toggleLock() {
        _uiState.update { it.copy(isLocked = !it.isLocked) }
    }

    fun playNext() {
        exoPlayer?.let {
            if (it.hasNextMediaItem()) {
                it.seekToNext()
                it.play()
            }
        }
    }

    fun playPrevious() {
        exoPlayer?.let {
            if (it.hasPreviousMediaItem()) {
                it.seekToPrevious()
                it.play()
            }
        }
    }

    private var lastAudioSessionId: Int = -1

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) return
        if (audioSessionId == lastAudioSessionId && loudnessEnhancer != null && equalizer != null) return
        
        try {
            lastAudioSessionId = audioSessionId
            
            loudnessEnhancer?.release()
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                enabled = true
            }
            updateLoudness()

            equalizer?.release()
            equalizer = android.media.audiofx.Equalizer(0, audioSessionId).apply {
                applyEqGains() // Apply gains BEFORE enabling to prevent pops and state errors
                enabled = _uiState.value.isEqEnabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup Audio Effects", e)
        }
    }

    private fun applyEqGains() {
        val eq = equalizer ?: return
        val state = _uiState.value
        
        try {
            // FIX: Don't set enabled here if it might be uninitialized
            // The setupLoudnessEnhancer will handle initial enablement.
            if (eq.enabled != state.isEqEnabled) {
                eq.enabled = state.isEqEnabled
            }

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
        val systemMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        if (!state.isVolumeBoost) {
            loudnessEnhancer?.setTargetGain(0)
            return
        }
        
        if (state.volume > systemMax) {
            val gain = (state.volume - systemMax) * 200 
            loudnessEnhancer?.setTargetGain(gain)
        } else {
            loudnessEnhancer?.setTargetGain(500) 
        }
    }

    fun setVolume(volume: Int) {
        val systemMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val max = if (!_uiState.value.isVolumeBoost) systemMax else systemMax * 2
        val newVol = volume.coerceIn(0, max)
        
        // Sync with system volume if not in boost range
        if (newVol <= systemMax) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        }
        
        _uiState.update { it.copy(volume = newVol, maxVolume = max) }
        updateLoudness()
    }

    fun toggleVolumeBoost() {
        val systemMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        _uiState.update { 
            val nextBoost = !it.isVolumeBoost
            val newMax = if (!nextBoost) systemMax else systemMax * 2
            it.copy(
                isVolumeBoost = nextBoost,
                maxVolume = newMax,
                volume = it.volume.coerceAtMost(newMax)
            )
        }
        updateLoudness()
    }

>>>>>>> 2d9abc7 (remove fps booster)
    fun toggleTimeDisplay() {
        _uiState.update { 
            val next = !it.showTotalTime
            it.copy(showTotalTime = next) 
        }
    }

    fun toggleLock() = _uiState.update { it.copy(isLocked = !it.isLocked) }
    fun playNext() = exoPlayer?.let { if (it.hasNextMediaItem()) it.seekToNext() }
    fun playPrevious() = exoPlayer?.let { if (it.hasPreviousMediaItem()) it.seekToPrevious() }

    private fun setupLoudnessEnhancer(sessionId: Int) {
        if (sessionId == android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) return
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId).apply { enabled = true }
            equalizer?.release()
            equalizer = android.media.audiofx.Equalizer(0, sessionId).apply { enabled = true; applyEqGains() }
        } catch (e: Exception) { Log.e(TAG, "AudioFX setup failed", e) }
    }

    private fun applyEqGains() {
        val eq = equalizer ?: return
        val state = _uiState.value
        try {
            if (runCatching { eq.enabled }.isFailure) return
            eq.enabled = state.isEqEnabled
            if (!state.isEqEnabled) return
            val range = eq.bandLevelRange
            state.eqGains.forEachIndexed { i, gain ->
                if (i < eq.numberOfBands) {
                    val level = (gain * 100).toInt().toShort().coerceIn(range[0], range[1])
                    eq.setBandLevel(i.toShort(), level)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "EQ apply failed", e) }
    }

    fun setVolume(v: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newV = v.coerceIn(0, if (_uiState.value.isVolumeBoost) max * 2 else max)
        if (newV <= max) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
        _uiState.update { it.copy(volume = newV) }
    }

    fun toggleVolumeBoost() = _uiState.update {
        val next = !it.isVolumeBoost
        it.copy(isVolumeBoost = next, maxVolume = if (next) 30 else 15)
    }

    fun toggleEqEnabled() {
        _uiState.update { it.copy(isEqEnabled = !it.isEqEnabled) }
        applyEqGains()
    }

    fun setEqGain(band: Int, gain: Float) {
        _uiState.update { s ->
            val newGains = s.eqGains.toMutableList().apply { this[band] = gain }
            s.copy(eqGains = newGains, selectedPreset = "Manual")
        }
        applyEqGains()
    }

    fun setEqPreset(preset: SavedEqPreset) {
        val standardFreqs = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val newGains = standardFreqs.map { f -> preset.bands.minByOrNull { Math.abs(it.frequencyHz - f) }?.gainDb ?: 0f }
        _uiState.update { it.copy(eqGains = newGains, selectedPreset = preset.name, isEqEnabled = true) }
        applyEqGains()
    }

    fun setSubtitleSize(s: Float) = _uiState.update { it.copy(subtitleSize = s) }
    fun setSubtitleTextColor(c: Int) = _uiState.update { it.copy(subtitleTextColor = c) }
    fun setSubtitleBackgroundColor(c: Int) = _uiState.update { it.copy(subtitleBackgroundColor = c) }
    fun setSubtitleOutlineColor(c: Int) = _uiState.update { it.copy(subtitleOutlineColor = c) }
    fun setSubtitleAlpha(a: Float) = _uiState.update { it.copy(subtitleAlpha = a) }
    fun setSubtitleOffset(o: Float) = _uiState.update { it.copy(subtitleOffset = o) }
    fun resetSubtitleStyle() = _uiState.update { it.copy(subtitleSize = 18f, subtitleTextColor = -1, subtitleBackgroundColor = 0, subtitleAlpha = 1f, subtitleOffset = 0f) }

    fun toggleOrientation(a: android.app.Activity) {
        val next = if (a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        a.requestedOrientation = next
        _uiState.update { it.copy(orientation = next) }
    }

    fun toggleBackgroundPlay() = _uiState.update { it.copy(isBackgroundPlayEnabled = !it.isBackgroundPlayEnabled) }

    fun setMotionBoostMode(mode: MotionBoostMode) {
        _uiState.update { it.copy(motionBoostMode = mode) }
        flushMotionBoost()
        val player = exoPlayer
        if (mode != MotionBoostMode.ORIGINAL) {
            player?.let { frameCapture?.attachToPlayer(it) }
            if (!renderLoopActive) startRenderLoop()
        } else {
            player?.let { frameCapture?.detachFromPlayer(it) }
            if (renderLoopActive) stopRenderLoop()
        }
    }

    fun setPresentationSurface(surface: Surface?) = motionBoostRenderer.setSurface(surface)

    private fun startRenderLoop() {
        renderLoopActive = true
        motionBoostRenderer.start()
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        renderLoopActive = false
        motionBoostRenderer.stop()
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val player = exoPlayer ?: return
        synchronized(frameBuffer) {
            performanceMonitor.recordFrameGeneration(0.0)
            updateLiveFps()

            if (!player.isPlaying || frameBuffer.size < 2) return

            // 100ms Presentation Delay for interpolation lookahead
            val elapsedMs = (System.nanoTime() - lastSystemTimeNs) / 1_000_000
            val predictedPositionUs = (lastExoPositionMs + (elapsedMs * _uiState.value.playbackSpeed)).toLong() * 1000
            val targetTimeUs = predictedPositionUs - PRESENTATION_DELAY_US

            val frameA = frameBuffer.findLast { it.presentationTimeUs <= targetTimeUs }
            val frameB = frameBuffer.find { it.presentationTimeUs > targetTimeUs }

            if (frameA != null && frameB != null) {
                val alpha = ((targetTimeUs - frameA.presentationTimeUs).toFloat() / (frameB.presentationTimeUs - frameA.presentationTimeUs).toFloat()).coerceIn(0f, 1f)
                motionBoostRenderer.render(frameA, frameB, alpha)
            } else if (targetTimeUs > (frameBuffer.lastOrNull()?.presentationTimeUs ?: 0) + 500000) {
                flushMotionBoost()
            }
        }
    }

    private fun updateLiveFps() {
        val liveFps = performanceMonitor.getLiveFps()
        if (liveFps != _uiState.value.motionBoostLiveFps) _uiState.update { it.copy(motionBoostLiveFps = liveFps) }
    }

    fun setMotionBoostQuality(q: MotionBoostQuality) = _uiState.update { it.copy(motionBoostQuality = q) }

    private fun updateMotionBoostCapabilities() {
        val refreshRate = DisplayRefreshRateDetector.getRefreshRate(getApplication())
        _uiState.update { it.copy(motionBoostCapabilities = MotionBoostCapabilities.compute(refreshRate), isMotionBoostSupported = true) }
    }

    private fun updateSourceFrameRate() {
        exoPlayer?.let { p ->
            val info = SourceFrameRateDetector.detect(p)
            _uiState.update { it.copy(sourceFrameRateInfo = info) }
        }
    }

    private fun flushMotionBoost() {
        synchronized(frameBuffer) { frameBuffer.forEach { it.release() }; frameBuffer.clear() }
    }

    fun getPlayer(): Player? = exoPlayer

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(volumeReceiver)
<<<<<<< HEAD
=======
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        PlaybackGlobalState.setPlaybackActive(false)
>>>>>>> 2d9abc7 (remove fps booster)
        loudnessEnhancer?.release()
        equalizer?.release()
        exoPlayer?.release()
        motionBoostRenderer.stop()
        viewModelScope.cancel()
    }
}

class VideoPlayerViewModelFactory(private val application: Application, private val videoQueue: List<Video>, private val initialVideoId: String) : ViewModelProvider.Factory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VideoPlayerViewModel(application, videoQueue, initialVideoId) as T
    }
}
