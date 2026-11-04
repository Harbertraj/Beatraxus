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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

object StemAudioDecoder {
    private const val TAG = "StemAudioDecoder"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val TIMEOUT_US = 5000L

    suspend fun decodeToStereoFloat44100(
        context: Context,
        song: Song,
        isCancelled: () -> Boolean
    ): FloatArray? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // Set Data Source
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

            // Attempt float PCM output
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            } catch (_: Throwable) {}

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rawSamples = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEof = false

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

                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floatBuf = outBuf.asFloatBuffer()
                            val count = floatBuf.remaining()
                            val temp = FloatArray(count)
                            floatBuf.get(temp)
                            for (v in temp) rawSamples.add(v)
                        } else {
                            val shortBuf = outBuf.asShortBuffer()
                            val count = shortBuf.remaining()
                            val temp = ShortArray(count)
                            shortBuf.get(temp)
                            for (s in temp) {
                                rawSamples.add(s / 32768.0f)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEof = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }

            if (isCancelled() || rawSamples.isEmpty()) return@withContext null

            // Remap channels to stereo (2 channels)
            val numSrcFrames = rawSamples.size / srcChannels
            val stereoPcm = FloatArray(numSrcFrames * 2)

            for (f in 0 until numSrcFrames) {
                val srcIdx = f * srcChannels
                val dstIdx = f * 2
                if (srcChannels == 1) {
                    val sample = rawSamples[srcIdx]
                    stereoPcm[dstIdx] = sample
                    stereoPcm[dstIdx + 1] = sample
                } else {
                    stereoPcm[dstIdx] = rawSamples[srcIdx]
                    stereoPcm[dstIdx + 1] = rawSamples[srcIdx + 1]
                }
            }

            // Resample to TARGET_SAMPLE_RATE (44.1 kHz) if needed
            if (srcSampleRate == TARGET_SAMPLE_RATE) {
                stereoPcm
            } else {
                val targetFrames = (numSrcFrames.toLong() * TARGET_SAMPLE_RATE / srcSampleRate).toInt()
                val resampled = FloatArray(targetFrames * 2)

                for (i in 0 until targetFrames) {
                    val srcPos = i.toDouble() * srcSampleRate / TARGET_SAMPLE_RATE
                    val idx0 = floor(srcPos).toInt().coerceIn(0, numSrcFrames - 1)
                    val idx1 = (idx0 + 1).coerceIn(0, numSrcFrames - 1)
                    val frac = (srcPos - idx0).toFloat()

                    val l0 = stereoPcm[idx0 * 2]
                    val l1 = stereoPcm[idx1 * 2]
                    val r0 = stereoPcm[idx0 * 2 + 1]
                    val r1 = stereoPcm[idx1 * 2 + 1]

                    resampled[i * 2] = l0 * (1.0f - frac) + l1 * frac
                    resampled[i * 2 + 1] = r0 * (1.0f - frac) + r1 * frac
                }
                resampled
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode song to stereo float PCM: ${e.message}", e)
            null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }
}
