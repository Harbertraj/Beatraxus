package com.beatflowy.app.engine

import android.content.Context
import android.media.*
import android.media.MediaCodecList
import android.net.Uri
import android.util.Log
import com.beatflowy.app.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class AudioEngine(private val context: Context, private val output: AudioOutput) {
    private val TAG = "AudioEngine"
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var playbackJob: Job? = null
    
    // We use a sessionId to invalidate old decode loops if they somehow linger
    private var currentSessionId = 0
    
    private val isPlaying = AtomicBoolean(false)
    private val seekPositionMs = java.util.concurrent.atomic.AtomicLong(-1)
    private val positionMsAtomic = AtomicLong(0L)
    
    private val _audioStateFlow = MutableStateFlow(AudioState())
    val audioStateFlow = _audioStateFlow.asStateFlow()

    private val _playbackStateFlow = MutableStateFlow(PlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private var currentSong: Song? = null

    fun currentPositionMs(): Long {
        val pending = seekPositionMs.get()
        return if (pending != -1L) pending else positionMsAtomic.get()
    }

    private val _onCompletion = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    val onCompletion = _onCompletion.asSharedFlow()

    fun play(song: Song) {
        currentSong = song
        _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }
        
        val sessionId = ++currentSessionId
        
        playbackJob?.cancel()
        playbackJob = engineScope.launch {
            try {
                // Ensure the previous job is truly finished before starting new one 
                // to avoid racing on AudioTrack resources
                yield() 
                
                positionMsAtomic.set(0L)
                val reachedEnd = decodeAndPlay(song.uri, sessionId, startPositionMs = 0L)
                if (reachedEnd && isActive && sessionId == currentSessionId) {
                    _onCompletion.emit(Unit)
                }
            } catch (e: CancellationException) {
                // Ignore
            } catch (e: Exception) {
                Log.e(TAG, "Playback job failed", e)
            }
        }
    }

    fun resume() {
        if (isPlaying.get()) return
        val song = currentSong ?: return
        val resumeFrom = positionMsAtomic.get()
        _playbackStateFlow.update { it.copy(isPlaying = true) }
        
        val sessionId = ++currentSessionId
        playbackJob?.cancel()
        playbackJob = engineScope.launch {
            decodeAndPlay(song.uri, sessionId, startPositionMs = resumeFrom)
        }
    }

    fun setShuffleMode(enabled: Boolean) {
        _playbackStateFlow.update { it.copy(shuffleMode = enabled) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _playbackStateFlow.update { it.copy(repeatMode = mode) }
    }

    fun stop() {
        isPlaying.set(false)
        playbackJob?.cancel()
        _playbackStateFlow.update { it.copy(isPlaying = false) }
        output.stop()
    }

    fun seekTo(positionMs: Long) {
        seekPositionMs.set(positionMs)
        output.flush()
    }

    private suspend fun decodeAndPlay(uri: Uri, sessionId: Int, startPositionMs: Long): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var reachedEnd = false
        
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return false

            if (sessionId != currentSessionId) return false

            val candidates = mutableListOf<Pair<Int, MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) candidates.add(i to format)
            }
            if (candidates.isEmpty()) return false

            val (trackIndex, format) = candidates.sortedByDescending { (_, f) ->
                audioMimePriority(f.getString(MediaFormat.KEY_MIME) ?: "")
            }.first()

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            if (!output.init(sampleRate, channels)) return false
            output.start()

            val info = MediaCodec.BufferInfo()
            _audioStateFlow.update { it.copy(sampleRate = sampleRate) }
            
            if (startPositionMs > 0) {
                extractor.seekTo(startPositionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            isPlaying.set(true)

            while (isPlaying.get() && sessionId == currentSessionId && coroutineContext.isActive) {
                val seekPos = seekPositionMs.getAndSet(-1)
                if (seekPos != -1L) {
                    extractor.seekTo(seekPos * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    output.flush()
                    val actualPos = extractor.sampleTime
                    positionMsAtomic.set(if (actualPos >= 0) actualPos / 1000 else seekPos)
                }

                val inIdx = codec.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                if (outIdx >= 0) {
                    if (info.presentationTimeUs >= 0) {
                        positionMsAtomic.set(info.presentationTimeUs / 1000)
                    }
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val shorts = ShortArray(info.size / 2)
                        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        val floats = FloatArray(shorts.size) { shorts[it] / 32768f }
                        output.write(floats, floats.size / channels)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        reachedEnd = true
                        break
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    output.init(newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), 
                               newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    output.start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in decode loop", e)
        } finally {
            if (sessionId == currentSessionId) {
                isPlaying.set(false)
                _playbackStateFlow.update { it.copy(isPlaying = false) }
            }
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
            output.stop()
        }
        return reachedEnd
    }

    fun release() {
        stop()
        output.release()
    }

    private fun audioMimePriority(mime: String): Int {
        val m = mime.lowercase()
        return when {
            m.contains("alac") -> 120
            m.contains("flac") -> 110
            m.contains("opus") -> 105
            m.contains("vorbis") -> 100
            m.contains("mpeg") || m.contains("mp3") -> 95
            m.contains("mp4a") || m.contains("aac") || m.contains("latm") -> 90
            m.contains("amr") -> 50
            else -> 10
        }
    }

    private fun isDecoderLikelyAvailable(mime: String): Boolean {
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (_: Exception) {
            true
        }
    }
}
