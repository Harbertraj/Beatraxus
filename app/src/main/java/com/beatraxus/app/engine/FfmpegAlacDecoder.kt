package com.beatraxus.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformationJsonParser
import com.arthenica.ffmpegkit.ReturnCode
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.repository.DriveAccountRepository
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.util.Locale

internal class FfmpegAlacDecoder(
    private val context: Context,
    private val driveAccountRepository: DriveAccountRepository,
    private val cloudCacheManager: com.beatraxus.app.drive.CloudCacheManager,
    private val tdLibManager: com.beatraxus.app.telegram.TdLibManager
) : AudioDecoder {

    override suspend fun canDecode(song: Song): Boolean {
        val ext = song.uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        if (song.format.equals("ALAC", ignoreCase = true) || song.format.equals("FLAC", ignoreCase = true) || song.format.equals("AC3", true) || song.format.equals("EAC3", true) || song.format.equals("DTS", true) || song.format.equals("DSD", true)) return true
        if (song.format.equals("WAV", ignoreCase = true)) return true
        if (ext in setOf("alac", "flac", "m4a", "mp4", "caf", "wav", "bwf", "ac3", "eac3", "ec3", "dts", "dsf", "dff")) return true
        return false
    }

    override suspend fun decode(
        request: PlaybackRequest,
        sink: DecoderSink,
        control: DecoderControl
    ): DecodeResult = withContext(Dispatchers.IO) {
        val headers = resolveHeaders(request.song)
        val format = probeFormat(request.song, headers) ?: return@withContext DecodeResult.Failed("Format probe failed (ALAC/WAV)")
        val ext = request.song.uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        
        val outputFormat = PcmAudioFormat(
            sampleRate = format.sampleRate,
            channels = format.channels.coerceIn(1, 8),
            bitDepth = format.bitDepth,
            codec = format.codecName
        )
        sink.configure(outputFormat)
        control.logDebug(
            "FFmpeg decoder selected: codec=${format.codecName}, sampleRate=${format.sampleRate}, " +
                "channels=${outputFormat.channels}, bitDepth=${format.bitDepth}"
        )

        val outputPipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
        Log.d(TAG, "FFmpeg output pipe registered at: $outputPipePath")

        var inputPipePath: String? = null
        var pumperJob: Job? = null

        if (request.song.source == SongSource.TELEGRAM) {
            inputPipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
            Log.d(TAG, "FFmpeg input pipe registered at: $inputPipePath")
            
            val dataSource = cloudCacheManager.getDataSource(request.song, tdLibManager) { control.isSeekPending() }
            if (dataSource == null) return@withContext DecodeResult.Failed("Unable to get data source for Telegram song")

            pumperJob = launch(Dispatchers.IO) {
                var inputPipeStream: FileOutputStream? = null
                try {
                    inputPipeStream = FileOutputStream(inputPipePath)
                    val buffer = ByteArray(64 * 1024)
                    var pos = 0L 
                    
                    while (isActive && control.isActive()) {
                        val read = dataSource.readAt(pos, buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read > 0) {
                            inputPipeStream.write(buffer, 0, read)
                            pos += read
                        } else {
                            delay(50)
                        }
                    }
                    inputPipeStream.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Telegram pumper failed", e)
                } finally {
                    try { inputPipeStream?.close() } catch (_: Exception) {}
                    try { dataSource.close() } catch (_: Exception) {}
                }
            }
        }

        val inputSource = if (inputPipePath != null) {
            inputPipePath
        } else {
            val resolved = resolveInputSource(request.song)
            if (resolved.isBlank()) return@withContext DecodeResult.Failed("Unable to resolve input source for ${request.song.title}")
            resolved
        }

        // Determine demuxer to help FFmpeg with pipes or extension-less cache files
        val demuxerHint = when {
            format.codecName.contains("ac3", ignoreCase = true) ||
            request.song.format.equals("AC3", ignoreCase = true) ||
            request.song.format.equals("EAC3", ignoreCase = true) -> "ac3"

            request.song.format.equals("DTS", ignoreCase = true) -> "dts"

            ext == "dsf" || request.song.format.equals("DSD", ignoreCase = true) -> "dsf"
            ext == "dff" -> "dsdiff"

            format.codecName.contains("alac", ignoreCase = true) || 
            request.song.format.equals("ALAC", ignoreCase = true) ||
            request.song.format.equals("M4A", ignoreCase = true) -> "mov"
            
            format.codecName.contains("wav", ignoreCase = true) || 
            format.codecName.contains("pcm", ignoreCase = true) ||
            request.song.format.equals("WAV", ignoreCase = true) -> "wav"
            
            else -> null
        }

        val args = buildList {
            add("-y")
            add("-nostdin")
            addAll(listOf("-v", "info")) 

            if (headers.isNotEmpty() && inputSource.startsWith("http")) {
                val headerStr = headers.map { "${it.key}: ${it.value}" }.joinToString("\r\n") + "\r\n"
                add("-headers")
                add(headerStr)
            }

            if (inputSource.startsWith("http")) {
                // Use only options known to be widely supported by FFmpeg for streaming
                addAll(listOf("-reconnect_at_eof", "1"))
                addAll(listOf("-reconnect_delay_max", "2"))
            }

            if (demuxerHint != null) {
                addAll(listOf("-f", demuxerHint))
            }

            addAll(listOf("-analyzeduration", "2000000"))
            addAll(listOf("-probesize", "2000000"))

            if (request.startPositionMs > 0) {
                addAll(listOf("-ss", formatSeekSeconds(request.startPositionMs)))
            }
            
            addAll(listOf("-i", inputSource))
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
                    outputPipePath
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

        control.setSeekListener {
            Log.d(TAG, "FFmpeg session cancelled due to seek")
            session.cancel()
            pumperJob?.cancel()
        }

        var outputPipeStream: FileInputStream? = null
        try {
            // Increased timeout for slow cloud connections
            outputPipeStream = waitForPipeOpen(outputPipePath, timeoutMs = 15000) ?: run {
                Log.e(TAG, "FFmpeg output pipe failed to open after 15s")
                session.cancel()
                pumperJob?.cancel()
                return@withContext DecodeResult.Failed("Unable to open ffmpeg output pipe (timeout)")
            }

            Log.d(TAG, "FFmpeg output pipe opened successfully, starting read loop")

            val byteBuffer = ByteArray(BYTES_PER_BATCH)
            val floatBuffer = FloatArray(FLOATS_PER_BATCH)
            var remainder = 0
            var totalSamplesWritten = 0L

            while (control.isActive()) {
                val pendingSeek = control.consumePendingSeekMs()
                if (pendingSeek != null) {
                    Log.d(TAG, "Seek requested during decode: $pendingSeek ms")
                    session.cancel()
                    pumperJob?.cancel()
                    return@withContext DecodeResult.Seek(pendingSeek)
                }

                val bytesToRead = byteBuffer.size - remainder
                val bytesRead = try {
                    // Reverting to blocking read as available() is unreliable for pipes
                    outputPipeStream.read(byteBuffer, remainder, bytesToRead)
                } catch (e: Exception) {
                    Log.e(TAG, "Pipe read failed for ${request.song.title}", e)
                    -1
                }
                
                if (bytesRead < 0) {
                    Log.d(TAG, "FFmpeg output pipe reached EOF or was closed")
                    break
                }
                
                if (bytesRead == 0) {
                    delay(5)
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
                pumperJob?.cancel()
                return@withContext DecodeResult.Failed("Playback stopped")
            }

            val code = withTimeoutOrNull(2000) { completion.await() } ?: -1
            return@withContext if (ReturnCode.isSuccess(ReturnCode(code)) || code == 0) {
                DecodeResult.Ended
            } else {
                if (!control.isActive()) return@withContext DecodeResult.Failed("Playback stopped")
                val logs = session.allLogsAsString
                
                // Detailed logging for exit code 255 and broken pipes
                val reason = when (code) {
                    255 -> "FFmpeg error (code 255): Likely file access error or TDLib timeout. Logs: ${logs.take(200)}..."
                    else -> "FFmpeg error (code $code). Logs: ${logs.take(200)}..."
                }
                
                control.logWarn(reason)
                DecodeResult.Failed(reason)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Lossless decode failed for ${request.song.title}", e)
            DecodeResult.Failed(e.message ?: e.toString())
        } finally {
            try {
                outputPipeStream?.close()
            } catch (_: Exception) {}
            try {
                FFmpegKitConfig.closeFFmpegPipe(outputPipePath)
                inputPipePath?.let { FFmpegKitConfig.closeFFmpegPipe(it) }
            } catch (_: Exception) {}
            pumperJob?.cancel()
        }
    }

    private suspend fun resolveHeaders(song: Song): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        // Always provide a User-Agent for cloud sources
        if (song.source != SongSource.LOCAL) {
            headers["User-Agent"] = "Beatraxus/2.8"
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

    private suspend fun resolveInputSource(song: Song): String {
        return if (song.source == SongSource.GDRIVE) {
            "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
        } else if (song.source == SongSource.TELEGRAM) {
            cloudCacheManager.getTelegramFilePath(song, tdLibManager) ?: ""
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
        // SKIP for Telegram to avoid slow/blocking network reads during probe.
        if (song.source != SongSource.TELEGRAM) {
            val extracted = probeFormatWithExtractor(song, headers)
            if (extracted != null) return@withContext extracted
        }

        // 2. Trust Song metadata if we have it (to avoid slow FFprobe fallback)
        if (song.sampleRateHz > 8000 && (song.bitDepth > 0 || song.source == SongSource.TELEGRAM)) {
            return@withContext ProbedAlacFormat(
                codecName = if (song.format.isNotBlank()) song.format else "ALAC",
                sampleRate = song.sampleRateHz,
                channels = 2, // Assumption, but safe for 99% of music
                bitDepth = if (song.bitDepth > 0) song.bitDepth else 16
            )
        }

        // 3. Fallback to FFprobe for cloud/complex sources only if absolutely necessary
        val inputSource = resolveInputSource(song)
        if (inputSource.isBlank()) {
            Log.w(TAG, "Cannot probe format: resolveInputSource returned blank for ${song.title}")
            return@withContext null
        }
        probeFormatWithFfprobe(inputSource, headers)
    }

    private fun probeFormatWithFfprobe(path: String, headers: Map<String, String>): ProbedAlacFormat? {
        if (path.isBlank()) return null
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

    private suspend fun probeFormatWithExtractor(song: Song, headers: Map<String, String>): ProbedAlacFormat? {
        val extractor = MediaExtractor()
        return try {
            if (song.source != SongSource.LOCAL) {
                val dataSource = cloudCacheManager.getDataSource(song, tdLibManager) { false }
                if (dataSource != null) {
                    extractor.setDataSource(dataSource)
                } else {
                    val inputSource = resolveInputSource(song)
                    if (inputSource.isNotBlank()) {
                        extractor.setDataSource(inputSource, headers)
                    } else {
                        return null
                    }
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
