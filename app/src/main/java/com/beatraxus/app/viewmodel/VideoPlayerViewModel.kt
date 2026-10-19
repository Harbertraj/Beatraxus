package com.beatraxus.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.media.audiofx.AudioEffect
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.engine.AudioEngine
import com.beatraxus.app.engine.AudioFrameUtils
import com.beatraxus.app.engine.VideoDecoderMode
import com.beatraxus.app.fpsbooster.BenchmarkState
import com.beatraxus.app.fpsbooster.BoosterBenchmarkRunner
import com.beatraxus.app.fpsbooster.BoosterPlan
import com.beatraxus.app.fpsbooster.BoosterRecommender
import com.beatraxus.app.fpsbooster.BoosterStats
import com.beatraxus.app.fpsbooster.DeviceCaps
import com.beatraxus.app.fpsbooster.FallbackReason
import com.beatraxus.app.fpsbooster.FpsBoosterPlanner
import com.beatraxus.app.fpsbooster.FpsBoosterSettings
import com.beatraxus.app.fpsbooster.FpsBoosterStore
import com.beatraxus.app.fpsbooster.GlCapabilityProbe
import com.beatraxus.app.fpsbooster.SourceInfo
import com.beatraxus.app.motionboost.DisplayRefreshRate
import com.beatraxus.app.engine.VideoPlaybackTuning
import com.beatraxus.app.engine.VideoRenderersFactory
import com.beatraxus.app.engine.VideoRoutePhase
import com.beatraxus.app.service.VideoAudioBridge
import com.beatraxus.app.model.DEFAULT_SURROUND_TRIMS_DB
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.SURROUND_CUSTOM_PRESET_NAME
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.SurroundMode
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.sanitizeSurroundTrims
import com.beatraxus.app.model.withSurroundPreset
import com.beatraxus.app.model.withSurroundTrim
import com.beatraxus.app.model.withSurroundTrimsReset
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
import com.beatraxus.app.colorgrade.ColorGradeEffect
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val isLooping: Boolean = false,
    val sleepTimerRemainingMs: Long? = null,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val showSkipIntroButton: Boolean = false,
    val scrubbingThumbnail: android.graphics.Bitmap? = null,
    val scrubbingTimeMs: Long? = null,
    val chapters: List<com.beatraxus.app.model.VideoChapterEntity> = emptyList(),
    val colorBrightness: Float = 0f,
    val colorContrast: Float = 1f,
    val colorSaturation: Float = 1f,
    val forceSdrToneMapping: Boolean = false,
    val routeAudioToEngine: Boolean = false,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.DEFAULT,
    /** Audio delay for the current video in ms. Positive = audio later than video. Resets per video. */
    val audioDelayMs: Int = 0,
    /** FPS Booster user preferences. Persisted. */
    val fpsBooster: FpsBoosterSettings = FpsBoosterSettings(),
    /** What the booster will actually do for this video on this device (derived; see [FpsBoosterPlanner]). */
    val fpsBoosterPlan: BoosterPlan = BoosterPlan.inactive("Off"),
    /** Live statistics while the booster runs. */
    val fpsBoosterStats: BoosterStats = BoosterStats(),
    /** Set when the booster stopped itself this session (device too slow / hot / GPU error). Cleared on the next video. */
    val fpsBoosterSuspended: String? = null,
    /** One-time CPU/GPU device test behind the recommended booster settings. */
    val fpsBoosterBenchmark: BenchmarkState = BenchmarkState.Idle,
    /** The user dismissed the first-run test, so the sheet must not start it on its own again. */
    val fpsBoosterBenchmarkSkipped: Boolean = false,
    /** Frame rate declared by the video stream (0 = unknown). */
    val sourceFps: Float = 0f,
    val isConnectingEngineRoute: Boolean = false,
    val engineRouteError: String? = null,
    /**
     * With [routeAudioToEngine]: true = the "5.1 engine" route (surround simulator only, no Studio DSP effects),
     * false = the "Connect to audio mode" route (whole Studio DSP chain).
     */
    val surroundEngineRoute: Boolean = false,
    /** The Audio Engine confirms that the surround simulator is really in this video's signal path. Drives the top icon. */
    val surroundActive: Boolean = false,
    /** The soundtrack is a real 5.1 / 7.1 mix: its discrete channels are kept, nothing is upmixed. */
    val surroundDiscrete: Boolean = false,
    /** Channel count of the soundtrack that reaches the engine (0 = unknown). */
    val surroundSourceChannels: Int = 0,
    /** Channel count the output device is really opened with (2 = stereo, 6 = 5.1, 8 = 7.1). */
    val surroundOutputChannels: Int = 2,
    /** The output device refused a multichannel stream, so the soundtrack plays in its own layout. */
    val surroundFallback: Boolean = false,
    val surroundSettings: VideoSurroundSettings = VideoSurroundSettings(),
    /** The surround icon just appeared for the first time ever: show the "new" hint on it. */
    val showSurroundHint: Boolean = false
)

/** The part of the Studio DSP surround settings the video player's surround sheet edits. */
data class VideoSurroundSettings(
    val layout: SurroundLayout = SurroundLayout.FIVE_ONE,
    val mode: SurroundMode = SurroundMode.MOVIE,
    /** Eight values in order L R C SL SR BL BR SW. */
    val trimsDb: List<Float> = DEFAULT_SURROUND_TRIMS_DB,
    val activePreset: String = "Default",
    val customPresetSaved: Boolean = false
)

private fun DspConfig.toVideoSurroundSettings() = VideoSurroundSettings(
    layout = surroundLayout,
    mode = surroundMode,
    trimsDb = sanitizeSurroundTrims(surroundChannelTrimsDb),
    activePreset = surroundActivePreset,
    customPresetSaved = surroundCustomTrimsDb != null
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
    val isSelected: Boolean,
    val audioOrdinal: Int = 0,
    val channelCount: Int = -1,
    val sampleRate: Int = -1
)

