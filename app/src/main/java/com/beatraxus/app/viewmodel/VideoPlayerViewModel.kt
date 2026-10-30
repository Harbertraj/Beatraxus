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
import com.beatraxus.app.fpsbooster.RifeAiEngine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.CaptionStyleCompat
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.cast.CastManager
import com.beatraxus.app.cast.VideoCastHost
import com.beatraxus.app.cast.VideoCastRequest
import com.beatraxus.app.cast.VideoCastSupport
import com.google.android.gms.cast.MediaStatus
import com.beatraxus.app.engine.AudioEngine
import com.beatraxus.app.engine.AudioFrameUtils
import com.beatraxus.app.engine.ExternalAudioMediaSourceFactory
import com.beatraxus.app.engine.ExternalAudioTrack
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
import com.beatraxus.app.model.VideoInfoData
import com.beatraxus.app.model.VideoStreamInfo
import com.beatraxus.app.model.VideoOrientation
import com.beatraxus.app.model.SavedEqPreset
import com.beatraxus.app.model.sanitizeSurroundTrims
import com.beatraxus.app.model.withSurroundPreset
import com.beatraxus.app.model.withSurroundTrim
import com.beatraxus.app.model.withSurroundTrimsReset
import com.beatraxus.app.model.VideoRecentlyPlayedEntity
import com.beatraxus.app.colorgrade.ColorGradeEffect
import com.beatraxus.app.deinterlace.DeinterlaceEffect
import com.beatraxus.app.upscale.VideoUpscaleEffect
import com.beatraxus.app.model.VideoUpscaleMode
import com.beatraxus.app.model.HdrFormat
import com.beatraxus.app.model.HdrInfo
import com.beatraxus.app.model.DeinterlaceMode
import com.beatraxus.app.model.AudioStreamInfo
import com.beatraxus.app.model.NightFilterMath
import com.beatraxus.app.model.NightFilterMode
import com.beatraxus.app.model.NightFilterSettings
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
    /** Feature 25: name of the external audio file loaded for the current video, or null. */
    val externalAudioName: String? = null,
    /** 0f..1f while an audio track is being extracted to a file ("Extract" icon of the Audio Tracks sheet), else null. */
    val audioExtractProgress: Float? = null,
    /** Language / name of the audio track being extracted, e.g. "Tamil" (shown as [Tamil] next to the progress). */
    val audioExtractLanguage: String? = null,
    /** Feature 26: name of the Cast device while this video plays on a TV (the phone only acts as the remote). */
    val castDeviceName: String? = null,
    val selectedAudioTrackIndex: Int = -1,
    val selectedSubtitleTrackIndex: Int = -1,
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.FIT,
    val aspectRatioMessage: String? = null,
    /** Rotate / flip / mirror of the picture. Kept for the whole player session (all videos in the queue). */
    val videoOrientation: VideoOrientation = VideoOrientation.NONE,
    /** Feature 15: removes combing lines from interlaced video. Remembered between sessions. */
    val deinterlaceMode: DeinterlaceMode = DeinterlaceMode.OFF,
    /** Video upscaler (mode + sharpening strength 0..1); both are remembered for the next video. */
    val upscaleMode: VideoUpscaleMode = VideoUpscaleMode.OFF,
    val upscaleStrength: Float = VideoUpscaleMode.DEFAULT_STRENGTH,
    /** Feature 17: warm night / blue-light veil over the player. Remembered between sessions. */
    val nightFilter: NightFilterSettings = NightFilterSettings(),
    /** Feature 20: codec / bitrate / frame rate facts for the info panel. Refreshed while the panel is open. */
    val videoInfo: VideoInfoData? = null,
    /** The HDR format of the video that is playing (from the stream itself, not the library scan). */
    val hdrInfo: HdrInfo = HdrInfo.NONE,
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
    /** Gamma 0.5..2 (1 = off), hue -180..180 degrees, colour temperature -1 (cool) .. 1 (warm), sharpness 0..1. */
    val colorGamma: Float = 1f,
    val colorHue: Float = 0f,
    val colorTemperature: Float = 0f,
    val colorSharpness: Float = 0f,
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
    /**
     * The surround simulator switch (top icon sheet). It is the user's intent and always visible:
     * - "Connect to audio mode" ON: ON adds the simulator to the audio track pipeline, OFF skips it.
     * - "Connect to audio mode" OFF: ON runs the soundtrack through the simulator alone, OFF plays it untouched.
     */
    val surroundSimulatorEnabled: Boolean = false,
    /** The Audio Engine confirms that the surround simulator is really in this video's signal path. */
    val surroundActive: Boolean = false,
    /** The soundtrack is a real 5.1 / 7.1 mix: its discrete channels are kept, nothing is upmixed. */
    val surroundDiscrete: Boolean = false,
    /** Channel count of the soundtrack that reaches the engine (0 = unknown). */
    val surroundSourceChannels: Int = 0,
    /** Channel count the output device is really opened with (2 = stereo, 6 = 5.1, 8 = 7.1). */
    val surroundOutputChannels: Int = 2,
    /** The output device refused a multichannel stream, so the soundtrack plays in its own layout. */
    val surroundFallback: Boolean = false,
    val surroundSettings: VideoSurroundSettings = VideoSurroundSettings()
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
    val sampleRate: Int = -1,
    /** True for the audio file loaded with "Add audio track" (feature 25). */
    val isExternal: Boolean = false,
    /** Position of this track among the loaded audio files of the video (-1 for the video's own tracks). */
    val externalIndex: Int = -1
)

/**
 * Picture shapes of the video player. FIT / FILL / ZOOM follow the video's own ratio ([forcedRatio] is null);
 * every other entry forces the picture frame to that width / height ratio.
 *
 * The order is the order of the aspect-ratio button cycle, so a new ratio only needs a new entry here.
 * Saved names (recently played, preferences) are looked up by [name]: never rename an existing entry.
 */
enum class VideoAspectRatio(val displayName: String, val forcedRatio: Float? = null) {
    FIT("Fit to screen"),
    FILL("Fill screen"),
    ZOOM("Zoom"),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    SIXTEEN_TEN("16:10", 16f / 10f),
    TWENTY_ONE_NINE("21:9", 21f / 9f),
    CINEMASCOPE("2.35:1", 2.35f);

    /** The next mode of the aspect-ratio button; wraps from the last entry back to [FIT]. */
    fun next(): VideoAspectRatio = entries[(ordinal + 1) % entries.size]
}


