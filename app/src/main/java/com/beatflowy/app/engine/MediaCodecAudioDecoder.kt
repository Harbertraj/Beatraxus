package com.beatflowy.app.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MediaCodecAudioDecoder(private val context: Context) : AudioDecoder {
    override suspend fun decode(
        request: PlaybackRequest,
        sink: DecoderSink,
        control: DecoderControl
    ): DecodeResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            context.contentResolver.openFileDescriptor(request.song.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return@withContext DecodeResult.Failed("Unable to open source")

            val track = selectBestAudioTrack(extractor) ?: return@withContext DecodeResult.Failed("No audio track")
            extractor.selectTrack(track.index)

            codec = MediaCodec.createDecoderByType(track.mime)
            codec.configure(track.format, null, null, 0)
            codec.start()

            val initialFormat = codec.outputFormat
            sink.configure(
                PcmAudioFormat(
                    sampleRate = initialFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = initialFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    bitDepth = resolveBitDepth(initialFormat)
                )
            )

            if (request.startPositionMs > 0) {
                extractor.seekTo(request.startPositionMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val info = MediaCodec.BufferInfo()
            var currentPcmEncoding = resolvePcmEncoding(initialFormat)
            var currentChannels = initialFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatBuffer = FloatArray(PCM_CHUNK_SAMPLES)

            while (control.isActive()) {
                control.consumePendingSeekMs()?.let { return@withContext DecodeResult.Seek(it) }

                var inputProgress = false
                try {
                    var inIndex = codec.dequeueInputBuffer(0)
                    while (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex) ?: break
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break
                        }
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                        inputProgress = true
                        inIndex = codec.dequeueInputBuffer(0)
                    }
                } catch (e: Exception) {
                    control.logWarn("MediaCodec input error: ${e.message}")
                }

                val timeoutUs = if (inputProgress) 0L else 5_000L
                var outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                var outputProgress = false
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val sampleCount = convertPcmToFloatArray(
                            buffer = outputBuffer,
                            sizeBytes = info.size,
                            pcmEncoding = currentPcmEncoding,
                            target = floatBuffer
                        )
                        if (sampleCount > 0) {
                            sink.write(floatBuffer, sampleCount)
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)
                    outputProgress = true

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return@withContext DecodeResult.Ended
                    }
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    currentPcmEncoding = resolvePcmEncoding(newFormat)
                    currentChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sink.configure(
                        PcmAudioFormat(
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channels = currentChannels,
                            bitDepth = resolveBitDepth(newFormat)
                        )
                    )
                }

                if (!inputProgress && !outputProgress) {
                    Thread.sleep(4)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec decode failed", e)
            return@withContext DecodeResult.Failed(e.message)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }

        DecodeResult.Failed("Playback stopped")
    }

    private fun selectBestAudioTrack(extractor: MediaExtractor): TrackSelection? {
        val candidates = buildList {
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    add(
                        TrackSelection(
                            index = index,
                            format = format,
                            mime = mime,
                            priority = mimePriority(mime)
                        )
                    )
                }
            }
        }
        return candidates.maxByOrNull { it.priority }
    }

    private fun mimePriority(mime: String): Int {
        val lower = mime.lowercase()
        return when {
            lower.contains("flac") -> 110
            lower.contains("opus") -> 105
            lower.contains("vorbis") -> 100
            lower.contains("mpeg") || lower.contains("mp3") -> 95
            lower.contains("mp4a") || lower.contains("aac") || lower.contains("latm") -> 90
            lower.contains("wav") || lower.contains("raw") -> 85
            else -> 10
        }
    }

    private fun resolveBitDepth(format: MediaFormat): Int {
        return when {
            format.containsKey("bits-per-sample") -> format.getInteger("bits-per-sample")
            format.containsKey(MediaFormat.KEY_PCM_ENCODING) -> when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                AudioFormat.ENCODING_PCM_FLOAT -> 32
                AudioFormat.ENCODING_PCM_32BIT -> 32
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                else -> 16
            }
            else -> 16
        }
    }

    private fun resolvePcmEncoding(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun convertPcmToFloatArray(buffer: ByteBuffer, sizeBytes: Int, pcmEncoding: Int, target: FloatArray): Int {
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
        val sampleCount = sizeBytes / bytesPerSample
        if (sampleCount == 0) return 0
        if (target.size < sampleCount) {
            throw IllegalStateException("PCM target buffer too small: ${target.size} < $sampleCount")
        }

        buffer.position(0)
        buffer.limit(sizeBytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatView = buffer.asFloatBuffer()
                var index = 0
                while (index < sampleCount) {
                    target[index] = floatView.get(index)
                    index++
                }
                sampleCount
            }

            AudioFormat.ENCODING_PCM_32BIT -> {
                var index = 0
                while (index < sampleCount) {
                    target[index] = buffer.getInt(index * 4) / 2147483648f
                    index++
                }
                sampleCount
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var index = 0
                while (index < sampleCount) {
                    val base = index * 3
                    val raw =
                        (buffer.get(base).toInt() and 0xFF) or
                            ((buffer.get(base + 1).toInt() and 0xFF) shl 8) or
                            (buffer.get(base + 2).toInt() shl 16)
                    val signed = if (raw and 0x800000 != 0) raw or -0x1000000 else raw
                    target[index] = signed / 8388608f
                    index++
                }
                sampleCount
            }

            else -> {
                val shortBuffer = buffer.asShortBuffer()
                var index = 0
                while (index < sampleCount) {
                    target[index] = shortBuffer.get(index) / 32768f
                    index++
                }
                sampleCount
            }
        }
    }

    private data class TrackSelection(
        val index: Int,
        val format: MediaFormat,
        val mime: String,
        val priority: Int
    )

    companion object {
        private const val TAG = "MediaCodecDecoder"
        private const val PCM_CHUNK_SAMPLES = 32_768
    }
}
