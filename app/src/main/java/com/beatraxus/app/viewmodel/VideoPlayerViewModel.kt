package com.beatraxus.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.engine.VideoRenderersFactory
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
import com.beatraxus.app.motionboost.ColorGradeEffect
import com.beatraxus.app.subtitles.data.SubtitleCache
import com.beatraxus.app.subtitles.domain.MediaKey
import com.beatraxus.app.subtitles.domain.SubtitlePositionPreset
import com.beatraxus.app.subtitles.player.Media3SubtitlePlayerController
import com.beatraxus.app.subtitles.player.SubtitlePlayerController
import com.beatraxus.app.util.PlaybackGlobalState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.abs

data class VideoPlayerUiState(
    val currentVideo: Video? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
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
    val orientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    val subtitleSize: Float = 18f,
    val subtitleTextColor: Int = android.graphics.Color.WHITE,
    val subtitleBackgroundColor: Int = android.graphics.Color.TRANSPARENT,
    val subtitleOutlineColor: Int = android.graphics.Color.BLACK,
    val subtitleWindowColor: Int = android.graphics.Color.TRANSPARENT,
    val subtitleAlpha: Float = 1.0f,
    val subtitleBold: Boolean = false,
    val subtitleEdgeType: Int = 1, // CaptionStyleCompat.EDGE_TYPE_OUTLINE
    val subtitleBackgroundOpacity: Float = 0.0f,
    val subtitlePositionPreset: SubtitlePositionPreset = SubtitlePositionPreset.BOTTOM,
    val subtitleSizePercent: Int = 100,
    val activeExternalSubtitleName: String? = null,
    val isSubtitleDelaySupported: Boolean = false,
    val currentSubtitleDelayMs: Long = 0L,
    val isBackgroundPlayEnabled: Boolean = false,
    val abRepeatPointA: Long? = null,
    val abRepeatPointB: Long? = null,
    val isAbRepeatActive: Boolean = false,
    val sleepTimerRemainingMs: Long? = null,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val showSkipIntroButton: Boolean = false,
    val scrubbingThumbnail: android.graphics.Bitmap? = null,
    val scrubbingTimeMs: Long? = null,
    val chapters: List<com.beatraxus.app.model.VideoChapterEntity> = emptyList(),
    val colorBrightness: Float = 0f,
    val colorContrast: Float = 1f,
    val colorSaturation: Float = 1f,
    val forceSdrToneMapping: Boolean = false
)



