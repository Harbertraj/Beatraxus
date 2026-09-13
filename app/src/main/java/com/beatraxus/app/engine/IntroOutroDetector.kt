package com.beatraxus.app.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatraxus.app.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class IntroOutroDetector(private val context: Context) {
    private val TAG = "IntroOutroDetector"
    private val nativeDsp = NativeDsp()

    /**
     * Analyzes a list of videos from the same folder to find a recurring intro.
     * Returns a pair of (startMs, endMs) if detected, or null.
     */
    suspend fun detectIntro(videos: List<Video>): Pair<Long, Long>? = withContext(Dispatchers.Default) {
        if (videos.size < 2) return@withContext null

        val fingerprints = mutableListOf<FloatArray>()
        // We only analyze the first 180 seconds for intros
        val maxAnalysisSeconds = 180

        for (video in videos.take(3)) { // Analyze up to 3 videos for efficiency
            try {
                val features = nativeDsp.extractFeatures(context, video.uri, maxAnalysisSeconds)
                features?.noveltyVector?.let { fingerprints.add(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract fingerprint for ${video.title}", e)
            }
        }

        if (fingerprints.size < 2) return@withContext null

        // Cross-correlate the first two fingerprints
        val match = findBestMatch(fingerprints[0], fingerprints[1]) ?: return@withContext null

        // Validate with the third if available
        if (fingerprints.size > 2) {
            val match2 = findBestMatch(fingerprints[0], fingerprints[2])
            if (match2 == null || abs(match.first - match2.first) > 5 || abs(match.second - match2.second) > 5) {
                // Not consistently appearing in all analyzed files
                return@withContext null
            }
        }

        // Convert indices to milliseconds
        // Each FFT hop is 2048 samples at (typically) 44100Hz
        val msPerIndex = (2048f / 44100f) * 1000f
        val startMs = (match.first * msPerIndex).toLong()
        val endMs = (match.second * msPerIndex).toLong()

        // Minimum intro length: 5 seconds, Maximum: 90 seconds
        val duration = endMs - startMs
        if (duration in 5000..90000) {
            Log.d(TAG, "Detected intro: $startMs ms to $endMs ms")
            startMs to endMs
        } else {
            null
        }
    }

    private fun findBestMatch(v1: FloatArray, v2: FloatArray): Pair<Int, Int>? {
        // Simple sliding window cross-correlation on novelty vectors
        val windowSize = 100 // ~4.6 seconds at 2048 hop / 44.1kHz
        val searchLimit = min(v1.size, v2.size) - windowSize
        
        var bestCorrelation = 0f
        var bestOffset = 0
        var bestIndex = -1

        // Find the best matching anchor point
        for (i in 0 until searchLimit) {
            // Search in a ±5 second range in the second file
            val searchStart = max(0, i - 110)
            val searchEnd = min(searchLimit, i + 110)
            
            for (j in searchStart until searchEnd) {
                val corr = correlate(v1, i, v2, j, windowSize)
                if (corr > bestCorrelation) {
                    bestCorrelation = corr
                    bestOffset = j - i
                    bestIndex = i
                }
            }
        }

        // Threshold for correlation to consider it a match
        if (bestCorrelation < 0.7f) return null

        // Expand the match in both directions
        var start = bestIndex
        var end = bestIndex + windowSize

        while (start > 0 && correlate(v1, start - 1, v2, start - 1 + bestOffset, 1) > 0.4f) {
            start--
        }
        while (end < searchLimit && correlate(v1, end, v2, end + bestOffset, 1) > 0.4f) {
            end++
        }

        return start to end
    }

    private fun correlate(v1: FloatArray, start1: Int, v2: FloatArray, start2: Int, length: Int): Float {
        var sum = 0f
        var sumSq1 = 0f
        var sumSq2 = 0f
        for (i in 0 until length) {
            val a = v1[start1 + i]
            val b = v2[start2 + i]
            sum += a * b
            sumSq1 += a * a
            sumSq2 += b * b
        }
        val denom = kotlin.math.sqrt(sumSq1 * sumSq2)
        return if (denom > 0) sum / denom else 0f
    }
}
