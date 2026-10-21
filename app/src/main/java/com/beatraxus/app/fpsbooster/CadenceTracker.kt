package com.beatraxus.app.fpsbooster

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns the render timestamps of decoded frames into an even time line (pure Kotlin, unit tested on the JVM).
 *
 * Why this exists: the timestamp ExoPlayer stamps on a frame it renders into a SurfaceTexture is not the
 * frame's presentation time, it is the *release* time, and Media3's frame-release helper snaps that to the
 * display's vsync grid. A 24 fps film on a 60 Hz panel therefore arrives as 50 / 33 / 50 / 33 ms steps instead
 * of 41.7 ms steps (the 3:2 pulldown pattern); 25 fps on 60 Hz and 30 fps on 90 Hz show the same effect. The
 * interpolator places every output frame at `alpha = (t - ts[a]) / (ts[b] - ts[a])`, so with those steps the
 * motion speed alternates by about +-20 % from one pair to the next: visible judder on top of the
 * interpolation, and the running frame-interval estimate keeps wobbling.
 *
 * The fix is to fit a straight line (frame index -> time) through the last [window] frames and use the point on
 * the line instead of the raw time. The line is anchored to the real timestamps (least squares), so there is no
 * long-term drift against the audio clock; a frame that is further than half an interval from the line is a
 * genuine timing change (variable-frame-rate content, a stall) and is passed through unchanged.
 *
 * A seek, pause, new clip or backwards time restarts the fit.
 */
class CadenceTracker(private val window: Int = WINDOW) {
    private val idx = DoubleArray(window)
    private val rel = DoubleArray(window)   // raw time of each sample, relative to [baseNs]
    private var count = 0
    private var next = 0
    private var baseNs = 0L
    private var lastRawNs = 0L
    private var lastOutNs = 0L
    private var frameIndex = 0L

    /**
     * Fitted time (ns) per frame step as seen by this tracker, or 0 until enough frames have been seen. At a
     * playback speed other than 1x one step can span several source frames, so the renderer derives its
     * frame interval from the regularised timestamps instead of using this value.
     */
    var intervalNs: Long = 0L
        private set

    fun reset() {
        count = 0; next = 0; frameIndex = 0L; intervalNs = 0L; lastRawNs = 0L; lastOutNs = 0L
    }

    /** Starts a fresh fit at [rawNs] (discontinuity). Returns [rawNs]. */
    private fun restart(rawNs: Long): Long {
        reset()
        baseNs = rawNs
        add(0.0, 0.0)
        lastRawNs = rawNs
        lastOutNs = rawNs
        return rawNs
    }

    private fun add(i: Double, t: Double) {
        idx[next] = i; rel[next] = t
        next = (next + 1) % window
        if (count < window) count++
    }

    /** Returns the regularised timestamp for a frame whose raw render time is [rawNs]. */
    fun onFrame(rawNs: Long): Long {
        if (count == 0) return restart(rawNs)
        val gap = rawNs - lastRawNs
        if (gap <= 0L || gap >= MAX_GAP_NS) return restart(rawNs)

        // How many frame steps this gap spans (a dropped frame makes it 2).
        val step = if (intervalNs > 0L) intervalNs else gap
        val n = (gap.toDouble() / step).roundToLong().coerceAtLeast(1L)
        if (n > MAX_STEPS) return restart(rawNs)

        frameIndex += n
        lastRawNs = rawNs
        add(frameIndex.toDouble(), (rawNs - baseNs).toDouble())
        if (count < MIN_FIT) { lastOutNs = rawNs; return rawNs }

        // Least-squares line through (index, time).
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (k in 0 until count) { sx += idx[k]; sy += rel[k]; sxx += idx[k] * idx[k]; sxy += idx[k] * rel[k] }
        val c = count.toDouble()
        val den = c * sxx - sx * sx
        if (den <= 1e-9) { lastOutNs = rawNs; return rawNs }
        val slope = (c * sxy - sx * sy) / den
        val icpt = (sy - slope * sx) / c
        if (slope < MIN_INTERVAL_NS || slope > MAX_INTERVAL_NS) { lastOutNs = rawNs; return rawNs }
        intervalNs = slope.toLong()

        val fitted = baseNs + (icpt + slope * frameIndex).toLong()
        var out = if (abs(fitted - rawNs) <= slope / 2.0) fitted else rawNs
        if (out <= lastOutNs) out = lastOutNs + MIN_STEP_NS     // time never runs backwards
        lastOutNs = out
        return out
    }

    companion object {
        const val WINDOW = 30   // whole pulldown cycles (5, 6, 3 frames) fit exactly
        private const val MIN_FIT = 4
        private const val MAX_STEPS = 2L
        private const val MAX_GAP_NS = 1_000_000_000L
        private const val MIN_STEP_NS = 1_000_000L
        private const val MIN_INTERVAL_NS = 4_000_000.0
        private const val MAX_INTERVAL_NS = 200_000_000.0
    }
}
