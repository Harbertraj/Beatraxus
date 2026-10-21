package com.beatraxus.app.fpsbooster

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

/**
 * "Use everything the device offers" helpers shared by both boost engines (Optical flow and Classic).
 *
 * Interpolation is a hard real-time job: every output frame has to be ready at a vsync, and the work is tiny
 * per frame but bursty (a motion search every source frame, a warp every output frame). Android's CPU / GPU
 * governors are tuned for steady load and ramp the clocks up only after they have seen the load, which is
 * exactly when a vsync has already been missed. Two switches fix that:
 *
 *  1. A high-priority GPU context (EGL_IMG_context_priority). When the UI, the decoder or another app also
 *     want the GPU, our draw is scheduled first instead of waiting behind them.
 *  2. An ADPF hint session (PerformanceHintManager, API 31+). It tells the system "this thread must finish
 *     its work in X ms" and reports how long it really took, so the governor raises CPU / GPU clocks *before*
 *     a frame is missed and keeps them there. On API 35+ it also states that power efficiency must not win
 *     over speed for this thread.
 *
 * Both degrade to a no-op when the device does not offer them; nothing here can make playback fail.
 */
object PerfHints {
    private const val TAG = "FpsBoosterPerf"
    private const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private const val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101

    /** Share of the output frame interval the system should aim to be done in (the rest is headroom). */
    const val TARGET_SHARE = 0.7f

    /** An OpenGL ES 3 context, with HIGH GPU priority when the driver supports it. Returns EGL_NO_CONTEXT on failure. */
    fun createContext(display: EGLDisplay, config: EGLConfig?): EGLContext {
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        if (extensions.contains("EGL_IMG_context_priority")) {
            val high = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG,
                    EGL14.EGL_NONE
                ), 0
            )
            if (high != EGL14.EGL_NO_CONTEXT) {
                val granted = IntArray(1)
                val ok = EGL14.eglQueryContext(display, high, EGL_CONTEXT_PRIORITY_LEVEL_IMG, granted, 0)
                Log.i(TAG, "GPU context priority " + if (ok && granted[0] == EGL_CONTEXT_PRIORITY_HIGH_IMG) "HIGH" else "default")
                return high
            }
            Log.w(TAG, "high-priority context refused, using a normal one")
        }
        return EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
    }
}

/**
 * One ADPF hint session for one thread (see [PerfHints]). Every call is a no-op when the device has no
 * PerformanceHintManager; a failing call permanently disables the session instead of throwing.
 */
class PerfSession private constructor(private var session: PerformanceHintManager.Session?) {
    private var lastTargetNs = 0L

    val active: Boolean get() = session != null

    /** Sets the duration the thread's per-frame work should fit in. Cheap to call every frame. */
    fun updateTarget(targetNs: Long) {
        val s = session ?: return
        if (targetNs <= 0L || targetNs == lastTargetNs) return
        try { s.updateTargetWorkDuration(targetNs); lastTargetNs = targetNs } catch (t: Throwable) { disable(t) }
    }

    /** Reports how long the last frame's work really took. */
    fun report(actualNs: Long) {
        val s = session ?: return
        if (actualNs <= 0L) return
        try { s.reportActualWorkDuration(actualNs) } catch (t: Throwable) { disable(t) }
    }

    fun close() {
        val s = session ?: return
        session = null
        runCatching { s.close() }
    }

    private fun disable(t: Throwable) {
        Log.w("FpsBoosterPerf", "performance hint session disabled", t)
        close()
    }

    companion object {
        /** Creates a session for the thread [threadId]; the result is inactive (never null) when unsupported. */
        fun create(context: Context, threadId: Int, targetNs: Long): PerfSession {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || targetNs <= 0L) return PerfSession(null)
            return try {
                val manager = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                val s = manager?.createHintSession(intArrayOf(threadId), targetNs)
                if (s != null && Build.VERSION.SDK_INT >= 35) runCatching { s.setPreferPowerEfficiency(false) }
                Log.i("FpsBoosterPerf", "ADPF hint session " + if (s != null) "active" else "not available")
                PerfSession(s).also { it.lastTargetNs = targetNs }
            } catch (t: Throwable) {
                Log.w("FpsBoosterPerf", "ADPF unavailable", t)
                PerfSession(null)
            }
        }
    }
}
