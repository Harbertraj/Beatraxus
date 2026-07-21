package com.beatraxus.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Decodes a whole track to a downsampled min/max waveform envelope and a coarse
 * spectrogram (per-frame 128-bucket magnitude arrays), for the Music Detail Inspector's
 * waveform + spectrogram cards (Phase 6b, sections 3-4).
 *
 * There's no existing native JNI function for this (the analyzer only returns one
 * averaged spectrum for its whole analysis window), so this is a lightweight Kotlin-side
 * decode via MediaExtractor/MediaCodec — the same safe, fd-based decode approach
 * NativeDsp.extractFeatures uses, just kept entirely on the Kotlin side to avoid adding a
 * new native ABI surface for a screen that only needs this once per song (and is cached).
 */
object WaveformExtractor {
    private const val TAG = "WaveformExtractor"
    private const val ENVELOPE_POINTS = 500      // min/max peak pairs across the whole track
    private const val SPECTROGRAM_FFT_SIZE = 2048
    private const val SPECTROGRAM_BUCKETS = 128
    private const val SPECTROGRAM_MAX_FRAMES = 200 // keep every Nth FFT frame up to this cap

    data class WaveformData(
        val minPeaks: FloatArray,
        val maxPeaks: FloatArray,
        val spectrogramFrames: Array<FloatArray>, // each: SPECTROGRAM_BUCKETS magnitudes (log-scaled, 0-1)
        val durationMs: Long
    )

    private fun cacheFile(context: Context, songId: String): File {
        val dir = File(context.filesDir, "waveform_cache").apply { mkdirs() }
        return File(dir, "$songId.json")
    }

    suspend fun getOrExtract(context: Context, songId: String, uri: Uri): WaveformData? =
        withContext(Dispatchers.IO) {
            val cache = cacheFile(context, songId)
            if (cache.exists()) {
                readCache(cache)?.let { return@withContext it }
            }
            val extracted = try {
                extract(context, uri)
            } catch (t: Throwable) {
                Log.e(TAG, "Waveform/spectrogram extraction crash prevented for $songId", t)
                null
            }
            if (extracted != null) {
                try { writeCache(cache, extracted) } catch (e: Exception) {
                    Log.e(TAG, "Failed to cache waveform for $songId", e)
                }
            }
            extracted
        }

    private fun readCache(file: File): WaveformData? = try {
        val json = JSONObject(file.readText())
        val minArr = json.getJSONArray("min")
        val maxArr = json.getJSONArray("max")
        val min = FloatArray(minArr.length()) { minArr.getDouble(it).toFloat() }
        val max = FloatArray(maxArr.length()) { maxArr.getDouble(it).toFloat() }
        val specArr = json.getJSONArray("spec")
        val spec = Array(specArr.length()) { i ->
            val row = specArr.getJSONArray(i)
            FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
        }
        WaveformData(min, max, spec, json.optLong("durationMs", 0L))
    } catch (e: Exception) {
        null
    }

    private fun writeCache(file: File, data: WaveformData) {
        val json = JSONObject()
        json.put("min", JSONArray(data.minPeaks.map { it.toDouble() }))
        json.put("max", JSONArray(data.maxPeaks.map { it.toDouble() }))
        json.put("spec", JSONArray(data.spectrogramFrames.map { row -> JSONArray(row.map { it.toDouble() }) }))
        json.put("durationMs", data.durationMs)
        file.writeText(json.toString())
    }

    private fun extract(context: Context, uri: Uri): WaveformData? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null

