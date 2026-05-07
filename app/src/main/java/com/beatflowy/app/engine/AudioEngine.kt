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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioEngine(
    context: Context,
    private val output: AudioOutput
) {
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val controlMutex = Mutex()
    
    private val decoderFactory = DecoderFactory(
        context = context,
        ffmpegAlacDecoder = FfmpegAlacDecoder(context),
        mediaCodecDecoder = MediaCodecAudioDecoder(context)
    )

    private val _audioStateFlow = MutableStateFlow(AudioState())
    val audioStateFlow = _audioStateFlow.asStateFlow()

    private val _playbackStateFlow = MutableStateFlow(PlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private val _onCompletion = MutableSharedFlow<Unit>()
    val onCompletion = _onCompletion.asSharedFlow()

    private var currentSong: Song? = null
    private var currentSessionId = AtomicLong(0)
    private var sessionJob: Job? = null
    private var activeSession: PlaybackSession? = null
    private var positionMs: Long = 0L
    private var underrunCount = 0
    @Volatile private var dspConfig: DspConfig = DspConfig()
    private val dspRevision = AtomicLong(0L)
    private val isSeeking = AtomicBoolean(false)

    fun currentPositionMs(): Long {
        if (isSeeking.get()) return positionMs
        return activeSession?.currentRenderedPositionMs() ?: positionMs
    }

    fun play(song: Song) {
        engineScope.launch {
            controlMutex.withLock {
                currentSong = song
                positionMs = 0L
                updateAudioStateForSong(song)
                
                // Important: Don't set isPlaying=true yet. 
                // We update currentSong so UI shows the new title, but keep isPlaying as-is or false if transitioning.
                _playbackStateFlow.update { it.copy(currentSong = song) }
                
                stopInternal()
                startSessionInternal(song, startPositionMs = 0L)
            }
        }
    }

    fun prepare(song: Song) {
        engineScope.launch {
            controlMutex.withLock {
                currentSong = song
                positionMs = 0L
                updateAudioStateForSong(song)
                _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = false) }
                stopInternal()
            }
        }
    }

    private fun updateAudioStateForSong(song: Song) {
        _audioStateFlow.update {
            it.copy(
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
                startSessionInternal(song, startPositionMs = currentPositionMs())
            }
        }
    }

    fun stop() {
        engineScope.launch {
            controlMutex.withLock {
                stopInternal()
            }
        }
    }

    private fun stopInternal() {
        positionMs = activeSession?.currentRenderedPositionMs() ?: positionMs
        activeSession?.stop()
        activeSession = null
        sessionJob?.cancel()
        currentSessionId.incrementAndGet()
        _playbackStateFlow.update { it.copy(isPlaying = false) }
        output.stop()
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
                    publishDspState(activeSession?.currentFormat())
                }
            }
        }
    }

    fun updateDspConfig(config: DspConfig) {
        val oldConfig = dspConfig
        val oldTargetRate = resolveTargetSampleRate(currentSong?.sampleRateHz ?: 44100, oldConfig)
        dspConfig = config
        val newTargetRate = resolveTargetSampleRate(currentSong?.sampleRateHz ?: 44100, config)

        output.setDvcState(
            enabled = config.dvcEnabled,
            mode = config.dvcMode.name,
            level = config.dvcLevel
        )
        publishDspState()

        val structuralChange = oldTargetRate != newTargetRate ||
                oldConfig.sampleFormat != config.sampleFormat ||
                oldConfig.outputMode != config.outputMode ||
                oldConfig.dvcEnabled != config.dvcEnabled

        if (structuralChange) {
            dspRevision.incrementAndGet()
            reconfigureOutput()
        }
    }

    private fun startSessionInternal(song: Song, startPositionMs: Long) {
        val sessionId = currentSessionId.get()
        val session = PlaybackSession(sessionId, song, startPositionMs)
        activeSession = session

        sessionJob = engineScope.launch {
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                
                // Ensure the output is clean before starting
                output.stop()
                
                val ended = session.run()
                if (ended && isActive && sessionId == currentSessionId.get()) {
                    _onCompletion.emit(Unit)
                }
            } catch (_: CancellationException) {
                // Normal cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "Playback session $sessionId failed", t)
            } finally {
                if (sessionId == currentSessionId.get()) {
                    val finalPos = session.currentRenderedPositionMs()
                    positionMs = finalPos
                    _playbackStateFlow.update { it.copy(isPlaying = false) }
                    session.stop()
                }
            }
        }
    }

    private inner class PlaybackSession(
        private val sessionId: Long,
        private val song: Song,
        private val initialStartPositionMs: Long
    ) : DecoderSink, DecoderControl {
        private val ringBuffer = FloatRingBuffer(RING_BUFFER_SAMPLES)
        private var rendererJob: Job? = null
        private val pendingSeekMs = AtomicLong(NO_SEEK_PENDING)
        @Volatile private var started = true
        private var decoderCompleted = false
        var pcmFormat: PcmAudioFormat? = null
            private set
        private var basePositionMs: Long = initialStartPositionMs
        private var dspPipeline = AudioDspPipeline.create(44_100, 44_100, 2, this@AudioEngine.dspConfig, song)
        private var appliedDspRevision = -1L

        suspend fun run(): Boolean {
            val decoder = decoderFactory.create(song)
            rendererJob = engineScope.launch(Dispatchers.IO) {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                renderLoop()
            }

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
                        while (isActive() && !ringBuffer.isEmpty()) {
                            delay(5)
                        }
                        ringBuffer.close()
                        rendererJob?.join()
                        return true
                    }

                    is DecodeResult.Seek -> {
                        notifySeek(result.positionMs)
                        decodeStartMs = result.positionMs
                    }

                    is DecodeResult.Failed -> {
                        logWarn("Decoder failed: ${result.reason ?: "unknown"}")
                        ringBuffer.close()
                        rendererJob?.join()
                        return false
                    }
                }
            }

            ringBuffer.close()
            rendererJob?.join()
            return false
        }

        fun stop() {
            if (!started) return
            val finalPos = currentRenderedPositionMs()
            started = false
            ringBuffer.close()
            rendererJob?.cancel()
            output.stop()
            if (sessionId == currentSessionId.get()) {
                this@AudioEngine.positionMs = finalPos
            }
        }

        fun requestSeek(positionMs: Long) {
            pendingSeekMs.set(positionMs)
            this@AudioEngine.positionMs = positionMs
        }

        fun currentFormat(): PcmAudioFormat? = pcmFormat

        fun currentRenderedPositionMs(): Long {
            val sampleRate = output.outputSampleRate().takeIf { it > 0 } ?: pcmFormat?.sampleRate ?: 44_100
            return basePositionMs + framesToMs(output.playbackPositionFrames(), sampleRate)
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
            if (formatChanged) {
                ringBuffer.clear()
                output.flush()
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
                val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                output.setTargetSampleRate(nativeRate)
                if (!output.init(format.sampleRate, format.channels, format.bitDepth)) {
                    output.setSampleFormat(SampleFormat.PCM_16BIT)
                    if (!output.init(format.sampleRate, format.channels, format.bitDepth)) {
                        throw IllegalStateException("AudioTrack init failed")
                    }
                }
            }
            
            output.start()
            
            // CRITICAL: UI state sync - only confirm playing when actually configured and output started
            if (sessionId == currentSessionId.get()) {
                _playbackStateFlow.update { it.copy(isPlaying = true) }
            }
            
            refreshDspPipeline(format)
            publishDspState(format)
        }

        override suspend fun write(data: FloatArray, sampleCount: Int) {
            ringBuffer.write(data, sampleCount)
        }

        override fun isActive(): Boolean {
            return started && sessionId == currentSessionId.get()
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

        private suspend fun renderLoop() {
            val localBuffer = FloatArray(RENDER_BATCH_SAMPLES)

            while (started && sessionId == currentSessionId.get()) {
                val format = pcmFormat
                if (format == null) {
                    delay(2)
                    continue
                }

                val sampleCount = ringBuffer.read(localBuffer, localBuffer.size)
                if (sampleCount > 0) {
                    dspPipeline.updateConfig(dspConfig)
                    if (appliedDspRevision != dspRevision.get()) {
                        refreshDspPipeline(format)
                        publishDspState(format)
                    }
                    val processed = dspPipeline.process(localBuffer, sampleCount, format.channels, format.sampleRate)
                    val frames = processed.sampleCount / format.channels
                    var writtenFramesTotal = 0
                    while (writtenFramesTotal < frames && started && sessionId == currentSessionId.get() && !isSeeking.get()) {
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
                    val newPos = currentRenderedPositionMs()
                    if (started && sessionId == currentSessionId.get() && !isSeeking.get()) {
                        this@AudioEngine.positionMs = newPos
                    }
                    continue
                }

                if (decoderCompleted && ringBuffer.isEmpty()) {
                    break
                }

                delay(2)
            }
        }

        private fun performSeek(positionMs: Long) {
            basePositionMs = positionMs
            decoderCompleted = false
            ringBuffer.clear()
            output.flush()
            this@AudioEngine.positionMs = positionMs
        }

        private fun refreshDspPipeline(format: PcmAudioFormat) {
            val actualOutputRate = output.outputSampleRate().takeIf { it > 0 } ?: resolveTargetSampleRate(format.sampleRate, dspConfig)
            dspPipeline = AudioDspPipeline.create(
                inputSampleRate = format.sampleRate,
                outputSampleRate = actualOutputRate,
                channels = format.channels,
                config = dspConfig,
                song = song
            )
            appliedDspRevision = dspRevision.get()
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val RING_BUFFER_SAMPLES = 131_072
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
                format == "m4a" || format == "mp4" || format.contains("aac") -> "AAC"
                format.contains("mpeg") || format.contains("mp3") -> "MP3"
                format.contains("wav") -> "WAV"
                format.isBlank() -> "Unknown"
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

    private fun publishDspState(format: PcmAudioFormat? = activeSession?.currentFormat()) {
        val sourceSampleRate = format?.sampleRate ?: _audioStateFlow.value.sampleRate
        val outputSampleRate = output.outputSampleRate().takeIf { it > 0 }
            ?: resolveTargetSampleRate(sourceSampleRate, dspConfig)
        val currentConfig = dspConfig
        _audioStateFlow.update { state ->
            state.copy(
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
                    codec = state.codec.ifBlank { currentSong?.format ?: "Unknown" },
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
                if (config.outputMode == OutputMode.HI_RES) {
                    val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                    maxOf(inputRate, maxOf(nativeRate, 96000))
                } else {
                    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                }
            }
            ResamplerMode.SR_44100 -> 44100
            ResamplerMode.SR_48000 -> 48000
            ResamplerMode.SR_88200 -> 88200
            ResamplerMode.SR_96000 -> 96000
            ResamplerMode.SR_176400 -> 176400
            ResamplerMode.SR_192000 -> 192000
        }
    }

}
