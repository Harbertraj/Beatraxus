package com.beatflowy.app.perf

import android.util.Log
import android.view.Choreographer

class FrameJankMonitor(
    private val tag: String,
    private val jankThresholdMs: Long = 24L
) {
    private var started = false
    private var lastFrameNanos = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started) return
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L
                if (deltaMs > jankThresholdMs) {
                    Log.w(tag, "Dropped/janky frame detected: ${deltaMs}ms")
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
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!started) return
        started = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
