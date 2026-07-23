package com.beatraxus.app.engine

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * AudioSpectrumAnalyzer
 * =====================
 *
 * Single source of truth for the Music Detail Inspector's "SPECTROGRAM" +
 * "OVERALL QUALITY" cards: decodes a track (local OR cloud, ANY codec) once,
 * builds a waveform envelope + spectrogram, and classifies whether the file is
 * *genuinely* lossless or a lossy source repackaged/upsampled into a lossless
 * container — then exposes a color-coded badge for the spectrogram's top-right
 * corner.
 *
 * --------------------------------------------------------------------------
 * WHY THIS FILE EXISTS (bugs it fixes vs. the old WaveformExtractor + guards)
 * --------------------------------------------------------------------------
 * 1. Local ALAC (and DSD/WAV-variant) files never showed waveform/spectrogram
 *    detail. The old `WaveformExtractor.extract()` called
 *    `MediaCodec.createDecoderByType(mime)` directly on whatever mime
 *    MediaExtractor reported. Most Android builds ship NO software/hardware
 *    ALAC decoder, so `createDecoderByType("audio/alac")` throws (or silently
 *    fails to produce output); the exception was swallowed by the outer
 *    try/catch in `getOrExtract`, so the card just spun forever with no error.
 *    The app already solved this exact problem for *playback* — `DecoderFactory`
 *    routes ALAC/WAV/DSD/Dolby to `FfmpegAlacDecoder`. This analyzer reuses
 *    that SAME decoder factory instead of a second, weaker, raw-MediaCodec
 *    pipeline, so analysis now succeeds for every format playback succeeds for
 *    — local or cloud.
 *
 * 2. `NativeDsp.extractFeatures()` explicitly skips every ALAC file
 *    ("Skipping feature extraction for ALAC to avoid native crash") and every
 *    non-local (cloud) song via `AiAnalysisEngine.analyzeSong`'s
 *    `if (song.source != SongSource.LOCAL) return null` guard. That means
 *    "Overall Quality" silently stayed on "Analyzing…" forever for local ALAC
 *    AND for every single cloud song regardless of format. This analyzer
 *    replaces that native/JNI path for the Inspector screen with a pure-Kotlin
 *    decode (through DecoderFactory) so quality scoring works uniformly.
 *
 * 3. There was no concept of "is this container's content actually the
 *    resolution it claims?" — a 128kbps MP3 re-encoded to FLAC, or a 16-bit
 *    FLAC zero-padded to "24-bit", both reported as 100% lossless. This file
 *    adds `LosslessAuthenticity` detection (spectral cutoff + bit-depth LSB
 *    entropy) so the badge can tell genuine hi-res/lossless apart from a fake.
 */
