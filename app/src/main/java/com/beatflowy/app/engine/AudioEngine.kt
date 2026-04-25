package com.beatflowy.app.engine

import android.content.Context
import android.os.Process
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong

class AudioEngine(
    context: Context,
    private val output: AudioOutput
) {
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
    private var currentSessionId = 0
    private var sessionJob: Job? = null
    private var activeSession: PlaybackSession? = null
    private var positionMs: Long = 0L
    private var underrunCount = 0

    fun currentPositionMs(): Long = activeSession?.currentRenderedPositionMs() ?: positionMs

    fun play(song: Song) {
        currentSong = song
        positionMs = 0L
        _audioStateFlow.update {
            it.copy(
                codec = normalizeCodec(song.format),
                bitrate = song.bitrate,
                outputPath = deriveOutputPath(song.sampleRateHz),
                resamplerActive = false
            )
        }
        _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }
        startSession(song, startPositionMs = 0L)
    }

    fun resume() {
        val song = currentSong ?: return
        if (_playbackStateFlow.value.isPlaying) return
        _playbackStateFlow.update { it.copy(isPlaying = true, currentSong = song) }
        startSession(song, startPositionMs = currentPositionMs())
    }

    fun stop() {
        positionMs = activeSession?.currentRenderedPositionMs() ?: positionMs
        activeSession?.stop()
        activeSession = null
        sessionJob?.cancel()
        _playbackStateFlow.update { it.copy(isPlaying = false) }
        output.stop()
    }

    fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
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

    fun setEqualizerEnabled(enabled: Boolean) {
        _audioStateFlow.update { current ->
            current.copy(equalizerActive = enabled)
        }
    }

    private fun startSession(song: Song, startPositionMs: Long) {
        activeSession?.stop()
        sessionJob?.cancel()

        val sessionId = ++currentSessionId
        val session = PlaybackSession(sessionId, song, startPositionMs)
        activeSession = session

        sessionJob = engineScope.launch {
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                val ended = session.run()
                if (ended && isActive && sessionId == currentSessionId) {
                    _onCompletion.emit(Unit)
                }
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                Log.e(TAG, "Playback session failed", t)
            } finally {
                if (sessionId == currentSessionId) {
                    positionMs = session.currentRenderedPositionMs()
                    _playbackStateFlow.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    private inner class PlaybackSession(
        private val sessionId: Int,
        private val song: Song,
        private val initialStartPositionMs: Long
    ) : DecoderSink, DecoderControl {
        private val ringBuffer = FloatRingBuffer(RING_BUFFER_SAMPLES)
        private var rendererJob: Job? = null
        private val pendingSeekMs = AtomicLong(NO_SEEK_PENDING)
        @Volatile private var started = true
        private var decoderCompleted = false
        private var pcmFormat: PcmAudioFormat? = null
        private var basePositionMs: Long = initialStartPositionMs

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
                        performSeek(result.positionMs)
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
            started = false
            ringBuffer.close()
            rendererJob?.cancel()
            output.stop()
        }

        fun requestSeek(positionMs: Long) {
            pendingSeekMs.set(positionMs)
            this@AudioEngine.positionMs = positionMs
        }

        fun currentRenderedPositionMs(): Long {
            val sampleRate = pcmFormat?.sampleRate ?: 44_100
            return basePositionMs + framesToMs(output.playbackPositionFrames(), sampleRate)
        }

        override suspend fun configure(format: PcmAudioFormat) {
            val formatChanged = pcmFormat != format
            pcmFormat = format
            if (formatChanged) {
                ringBuffer.clear()
                output.flush()
            }
            if (!output.init(format.sampleRate, format.channels)) {
                throw IllegalStateException("AudioTrack init failed for ${format.sampleRate}Hz/${format.channels}ch")
            }
            output.start()
            _audioStateFlow.update {
                it.copy(
                    sampleRate = format.sampleRate,
                    outputSampleRate = output.outputSampleRate(),
                    bitDepth = format.bitDepth,
                    outputPath = output.outputPathLabel(),
                    resamplerActive = format.sampleRate != output.outputSampleRate(),
                    outputLatencyMs = output.estimatedLatencyMs(),
                    underrunCount = underrunCount
                )
            }
            logDebug("Configured output ${format.sampleRate}Hz ${format.channels}ch ${format.bitDepth}bit")
        }

        override suspend fun write(data: FloatArray, sampleCount: Int) {
            ringBuffer.write(data, sampleCount)
        }

        override fun isActive(): Boolean {
            return started && sessionId == currentSessionId
        }

        override fun consumePendingSeekMs(): Long? {
            val requested = pendingSeekMs.getAndSet(NO_SEEK_PENDING)
            return if (requested == NO_SEEK_PENDING) null else requested
        }

        override fun logDebug(message: String) {
            Log.d(TAG, "[session=$sessionId] $message")
        }

        override fun logWarn(message: String) {
            Log.w(TAG, "[session=$sessionId] $message")
        }

        private suspend fun renderLoop() {
            val localBuffer = FloatArray(RENDER_BATCH_SAMPLES)

            while (started && sessionId == currentSessionId) {
                val format = pcmFormat
                if (format == null) {
                    delay(2)
                    continue
                }

                val sampleCount = ringBuffer.read(localBuffer, localBuffer.size)
                if (sampleCount > 0) {
                    val frames = sampleCount / format.channels
                    var writtenFramesTotal = 0
                    while (writtenFramesTotal < frames && started && sessionId == currentSessionId) {
                        val written = output.write(
                            data = localBuffer,
                            offsetInSamples = writtenFramesTotal * format.channels,
                            frameCount = frames - writtenFramesTotal
                        )
                        if (written <= 0) {
                            underrunCount++
                            _audioStateFlow.update { state ->
                                state.copy(
                                    outputLatencyMs = output.estimatedLatencyMs(),
                                    underrunCount = underrunCount
                                )
                            }
                            logWarn("Renderer underrun")
                            delay(2)
                            break
                        }
                        writtenFramesTotal += written
                    }
                    positionMs = currentRenderedPositionMs()
                    _audioStateFlow.update { state -> state.copy(outputLatencyMs = output.estimatedLatencyMs()) }
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
            logDebug("Seek -> ${positionMs}ms")
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val RING_BUFFER_SAMPLES = 262_144
        private const val RENDER_BATCH_SAMPLES = 16_384
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

        private fun deriveOutputPath(sampleRate: Int): String {
            return if (sampleRate > 48_000) "Hi-Res" else "AudioTrack"
        }
    }
}
