package com.beatflowy.app.engine

import android.content.Context
import android.os.Process
import android.util.Log
import android.media.AudioManager
import android.media.AudioTrack
import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.ResamplerMode
import com.beatflowy.app.model.SampleFormat
import com.beatflowy.app.model.Song
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
import com.beatflowy.app.repository.DriveAccountRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioEngine(
    context: Context,
    private val output: AudioOutput,
    private val cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager
) {
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val controlMutex = Mutex()
    private val driveAccountRepository = DriveAccountRepository(context)

    private val decoderFactory = DecoderFactory(
        context = context,
        driveAccountRepository = driveAccountRepository,
        cloudCacheManager = cloudCacheManager,
        ffmpegAlacDecoder = FfmpegAlacDecoder(context, driveAccountRepository, cloudCacheManager),
        mediaCodecDecoder = MediaCodecAudioDecoder(context, driveAccountRepository, cloudCacheManager)
    )

    private val _audioStateFlow = MutableStateFlow(AudioState())
    val audioStateFlow = _audioStateFlow.asStateFlow()

    private val _playbackStateFlow = MutableStateFlow(PlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private val _onCompletion = MutableSharedFlow<Unit>()
    val onCompletion = _onCompletion.asSharedFlow()

    private var currentSong: Song? = null
    private var nextSong: Song? = null

    private var currentSessionId = AtomicLong(0)
    private var activeSession: PlaybackSession? = null
    private var nextSession: PlaybackSession? = null

    private var rendererJob: Job? = null

    private var positionMs: Long = 0L
    private var underrunCount = 0
    @Volatile private var dspConfig: DspConfig = DspConfig()
    private val dspRevision = AtomicLong(0L)
    private val isSeeking = AtomicBoolean(false)

    init {
        startRenderer()
    }

    private fun startRenderer() {
        rendererJob?.cancel()
        rendererJob = engineScope.launch(Dispatchers.IO) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            renderLoop()
        }
    }

    fun currentPositionMs(): Long {
        if (isSeeking.get()) return positionMs
        return activeSession?.currentRenderedPositionMs() ?: positionMs
    }

    fun play(song: Song) {
        engineScope.launch {
            controlMutex.withLock {
                // Promotion logic: if we have this song preloaded as nextSession, promote it!
                if (nextSession != null && nextSong?.id == song.id) {
                    activeSession?.stop()
                    activeSession = nextSession
                    nextSession = null
                    currentSong = song
                    nextSong = null
                    positionMs = 0L
                    updateAudioStateForSong(song)
                    _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }
                    
                    // Trigger output reconfiguration for the promoted session
                    val fmt = activeSession?.pcmFormat
                    if (fmt != null) {
                        activeSession?.configure(fmt)
                    } else {
                        output.start()
                    }
                    return@withLock
                }

                // If we are already playing this song, just ensure it's playing
                if (currentSong?.id == song.id && activeSession != null) {
                    if (!_playbackStateFlow.value.isPlaying) {
                        resume()
                    }
                    return@withLock
                }

                currentSong = song
                nextSong = null
                positionMs = 0L
                updateAudioStateForSong(song)

                _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }

                stopSessionsInternal()
                startSessionInternal(song, startPositionMs = 0L)
            }
        }
    }

    fun preloadNext(song: Song) {
        engineScope.launch {
            controlMutex.withLock {
                if (nextSong?.id == song.id) return@withLock
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
    }

    fun prepare(song: Song) {
        engineScope.launch {
            controlMutex.withLock {
                if (currentSong?.id == song.id && (activeSession != null || nextSession != null)) return@withLock
                
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
                }

                currentSong = song
                positionMs = 0L
                updateAudioStateForSong(song)
                _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = false) }
                
                if (activeSession == null && nextSession == null) {
                    preloadNext(song)
                }
            }
        }
    }

    private fun updateAudioStateForSong(song: Song) {
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

                // Set isPlaying = true EAGERLY to ensure UI responsiveness
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
            controlMutex.withLock {
                stopSessionsInternal()
                output.stop()
                _playbackStateFlow.update { it.copy(isPlaying = false) }
            }
        }
    }

    fun pause() {
        engineScope.launch {
            controlMutex.withLock {
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
    }

    fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        isSeeking.set(true)
        activeSession?.requestSeek(positionMs)
    }

    fun setShuffleMode(enabled: Boolean) {
        _playbackStateFlow.update { it.copy(shuffleMode = enabled) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _playbackStateFlow.update { it.copy(repeatMode = mode) }
    }

    fun release() {
        stop()
        rendererJob?.cancel()
        output.release()
    }

    fun reconfigureOutput() {
        engineScope.launch {
            controlMutex.withLock {
                val song = currentSong ?: return@withLock
                val session = activeSession
                if (session != null && session.isActive() && session.pcmFormat != null) {
                    session.requestOutputRestart()
                } else if (_playbackStateFlow.value.isPlaying) {
                    val resumePositionMs = currentPositionMs()
                    startSessionInternal(song, resumePositionMs)
                } else {
                    publishDspState(activeSession?.pcmFormat)
                }
            }
        }
    }

    fun updateDspConfig(config: DspConfig) {
        val oldConfig = dspConfig
        val sourceSampleRate = currentSong?.sampleRateHz ?: 44100
        val oldTargetRate = resolveTargetSampleRate(sourceSampleRate, oldConfig)
        dspConfig = config
        val newTargetRate = resolveTargetSampleRate(sourceSampleRate, config)

        output.setDvcState(
            enabled = config.dvcEnabled,
            mode = config.dvcMode.name,
            level = config.dvcLevel
        )

        // When output mode or target rate changes, push the new target rate to the output
        // object BEFORE calling reconfigureOutput(). This ensures that init() inside
        // reconfigureOutput sees the correct targetSampleRate for the new mode, so the
        // DSP resampler is rebuilt with the right in→out ratio in real time.
        val outputModeChanged = oldConfig.outputMode != config.outputMode
        if (outputModeChanged || oldTargetRate != newTargetRate) {
            output.setTargetSampleRate(newTargetRate)
            if (outputModeChanged && output is AudioTrackOutput) {
                output.setOutputMode(config.outputMode)
            }
        }

        publishDspState()

        val structuralChange = oldTargetRate != newTargetRate ||
                oldConfig.sampleFormat != config.sampleFormat ||
                outputModeChanged ||
                oldConfig.dvcEnabled != config.dvcEnabled

        // Always increment revision for ANY config change — this triggers
        // updateConfig() in renderLoop for parameters like reverb, EQ, tone.
        dspRevision.incrementAndGet()

        if (structuralChange) {
            reconfigureOutput()  // Handles refreshDspPipeline for structural changes
        }
    }

    private fun startSessionInternal(song: Song, startPositionMs: Long) {
        val sessionId = currentSessionId.incrementAndGet()
        val session = PlaybackSession(sessionId, song, startPositionMs)
        activeSession = session

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

    private suspend fun renderLoop() {
        val localBuffer = FloatArray(RENDER_BATCH_SAMPLES)

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

            if (!_playbackStateFlow.value.isPlaying) {
                delay(10)
                continue
            }

            val sampleCount = session.ringBuffer.read(localBuffer, localBuffer.size)
            if (sampleCount > 0) {
                val currentRevision = dspRevision.get()
                if (appliedDspRevision != currentRevision) {
                    // Light update: push new parameter values to existing native DSP
                    session.dspPipeline.updateConfig(dspConfig)
                    appliedDspRevision = currentRevision
                }

                val processed = session.dspPipeline.process(localBuffer, sampleCount, format.channels, format.sampleRate)
                val frames = processed.sampleCount / format.channels
                var writtenFramesTotal = 0

                while (writtenFramesTotal < frames && engineScope.isActive && activeSession?.sessionId == session.sessionId && !isSeeking.get() && _playbackStateFlow.value.isPlaying) {
                    val written = output.write(
                        data = processed.data,
                        offsetInSamples = writtenFramesTotal * format.channels,
                        frameCount = frames - writtenFramesTotal
                    )
                    if (written <= 0) {
                        underrunCount++
                        delay(2)
                        break
                    }
                    writtenFramesTotal += written
                }

                val newPos = session.currentRenderedPositionMs()
                if (engineScope.isActive && activeSession?.sessionId == session.sessionId && !isSeeking.get()) {
                    this@AudioEngine.positionMs = newPos
                }
                continue
            }

            if (session.decoderCompleted && session.ringBuffer.isEmpty() && _playbackStateFlow.value.isPlaying) {
                // TRACK COMPLETED - Transition to next if available
                controlMutex.withLock {
                    if (activeSession?.sessionId == session.sessionId) {
                        val next = nextSession
                        if (next != null && next.pcmFormat != null) {
                            // Gapless transition
                            val oldFormat = session.pcmFormat
                            val newFormat = next.pcmFormat

                            activeSession = next
                            nextSession = null
                            currentSong = next.song
                            nextSong = null

                            val framesAtTransition = output.totalFramesWritten()
                            next.setStartFrameOffset(framesAtTransition)

                            if (oldFormat != newFormat) {
                                // If formats differ, we MUST re-init output
                                // This might cause a tiny gap but is necessary
                                next.configure(newFormat!!)
                            }

                            updateAudioStateForSong(currentSong!!)
                            _playbackStateFlow.update { it.copy(currentSong = currentSong) }
                            _onCompletion.emit(Unit)
                        } else {
                            // No next track ready
                            activeSession = null
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
        private val initialStartPositionMs: Long
    ) : DecoderSink, DecoderControl {
        val ringBuffer = FloatRingBuffer(RING_BUFFER_SAMPLES)
        private val pendingSeekMs = AtomicLong(NO_SEEK_PENDING)
        @Volatile private var started = true
        var decoderCompleted = false
            private set

        var pcmFormat: PcmAudioFormat? = null
            private set
        private var basePositionMs: Long = initialStartPositionMs
        private var startFrameOffset = 0L
        var dspPipeline = AudioDspPipeline.create(44_100, 44_100, 2, output.outputBitDepth(), this@AudioEngine.dspConfig, song)

        fun setStartFrameOffset(offset: Long) {
            startFrameOffset = offset
        }

        suspend fun run() {
            val decoder = decoderFactory.create(song)
            var decodeStartMs = initialStartPositionMs

            while (isActive()) {
                decoderCompleted = false
                val result = decoder.decode(
                    request = PlaybackRequest(song = song, startPositionMs = decodeStartMs),
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
                    }
                    is DecodeResult.Failed -> {
                        logWarn("Decoder failed: ${result.reason ?: "unknown"}")
                        ringBuffer.close()
                        controlMutex.withLock {
                            if (activeSession?.sessionId == sessionId) {
                                activeSession = null
                                _playbackStateFlow.update { it.copy(isPlaying = false) }
                            }
                        }
                        return
                    }
                }
            }
        }

        fun stop() {
            started = false
            ringBuffer.close()
        }

        fun requestSeek(positionMs: Long) {
            pendingSeekMs.set(positionMs)
            this@AudioEngine.positionMs = positionMs
        }

        fun currentRenderedPositionMs(): Long {
            val sampleRate = output.outputSampleRate().takeIf { it > 0 } ?: pcmFormat?.sampleRate ?: 44_100
            val framesSinceStart = (output.playbackPositionFrames() - startFrameOffset).coerceAtLeast(0L)
            return basePositionMs + framesToMs(framesSinceStart, sampleRate)
        }

        fun requestOutputRestart() {
            val format = pcmFormat ?: return
            engineScope.launch {
                try {
                    configure(format)
                } catch (e: Exception) {
                    Log.e(TAG, "Output restart failed", e)
                }
            }
        }

        override suspend fun configure(format: PcmAudioFormat) {
            val formatChanged = pcmFormat != format
            pcmFormat = format

            if (activeSession?.sessionId == this.sessionId) {
                if (formatChanged) {
                    ringBuffer.clear()
                    output.flush()
                    startFrameOffset = 0L
                }

                val targetRate = resolveTargetSampleRate(format.sampleRate, dspConfig)
                output.setTargetSampleRate(targetRate)

                if (dspConfig.outputMode == OutputMode.HI_RES) {
                    output.setSampleFormat(SampleFormat.FLOAT_32BIT)
                } else {
                    output.setSampleFormat(dspConfig.sampleFormat)
                }

                if (!output.init(format.sampleRate, format.channels, format.bitDepth)) {
                    logWarn("Audio output initialization failed")
                }

                // FIX: Only start output if we are actually in playing state
                if (_playbackStateFlow.value.isPlaying) {
                    output.start()
                }

                refreshDspPipeline(format)
                publishDspState(format)
            } else if (nextSession?.sessionId == this.sessionId) {
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

        override fun consumePendingSeekMs(): Long? {
            val requested = pendingSeekMs.getAndSet(NO_SEEK_PENDING)
            return if (requested == NO_SEEK_PENDING) null else requested
        }

        override fun notifySeek(positionMs: Long) {
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
            basePositionMs = positionMs
            decoderCompleted = false
            ringBuffer.clear()
            output.flush()
            this@AudioEngine.positionMs = positionMs
        }

        fun refreshDspPipeline(format: PcmAudioFormat) {
            val actualOutputRate = output.outputSampleRate().takeIf { it > 0 } ?: resolveTargetSampleRate(format.sampleRate, dspConfig)
            dspPipeline = AudioDspPipeline.create(
                inputSampleRate = format.sampleRate,
                outputSampleRate = actualOutputRate,
                channels = format.channels,
                outputBitDepth = output.outputBitDepth(),
                config = dspConfig,
                song = song
            )
            appliedDspRevision = dspRevision.get()
        }
    }

    private var appliedDspRevision = -1L

    companion object {
        private const val TAG = "AudioEngine"
        private const val RING_BUFFER_SAMPLES = 262_144 // Increased for better pre-fetch
        // Change 1: RENDER_BATCH_SAMPLES changed from 1_024 to 4_096
        private const val RENDER_BATCH_SAMPLES = 4_096
        private const val NO_SEEK_PENDING = -1L

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

    private fun resolveTargetSampleRate(inputRate: Int, config: DspConfig): Int {
        return when (config.resamplerMode) {
            ResamplerMode.AUTO -> {
                val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                if (config.outputMode == OutputMode.HI_RES) {
                    // MTK HiFi direct path: honour the source sample rate exactly up to 192kHz.
                    // Do NOT downsample — the hardware driver handles any DAC-level conversion.
                    // This is the same policy Poweramp uses: pass native source rate to the DAC.
                    when {
                        inputRate <= 0 -> nativeRate
                        inputRate <= 384000 -> inputRate   // preserve exact source rate up to 384kHz
                        else -> 384000                     // hard cap at 384kHz
                    }
                } else {
                    // AAudio path: use hardware native rate to avoid Android SRC.
                    // For hi-res sources, the DSP resampler handles conversion to nativeRate.
                    nativeRate
                }
            }
            ResamplerMode.SR_44100  -> 44100
            ResamplerMode.SR_48000  -> 48000
            ResamplerMode.SR_88200  -> 88200
            ResamplerMode.SR_96000  -> 96000
            ResamplerMode.SR_176400 -> 176400
            ResamplerMode.SR_192000 -> 192000
        }
    }
}