        pfd.use { pfdSafe ->
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(pfdSafe.fileDescriptor)

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return null
                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                // Rough total-sample estimate to size the min/max downsampling bucket width.
                val estimatedTotalSamples = if (durationUs > 0) ((durationUs / 1_000_000.0) * sampleRate).toLong() else -1L
                val samplesPerEnvelopeBucket = if (estimatedTotalSamples > 0)
                    max(1L, estimatedTotalSamples / ENVELOPE_POINTS) else 4096L

                val minPeaks = ArrayList<Float>(ENVELOPE_POINTS + 8)
                val maxPeaks = ArrayList<Float>(ENVELOPE_POINTS + 8)
                var bucketMin = 0f
                var bucketMax = 0f
                var bucketCount = 0L
                var totalSamplesSeen = 0L

                val fftBuffer = FloatArray(SPECTROGRAM_FFT_SIZE)
                var fftFill = 0
                val spectrogramFrames = ArrayList<FloatArray>()
                var frameCounter = 0
                // Keep every Nth frame so we cap total frames stored, regardless of track length.
                val estimatedTotalFftFrames = if (estimatedTotalSamples > 0)
                    max(1L, estimatedTotalSamples / SPECTROGRAM_FFT_SIZE) else 500L
                val frameStride = max(1L, estimatedTotalFftFrames / SPECTROGRAM_MAX_FRAMES).toInt()

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                while (!sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inIndex = codec.dequeueInputBuffer(2000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)
                            val sampleSize = inBuf?.let { extractor.readSampleData(it, 0) } ?: -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 2000)
                    if (outIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val shortBuf = outBuf.asShortBuffer()
                            val count = bufferInfo.size / 2
                            var i = 0
                            while (i < count) {
                                // Downmix to mono for both envelope + spectrogram purposes.
                                var mono = 0f
                                var ch = 0
                                while (ch < channels && i < count) {
                                    mono += shortBuf.get(i) / 32768f
                                    i++
                                    ch++
                                }
                                mono /= max(1, channels)

                                bucketMin = min(bucketMin, mono)
                                bucketMax = max(bucketMax, mono)
                                bucketCount++
                                totalSamplesSeen++
                                if (bucketCount >= samplesPerEnvelopeBucket) {
                                    minPeaks.add(bucketMin)
                                    maxPeaks.add(bucketMax)
                                    bucketMin = 0f; bucketMax = 0f; bucketCount = 0
                                }

                                fftBuffer[fftFill] = mono
                                fftFill++
                                if (fftFill >= SPECTROGRAM_FFT_SIZE) {
                                    fftFill = 0
                                    if (frameCounter % frameStride == 0 && spectrogramFrames.size < SPECTROGRAM_MAX_FRAMES) {
                                        spectrogramFrames.add(computeSpectrogramFrame(fftBuffer, sampleRate))
                                    }
                                    frameCounter++
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }

                if (bucketCount > 0) {
                    minPeaks.add(bucketMin)
                    maxPeaks.add(bucketMax)
                }

                val durationMs = if (durationUs > 0) durationUs / 1000 else 0L
                return WaveformData(
                    minPeaks.toFloatArray(),
                    maxPeaks.toFloatArray(),
                    spectrogramFrames.toTypedArray(),
                    durationMs
                )
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                extractor.release()
            }
        }
    }

    /** Windowed FFT on one mono block -> 128-bucket log-magnitude spectrum, 0-1 normalized. */
    private fun computeSpectrogramFrame(buffer: FloatArray, sampleRate: Int): FloatArray {
        val n = buffer.size
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1))) // Hann window
            real[i] = buffer[i] * w
        }
        fft(real, imag)

        val half = n / 2
        val binsPerBucket = max(1, half / SPECTROGRAM_BUCKETS)
        val magnitudes = DoubleArray(SPECTROGRAM_BUCKETS)
        var maxMag = 1e-9
        for (b in 0 until SPECTROGRAM_BUCKETS) {
            var sum = 0.0
            var i = 0
            while (i < binsPerBucket && (b * binsPerBucket + i) < half) {
                val idx = b * binsPerBucket + i
                sum += kotlin.math.sqrt(real[idx] * real[idx] + imag[idx] * imag[idx])
                i++
            }
            magnitudes[b] = sum / binsPerBucket
            maxMag = max(maxMag, magnitudes[b])
        }

        // Log-scale + normalize to 0-1 against this frame's own peak so quiet passages
        // still show visible detail (standard spectrogram display convention).
        val out = FloatArray(SPECTROGRAM_BUCKETS)
        val floorDb = -60.0
        for (b in 0 until SPECTROGRAM_BUCKETS) {
            val db = 20.0 * log10((magnitudes[b] / maxMag).coerceAtLeast(1e-9))
            out[b] = ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. `n` must be a power of two. */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wR = cos(ang)
            val wI = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * curR - imag[i + k + len / 2] * curI
                    val vI = real[i + k + len / 2] * curI + imag[i + k + len / 2] * curR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextR = curR * wR - curI * wI
                    val nextI = curR * wI + curI * wR
                    curR = nextR
                    curI = nextI
                }
                i += len
            }
            len = len shl 1
        }
    }
}
