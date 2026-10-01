package com.beatraxus.app.perf

import android.util.Log
import android.view.Choreographer

class FrameJankMonitor(
    private val tag: String,
    expectedFrameIntervalMs: Long = 16L
) {
    private var started = false
    private var lastFrameNanos = 0L
    private var lastLogTimeMs = 0L

    // Jank threshold is ~2x expected frame interval (e.g. > 35ms for 60Hz display)
    private val jankThresholdMs: Long = (expectedFrameIntervalMs * 2L).coerceAtLeast(35L)

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started) return
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L
                val now = System.currentTimeMillis()
                if (deltaMs > jankThresholdMs && (now - lastLogTimeMs >= 1000L)) {
                    lastLogTimeMs = now
                    Log.w(tag, "Dropped/janky frame detected: ${deltaMs}ms (threshold: ${jankThresholdMs}ms)")
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (started) return
        started = true
        lastFrameNanos = 0L
        lastLogTimeMs = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!started) return
        started = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