enum class VideoAspectRatio(val displayName: String) {
    FIT("Fit to screen"),
    FILL("Fill screen"),
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
    private var telegramDataSourceFactory: com.beatraxus.app.telegram.TelegramAwareDataSourceFactory? = null
    private val database = (application as BeatraxusApplication).database
    private val videoRecentlyPlayedDao = database.videoRecentlyPlayedDao()
    private val videoChapterDao = database.videoChapterDao()
    private val introOutroDao = database.introOutroDao()
    private val dspPreferences = com.beatraxus.app.repository.DspPreferences(application)
    private val introOutroDetector = com.beatraxus.app.engine.IntroOutroDetector(application)
    private val sceneChangeDetector = com.beatraxus.app.engine.SceneChangeDetector(application)
    private var cachedIntroRange: com.beatraxus.app.model.IntroOutroRange? = null
    private val prefs = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)
    // Read live by VideoRenderersFactory: decoder mode is persisted, audio delay is per video.
    private val tuning = VideoPlaybackTuning(
        decoderMode = VideoDecoderMode.fromName(prefs.getString(PREF_VIDEO_DECODER_MODE, null))
    )
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
                    updateLoudness()
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(VideoPlayerUiState(
        volume = (application.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC),
        maxVolume = (application.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        isEqEnabled = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getBoolean("video_eq_enabled", false),
        videoDecoderMode = tuning.decoderMode,
        fpsBooster = FpsBoosterStore.load(application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)),
        // A result from an earlier run is shown again instead of testing every time. Read synchronously so the
        // sheet never mistakes "not loaded yet" for "never tested" and starts the test a second time.
        fpsBoosterBenchmark = FpsBoosterStore.loadBenchmark(application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE))
            ?.let { BenchmarkState.Done(it, BoosterRecommender.recommend(it, DisplayRefreshRate.supportedRates(application))) }
            ?: BenchmarkState.Idle,
        fpsBoosterBenchmarkSkipped = FpsBoosterStore.isBenchmarkSkipped(application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE)),
        aspectRatio = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getString("video_aspect_ratio", VideoAspectRatio.FIT.name)?.let { name ->
            val mappedName = when (name) {
                "ORIGINAL" -> "FIT"
                "STRETCH" -> "FILL"
                "CROP" -> "ZOOM"
                else -> name
            }
            VideoAspectRatio.entries.find { it.name == mappedName }
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

    // Audio Engine routing
    private val RESET_ROUTE_AUDIO_ON_VIDEO_CHANGE = true
    private var videoAudioSyncJob: Job? = null
    private var videoAudioStateObservationJob: Job? = null
    private var savedAudioTrackOverride: TrackSelectionOverride? = null
    private var savedAudioTrackIndex: Int = -1

    // Surround simulator (5.1 engine / audio mode)
    private var surroundStateJob: Job? = null
    @Volatile private var latestDspConfig: DspConfig = DspConfig()
    // Own scope: a trim change made just before leaving the player must still reach the disk.
    private val surroundSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var surroundSaveJob: Job? = null

    init {
        application.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        if (videoQueue.isNotEmpty()) {
            val initialIndex = videoQueue.indexOfFirst { it.id == initialVideoId }.coerceAtLeast(0)
            setupPlayer(initialIndex)
        }

        loadCustomPresets()
        loadIntroRange()
        probeDeviceForFpsBooster()
        observeSurroundSettings()
    }

    // ---- FPS Booster ----

    @Volatile private var deviceCaps: DeviceCaps? = null

    /** GPU / panel capabilities are probed once, off the main thread; the plan is (re)computed when they arrive. */
    private fun probeDeviceForFpsBooster() {
        viewModelScope.launch(Dispatchers.Default) {
            val caps = runCatching { GlCapabilityProbe.query(getApplication()) }.getOrNull()
            deviceCaps = caps
            _uiState.update { withFpsPlan(it) }

        }
    }

    private var benchmarkJob: Job? = null

    /**
     * Runs the CPU + GPU device test (about 3 s), stores the result and applies the recommended engine,
     * quality and target FPS. Playback is paused meanwhile so the video does not disturb the GPU timings.
     */
    fun startFpsBoosterBenchmark() {
        if (benchmarkJob?.isActive == true) return
        val wasPlaying = exoPlayer?.isPlaying == true
        if (wasPlaying) exoPlayer?.pause()
        _uiState.update { it.copy(fpsBoosterBenchmark = BenchmarkState.Running(0f, "Starting")) }
        benchmarkJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = BoosterBenchmarkRunner.run(
                    getApplication(),
                    isCancelled = { !isActive },
                    onProgress = { f, label ->
                        _uiState.update { st ->
                            if (st.fpsBoosterBenchmark is BenchmarkState.Running) st.copy(fpsBoosterBenchmark = BenchmarkState.Running(f, label)) else st
                        }
                    }
                )
                FpsBoosterStore.saveBenchmark(prefs, result)
                val rec = BoosterRecommender.recommend(result, DisplayRefreshRate.supportedRates(getApplication()))
                _uiState.update {
                    withFpsPlan(it.copy(
                        fpsBooster = rec.applyTo(it.fpsBooster),
                        fpsBoosterSuspended = null,
                        fpsBoosterBenchmark = BenchmarkState.Done(result, rec),
                        fpsBoosterBenchmarkSkipped = false
                    ))
                }
                FpsBoosterStore.save(prefs, _uiState.value.fpsBooster)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(fpsBoosterBenchmark = BenchmarkState.Idle) }
                throw e
            } catch (e: InterruptedException) {
                _uiState.update { it.copy(fpsBoosterBenchmark = BenchmarkState.Idle) }
            } catch (t: Throwable) {
                Log.w("VideoPlayerViewModel", "FPS booster device test failed", t)
                _uiState.update { it.copy(fpsBoosterBenchmark = BenchmarkState.Failed("The device test could not finish. You can still choose settings by hand.")) }
            } finally {
                if (wasPlaying) withContext(NonCancellable + Dispatchers.Main) { exoPlayer?.play() }
            }
        }
    }

    /** Stops a running test. A first-run test that is skipped is not started again automatically. */
    fun skipFpsBoosterBenchmark() {
        benchmarkJob?.cancel()
        FpsBoosterStore.setBenchmarkSkipped(prefs)
        _uiState.update { it.copy(fpsBoosterBenchmarkSkipped = true, fpsBoosterBenchmark = BenchmarkState.Idle) }
    }

    /** Re-derives [VideoPlayerUiState.fpsBoosterPlan] from settings + device + video. Returns the same instance if nothing changed. */
    private fun withFpsPlan(s: VideoPlayerUiState): VideoPlayerUiState {
        val caps = deviceCaps
        val plan = when {
            !s.fpsBooster.isOn -> BoosterPlan.inactive("Off")
            s.fpsBoosterSuspended != null -> BoosterPlan.inactive(s.fpsBoosterSuspended)
            caps == null -> BoosterPlan.inactive("Checking device")
            else -> FpsBoosterPlanner.plan(s.fpsBooster, caps, SourceInfo(s.sourceFps, s.videoSize.width, s.videoSize.height, s.isHdr))
        }
        return if (plan == s.fpsBoosterPlan) s else s.copy(fpsBoosterPlan = plan, fpsBoosterStats = BoosterStats())
    }

    private fun loadIntroRange() {
        // A queue of channels has no intros to find.
        if (videoQueue.firstOrNull()?.source == com.beatraxus.app.model.VideoSource.LIVE_TV) return
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

        // A brand-new player has NO video effects yet. Without this reset a stale `true`
        // from a previous player made updateVideoEffects() skip setVideoEffects() forever.
        isEffectActive = false

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(context, VideoRenderersFactory(context, tuning))
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    // Same as before for every normal Uri; `tgvideo://` Telegram videos are streamed through TDLib
                    // and `cloudvideo://` cloud-drive videos (Drive, Dropbox, OneDrive, Box, Nextcloud) are
                    // resolved to an authorised https address first.
                    com.beatraxus.app.telegram.TelegramAwareDataSourceFactory(
                        (context.applicationContext as BeatraxusApplication).tdLibManager,
                        com.beatraxus.app.drive.CloudVideoDataSource.wrap(
                            context,
                            com.beatraxus.app.addons.AddonStreamHeaders.dataSourceFactory(context)
                        )
                    ).also { telegramDataSourceFactory = it }
                )
            )
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItems(videoQueue.map { v ->
                    MediaItem.Builder().setUri(v.uri).setMediaId(v.id).apply {
                        // Live TV channels: some playlists are served from addresses without a .m3u8 extension.
                        if (v.source == com.beatraxus.app.model.VideoSource.LIVE_TV) {
                            com.beatraxus.app.livetv.LiveTvPlayback.mimeTypeFor(v.uri)?.let { setMimeType(it) }
                        }
                    }.build()
                })
                
                viewModelScope.launch(Dispatchers.IO) {
                    val recentlyPlayed = videoRecentlyPlayedDao.getRecentlyPlayedByVideoId(video.id)
                    val lastPos = recentlyPlayed?.lastPositionMs ?: 0L
                    val lastRatio = recentlyPlayed?.lastAspectRatio?.let { ratioName ->
                        val mappedName = when (ratioName) {
                            "ORIGINAL" -> "FIT"
                            "STRETCH" -> "FILL"
                            "CROP" -> "ZOOM"
                            else -> ratioName
                        }
                        VideoAspectRatio.entries.find { it.name == mappedName }
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
                PlaybackGlobalState.setPlaybackActive(isPlaying)
                if (_uiState.value.routeAudioToEngine) {
                    val engine = VideoAudioBridge.getAudioEngine()
                    if (isPlaying) engine?.resumeVideoAudio() else engine?.pauseVideoAudio()
                }
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
                if (_uiState.value.routeAudioToEngine) {
                    val engine = VideoAudioBridge.getAudioEngine()
                    if (playbackState == Player.STATE_BUFFERING) {
                        engine?.pauseVideoAudio()
                    } else if (playbackState == Player.STATE_READY && exoPlayer?.isPlaying == true) {
                        engine?.resumeVideoAudio()
                    } else if (playbackState == Player.STATE_ENDED) {
                        setRouteAudioToEngine(false)
                    }
                }
                if (playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(duration = player.duration) }
                    reportStreamedVideoMetadata(player)
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

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Only Live TV gets special handling; every other video behaves exactly as before.
                val live = _uiState.value.currentVideo
                    ?.takeIf { it.source == com.beatraxus.app.model.VideoSource.LIVE_TV } ?: return
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Fell behind the live window of an HLS stream: jump back to the live edge.
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                Log.w(TAG, "Live channel ${live.title} failed: ${error.errorCodeName}")
                android.widget.Toast.makeText(
                    context,
                    "${live.title} isn't available right now. Try another channel.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
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
                val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f
                _uiState.update { withFpsPlan(it.copy(videoSize = videoSize, sourceFps = if (fps > 0f) fps else it.sourceFps)) }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateTracks()
                player.videoFormat?.frameRate?.takeIf { it > 0f }?.let { fps ->
                    _uiState.update { withFpsPlan(it.copy(sourceFps = fps)) }
                }
                
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
        _uiState.update { withFpsPlan(it.copy(currentVideo = video, isHdr = video?.isHdr ?: false)) }
        
        if (video?.id != lastHandledVideoId) {
            lastHandledVideoId = video?.id
            // A booster that stopped itself on the previous video gets a fresh chance on the next one.
            if (_uiState.value.fpsBoosterSuspended != null) {
                _uiState.update { withFpsPlan(it.copy(fpsBoosterSuspended = null)) }
            }
            // Audio delay is tuned per file, so a new video starts back in sync.
            if (tuning.audioDelayMs != 0) setAudioDelayMs(0)
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

    /**
     * Streamed videos (Telegram files, cloud drives) often have no runtime in their listing. Once the
     * player knows the real one, hand it to the library so the card shows it without another probe.
     */
    private fun reportStreamedVideoMetadata(player: ExoPlayer) {
        val video = _uiState.value.currentVideo ?: return
        if (!video.isCloud) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val size = player.videoSize
        val needsDuration = video.durationMs <= 0L
        val needsSize = video.resolutionHeight <= 0 && size.height > 0
        if (!needsDuration && !needsSize) return
        com.beatraxus.app.repository.CloudVideoMetadataBus.updates.tryEmit(
            com.beatraxus.app.repository.CloudVideoMetadataBus.Update(
                videoId = video.id,
                durationMs = if (needsDuration) duration else video.durationMs,
                width = if (needsSize) size.width else video.resolutionWidth,
                height = if (needsSize) size.height else video.resolutionHeight
            )
        )
    }

    private fun checkBackgroundTasks() {
        val player = exoPlayer ?: return
        val currentVideo = _uiState.value.currentVideo ?: return
        val videoId = currentVideo.id

        // Scrub previews and chapter detection decode the file directly, which streamed videos can't offer.
        if (currentVideo.isCloud) return

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

        // Always record so the home card shows the LAST PLAYED video.
        // Finished videos (>95%) restart from the beginning next time.
        val progress = currentPos.toDouble() / duration.toDouble()
        val savedPos = if (progress > 0.95) 0L else currentPos

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

        // Own scope: viewModelScope is already cancelled when onCleared() runs.
        saveScope.launch {
            videoRecentlyPlayedDao.addRecentlyPlayed(
                VideoRecentlyPlayedEntity(
                    videoId = video.id,
                    timestamp = System.currentTimeMillis(),
                    lastPositionMs = savedPos,
                    durationMs = duration,
                    lastAspectRatio = currentRatio,
                    lastAudioTrackIndex = audioIdx,
                    lastSubtitleTrackIndex = subtitleIdx
                )
            )
        }
    }

    private var lastDbSaveTime = 0L
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call when leaving the player screen so "last played" + position are stored. */
    fun saveProgressNow() {
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
    }

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

        var currentAudioOrdinal = 0
        player.currentTracks.groups.forEachIndexed { index, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    val ordinal = currentAudioOrdinal++
                    val info = VideoTrackInfo(
                        index = index,
                        name = format.label ?: "Track ${ordinal + 1}",
                        language = format.language,
                        format = format.sampleMimeType,
                        isSelected = isSelected,
                        audioOrdinal = ordinal,
                        channelCount = format.channelCount,
                        sampleRate = format.sampleRate
                    )
                    audioTracks.add(info)
                    if (isSelected) selectedAudio = ordinal
                } else if (group.type == C.TRACK_TYPE_TEXT) {
                    val info = VideoTrackInfo(
                        index = index,
                        name = format.label ?: "Track ${subtitleTracks.size + 1}",
                        language = format.language,
                        format = format.sampleMimeType,
                        isSelected = isSelected,
                        audioOrdinal = -1
                    )
                    subtitleTracks.add(info)
                    if (isSelected) selectedSubtitle = subtitleTracks.size - 1
                }
            }
        }

        val isRouted = _uiState.value.routeAudioToEngine
        val effectiveSelectedAudio = if (isRouted) {
            savedAudioTrackIndex.takeIf { it in audioTracks.indices } ?: _uiState.value.selectedAudioTrackIndex
        } else {
            selectedAudio
        }

        val updatedAudioTracks = if (isRouted && effectiveSelectedAudio >= 0) {
            audioTracks.map { track ->
                track.copy(isSelected = (track.audioOrdinal == effectiveSelectedAudio))
            }
        } else {
            audioTracks
        }

        _uiState.update { it.copy(
            availableAudioTracks = updatedAudioTracks, 
            availableSubtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = effectiveSelectedAudio,
            selectedSubtitleTrackIndex = selectedSubtitle
        ) }
    }

    fun togglePlayPause() = exoPlayer?.let { p ->
        val nextPlaying = !p.isPlaying
        if (_uiState.value.routeAudioToEngine) {
            val engine = VideoAudioBridge.getAudioEngine()
            if (nextPlaying) {
                engine?.resumeVideoAudio()
                p.play()
            } else {
                p.pause()
                engine?.pauseVideoAudio()
            }
        } else {
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        positionFlow.value = position
        if (_uiState.value.routeAudioToEngine) {
            VideoAudioBridge.getAudioEngine()?.seekVideoAudio(position)
        }
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
        if (speed != 1.0f && _uiState.value.routeAudioToEngine) {
            setRouteAudioToEngine(false)
        }
        exoPlayer?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    /**
     * Chooses the video decoder (HW / HW+ / SW) and saves it for future videos.
     * The player keeps its surface and queue: it is stopped and prepared again at the same
     * position so the codec is re-created with the new choice. While audio is routed to the
     * Audio Engine the restart is skipped (it would desync the engine), so the choice applies
     * the next time playback starts.
     */
    fun setVideoDecoderMode(mode: VideoDecoderMode) {
        if (mode == tuning.decoderMode) return
        tuning.decoderMode = mode
        prefs.edit().putString(PREF_VIDEO_DECODER_MODE, mode.name).apply()
        _uiState.update { it.copy(videoDecoderMode = mode) }

        val player = exoPlayer ?: return
        if (_uiState.value.routeAudioToEngine || player.playbackState == Player.STATE_IDLE) return
        // currentPosition includes the audio-delay clock shift, so take it back out.
        val positionMs = (player.currentPosition - tuning.audioDelayMs).coerceAtLeast(0L)
        val resumePlayback = player.playWhenReady
        player.stop()
        player.seekTo(player.currentMediaItemIndex, positionMs)
        player.prepare()
        player.playWhenReady = resumePlayback
    }

    /** Switches to the next decoder mode (HW -> HW+ -> SW -> HW) and returns it. */
    fun cycleVideoDecoderMode(): VideoDecoderMode {
        val modes = VideoDecoderMode.entries
        val next = modes[(modes.indexOf(tuning.decoderMode) + 1) % modes.size]
        setVideoDecoderMode(next)
        return next
    }

    /** Delays (positive) or advances (negative) the audio relative to the video. Not available with Audio Engine routing. */
    fun setAudioDelayMs(delayMs: Int) {
        val clamped = VideoPlaybackTuning.clampAudioDelay(delayMs)
        tuning.audioDelayMs = clamped
        _uiState.update { it.copy(audioDelayMs = clamped) }
    }

    fun adjustAudioDelayMs(deltaMs: Int) = setAudioDelayMs(_uiState.value.audioDelayMs + deltaMs)

    /** Saves new FPS Booster preferences and re-plans. Choosing settings again also lifts a session fallback. */
    fun setFpsBoosterSettings(settings: FpsBoosterSettings) {
        if (settings == _uiState.value.fpsBooster) return
        FpsBoosterStore.save(prefs, settings)
        _uiState.update { withFpsPlan(it.copy(fpsBooster = settings, fpsBoosterSuspended = null)) }
    }

    fun updateFpsBoosterStats(stats: BoosterStats) {
        _uiState.update { it.copy(fpsBoosterStats = stats) }
    }

    /** The booster could not keep up / overheated / hit a GPU error: play normally for the rest of this video. */
    fun onFpsBoosterFallback(reason: FallbackReason) {
        _uiState.update {
            withFpsPlan(it.copy(fpsBoosterSuspended = reason.message, fpsBoosterStats = BoosterStats(fallbackReason = reason.message)))
        }
    }

    fun setAspectRatio(ratio: VideoAspectRatio) {
        _uiState.update { it.copy(aspectRatio = ratio, aspectRatioMessage = ratio.displayName) }
        viewModelScope.launch { delay(2000); _uiState.update { it.copy(aspectRatioMessage = null) } }
    }

    fun selectAudioTrack(track: VideoTrackInfo) {
        exoPlayer?.let { p ->
            val trackGroup = p.currentTracks.groups.getOrNull(track.index)
            if (trackGroup != null) {
                val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, 0)
                savedAudioTrackOverride = override
                savedAudioTrackIndex = track.audioOrdinal

                _uiState.update { state ->
                    state.copy(
                        selectedAudioTrackIndex = track.audioOrdinal,
                        availableAudioTracks = state.availableAudioTracks.map { t ->
                            t.copy(isSelected = (t.audioOrdinal == track.audioOrdinal))
                        }
                    )
                }

                if (_uiState.value.routeAudioToEngine) {
                    val engine = VideoAudioBridge.getAudioEngine()
                    if (engine != null) {
                        startEngineRouting(engine)
                    }
                } else {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(override)
                        .build()
                }
            }
        }
    }

    /**
     * "Connect to audio mode" switch: routes the soundtrack through the Audio Engine with the whole Studio DSP chain.
     * Turning it ON while the 5.1 engine is active switches over to this mode; turning it OFF always stops routing.
     */
    fun setRouteAudioToEngine(enabled: Boolean) = setEngineRoute(enabled, surroundEngine = false)

    /**
     * "5.1 engine" switch: routes the soundtrack through the Audio Engine with the surround simulator only (no Studio
     * DSP effects). Turning it ON while audio mode is active switches over to this mode; turning it OFF only has an
     * effect while the 5.1 engine itself is the active route.
     */
    fun setSurroundEngine(enabled: Boolean) = setEngineRoute(enabled, surroundEngine = true)

    private fun setEngineRoute(enabled: Boolean, surroundEngine: Boolean) {
        val current = _uiState.value
        if (enabled) {
            if (current.routeAudioToEngine && !current.isConnectingEngineRoute && current.surroundEngineRoute == surroundEngine) return
        } else {
            if (!current.routeAudioToEngine && !current.isConnectingEngineRoute) return
            // The 5.1 engine switch was turned off while audio mode is the running route: nothing to stop.
            if (surroundEngine && !current.surroundEngineRoute) return
        }

        if (enabled) {
            if (current.currentVideo?.isCloud == true) {
                _uiState.update { it.copy(engineRouteError = "Audio Engine routing isn't available for cloud videos", isConnectingEngineRoute = false) }
                return
            }
            if (current.availableAudioTracks.isEmpty()) {
                _uiState.update { it.copy(engineRouteError = "No audio tracks available to route", isConnectingEngineRoute = false) }
                return
            }
            if (current.playbackSpeed != 1.0f) {
                _uiState.update { it.copy(engineRouteError = "Audio Engine routing requires 1.0x playback speed", isConnectingEngineRoute = false) }
                return
            }

            if (current.routeAudioToEngine) {
                // Already routed: only the flavour changes (audio mode <-> 5.1 engine), so restart the route with it.
                val engine = VideoAudioBridge.getAudioEngine()
                if (engine == null) {
                    _uiState.update { it.copy(engineRouteError = "Audio Engine is not running") }
                    return
                }
                _uiState.update {
                    it.copy(
                        surroundEngineRoute = surroundEngine,
                        surroundActive = false,
                        surroundDiscrete = false,
                        engineRouteError = null
                    )
                }
                startEngineRouting(engine)
                return
            }

            _uiState.update { it.copy(isConnectingEngineRoute = true, surroundEngineRoute = surroundEngine, engineRouteError = null) }

            viewModelScope.launch {
                val context = getApplication<Application>()
                val engine = VideoAudioBridge.awaitAudioEngine(context, timeoutMs = 3000L)
                if (engine == null) {
                    _uiState.update {
                        it.copy(
                            routeAudioToEngine = false,
                            isConnectingEngineRoute = false,
                            surroundEngineRoute = false,
                            engineRouteError = "Audio Engine is not running"
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(routeAudioToEngine = true, engineRouteError = null) }
                startEngineRouting(engine)
            }
        } else {
            _uiState.update {
                it.copy(
                    routeAudioToEngine = false,
                    isConnectingEngineRoute = false,
                    engineRouteError = null,
                    surroundEngineRoute = false,
                    surroundActive = false,
                    surroundDiscrete = false,
                    surroundSourceChannels = 0,
                    surroundOutputChannels = 2,
                    surroundFallback = false
                )
            }
            stopEngineRouting(restoreExoAudio = true)
        }
    }

    // ---- Surround simulator (video player sheet) ----

    /** Keeps [VideoPlayerUiState.surroundSettings] in step with the Studio DSP settings the sheet edits. */
    private fun observeSurroundSettings() {
        viewModelScope.launch {
            dspPreferences.dspConfig.collect { config ->
                latestDspConfig = config
                val settings = config.toVideoSurroundSettings()
                _uiState.update { if (it.surroundSettings == settings) it else it.copy(surroundSettings = settings) }
            }
        }
    }

    /**
     * Mirrors what the engine really does with the soundtrack: surround in the path or not, real 5.1 or upmixed, and
     * how wide the output is. The top icon only exists while the engine says the simulator is in the pipeline.
     */
    private fun observeSurroundState(engine: AudioEngine) {
        surroundStateJob?.cancel()
        surroundStateJob = viewModelScope.launch {
            combine(engine.audioStateFlow, engine.videoRouteStateFlow) { audio, route ->
                SurroundLive(
                    active = route.isActive && audio.surroundActive,
                    discrete = audio.surroundDiscrete,
                    sourceChannels = audio.sourceChannels,
                    outputChannels = audio.outputChannels,
                    fallback = audio.surroundFallback
                )
            }.distinctUntilChanged().collect { live ->
                _uiState.update {
                    if (!it.routeAudioToEngine) it else it.copy(
                        surroundActive = live.active,
                        surroundDiscrete = live.discrete && live.active,
                        surroundSourceChannels = live.sourceChannels,
                        surroundOutputChannels = live.outputChannels,
                        surroundFallback = live.fallback
                    )
                }
                if (live.active && !prefs.getBoolean(PREF_SURROUND_HINT_SEEN, false)) {
                    _uiState.update { it.copy(showSurroundHint = true) }
                }
            }
        }
    }

    private data class SurroundLive(
        val active: Boolean,
        val discrete: Boolean,
        val sourceChannels: Int,
        val outputChannels: Int,
        val fallback: Boolean
    )

    /** The surround icon was on screen long enough (or tapped): the "new" hint never shows again. */
    fun onSurroundHintShown() {
        prefs.edit().putBoolean(PREF_SURROUND_HINT_SEEN, true).apply()
        _uiState.update { it.copy(showSurroundHint = false) }
    }

    private fun applySurroundConfig(transform: (DspConfig) -> DspConfig) {
        val updated = transform(latestDspConfig)
        if (updated == latestDspConfig) return
        latestDspConfig = updated
        _uiState.update { it.copy(surroundSettings = updated.toVideoSurroundSettings()) }
        // Live: the engine picks it up immediately; the saved copy follows after a short debounce.
        VideoAudioBridge.getAudioEngine()?.updateDspConfig(updated)
        surroundSaveJob?.cancel()
        surroundSaveJob = surroundSaveScope.launch {
            delay(500)
            dspPreferences.saveConfig(updated, dspPreferences.getCurrentDeviceId())
        }
    }

    fun setSurroundLayout(layout: SurroundLayout) = applySurroundConfig { it.copy(surroundLayout = layout) }

    fun setSurroundMode(mode: SurroundMode) = applySurroundConfig { it.copy(surroundMode = mode) }

    fun setSurroundChannelTrim(index: Int, db: Float) = applySurroundConfig { it.withSurroundTrim(index, db) }

    fun resetSurroundTrims() = applySurroundConfig { it.withSurroundTrimsReset() }

    fun applySurroundPreset(name: String) = applySurroundConfig { it.withSurroundPreset(name) }

    fun saveSurroundCustomPreset() = applySurroundConfig {
        it.copy(
            surroundCustomTrimsDb = sanitizeSurroundTrims(it.surroundChannelTrimsDb),
            surroundCustomMode = it.surroundMode,
            surroundActivePreset = SURROUND_CUSTOM_PRESET_NAME
        )
    }

    private fun startEngineRouting(engine: AudioEngine) {
        val player = exoPlayer ?: run {
            _uiState.update { it.copy(routeAudioToEngine = false, isConnectingEngineRoute = false, surroundEngineRoute = false) }
            return
        }
        val video = _uiState.value.currentVideo ?: run {
            _uiState.update { it.copy(routeAudioToEngine = false, isConnectingEngineRoute = false, surroundEngineRoute = false) }
            return
        }

        try {
            equalizer?.enabled = false
            loudnessEnhancer?.enabled = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable local EQ during engine routing", e)
        }

        val currentTracks = player.currentTracks
        var currentOverride: TrackSelectionOverride? = null
        var selectedTrackIdx = _uiState.value.selectedAudioTrackIndex
        if (selectedTrackIdx < 0) selectedTrackIdx = 0

        currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                currentOverride = TrackSelectionOverride(group.mediaTrackGroup, 0)
            }
        }
        if (currentOverride != null) {
            savedAudioTrackOverride = currentOverride
        }
        savedAudioTrackIndex = selectedTrackIdx

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()

        val currentPosMs = player.currentPosition
        val wasPlaying = player.isPlaying
        if (wasPlaying) {
            player.pause()
        }

        val selectedTrackInfo = _uiState.value.availableAudioTracks.find { it.audioOrdinal == selectedTrackIdx }

        _uiState.update { it.copy(isConnectingEngineRoute = true, engineRouteError = null) }

        engine.playVideoAudio(
            uri = video.uri,
            audioTrackIndex = selectedTrackIdx,
            startPositionMs = currentPosMs,
            startPlaying = wasPlaying,
            title = video.title,
            codecMime = selectedTrackInfo?.format,
            surroundEngine = _uiState.value.surroundEngineRoute
        )
        observeSurroundState(engine)

        videoAudioStateObservationJob?.cancel()
        videoAudioStateObservationJob = viewModelScope.launch {
            var hasBeenActive = false
            var exoResumed = false
            val timeoutJob = launch {
                delay(8000) // Increased to 8s
                if (!hasBeenActive && isActive) {
                    Log.w(TAG, "Video audio routing start timed out after 8s")
                    _uiState.update { it.copy(isConnectingEngineRoute = false, engineRouteError = "Audio Engine connection timed out") }
                    setRouteAudioToEngine(false)
                }
            }

            engine.videoRouteStateFlow.collect { routeState ->
                Log.d(TAG, "Observed VideoRouteState: phase=${routeState.phase}, error=${routeState.error}, audioReady=${routeState.firstFrameRendered}")
                
                if (routeState.firstFrameRendered && !exoResumed && routeState.phase == VideoRoutePhase.ACTIVE) {
                    exoResumed = true
                    hasBeenActive = true
                    timeoutJob.cancel()
                    _uiState.update { it.copy(isConnectingEngineRoute = false, engineRouteError = null) }
                    
                    val engineStartPos = routeState.positionMs
                    player.seekTo(engineStartPos)
                    if (wasPlaying) {
                        player.play()
                    }
                }

                when (routeState.phase) {
                    VideoRoutePhase.STARTING -> {
                        // Ignore STARTING
                    }
                    VideoRoutePhase.ACTIVE -> {
                        if (routeState.firstFrameRendered) {
                            hasBeenActive = true
                            timeoutJob.cancel()
                            _uiState.update { it.copy(isConnectingEngineRoute = false, engineRouteError = null) }
                        }
                    }
                    VideoRoutePhase.ERROR -> {
                        timeoutJob.cancel()
                        _uiState.update { it.copy(isConnectingEngineRoute = false, engineRouteError = routeState.error ?: "Engine routing failed") }
                        setRouteAudioToEngine(false)
                    }
                    VideoRoutePhase.IDLE -> {
                        if (hasBeenActive) {
                            timeoutJob.cancel()
                            _uiState.update { it.copy(isConnectingEngineRoute = false) }
                        }
                    }
                }
            }
        }

        startVideoAudioSyncLoop()
    }

    private fun stopEngineRouting(restoreExoAudio: Boolean = true) {
        surroundStateJob?.cancel()
        surroundStateJob = null
        videoAudioSyncJob?.cancel()
        videoAudioSyncJob = null
        videoAudioStateObservationJob?.cancel()
        videoAudioStateObservationJob = null

        val engine = VideoAudioBridge.getAudioEngine()
        engine?.stopVideoAudio()

        val player = exoPlayer
        if (restoreExoAudio && player != null) {
            var builder = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)

            val override = savedAudioTrackOverride
            if (override != null) {
                builder = builder.setOverrideForType(override)
            }
            player.trackSelectionParameters = builder.build()

            applyEqGains()
            updateLoudness()
        }
    }

    private fun startVideoAudioSyncLoop() {
        videoAudioSyncJob?.cancel()
        videoAudioSyncJob = viewModelScope.launch {
            var lastLogMs = 0L
            var consecutiveDriftCount = 0
            var lastResyncTimeMs = 0L

            while (isActive && _uiState.value.routeAudioToEngine) {
                delay(500)
                val player = exoPlayer ?: break
                val engine = VideoAudioBridge.getAudioEngine() ?: break

                if (!engine.isVideoAudioActive()) break

                if (_uiState.value.isConnectingEngineRoute) {
                    consecutiveDriftCount = 0
                    continue
                }

                val now = System.currentTimeMillis()

                if (player.isPlaying && player.playbackState == Player.STATE_READY) {
                    val exoPos = player.currentPosition
                    val audioPos = engine.videoAudioPositionMs()

                    val latencyFrames = engine.videoAudioLatencyFrames()
                    val sampleRate = engine.audioStateFlow.value.outputSampleRate.takeIf { it > 0 } ?: 48000
                    val latencyMs = (latencyFrames * 1000L) / sampleRate

                    val driftMs = AudioFrameUtils.calculateDriftMs(exoPos, audioPos, latencyMs)

                    if (now - lastLogMs >= 1000L) {
                        lastLogMs = now
                        val codec = engine.audioStateFlow.value.codec
                        val underruns = engine.audioStateFlow.value.underrunCount
                        Log.d(
                            "VideoAudioRouting",
                            "VideoAudioRouting Sync: drift=${driftMs}ms, exoPos=${exoPos}ms, audioPos=${audioPos}ms, " +
                            "latency=${latencyMs}ms, rate=${sampleRate}Hz, codec=$codec, underruns=$underruns. NO DOWNMIX APPLIED."
                        )
                    }

                    if (!engine.isVideoAudioReady()) {
                        consecutiveDriftCount = 0
                        continue
                    }

                    val firstFrameMs = engine.videoRouteStateFlow.value.firstFrameTimestampMs
                    val timeSinceFirstFrame = now - firstFrameMs
                    if (timeSinceFirstFrame < 2000L) {
                        consecutiveDriftCount = 0
                        continue // Warm-up
                    }

                    if (now - lastResyncTimeMs < 3000L || timeSinceFirstFrame < 3000L) {
                        consecutiveDriftCount = 0
                        continue // Cooldown
                    }

                    if (AudioFrameUtils.shouldResync(driftMs, 80L)) {
                        consecutiveDriftCount++
                        if (consecutiveDriftCount >= 3) {
                            Log.d("VideoAudioRouting", "Drift ${driftMs}ms > 80ms threshold for 3 samples. Resyncing engine audio to ${exoPos}ms with fade-in. NO DOWNMIX APPLIED.")
                            engine.requestVideoAudioResync(exoPos, driftMs)
                            lastResyncTimeMs = System.currentTimeMillis()
                            consecutiveDriftCount = 0
                        }
                    } else {
                        consecutiveDriftCount = 0
                    }

                    if (player.playbackParameters.speed != 1.0f) {
                        player.playbackParameters = PlaybackParameters(1.0f)
                    }
                } else {
                    consecutiveDriftCount = 0
                }
            }
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
        if (audioSessionId <= 0 || audioSessionId == AudioEffect.ERROR_BAD_VALUE) return
        if (audioSessionId == lastAudioSessionId && loudnessEnhancer != null && equalizer != null) return
        
        try {
            lastAudioSessionId = audioSessionId
            
            loudnessEnhancer?.release()
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                // Enabled state will be managed by updateLoudness
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

        // While audio is routed to the Audio Engine (DSP Studio), the local EQ must stay off.
        if (state.routeAudioToEngine || state.isConnectingEngineRoute) {
            try { if (eq.enabled) eq.enabled = false } catch (_: Exception) {}
            return
        }
        
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
        
        if (!state.isVolumeBoost || state.volume <= systemMax) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.setTargetGain(0)
            return
        }
        
        val MAX_BOOST_MB = 800
        var gainMb = ((state.volume - systemMax).toFloat() / systemMax * MAX_BOOST_MB).toInt()
        
        if (state.isEqEnabled && equalizer != null) {
            val maxBandGainMb = state.eqGains.maxOrNull()?.let { (it * 100).toInt() } ?: 0
            if (maxBandGainMb > 0) {
                gainMb = (gainMb - maxBandGainMb).coerceAtLeast(0)
            }
        }
        
        loudnessEnhancer?.enabled = gainMb > 0
        loudnessEnhancer?.setTargetGain(gainMb)
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
        if (_uiState.value.routeAudioToEngine || _uiState.value.isConnectingEngineRoute) return
        val next = !_uiState.value.isEqEnabled
        prefs.edit().putBoolean("video_eq_enabled", next).apply()
        _uiState.update { it.copy(isEqEnabled = next) }
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
        if (_uiState.value.routeAudioToEngine || _uiState.value.isConnectingEngineRoute) return
        prefs.edit().putBoolean("video_eq_enabled", true).apply()
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

    /** Simple on/off loop of the current video (tap once = on, tap again = off). */
    fun toggleLoop() {
        val player = exoPlayer ?: return
        val enable = !_uiState.value.isLooping
        player.repeatMode = if (enable) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _uiState.update { it.copy(isLooping = enable) }
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

    /**
     * Colour grading is now drawn by the UI (ColorMatrix on the video TextureView, see
     * VideoPlayerScreen), which updates instantly - also while paused - and does not depend on the
     * codec / media3 effect pipeline. Make sure no leftover GL effect is active so the grade is
     * never applied twice.
     */
    private fun updateVideoEffects() {
        val player = exoPlayer ?: return
        if (isEffectActive) {
            player.setVideoEffects(emptyList())
            isEffectActive = false
        }
    }


    fun setPresentationSurface(surface: Surface?) {
        exoPlayer?.setVideoSurface(surface)
    }

    fun getPlayer(): Player? = exoPlayer

    override fun onCleared() {
        if (_uiState.value.routeAudioToEngine) {
            stopEngineRouting(restoreExoAudio = false)
        }
        super.onCleared()
        getApplication<Application>().unregisterReceiver(volumeReceiver)
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        loudnessEnhancer?.release()
        equalizer?.release()
        exoPlayer?.release()
        // Don't leave streamed Telegram videos sitting in TDLib's cache (fire and forget: viewModelScope is cancelled below).
        telegramDataSourceFactory?.let { factory ->
            CoroutineScope(Dispatchers.IO).launch { factory.purgeDownloads() }
        }
        telegramDataSourceFactory = null
        spriteSheet?.recycle()
        backgroundTasksTimerJob?.cancel()
        spriteLoadingJob?.cancel()
        chapterDetectionJob?.cancel()
        chapterObservationJob?.cancel()
        abRepeatJob?.cancel()
        viewModelScope.cancel()
    }
}

private const val PREF_VIDEO_DECODER_MODE = "video_decoder_mode"
private const val PREF_SURROUND_HINT_SEEN = "video_surround_icon_hint_seen"

class VideoPlayerViewModelFactory(private val application: Application, private val videoQueue: List<Video>, private val initialVideoId: String) : ViewModelProvider.Factory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VideoPlayerViewModel(application, videoQueue, initialVideoId) as T
    }
}
