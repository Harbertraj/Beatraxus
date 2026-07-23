package com.beatraxus.app.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.repository.DriveAccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

internal class MediaCodecAudioDecoder(
    private val context: Context,
    private val driveAccountRepository: DriveAccountRepository,
    private val cloudCacheManager: com.beatraxus.app.drive.CloudCacheManager,
    private val tdLibManager: com.beatraxus.app.telegram.TdLibManager
) : AudioDecoder {
    
    override suspend fun decode(
        request: PlaybackRequest,
        sink: DecoderSink,
        control: DecoderControl
    ): DecodeResult = withContext(Dispatchers.IO) {
        var extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // 1. Setup Data Source
            val cachedFile = cloudCacheManager.getCachedFile(request.song)
            if (cachedFile != null) {
                extractor.setDataSource(cachedFile.absolutePath)
            } else if (request.song.source != SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(request.song, tdLibManager) { control.isSeekPending() }
                if (dataSource != null) {
                    try {
                        extractor.setDataSource(dataSource)
                    } catch (e: Exception) {
                        control.logWarn("DataSource failed, falling back to direct URL: ${e.message}")
                        val (source, headers) = resolveSource(request.song)
                        if (source.isNotBlank()) {
                            extractor.setDataSource(source, headers)
                        } else {
                            return@withContext DecodeResult.Failed("Fallback source resolve failed (blank)")
                        }
                    }
                } else {
                    val (source, headers) = resolveSource(request.song)
                    if (source.isNotBlank()) {
                        extractor.setDataSource(source, headers)
                    } else {
                        return@withContext DecodeResult.Failed("Source resolve failed (blank)")
                    }
                }
            } else {
                // For local files, try multiple ways to set the data source.
                // FileDescriptor is generally more robust on modern Android versions.
                try {
                    context.contentResolver.openFileDescriptor(request.song.uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set data source via PFD for ${request.song.uri}: ${e.message}")
                    try {
                        // Fallback to standard Context/Uri method
                        extractor.setDataSource(context, request.song.uri, null)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to set data source via Context/Uri for ${request.song.uri}", e2)
                        throw e2
                    }
                }
            }

            // 2. Select Track with retry
            var track = selectBestAudioTrack(extractor)
            if (track == null && request.song.source != SongSource.LOCAL && cachedFile == null) {
                control.logWarn("Initial extraction failed, retrying with fresh extractor and direct URL...")
                extractor.release()
                extractor = MediaExtractor()
                val (source, headers) = resolveSource(request.song)
                if (source.isNotBlank()) {
                    extractor.setDataSource(source, headers)
                    track = selectBestAudioTrack(extractor)
                } else {
                    control.logWarn("Fallback source resolve failed (blank)")
                }
            }

            if (track == null) {
                return@withContext DecodeResult.Failed("No audio track found for ${request.song.title}")
            }

            // 3. Configure Codec
            extractor.selectTrack(track.index)
            
            // SECURITY: Never use MediaCodec for ALAC. It's notoriously unstable across Android vendors.
            if (track.mime.contains("alac", ignoreCase = true)) {
                return@withContext DecodeResult.Failed("ALAC not supported via MediaCodec")
            }

            codec = try {
                MediaCodec.createDecoderByType(track.mime)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create decoder for ${track.mime}", e)
                return@withContext DecodeResult.Failed("Decoder creation failed: ${e.message}")
            }

            codec.configure(track.format, null, null, 0)
            codec.start()

            // Explicitly seek to requested position (even if 0) to ensure extractor is at the right start
            extractor.seekTo(request.startPositionMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var currentPcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var currentChannels = 2
            var floatBuffer = FloatArray(PCM_CHUNK_SAMPLES)
            var configured = false

            // 4. Decode Loop
            while (control.isActive()) {
                val pendingSeek = control.consumePendingSeekMs()
                if (pendingSeek != null) {
                    try {
                        extractor.seekTo(pendingSeek * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                    } catch (e: Exception) {
                        control.logWarn("Seek failed: ${e.message}")
                    } finally {
                        control.notifySeek(pendingSeek)
                    }
                    continue
                }

                var inputProgress = false
                try {
                    var inIndex = codec.dequeueInputBuffer(0)
                    while (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex) ?: break
                        val sampleSize = try { extractor.readSampleData(inputBuffer, 0) } catch (e: Exception) { -1 }
                        if (sampleSize < 0) {
                            // If a seek is pending, the -1 is likely a forced interruption from the DataSource.
                            // We break the input loop without queuing EOS so the outer loop can handle the seek.
                            if (control.isSeekPending()) {
                                Log.d(TAG, "Extractor interrupted by pending seek, skipping EOS")
                                break 
                            }
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break
                        }
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                        inputProgress = true
                        inIndex = codec.dequeueInputBuffer(0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaCodec input error for ${request.song.title}", e)
                    break
                }

                val timeoutUs = if (inputProgress) 0L else 5_000L
                var outIndex = codec.dequeueOutputBuffer(info, timeoutUs)

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (outIndex >= 0 && !configured)) {
                    val newFormat = codec.outputFormat
                    currentPcmEncoding = resolvePcmEncoding(newFormat)
                    currentChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sink.configure(
                        PcmAudioFormat(
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channels = currentChannels,
                            bitDepth = resolveBitDepth(newFormat),
                            codec = track.mime
                        )
                    )
                    configured = true
                }

                var outputProgress = false
                while (outIndex >= 0 && control.isActive()) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val bytesPerSample = when (currentPcmEncoding) {
                            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                            else -> 2
                        }
                        val sampleCount = info.size / bytesPerSample
                        if (sampleCount > floatBuffer.size) {
                            floatBuffer = FloatArray(sampleCount)
                        }

                        val actualCount = convertPcmToFloatArray(
                            buffer = outputBuffer,
                            sizeBytes = info.size,
                            pcmEncoding = currentPcmEncoding,
                            target = floatBuffer
                        )
                        if (actualCount > 0) {
                            sink.write(floatBuffer, actualCount)
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)
                    outputProgress = true

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return@withContext DecodeResult.Ended
                    }
                    outIndex = codec.dequeueOutputBuffer(info, 0)

                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        currentPcmEncoding = resolvePcmEncoding(newFormat)
                        currentChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sink.configure(
                            PcmAudioFormat(
                                sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                channels = currentChannels,
                                bitDepth = resolveBitDepth(newFormat),
                                codec = track.mime
                            )
                        )
                        configured = true
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }

                if (!inputProgress && !outputProgress) {
                    Thread.yield()
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "MediaCodec decode failed for ${request.song.title}", e)
            return@withContext DecodeResult.Failed(e.message ?: e.toString())
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        if (control.isActive()) DecodeResult.Failed("Decoder loop exited prematurely")
        else DecodeResult.Failed("Playback stopped")
    }

    private suspend fun resolveSource(song: Song): Pair<String, Map<String, String>> {
        val cachedFile = cloudCacheManager.getCachedFile(song)
        if (cachedFile != null) {
            return cachedFile.absolutePath to emptyMap()
        }

        if (song.source == SongSource.TELEGRAM) {
            val path = cloudCacheManager.getTelegramFilePath(song, tdLibManager)
            return (path ?: "") to emptyMap()
        }

        return if (song.source == SongSource.GDRIVE) {
            val url = "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
            val headers = mutableMapOf<String, String>()
            if (song.driveAccountEmail != null) {
                val token = driveAccountRepository.getAccessToken(song.driveAccountEmail)
                if (token != null) {
                    headers["Authorization"] = "Bearer $token"
                }
            }
            url to headers
        } else if (song.uri.scheme?.startsWith("http") == true) {
            song.uri.toString() to emptyMap()
        } else {
            "" to emptyMap()
        }
    }

    private fun selectBestAudioTrack(extractor: MediaExtractor): TrackSelection? {
        val candidates = mutableListOf<TrackSelection>()
        for (index in 0 until extractor.trackCount) {
            try {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    candidates.add(
                        TrackSelection(
                            index = index,
                            format = format,
                            mime = mime,
                            priority = mimePriority(mime)
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore tracks that fail to probe
            }
        }
        return candidates.maxByOrNull { it.priority }
    }

    private fun mimePriority(mime: String): Int {
        val lower = mime.lowercase()
        return when {
            lower.contains("flac") -> 110
            lower.contains("alac") -> 108
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
        if (sampleCount <= 0) return 0
        val finalSampleCount = if (sampleCount > target.size) target.size else sampleCount

        // Framework sets position and limit to valid data range
        val startPos = buffer.position()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatView = buffer.asFloatBuffer()
                for (i in 0 until finalSampleCount) {
                    target[i] = floatView.get(i)
                }
                finalSampleCount
            }

            AudioFormat.ENCODING_PCM_32BIT -> {
                for (i in 0 until finalSampleCount) {
                    target[i] = buffer.getInt(startPos + i * 4) / 2147483648f
                }
                finalSampleCount
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                for (i in 0 until finalSampleCount) {
                    val base = startPos + i * 3
                    val raw =
                        (buffer.get(base).toInt() and 0xFF) or
                            ((buffer.get(base + 1).toInt() and 0xFF) shl 8) or
                            (buffer.get(base + 2).toInt() shl 16)
                    val signed = if (raw and 0x800000 != 0) raw or -0x1000000 else raw
                    target[i] = signed / 8388608f
                }
                finalSampleCount
            }

            else -> {
                val shortBuffer = buffer.asShortBuffer()
                for (i in 0 until finalSampleCount) {
                    target[i] = shortBuffer.get(i) / 32768f
                }
                finalSampleCount
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
        private const val PCM_CHUNK_SAMPLES = 131_072 // Increased for multi-channel/high-res buffers
    }
}
