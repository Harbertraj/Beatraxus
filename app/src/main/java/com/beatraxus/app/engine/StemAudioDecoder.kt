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

            val srcSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_SAMPLE_RATE
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            } catch (_: Throwable) {}

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEof = false
            var totalSrcFloats = 0L

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

                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.nativeOrder())

                            val numFloats = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                bufferInfo.size / 4
                            } else {
                                bufferInfo.size / 2
                            }

                            val byteBuf = ByteBuffer.allocateDirect(numFloats * 4).order(ByteOrder.nativeOrder())
                            val floatBuf = byteBuf.asFloatBuffer()

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                floatBuf.put(outBuf.asFloatBuffer())
                            } else {
                                val shortBuf = outBuf.asShortBuffer()
                                while (shortBuf.hasRemaining()) {
                                    floatBuf.put(shortBuf.get() / 32768.0f)
                                }
                            }
                            byteBuf.rewind()
                            channel.write(byteBuf)
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

            val numSrcFrames = (totalSrcFloats / srcChannels).toInt()

            // Stream remapped and resampled PCM into outputFile
            RandomAccessFile(tempRawFile, "r").use { rawRaf ->
                val rawChannel = rawRaf.channel
                RandomAccessFile(outputFile, "rw").use { outRaf ->
                    val outChannel = outRaf.channel

                    if (srcChannels == 2 && srcSampleRate == TARGET_SAMPLE_RATE) {
                        // Direct copy
                        rawChannel.transferTo(0, rawChannel.size(), outChannel)
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
                            rawChannel.read(inBuffer)
                            inBuffer.rewind()

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
                            outChannel.write(outBuffer)
                            framesRead += framesToRead
                        }
                        return@withContext targetFrames.toLong()
                    } else {
                        // Resample loop
                        var targetFrameIdx = 0
                        val srcFrameBuf = FloatArray(2 * srcChannels)
                        while (targetFrameIdx < targetFrames) {
                            if (isCancelled()) return@withContext null
                            val framesToProcess = minOf(chunkSize, targetFrames - targetFrameIdx)
                            outBuffer.clear()
                            val outFloats = outBuffer.asFloatBuffer()

                            for (i in 0 until framesToProcess) {
                                val currentTargetFrame = targetFrameIdx + i
                                val srcPos = currentTargetFrame.toDouble() * srcSampleRate / TARGET_SAMPLE_RATE
                                val idx0 = floor(srcPos).toInt().coerceIn(0, numSrcFrames - 1)
                                val idx1 = (idx0 + 1).coerceIn(0, numSrcFrames - 1)
                                val frac = (srcPos - idx0).toFloat()

                                // Read samples for idx0 and idx1
                                rawChannel.position(idx0.toLong() * srcChannels * 4)
                                inBuffer.clear()
                                inBuffer.limit(srcChannels * 4)
                                rawChannel.read(inBuffer)
                                inBuffer.rewind()
                                val f0Buf = inBuffer.asFloatBuffer()

                                rawChannel.position(idx1.toLong() * srcChannels * 4)
                                inBuffer.clear()
                                inBuffer.limit(srcChannels * 4)
                                rawChannel.read(inBuffer)
                                inBuffer.rewind()
                                val f1Buf = inBuffer.asFloatBuffer()

                                val l0 = f0Buf.get(0)
                                val r0 = if (srcChannels >= 2) f0Buf.get(1) else l0
                                val l1 = f1Buf.get(0)
                                val r1 = if (srcChannels >= 2) f1Buf.get(1) else l1

                                outFloats.put(l0 * (1.0f - frac) + l1 * frac)
                                outFloats.put(r0 * (1.0f - frac) + r1 * frac)
                            }

                            outBuffer.rewind()
                            outBuffer.limit(framesToProcess * 2 * 4)
                            outChannel.write(outBuffer)
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
