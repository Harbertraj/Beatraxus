package com.beatraxus.app.engine

import android.content.Context
import android.os.Process
import android.util.Log
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.OutputMode
import com.beatraxus.app.model.ResamplerMode
import com.beatraxus.app.model.SampleFormat
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SurroundLayout
import com.beatraxus.app.model.SurroundStage
import com.beatraxus.app.model.resolveSurroundOutputChannels
import com.beatraxus.app.model.resolveSurroundStage
import com.beatraxus.app.model.toSurroundEngineConfig
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.beatraxus.app.repository.DriveAccountRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.math.abs

internal fun computeCrossfadeProgress(remainingMs: Long, crossfadeDurationS: Int): Float {
    val crossfadeMs = crossfadeDurationS * 1000f
    if (crossfadeMs <= 0f) return 0f
    return (remainingMs / crossfadeMs).coerceIn(0f, 1f)
}

enum class VideoRoutePhase {
    IDLE,
    STARTING,
    ACTIVE,
    ERROR
}

data class VideoRouteState(
    val phase: VideoRoutePhase = VideoRoutePhase.IDLE,
    val uri: Uri? = null,
    val audioTrackIndex: Int = 0,
    val title: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val error: String? = null,
    val firstFrameRendered: Boolean = false,
    val firstFrameTimestampMs: Long = 0L,
    /**
     * True for the "5.1 engine" route: the soundtrack goes through the surround simulator only (no Studio DSP
     * effects). False for the "Connect to audio mode" route, which runs the whole Studio DSP chain.
     */
    val surroundEngine: Boolean = false
) {
    val isActive: Boolean get() = phase == VideoRoutePhase.ACTIVE || phase == VideoRoutePhase.STARTING
}

