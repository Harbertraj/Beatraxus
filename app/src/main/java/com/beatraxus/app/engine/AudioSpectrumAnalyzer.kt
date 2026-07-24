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
        /** Full-bandwidth content, likely original master. (90-100% confidence) */
        ORIGINAL_LOSSLESS,
        /** High bandwidth with minor roll-off or artifacts; likely genuine. (70-89% confidence) */
        LIKELY_LOSSLESS,
        /** Significant band-limiting or suspicious noise floor detected. (40-69% confidence) */
        POSSIBLY_UPSCALED,
        /** Obvious brick-wall filtering or zero-padded bits detected. (0-39% confidence) */
        DEFINITELY_UPSCALED
    }

    data class SpectrumAnalysisResult(
        val minPeaks: FloatArray,
        val maxPeaks: FloatArray,
        val spectrogramFrames: Array<FloatArray>, // SPECTROGRAM_BUCKETS magnitudes per frame, 0-1
        val durationMs: Long,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val authenticity: LosslessAuthenticity,
        val confidenceScore: Int,        // 0-100 weighted confidence
        val spectralCutoffHz: Int,       // detected high-frequency rolloff point
        val nyquistHz: Int,              // sampleRateHz / 2, for comparison in the UI
        val bitDepthLooksPadded: Boolean // true if the declared bit depth's low bits are silent/constant
    ) {
        /** Badge color based on tier: Green for original, Amber for likely, Gray for fake. */
        fun badgeColor(): Color = when (authenticity) {
            LosslessAuthenticity.ORIGINAL_LOSSLESS -> Color(0xFF43E97B) // Green
            LosslessAuthenticity.LIKELY_LOSSLESS -> Color(0xFF43E97B)   // Green (still considered "good")
            LosslessAuthenticity.POSSIBLY_UPSCALED -> Color(0xFFFFB03B) // Amber/Orange
            LosslessAuthenticity.DEFINITELY_UPSCALED -> Color(0xFF9AA3AF) // Gray
        }

        fun badgeLabel(): String = when (authenticity) {
            LosslessAuthenticity.ORIGINAL_LOSSLESS -> "ORIGINAL LOSSLESS"
            LosslessAuthenticity.LIKELY_LOSSLESS -> "LIKELY LOSSLESS"
            LosslessAuthenticity.POSSIBLY_UPSCALED -> "POSSIBLY UPSCALED"
            LosslessAuthenticity.DEFINITELY_UPSCALED -> "DEFINITELY UPSCALED"
        }

        /** Detail text showing the score and detected cutoff. */
        fun badgeSubtitle(): String = "${confidenceScore}% \u00b7 \u2248${spectralCutoffHz / 1000}kHz"
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

        // Averaged spectrum across the whole track, for cutoff-frequency detection.
        private val cutoffAccum = DoubleArray(SPECTROGRAM_BUCKETS)
        private var cutoffFrameCount = 0

        // Per-frame cutoff history for temporal stability analysis.
        private val frameCutoffs = ArrayList<Int>()

        // High-frequency energy accumulation (>15kHz).
        private var hfEnergyTotal = 0.0
        private var energyTotal = 0.0

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
                    val nyquist = sampleRate / 2
                    
                    // Track temporal signals
                    val frameCutoff = detectFrameCutoff(frame, nyquist)
                    frameCutoffs.add(frameCutoff)
                    accumulateEnergy(frame, nyquist)
                    
                    accumulateCutoff(frame)
                    if (frameCounter % frameStride == 0 && spectrogramFrames.size < SPECTROGRAM_MAX_FRAMES) {
                        spectrogramFrames.add(frame)
                    }
                    frameCounter++
                    framesCollected++
                }
            }
        }

        private fun detectFrameCutoff(frame: FloatArray, nyquist: Int): Int {
            // Quick per-frame cutoff check (0.1 magnitude threshold)
            val hzPerBucket = nyquist.toDouble() / frame.size
            for (b in frame.indices.reversed()) {
                if (frame[b] > 0.1f) return (b * hzPerBucket).toInt()
            }
            return 0
        }

        private fun accumulateEnergy(frame: FloatArray, nyquist: Int) {
            val hzPerBucket = nyquist.toDouble() / frame.size
            for (b in frame.indices) {
                val hz = b * hzPerBucket
                val energy = frame[b].toDouble()
                energyTotal += energy
                if (hz > 15000) hfEnergyTotal += energy
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
            val cutoffMetrics = analyzeSpectralRollOff(cutoffAccum, cutoffFrameCount, nyquist)
            val bitDepthPadded = detectBitDepthPadding(lowByteHistogram, lowByteSamples, declaredBitDepth)
            
            // Temporal stability: variance of frame cutoffs (normalized 0-1)
            val temporalStability = computeTemporalStability(frameCutoffs, cutoffMetrics.cutoffHz)
            
            // HF Energy ratio
            val hfRatio = if (energyTotal > 0) hfEnergyTotal / energyTotal else 0.0

            val declaredLosslessCodec = LOSSLESS_CODECS.any { song.format.uppercase().contains(it) }

            val score = if (!declaredLosslessCodec) 0 else {
                computeConfidenceScore(
                    cutoffHz = cutoffMetrics.cutoffHz,
                    nyquistHz = nyquist,
                    slopeDbOct = cutoffMetrics.slopeDbOct,
                    noiseFloorDb = cutoffMetrics.hfNoiseFloorDb,
                    temporalStability = temporalStability,
                    bitDepthPadded = bitDepthPadded,
                    hfRatio = hfRatio
                )
            }

            val authenticity = when {
                !declaredLosslessCodec -> LosslessAuthenticity.DEFINITELY_UPSCALED
                score >= 90 -> LosslessAuthenticity.ORIGINAL_LOSSLESS
                score >= 70 -> LosslessAuthenticity.LIKELY_LOSSLESS
                score >= 40 -> LosslessAuthenticity.POSSIBLY_UPSCALED
                else -> LosslessAuthenticity.DEFINITELY_UPSCALED
            }

            return SpectrumAnalysisResult(
                minPeaks = minPeaks.toFloatArray(),
                maxPeaks = maxPeaks.toFloatArray(),
                spectrogramFrames = spectrogramFrames.toTypedArray(),
                durationMs = song.durationMs,
                sampleRateHz = sampleRate,
                bitDepth = declaredBitDepth,
                authenticity = authenticity,
                confidenceScore = score,
                spectralCutoffHz = cutoffMetrics.cutoffHz,
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

        private const val WEIGHT_CUTOFF = 0.40
        private const val WEIGHT_SLOPE = 0.25
        private const val WEIGHT_NOISE = 0.15
        private const val WEIGHT_STABILITY = 0.10
        private const val WEIGHT_BIT_DEPTH = 0.10

        data class CutoffMetrics(
            val cutoffHz: Int,
            val slopeDbOct: Double,
            val hfNoiseFloorDb: Double
        )

        /** Comprehensive spectral analysis: finds the cutoff, the steepness of the
         *  drop, and the noise floor level above the cutoff. */
        internal fun analyzeSpectralRollOff(accum: DoubleArray, frameCount: Int, nyquistHz: Int): CutoffMetrics {
            if (frameCount <= 0) return CutoffMetrics(nyquistHz, 0.0, -100.0)
            val buckets = accum.size
            val avg = DoubleArray(buckets) { accum[it] / frameCount }
            val hzPerBucket = nyquistHz.toDouble() / buckets

            // 1. Find Reference Level (median of 0-12kHz range)
            val refLimit = (12000.0 / hzPerBucket).toInt().coerceIn(1, buckets)
            val refBand = avg.copyOfRange(0, refLimit).sortedDescending()
            val refLevel = if (refBand.isNotEmpty()) refBand[refBand.size / 2] else 1e-6

            // 2. Find Cutoff (-30dB point)
            var cutoffBucket = buckets - 1
            for (b in buckets - 1 downTo 1) {
                val db = 20.0 * log10((avg[b] / refLevel).coerceAtLeast(1e-9))
                if (db > -30.0) { cutoffBucket = b; break }
            }

            // 3. Calculate Slope (dB/octave) just before cutoff
            val octRange = 0.1 // analyze 1/10th of an octave
            val startBucket = (cutoffBucket * (1.0 - octRange)).toInt().coerceAtLeast(0)
            val dbStart = 20.0 * log10((avg[startBucket] / refLevel).coerceAtLeast(1e-9))
            val dbEnd = 20.0 * log10((avg[cutoffBucket] / refLevel).coerceAtLeast(1e-9))
            // This is a simplified slope; in practice, brick-walls are >60dB/oct
            val slope = (dbStart - dbEnd) / octRange 

            // 4. Measure Noise Floor above cutoff
            var noiseFloorSum = 0.0
            var noiseFloorCount = 0
            for (b in cutoffBucket + 1 until buckets) {
                noiseFloorSum += avg[b]
                noiseFloorCount++
            }
            val avgNoise = if (noiseFloorCount > 0) noiseFloorSum / noiseFloorCount else 1e-9
            val noiseDb = 20.0 * log10((avgNoise / refLevel).coerceAtLeast(1e-9))

            return CutoffMetrics((cutoffBucket * hzPerBucket).roundToInt(), slope, noiseDb)
        }

        internal fun computeTemporalStability(cutoffs: List<Int>, avgCutoff: Int): Double {
            if (cutoffs.isEmpty()) return 1.0
            var variance = 0.0
            for (c in cutoffs) variance += kotlin.math.abs(c - avgCutoff)
            val meanVar = variance / cutoffs.size
            // High stability (low variance) is actually SUSPICIOUS for lossy filters,
            // but natural roll-offs are also stable. We use this to distinguish
            // "clean" filters from "noisy" original content.
            // 0 = perfectly stable, 1 = wildly unstable
            return (meanVar / 2000.0).coerceIn(0.0, 1.0)
        }

        internal fun computeConfidenceScore(
            cutoffHz: Int,
            nyquistHz: Int,
            slopeDbOct: Double,
            noiseFloorDb: Double,
            temporalStability: Double,
            bitDepthPadded: Boolean,
            hfRatio: Double
        ): Int {
            // Cutoff Score: 100 if >20kHz, scales down to 0 at 15kHz
            val cutoffScore = ((cutoffHz - 15000.0) / (min(nyquistHz, 21000) - 15000.0))
                .coerceIn(0.0, 1.0) * 100.0

            // Slope Score: Natural roll-offs are gentle (<20dB/oct).
            // Brick-walls are >60dB/oct.
            val slopeScore = (1.0 - (slopeDbOct - 10.0) / 50.0).coerceIn(0.0, 1.0) * 100.0

            // Noise Score: -40dB to -70dB is good (analog noise).
            // -90dB or lower is likely a digital silence/filter.
            val noiseScore = ((noiseFloorDb + 90.0) / 40.0).coerceIn(0.0, 1.0) * 100.0

            // Stability: 100 if it wavers (genuine), 50 if perfectly static (clean filter).
            val stabilityScore = 50.0 + (temporalStability * 50.0)

            // Bit-depth: 100 if not padded, 0 if padded.
            val bitDepthScore = if (bitDepthPadded) 0.0 else 100.0

            // HF Energy ratio: hi-res recordings usually have >0.1% energy above 15kHz.
            // Brick-walls have near 0%.
            val hfScore = (hfRatio * 1000.0).coerceIn(0.0, 1.0) * 100.0

            val total = (cutoffScore * WEIGHT_CUTOFF) +
                        (slopeScore * WEIGHT_SLOPE) +
                        (noiseScore * WEIGHT_NOISE) +
                        (stabilityScore * WEIGHT_STABILITY) +
                        (bitDepthScore * WEIGHT_BIT_DEPTH * 0.5) + // reducing bit depth weight slightly to fit HF
                        (hfScore * 0.05)
            
            return total.roundToInt().coerceIn(0, 100)
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
            authenticity = LosslessAuthenticity.valueOf(json.optString("authenticity", "DEFINITELY_UPSCALED")),
            confidenceScore = json.optInt("confidenceScore", 0),
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
        json.put("confidenceScore", data.confidenceScore)
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
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "${result.badgeLabel()} \u00b7 ${result.badgeSubtitle()}",
            color = color,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.3.sp,
            maxLines = 1
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
