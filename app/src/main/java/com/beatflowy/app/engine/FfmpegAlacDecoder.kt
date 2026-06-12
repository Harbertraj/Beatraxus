package com.beatflowy.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformationJsonParser
import com.arthenica.ffmpegkit.ReturnCode
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.util.Locale

internal class FfmpegAlacDecoder(
    private val context: Context,
    private val driveAccountRepository: DriveAccountRepository,
    private val cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager
) : AudioDecoder {

    override suspend fun canDecode(song: Song): Boolean {
        val ext = song.uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        if (song.format.equals("ALAC", ignoreCase = true)) return true
        if (song.format.equals("WAV", ignoreCase = true)) return true
        if (ext in setOf("alac", "m4a", "mp4", "caf", "wav", "bwf")) return true
        return false
    }

    override suspend fun decode(
        request: PlaybackRequest,
        sink: DecoderSink,
        control: DecoderControl
    ): DecodeResult = withContext(Dispatchers.IO) {
        val headers = resolveHeaders(request.song)
        val format = probeFormat(request.song, headers) ?: return@withContext DecodeResult.Failed("Format probe failed (ALAC/WAV)")
        
        val inputSource = resolveInputSource(request.song)
        val outputFormat = PcmAudioFormat(
            sampleRate = format.sampleRate,
            channels = format.channels.coerceIn(1, 2),
            bitDepth = format.bitDepth,
            codec = format.codecName
        )
        sink.configure(outputFormat)
        control.logDebug(
            "FFmpeg decoder selected: codec=${format.codecName}, sampleRate=${format.sampleRate}, " +
                "channels=${outputFormat.channels}, bitDepth=${format.bitDepth}"
        )

        val pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
        Log.d(TAG, "FFmpeg output pipe registered at: $pipePath")

        val inputPipePath = if (request.song.source != SongSource.LOCAL && cloudCacheManager.getCachedFile(request.song) == null) {
            FFmpegKitConfig.registerNewFFmpegPipe(context)
        } else null
        if (inputPipePath != null) Log.d(TAG, "FFmpeg input pipe registered at: $inputPipePath")

        val effectiveInputSource = inputPipePath ?: inputSource

        val args = buildList {
            add("-y")
            add("-nostdin")
            addAll(listOf("-v", "info")) 

            if (headers.isNotEmpty() && inputPipePath == null) {
                val headerStr = headers.map { "${it.key}: ${it.value}" }.joinToString("\r\n") + "\r\n"
                add("-headers")
                add(headerStr)
            }

            if (effectiveInputSource.startsWith("http") && inputPipePath == null) {
                // Use only options known to be widely supported by FFmpeg for streaming
                addAll(listOf("-reconnect_at_eof", "1"))
                addAll(listOf("-reconnect_delay_max", "2"))
            }

            addAll(listOf("-analyzeduration", "2000000"))
            addAll(listOf("-probesize", "2000000"))

            if (request.startPositionMs > 0) {
                addAll(listOf("-ss", formatSeekSeconds(request.startPositionMs)))
            }
            
            addAll(listOf("-i", effectiveInputSource))
            addAll(
                listOf(
                    "-map", "0:a:0",
                    "-vn",
                    "-sn",
                    "-dn",
                    "-c:a", "pcm_f32le",
                    "-ac", outputFormat.channels.toString(),
                    "-ar", outputFormat.sampleRate.toString(),
                    "-f", "f32le",
                    pipePath
                )
            )
        }.toTypedArray()

        Log.d(TAG, "FFmpeg args: ${args.joinToString(" ")}")

        val completion = CompletableDeferred<Int>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { finished ->
                Log.d(TAG, "FFmpeg session finished with code: ${finished.returnCode}")
                completion.complete(finished.returnCode?.value ?: -1)
            },
            { log -> Log.v(TAG, "ffmpeg: ${log.message}") },
            null
        )

        // Launch input feeder if using pipe
        if (inputPipePath != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dataSource = cloudCacheManager.getDataSource(request.song) { control.isSeekPending() } ?: return@launch
                    val output = FileOutputStream(inputPipePath)
                    val buffer = ByteArray(65536)
                    var pos = 0L
                    while (control.isActive() && !session.state.name.equals("COMPLETED", true)) {
                        val read = dataSource.readAt(pos, buffer, 0, buffer.size)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        pos += read
                    }
                    output.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Input pipe feeder failed", e)
                }
            }
        }

        control.setSeekListener {
            Log.d(TAG, "FFmpeg session cancelled due to seek")
            session.cancel()
        }

        var input: FileInputStream? = null
        try {
            // Increased timeout for slow cloud connections
            input = waitForPipeOpen(pipePath, timeoutMs = 10000) ?: run {
                Log.e(TAG, "FFmpeg pipe failed to open after 10s")
                session.cancel()
                return@withContext DecodeResult.Failed("Unable to open ffmpeg pipe (timeout)")
            }

            Log.d(TAG, "FFmpeg pipe opened successfully, starting read loop")

            val byteBuffer = ByteArray(BYTES_PER_BATCH)
            val floatBuffer = FloatArray(FLOATS_PER_BATCH)
            var remainder = 0
            var totalSamplesWritten = 0L

            while (control.isActive()) {
                val pendingSeek = control.consumePendingSeekMs()
                if (pendingSeek != null) {
                    Log.d(TAG, "Seek requested during decode: $pendingSeek ms")
                    session.cancel()
                    return@withContext DecodeResult.Seek(pendingSeek)
                }

                val bytesToRead = byteBuffer.size - remainder
                val bytesRead = try {
                    // Reverting to blocking read as available() is unreliable for pipes
                    input.read(byteBuffer, remainder, bytesToRead)
                } catch (e: Exception) {
                    Log.w(TAG, "Pipe read failed: ${e.message}")
                    -1
                }
                
                if (bytesRead < 0) {
                    Log.d(TAG, "FFmpeg pipe reached EOF or was closed")
                    break
                }
                
                if (bytesRead == 0) {
                    kotlinx.coroutines.delay(5)
                    continue
                }

                val totalBytes = remainder + bytesRead
                val sampleCount = totalBytes / FLOAT_SIZE_BYTES
                
                if (sampleCount > 0) {
                    unpackFloats(byteBuffer, floatBuffer, sampleCount)
                    sink.write(floatBuffer, sampleCount)
                    totalSamplesWritten += sampleCount
                    
                    remainder = totalBytes % FLOAT_SIZE_BYTES
                    if (remainder > 0) {
                        System.arraycopy(byteBuffer, sampleCount * FLOAT_SIZE_BYTES, byteBuffer, 0, remainder)
                    }
                } else {
                    remainder = totalBytes
                }
            }

            Log.d(TAG, "Decode loop finished. Total samples written: $totalSamplesWritten")

            if (!control.isActive()) {
                session.cancel()
                return@withContext DecodeResult.Failed("Playback stopped")
            }

            val code = withTimeoutOrNull(2000) { completion.await() } ?: -1
            return@withContext if (ReturnCode.isSuccess(ReturnCode(code)) || code == 0) {
                DecodeResult.Ended
            } else {
                if (!control.isActive()) return@withContext DecodeResult.Failed("Playback stopped")
                val logs = session.allLogsAsString
                control.logWarn("ffmpeg session failed: code=$code logs=$logs")
                DecodeResult.Failed("ffmpeg error (code $code)")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Lossless decode failed", e)
            DecodeResult.Failed(e.message)
        } finally {
            try {
                input?.close()
            } catch (_: Exception) {}
            try {
                FFmpegKitConfig.closeFFmpegPipe(pipePath)
            } catch (_: Exception) {}
            if (inputPipePath != null) {
                try {
                    FFmpegKitConfig.closeFFmpegPipe(inputPipePath)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun resolveHeaders(song: Song): Map<String, String> {
        if (cloudCacheManager.getCachedFile(song) != null) return emptyMap()
        
        val headers = mutableMapOf<String, String>()
        
        // Always provide a User-Agent for cloud sources
        if (song.source != SongSource.LOCAL) {
            headers["User-Agent"] = "Beatflowy/2.8"
            headers["Connection"] = "keep-alive"
        }

        if (song.source == SongSource.GDRIVE) {
            if (song.driveAccountEmail != null) {
                val token = driveAccountRepository.getAccessToken(song.driveAccountEmail)
                if (token != null) {
                    headers["Authorization"] = "Bearer $token"
                }
            }
        }
        
        return headers
    }

    private fun resolveInputSource(song: Song): String {
        val cachedFile = cloudCacheManager.getCachedFile(song)
        if (cachedFile != null) return cachedFile.absolutePath

        return if (song.source == SongSource.GDRIVE) {
            "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
        } else if (song.uri.scheme?.startsWith("http") == true) {
            song.uri.toString()
        } else {
            // Force generate a fresh SAF parameter
            FFmpegKitConfig.getSafParameterForRead(context, song.uri)
        }
    }

    private suspend fun probeFormat(song: Song, headers: Map<String, String>): ProbedAlacFormat? = withContext(Dispatchers.IO) {
        // 1. Try MediaExtractor first (local or cached)
        // MediaExtractor is significantly faster than FFprobe as it can use our StreamingCacheDataSource
        val extracted = probeFormatWithExtractor(song, headers)
        if (extracted != null) return@withContext extracted

        // 2. Trust Song metadata if we have it (to avoid slow FFprobe fallback)
        if (song.sampleRateHz > 8000 && song.bitDepth > 0) {
            return@withContext ProbedAlacFormat(
                codecName = song.format,
                sampleRate = song.sampleRateHz,
                channels = 2, // Assumption, but safe for 99% of music
                bitDepth = song.bitDepth
            )
        }

        // 3. Fallback to FFprobe for cloud/complex sources only if absolutely necessary
        val inputSource = resolveInputSource(song)
        probeFormatWithFfprobe(inputSource, headers)
    }

    private fun probeFormatWithFfprobe(path: String, headers: Map<String, String>): ProbedAlacFormat? {
        return try {
            val mediaInfo = if (headers.isEmpty()) {
                val session = FFprobeKit.getMediaInformation(path)
                session.mediaInformation
            } else {
                val headerStr = headers.map { "${it.key}: ${it.value}" }.joinToString("\r\n") + "\r\n"
                val args = arrayOf("-v", "error", "-headers", headerStr, "-show_format", "-show_streams", "-print_format", "json", "-i", path)
                val session = FFprobeKit.executeWithArguments(args)
                val output = session.output
                if (output.isNullOrBlank()) {
                    Log.w(TAG, "FFprobe output is empty for $path")
                    return null
                }
                // Try to find the start of JSON in case of warnings
                val jsonStart = output.indexOf('{')
                if (jsonStart == -1) {
                    Log.w(TAG, "FFprobe output does not contain JSON: $output")
                    return null
                }
                MediaInformationJsonParser.from(output.substring(jsonStart))
            }
            
            if (mediaInfo == null) {
                Log.w(TAG, "FFprobe did not return media information.")
                return null
            }

            val audioStream = mediaInfo.streams
                ?.firstOrNull { it.type.equals("audio", ignoreCase = true) }
                ?: return null

            val sampleRate = audioStream.sampleRate?.toIntOrNull() ?: 44_100
            val channels = audioStream.getNumberProperty("channels")?.toInt() ?: 2
            val sampleFormat = audioStream.sampleFormat.orEmpty().lowercase(Locale.US)
            val bitDepth = when {
                sampleFormat.contains("s32") || sampleFormat.contains("flt") -> 32
                sampleFormat.contains("s24") -> 24
                else -> audioStream.getNumberProperty("bits_per_raw_sample")?.toInt() ?: 16
            }

            ProbedAlacFormat(
                codecName = audioStream.codec.orEmpty(),
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = bitDepth
            )
        } catch (t: Throwable) {
            Log.w(TAG, "FFprobe format probe failed", t)
            null
        }
    }

    private fun probeFormatWithExtractor(song: Song, headers: Map<String, String>): ProbedAlacFormat? {
        val extractor = MediaExtractor()
        return try {
            val cachedFile = cloudCacheManager.getCachedFile(song)
            if (cachedFile != null) {
                extractor.setDataSource(cachedFile.absolutePath)
            } else if (song.source != SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(song) { false }
                if (dataSource != null) {
                    extractor.setDataSource(dataSource)
                } else {
                    val url = if (song.source == SongSource.GDRIVE) {
                        "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
                    } else {
                        song.uri.toString()
                    }
                    extractor.setDataSource(url, headers)
                }
            } else {
                try {
                    extractor.setDataSource(context, song.uri, null)
                } catch (e: Exception) {
                    context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    } ?: return null
                }
            }

            var best: MediaFormat? = null
            var bestPriority = Int.MIN_VALUE
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val priority = when {
                    mime.contains("alac", true) -> 100
                    else -> 10
                }
                if (priority > bestPriority) {
                    bestPriority = priority
                    best = format
                }
            }

            val audioFormat = best ?: return null
            val pcmEncoding = if (audioFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                0
            }
            val bitDepth = when (pcmEncoding) {
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                else -> if (audioFormat.containsKey("bits-per-sample")) {
                    audioFormat.getInteger("bits-per-sample")
                } else {
                    16
                }
            }

            ProbedAlacFormat(
                codecName = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty(),
                sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44_100
                },
                channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                },
                bitDepth = bitDepth
            )
        } catch (t: Throwable) {
            Log.w(TAG, "MediaExtractor format probe failed", t)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun unpackFloats(bytes: ByteArray, target: FloatArray, sampleCount: Int) {
        var index = 0
        while (index < sampleCount) {
            val base = index * FLOAT_SIZE_BYTES
            val bits =
                (bytes[base].toInt() and 0xFF) or
                    ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[base + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[base + 3].toInt() and 0xFF) shl 24)
            target[index] = Float.fromBits(bits)
            index++
        }
    }

    private suspend fun waitForPipeOpen(pipePath: String, timeoutMs: Long = 2000): FileInputStream? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                return@withContext FileInputStream(pipePath)
            } catch (_: Exception) {
                kotlinx.coroutines.delay(25)
            }
        }
        null
    }

    private fun formatSeekSeconds(positionMs: Long): String =
        String.format(Locale.US, "%.3f", positionMs / 1000.0)

    private data class ProbedAlacFormat(
        val codecName: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int
    )

    companion object {
        private const val TAG = "FfmpegAlacDecoder"
        private const val FLOAT_SIZE_BYTES = 4
        private const val FLOATS_PER_BATCH = 4096
        private const val BYTES_PER_BATCH = FLOATS_PER_BATCH * FLOAT_SIZE_BYTES
    }
}