enum class SleepTimerMode(val label: String) {
    OFF("Off"),
    MIN_15("15 Minutes"),
    MIN_30("30 Minutes"),
    MIN_60("60 Minutes"),
    END_OF_VIDEO("End of Video")
}

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
    ORIGINAL("Original"),
    FIT("Fit to screen"),
    FILL("Fill screen"),
    ZOOM("Zoom"),
    STRETCH("Stretch"),
    CROP("Crop"),
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
    private val videoChapterDao = database.videoChapterDao()
    private val introOutroDao = database.introOutroDao()
    private val dspPreferences = com.beatraxus.app.repository.DspPreferences(application)
    private val introOutroDetector = com.beatraxus.app.engine.IntroOutroDetector(application)
    private val sceneChangeDetector = com.beatraxus.app.engine.SceneChangeDetector(application)
    private var cachedIntroRange: com.beatraxus.app.model.IntroOutroRange? = null
    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var spriteSheet: android.graphics.Bitmap? = null
    private var spriteMetadata: com.beatraxus.app.utils.ThumbnailSpriteGenerator.SpriteMetadata? = null
    private var spriteLoadingJob: Job? = null
    private var chapterDetectionJob: Job? = null
    private var chapterObservationJob: Job? = null
    private var backgroundTasksTimerJob: Job? = null
    private val processedBackgroundVideos = mutableSetOf<String>()
    private val chapterDetectionStartedForVideoIds = mutableSetOf<String>()
    private var isEffectActive = false

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
        showTotalTime = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getBoolean("video_show_remaining_time", false),
        subtitleSize = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getFloat("video_subtitle_size", 18f),
        subtitleTextColor = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getInt("video_subtitle_text_color", Color.WHITE),
        subtitleBackgroundColor = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getInt("video_subtitle_bg_color", Color.TRANSPARENT),
        subtitleOutlineColor = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getInt("video_subtitle_outline_color", Color.BLACK),
        subtitleWindowColor = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getInt("video_subtitle_window_color", Color.TRANSPARENT),
        subtitleAlpha = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getFloat("video_subtitle_alpha", 1.0f),
        subtitleOffset = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getFloat("video_subtitle_offset", 0f),
        subtitleBold = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getBoolean("video_subtitle_bold", false),
        subtitleEdgeType = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getInt("video_subtitle_edge_type", 1),
        subtitleBackgroundOpacity = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getFloat("video_subtitle_bg_opacity", 0.0f),
        subtitlePositionPreset = SubtitlePositionPreset.entries.find {
            it.name == application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getString("video_subtitle_pos_preset", SubtitlePositionPreset.BOTTOM.name)
        } ?: SubtitlePositionPreset.BOTTOM,
        subtitleSizePercent = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getInt("video_subtitle_size_percent", 100)
    ))
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    val subtitleController: SubtitlePlayerController = Media3SubtitlePlayerController { exoPlayer }
    private val subtitleCache = SubtitleCache(application)
    
    val positionFlow = MutableStateFlow(0L)

    private var progressJob: Job? = null
    private var abRepeatJob: Job? = null
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var sleepTimerJob: Job? = null
    
    private val colorGradeEffect = ColorGradeEffect(0f, 1f, 1f)

    private var pendingAudioTrackIndex = -1
    private var pendingSubtitleTrackIndex = -1
    private var isInitialTrackRestorationDone = false
    private var lastHandledVideoId: String? = null

    init {
        application.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        if (videoQueue.isNotEmpty()) {
            val initialIndex = videoQueue.indexOfFirst { it.id == initialVideoId }.coerceAtLeast(0)
            setupPlayer(initialIndex)
        }

        loadCustomPresets()
        loadIntroRange()
    }

    private fun loadIntroRange() {
        val folder = videoQueue.firstOrNull()?.folderPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val range = introOutroDao.getRangeForFolder(folder)
            if (range != null) {
                cachedIntroRange = range
            } else if (videoQueue.size >= 2) {
                val detected = introOutroDetector.detectIntro(videoQueue)
                if (detected != null) {
                    val newRange = com.beatraxus.app.model.IntroOutroRange(folder, detected.first, detected.second)
                    introOutroDao.insertRange(newRange)
                    cachedIntroRange = newRange
                }
            }
        }
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
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(context, VideoRenderersFactory(context))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItems(videoQueue.map { v -> MediaItem.Builder().setUri(v.uri).setMediaId(v.id).build() })
                
                viewModelScope.launch(Dispatchers.IO) {
                    val recentlyPlayed = videoRecentlyPlayedDao.getRecentlyPlayedByVideoId(video.id)
                    val lastPos = recentlyPlayed?.lastPositionMs ?: 0L
                    val lastRatio = recentlyPlayed?.lastAspectRatio?.let { ratioName ->
                        VideoAspectRatio.entries.find { it.name == ratioName }
                    } ?: VideoAspectRatio.FIT
                    
                    val lastAudio = recentlyPlayed?.lastAudioTrackIndex ?: -1
                    val lastSubtitle = recentlyPlayed?.lastSubtitleTrackIndex ?: -1

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(aspectRatio = lastRatio) }
                        
                        pendingAudioTrackIndex = lastAudio
                        pendingSubtitleTrackIndex = lastSubtitle
                        isInitialTrackRestorationDone = false
                        
                        val isIdentity = abs(_uiState.value.colorBrightness) < 0.001f &&
                                         abs(_uiState.value.colorContrast - 1f) < 0.001f &&
                                         abs(_uiState.value.colorSaturation - 1f) < 0.001f
                        if (!isIdentity) {
                            updateVideoEffects() // Apply initial effects before prepare
                        }
                        
                        seekTo(startIndex, lastPos)
                        prepare()
                        playWhenReady = true
                    }
                }
            }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                // Use handleAudioFocus to let ExoPlayer manage MediaRouter internally
                PlaybackGlobalState.setPlaybackActive(isPlaying) // Do not pause music engine explicitly if ExoPlayer does it via AudioFocus
                if (isPlaying) {
                    startProgressUpdate()
                    startAbRepeatWatcher()
                } else {
                    stopProgressUpdate()
                    stopAbRepeatWatcher()
                    _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
                }
                checkBackgroundTasks()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update { it.copy(playbackState = playbackState) }
                if (playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(duration = player.duration) }
                    updateTracks()
                    setupLoudnessEnhancer(player.audioSessionId)
                } else if (playbackState == Player.STATE_ENDED) {
                    if (_uiState.value.sleepTimerMode == SleepTimerMode.END_OF_VIDEO) {
                        player.pause()
                        cancelSleepTimer()
                    }
                }
                checkBackgroundTasks()
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    updateCurrentVideo()
                }
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
                
                if (!isInitialTrackRestorationDone && (pendingAudioTrackIndex != -1 || pendingSubtitleTrackIndex != -1)) {
                    val player = exoPlayer ?: return
                    var params = player.trackSelectionParameters.buildUpon()
                    var changed = false
                    
                    if (pendingAudioTrackIndex != -1 && pendingAudioTrackIndex < tracks.groups.size) {
                        val group = tracks.groups[pendingAudioTrackIndex]
                        if (group.type == C.TRACK_TYPE_AUDIO) {
                            params = params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
                            changed = true
                        }
                    }
                    
                    if (pendingSubtitleTrackIndex != -1) {
                        if (pendingSubtitleTrackIndex == -2) {
                            params = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            changed = true
                        } else if (pendingSubtitleTrackIndex < tracks.groups.size) {
                            val group = tracks.groups[pendingSubtitleTrackIndex]
                            if (group.type == C.TRACK_TYPE_TEXT) {
                                params = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
                                changed = true
                            }
                        }
                    }
                    
                    if (changed) {
                        player.trackSelectionParameters = params.build()
                    }
                    isInitialTrackRestorationDone = true
                }
            }
        })

        exoPlayer = player
        updateCurrentVideo()
    }

    private fun updateCurrentVideo() {
        val player = exoPlayer ?: return
        val currentId = player.currentMediaItem?.mediaId
        val video = videoQueue.find { it.id == currentId }
        
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        _uiState.update { it.copy(currentVideo = video, isHdr = video?.isHdr ?: false) }
        
        if (video?.id != lastHandledVideoId) {
            lastHandledVideoId = video?.id
            video?.let { v ->
                viewModelScope.launch {
                    val mediaKey = MediaKey.of(v)
                    val cachedSubs = subtitleCache.getCachedSubtitles(mediaKey)
                    val activeCached = cachedSubs.firstOrNull()
                    if (activeCached != null) {
                        val file = File(activeCached.localPath)
                        if (file.exists()) {
                            subtitleController.attachExternalSubtitle(
                                videoId = v.id,
                                file = file,
                                language = activeCached.language,
                                label = activeCached.releaseName ?: "External Subtitle",
                                mimeType = MimeTypes.APPLICATION_SUBRIP,
                                subtitleId = activeCached.subtitleId
                            )
                            pendingSubtitleTrackIndex = -1
                        }
                    } else {
                        subtitleController.removeExternalSubtitle(v.id)
                    }
                }
                
                backgroundTasksTimerJob?.cancel()
                spriteLoadingJob?.cancel()
                chapterDetectionJob?.cancel()
                chapterObservationJob?.cancel()
                checkBackgroundTasks()
            }
        }
    }

    private fun checkBackgroundTasks() {
        val player = exoPlayer ?: return
        val currentVideo = _uiState.value.currentVideo ?: return
        val videoId = currentVideo.id
        
        if (processedBackgroundVideos.contains(videoId)) return
        
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            if (backgroundTasksTimerJob?.isActive != true) {
                backgroundTasksTimerJob = viewModelScope.launch {
                    delay(10000)
                    if (isActive) {
                        processedBackgroundVideos.add(videoId)
                        generateScrubPreviews(currentVideo)
                        observeAndDetectChapters(currentVideo)
                    }
                }
            }
        } else {
            backgroundTasksTimerJob?.cancel()
        }
    }

    private fun observeAndDetectChapters(video: Video) {
        chapterObservationJob?.cancel()
        chapterObservationJob = viewModelScope.launch {
            videoChapterDao.getChaptersForVideo(video.id).collect { list ->
                _uiState.update { it.copy(chapters = list) }
                if (list.isEmpty() && !chapterDetectionStartedForVideoIds.contains(video.id)) {
                    chapterDetectionStartedForVideoIds.add(video.id)
                    triggerChapterDetection(video)
                }
            }
        }
    }

    private fun triggerChapterDetection(video: Video) {
        chapterDetectionJob?.cancel()
        chapterDetectionJob = viewModelScope.launch(Dispatchers.Default) {
            val duration = if (video.durationMs > 0) video.durationMs else {
                while (exoPlayer?.duration ?: 0 <= 0) delay(500)
                exoPlayer?.duration ?: 0
            }
            
            val detected = sceneChangeDetector.detectScenes(video.uri, video.id, duration)
            if (detected.isNotEmpty()) {
                videoChapterDao.insertChapters(detected)
            }
        }
    }

    private fun generateScrubPreviews(video: Video) {
        spriteLoadingJob?.cancel()
        spriteSheet?.recycle()
        spriteSheet = null
        spriteMetadata = null
        
        spriteLoadingJob = viewModelScope.launch {
            val meta = com.beatraxus.app.utils.ThumbnailSpriteGenerator.generateSpriteSheet(
                getApplication(),
                video.uri,
                video.id
            )
            spriteMetadata = meta
            
            if (meta != null) {
                val file = com.beatraxus.app.utils.ThumbnailSpriteGenerator.getSpriteFile(getApplication(), video.id)
                if (file.exists()) {
                    withContext(Dispatchers.IO) {
                        spriteSheet = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    }
                }
            }
        }
    }

    fun updateScrubbingPreview(positionMs: Long?) {
        if (positionMs == null) {
            _uiState.update { it.copy(scrubbingThumbnail = null, scrubbingTimeMs = null) }
            return
        }

        val sheet = spriteSheet
        val meta = spriteMetadata
        
        if (sheet != null && meta != null) {
            val thumb = com.beatraxus.app.utils.ThumbnailSpriteGenerator.getFrameFromSprite(sheet, meta, positionMs)
            _uiState.update { it.copy(scrubbingThumbnail = thumb, scrubbingTimeMs = positionMs) }
        } else {
            _uiState.update { it.copy(scrubbingThumbnail = null, scrubbingTimeMs = positionMs) }
        }
    }

    private fun recordVideoPlayed(video: Video) {
        val player = exoPlayer ?: return
        val currentPos = player.currentPosition
        val duration = player.duration
        if (duration <= 0) return

        val progress = currentPos.toDouble() / duration.toDouble()
        if (progress < 0.02 || progress > 0.95) return

        val currentRatio = _uiState.value.aspectRatio.name
        
        var audioIdx = -1
        var subtitleIdx = -1
        
        player.currentTracks.groups.forEachIndexed { index, group ->
            if (group.isSelected) {
                if (group.type == C.TRACK_TYPE_AUDIO) audioIdx = index
                else if (group.type == C.TRACK_TYPE_TEXT) subtitleIdx = index
            }
        }
        
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            subtitleIdx = -2
        }

        viewModelScope.launch(Dispatchers.IO) {
            videoRecentlyPlayedDao.addRecentlyPlayed(
                VideoRecentlyPlayedEntity(
                    videoId = video.id,
                    timestamp = System.currentTimeMillis(),
                    lastPositionMs = currentPos,
                    durationMs = duration,
                    lastAspectRatio = currentRatio,
                    lastAudioTrackIndex = audioIdx,
                    lastSubtitleTrackIndex = subtitleIdx
                )
            )
        }
    }

    private var lastDbSaveTime = 0L

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    val pos = player.currentPosition
                    if (positionFlow.value != pos) {
                        positionFlow.value = pos
                    }

                    val state = _uiState.value

                    val intro = cachedIntroRange
                    val showSkip = intro != null && pos in intro.startMs until intro.endMs - 1000
                    if (showSkip != state.showSkipIntroButton) {
                        _uiState.update { it.copy(showSkipIntroButton = showSkip) }
                    }

                    if (state.bufferedPercentage != player.bufferedPercentage) {
                        _uiState.update { it.copy(bufferedPercentage = player.bufferedPercentage) }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastDbSaveTime > 5000) {
                        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
                        lastDbSaveTime = now
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopProgressUpdate() = progressJob?.cancel()

    private fun startAbRepeatWatcher() {
        abRepeatJob?.cancel()
        abRepeatJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                if (state.isAbRepeatActive && state.abRepeatPointA != null && state.abRepeatPointB != null) {
                    val pos = exoPlayer?.currentPosition ?: 0L
                    if (pos >= state.abRepeatPointB) {
                        exoPlayer?.seekTo(state.abRepeatPointA)
                    }
                }
                delay(50)
            }
        }
    }

    private fun stopAbRepeatWatcher() = abRepeatJob?.cancel()

    private fun updateTracks() {
        val player = exoPlayer ?: return
        val audioTracks = mutableListOf<VideoTrackInfo>()
        val subtitleTracks = mutableListOf<VideoTrackInfo>()
        var selectedAudio = -1
        var selectedSubtitle = -1

        player.currentTracks.groups.forEachIndexed { index, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                val info = VideoTrackInfo(index, format.label ?: "Track ${i+1}", format.language, format.sampleMimeType, isSelected)
                
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    audioTracks.add(info)
                    if (isSelected) selectedAudio = audioTracks.size - 1
                } else if (group.type == C.TRACK_TYPE_TEXT) {
                    subtitleTracks.add(info)
                    if (isSelected) selectedSubtitle = subtitleTracks.size - 1
                }
            }
        }
        _uiState.update { it.copy(
            availableAudioTracks = audioTracks, 
            availableSubtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = selectedAudio,
            selectedSubtitleTrackIndex = selectedSubtitle
        ) }
    }

    fun togglePlayPause() = exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        positionFlow.value = position
    }

    fun stepFrame(forward: Boolean) {
        val player = exoPlayer ?: return
        if (player.isPlaying) return

        val fps = 30f
        val frameDurationMs = 1000f / fps
        val currentPos = player.currentPosition
        val duration = player.duration

        val delta = if (forward) frameDurationMs else -frameDurationMs
        val newPos = (currentPos + delta).toLong().coerceIn(0L, duration)

        player.seekTo(newPos)
        player.playWhenReady = false
        positionFlow.value = newPos
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
                applyEqGains()
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

    fun toggleTimeDisplay() {
        _uiState.update { 
            val next = !it.showTotalTime
            it.copy(showTotalTime = next) 
        }
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

    fun setSubtitleSize(s: Float) {
        prefs.edit().putFloat("video_subtitle_size", s).apply()
        _uiState.update { it.copy(subtitleSize = s) }
    }

    fun setSubtitleTextColor(c: Int) {
        prefs.edit().putInt("video_subtitle_text_color", c).apply()
        _uiState.update { it.copy(subtitleTextColor = c) }
    }

    fun setSubtitleBackgroundColor(c: Int) {
        prefs.edit().putInt("video_subtitle_bg_color", c).apply()
        _uiState.update { it.copy(subtitleBackgroundColor = c) }
    }

    fun setSubtitleOutlineColor(c: Int) {
        prefs.edit().putInt("video_subtitle_outline_color", c).apply()
        _uiState.update { it.copy(subtitleOutlineColor = c) }
    }

    fun setSubtitleAlpha(a: Float) {
        prefs.edit().putFloat("video_subtitle_alpha", a).apply()
        _uiState.update { it.copy(subtitleAlpha = a) }
    }

    fun setSubtitleOffset(o: Float) {
        prefs.edit().putFloat("video_subtitle_offset", o).apply()
        _uiState.update { it.copy(subtitleOffset = o) }
    }

    fun setSubtitleBold(b: Boolean) {
        prefs.edit().putBoolean("video_subtitle_bold", b).apply()
        _uiState.update { it.copy(subtitleBold = b) }
    }

    fun setSubtitleEdgeType(edge: Int) {
        prefs.edit().putInt("video_subtitle_edge_type", edge).apply()
        _uiState.update { it.copy(subtitleEdgeType = edge) }
    }

    fun setSubtitleBackgroundOpacity(opacity: Float) {
        prefs.edit().putFloat("video_subtitle_bg_opacity", opacity).apply()
        _uiState.update { it.copy(subtitleBackgroundOpacity = opacity) }
    }

    fun setSubtitlePositionPreset(preset: SubtitlePositionPreset) {
        prefs.edit().putString("video_subtitle_pos_preset", preset.name).apply()
        _uiState.update { it.copy(subtitlePositionPreset = preset) }
    }

    fun setSubtitleSizePercent(percent: Int) {
        val newSp = (18f * (percent / 100f)).coerceAtLeast(12f)
        prefs.edit().putInt("video_subtitle_size_percent", percent)
            .putFloat("video_subtitle_size", newSp).apply()
        _uiState.update { it.copy(subtitleSizePercent = percent, subtitleSize = newSp) }
    }

    fun resetSubtitleStyle() {
        val defaultSp = 18f
        val defaultTextColor = Color.WHITE
        val defaultBgColor = Color.TRANSPARENT
        val defaultOutlineColor = Color.BLACK
        val defaultWindowColor = Color.TRANSPARENT
        val defaultAlpha = 1.0f
        val defaultOffset = 0f
        val defaultBold = false
        val defaultEdgeType = 1
        val defaultBgOpacity = 0.0f
        val defaultPosPreset = SubtitlePositionPreset.BOTTOM
        val defaultSizePercent = 100

        prefs.edit()
            .putFloat("video_subtitle_size", defaultSp)
            .putInt("video_subtitle_text_color", defaultTextColor)
            .putInt("video_subtitle_bg_color", defaultBgColor)
            .putInt("video_subtitle_outline_color", defaultOutlineColor)
            .putInt("video_subtitle_window_color", defaultWindowColor)
            .putFloat("video_subtitle_alpha", defaultAlpha)
            .putFloat("video_subtitle_offset", defaultOffset)
            .putBoolean("video_subtitle_bold", defaultBold)
            .putInt("video_subtitle_edge_type", defaultEdgeType)
            .putFloat("video_subtitle_bg_opacity", defaultBgOpacity)
            .putString("video_subtitle_pos_preset", defaultPosPreset.name)
            .putInt("video_subtitle_size_percent", defaultSizePercent)
            .apply()

        _uiState.update {
            it.copy(
                subtitleSize = defaultSp,
                subtitleTextColor = defaultTextColor,
                subtitleBackgroundColor = defaultBgColor,
                subtitleOutlineColor = defaultOutlineColor,
                subtitleWindowColor = defaultWindowColor,
                subtitleAlpha = defaultAlpha,
                subtitleOffset = defaultOffset,
                subtitleBold = defaultBold,
                subtitleEdgeType = defaultEdgeType,
                subtitleBackgroundOpacity = defaultBgOpacity,
                subtitlePositionPreset = defaultPosPreset,
                subtitleSizePercent = defaultSizePercent
            )
        }
    }

    fun toggleOrientation(a: android.app.Activity) {
        val next = if (a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        a.requestedOrientation = next
        _uiState.update { it.copy(orientation = next) }
    }

    fun toggleBackgroundPlay() = _uiState.update { it.copy(isBackgroundPlayEnabled = !it.isBackgroundPlayEnabled) }

    fun skipIntro() {
        cachedIntroRange?.let { range ->
            seekTo(range.endMs)
            _uiState.update { it.copy(showSkipIntroButton = false) }
        }
    }

    fun toggleAbRepeat() {
        val state = _uiState.value
        when {
            state.abRepeatPointA == null -> setAbPointA()
            state.abRepeatPointB == null -> setAbPointB()
            else -> clearAbRepeat()
        }
    }

    private fun setAbPointA() {
        val pos = exoPlayer?.currentPosition ?: 0L
        _uiState.update { it.copy(abRepeatPointA = pos, abRepeatPointB = null, isAbRepeatActive = false) }
    }

    private fun setAbPointB() {
        val pos = exoPlayer?.currentPosition ?: 0L
        val pointA = _uiState.value.abRepeatPointA ?: return
        
        if (pos > pointA) {
            _uiState.update { it.copy(abRepeatPointB = pos, isAbRepeatActive = true) }
        } else {
            _uiState.update { it.copy(abRepeatPointA = pos, abRepeatPointB = pointA, isAbRepeatActive = true) }
            exoPlayer?.seekTo(pos)
        }
    }

    fun clearAbRepeat() {
        _uiState.update { it.copy(abRepeatPointA = null, abRepeatPointB = null, isAbRepeatActive = false) }
    }

    fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerMode = mode, sleepTimerRemainingMs = null) }
        
        if (mode == SleepTimerMode.OFF || mode == SleepTimerMode.END_OF_VIDEO) return
        
        val durationMs = when (mode) {
            SleepTimerMode.MIN_15 -> 15 * 60 * 1000L
            SleepTimerMode.MIN_30 -> 30 * 60 * 1000L
            SleepTimerMode.MIN_60 -> 60 * 60 * 1000L
            else -> 0L
        }
        
        sleepTimerJob = viewModelScope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                _uiState.update { it.copy(sleepTimerRemainingMs = remaining) }
                delay(1000)
                remaining -= 1000
            }
            _uiState.update { it.copy(sleepTimerRemainingMs = 0, sleepTimerMode = SleepTimerMode.OFF) }
            exoPlayer?.pause()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepTimerRemainingMs = null) }
    }

    fun setColorGrading(brightness: Float, contrast: Float, saturation: Float) {
        _uiState.update { it.copy(
            colorBrightness = brightness,
            colorContrast = contrast,
            colorSaturation = saturation
        ) }
        updateVideoEffects()
    }

    fun resetColorGrading() {
        setColorGrading(0f, 1f, 1f)
    }

    fun setForceSdrToneMapping(force: Boolean) {
        _uiState.update { it.copy(forceSdrToneMapping = force) }
        updateVideoEffects()
    }

    private fun updateVideoEffects() {
        val player = exoPlayer ?: return
        val state = _uiState.value
        
        colorGradeEffect.brightness = state.colorBrightness
        colorGradeEffect.contrast = state.colorContrast
        colorGradeEffect.saturation = state.colorSaturation
        
        val isIdentity = abs(state.colorBrightness) < 0.001f &&
                         abs(state.colorContrast - 1f) < 0.001f &&
                         abs(state.colorSaturation - 1f) < 0.001f

        if (isIdentity) {
            if (isEffectActive) {
                player.setVideoEffects(emptyList())
                isEffectActive = false
            }
        } else {
            if (!isEffectActive) {
                player.setVideoEffects(listOf(colorGradeEffect))
                isEffectActive = true
            }
        }
    }


    fun setPresentationSurface(surface: Surface?) {
        exoPlayer?.setVideoSurface(surface)
    }

    fun getPlayer(): Player? = exoPlayer

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(volumeReceiver)
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        loudnessEnhancer?.release()
        equalizer?.release()
        exoPlayer?.release()
        spriteSheet?.recycle()
        backgroundTasksTimerJob?.cancel()
        spriteLoadingJob?.cancel()
        chapterDetectionJob?.cancel()
        chapterObservationJob?.cancel()
        abRepeatJob?.cancel()
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
