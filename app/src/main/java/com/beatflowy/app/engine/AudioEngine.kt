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
    private val processor  = BeatraxusAudioProcessor()
    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _audioState    = MutableStateFlow(AudioState())
    val audioStateFlow: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackStateFlow: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var currentSong: Song? = null

    private var songQueue: List<Song> = emptyList()

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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = exoPlayer.currentMediaItemIndex
                if (index >= 0 && index < songQueue.size) {
                    val song = songQueue[index]
                    currentSong = song
                    reconfigurePipeline(song)
                    _playbackState.value = _playbackState.value.copy(
                        currentSong = song,
                        positionMs = 0L
                    )
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }
            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = _playbackState.value.copy(
                    isBuffering = state == Player.STATE_BUFFERING
                )
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playbackState.value = _playbackState.value.copy(shuffleMode = shuffleModeEnabled)
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                val mode = when(repeatMode) {
                    Player.REPEAT_MODE_OFF -> 0
                    Player.REPEAT_MODE_ONE -> 1
                    Player.REPEAT_MODE_ALL -> 2
                    else -> 0
                }
                _playbackState.value = _playbackState.value.copy(repeatMode = mode)
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
        songQueue = listOf(song)
        currentSong = song
        reconfigurePipeline(song)
        exoPlayer.setMediaItem(MediaItem.fromUri(song.uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        _playbackState.value = _playbackState.value.copy(
            currentSong = song, 
            positionMs = 0L,
            shuffleMode = exoPlayer.shuffleModeEnabled,
            repeatMode = when(exoPlayer.repeatMode) {
                Player.REPEAT_MODE_OFF -> 0
                Player.REPEAT_MODE_ONE -> 1
                Player.REPEAT_MODE_ALL -> 2
                else -> 0
            }
        )
    }

    fun playList(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        songQueue = songs
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        
        val items = songs.map { MediaItem.fromUri(it.uri) }
        exoPlayer.addMediaItems(items)
        
        currentSong = songs[startIndex]
        reconfigurePipeline(currentSong!!)
        
        exoPlayer.seekTo(startIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        
        _playbackState.value = _playbackState.value.copy(
            currentSong = currentSong,
            positionMs = 0L,
            shuffleMode = exoPlayer.shuffleModeEnabled,
            repeatMode = when(exoPlayer.repeatMode) {
                Player.REPEAT_MODE_OFF -> 0
                Player.REPEAT_MODE_ONE -> 1
                Player.REPEAT_MODE_ALL -> 2
                else -> 0
            }
        )
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun pause()  { exoPlayer.pause() }
    fun resume() { exoPlayer.play() }

    fun next() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        }
    }

    fun getNextSong(): Song? {
        val nextIndex = exoPlayer.nextMediaItemIndex
        return if (nextIndex != C.INDEX_UNSET && nextIndex < songQueue.size) {
            songQueue[nextIndex]
        } else null
    }

    fun previous() {
        if (exoPlayer.currentPosition > 3000) {
            exoPlayer.seekTo(0)
        } else if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
        }
    }

    fun toggleShuffle() {
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    fun setShuffleMode(enabled: Boolean) {
        exoPlayer.shuffleModeEnabled = enabled
    }

    fun toggleRepeat() {
        exoPlayer.repeatMode = when(exoPlayer.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        resampler.reset()
        equalizer.reset()
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
    }

    val currentPositionMs: Long
        get() = if (::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L

    fun setResamplingEnabled(enabled: Boolean) {
        resampler.isEnabled = false
        _audioState.value = _audioState.value.copy(resamplingActive = false)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        equalizer.isEnabled = enabled
        _audioState.value = _audioState.value.copy(equalizerActive = enabled)
    }

    fun setEqGain(band: Int, gainDb: Float) {
        equalizer.setGain(band, gainDb)
        updateAudioState()
    }

    fun setPreamp(db: Float) {
        equalizer.setPreampManual(db)
        updateAudioState()
    }

    fun setTone(bass: Float, treble: Float) {
        // Simple bass/treble shelf implementation inside Equalizer10Band
        // or a separate Tone class. For now, let's assume Equalizer10Band handles it.
        equalizer.setTone(bass, treble)
        updateAudioState()
    }

    fun setBalance(balance: Float) {
        processor.setBalance(balance)
        updateAudioState()
    }

    fun setStereoExpand(expand: Float) {
        processor.setStereoExpand(expand)
        updateAudioState()
    }

    fun setTempo(tempo: Float) {
        exoPlayer.setPlaybackSpeed(tempo)
        updateAudioState()
    }

    fun setMono(mono: Boolean) {
        processor.setMono(mono)
        updateAudioState()
    }

    fun setReverbEnabled(enabled: Boolean) {
        processor.setReverbEnabled(enabled)
        updateAudioState()
    }

    fun setReverbParams(mix: Float, size: Float, damp: Float, filter: Float, fade: Float, preDelay: Float, preDelayMix: Float) {
        processor.setReverbParams(mix, size, damp, filter, fade, preDelay, preDelayMix)
        updateAudioState()
    }

    private fun updateAudioState() {
        val song = currentSong ?: return
        _audioState.value = AudioState(
            inputSampleRateHz  = song.sampleRateHz,
            outputSampleRateHz = Resampler.targetSampleRate(song.sampleRateHz, resampler.isEnabled),
            outputDevice       = outputMgr.currentDevice.displayName,
            resamplingActive   = resampler.isEnabled,
            equalizerActive    = equalizer.isEnabled,
            bitDepth           = song.bitDepth,
            preampDb           = equalizer.getPreampManual(),
            bassDb             = equalizer.getBassDb(),
            trebleDb           = equalizer.getTrebleDb(),
            balance            = processor.getBalance(),
            stereoExpand       = processor.getStereoExpand(),
            tempo              = exoPlayer.playbackParameters.speed,
            isMono             = processor.getMono(),
            reverbMix          = processor.getReverbMix(),
            reverbSize         = processor.getReverbSize(),
            reverbDamp         = processor.getReverbDamp(),
            reverbFilter       = processor.getReverbFilter(),
            reverbFade         = processor.getReverbFade(),
            reverbPreDelay     = processor.getReverbPreDelay(),
            reverbPreDelayMix  = processor.getReverbPreDelayMix()
        )
    }

    private fun reconfigurePipeline(song: Song) {
        updateAudioState()
    }

    inner class BeatraxusAudioProcessor : AudioProcessor {

        private var inputFormat  = AudioProcessor.AudioFormat.NOT_SET
        private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
        private var buffer       = AudioProcessor.EMPTY_BUFFER
        private var outputBuffer = AudioProcessor.EMPTY_BUFFER
        private val ended        = AtomicBoolean(false)
        private var active       = false

        // DSP Params
        private var balance = 0f
        private var stereoExpand = 0f
        private var isMono = false
        private var reverbEnabled = false
        private var reverbMix = 0f
        private var reverbSize = 0.5f
        private var reverbDamp = 0.5f
        private var reverbFilter = 1.0f
        private var reverbFade = 1.0f
        private var reverbPreDelay = 0f
        private var reverbPreDelayMix = 0f

        fun setBalance(v: Float) { balance = v }
        fun getBalance() = balance
        fun setStereoExpand(v: Float) { stereoExpand = v }
        fun getStereoExpand() = stereoExpand
        fun setMono(v: Boolean) { isMono = v }
        fun getMono() = isMono
        fun setReverbEnabled(v: Boolean) { reverbEnabled = v }
        fun setReverbParams(mix: Float, size: Float, damp: Float, filter: Float, fade: Float, preDelay: Float, preDelayMix: Float) {
            reverbMix = mix
            reverbSize = size
            reverbDamp = damp
            reverbFilter = filter
            reverbFade = fade
            reverbPreDelay = preDelay
            reverbPreDelayMix = preDelayMix
        }
        fun getReverbMix() = reverbMix
        fun getReverbSize() = reverbSize
        fun getReverbDamp() = reverbDamp
        fun getReverbFilter() = reverbFilter
        fun getReverbFade() = reverbFade
        fun getReverbPreDelay() = reverbPreDelay
        fun getReverbPreDelayMix() = reverbPreDelayMix

        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            inputFormat = inputAudioFormat
            
            // Allow both FLOAT and 16-bit PCM for broader compatibility.
            // Some decoders might not output float directly.
            val supportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT || 
                                   inputAudioFormat.encoding == C.ENCODING_PCM_16BIT

            if (!supportedEncoding) {
                Log.w(TAG, "Processor inactive: input encoding is ${inputAudioFormat.encoding}")
                active = false
                return AudioProcessor.AudioFormat.NOT_SET
            }

            // Real-time configuration based on actual decoded stream
            val inHz = inputAudioFormat.sampleRate
            val outHz = Resampler.targetSampleRate(inHz, resampler.isEnabled)
            
            Log.d(TAG, "Configuring Resampler: $inHz -> $outHz (Channels: ${inputAudioFormat.channelCount}, Encoding: ${inputAudioFormat.encoding})")
            resampler.configure(inHz, outHz, inputAudioFormat.channelCount)
            equalizer.setSampleRate(outHz)
            
            outputFormat = AudioProcessor.AudioFormat(
                outHz,
                inputAudioFormat.channelCount,
                C.ENCODING_PCM_FLOAT
            )
            active = true
            return outputFormat
        }

        override fun isActive(): Boolean = active

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            
            val channels = inputFormat.channelCount
            val floats: FloatArray
            val frameCount: Int

            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                val floatCount = inputBuffer.remaining() / 4
                frameCount = floatCount / channels
                floats = FloatArray(floatCount)
                inputBuffer.asFloatBuffer().get(floats)
            } else {
                // Convert 16-bit PCM to Float [-1.0, 1.0]
                val shortCount = inputBuffer.remaining() / 2
                frameCount = shortCount / channels
                floats = FloatArray(shortCount)
                for (i in 0 until shortCount) {
                    val s = inputBuffer.short
                    floats[i] = s.toFloat() / 32768.0f
                }
            }
            inputBuffer.position(inputBuffer.limit())

            // Resample and Equalize
            val (processed, frames) = resampler.process(floats, frameCount)
            
            // Apply Mono / Balance / Stereo Expand
            applyDsp(processed, frames, channels)

            if (equalizer.isEnabled) {
                equalizer.process(processed, frames)
            }

            // TODO: Reverb processing (Simple Schroeder Reverb could be added)

            val outBytes = frames * channels * 4
            if (buffer.capacity() < outBytes) {
                buffer = ByteBuffer.allocateDirect(outBytes).order(java.nio.ByteOrder.nativeOrder())
            } else {
                buffer.clear()
            }
            
            buffer.asFloatBuffer().put(processed, 0, frames * channels)
            buffer.limit(outBytes)
            this.outputBuffer = buffer
        }

        override fun queueEndOfStream() { 
            ended.set(true) 
        }

        override fun getOutput(): ByteBuffer {
            val out = outputBuffer
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return out
        }

        override fun isEnded(): Boolean = ended.get() && !outputBuffer.hasRemaining()

        override fun flush() {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            ended.set(false)
        }

        override fun reset() {
            flush()
            buffer = AudioProcessor.EMPTY_BUFFER
            inputFormat = AudioProcessor.AudioFormat.NOT_SET
            outputFormat = AudioProcessor.AudioFormat.NOT_SET
            active = false
        }

        private fun applyDsp(buffer: FloatArray, frames: Int, channels: Int) {
            if (channels < 2) return
            
            for (i in 0 until frames) {
                var l = buffer[i * 2]
                var r = buffer[i * 2 + 1]

                // Mono
                if (isMono) {
                    val m = (l + r) * 0.5f
                    l = m
                    r = m
                }

                // Stereo Expand (Mid-Side processing)
                if (stereoExpand > 0 && !isMono) {
                    val mid = (l + r) * 0.5f
                    var side = (l - r) * 0.5f
                    side *= (1.0f + stereoExpand)
                    l = mid + side
                    r = mid - side
                }

                // Balance
                if (balance < 0) {
                    r *= (1.0f + balance)
                } else if (balance > 0) {
                    l *= (1.0f - balance)
                }

                buffer[i * 2] = l
                buffer[i * 2 + 1] = r
            }
        }
    }
}