class AudioEngine(
    context: Context,
    private val output: AudioOutput,
    private val cloudCacheManager: com.beatraxus.app.drive.CloudCacheManager,
    private val database: com.beatraxus.app.model.AppDatabase,
    private val tdLibManager: com.beatraxus.app.telegram.TdLibManager
) {
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioRenderDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            r.run()
        }, "AudioRenderThread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val aiEqStore = com.beatraxus.app.engine.aieq.AiEqStore.getInstance(context)
    private val controlMutex = Mutex()
    private val driveAccountRepository = DriveAccountRepository(context)

    private val decoderFactory = DecoderFactory(
        context = context,
        driveAccountRepository = driveAccountRepository,
        cloudCacheManager = cloudCacheManager,
        tdLibManager = tdLibManager,
        ffmpegAlacDecoder = FfmpegAlacDecoder(context, cloudCacheManager, tdLibManager),
        mediaCodecDecoder = MediaCodecAudioDecoder(context, cloudCacheManager, tdLibManager)
    )

    private val audioSpectrumAnalyzer by lazy { AudioSpectrumAnalyzer(context, decoderFactory) }

    internal fun getAudioSpectrumAnalyzer(): AudioSpectrumAnalyzer = audioSpectrumAnalyzer

    private val _audioStateFlow = MutableStateFlow(AudioState())
    val audioStateFlow = _audioStateFlow.asStateFlow()

    private val _playbackStateFlow = MutableStateFlow(PlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private val _videoRouteStateFlow = MutableStateFlow(VideoRouteState())
    val videoRouteStateFlow = _videoRouteStateFlow.asStateFlow()

    @Volatile
    private var videoSession: PlaybackSession? = null

    private fun isSessionPlaying(session: PlaybackSession): Boolean {
        if (videoSession?.sessionId == session.sessionId) {
            return _videoRouteStateFlow.value.isPlaying
        }
        return _playbackStateFlow.value.isPlaying
    }

    fun isVideoAudioActive(): Boolean = _videoRouteStateFlow.value.isActive
    fun isVideoAudioReady(): Boolean = _videoRouteStateFlow.value.firstFrameRendered

    fun videoAudioPositionMs(): Long {
        return videoSession?.currentRenderedPositionMs() ?: _videoRouteStateFlow.value.positionMs
    }

    fun videoAudioLatencyFrames(): Int {
        return videoSession?.dspPipeline?.getLatencyFrames() ?: 0
    }

    @OptIn(UnstableApi::class)
    fun playVideoAudio(
        uri: Uri,
        audioTrackIndex: Int,
        startPositionMs: Long,
        startPlaying: Boolean,
        title: String,
        codecMime: String? = null,
        surroundEngine: Boolean = false
    ) {
        Log.d("VideoAudioRouting", "playVideoAudio requested: uri=$uri, audioTrackIndex=$audioTrackIndex, title=$title, surroundEngine=$surroundEngine")
        // Whatever the previous stream (a song, the other video route) said about surround is stale for this one;
        // the real answer is published once the output is open (see publishDspState).
        _audioStateFlow.update { it.copy(surroundActive = false, surroundDiscrete = false) }
        _videoRouteStateFlow.update {
            VideoRouteState(
                phase = VideoRoutePhase.STARTING,
                uri = uri,
                audioTrackIndex = audioTrackIndex,
                title = title,
                isPlaying = startPlaying,
                positionMs = startPositionMs,
                error = null,
                surroundEngine = surroundEngine
            )
        }

        engineScope.launch {
            controlMutex.withLock {
                if (_playbackStateFlow.value.isPlaying) {
                    userIntentPlaying.set(false)
                    output.pause()
                    _playbackStateFlow.update { it.copy(isPlaying = false) }
                }

                stopVideoAudioInternal(emitIdle = false)
                // Must be set before the session (and its DSP pipeline) is created: they read the effective config.
                setSurroundEngineRoute(surroundEngine)

            val mappedFormat = when (codecMime) {
                MimeTypes.AUDIO_AC3 -> "ac3"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X -> "dts"
                MimeTypes.AUDIO_TRUEHD -> "truehd"
                else -> "video"
            }

            val videoSong = Song(
                id = "video_route:${uri.hashCode()}_${audioTrackIndex}",
                uri = uri,
                title = title,
                artist = "Video Player",
                album = "Video Audio",
                durationMs = 0L,
                format = mappedFormat,
                sampleRateHz = 48000
            )

                val sessionId = currentSessionId.incrementAndGet()
                val session = PlaybackSession(
                    sessionId = sessionId,
                    song = videoSong,
                    initialStartPositionMs = startPositionMs,
                    preferredAudioTrackIndex = audioTrackIndex
                )

                videoSession = session
                activeSession = session

                _videoRouteStateFlow.update {
                    it.copy(
                        phase = VideoRoutePhase.ACTIVE,
                        uri = uri,
                        audioTrackIndex = audioTrackIndex,
                        title = title,
                        isPlaying = startPlaying,
                        positionMs = startPositionMs,
                        error = null
                    )
                }
                Log.d("VideoAudioRouting", "Video route state -> ACTIVE")

                if (startPlaying) {
                    output.start()
                }

                engineScope.launch(Dispatchers.IO) {
                    try {
                        session.run()
                    } catch (t: Throwable) {
                        if (t !is CancellationException) {
                            Log.e(TAG, "Video audio session $sessionId failed", t)
                            controlMutex.withLock {
                                _videoRouteStateFlow.update {
                                    it.copy(phase = VideoRoutePhase.ERROR, isPlaying = false, error = t.message ?: "Session failed")
                                }
                                Log.w("VideoAudioRouting", "Video route state -> ERROR (${t.message})")
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopVideoAudio() {
        engineScope.launch {
            controlMutex.withLock {
                stopVideoAudioInternal(emitIdle = true)
                output.stop()
            }
        }
    }

    private fun stopVideoAudioInternal(emitIdle: Boolean = true) {
        val vSession = videoSession
        if (vSession != null) {
            vSession.stop()
            if (activeSession?.sessionId == vSession.sessionId) {
                activeSession = null
            }
            videoSession = null
        }
        setSurroundEngineRoute(false)
        if (emitIdle) {
            _videoRouteStateFlow.update { VideoRouteState(phase = VideoRoutePhase.IDLE) }
            Log.d("VideoAudioRouting", "Video route state -> IDLE")
        }
    }

    private var lastResyncTimeMs = 0L

    fun requestVideoAudioResync(positionMs: Long, driftMs: Long) {
        engineScope.launch {
            val session = activeSession ?: videoSession ?: return@launch
            
            val now = System.currentTimeMillis()
            if (session.isSeekPending() || !isVideoAudioReady() || now - lastResyncTimeMs < 3000L) {
                return@launch
            }

            lastResyncTimeMs = now

            if (abs(driftMs) < 250L) {
                // Soft correction: adjust base position without hard-seeking FFmpeg
                val effectiveDriftMs = if (driftMs < 0) maxOf(driftMs, -120L) else driftMs
                session.adjustBasePositionForDrift(effectiveDriftMs)
                session.triggerFadeIn(30, session.pcmFormat?.sampleRate ?: 48000)
                Log.d("VideoAudioRouting", "Soft resynced video audio engine for drift ${effectiveDriftMs}ms. NO DOWNMIX APPLIED.")
            } else {
                // Hard seek
                session.triggerFadeIn(30, session.pcmFormat?.sampleRate ?: 48000)
                session.requestSeek(positionMs)
                Log.d("VideoAudioRouting", "Hard resynced video audio engine to position $positionMs ms (drift ${driftMs}ms) with 30ms fade-in. NO DOWNMIX APPLIED.")
            }
        }
    }

    fun pauseVideoAudio() {
        engineScope.launch {
            controlMutex.withLock {
                if (_videoRouteStateFlow.value.isActive) {
                    output.pause()
                    _videoRouteStateFlow.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    fun resumeVideoAudio() {
        engineScope.launch {
            controlMutex.withLock {
                if (_videoRouteStateFlow.value.isActive) {
                    _videoRouteStateFlow.update { it.copy(isPlaying = true) }
                    output.start()
                }
            }
        }
    }

    fun seekVideoAudio(positionMs: Long) {
        engineScope.launch {
            controlMutex.withLock {
                val vSession = videoSession
                if (vSession != null && _videoRouteStateFlow.value.isActive) {
                    vSession.requestSeek(positionMs)
                    _videoRouteStateFlow.update { it.copy(positionMs = positionMs) }
                }
            }
        }
    }

    private val _onCompletion = MutableSharedFlow<Unit>()
    val onCompletion = _onCompletion.asSharedFlow()

    private var currentSong: Song? = null
    private var nextSong: Song? = null

    private var currentSessionId = AtomicLong(0)
    @Volatile
    private var activeSession: PlaybackSession? = null
    @Volatile
    private var nextSession: PlaybackSession? = null
    @Volatile
    private var fadingOutSession: PlaybackSession? = null

    private var rendererJob: Job? = null
    private val reconfigChannel = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile private var positionMs: Long = 0L
    private val isReleased = AtomicBoolean(false)
    /** The settings the user saved in Studio DSP, untouched. */
    @Volatile private var userDspConfig: DspConfig = DspConfig()

    /** True while a video soundtrack runs through the "5.1 engine" (surround simulator only, no DSP effects). */
    @Volatile private var surroundEngineRoute = false
    private var surroundEngineConfigCache: Pair<DspConfig, DspConfig>? = null

    /**
     * The settings the audio path really runs with: the user's own, except on the 5.1-engine route where every
     * effect is neutral and the surround simulator is forced on (see [toSurroundEngineConfig]).
     */
    private val dspConfig: DspConfig
        get() {
            val user = userDspConfig
            if (!surroundEngineRoute) return user
            val cached = surroundEngineConfigCache
            if (cached != null && cached.first === user) return cached.second
            return user.toSurroundEngineConfig().also { surroundEngineConfigCache = user to it }
        }

    private fun setSurroundEngineRoute(enabled: Boolean) {
        if (surroundEngineRoute == enabled) return
        surroundEngineRoute = enabled
        dspRevision.incrementAndGet()
    }
    private val dspRevision = AtomicLong(0L)
    private val isSeeking = AtomicBoolean(false)
    private val userIntentPlaying = AtomicBoolean(false)

    init {
        startRenderer()

        // Debounce reconfiguration requests to prevent app crash when user rapidly toggles settings
        engineScope.launch {
            reconfigChannel.collectLatest {
                delay(200)
                performReconfigureOutput()
            }
        }
    }

    private fun startRenderer() {
        rendererJob?.cancel()
        rendererJob = engineScope.launch(audioRenderDispatcher) {
            renderLoop()
        }
    }

    fun currentPositionMs(): Long {
        if (isSeeking.get()) return positionMs
        return activeSession?.currentRenderedPositionMs() ?: positionMs
    }

    /**
     * Play Together: true when the prepared (paused) song has decoded audio waiting, so play() is heard at once
     * and the position is where it was asked to start.
     */
    fun isPrepared(): Boolean {
        // A song that was just loaded (prepare) waits in nextSession until play() promotes it; only a song
        // that is already playing / paused in place is the activeSession. Looking at activeSession alone
        // reported "not ready" for every freshly loaded song, so followers waited for the start gate's
        // time-out with the song loaded but paused.
        val session = activeSession
            ?: nextSession?.takeIf { nextSong?.id != null && nextSong?.id == currentSong?.id }
            ?: return false
        if (isSeeking.get() || session.isSeekPending()) return false
        val format = session.pcmFormat ?: return false
        val channels = format.channels.coerceAtLeast(1)
        // ~90 ms of decoded audio is enough for the output to start without an underrun
        return session.ringBuffer.availableRead() >= (format.sampleRate.coerceAtLeast(8000) * channels * 90L / 1000L).toInt()
    }

    /**
     * Play Together: moves what is heard by [driftMs] without a seek, sample-accurately.
     * driftMs > 0: this phone is late, so that much already-decoded audio is skipped.
     * driftMs < 0: this phone is early, so silence is inserted. A 4 ms fade-in hides the seam.
     */
    fun nudgePosition(driftMs: Long) {
        engineScope.launch {
            controlMutex.withLock {
                val session = activeSession ?: return@withLock
                if (session.isSeekPending() || isSeeking.get()) return@withLock
                val format = session.pcmFormat ?: return@withLock
                val channels = format.channels.coerceAtLeast(1)
                val rate = format.sampleRate.coerceAtLeast(8000)
                val bufferedMs = session.ringBuffer.availableRead().toLong() * 1000L / (rate.toLong() * channels)
                val applied = com.beatraxus.app.features.PtSyncMath.nudgeLimitMs(driftMs, bufferedMs)
                if (applied == 0L) return@withLock
                session.adjustBasePositionForDrift(applied)
                session.triggerFadeIn(4, rate)
            }
        }
    }

    fun play(song: Song) {
        engineScope.launch {
            controlMutex.withLock {
                // Promotion logic: if we have this song preloaded as nextSession, promote it!
                if (nextSession != null && nextSong?.id == song.id) {
                    val oldSession = activeSession
                    activeSession = nextSession
                    val newSessionId = activeSession?.sessionId ?: 0L
                    nextSession = null
                    currentSong = song
                    nextSong = null
                    // A seek that was pending on the previous song must not freeze this song's runtime.
                    isSeeking.set(false)

                    // Use write-cursor (totalFramesWritten) as the reference for live-track continuation,
                    // so the counter only starts once the new song's first written sample is rendered.
                    activeSession?.setStartFrameOffset(output.totalFramesWritten())

                    // Don't reset positionMs to 0 if we are promoting a prepared session
                    // instead, sync it with the session's current internal position.
                    this@AudioEngine.positionMs = activeSession?.currentRenderedPositionMs() ?: 0L

                    userIntentPlaying.set(true)
                    updateAudioStateForSong(song)
                    _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true, sessionId = newSessionId) }

                    // Trigger output reconfiguration for the promoted session
                    val fmt = activeSession?.pcmFormat
                    if (fmt != null) {
                        activeSession?.configure(fmt)
                    } else {
                        // If format is not yet known (still probing), output.start() is a no-op.
                        // The session will automatically call configure() and start() once the decoder 
                        // identifies the stream format.
                    }

                    // Stop old session after promotion to avoid gap
                    oldSession?.stop()
                    return@withLock
                }

                // If we are already playing this song, just ensure it's playing
                if (currentSong?.id == song.id && activeSession != null) {
                    if (!_playbackStateFlow.value.isPlaying) {
                        resume()
                    }
                    return@withLock
                }

                // STOP FIRST to avoid overwriting positionMs in stopSessionsInternal
                stopSessionsInternal()

                currentSong = song
                nextSong = null
                positionMs = 0L
                updateAudioStateForSong(song)

                userIntentPlaying.set(true)
                _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }

                startSessionInternal(song, startPositionMs = 0L)
            }
        }
    }

    fun preloadNext(song: Song) {
        engineScope.launch {
            preloadNextInternal(song)
        }
    }

    private suspend fun preloadNextInternal(song: Song) {
        controlMutex.withLock {
            if (nextSong?.id == song.id && nextSession != null) return@withLock

            // Log.d(TAG, "Preloading next song: ${song.title}")
            nextSong = song
            nextSession?.stop()

            val sessionId = currentSessionId.incrementAndGet()
            val session = PlaybackSession(sessionId, song, 0L)
            nextSession = session

            engineScope.launch(Dispatchers.IO) {
                session.run()
            }
        }
    }

    fun prepare(song: Song, startPositionMs: Long = 0L) {
        engineScope.launch {
            controlMutex.withLock {
                if (currentSong?.id == song.id && (activeSession != null || nextSession != null)) {
                    // Even if already present, ensure state is correct
                    _playbackStateFlow.update { it.copy(currentSong = song) }
                    return@withLock
                }

                // If it's not already preloaded, clear and start preloading
                if (nextSong?.id != song.id) {
                    nextSession?.stop()
                    nextSession = null
                    nextSong = null
                }

                // Stop current session if different
                if (activeSession?.song?.id != song.id) {
                    activeSession?.stop()
                    activeSession = null
                    // The old session's pending seek dies with it; otherwise currentPositionMs() keeps
                    // returning the previous song's position.
                    isSeeking.set(false)
                }

                currentSong = song
                positionMs = startPositionMs
                updateAudioStateForSong(song)
                _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = false) }

                if (activeSession == null && nextSession == null) {
                    // Preload immediately while holding lock to avoid race with play()
                    val sessionId = currentSessionId.incrementAndGet()
                    val session = PlaybackSession(sessionId, song, startPositionMs)
                    nextSession = session
                    nextSong = song
                    engineScope.launch(Dispatchers.IO) {
                        session.run()
                    }
                }
            }
        }
    }

    private fun updateAudioStateForSong(song: Song) {
        output.resetUnderrunCount()
        _audioStateFlow.update {
            it.copy(
                songId = song.id,
                codec = normalizeCodec(song.format),
                bitrate = song.bitrate,
                bitDepth = song.bitDepth,
                outputBitDepth = output.outputBitDepth(),
                outputPath = output.outputPathLabel(),
                outputDevice = output.outputDeviceLabel(),
                dynamicVolumeControlActive = dspConfig.dvcEnabled,
                resamplerActive = resolveTargetSampleRate(song.sampleRateHz, dspConfig) != song.sampleRateHz
            )
        }
    }

    fun resume() {
        engineScope.launch {
            controlMutex.withLock {
                val song = currentSong ?: return@withLock
                if (_playbackStateFlow.value.isPlaying) return@withLock

                // Promotion logic: if we have this song preloaded as nextSession, promote it!
                if (nextSession != null && nextSong?.id == song.id) {
                    val oldSession = activeSession
                    activeSession = nextSession
                    val newSessionId = activeSession?.sessionId ?: 0L
                    nextSession = null
                    nextSong = null
                    isSeeking.set(false)

                    // Use write-cursor (totalFramesWritten) as the reference for live-track continuation,
                    // so the counter only starts once the new song's first written sample is rendered.
                    activeSession?.setStartFrameOffset(output.totalFramesWritten())

                    this@AudioEngine.positionMs = activeSession?.currentRenderedPositionMs() ?: positionMs

                    userIntentPlaying.set(true)
                    _playbackStateFlow.update { it.copy(isPlaying = true, sessionId = newSessionId) }

                    val fmt = activeSession?.pcmFormat
                    if (fmt != null) {
                        activeSession?.configure(fmt)
                    } else {
                        // If format is not yet known (still probing), output.start() is a no-op.
                        // The session will automatically call configure() and start() once the decoder 
                        // identifies the stream format.
                    }

                    oldSession?.stop()
                    return@withLock
                }

                // Set isPlaying = true EAGERLY to ensure UI responsiveness
                userIntentPlaying.set(true)
                _playbackStateFlow.update { it.copy(isPlaying = true) }

                if (activeSession == null) {
                    startSessionInternal(song, startPositionMs = currentPositionMs())
                } else {
                    output.start()
                }
            }
        }
    }

    fun stop() {
        engineScope.launch {
            stopSync()
        }
    }

    suspend fun stopSync() {
        controlMutex.withLock {
            userIntentPlaying.set(false)
            stopSessionsInternal()
            output.stop()
            _playbackStateFlow.update { it.copy(isPlaying = false) }
        }
    }

    fun pause() {
        engineScope.launch {
            controlMutex.withLock {
                userIntentPlaying.set(false)
                output.pause()
                _playbackStateFlow.update { it.copy(isPlaying = false) }
            }
        }
    }

    private fun stopSessionsInternal() {
        positionMs = activeSession?.currentRenderedPositionMs() ?: positionMs
        activeSession?.stop()
        activeSession = null
        nextSession?.stop()
        nextSession = null
        isSeeking.set(false)
    }

    fun seekTo(positionMs: Long) {
        Log.d("AudioEngine", "seekTo requested: $positionMs ms")
        engineScope.launch {
            controlMutex.withLock {
                this@AudioEngine.positionMs = positionMs
                val session = activeSession
                if (session != null) {
                    isSeeking.set(true)
                    session.requestSeek(positionMs)
                } else {
                    isSeeking.set(false)
                }
            }
        }
    }

    /**
     * Fully tears down and re-creates the playback session for the current song at
     * [positionMs], forcing the decoder to re-resolve its data source (cached file vs.
     * live network/window stream) from scratch.
     *
     * A plain seekTo() is NOT enough for this: seeking only calls requestSeek() on the
     * already-running PlaybackSession, which reuses the MediaExtractor/data source that
     * was opened when the session started. That "use cache or not" decision is made once,
     * at session start, inside the decoder (MediaCodecAudioDecoder / FfmpegAlacDecoder /
     * DecoderFactory). Since we now exclusively use no-cache streaming for all cloud songs,
     * this restart ensures any change in playback strategy is applied immediately.
     *
     * Call this instead of seekTo() whenever a setting that changes *how* the current
     * song's data source is opened changes mid-playback.
     */
    fun restartCurrentSession(positionMs: Long) {
        Log.d("AudioEngine", "restartCurrentSession requested at $positionMs ms")
        engineScope.launch {
            controlMutex.withLock {
                val song = currentSong ?: return@withLock
                val wasPlaying = _playbackStateFlow.value.isPlaying
                isSeeking.set(false)

                // Tear down the current (and any preloaded next) session outright, rather
                // than just requesting a seek, so the decoder is created fresh and re-checks
                // the current no-cache-streaming flag when it resolves its data source.
                activeSession?.stop()
                activeSession = null
                nextSession?.stop()
                nextSession = null
                nextSong = null

                this@AudioEngine.positionMs = positionMs
                userIntentPlaying.set(wasPlaying)
                _playbackStateFlow.update { it.copy(isPlaying = wasPlaying) }

                startSessionInternal(song, startPositionMs = positionMs)
            }
        }
    }

    fun setShuffleMode(enabled: Boolean) {
        _playbackStateFlow.update { it.copy(shuffleMode = enabled) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _playbackStateFlow.update { it.copy(repeatMode = mode) }
    }

    // ---- Live pipeline stats (latency / headroom / underruns) -------------------------------
    // These used to be published once per track start, so the Signal Path sheet always showed
    // 0. They are now refreshed while audio is playing.
    private var peakHoldLin = 0f

    private fun startLiveStatsPolling() {
        engineScope.launch {
            while (isActive) {
                delay(LIVE_STATS_INTERVAL_MS)
                val playing = _playbackStateFlow.value.isPlaying || _videoRouteStateFlow.value.isPlaying
                if (playing) {
                    try { publishLiveStats() } catch (e: Exception) { Log.w(TAG, "live stats failed: ${e.message}") }
                }
            }
        }
    }

    private fun publishLiveStats() {
        val pipeline = (activeSession ?: videoSession)?.dspPipeline

        // Headroom: engine auto-headroom (EQ boost compensation) when active, otherwise the
        // real distance of the signal peak to 0 dBFS (peak-hold with slow decay).
        val autoHeadroom = pipeline?.getHeadroomDb() ?: 0f
        val levels = pipeline?.getLevels() ?: floatArrayOf(0f, 0f)
        val peak = maxOf(levels.getOrElse(0) { 0f }, levels.getOrElse(1) { 0f })
        peakHoldLin = maxOf(peak, peakHoldLin * 0.9f)
        val peakHeadroom = if (peakHoldLin > 1e-4f) {
            (-20f * kotlin.math.log10(peakHoldLin)).coerceIn(0f, 96f)
        } else null

        // Latency: real queued output frames + EQ processing delay.
        val eqFrames = pipeline?.getLatencyFrames() ?: 0
        val totalLatencyFrames = output.queuedLatencyFrames() + eqFrames
        val underruns = output.underrunCount()
        val outLatencyMs = output.estimatedLatencyMs()

        // Quantise so the UI only recomposes on meaningful changes.
        val headroomQ = when {
            autoHeadroom != 0f -> autoHeadroom
            peakHeadroom != null -> Math.round(peakHeadroom * 2f) / 2f
            else -> null
        }
        val latencyQ = (totalLatencyFrames / 16) * 16

        _audioStateFlow.update { st ->
            st.copy(
                headroomDb = headroomQ ?: st.headroomDb,
                latencyFrames = latencyQ,
                outputLatencyMs = outLatencyMs,
                underrunCount = underruns
            )
        }
    }

    init { startLiveStatsPolling() }

    fun release() {
        if (!isReleased.compareAndSet(false, true)) {
            Log.w(TAG, "release() called more than once — ignoring redundant call")
            return
        }
        runBlocking {
            stopSync()
        }
        rendererJob?.cancel()
        output.release()
        engineScope.cancel()
    }

    fun reconfigureOutput() {
        reconfigChannel.tryEmit(Unit)
    }

    private suspend fun performReconfigureOutput() {
        controlMutex.withLock {
            val session = activeSession
            if (session != null && session.isActive() && session.pcmFormat != null) {
                // Also covers a video soundtrack routed through the engine (currentSong stays null for it).
                session.requestOutputRestart()
                return@withLock
            }
            val song = currentSong ?: return@withLock
            if (_playbackStateFlow.value.isPlaying) {
                // Restart from current position without artificial skip
                val resumePositionMs = currentPositionMs() + OUTPUT_RECONFIG_SKIP_MS
                startSessionInternal(song, resumePositionMs)
            } else {
                publishDspState(activeSession?.pcmFormat)
            }
        }
    }

    /**
     * Applies a freshly predicted AI EQ [profile] to the running DSP pipeline if it belongs to the song that is
     * playing. The pipeline only looks the profile up when a song starts, so a profile that arrives while the song
     * is already playing has to be pushed in.
     */
    fun applyAiEq(profile: com.beatraxus.app.engine.aieq.AiEqProfile) {
        engineScope.launch {
            try {
                val session = activeSession ?: return@launch
                if (session.song.id != profile.songId) return@launch
                session.dspLock.writeLock().withLock {
                    session.dspPipeline.updateAiEq(profile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI EQ apply failed for ${profile.songId}", e)
            }
        }
    }

    // Last values pushed to the output layer (null = never pushed, so the first call pushes all).
    private var pushedDvc: Triple<Boolean, String, Float>? = null
    private var pushedDither: Pair<Boolean, Int>? = null
    private var pushedRoute: Pair<Boolean, Boolean>? = null
    private var pushedBuffer: List<Int>? = null
    private var pushedMmap: Pair<Boolean, Int>? = null

    fun updateDspConfig(userConfig: DspConfig) {
        val oldConfig = dspConfig
        val sourceSampleRate = currentSong?.sampleRateHz ?: activeSession?.pcmFormat?.sampleRate ?: 44100
        val oldTargetRate = resolveTargetSampleRate(sourceSampleRate, oldConfig)
        userDspConfig = userConfig
        // Everything below works with what the audio path really runs with (differs on the 5.1-engine route).
        val config = dspConfig
        val newTargetRate = resolveTargetSampleRate(sourceSampleRate, config)

        // Each output setter takes the output's write lock. Only push a group when its value
        // actually changed: a Spatial slider drag fires this function many times a second, and
        // holding that lock makes position reads fail and blocks the UI thread.
        val dvcKey = Triple(config.dvcEnabled, config.dvcMode.name, config.dvcLevel)
        if (dvcKey != pushedDvc) {
            pushedDvc = dvcKey
            output.setDvcState(
                enabled = config.dvcEnabled,
                mode = config.dvcMode.name,
                level = config.dvcLevel
            )
        }

        val ditherKey = (config.ditherEnabled && config.ditherType != com.beatraxus.app.model.DitherType.NONE) to
            config.ditherType.nativeValue
        if (ditherKey != pushedDither) {
            pushedDither = ditherKey
            output.setDitherState(enabled = ditherKey.first, type = ditherKey.second)
        }

        // Forward USB Exclusive Mode / Bit-Perfect to output layer
        val routeKey = config.usbExclusiveEnabled to config.bitPerfectEnabled
        if (routeKey != pushedRoute) {
            pushedRoute = routeKey
            output.setUsbExclusiveMode(config.usbExclusiveEnabled)
            output.setBitPerfectMode(config.bitPerfectEnabled)
        }

        val bufferKey = listOf(
            (config.outputBufferMs * sourceSampleRate) / 1000,
            config.outputBufferCount.coerceIn(2, 4),
            (config.postFadeBufferMs * sourceSampleRate) / 1000
        )
        if (bufferKey != pushedBuffer) {
            pushedBuffer = bufferKey
            output.setBufferConfig(
                bufferFrames = bufferKey[0],
                bufferCount = bufferKey[1],
                postFadeFrames = bufferKey[2]
            )
        }

        // Forward MMAP Exclusive Mode
        val mmapKey = (config.outputMode == OutputMode.MMAP_EXCLUSIVE) to
            ((config.outputBufferMs * sourceSampleRate) / 1000)
        if (mmapKey != pushedMmap) {
            pushedMmap = mmapKey
            output.setMmapExclusiveMode(enabled = mmapKey.first, requestedBufferFrames = mmapKey.second)
        }

        val outputModeChanged = oldConfig.outputMode != config.outputMode
        val usbExclusiveChanged = oldConfig.usbExclusiveEnabled != config.usbExclusiveEnabled
        val bitPerfectChanged = oldConfig.bitPerfectEnabled != config.bitPerfectEnabled
        val soxrQualityChanged = oldConfig.soxrQuality != config.soxrQuality
        if (outputModeChanged || oldTargetRate != newTargetRate) {
            output.setTargetSampleRate(newTargetRate)
            if (outputModeChanged && output is AudioTrackOutput) {
                output.setOutputMode(config.outputMode)
            }
        }

        publishDspState()

        // 5.1/7.1 Surround Simulator: enabling/disabling it, switching 5.1 <-> 7.1, or anything that forces it
        // off (Bit-Perfect, A/B bypass) changes the channel count the output must be opened with.
        // A "device refused 6/8 channels" verdict only holds for the exact settings that were refused.
        if (oldConfig.surroundEnabled != config.surroundEnabled || oldConfig.surroundLayout != config.surroundLayout) {
            surroundRejectedChannels = 0
        }
        val surroundFormat = activeSession?.pcmFormat
        val surroundChanged = surroundFormat != null &&
                surroundTargetChannels(oldConfig, surroundFormat) !=
                surroundTargetChannels(config, surroundFormat)

        val structuralChange = oldTargetRate != newTargetRate ||
                oldConfig.sampleFormat != config.sampleFormat ||
                outputModeChanged ||
                usbExclusiveChanged ||
                bitPerfectChanged ||
                oldConfig.dvcEnabled != config.dvcEnabled ||
                surroundChanged

        dspRevision.incrementAndGet()

        if (structuralChange) {
            reconfigureOutput()
        }
    }

    /**
     * Channel count the output should be opened with for [config]: 6 (5.1) or 8 (7.1) while the surround
     * simulator upmixes this stream, otherwise exactly the source's own channel count (= behaviour before the
     * simulator existed). Honours a previous "output refused this width" verdict.
     */
    private fun surroundTargetChannels(config: DspConfig, format: PcmAudioFormat): Int {
        val wanted = resolveSurroundOutputChannels(
            config = config,
            sourceChannels = format.channels,
            isDoP = format.isDoP,
            // Only the "Auto" layout needs to know what the device can do; skip the query otherwise.
            deviceMaxChannels = if (config.surroundEnabled && config.surroundLayout == SurroundLayout.AUTO) {
                output.maxOutputChannels()
            } else {
                2
            }
        )
        return if (wanted != format.channels && wanted == surroundRejectedChannels) format.channels else wanted
    }

    /** 6 / 8 when the output is open wider than a mono/stereo source (the surround stage must upmix), else 0. */
    private fun surroundChannelsForOutput(format: PcmAudioFormat): Int {
        val deviceChannels = output.outputChannelCount()
        return if (deviceChannels > 2 && format.channels in 1..2 && !format.isDoP) deviceChannels else 0
    }

    /**
     * True when the source already is a real 5.1 / 7.1 mix that the surround stage must keep discrete (only trims
     * apply). Mirrors [surroundChannelsForOutput] for the multichannel case.
     */
    private fun discreteSurroundForOutput(format: PcmAudioFormat): Boolean =
        resolveSurroundStage(dspConfig, format.channels, format.isDoP, output.outputChannelCount()) == SurroundStage.DISCRETE

    /** Width the output refused while the surround simulator was on (0 = none). Reset when the user changes it. */
    @Volatile private var surroundRejectedChannels = 0

    private fun startSessionInternal(song: Song, startPositionMs: Long) {
        val sessionId = currentSessionId.incrementAndGet()
        val session = PlaybackSession(sessionId, song, startPositionMs)
        activeSession = session
        _playbackStateFlow.update { it.copy(sessionId = sessionId) }

        engineScope.launch(Dispatchers.IO) {
            try {
                session.run()
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    Log.e(TAG, "Playback session $sessionId failed", t)
                }
            }
        }
    }

    private var lastDiagnosticsTimeMs = 0L

    private suspend fun renderLoop() {
        val localBuffer = FloatArray(RENDER_BATCH_SAMPLES)
        val localBufferNext = FloatArray(RENDER_BATCH_SAMPLES)

        while (engineScope.isActive) {
            val session = activeSession
            if (session == null) {
                delay(10)
                continue
            }

            val format = session.pcmFormat
            if (format == null) {
                delay(2)
                continue
            }

            if (!isSessionPlaying(session)) {
                delay(10)
                continue
            }

            val crossfadeDurationS = dspConfig.crossfadeDurationS

            // Check if we should start crossfading into next track
            if (crossfadeDurationS > 0 && fadingOutSession == null && nextSession?.pcmFormat != null) {
                val remainingMs = session.song.durationMs - session.currentRenderedPositionMs()
                if (remainingMs <= crossfadeDurationS * 1000L) {
                    controlMutex.withLock {
                        fadingOutSession = activeSession
                        activeSession = nextSession
                        val newSessionId = activeSession?.sessionId ?: 0L
                        nextSession = null
                        currentSong = activeSession?.song
                        val currentHwPos = output.totalFramesWritten()
                        activeSession?.setStartFrameOffset(currentHwPos)
                        updateAudioStateForSong(currentSong!!)
                        this@AudioEngine.positionMs = 0L
                        _playbackStateFlow.update { it.copy(currentSong = currentSong, sessionId = newSessionId) }
                    }
                }
            }

            // Reflects activeSession AFTER any crossfade swap above
            val targetSession = activeSession ?: session
            val targetSessionId = targetSession.sessionId

            val channels = format.channels.coerceAtLeast(1)
            val samplesToRead = AudioFrameUtils.calculateBatchSamplesToRead(localBuffer.size, channels)
            
            var sampleCount = 0
            val silenceRemaining = targetSession.pendingSilenceSamples.get()
            if (silenceRemaining > 0) {
                val toRead = minOf(silenceRemaining, samplesToRead)
                sampleCount = AudioFrameUtils.calculateAlignedSampleCount(toRead, channels)
                localBuffer.fill(0f, 0, sampleCount)
                targetSession.pendingSilenceSamples.addAndGet(-sampleCount)
            } else {
                val rawSampleCount = targetSession.ringBuffer.read(localBuffer, samplesToRead)
                sampleCount = AudioFrameUtils.calculateAlignedSampleCount(rawSampleCount, channels)
            }

            if (sampleCount > 0) {
                targetSession.applyFadeIn(localBuffer, sampleCount, channels)

                val now = System.currentTimeMillis()
                if (now - lastDiagnosticsTimeMs > 1000L) {
                    lastDiagnosticsTimeMs = now
                    if (targetSession.song.id.startsWith("video_route:")) {
                        Log.d(
                            "VideoAudioRouting",
                            "VideoAudioRouting: channels=$channels, sampleRate=${format.sampleRate}, " +
                            "codec=${format.codec}, underruns=${_audioStateFlow.value.underrunCount}. NO DOWNMIX APPLIED."
                        )
                    }
                }

                val currentRevision = dspRevision.get()

                val processed = targetSession.dspLock.readLock().withLock {
                    if (appliedDspRevision != currentRevision) {
                        targetSession.dspPipeline.updateConfig(dspConfig)
                        appliedDspRevision = currentRevision
                    }

                    // Handle fading in current session (if it just started crossfade)
                    // and fading out the previous session
                    val outSession = fadingOutSession
                    if (outSession != null) {
                        val outSamplesToRead = AudioFrameUtils.calculateBatchSamplesToRead(sampleCount, format.channels)
                        val rawOut = outSession.ringBuffer.read(localBufferNext, outSamplesToRead)
                        val outSampleCount = AudioFrameUtils.calculateAlignedSampleCount(rawOut, format.channels)
                        if (outSampleCount > 0) {
                            val remainingMs = outSession.song.durationMs - outSession.currentRenderedPositionMs()
                            val t = computeCrossfadeProgress(remainingMs, crossfadeDurationS)

                            for (i in 0 until outSampleCount step format.channels) {
                                val gainOut = kotlin.math.sqrt(t)
                                val gainIn = kotlin.math.sqrt(1f - t)

                                for (c in 0 until format.channels) {
                                    val idx = i + c
                                    if (idx < sampleCount) {
                                        localBuffer[idx] = localBuffer[idx] * gainIn + localBufferNext[idx] * gainOut
                                    }
                                }
                            }
                        }
                        if (outSession.decoderCompleted && outSession.ringBuffer.isEmpty()) {
                            fadingOutSession = null
                        }
                    }

                    // 5.1/7.1 Surround Simulator: the output is the source of truth for the channel layout.
                    // Whatever session is playing (current, pre-loaded gapless next, rebuilt after a rate
                    // change) follows it here, so pipeline and output can never stay out of step.
                    targetSession.dspPipeline.syncSurroundChannels(surroundChannelsForOutput(format))
                    targetSession.dspPipeline.syncDiscreteTrims(discreteSurroundForOutput(format))

                    targetSession.dspPipeline.process(localBuffer, sampleCount, format.channels, format.sampleRate)
                }

                // The surround stage widens a stereo stream to 6/8 channels; every other path keeps the source layout.
                val outChannels = if (processed.channels > 0) processed.channels else format.channels

                // Output and pipeline change layout in two separate steps (see PlaybackSession.configure). In the
                // few milliseconds they disagree, drop this batch rather than write N-channel audio into an
                // M-channel track. Plain stereo/multichannel playback never takes this branch.
                val deviceChannels = output.outputChannelCount()
                if (deviceChannels > 0 && outChannels != deviceChannels &&
                    (processed.channels > 0 || deviceChannels != format.channels)
                ) {
                    delay(2)
                    continue
                }

                val frames = processed.sampleCount / outChannels
                var writtenFramesTotal = 0

                while (writtenFramesTotal < frames && engineScope.isActive && activeSession?.sessionId == targetSessionId && isSessionPlaying(targetSession)) {
                    val written = if (processed.isDoP && processed.intData != null) {
                        output.writeInt(
                            data = processed.intData,
                            offsetInSamples = writtenFramesTotal * outChannels,
                            frameCount = frames - writtenFramesTotal
                        )
                    } else {
                        output.write(
                            data = processed.data,
                            offsetInSamples = writtenFramesTotal * outChannels,
                            frameCount = frames - writtenFramesTotal
                        )
                    }
                    if (written <= 0) {
                        delay(2)
                        continue
                    }
                    writtenFramesTotal += written
                    
                    if (targetSession.song.id.startsWith("video_route:") && !_videoRouteStateFlow.value.firstFrameRendered) {
                        _videoRouteStateFlow.update { 
                            it.copy(firstFrameRendered = true, firstFrameTimestampMs = System.currentTimeMillis()) 
                        }
                    }
                }

                continue
            }

            // Still update position even if no samples were read (e.g. throttled).
            if (engineScope.isActive && !isSeeking.get() && isSessionPlaying(targetSession)) {
                this@AudioEngine.positionMs = targetSession.currentRenderedPositionMs()
            }

            if (session.decoderCompleted && session.ringBuffer.isEmpty() && isSessionPlaying(session)) {
                // TRACK COMPLETED
                controlMutex.withLock {
                    if (videoSession?.sessionId == session.sessionId) {
                        stopVideoAudioInternal()
                    } else if (activeSession?.sessionId == session.sessionId) {
                        val next = nextSession
                        if (next != null) {
                            val oldFormat = session.pcmFormat
                            val newFormat = next.pcmFormat

                            activeSession = next
                            val newSessionId = next.sessionId
                            nextSession = null
                            currentSong = next.song
                            nextSong = null

                            if (newFormat != null && oldFormat != newFormat) {
                                next.configure(newFormat)
                            }
                            val framesAtTransition = output.totalFramesWritten()
                            next.setStartFrameOffset(framesAtTransition)

                            updateAudioStateForSong(currentSong!!)
                            this@AudioEngine.positionMs = 0L
                            _playbackStateFlow.update { it.copy(currentSong = currentSong, sessionId = newSessionId) }
                            _onCompletion.emit(Unit)
                        } else {
                            activeSession = null
                            userIntentPlaying.set(false)
                            _playbackStateFlow.update { it.copy(isPlaying = false) }
                            _onCompletion.emit(Unit)
                        }
                    }
                }
            } else {
                delay(2)
            }
        }
    }

    private inner class PlaybackSession(
        val sessionId: Long,
        val song: Song,
        private val initialStartPositionMs: Long,
        val preferredAudioTrackIndex: Int? = null
    ) : DecoderSink, DecoderControl {

        val ringBuffer = FloatRingBuffer(RING_BUFFER_SAMPLES)
        private val pendingSeekMs = AtomicLong(NO_SEEK_PENDING)
        val pendingSilenceSamples = AtomicInteger(0)
        @Volatile private var started = true
        private var seekListener: (() -> Unit)? = null
        var decoderCompleted = false
            private set

        @Volatile
        var pcmFormat: PcmAudioFormat? = null
            private set
        private var basePositionMs: Long = initialStartPositionMs
        private var startFrameOffset = output.playbackPositionFrames()
        private var lastOutputRate = 0
        private var lastOutputBitDepth = 0
        private var lastOutputChannels = 0
        @Volatile
        private var hasOutputStarted = false
        val dspLock = ReentrantReadWriteLock()

        @Volatile
        var dspPipeline = AudioDspPipeline.create(44_100, 44_100, 2, output.outputBitDepth(), this@AudioEngine.dspConfig, song)

        @Volatile private var fadeInFramesRemaining = 0
        @Volatile private var totalFadeInFrames = 0

        init {
            triggerFadeIn(30, song.sampleRateHz.takeIf { it > 0 } ?: 48000)
        }

        fun triggerFadeIn(durationMs: Int = 30, sampleRate: Int = 48000) {
            val rate = sampleRate.coerceAtLeast(8000)
            val total = (durationMs * rate) / 1000
            totalFadeInFrames = total
            fadeInFramesRemaining = total
        }

        fun applyFadeIn(data: FloatArray, sampleCount: Int, channels: Int) {
            val rem = fadeInFramesRemaining
            if (rem <= 0 || channels <= 0) return
            val total = totalFadeInFrames.coerceAtLeast(1)
            val framesInBatch = sampleCount / channels
            var currentRem = rem
            for (f in 0 until framesInBatch) {
                if (currentRem > 0) {
                    val gain = 1f - (currentRem.toFloat() / total)
                    val offset = f * channels
                    for (c in 0 until channels) {
                        data[offset + c] *= gain
                    }
                    currentRem--
                }
            }
            fadeInFramesRemaining = currentRem
        }

        fun adjustBasePositionForDrift(driftMs: Long) {
            val format = pcmFormat ?: return
            val channels = format.channels.coerceAtLeast(1)
            val sampleRate = format.sampleRate.coerceAtLeast(8000)
            
            if (driftMs > 0) {
                // Audio is behind video. Skip audio samples.
                val framesToSkip = ((driftMs * sampleRate) / 1000L).toInt()
                val samplesToSkip = framesToSkip * channels
                ringBuffer.skip(samplesToSkip)
                basePositionMs += driftMs
            } else if (driftMs < 0) {
                // Audio is ahead of video. Insert silence.
                val framesToAdd = ((-driftMs * sampleRate) / 1000L).toInt()
                val samplesToAdd = framesToAdd * channels
                pendingSilenceSamples.addAndGet(samplesToAdd)
                basePositionMs += driftMs
            }
        }

        fun setStartFrameOffset(offset: Long) {
            startFrameOffset = offset
        }

        fun requestSeek(positionMs: Long) {
            pendingSeekMs.set(positionMs)
            this@AudioEngine.positionMs = positionMs
            if (song.id.startsWith("video_route:")) {
                _videoRouteStateFlow.update { it.copy(positionMs = positionMs, firstFrameRendered = false) }
            }
            ringBuffer.clear() // Unblock decoder if it's waiting on a full buffer
            seekListener?.invoke()
        }

        override fun setSeekListener(listener: () -> Unit) {
            seekListener = listener
        }

        suspend fun run() {
            val decoder = decoderFactory.create(song)
            var decodeStartMs = initialStartPositionMs
            var retryCount = 0

            try {
                while (isActive()) {
                    decoderCompleted = false
                    val result = decoder.decode(
                        request = PlaybackRequest(
                            song = song,
                            startPositionMs = decodeStartMs,
                            preferredAudioTrackIndex = preferredAudioTrackIndex
                        ),
                        sink = this,
                        control = this
                    )

                    when (result) {
                        DecodeResult.Ended -> {
                            decoderCompleted = true
                            return
                        }

                        is DecodeResult.Seek -> {
                            notifySeek(result.positionMs)
                            decodeStartMs = result.positionMs
                            retryCount = 0 // Reset retry on seek
                        }

                        is DecodeResult.Failed -> {
                            if (song.source == SongSource.GDRIVE && retryCount < 2) {
                                retryCount++
                                Log.w("AudioEngine", "Retry $retryCount for Drive song ${song.title} after failure: ${result.reason}")
                                delay(500)
                                continue
                            }

                            if (song.source == SongSource.TELEGRAM && retryCount < 2) {
                                retryCount++
                                Log.w("AudioEngine", "Telegram decode failed, waiting for TDLib ready and retrying... (Attempt $retryCount)")
                                if (tdLibManager.awaitTdlibReady(20000)) {
                                    continue
                                }
                            }

                            // "Playback stopped" means the decode loop exited because control.isActive()
                            // went false (the session was intentionally torn down by a track change)
                            // — this is NOT a genuine decoder fault, so don't penalize
                            // this song's future decoder choice for it.
                            val isIntentionalStop = result.reason == "Playback stopped"

                            if (decoder is MediaCodecAudioDecoder && !isIntentionalStop) {
                                decoderFactory.notifyDecoderFailure(song.id)
                            }

                            logWarn("Decoder failed permanently: ${result.reason ?: "unknown"}")
                            ringBuffer.close()
                            controlMutex.withLock {
                                if (videoSession?.sessionId == sessionId) {
                                    videoSession = null
                                    if (activeSession?.sessionId == sessionId) {
                                        activeSession = null
                                    }
                                    _videoRouteStateFlow.update { it.copy(phase = VideoRoutePhase.ERROR, isPlaying = false, error = result.reason ?: "Decoder failed") }
                                } else if (activeSession?.sessionId == sessionId) {
                                    activeSession = null
                                    _playbackStateFlow.update { it.copy(isPlaying = false) }
                                } else if (nextSession?.sessionId == sessionId) {
                                    nextSession = null
                                    nextSong = null
                                }
                            }
                            return
                        }
                    }
                }
            } finally {
                // Always clear isSeeking unconditionally — if this session is ending,
                // any pending seek is dead regardless of whether it's still activeSession
                isSeeking.set(false)
            }
        }

        fun stop() {
            started = false
            dspLock.writeLock().withLock {
                dspPipeline.release()
            }
            ringBuffer.close()
        }

        fun currentRenderedPositionMs(): Long {
            val sampleRate = output.outputSampleRate().takeIf { it > 0 } ?: pcmFormat?.sampleRate ?: 44_100
            val hwPosition = output.playbackPositionFrames()
            val framesSinceStart = (hwPosition - startFrameOffset).coerceAtLeast(0L)
            
            // Safeguard: if elapsed frames represent more than 24 hours of playback, 
            // it's likely a hardware sync error (hardware counter was cumulative since boot 
            // but we anchored to 0). In this case, we cap it to the song duration or 
            // return basePositionMs to avoid "6234:03" glitch.
            if (framesSinceStart > 86400L * sampleRate) {
                return basePositionMs
            }
            
            val position = basePositionMs + framesToMs(framesSinceStart, sampleRate)
            // Never report a position past the end of the track.
            val duration = song.durationMs
            return if (duration > 0L) position.coerceAtMost(duration) else position
        }

        suspend fun requestOutputRestart() {
            val format = pcmFormat ?: return

            // Capture current position synchronously before launching reconfiguration.
            // Resume from exactly where we left off.
            basePositionMs = currentRenderedPositionMs() + OUTPUT_RECONFIG_SKIP_MS
            startFrameOffset = output.playbackPositionFrames()

            try {
                requestSeek(basePositionMs)
                configure(format)
            } catch (e: Exception) {
                Log.e(TAG, "Output restart failed", e)
            }
        }

        override suspend fun configure(format: PcmAudioFormat) {
            val formatChanged = pcmFormat != format
            pcmFormat = format

            if (activeSession?.sessionId == this.sessionId) {
                // 5.1/7.1 Surround Simulator: 6 / 8 while it upmixes this stream, else the source's own count.
                var outChannels = surroundTargetChannels(dspConfig, format)
                var targetRate = resolveTargetSampleRate(format.sampleRate, dspConfig)
                if (outChannels > format.channels && output.maxOutputChannels() < outChannels) {
                    // The device is not really multichannel, so the platform mixer folds the stream down. Direct
                    // hi-res rates (up to 192 kHz) make no sense on that path and may be refused for 6/8 channels.
                    // Resampler "Auto" keeps 96 kHz (and anything below) untouched; other modes keep the 48 kHz cap.
                    val cap = if (dspConfig.resamplerMode == ResamplerMode.AUTO) SURROUND_MIXER_RATE_CAP_AUTO else SURROUND_MIXER_RATE_CAP
                    targetRate = minOf(targetRate, cap)
                }
                val targetBitDepth = if (dspConfig.outputMode == OutputMode.HI_RES) 32 else dspConfig.sampleFormat.bitDepth

                val outputConfigChanged = targetRate != lastOutputRate || targetBitDepth != lastOutputBitDepth ||
                        outChannels != lastOutputChannels
                val hardwareConfigChanged = targetRate != output.outputSampleRate() || targetBitDepth != output.outputBitDepth() ||
                        (output.outputChannelCount() > 0 && outChannels != output.outputChannelCount())

                if (hasOutputStarted && (formatChanged || outputConfigChanged)) {
                    ringBuffer.clear()
                }
                
                if ((hasOutputStarted && outputConfigChanged) || (!hasOutputStarted && hardwareConfigChanged)) {
                    output.flush()
                }

                lastOutputRate = targetRate
                lastOutputBitDepth = targetBitDepth

                output.setTargetSampleRate(targetRate)

                if (dspConfig.outputMode == OutputMode.HI_RES) {
                    output.setSampleFormat(SampleFormat.FLOAT_32BIT)
                } else {
                    output.setSampleFormat(dspConfig.sampleFormat)
                }

                // RECOVERY: Capture the current cumulative position before re-initializing
                // the output, so we can resume smoothly without a jump.
                basePositionMs = currentRenderedPositionMs()

                var outputReady = output.init(format.sampleRate, outChannels, format.bitDepth, format.isDoP, resetOffsets = false)
                if (!outputReady && outChannels != format.channels) {
                    // The device refused the 5.1/7.1 stream: stay on the source layout (plain stereo) instead of
                    // going silent. The verdict is remembered until the user changes the surround settings.
                    logWarn("Output refused a $outChannels-channel stream, falling back to ${format.channels} channels")
                    surroundRejectedChannels = outChannels
                    outChannels = format.channels
                    lastOutputRate = resolveTargetSampleRate(format.sampleRate, dspConfig)
                    output.setTargetSampleRate(lastOutputRate)
                    outputReady = output.init(format.sampleRate, outChannels, format.bitDepth, format.isDoP, resetOffsets = false)
                }
                if (!outputReady) {
                    logWarn("Audio output initialization failed")
                }
                lastOutputChannels = outChannels

                // Hardware position was just updated/continued by init(resetOffsets=false),
                // so we reset our base frame offset to the "new" start position.
                startFrameOffset = output.playbackPositionFrames()

                // Move pipeline refresh BEFORE starting output to avoid race condition where
                // audio at the new rate is processed by an old-rate pipeline.
                refreshDspPipeline(format)

                // FIX: Only start output if we are actually in playing state
                if (isSessionPlaying(this)) {
                    output.start()
                }

                hasOutputStarted = true
                publishDspState(format)
            } else if (nextSession?.sessionId == this.sessionId) {
                pcmFormat = format
                // For preloaded session, we just prepare the DSP
                refreshDspPipeline(format)
            }
        }

        override suspend fun write(data: FloatArray, sampleCount: Int) {
            ringBuffer.write(data, sampleCount)
        }

        override fun isActive(): Boolean {
            return started && (activeSession?.sessionId == sessionId || nextSession?.sessionId == sessionId)
        }

        override fun isSeekPending(): Boolean {
            return pendingSeekMs.get() != NO_SEEK_PENDING
        }

        override fun consumePendingSeekMs(): Long? {
            val requested = pendingSeekMs.getAndSet(NO_SEEK_PENDING)
            return if (requested == NO_SEEK_PENDING) null else requested
        }

        override fun notifySeek(positionMs: Long) {
            Log.d("AudioEngine", "notifySeek: $positionMs ms")
            performSeek(positionMs)
            isSeeking.set(false)
        }

        override fun logDebug(message: String) {
            Log.d(TAG, "[session=$sessionId] $message")
        }

        override fun logWarn(message: String) {
            Log.w(TAG, "[session=$sessionId] $message")
        }

        fun performSeek(positionMs: Long) {
            Log.d("AudioEngine", "performSeek: $positionMs ms")
            triggerFadeIn(30, pcmFormat?.sampleRate ?: 48000)
            basePositionMs = positionMs
            decoderCompleted = false
            ringBuffer.clear()
            output.flush()
            dspPipeline.flush()
            // BUGFIX: this used to be hardcoded to 0L, which made currentRenderedPositionMs()
            // compute elapsed time from the RAW absolute hardware frame counter (running since
            // the audio output was first created) instead of relative to this seeker point. That
            // produced huge bogus displayed positions/durations (e.g. "1634:43") after seeking.
            // Resetting it to the current hardware frame position — exactly like configure() and
            // requestOutputRestart() already do — keeps position math relative to "now".
            startFrameOffset = output.playbackPositionFrames()
            this@AudioEngine.positionMs = positionMs
        }

        suspend fun refreshDspPipeline(format: PcmAudioFormat) {
            val actualOutputRate = output.outputSampleRate().takeIf { it > 0 } ?: resolveTargetSampleRate(format.sampleRate, dspConfig)
            val currentBitDepth = output.outputBitDepth()

            // 1. Swap/Update pipeline synchronously so the next render loop uses correct parameters
            val oldPipelineToRelease = dspLock.writeLock().withLock {
                val current = dspPipeline

                if (current.inputSampleRate == format.sampleRate &&
                    current.outputSampleRate == actualOutputRate &&
                    current.channels == format.channels) {

                    current.updateOutputBitDepth(currentBitDepth)
                    current.updateConfig(dspConfig)
                    current.updateAiEq(aiEqStore.get(song.id))
                    appliedDspRevision = dspRevision.get()
                    null
                } else {
                    val newPipeline = AudioDspPipeline.create(
                        inputSampleRate = format.sampleRate,
                        outputSampleRate = actualOutputRate,
                        channels = format.channels,
                        outputBitDepth = currentBitDepth,
                        config = dspConfig,
                        song = song,
                        aiEq = aiEqStore.get(song.id)
                    )
                    dspPipeline = newPipeline
                    appliedDspRevision = dspRevision.get()
                    current
                }
            }

            // Release old pipeline outside of lock
            oldPipelineToRelease?.release()

        }
    }

    private var appliedDspRevision = -1L

    companion object {
        private const val TAG = "AudioEngine"
        private const val RING_BUFFER_SAMPLES = 262_144 // Increased for better pre-fetch
        private const val RENDER_BATCH_SAMPLES = 1_024
        private const val NO_SEEK_PENDING = -1L
        private const val OUTPUT_RECONFIG_SKIP_MS = 0L
        /** Highest output rate used when 5.1/7.1 is rendered through the platform mixer (device not truly multichannel). */
        private const val SURROUND_MIXER_RATE_CAP = 48_000
        /** Same cap when the resampler is on Auto: 96 kHz files are never downgraded. */
        private const val SURROUND_MIXER_RATE_CAP_AUTO = 96_000
        /** Resampler Auto keeps sources in this range (88.2 .. 96 kHz) at their own rate. */
        private const val HIRES_PASSTHROUGH_MIN = 88_200
        private const val HIRES_PASSTHROUGH_MAX = 96_000
        /** Resampler Auto: sources up to this rate are converted to it (44.1 -> 48); Bluetooth fallback when no max is known. */
        private const val AUTO_BASE_RATE = 48_000
        private const val LIVE_STATS_INTERVAL_MS = 500L

        private fun framesToMs(frames: Long, sampleRate: Int): Long {
            if (sampleRate <= 0) return 0L
            return (frames * 1000L) / sampleRate
        }

        private fun normalizeCodec(rawFormat: String): String {
            val format = rawFormat.trim().lowercase()
            return when {
                format.contains("flac") -> "FLAC"
                format.contains("alac") -> "ALAC"
                format.contains("dsd") || format.contains("dsf") || format.contains("dff") -> "DSD"
                format.contains("aiff") || format.contains("aif") -> "AIFF"
                format.contains("opus") -> "OPUS"
                format.contains("ogg") || format.contains("vorbis") -> "OGG"
                format == "m4a" || format == "mp4" || format.contains("aac") -> "AAC"
                format.contains("mpeg") || format.contains("mp3") -> "MP3"
                format.contains("wav") -> "WAV"
                format.contains("raw") || format.contains("pcm") -> ""
                format.isBlank() -> ""
                else -> format.uppercase()
            }
        }

        private fun buildPipelineSummary(
            codec: String,
            inputRate: Int,
            outputRate: Int,
            outputBitDepth: Int,
            outputPath: String,
            outputDevice: String
        ): String {
            val resamplerLabel = if (inputRate != outputRate) "${outputRate / 1000}kHz" else "Native"
            return "${codec.uppercase()} -> Float PCM -> DSP -> $resamplerLabel -> ${outputBitDepth}-bit -> $outputPath -> $outputDevice"
        }

    }

    private fun publishDspState(format: PcmAudioFormat? = activeSession?.pcmFormat) {
        val sourceSampleRate = format?.sampleRate ?: _audioStateFlow.value.sampleRate
        val outputSampleRate = output.outputSampleRate().takeIf { it > 0 }
            ?: resolveTargetSampleRate(sourceSampleRate, dspConfig)
        val currentConfig = dspConfig
        val surroundStage = if (format != null) {
            resolveSurroundStage(currentConfig, format.channels, format.isDoP, output.outputChannelCount())
        } else {
            SurroundStage.OFF
        }
        _audioStateFlow.update { state ->
            val codec = if (!format?.codec.isNullOrBlank()) normalizeCodec(format!!.codec!!) else state.codec
            state.copy(
                songId = currentSong?.id,
                codec = codec,
                sampleRate = sourceSampleRate,
                outputSampleRate = outputSampleRate,
                bitDepth = format?.bitDepth ?: state.bitDepth,
                outputBitDepth = output.outputBitDepth(),
                outputPath = output.outputPathLabel(),
                outputDevice = output.outputDeviceLabel(),
                dynamicVolumeControlActive = currentConfig.dvcEnabled,
                resamplerActive = sourceSampleRate != outputSampleRate,
                resamplerType = if (currentConfig.highQualityResampler) "SOXR" else "Cubic",
                activeEffects = currentConfig.activeEffects(),
                autoEqProfileName = currentConfig.autoEqProfile?.name,
                headroomDb = activeSession?.dspPipeline?.getHeadroomDb()?.takeIf { it != 0f } ?: state.headroomDb,
                latencyFrames = (output.queuedLatencyFrames() + (activeSession?.dspPipeline?.getLatencyFrames() ?: 0)),
                underrunCount = output.underrunCount(),
                outputChannels = output.outputChannelCount().takeIf { it > 0 } ?: (format?.channels ?: state.outputChannels),
                surroundFallback = currentConfig.surroundEnabled && surroundRejectedChannels != 0,
                sourceChannels = format?.channels ?: state.sourceChannels,
                surroundActive = surroundStage != SurroundStage.OFF,
                surroundDiscrete = surroundStage == SurroundStage.DISCRETE,
                ditherType = currentConfig.ditherType.displayName,
                eqMode = if (currentConfig.eqPhaseMode == com.beatraxus.app.model.EqPhaseMode.LINEAR_PHASE) "Linear Phase" else "IIR",
                pipelineSummary = buildPipelineSummary(
                    codec = codec.ifBlank { currentSong?.format ?: "Unknown" },
                    inputRate = sourceSampleRate,
                    outputRate = outputSampleRate,
                    outputBitDepth = output.outputBitDepth(),
                    outputPath = output.outputPathLabel(),
                    outputDevice = output.outputDeviceLabel()
                )
            )
        }
    }

    fun getLevels(): FloatArray = activeSession?.dspPipeline?.getLevels() ?: floatArrayOf(0f, 0f)

    private fun directPlaybackSupported(rate: Int): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        return runCatching {
            AudioTrack.isDirectPlaybackSupported(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }.getOrDefault(false)
    }

    /**
     * Resampler "Auto": the target rate follows the output device that is connected right now.
     *
     *  - Bluetooth headset: every source is converted to the highest rate the headset accepts (a 48 kHz / 16-bit
     *    A2DP headset gets 48 kHz for 44.1, 48 and 96 kHz files alike), so the link never has to resample again.
     *  - USB DAC / wired IEM / anything else: nothing is limited. Sources up to 48 kHz are converted to 48 kHz
     *    (44.1 -> 48); higher rates (88.2 / 96 kHz and up) stay untouched (96 -> 96).
     */
    private fun resolveAutoTargetRate(inputRate: Int, config: DspConfig): Int {
        val route = output.routeRateInfo()
        if (route.bluetooth) {
            return route.maxRateHz.takeIf { it > 0 } ?: AUTO_BASE_RATE
        }

        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        if (config.outputMode != OutputMode.HI_RES) {
            // AAudio path: use hardware native rate to avoid Android SRC.
            // For hi-res sources, the DSP resampler handles conversion to nativeRate.
            return nativeRate
        }

        // MTK HiFi direct path: honour the source sample rate exactly up to 192kHz.
        // Do NOT downsample — the hardware driver handles any DAC-level conversion.
        // This is the same policy Poweramp uses: pass native source rate to the DAC.
        val sourceRate = when {
            inputRate <= 0 -> return nativeRate
            inputRate <= 384000 -> inputRate   // preserve exact source rate up to 384kHz
            else -> 384000                     // hard cap at 384kHz
        }
        // USB Exclusive talks to the DAC directly, so the source rate is always kept.
        if (config.usbExclusiveEnabled) return sourceRate
        // 44.1 kHz (and anything lower) goes to 48 kHz.
        if (sourceRate <= AUTO_BASE_RATE) return AUTO_BASE_RATE

        // Above 48 kHz: keep the source rate when the device can really play it (it reports it, the mixer already
        // runs at it, or the direct-playback probe says so). Otherwise Android's built-in mixer resampler would
        // convert it, which is noticeably worse than our own SoX resampler, so convert to the hardware rate here.
        // 88.2 / 96 kHz sources are always passed through untouched in Auto: the direct-playback probe
        // is unreliable for USB DACs behind the mixer and used to push them down to the 48 kHz native rate.
        return if (sourceRate == nativeRate || sourceRate in HIRES_PASSTHROUGH_MIN..HIRES_PASSTHROUGH_MAX ||
            (route.maxRateHz > 0 && sourceRate <= route.maxRateHz) || directPlaybackSupported(sourceRate)) sourceRate
        else nativeRate
    }

    private fun resolveTargetSampleRate(inputRate: Int, config: DspConfig): Int {
        // Bit-Perfect: always pass source rate unchanged, unless resampler is unbypassed
        if (config.bitPerfectEnabled && !config.bitPerfectUnbypassResample) return inputRate

        return when (config.resamplerMode) {
            ResamplerMode.AUTO -> resolveAutoTargetRate(inputRate, config)
            ResamplerMode.SR_44100  -> 44100
            ResamplerMode.SR_48000  -> 48000
            ResamplerMode.SR_88200  -> 88200
            ResamplerMode.SR_96000  -> 96000
            ResamplerMode.SR_176400 -> 176400
            ResamplerMode.SR_192000 -> 192000
        }
    }
}