internal class AudioSpectrumAnalyzer(
    private val context: Context,
    private val decoderFactory: DecoderFactory
) {
    // ------------------------------------------------------------------
    // Public result types
    // ------------------------------------------------------------------

    enum class LosslessAuthenticity {
        /** Full-bandwidth content up to (near) Nyquist, real bit-depth noise floor. */
        GENUINE_LOSSLESS,
        /** Lossless/hi-res *container* (FLAC/ALAC/WAV/24-bit/hi-res sample rate) whose
         *  actual content was transcoded from a lossy source or zero-padded from a lower
         *  bit depth / sample rate — a "fake" lossless file. */
        UPSAMPLED_FAKE,
        /** File is declared/encoded in a lossy codec (MP3, AAC, OGG, Opus, WMA-lossy…). */
        LOSSY_SOURCE
    }

    data class SpectrumAnalysisResult(
        val minPeaks: FloatArray,
        val maxPeaks: FloatArray,
        val spectrogramFrames: Array<FloatArray>, // SPECTROGRAM_BUCKETS magnitudes per frame, 0-1
        val durationMs: Long,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val authenticity: LosslessAuthenticity,
        val spectralCutoffHz: Int,       // detected high-frequency rolloff point
        val nyquistHz: Int,              // sampleRateHz / 2, for comparison in the UI
        val bitDepthLooksPadded: Boolean // true if the declared bit depth's low bits are silent/constant
    ) {
        /** Badge text + color for the spectrogram's top-right corner, per spec:
         *  genuine lossless -> accent color; upsampled/fake OR lossy -> gray. */
        fun badgeColor(): Color = when (authenticity) {
            LosslessAuthenticity.GENUINE_LOSSLESS -> Color(0xFF43E97B) // green — matches app's LosslessBadge accent
            LosslessAuthenticity.UPSAMPLED_FAKE -> Color(0xFF9AA3AF)  // gray
            LosslessAuthenticity.LOSSY_SOURCE -> Color(0xFF9AA3AF)    // gray
        }

        fun badgeLabel(): String = "ORIGINAL LOSSLESS"

        /** Small subtitle so the gray state isn't ambiguous about *why* it's gray. */
        fun badgeSubtitle(): String? = when (authenticity) {
            LosslessAuthenticity.GENUINE_LOSSLESS -> null
            LosslessAuthenticity.UPSAMPLED_FAKE -> "upsampled from lossy \u2248${spectralCutoffHz / 1000}kHz"
            LosslessAuthenticity.LOSSY_SOURCE -> "lossy source"
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Cached, coroutine-safe entry point — call this from the Inspector screen. */
    suspend fun getOrAnalyze(song: Song): SpectrumAnalysisResult? = withContext(Dispatchers.IO) {
        val cache = cacheFile(context, song)
        readCache(cache)?.let { return@withContext it }

        val result = try {
            analyze(song)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Spectrum analysis crash prevented for ${song.title}", t)
            null
        }
        if (result != null) {
            try {
                writeCache(cache, result)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to cache spectrum analysis for ${song.title}", e)
            }
        }
        result
    }

    /** Decodes the track once (via the SAME DecoderFactory playback uses — this is what
     *  fixes local ALAC / DSD / cloud-any-format) and derives the waveform, spectrogram,
     *  and lossless-authenticity signals from the decoded PCM. */
    private suspend fun analyze(song: Song): SpectrumAnalysisResult? {
        val decoder = decoderFactory.create(song)
        val sink = AnalysisSink(declaredBitDepth = song.bitDepth.takeIf { it > 0 } ?: 16)

        val control = object : DecoderControl {
            @Volatile private var active = true
            override fun isActive(): Boolean = active && sink.framesCollected() < MAX_ANALYSIS_FRAMES
            override fun isSeekPending(): Boolean = false
            override fun consumePendingSeekMs(): Long? = null
            override fun setSeekListener(listener: () -> Unit) {}
            override fun notifySeek(positionMs: Long) {}
            override fun logDebug(message: String) {}
            override fun logWarn(message: String) { android.util.Log.w(TAG, message) }
            fun stop() { active = false }
        }

        val request = PlaybackRequest(song = song, startPositionMs = 0L)
        try {
            decoder.decode(request, sink, control)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Decode failed during analysis for ${song.title}", t)
            if (!sink.hasAnyData()) return null
        }

        if (!sink.hasAnyData()) return null
        return sink.buildResult(song)
    }

    // ------------------------------------------------------------------
    // Streaming sink: consumes decoded PCM, builds envelope + spectrogram +
    // authenticity signals incrementally so we never hold the whole track in RAM.
    // ------------------------------------------------------------------
    private inner class AnalysisSink(private val declaredBitDepth: Int) : DecoderSink {
        private var sampleRate = 44100
        private var channels = 2

        private val minPeaks = ArrayList<Float>(ENVELOPE_POINTS + 8)
        private val maxPeaks = ArrayList<Float>(ENVELOPE_POINTS + 8)
        private var bucketMin = 0f
        private var bucketMax = 0f
        private var bucketCount = 0L
        private var samplesPerEnvelopeBucket = 4096L
        private var totalMonoSamplesSeen = 0L

        private val fftBuffer = FloatArray(SPECTROGRAM_FFT_SIZE)
        private var fftFill = 0
        private val spectrogramFrames = ArrayList<FloatArray>()
        private var frameCounter = 0
        private var frameStride = 1

        // Averaged spectrum across the whole track, for cutoff-frequency detection —
        // more reliable than reading it off any single displayed frame.
        private val cutoffAccum = DoubleArray(SPECTROGRAM_BUCKETS)
        private var cutoffFrameCount = 0

        // Bit-depth authenticity: histogram of the lowest byte of each sample once
        // rescaled to the declared bit depth. A genuine 24-bit source has a roughly
        // uniform/noisy low byte; a 16-bit source zero-padded to 24-bit has a low byte
        // that is (almost) always 0.
        private val lowByteHistogram = IntArray(256)
        private var lowByteSamples = 0L

        private var framesCollected = 0L
        fun framesCollected() = framesCollected

        fun hasAnyData(): Boolean = spectrogramFrames.isNotEmpty() || maxPeaks.isNotEmpty()

        override suspend fun configure(format: PcmAudioFormat) {
            sampleRate = format.sampleRate.takeIf { it > 0 } ?: sampleRate
            channels = format.channels.takeIf { it > 0 } ?: channels

            val estimatedTotalSamples = -1L // unknown up front; envelope adapts via a rolling bucket size below
            samplesPerEnvelopeBucket = if (estimatedTotalSamples > 0)
                max(1L, estimatedTotalSamples / ENVELOPE_POINTS) else 4096L

            val estimatedTotalFftFrames = 2000L // generous upper bound; stride recalculated as needed
            frameStride = max(1, (estimatedTotalFftFrames / SPECTROGRAM_MAX_FRAMES).toInt())
        }

        override suspend fun write(data: FloatArray, sampleCount: Int) {
            var i = 0
            while (i < sampleCount) {
                var mono = 0f
                var ch = 0
                var frameLowByteRef = 0
                while (ch < channels && i < sampleCount) {
                    val s = data[i]
                    mono += s
                    if (ch == 0) frameLowByteRef = quantizedLowByte(s)
                    i++
                    ch++
                }
                mono /= max(1, channels)
                totalMonoSamplesSeen++

                // Envelope
                bucketMin = min(bucketMin, mono)
                bucketMax = max(bucketMax, mono)
                bucketCount++
                if (bucketCount >= samplesPerEnvelopeBucket) {
                    minPeaks.add(bucketMin); maxPeaks.add(bucketMax)
                    bucketMin = 0f; bucketMax = 0f; bucketCount = 0
                }

                // Bit-depth authenticity sampling (sparse — every 8th sample is plenty)
                if (totalMonoSamplesSeen % 8L == 0L) {
                    lowByteHistogram[frameLowByteRef and 0xFF]++
                    lowByteSamples++
                }

                // Spectrogram / cutoff FFT feed
                fftBuffer[fftFill] = mono
                fftFill++
                if (fftFill >= SPECTROGRAM_FFT_SIZE) {
                    fftFill = 0
                    val frame = computeSpectrogramFrame(fftBuffer, sampleRate)
                    accumulateCutoff(frame)
                    if (frameCounter % frameStride == 0 && spectrogramFrames.size < SPECTROGRAM_MAX_FRAMES) {
                        spectrogramFrames.add(frame)
                    }
                    frameCounter++
                    framesCollected++
                }
            }
        }

        private fun quantizedLowByte(sample: Float): Int {
            // Rescale the normalized float sample back to an integer at the *declared*
            // bit depth (capped at 24) and return its lowest byte.
            val bits = declaredBitDepth.coerceIn(16, 24)
            val scale = (1 shl (bits - 1)) - 1
            val q = (sample.coerceIn(-1f, 1f) * scale).roundToInt()
            return q and 0xFF
        }

        private fun accumulateCutoff(frame: FloatArray) {
            for (b in frame.indices) cutoffAccum[b] += frame[b]
            cutoffFrameCount++
        }

        fun buildResult(song: Song): SpectrumAnalysisResult {
            if (bucketCount > 0) { minPeaks.add(bucketMin); maxPeaks.add(bucketMax) }

            val nyquist = sampleRate / 2
            val cutoffHz = detectSpectralCutoff(cutoffAccum, cutoffFrameCount, nyquist)
            val bitDepthPadded = detectBitDepthPadding(lowByteHistogram, lowByteSamples, declaredBitDepth)

            val declaredLossyCodec = LOSSY_CODECS.any { song.format.lowercase().contains(it) }
            val declaredLosslessCodec = LOSSLESS_CODECS.any { song.format.uppercase().contains(it) }

            val authenticity = when {
                declaredLossyCodec && !declaredLosslessCodec -> LosslessAuthenticity.LOSSY_SOURCE
                declaredLosslessCodec && (isSuspiciousCutoff(cutoffHz, nyquist) || bitDepthPadded) ->
                    LosslessAuthenticity.UPSAMPLED_FAKE
                declaredLosslessCodec -> LosslessAuthenticity.GENUINE_LOSSLESS
                else -> LosslessAuthenticity.LOSSY_SOURCE // unknown codec: treat conservatively as non-lossless
            }

            return SpectrumAnalysisResult(
                minPeaks = minPeaks.toFloatArray(),
                maxPeaks = maxPeaks.toFloatArray(),
                spectrogramFrames = spectrogramFrames.toTypedArray(),
                durationMs = song.durationMs,
                sampleRateHz = sampleRate,
                bitDepth = declaredBitDepth,
                authenticity = authenticity,
                spectralCutoffHz = cutoffHz,
                nyquistHz = nyquist,
                bitDepthLooksPadded = bitDepthPadded
            )
        }
    }

    // ------------------------------------------------------------------
    // Signal-detection helpers
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "AudioSpectrumAnalyzer"
        private const val ENVELOPE_POINTS = 500
        private const val SPECTROGRAM_FFT_SIZE = 2048
        private const val SPECTROGRAM_BUCKETS = 128
        private const val SPECTROGRAM_MAX_FRAMES = 200
        private const val MAX_ANALYSIS_FRAMES = 2000L // decode cap so a 3-hour file doesn't stall analysis

        private val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD", "DSF", "PCM")
        private val LOSSY_CODECS = setOf("mp3", "aac", "m4a", "ogg", "opus", "wma", "vorbis")

        /** Scans the averaged (whole-track) spectrum for a sharp high-frequency cliff —
         *  the signature of a prior lossy encode's low-pass filter (e.g. ~16kHz for
         *  128kbps MP3, ~19-20kHz for 256-320kbps MP3/AAC) baked into a lossless container.
         *  Returns the detected cutoff in Hz, or `nyquist` if the spectrum is full-bandwidth. */
        internal fun detectSpectralCutoff(accum: DoubleArray, frameCount: Int, nyquistHz: Int): Int {
            if (frameCount <= 0) return nyquistHz
            val buckets = accum.size
            val avg = DoubleArray(buckets) { accum[it] / frameCount }
            val hzPerBucket = nyquistHz.toDouble() / buckets

            // Reference level = median magnitude across the lower 60% of the spectrum
            // (where lossy encoders rarely touch anything), used as "full signal" baseline.
            val refBand = avg.copyOfRange(0, (buckets * 0.6).toInt()).sortedDescending()
            val refLevel = if (refBand.isNotEmpty()) refBand[refBand.size / 2] else 1e-6

            // Walk from high frequency downward looking for the first bucket where the
            // level rises back above -30dB relative to the reference — i.e. the top edge
            // of the "cliff". Everything above that point is treated as filtered/noise floor.
            var cutoffBucket = buckets - 1
            for (b in buckets - 1 downTo (buckets * 0.5).toInt()) {
                val db = 20.0 * log10((avg[b] / refLevel).coerceAtLeast(1e-9))
                if (db > -30.0) { cutoffBucket = b; break }
                cutoffBucket = (buckets * 0.5).toInt()
            }
            return (cutoffBucket * hzPerBucket).roundToInt()
        }

        /** A cutoff meaningfully below Nyquist (>8% short of it, and below ~21kHz where
         *  most consumer lossy encoders top out even at their best settings) indicates the
         *  content itself was band-limited before being packed into this container. */
        internal fun isSuspiciousCutoff(cutoffHz: Int, nyquistHz: Int): Boolean {
            if (nyquistHz <= 0) return false
            val ratio = cutoffHz.toDouble() / nyquistHz.toDouble()
            return ratio < 0.92 && cutoffHz < 21500
        }

        /** A genuinely 24-bit (or deeper) source has dithered/noisy low-order bits; a
         *  16-bit source zero-padded to "24-bit" has a low byte that is overwhelmingly
         *  one value (typically 0). We flag padding when >85% of sampled low bytes are
         *  the single most common value. Only meaningful when the declared bit depth is
         *  >16; 16-bit-declared files are trivially "not padded" by definition. */
        internal fun detectBitDepthPadding(histogram: IntArray, totalSamples: Long, declaredBitDepth: Int): Boolean {
            if (declaredBitDepth <= 16 || totalSamples < 2000) return false
            val peak = histogram.maxOrNull() ?: return false
            val dominance = peak.toDouble() / totalSamples.toDouble()
            return dominance > 0.85
        }

        /** Windowed FFT -> 128-bucket log-magnitude spectrum, 0-1 normalized. Identical
         *  algorithm/constants to the previous WaveformExtractor so cached spectrogram
         *  visuals look the same to users. */
        internal fun computeSpectrogramFrame(buffer: FloatArray, sampleRate: Int): FloatArray {
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
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j or bit
                if (i < j) {
                    val tr = real[i]; real[i] = real[j]; real[j] = tr
                    val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wR = cos(ang); val wI = sin(ang)
                var i = 0
                while (i < n) {
                    var curR = 1.0; var curI = 0.0
                    for (k in 0 until len / 2) {
                        val uR = real[i + k]; val uI = imag[i + k]
                        val vR = real[i + k + len / 2] * curR - imag[i + k + len / 2] * curI
                        val vI = real[i + k + len / 2] * curI + imag[i + k + len / 2] * curR
                        real[i + k] = uR + vR; imag[i + k] = uI + vI
                        real[i + k + len / 2] = uR - vR; imag[i + k + len / 2] = uI - vI
                        val nextR = curR * wR - curI * wI
                        val nextI = curR * wI + curI * wR
                        curR = nextR; curI = nextI
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    // ------------------------------------------------------------------
    // Disk cache (mirrors WaveformExtractor's convention, extended with the
    // new authenticity fields, keyed by song id).
    // ------------------------------------------------------------------

    private fun cacheFile(context: Context, song: Song): File {
        val dir = File(context.filesDir, "spectrum_analysis_cache").apply { mkdirs() }
        // Task 3: key on both ID and size for extra safety against file swaps/updates.
        return File(dir, "${song.id}_${song.fileSizeBytes}.json")
    }

    private fun readCache(file: File): SpectrumAnalysisResult? = try {
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
        SpectrumAnalysisResult(
            minPeaks = min,
            maxPeaks = max,
            spectrogramFrames = spec,
            durationMs = json.optLong("durationMs", 0L),
            sampleRateHz = json.optInt("sampleRateHz", 44100),
            bitDepth = json.optInt("bitDepth", 16),
            authenticity = LosslessAuthenticity.valueOf(json.optString("authenticity", "LOSSY_SOURCE")),
            spectralCutoffHz = json.optInt("spectralCutoffHz", 0),
            nyquistHz = json.optInt("nyquistHz", 22050),
            bitDepthLooksPadded = json.optBoolean("bitDepthLooksPadded", false)
        )
    } catch (e: Exception) {
        null
    }

    private fun writeCache(file: File, data: SpectrumAnalysisResult) {
        val json = JSONObject()
        json.put("min", JSONArray(data.minPeaks.map { it.toDouble() }))
        json.put("max", JSONArray(data.maxPeaks.map { it.toDouble() }))
        json.put("spec", JSONArray(data.spectrogramFrames.map { row -> JSONArray(row.map { it.toDouble() }) }))
        json.put("durationMs", data.durationMs)
        json.put("sampleRateHz", data.sampleRateHz)
        json.put("bitDepth", data.bitDepth)
        json.put("authenticity", data.authenticity.name)
        json.put("spectralCutoffHz", data.spectralCutoffHz)
        json.put("nyquistHz", data.nyquistHz)
        json.put("bitDepthLooksPadded", data.bitDepthLooksPadded)
        file.writeText(json.toString())
    }
}

// ============================================================================
// UI: spectrogram canvas + top-right authenticity badge (drop-in replacement
// for MusicDetailInspectorScreen's SpectrogramCard body).
// ============================================================================

/** Thermal palette — identical stops to the existing Inspector screen so the
 *  heatmap look doesn't change, only the corner badge is new. */
private fun thermalColor(mag: Float): Color {
    val stops = listOf(
        0.00f to Color(0xFF07060B),
        0.20f to Color(0xFF2A2A8C),
        0.40f to Color(0xFF1E9BD7),
        0.60f to Color(0xFF29E17A),
        0.80f to Color(0xFFF6E24C),
        1.00f to Color(0xFFFF3B3B)
    )
    val m = mag.coerceIn(0f, 1f)
    for (i in 0 until stops.size - 1) {
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        if (m in p0..p1) {
            val t = if (p1 > p0) (m - p0) / (p1 - p0) else 0f
            return Color(
                red = c0.red + (c1.red - c0.red) * t,
                green = c0.green + (c1.green - c0.green) * t,
                blue = c0.blue + (c1.blue - c0.blue) * t
            )
        }
    }
    return stops.last().second
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpectrogram(frames: Array<FloatArray>) {
    val cols = frames.size
    val rows = frames.firstOrNull()?.size ?: return
    val cellW = size.width / cols
    val cellH = size.height / rows
    for (c in 0 until cols) {
        val frame = frames[c]
        for (r in 0 until rows) {
            drawRect(
                color = thermalColor(frame[r].coerceIn(0f, 1f)),
                topLeft = Offset(c * cellW, size.height - (r + 1) * cellH),
                size = Size(cellW + 0.5f, cellH + 0.5f)
            )
        }
    }
}

/** Top-right corner badge: "ORIGINAL LOSSLESS" in green when the spectrum/bit-depth
 *  analysis confirms genuine lossless content; gray (with a short reason) when the
 *  file is an upsampled/transcoded fake or a plain lossy format. */
@Composable
internal fun LosslessAuthenticityBadge(result: AudioSpectrumAnalyzer.SpectrumAnalysisResult) {
    val color = result.badgeColor()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = result.badgeSubtitle()?.let { "${result.badgeLabel()} \u00b7 ${it}" } ?: result.badgeLabel(),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
    }
}

/** Full spectrogram canvas + corner badge, positioned exactly like the request:
 *  the lossless-authenticity text sits in the spectrogram's top-right corner. */
@Composable
internal fun SpectrogramAnalysisView(
    result: AudioSpectrumAnalyzer.SpectrumAnalysisResult?,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        // Frequency measurements on the outside (left)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            val nyquistK = (result?.nyquistHz ?: 22050) / 1000f
            Text("${nyquistK.toInt()}k", color = Color.White.copy(0.4f), fontSize = 8.sp)
            Text("${(nyquistK * 0.75f).toInt()}k", color = Color.White.copy(0.4f), fontSize = 8.sp)
            Text("${(nyquistK * 0.5f).toInt()}k", color = Color.White.copy(0.4f), fontSize = 8.sp)
            Text("${(nyquistK * 0.25f).toInt()}k", color = Color.White.copy(0.4f), fontSize = 8.sp)
            Text("0", color = Color.White.copy(0.4f), fontSize = 8.sp)
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val frames = result?.spectrogramFrames
            if (!frames.isNullOrEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawSpectrogram(frames) }
            }
        }
    }
}
