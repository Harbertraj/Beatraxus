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

class AudioEngine(private val context: Context, private val output: AudioOutput) {
    private val TAG = "AudioEngine"
    private var job: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private val seekPositionMs = java.util.concurrent.atomic.AtomicLong(-1)
    /** Thread-safe playback position for UI polling (extractor time base). */
    private val positionMsAtomic = AtomicLong(0L)
    
    private val _audioStateFlow = MutableStateFlow(AudioState())
    val audioStateFlow = _audioStateFlow.asStateFlow()

    private val _playbackStateFlow = MutableStateFlow(PlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private var currentSong: Song? = null

    fun currentPositionMs(): Long = positionMsAtomic.get()

    private val _onCompletion = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    val onCompletion = _onCompletion.asSharedFlow()

    fun play(song: Song) {
        cancelDecodeJob()
        positionMsAtomic.set(0L)
        currentSong = song
        _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }
        job = CoroutineScope(Dispatchers.IO).launch {
            val reachedEnd = decodeAndPlay(song.uri, song.id, startPositionMs = 0L)
            if (reachedEnd) {
                _onCompletion.emit(Unit)
            }
        }
    }

    fun resume() {
        if (isPlaying.get()) return
        val song = currentSong ?: return
        val resumeFrom = positionMsAtomic.get()
        _playbackStateFlow.update { it.copy(isPlaying = true) }
        job = CoroutineScope(Dispatchers.IO).launch {
            decodeAndPlay(song.uri, song.id, startPositionMs = resumeFrom)
        }
    }

    fun setShuffleMode(enabled: Boolean) {
        _playbackStateFlow.update { it.copy(shuffleMode = enabled) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _playbackStateFlow.update { it.copy(repeatMode = mode) }
    }

    /**
     * Stops decoding immediately and publishes [PlaybackState.isPlaying] = false so UI never
     * stays out of sync while the decode coroutine unwinds.
     */
    fun stop() {
        cancelDecodeJob()
        _playbackStateFlow.update { it.copy(isPlaying = false) }
    }

    private fun cancelDecodeJob() {
        isPlaying.set(false)
        job?.cancel()
        job = null
        output.stop()
    }

    fun seekTo(positionMs: Long) {
        seekPositionMs.set(positionMs)
    }

    private suspend fun decodeAndPlay(uri: Uri, sessionSongId: String, startPositionMs: Long): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var reachedEnd = false
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }

            val candidates = mutableListOf<Pair<Int, MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                candidates.add(i to format)
            }
            if (candidates.isEmpty()) throw Exception("No audio track found")

            val ordered = candidates.sortedWith(
                compareByDescending<Pair<Int, MediaFormat>> { (_, f) ->
                    audioMimePriority(f.getString(MediaFormat.KEY_MIME) ?: "")
                }.thenBy { it.first }
            )

            var selectedFormat: MediaFormat? = null
            var selectedMime: String? = null
            var lastFailure: Exception? = null
            pickCodec@ for (requireListedDecoder in listOf(true, false)) {
                for ((idx, format) in ordered) {
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (requireListedDecoder && !isDecoderLikelyAvailable(mime)) {
                        Log.w(TAG, "Skipping mime (no decoder listed, strict pass): $mime")
                        continue
                    }
                    try {
                        extractor.selectTrack(idx)
                        val dec = MediaCodec.createDecoderByType(mime)
                        dec.configure(format, null, null, 0)
                        dec.start()
                        codec = dec
                        selectedFormat = format
                        selectedMime = mime
                        break@pickCodec
                    } catch (e: Exception) {
                        lastFailure = e
                        Log.w(TAG, "Decoder init failed for track=$idx mime=$mime: ${e.message}")
                        try {
                            codec?.release()
                        } catch (_: Exception) {
                        }
                        codec = null
                        try {
                            extractor.unselectTrack(idx)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (codec == null) {
                throw lastFailure ?: IllegalStateException("No working audio decoder for this file")
            }

            val format = selectedFormat!!
            val mime = selectedMime!!
            Log.d(TAG, "Playing mime=$mime format=$format")

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Re-fetch format after start, some codecs update it
            val outputFormat = codec.outputFormat
            val actualSampleRate = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) 
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
            val actualChannels = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) 
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels

            if (!output.init(actualSampleRate, actualChannels)) return false
            output.start()

            val info = MediaCodec.BufferInfo()
            _audioStateFlow.update { it.copy(sampleRate = actualSampleRate) }
            
            if (startPositionMs > 0) {
                extractor.seekTo(startPositionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            isPlaying.set(true)

            while (isPlaying.get()) {
                val seekPos = seekPositionMs.getAndSet(-1)
                if (seekPos != -1L) {
                    extractor.seekTo(seekPos * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    positionMsAtomic.set(seekPos.coerceAtLeast(0L))
                }

                val sampleUs = extractor.sampleTime
                if (sampleUs >= 0) {
                    positionMsAtomic.set(sampleUs / 1000)
                }
                val inIdx = codec.dequeueInputBuffer(10000)
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

                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    output.stop()
                    output.init(newSampleRate, newChannels)
                    output.start()
                    _audioStateFlow.update { it.copy(sampleRate = newSampleRate) }
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)

                    if (info.size > 0) {
                        // Support different PCM encodings if possible, but default to 16-bit to Float
                        val pcmEncoding = if (codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT

                        val floatData = when (pcmEncoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> {
                                val floats = FloatArray(info.size / 4)
                                buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                                floats
                            }
                            else -> {
                                // Default to 16-bit
                                val shorts = ShortArray(info.size / 2)
                                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                FloatArray(shorts.size) { shorts[it] / 32768f }
                            }
                        }
                        
                        val currentChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (floatData.isNotEmpty() && currentChannels > 0) {
                            output.write(floatData, floatData.size / currentChannels)
                        }
                    }
                    
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        reachedEnd = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
        } finally {
            // Avoid racing a newer session: only clear playing if this decode still owns the song.
            if (currentSong?.id == sessionSongId) {
                _playbackStateFlow.update { it.copy(isPlaying = false) }
            }
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
            extractor.release()
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
