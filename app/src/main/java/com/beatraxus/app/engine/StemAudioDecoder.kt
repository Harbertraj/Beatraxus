package com.beatraxus.app.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

object StemAudioDecoder {
    private const val TAG = "StemAudioDecoder"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val TIMEOUT_US = 5000L

    suspend fun decodeToStereoFloat44100File(
        context: Context,
        song: Song,
        outputFile: File,
        isCancelled: () -> Boolean
    ): Long? = withContext(Dispatchers.IO) {
        if (outputFile.exists()) outputFile.delete()
        val tempRawFile = File(outputFile.parentFile, "${outputFile.name}.raw")
        if (tempRawFile.exists()) tempRawFile.delete()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            if (song.source == SongSource.LOCAL) {
                try {
                    context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                } catch (e: Exception) {
                    extractor.setDataSource(context, song.uri, null)
                }
            } else {
                extractor.setDataSource(song.uri.toString())
            }

            var trackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    mime = m
                    break
                }
            }

            if (trackIndex < 0 || format == null || mime == null) {
                Log.e(TAG, "No audio track found for song ${song.title}")
                return@withContext null
            }

            extractor.selectTrack(trackIndex)

            val headerRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_SAMPLE_RATE
            val headerChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            // Do NOT force KEY_PCM_ENCODING: on the pass-through raw decoder (FLAC / WAV arrive as audio/raw) that key
            // describes the INPUT bytes, so forcing float made 16-bit samples be read as garbage floats (NaN / huge),
            // which silenced the output and crashed the app. Whatever the codec outputs is converted below.

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEof = false
            var totalSrcFloats = 0L
            // Real output layout; the track header can differ (and may be missing) so the output format wins.
            var outChannels = headerChannels
            var outRate = headerRate

            FileOutputStream(tempRawFile).use { fos ->
                val channel = fos.channel
                while (!isEof && !isCancelled()) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEof = true
                            } else {
                                val timeUs = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    while (outputIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outputIndex)
                        val outFormat = codec.getOutputFormat(outputIndex)
                        val pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)

                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.nativeOrder())

                            val bytesPer = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val numFloats = bufferInfo.size / bytesPer

                            val byteBuf = ByteBuffer.allocateDirect(numFloats * 4).order(ByteOrder.nativeOrder())
                            val floatBuf = byteBuf.asFloatBuffer()

                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = outBuf.asFloatBuffer()
                                    for (k in 0 until numFloats) floatBuf.put(fb.get())
                                }
                                AudioFormat.ENCODING_PCM_32BIT -> {
                                    val ib = outBuf.asIntBuffer()
                                    for (k in 0 until numFloats) floatBuf.put(ib.get() / 2147483648.0f)
                                }
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                    var p = outBuf.position()
                                    for (k in 0 until numFloats) {
                                        val v = (outBuf.get(p).toInt() and 0xFF) or
                                            ((outBuf.get(p + 1).toInt() and 0xFF) shl 8) or
                                            (outBuf.get(p + 2).toInt() shl 16) // sign from the top byte
                                        floatBuf.put(v / 8388608.0f)
                                        p += 3
                                    }
                                }
                                AudioFormat.ENCODING_PCM_8BIT -> {
                                    for (k in 0 until numFloats) floatBuf.put(((outBuf.get().toInt() and 0xFF) - 128) / 128.0f)
                                }
                                else -> {
                                    val shortBuf = outBuf.asShortBuffer()
                                    for (k in 0 until numFloats) floatBuf.put(shortBuf.get() / 32768.0f)
                                }
                            }
                            byteBuf.rewind()
                            byteBuf.limit(numFloats * 4)
                            while (byteBuf.hasRemaining()) channel.write(byteBuf)
                            totalSrcFloats += numFloats
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEof = true
                            break
                        }
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                }
            }

            if (isCancelled() || totalSrcFloats == 0L) {
                if (tempRawFile.exists()) tempRawFile.delete()
                return@withContext null
            }

            val srcChannels = outChannels
            val srcSampleRate = outRate
            val numSrcFrames = (totalSrcFloats / srcChannels).toInt()

            // Stream remapped and resampled PCM into outputFile
            RandomAccessFile(tempRawFile, "r").use { rawRaf ->
                val rawChannel = rawRaf.channel
                RandomAccessFile(outputFile, "rw").use { outRaf ->
                    val outChannel = outRaf.channel

                    if (srcChannels == 2 && srcSampleRate == TARGET_SAMPLE_RATE) {
                        // Direct copy
                        var pos = 0L
                        val total = rawChannel.size()
                        while (pos < total) {
                            val n = rawChannel.transferTo(pos, total - pos, outChannel)
                            if (n <= 0) break
                            pos += n
                        }
                        return@withContext numSrcFrames.toLong()
                    }

                    // Process frame by frame or in chunks
                    val targetFrames = if (srcSampleRate == TARGET_SAMPLE_RATE) numSrcFrames else (numSrcFrames.toLong() * TARGET_SAMPLE_RATE / srcSampleRate).toInt()
                    val chunkSize = 4096
                    val inBuffer = ByteBuffer.allocateDirect(chunkSize * srcChannels * 4).order(ByteOrder.nativeOrder())
                    val outBuffer = ByteBuffer.allocateDirect(chunkSize * 2 * 4).order(ByteOrder.nativeOrder())

                    if (srcSampleRate == TARGET_SAMPLE_RATE) {
                        var framesRead = 0
                        while (framesRead < numSrcFrames) {
                            if (isCancelled()) return@withContext null
                            val framesToRead = minOf(chunkSize, numSrcFrames - framesRead)
                            inBuffer.clear()
                            inBuffer.limit(framesToRead * srcChannels * 4)
                            while (inBuffer.hasRemaining()) if (rawChannel.read(inBuffer) < 0) break
                            inBuffer.flip()

                            val srcFloats = inBuffer.asFloatBuffer()
                            outBuffer.clear()
                            val outFloats = outBuffer.asFloatBuffer()

                            for (f in 0 until framesToRead) {
                                if (srcChannels == 1) {
                                    val s = srcFloats.get()
                                    outFloats.put(s)
                                    outFloats.put(s)
                                } else {
                                    outFloats.put(srcFloats.get())
                                    outFloats.put(srcFloats.get())
                                    for (c in 2 until srcChannels) srcFloats.get()
                                }
                            }
                            outBuffer.rewind()
                            outBuffer.limit(framesToRead * 2 * 4)
                            while (outBuffer.hasRemaining()) outChannel.write(outBuffer)
                            framesRead += framesToRead
                        }
                        return@withContext targetFrames.toLong()
                    } else {
                        // Linear resample, one block read per output block (it used to seek twice per output frame).
                        var targetFrameIdx = 0
                        val ratio = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE
                        var win = FloatArray(0)
                        while (targetFrameIdx < targetFrames) {
                            if (isCancelled()) return@withContext null
                            val framesToProcess = minOf(chunkSize, targetFrames - targetFrameIdx)
                            outBuffer.clear()
                            val outFloats = outBuffer.asFloatBuffer()

                            val first = floor(targetFrameIdx * ratio).toInt().coerceIn(0, numSrcFrames - 1)
                            val last = (floor((targetFrameIdx + framesToProcess) * ratio).toInt() + 1).coerceIn(first, numSrcFrames - 1)
                            val winFrames = last - first + 1
                            if (win.size < winFrames * srcChannels) win = FloatArray(winFrames * srcChannels)
                            val winBytes = ByteBuffer.allocateDirect(winFrames * srcChannels * 4).order(ByteOrder.nativeOrder())
                            rawChannel.position(first.toLong() * srcChannels * 4)
                            while (winBytes.hasRemaining()) if (rawChannel.read(winBytes) < 0) break
                            winBytes.flip()
                            val got = winBytes.remaining() / 4
                            winBytes.asFloatBuffer().get(win, 0, got)
                            if (got < winFrames * srcChannels) java.util.Arrays.fill(win, got, winFrames * srcChannels, 0f)

                            for (i in 0 until framesToProcess) {
                                val srcPos = (targetFrameIdx + i) * ratio
                                val idx0 = floor(srcPos).toInt().coerceIn(first, last)
                                val idx1 = (idx0 + 1).coerceIn(first, last)
                                val frac = (srcPos - idx0).toFloat().coerceIn(0f, 1f)
                                val o0 = (idx0 - first) * srcChannels
                                val o1 = (idx1 - first) * srcChannels
                                val l0 = win[o0]
                                val r0 = if (srcChannels >= 2) win[o0 + 1] else l0
                                val l1 = win[o1]
                                val r1 = if (srcChannels >= 2) win[o1 + 1] else l1
                                outFloats.put(l0 * (1.0f - frac) + l1 * frac)
                                outFloats.put(r0 * (1.0f - frac) + r1 * frac)
                            }

                            outBuffer.rewind()
                            outBuffer.limit(framesToProcess * 2 * 4)
                            while (outBuffer.hasRemaining()) outChannel.write(outBuffer)
                            targetFrameIdx += framesToProcess
                        }
                        return@withContext targetFrames.toLong()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode song to stereo float PCM file: ${e.message}", e)
            if (outputFile.exists()) outputFile.delete()
            null
        } finally {
            if (tempRawFile.exists()) tempRawFile.delete()
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    suspend fun decodeToStereoFloat44100(
        context: Context,
        song: Song,
        isCancelled: () -> Boolean
    ): FloatArray? = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_decode_${song.id}_mem.pcm")
        try {
            val totalFrames = decodeToStereoFloat44100File(context, song, tempFile, isCancelled) ?: return@withContext null
            val numFloats = (totalFrames * 2).toInt()
            val result = FloatArray(numFloats)
            RandomAccessFile(tempFile, "r").use { raf ->
                val channel = raf.channel
                val byteBuf = ByteBuffer.allocateDirect(numFloats * 4).order(ByteOrder.nativeOrder())
                channel.read(byteBuf)
                byteBuf.rewind()
                byteBuf.asFloatBuffer().get(result)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode to FloatArray: ${e.message}", e)
            null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