@UnstableApi
class VideoPlayerViewModel(
    application: Application,
    private val videoQueue: List<Video>,
    initialVideoId: String
) : AndroidViewModel(application) {
    private val TAG = "VideoPlayerViewModel"
    private val CAST_STATUS_SETTLE_MS = 4000L

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
    // Settings -> Video Settings. Read once per player session, so a change applies from the next video.
    private val playerOptions = com.beatraxus.app.util.VideoPlayerPrefs.read(prefs)
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
    /** Which video effects the player currently has: bit 1 = deinterlace, bit 2 = gamma / sharpness, bit 4 = upscaler. */
    private var appliedEffectsKey = 0

    /**
     * media3 1.5: once setVideoEffects() has been called on a player (even with an empty list) its video renderer
     * keeps drawing through the effect pipeline for good. That pipeline cannot render into the bare Surface the
     * FPS Booster gives ExoPlayer (size unknown): the picture stays black while the sound plays. So a player the
     * booster may use never gets video effects (the booster deinterlaces itself), and a player that already has
     * effects never lets the booster start (see [withFpsPlan]).
     */
    private var effectsPipelineAllowed = true
    private var effectsInstalled = false

    // Feature 25: external audio files loaded per video id. Read from ExoPlayer's loader thread, hence concurrent.
    private val externalAudioByVideoId = java.util.concurrent.ConcurrentHashMap<String, List<ExternalAudioTrack>>()
    /** Index (in the video's loaded-audio list) to switch to as soon as its track shows up, or -1. */
    /**
     * The loaded audio file is played by a second, audio-only ExoPlayer ([extPlayer]) that follows the video player
     * (play / pause / seek / speed). The video player is muted while an added track is the chosen one.
     */
    private var extPlayer: ExoPlayer? = null
    private var extSyncJob: Job? = null
    /** Which loaded file is playing (index into the video's list), or -1 when the video's own audio is used. */
    private var activeExternalIndex = -1
    private var extVideoId: String? = null
    private var lastExtSeekAtMs = 0L
    /** How many corrective seeks in a row did not bring the added audio back in step (imprecise seeking in raw streams). */
    private var extMissCount = 0
    /** Time (ms) the last corrective seek needed before sound came out again; the next seek aims this far ahead. */
    private var extSeekLeadMs = 0L
    private var extSeekPending = false
    /** Current speed correction of the added audio: 1f = none, <1 = slowed (audio is ahead), >1 = sped up (behind). */
    private var extNudge = 1f
    /**
     * Timeline factor of the added file: 1 = normal. Some files carry timestamps that run N times faster than their
     * sound (seen in logcat: ~341 ms of timestamps for every 42.7 ms of decoded audio, so N = 8). The player's position
     * then races ahead of the video, the sync loop "corrects" it with a seek every 2 s and the same second of sound
     * repeats. N is found at run time (see [handleRunawayExternal]); seeks are then made in the file's own timeline.
     */
    private var extScale = 1f
    private var extRateAtMs = 0L
    private var extRateEpPos = 0L
    private var extRateMainPos = 0L
    private var extLastMainPos = 0L
    private var extLastMainAtMs = 0L
    private var extLastMainPlaying = false
    /** The video jumped (user seek): the added audio must be seeked too. Used when the file's timeline is scaled. */
    private var extNeedReseek = false
    /** Codec / channel info of each loaded file (by uri), known once it has been opened; shown in the track row. */
    private val externalFormats = java.util.concurrent.ConcurrentHashMap<String, androidx.media3.common.Format>()

    private fun externalAudioFor(videoId: String): List<ExternalAudioTrack> = externalAudioByVideoId[videoId].orEmpty()

    private fun externalAudioLabel(videoId: String?): String? =
        videoId?.let { externalAudioFor(it) }?.takeIf { it.isNotEmpty() }?.joinToString(" + ") { it.label }

    // Feature 26: casting a video to a Chromecast.
    private var castProgressJob: Job? = null
    private var castFinishedHandled = false
    private var castIgnoreFinishedUntil = 0L
    private val castHost = object : VideoCastHost {
        override fun onCastSessionReady(deviceName: String?): VideoCastRequest? = onCastReady(deviceName)
        override fun onCastSessionEnded(positionMs: Long) = onCastEnded(positionMs)
    }

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
        nightFilter = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).let { p ->
            NightFilterSettings(
                mode = NightFilterMode.fromName(p.getString(NightFilterSettings.KEY_MODE, null)),
                strength = NightFilterMath.clampStrength(p.getInt(NightFilterSettings.KEY_STRENGTH, NightFilterSettings.DEFAULT_STRENGTH))
            )
        },
        deinterlaceMode = DeinterlaceMode.fromName(application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getString(DeinterlaceMode.PREF_KEY, null)),
        upscaleMode = VideoUpscaleMode.fromName(application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getString(VideoUpscaleMode.PREF_KEY, null)),
        upscaleStrength = application.getSharedPreferences("beatraxus", Application.MODE_PRIVATE).getFloat(VideoUpscaleMode.STRENGTH_KEY, VideoUpscaleMode.DEFAULT_STRENGTH),
        playbackSpeed = playerOptions.defaultSpeed,
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
    
    val positionFlow = MutableStateFlow(0L)

    private var progressJob: Job? = null
    private var abRepeatJob: Job? = null
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var sleepTimerJob: Job? = null
    
    private val colorGradeEffect = ColorGradeEffect(0f, 1f, 1f)
    private val deinterlaceEffect = DeinterlaceEffect(DeinterlaceMode.OFF)
    private val upscaleEffect = VideoUpscaleEffect(VideoUpscaleMode.OFF, VideoUpscaleMode.DEFAULT_STRENGTH)

    // Feature 20: decoder names the device picked, reported by the player.
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null

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
        observeAudioExtraction()
        // Feature 26: a Cast session started from this screen takes the video to the TV.
        CastManager.videoHost = castHost
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
            effectsInstalled -> BoosterPlan.inactive("Replay the video to use the FPS Booster with video effects (deinterlacing, upscaler, sharpness)")
            caps == null -> BoosterPlan.inactive("Checking device")
            else -> FpsBoosterPlanner.plan(
                s.fpsBooster,
                caps,
                SourceInfo(s.sourceFps, s.videoSize.width, s.videoSize.height, s.isHdr),
                rifeModelInstalled = RifeAiEngine.isModelInstalled(getApplication<Application>())
            )
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
        appliedEffectsKey = 0
        effectsInstalled = false
        // Will the FPS Booster very likely run for this video? Then this player must never get ExoPlayer video effects.
        val boosterSetting = _uiState.value
        effectsPipelineAllowed = !(boosterSetting.fpsBooster.isOn && boosterSetting.fpsBoosterSuspended == null &&
            !video.isHdr && deviceCaps?.glEs3 != false)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Same chain as before for every normal Uri; `tgvideo://` Telegram videos are streamed through TDLib
        // and `cloudvideo://` cloud-drive videos (Drive, Dropbox, OneDrive, Box, Nextcloud) are
        // resolved to an authorised https address first.
        val streamDataSourceFactory = com.beatraxus.app.telegram.TelegramAwareDataSourceFactory(
            (context.applicationContext as BeatraxusApplication).tdLibManager,
            com.beatraxus.app.drive.CloudVideoDataSource.wrap(
                context,
                com.beatraxus.app.addons.AddonStreamHeaders.dataSourceFactory(context)
            )
        ).also { telegramDataSourceFactory = it }

        val player = ExoPlayer.Builder(context, VideoRenderersFactory(context, tuning))
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(streamDataSourceFactory)
            )
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(playerOptions.pauseOnHeadsetUnplug)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                repeatMode = Player.REPEAT_MODE_OFF
                // Video Settings -> Playback & Audio
                pauseAtEndOfMediaItems = !playerOptions.autoPlayNext
                if (playerOptions.defaultSpeed != 1f) this.setPlaybackSpeed(playerOptions.defaultSpeed)
                if (playerOptions.preferredAudioLanguage != null || playerOptions.preferredSubtitleLanguage != null) {
                    trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                        playerOptions.preferredAudioLanguage?.let { setPreferredAudioLanguage(it) }
                        playerOptions.preferredSubtitleLanguage?.let { setPreferredTextLanguage(it) }
                    }.build()
                }
                setMediaItems(videoQueue.map { v ->
                    MediaItem.Builder().setUri(v.uri).setMediaId(v.id).apply {
                        // Live TV channels: some playlists are served from addresses without a .m3u8 extension.
                        if (v.source == com.beatraxus.app.model.VideoSource.LIVE_TV) {
                            com.beatraxus.app.livetv.LiveTvPlayback.mimeTypeFor(v.uri)?.let { setMimeType(it) }
                        }
                        // Feature 27: pasted links whose address hides that it is an HLS / DASH manifest.
                        if (v.source == com.beatraxus.app.model.VideoSource.NETWORK) {
                            com.beatraxus.app.util.NetworkStreamLink.mimeTypeFor(v.uri.toString())?.let { setMimeType(it) }
                        }
                    }.build()
                })
                
                viewModelScope.launch(Dispatchers.IO) {
                    val recentlyPlayed = videoRecentlyPlayedDao.getRecentlyPlayedByVideoId(video.id)
                    val lastPos = if (playerOptions.resumePlayback) (recentlyPlayed?.lastPositionMs ?: 0L) else 0L
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
                        if (!isIdentity || _uiState.value.deinterlaceMode.isOn || needsGradeEffect() || _uiState.value.upscaleMode.isOn) {
                            updateVideoEffects() // Apply initial effects before prepare
                        }
                        
                        seekTo(startIndex, lastPos)
                        prepare()
                        playWhenReady = true
                    }
                }
            }

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                videoDecoderName = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                audioDecoderName = decoderName
            }
        })

        // Feature 25: keep the added-audio player in step with the video player.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = syncExternal()
            override fun onPlaybackStateChanged(playbackState: Int) = syncExternal()
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = syncExternal()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncExternal()
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) =
                syncExternal(forceSeek = reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        })

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
                player.videoFormat?.let { vf -> onVideoFormatKnown(vf) }
                
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
                    
                    // A subtitle file the viewer's saved choice put on the video wins over the last track index remembered.
                    if (pendingSubtitleTrackIndex != -1 && subtitleController.activeExternalTrackId.value == null) {
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
        _uiState.update { withFpsPlan(it.copy(currentVideo = video, isHdr = video?.isHdr ?: false, externalAudioName = externalAudioLabel(video?.id))) }
        updateVideoEffects() // the FPS Booster plan may have changed with the video; the upscaler steps aside for it
        
        if (video?.id != lastHandledVideoId) {
            lastHandledVideoId = video?.id
            // A booster that stopped itself on the previous video gets a fresh chance on the next one.
            if (_uiState.value.fpsBoosterSuspended != null) {
                _uiState.update { withFpsPlan(it.copy(fpsBoosterSuspended = null)) }
            }
            // Audio delay is tuned per file, so a new video starts back in sync.
            if (tuning.audioDelayMs != 0) setAudioDelayMs(0)
            video?.let { v ->
                // Putting the viewer's saved subtitle back on a video (and clearing a stale one) is done in one place only,
                // SubtitleViewModel.onVideoChanged. It looks the subtitles up under the video's content-hash key; this class
                // used a different key, found nothing and removed the subtitle that had just been loaded.
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
                // The loaded external audio only exists for this session, so never remember its group index.
                if (group.type == C.TRACK_TYPE_AUDIO) audioIdx = if (ExternalAudioTrack.isExternalGroupId(group.mediaTrackGroup.id)) -1 else index
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
                        sampleRate = format.sampleRate,
                        isExternal = false
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

        // Loaded audio files: always listed after the video's own tracks, same look; ADDED tag is drawn by the sheet.
        externalAudioFor(currentVideoId()).forEachIndexed { i, ext ->
            val f = externalFormats[ext.uri.toString()]
            audioTracks.add(
                VideoTrackInfo(
                    index = -1, name = ext.label, language = f?.language, format = f?.sampleMimeType,
                    isSelected = i == activeExternalIndex, audioOrdinal = currentAudioOrdinal++,
                    channelCount = f?.channelCount ?: -1, sampleRate = f?.sampleRate ?: -1,
                    isExternal = true, externalIndex = i
                )
            )
        }
        if (activeExternalIndex >= 0) {
            // An added track is playing: it is the selected one, the video's own tracks are not.
            for (k in audioTracks.indices) {
                val t = audioTracks[k]
                audioTracks[k] = t.copy(isSelected = t.isExternal && t.externalIndex == activeExternalIndex)
            }
            selectedAudio = audioTracks.firstOrNull { it.isSelected }?.audioOrdinal ?: selectedAudio
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
        if (_uiState.value.castDeviceName != null) {
            // Casting: the phone is the remote control for the TV.
            if (_uiState.value.isPlaying) CastManager.pause() else CastManager.play()
            _uiState.update { it.copy(isPlaying = !it.isPlaying) }
            return@let
        }
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
        if (_uiState.value.castDeviceName != null) {
            CastManager.seek(position)
            positionFlow.value = position
            return
        }
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
        updateVideoEffects()
    }

    fun updateFpsBoosterStats(stats: BoosterStats) {
        val activePlan = _uiState.value.fpsBoosterPlan
        val updatedStats = if (activePlan.active && activePlan.targetFps > 0 && stats.targetFps != activePlan.targetFps) {
            stats.copy(targetFps = activePlan.targetFps)
        } else {
            stats
        }
        _uiState.update { it.copy(fpsBoosterStats = updatedStats) }
    }

    /** The booster could not keep up / overheated / hit a GPU error: play normally for the rest of this video. */
    fun onFpsBoosterFallback(reason: FallbackReason) {
        _uiState.update {
            withFpsPlan(it.copy(fpsBoosterSuspended = reason.message, fpsBoosterStats = BoosterStats(fallbackReason = reason.message)))
        }
        updateVideoEffects() // back to normal playback: deinterlacing returns to the player
    }

    /** Turns the picture 90 degrees clockwise (0 -> 90 -> 180 -> 270 -> 0). */
    fun rotateVideo() = changeVideoOrientation { it.rotatedClockwise() }

    /** Mirror: swaps left and right. */
    fun toggleVideoMirror() = changeVideoOrientation { it.withFlipHorizontal(!it.flipHorizontal) }

    /** Swaps top and bottom. */
    fun toggleVideoFlipVertical() = changeVideoOrientation { it.withFlipVertical(!it.flipVertical) }

    fun resetVideoOrientation() = changeVideoOrientation { VideoOrientation.NONE }

    private fun changeVideoOrientation(change: (VideoOrientation) -> VideoOrientation) {
        val next = change(_uiState.value.videoOrientation)
        _uiState.update { it.copy(videoOrientation = next, aspectRatioMessage = next.describe()) }
        viewModelScope.launch { delay(2000); _uiState.update { it.copy(aspectRatioMessage = null) } }
    }

    fun setAspectRatio(ratio: VideoAspectRatio) {
        _uiState.update { it.copy(aspectRatio = ratio, aspectRatioMessage = ratio.displayName) }
        viewModelScope.launch { delay(2000); _uiState.update { it.copy(aspectRatioMessage = null) } }
    }

    // ---- Feature 26: cast the video to a Chromecast ----

    /** The queue entry the player is on right now (callbacks that update currentVideo run a little later). */
    private fun playerCurrentVideo(): Video? {
        val id = exoPlayer?.currentMediaItem?.mediaId ?: return null
        return videoQueue.find { it.id == id }
    }

    /** A Cast session is ready: pause here, remember where we were and let CastManager load the video on the TV. */
    private fun onCastReady(deviceName: String?): VideoCastRequest? {
        val player = exoPlayer ?: return null
        val video = playerCurrentVideo() ?: return null
        if (VideoCastSupport.routeFor(video.uri.scheme) == VideoCastSupport.Route.UNSUPPORTED) {
            android.widget.Toast.makeText(
                getApplication(),
                "Casting isn't available for cloud or Telegram videos yet.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return null
        }
        val alreadyCasting = _uiState.value.castDeviceName != null
        // currentPosition includes the audio-delay clock shift, so take it back out.
        val positionMs = if (alreadyCasting) positionFlow.value
        else (player.currentPosition - tuning.audioDelayMs).coerceAtLeast(0L)

        // The Audio Engine route decodes the video file on the phone; it has no place while the TV plays it.
        if (_uiState.value.routeAudioToEngine || _uiState.value.isConnectingEngineRoute) {
            setRouteAudioToEngine(false)
        }
        player.pause()
        castFinishedHandled = false
        castIgnoreFinishedUntil = android.os.SystemClock.elapsedRealtime() + CAST_STATUS_SETTLE_MS
        _uiState.update { it.copy(castDeviceName = deviceName ?: "Chromecast", isPlaying = true) }
        startCastProgress()
        return VideoCastRequest(video, positionMs)
    }

    /** The Cast session ended (the user stopped it, or the TV went away): carry on playing here. */
    private fun onCastEnded(positionMs: Long) {
        if (_uiState.value.castDeviceName == null) return
        castProgressJob?.cancel()
        val wasPlaying = _uiState.value.isPlaying
        val resumeAt = if (positionMs > 0) positionMs else positionFlow.value
        _uiState.update { it.copy(castDeviceName = null) }
        val player = exoPlayer ?: return
        player.seekTo(resumeAt)
        positionFlow.value = resumeAt
        player.playWhenReady = wasPlaying
    }

    /** Loads the queue entry the player is on (after next / previous) on the TV, from the start. */
    private fun castCurrentVideo() {
        val video = playerCurrentVideo() ?: return
        if (VideoCastSupport.routeFor(video.uri.scheme) == VideoCastSupport.Route.UNSUPPORTED) {
            android.widget.Toast.makeText(
                getApplication(),
                "This video can't be cast. Stop casting to play it on the phone.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        castFinishedHandled = false
        castIgnoreFinishedUntil = android.os.SystemClock.elapsedRealtime() + CAST_STATUS_SETTLE_MS
        positionFlow.value = 0L
        _uiState.update { it.copy(isPlaying = true) }
        CastManager.loadVideo(getApplication(), video, 0L)
    }

    /** Mirrors the TV's position, play state and duration into the player UI while casting. */
    private fun startCastProgress() {
        castProgressJob?.cancel()
        castProgressJob = viewModelScope.launch {
            while (isActive) {
                val status = CastManager.castMediaStatus.value
                val pos = CastManager.currentPositionMs()
                if (positionFlow.value != pos) positionFlow.value = pos

                val state = status?.playerState
                val remotePlaying = state == MediaStatus.PLAYER_STATE_PLAYING ||
                    state == MediaStatus.PLAYER_STATE_BUFFERING ||
                    state == MediaStatus.PLAYER_STATE_LOADING
                val remoteDuration = status?.mediaInfo?.streamDuration ?: -1L
                _uiState.update { s ->
                    val newDuration = if (remoteDuration > 0) remoteDuration else s.duration
                    if (s.isPlaying == remotePlaying && s.duration == newDuration) s
                    else s.copy(isPlaying = remotePlaying, duration = newDuration)
                }

                // Finished on the TV: carry on with the next video of the queue, like local playback does.
                if (status != null && state == MediaStatus.PLAYER_STATE_IDLE &&
                    status.idleReason == MediaStatus.IDLE_REASON_FINISHED &&
                    !castFinishedHandled &&
                    android.os.SystemClock.elapsedRealtime() >= castIgnoreFinishedUntil
                ) {
                    castFinishedHandled = true
                    if (playerOptions.autoPlayNext && exoPlayer?.hasNextMediaItem() == true) playNext()
                }
                delay(500)
            }
        }
    }

    // ---- Feature 25: add an external audio track ----

    private fun currentVideoId(): String = exoPlayer?.currentMediaItem?.mediaId.orEmpty()

    private fun ensureExternalPlayer(): ExoPlayer {
        extPlayer?.let { return it }
        val app = getApplication<Application>()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // Raw streams (.ac3 .eac3 .dts .mp3 .aac, as saved by "Extract") have no seek table and often no duration.
        // Without these flags ExoPlayer can't seek in them: every seek restarts from the beginning and the added audio
        // stalls at a random point while the video goes on. Constant-bitrate seeking gives a duration estimate and
        // working seeks; index seeking makes variable-bitrate MP3 files seek accurately.
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        // false: the video player owns audio focus; this one only follows it.
        val ep = ExoPlayer.Builder(
            app,
            com.beatraxus.app.engine.ExternalAudioRenderersFactory(app, tuning),
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app, extractorsFactory)
        )
            .setAudioAttributes(attrs, false)
            .build()
        ep.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Added audio failed (${error.errorCodeName})", error)
                val failed = activeExternalIndex
                android.widget.Toast.makeText(
                    app, "Couldn't play that audio file. Playing the original audio.", android.widget.Toast.LENGTH_LONG
                ).show()
                removeExternalAudio(failed)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0) ?: return
                val file = externalAudioFor(extVideoId.orEmpty()).getOrNull(activeExternalIndex) ?: return
                externalFormats[file.uri.toString()] = format
                Log.i(TAG, "Added audio format: ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz")
                updateTracks()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Not a forced seek: a seek ends in READY again, which would seek again, forever.
                if (playbackState == Player.STATE_READY) {
                    if (extSeekPending) {
                        extSeekPending = false
                        extSeekLeadMs = (android.os.SystemClock.elapsedRealtime() - lastExtSeekAtMs).coerceIn(0L, 3000L)
                    }
                    syncExternal()
                }
            }
        })
        extPlayer = ep
        return ep
    }

    /** Switches the sound to loaded file number [index] of the current video (the video player is muted). */
    private fun startExternalPlayback(index: Int) {
        val main = exoPlayer ?: return
        val videoId = currentVideoId()
        val file = externalAudioFor(videoId).getOrNull(index) ?: return
        // The Audio Engine route decodes the video file itself and can't play a separate file: leave it first.
        if (_uiState.value.routeAudioToEngine || _uiState.value.isConnectingEngineRoute) {
            setRouteAudioToEngine(false)
        }
        val ep = ensureExternalPlayer()
        extVideoId = videoId
        activeExternalIndex = index
        ep.setMediaItem(MediaItem.fromUri(file.uri), (main.currentPosition - tuning.audioDelayMs).coerceAtLeast(0L))
        ep.playbackParameters = PlaybackParameters(main.playbackParameters.speed)
        ep.volume = 1f
        lastExtSeekAtMs = android.os.SystemClock.elapsedRealtime() // the start position above is already aligned
        extMissCount = 0
        extNudge = 1f
        extScale = 1f
        extRateAtMs = 0L
        extLastMainAtMs = 0L
        extNeedReseek = false
        extSeekPending = true // the first load counts as a seek: its length becomes the lead for later seeks
        ep.prepare()
        ep.playWhenReady = main.isPlaying
        main.volume = 0f
        extSyncJob?.cancel()
        extSyncJob = viewModelScope.launch {
            while (isActive && activeExternalIndex >= 0) {
                syncExternal()
                delay(200)
            }
        }
        updateTracks()
    }

    /** Goes back to the video's own audio. */
    private fun stopExternalPlayback() {
        extSyncJob?.cancel()
        extSyncJob = null
        extNudge = 1f
        extScale = 1f
        extRateAtMs = 0L
        extNeedReseek = false
        extSeekPending = false
        activeExternalIndex = -1
        extVideoId = null
        extPlayer?.let { it.stop(); it.clearMediaItems() }
        exoPlayer?.volume = 1f
        updateTracks()
    }

    /** Seeks the added audio to where the video is now (in the added file's own timeline, see [extScale]). */
    private fun seekExternalToVideo(ep: ExoPlayer, main: ExoPlayer, now: Long, speed: Float) {
        lastExtSeekAtMs = now
        extMissCount++
        extSeekPending = true
        extNeedReseek = false
        extRateAtMs = 0L
        // While the video plays on, the seek itself takes time: aim ahead by what the last seek needed,
        // otherwise the audio lands behind again every time.
        val lead = if (main.isPlaying) (extSeekLeadMs * speed).toLong() else 0L
        val target = (main.currentPosition - tuning.audioDelayMs + lead).coerceAtLeast(0L)
        ep.seekTo((target * extScale).toLong())
    }

    /**
     * The added audio's position ran much faster than the video's: its timestamps don't match its sound. When the file's
     * length is a whole multiple of the video's (8x, ...), that multiple is the timeline factor and sync continues with
     * it. Otherwise the file can't be synced: the original audio comes back. Returns true when the file was dropped.
     */
    private fun handleRunawayExternal(ep: ExoPlayer, main: ExoPlayer, now: Long, speed: Float): Boolean {
        val mainDur = main.duration
        val epDur = ep.duration
        val ratio = if (mainDur > 0 && epDur > 0) epDur.toDouble() / mainDur else 0.0
        val n = Math.round(ratio)
        if (n >= 2 && abs(ratio - n) <= 0.1 * n) {
            Log.w(TAG, "Added audio timeline runs ${n}x faster than its sound; syncing with factor $n")
            extScale = n.toFloat()
            seekExternalToVideo(ep, main, now, speed)
            return false
        }
        Log.w(TAG, "Added audio timestamps are unusable (position ran away, length ratio $ratio); dropping it")
        android.widget.Toast.makeText(
            getApplication(), "This audio file has broken timing (a file saved by an older version?). Extract the track again and add the new file. Playing the original audio.", android.widget.Toast.LENGTH_LONG
        ).show()
        removeExternalAudio(activeExternalIndex)
        return true
    }

    /** Makes the added-audio player follow the video player: play state, speed and position. */
    private fun syncExternal(forceSeek: Boolean = false) {
        if (activeExternalIndex < 0) return
        val ep = extPlayer ?: return
        val main = exoPlayer ?: return
        if (currentVideoId() != extVideoId) { // the video changed (next / previous): the file belonged to the old one
            stopExternalPlayback()
            return
        }
        if (main.volume != 0f) main.volume = 0f
        val speed = main.playbackParameters.speed
        val epState = ep.playbackState
        if (epState == Player.STATE_ENDED) extSeekPending = false // a seek past the end never turns READY
        val now = android.os.SystemClock.elapsedRealtime()
        // Did the video jump (user seek)? Needed when the file's own position can't be compared with the video's.
        if (extLastMainAtMs != 0L) {
            val expectedMain = extLastMainPos + if (extLastMainPlaying) ((now - extLastMainAtMs) * speed).toLong() else 0L
            if (abs(main.currentPosition - expectedMain) > 1000L) extNeedReseek = true
        }
        extLastMainPos = main.currentPosition
        extLastMainAtMs = now
        extLastMainPlaying = main.isPlaying
        var nudge = extNudge
        if (main.playbackState == Player.STATE_READY && epState != Player.STATE_IDLE) {
            val sinceSeek = now - lastExtSeekAtMs
            val scaled = extScale > 1f
            // The file really ended (it is shorter than the video) when the video is already past the point where it
            // stopped. Judged by position, not by ep.duration: for raw streams the duration is only an estimate.
            val ended = epState == Player.STATE_ENDED &&
                main.currentPosition * extScale >= ep.currentPosition - 500 * extScale
            // A seek in a raw stream can take seconds to load. Seeking again while it loads restarts the load from the
            // beginning every time, so the audio never arrives ("stuck"): wait for it (up to 20 s) unless forced.
            val loadingSeek = (epState == Player.STATE_BUFFERING || extSeekPending) && !forceSeek && sinceSeek < 20_000L
            if (ended) {
                nudge = 1f
            } else if (!loadingSeek) {
                if (scaled) {
                    // The file's position runs on its own timeline: it can't be compared with the video's. After the
                    // first seek the sound simply plays in real time, so only a jump of the video needs a new seek.
                    nudge = 1f
                    if (sinceSeek > 400L && (forceSeek || extNeedReseek)) seekExternalToVideo(ep, main, now, speed)
                } else {
                    // > 0: the added audio is ahead of the video, < 0: behind it.
                    val drift = ep.currentPosition - main.currentPosition
                    val absDrift = abs(drift)
                    if (forceSeek || absDrift <= 300L) extMissCount = 0
                    // Seeks in some files are never exact; after three corrections that did not help, only seek for big gaps.
                    val seekThreshold = if (extMissCount >= 3) 2000L else 700L
                    val cooledDown = sinceSeek > (if (forceSeek) 250L else 2000L)
                    if (cooledDown && (forceSeek || absDrift > seekThreshold)) {
                        nudge = 1f
                        seekExternalToVideo(ep, main, now, speed)
                    } else if (epState == Player.STATE_READY && main.isPlaying) {
                        // Small gaps are closed by playing the added audio a little slower / faster (pitch is kept), which
                        // is inaudible and does not flush the sound like a seek does. Hysteresis: start 120 ms, stop 40 ms.
                        nudge = when {
                            absDrift < 40L -> 1f
                            absDrift > 300L -> if (drift > 0) 0.95f else 1.05f
                            absDrift > 120L -> if (drift > 0) 0.975f else 1.025f
                            else -> nudge
                        }
                        // Runaway check: over one second of normal playback the file's position must not move much
                        // faster than the video's. If it does, its timestamps don't match its sound.
                        if (extRateAtMs == 0L) {
                            extRateAtMs = now; extRateEpPos = ep.currentPosition; extRateMainPos = main.currentPosition
                        } else if (now - extRateAtMs >= 1000L) {
                            val dMain = main.currentPosition - extRateMainPos
                            val dEp = ep.currentPosition - extRateEpPos
                            extRateAtMs = now; extRateEpPos = ep.currentPosition; extRateMainPos = main.currentPosition
                            if (dMain >= 600L && dEp > dMain * 1.6) {
                                if (handleRunawayExternal(ep, main, now, speed)) return
                                nudge = 1f
                            }
                        }
                    } else {
                        extRateAtMs = 0L
                    }
                }
            }
        } else {
            nudge = 1f
            extRateAtMs = 0L
        }
        extNudge = nudge
        val wanted = speed * nudge
        if (abs(ep.playbackParameters.speed - wanted) > 0.001f) ep.playbackParameters = PlaybackParameters(wanted)
        val playing = main.isPlaying
        if (ep.playWhenReady != playing) ep.playWhenReady = playing
    }

    /**
     * Adds [uri] (picked with the system file picker) as an extra audio track of the current video and switches to
     * it. Several files can be added; they are listed with the video's own audio tracks and can be switched between
     * at any time. They stay for this video until the player closes or [removeExternalAudio] is called.
     */
    fun addExternalAudio(uri: Uri, displayName: String?) {
        val videoId = currentVideoId()
        if (videoId.isEmpty()) return
        // Keep the permission so the file can still be read later.
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val existing = externalAudioFor(videoId)
        val sameFile = existing.indexOfFirst { it.uri == uri }
        if (sameFile >= 0) {
            startExternalPlayback(sameFile)
            return
        }
        var label = ExternalAudioTrack.labelFromFileName(displayName)
        if (existing.any { it.label == label }) label = "$label (${existing.size + 1})"
        externalAudioByVideoId[videoId] = existing + ExternalAudioTrack(uri, label)
        _uiState.update { it.copy(externalAudioName = externalAudioLabel(videoId), error = null) }
        startExternalPlayback(existing.size)
    }

    /**
     * Saves the chosen audio track of the video file itself ([track], picked in the "which audio track?" dialog)
     * into the folder [treeUri] the user picked. File name: "[<audio language>] <video title>.<ext>".
     * The work and its progress live in [VideoAudioExtractionManager], shared with the library's long-press menu.
     */
    fun extractAudioTrack(treeUri: Uri, track: VideoTrackInfo) {
        val state = _uiState.value
        val video = state.currentVideo ?: return
        if (state.audioExtractProgress != null) return
        val tracks = state.availableAudioTracks
        val app = getApplication<Application>()
        if (track.isExternal) {
            android.widget.Toast.makeText(app, "No audio track to extract", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // Position among the audio tracks of the file itself (loaded external audio is listed too, but is not in the file)
        val nativeIndex = tracks.count { !it.isExternal && it.audioOrdinal < track.audioOrdinal }
        val language = com.beatraxus.app.util.VideoAudioExtractor.languageDisplayName(track.language)
            ?: track.name.takeIf { it.isNotBlank() } ?: "Unknown"
        com.beatraxus.app.util.VideoAudioExtractionManager.start(app, video, nativeIndex, language, treeUri)
    }

    /** Stops a running extraction; the half-written file is deleted by the extractor. */
    fun cancelAudioExtraction() {
        com.beatraxus.app.util.VideoAudioExtractionManager.cancel()
    }

    /** Mirrors the shared extraction progress into [VideoPlayerUiState] so the Audio Tracks sheet shows "[Tamil] 42%". */
    private fun observeAudioExtraction() {
        viewModelScope.launch {
            com.beatraxus.app.util.VideoAudioExtractionManager.state.collect { st ->
                _uiState.update { it.copy(audioExtractProgress = st?.progress, audioExtractLanguage = st?.language) }
            }
        }
    }

    /**
     * Removes the added audio file number [index] of the current video (every added file when [index] is -1). If it
     * was the playing one, the video's own audio comes back.
     */
    fun removeExternalAudio(index: Int = -1) {
        val videoId = currentVideoId()
        if (videoId.isEmpty()) return
        val current = externalAudioFor(videoId)
        if (current.isEmpty()) return
        val removeAll = index !in current.indices
        val wasActive = activeExternalIndex
        val remaining = if (removeAll) emptyList() else current.filterIndexed { i, _ -> i != index }
        (if (removeAll) current else listOf(current[index])).forEach { externalFormats.remove(it.uri.toString()) }
        if (remaining.isEmpty()) externalAudioByVideoId.remove(videoId) else externalAudioByVideoId[videoId] = remaining
        when {
            wasActive >= 0 && (removeAll || wasActive == index) -> stopExternalPlayback()
            wasActive > index -> activeExternalIndex = wasActive - 1
        }
        _uiState.update { it.copy(externalAudioName = externalAudioLabel(videoId)) }
        updateTracks()
    }

    fun selectAudioTrack(track: VideoTrackInfo) {
        if (track.isExternal) {
            startExternalPlayback(track.externalIndex)
            return
        }
        if (activeExternalIndex >= 0) stopExternalPlayback()
        exoPlayer?.let { p ->
            val trackGroup = p.currentTracks.groups.getOrNull(track.index)
            if (trackGroup != null) {
                if (!trackGroup.isTrackSupported(0, true)) {
                    android.widget.Toast.makeText(
                        getApplication(), "This audio format isn't supported on this device", android.widget.Toast.LENGTH_LONG
                    ).show()
                    return
                }
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
     * Stops (false) or starts (true) the "Connect to audio mode" route: the soundtrack goes through the Audio Engine
     * with the whole Studio DSP chain. Used internally (errors, speed change) where false must always stop routing.
     * The audio-mode switch in the Audio Tracks sheet uses [setAudioMode].
     */
    fun setRouteAudioToEngine(enabled: Boolean) {
        setEngineRoute(enabled, surroundEngine = false)
    }

    /**
     * "Connect to audio mode" switch in the Audio Tracks sheet.
     *
     * ON: the soundtrack runs through the Studio DSP chain. The surround simulator is added to that pipeline when its
     * switch is ON ([VideoPlayerUiState.surroundSimulatorEnabled]) and skipped when it is OFF.
     * OFF: routing stops, except that a simulator that is still ON keeps working on the soundtrack alone.
     */
    fun setAudioMode(enabled: Boolean) {
        val state = _uiState.value
        when {
            enabled -> setEngineRoute(true, surroundEngine = false)
            state.surroundSimulatorEnabled && state.routeAudioToEngine && !state.surroundEngineRoute ->
                setEngineRoute(true, surroundEngine = true)
            else -> setEngineRoute(false, surroundEngine = false)
        }
    }

    private fun setEngineRoute(enabled: Boolean, surroundEngine: Boolean) {
        // The engine route decodes the video's own audio, so an added track can't be used with it.
        if (enabled && activeExternalIndex >= 0) stopExternalPlayback()
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
            if (current.availableAudioTracks.any { it.isExternal && it.isSelected }) {
                _uiState.update { it.copy(engineRouteError = "Audio Engine routing isn't available with an external audio track", isConnectingEngineRoute = false) }
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
                // A tap in the sheet is saved after a short debounce. Until it lands, the stored copy is older than what
                // the sheet shows, so adopting it here (any unrelated setting write re-emits it) would flip the layout
                // chips and the speaker preview back to the previous choice. The save re-emits the new value anyway.
                if (surroundSaveJob?.isActive == true) return@collect
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

    /**
     * On/off switch of the surround simulator in the video player sheet (the top icon is always there).
     *
     * "Connect to audio mode" running: the simulator stage is added to / skipped in the running DSP pipeline, no
     * restart of the route.
     * Otherwise ON starts the simulator-only route (also from fully off) and OFF stops it.
     */
    fun setSurroundSimulatorEnabled(enabled: Boolean) {
        val state = _uiState.value
        if (state.isConnectingEngineRoute) return
        _uiState.update { it.copy(surroundSimulatorEnabled = enabled) }
        when {
            state.routeAudioToEngine && !state.surroundEngineRoute ->
                VideoAudioBridge.getAudioEngine()?.setVideoSurroundInPipeline(enabled)
            enabled -> setEngineRoute(true, surroundEngine = true)
            state.surroundEngineRoute -> setEngineRoute(false, surroundEngine = true)
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
            surroundEngine = _uiState.value.surroundEngineRoute,
            // "Connect to audio mode": the simulator joins the DSP pipeline only while its switch is ON.
            surroundInPipeline = _uiState.value.surroundSimulatorEnabled
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
                if (_uiState.value.castDeviceName != null) castCurrentVideo() else it.play()
            }
        }
    }

    /** The videos of this player session in play order (the "Up next" sheet lists them). */
    val queue: List<Video> get() = videoQueue

    /** Jumps straight to a video of the queue, picked from the "Up next" sheet. */
    fun playQueueItem(videoId: String) {
        val index = videoQueue.indexOfFirst { it.id == videoId }
        if (index < 0) return
        exoPlayer?.let {
            if (index >= it.mediaItemCount) return
            it.seekTo(index, 0L)
            if (_uiState.value.castDeviceName != null) castCurrentVideo() else it.play()
        }
    }

    fun playPrevious() {
        exoPlayer?.let {
            if (it.hasPreviousMediaItem()) {
                it.seekToPrevious()
                if (_uiState.value.castDeviceName != null) castCurrentVideo() else it.play()
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
        _uiState.update { it.copy(colorGamma = 1f, colorHue = 0f, colorTemperature = 0f, colorSharpness = 0f) }
        setColorGrading(0f, 1f, 1f)
    }

    /** Gamma, hue, colour temperature and sharpness (the second group of sliders in Color Grading). */
    fun setColorExtras(gamma: Float, hue: Float, temperature: Float, sharpness: Float) {
        _uiState.update { it.copy(colorGamma = gamma, colorHue = hue, colorTemperature = temperature, colorSharpness = sharpness) }
        updateVideoEffects()
    }

    /** Gamma and sharpness need the GPU effect; it is only added to the player while one of them is in use. */
    private fun needsGradeEffect(): Boolean {
        val st = _uiState.value
        return abs(st.colorGamma - 1f) > 0.001f || st.colorSharpness > 0.001f
    }

    fun setForceSdrToneMapping(force: Boolean) {
        _uiState.update { it.copy(forceSdrToneMapping = force) }
        updateVideoEffects()
    }

    /** Feature 20. Reads the facts of the current video from the player; the info sheet calls it every second. */
    /** Display HDR types, read once. */
    val displayHdrSupport: String by lazy {
        try {
            val dm = getApplication<Application>().getSystemService(android.hardware.display.DisplayManager::class.java)
            @Suppress("DEPRECATION")
            val types = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            val names = types.asList().mapNotNull {
                when (it) {
                    android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> null
                }
            }
            if (names.isEmpty()) "None (standard screen)" else names.joinToString(", ")
        } catch (_: Exception) {
            "Unknown"
        }
    }

    /** Whether the device has a Dolby Vision decoder, read once. */
    val hasDolbyVisionDecoder: Boolean by lazy {
        try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/dolby-vision", ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    private var dolbyVisionWarnedFor: String? = null

    /** Works out the HDR format from the video stream, and warns once about Dolby Vision the device cannot decode. */
    private fun onVideoFormatKnown(vf: androidx.media3.common.Format) {
        val hdr = HdrFormat.detect(vf.sampleMimeType, vf.codecs, vf.colorInfo?.colorTransfer ?: -1, vf.colorInfo?.colorSpace ?: -1)
        if (hdr != _uiState.value.hdrInfo) _uiState.update { it.copy(hdrInfo = hdr) }
        val id = exoPlayer?.currentMediaItem?.mediaId
        if (hdr.needsDolbyVisionDecoder && !hasDolbyVisionDecoder && dolbyVisionWarnedFor != id) {
            dolbyVisionWarnedFor = id
            _uiState.update { it.copy(aspectRatioMessage = "Dolby Vision profile 5 needs a Dolby Vision decoder. Colours may look wrong on this device") }
            viewModelScope.launch { delay(4000); _uiState.update { it.copy(aspectRatioMessage = null) } }
        }
    }

    fun refreshVideoInfo() {
        val player = exoPlayer ?: return
        val video = _uiState.value.currentVideo
        val vf = player.videoFormat
        val af = player.audioFormat
        val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
        val playerDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val info = VideoInfoData(
            fileName = video?.displayName?.takeIf { it.isNotBlank() } ?: video?.title.orEmpty(),
            location = video?.let { if (it.isCloud) it.source.name.lowercase().replaceFirstChar { c -> c.uppercase() } else it.folderPath },
            containerMime = vf?.containerMimeType ?: video?.mimeType,
            sizeBytes = video?.sizeBytes ?: 0L,
            durationMs = playerDuration ?: video?.durationMs ?: 0L,
            isStreamed = video?.isCloud == true,
            video = vf?.let {
                VideoStreamInfo(
                    mimeType = it.sampleMimeType,
                    codecs = it.codecs,
                    width = it.width.coerceAtLeast(0),
                    height = it.height.coerceAtLeast(0),
                    frameRate = it.frameRate.takeIf { fps -> fps > 0f } ?: 0f,
                    bitrate = it.averageBitrate,
                    peakBitrate = it.peakBitrate,
                    colorTransfer = it.colorInfo?.colorTransfer ?: -1,
                    colorSpace = it.colorInfo?.colorSpace ?: -1
                )
            },
            audio = af?.let {
                AudioStreamInfo(
                    mimeType = it.sampleMimeType,
                    codecs = it.codecs,
                    channelCount = it.channelCount,
                    sampleRate = it.sampleRate,
                    bitrate = if (it.averageBitrate > 0) it.averageBitrate else it.peakBitrate,
                    language = it.language,
                    label = it.label
                )
            },
            videoDecoder = videoDecoderName,
            audioDecoder = audioDecoderName,
            droppedFrames = counters?.droppedBufferCount ?: 0,
            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
            displayHdrTypes = displayHdrSupport,
            dolbyVisionDecoder = hasDolbyVisionDecoder
        )
        _uiState.update { it.copy(videoInfo = info) }
    }

    /** Feature 17. Off / always on / only in the evening. */
    fun setNightFilterMode(mode: NightFilterMode) {
        prefs.edit().putString(NightFilterSettings.KEY_MODE, mode.name).apply()
        _uiState.update { it.copy(nightFilter = it.nightFilter.copy(mode = mode)) }
    }

    /** Feature 17. Called while the slider moves, so the tint changes live; saved by [commitNightFilterStrength]. */
    fun setNightFilterStrength(strength: Int) {
        _uiState.update { it.copy(nightFilter = it.nightFilter.copy(strength = NightFilterMath.clampStrength(strength))) }
    }

    fun commitNightFilterStrength() {
        prefs.edit().putInt(NightFilterSettings.KEY_STRENGTH, _uiState.value.nightFilter.strength).apply()
    }

    /** Feature 15. Picks how combing lines of interlaced video are removed; remembered for the next video. */
    fun setDeinterlaceMode(mode: DeinterlaceMode) {
        prefs.edit().putString(DeinterlaceMode.PREF_KEY, mode.name).apply()
        _uiState.update { it.copy(deinterlaceMode = mode, aspectRatioMessage = "Deinterlacing: ${mode.label}") }
        updateVideoEffects()
        viewModelScope.launch { delay(2000); _uiState.update { it.copy(aspectRatioMessage = null) } }
    }

    /** Picks the video upscaler; remembered for the next video. */
    fun setUpscaleMode(mode: VideoUpscaleMode) {
        prefs.edit().putString(VideoUpscaleMode.PREF_KEY, mode.name).apply()
        _uiState.update { it.copy(upscaleMode = mode, aspectRatioMessage = "Upscaler: ${mode.label}") }
        updateVideoEffects()
        viewModelScope.launch { delay(2000); _uiState.update { it.copy(aspectRatioMessage = null) } }
    }

    /** Sharpening strength of the upscaler, 0..1. Applies on the next frame. */
    fun setUpscaleStrength(strength: Float) {
        val v = strength.coerceIn(0f, 1f)
        prefs.edit().putFloat(VideoUpscaleMode.STRENGTH_KEY, v).apply()
        _uiState.update { it.copy(upscaleStrength = v) }
        upscaleEffect.strength = v
    }

    /**
     * Colour grading is drawn by the UI (ColorMatrix on the video TextureView, see VideoPlayerScreen), which
     * updates instantly - also while paused - and does not depend on the codec / media3 effect pipeline, so no
     * colour GL effect is ever installed here (it would be applied twice).
     *
     * The media3 effect pipeline is only used while deinterlacing is on, and removed again as soon as it is off,
     * so normal playback never pays for it.
     *
     * Not while the FPS Booster may run: see [effectsPipelineAllowed]. The booster deinterlaces in its own copy pass
     * (DeinterlaceGlsl), so deinterlacing still works there; the upscaler and gamma / sharpness need the player's
     * effects and wait until the video is replayed with the booster off.
     */
    private fun updateVideoEffects() {
        val player = exoPlayer ?: return
        val state = _uiState.value
        val mode = state.deinterlaceMode
        deinterlaceEffect.mode = mode
        colorGradeEffect.gamma = state.colorGamma
        colorGradeEffect.sharpness = state.colorSharpness
        upscaleEffect.mode = state.upscaleMode
        upscaleEffect.strength = state.upscaleStrength
        if (!effectsPipelineAllowed || state.fpsBoosterPlan.active) return
        val wantGrade = needsGradeEffect()
        // The FPS Booster draws the video itself at the file's size, so the upscaler stays out of its way.
        val wantUpscale = state.upscaleMode.isOn && !state.fpsBoosterPlan.active
        val key = (if (mode.isOn) 1 else 0) or (if (wantGrade) 2 else 0) or (if (wantUpscale) 4 else 0)
        // Only touch the player when the set of effects changes; slider moves just update the values above.
        if (key == appliedEffectsKey) return
        effectsInstalled = true
        player.setVideoEffects(buildList {
            if (mode.isOn) add(deinterlaceEffect)
            if (wantGrade) add(colorGradeEffect)
            if (wantUpscale) add(upscaleEffect) // last: it enlarges the frame
        })
        appliedEffectsKey = key
        _uiState.update { withFpsPlan(it) } // a booster that was waiting for "no effects" now knows it cannot start
    }


    fun setPresentationSurface(surface: Surface?) {
        exoPlayer?.setVideoSurface(surface)
    }

    fun getPlayer(): Player? = exoPlayer

    override fun onCleared() {
        if (_uiState.value.routeAudioToEngine) {
            stopEngineRouting(restoreExoAudio = false)
        }
        // Feature 26: leaving the player ends the cast. Remember where the TV was so "continue watching" is right.
        if (CastManager.videoHost === castHost) CastManager.videoHost = null
        castProgressJob?.cancel()
        if (_uiState.value.castDeviceName != null) {
            val resumeAt = CastManager.currentPositionMs().takeIf { it > 0 } ?: positionFlow.value
            exoPlayer?.seekTo(resumeAt)
            CastManager.stopCast()
        }
        super.onCleared()
        getApplication<Application>().unregisterReceiver(volumeReceiver)
        _uiState.value.currentVideo?.let { recordVideoPlayed(it) }
        loudnessEnhancer?.release()
        equalizer?.release()
        extSyncJob?.cancel()
        extPlayer?.release()
        extPlayer = null
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

class VideoPlayerViewModelFactory(private val application: Application, private val videoQueue: List<Video>, private val initialVideoId: String) : ViewModelProvider.Factory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VideoPlayerViewModel(application, videoQueue, initialVideoId) as T
    }
}
