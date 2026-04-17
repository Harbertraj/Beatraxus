package com.beatflowy.app.engine

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.beatflowy.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
    }

    private val resampler  = Resampler()
    private val equalizer  = Equalizer10Band()
    private val outputMgr  = OutputManager(context)

    private lateinit var exoPlayer: ExoPlayer
    private val processor  = BeatflowyAudioProcessor()
    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _audioState    = MutableStateFlow(AudioState())
    val audioStateFlow: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackStateFlow: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var currentSong: Song? = null

    fun prepare() {
        val audioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(processor))
            .setEnableFloatOutput(true)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return audioSink
            }
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }
            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = _playbackState.value.copy(
                    isBuffering = state == Player.STATE_BUFFERING
                )
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
                _playbackState.value = _playbackState.value.copy(error = error.message)
            }
        })

        outputMgr.register()
        scope.launch {
            outputMgr.deviceFlow.collect { device ->
                _audioState.value = _audioState.value.copy(outputDevice = device.displayName)
                currentSong?.let { reconfigurePipeline(it) }
            }
        }
    }

    fun release() {
        scope.cancel()
        outputMgr.unregister()
        if (::exoPlayer.isInitialized) exoPlayer.release()
    }

    fun play(song: Song) {
        currentSong = song
        reconfigurePipeline(song)
        exoPlayer.setMediaItem(MediaItem.fromUri(song.uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        _playbackState.value = _playbackState.value.copy(
            currentSong = song, positionMs = 0L
        )
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun pause()  { exoPlayer.pause() }
    fun resume() { exoPlayer.play() }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        resampler.reset()
        equalizer.reset()
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
    }

    val currentPositionMs: Long
        get() = if (::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L

    fun setResamplingEnabled(enabled: Boolean) {
        resampler.isEnabled = enabled
        currentSong?.let { reconfigurePipeline(it) }
        _audioState.value = _audioState.value.copy(resamplingActive = enabled)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        equalizer.isEnabled = enabled
        _audioState.value = _audioState.value.copy(equalizerActive = enabled)
    }

    fun setEqGain(band: Int, gainDb: Float) {
        equalizer.setGain(band, gainDb)
    }

    private fun reconfigurePipeline(song: Song) {
        val inHz  = song.sampleRateHz
        val outHz = Resampler.targetSampleRate(inHz, resampler.isEnabled)
        resampler.configure(inHz, outHz)
        equalizer.setSampleRate(outHz)
        equalizer.reset()
        resampler.reset()
        processor.setOutputSampleRate(outHz)
        _audioState.value = AudioState(
            inputSampleRateHz  = inHz,
            outputSampleRateHz = outHz,
            outputDevice       = outputMgr.currentDevice.displayName,
            resamplingActive   = resampler.isEnabled,
            equalizerActive    = equalizer.isEnabled,
            bitDepth           = song.bitDepth
        )
    }

    inner class BeatflowyAudioProcessor : AudioProcessor {

        private var inputFormat  = AudioProcessor.AudioFormat.NOT_SET
        private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
        private var targetSampleRate = 48_000
        private var outputBuf    = ByteBuffer.allocate(0)!!
        private val ended        = AtomicBoolean(false)
        private var active       = false

        fun setOutputSampleRate(hz: Int) { targetSampleRate = hz }

        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            inputFormat = inputAudioFormat
            if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
                active = false
                return AudioProcessor.AudioFormat.NOT_SET
            }
            outputFormat = AudioProcessor.AudioFormat(
                targetSampleRate,
                inputAudioFormat.channelCount,
                C.ENCODING_PCM_FLOAT
            )
            active = true
            return outputFormat
        }

        override fun isActive(): Boolean = active

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val floatCount  = inputBuffer.remaining() / 4
            val channels    = inputFormat.channelCount.coerceAtLeast(1)
            val frameCount  = floatCount / channels
            val floats      = FloatArray(floatCount)
            inputBuffer.asFloatBuffer().get(floats)
            inputBuffer.position(inputBuffer.limit())

            val (processed, frames) = resampler.process(floats, frameCount)
            if (equalizer.isEnabled) equalizer.process(processed, frames)

            val outBytes = frames * channels * 4
            if (outputBuf.capacity() < outBytes) {
                outputBuf = ByteBuffer.allocateDirect(outBytes)
            }
            outputBuf.clear()
            outputBuf.asFloatBuffer().put(processed, 0, frames * channels)
            outputBuf.limit(outBytes)
        }

        override fun queueEndOfStream() { ended.set(true) }

        override fun getOutput(): ByteBuffer {
            val out = outputBuf.duplicate()
            outputBuf = ByteBuffer.allocate(0)
            return out
        }

        override fun isEnded(): Boolean = ended.get() && !outputBuf.hasRemaining()

        override fun flush() {
            outputBuf = ByteBuffer.allocate(0)
            ended.set(false)
        }

        override fun reset() {
            outputBuf = ByteBuffer.allocate(0)
            ended.set(false)
            active = false
        }
    }
}
