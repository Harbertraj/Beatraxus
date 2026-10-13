package com.beatraxus.app.motionboost

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FPS Booster engine: takes the decoded video from ExoPlayer, builds the in-between frames on the GPU
 * and presents them at the chosen rate (45 / 60 / 90 / 120).
 *
 * Pipeline (all on one dedicated GL thread):
 *  1. ExoPlayer renders into [inputSurface] (a SurfaceTexture). Every decoded frame is copied into a
 *     slot (RGBA texture) together with a small luma copy (1/4 size) used for motion search.
 *  2. For the newest pair of frames a block-matching motion field is computed on the GPU (16px blocks,
 *     +-32px search, coarse-to-fine, zero-motion early-out).
 *  3. On every display vsync that is due, an interpolated frame is rendered for the matching point in
 *     time: both source frames are warped along the motion vectors and blended. Where the vectors are
 *     unreliable (scene cuts, occlusions, fast complex motion) it falls back to the nearest real frame,
 *     so errors show up as judder instead of ghosting.
 *
 * Source frames carry the time ExoPlayer wants them on screen (SurfaceTexture timestamp), so the output
 * stays in sync with audio apart from the small hold-back (see [MotionBoostTiming.holdNs]).
 */
class MotionBoostRenderer(
    private val outputSurface: Surface,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** Called on the GL thread once ExoPlayer can be pointed at the input surface. */
        fun onInputSurfaceReady(surface: Surface)

        /** Called on the GL thread about once per second. */
        fun onStats(sourceFps: Float, outputFps: Float)

        fun onError(message: String)
    }

    private class Slot(val full: Int, val luma: Int, val half: Int) {
        var tsNs: Long = 0L
    }

    private val thread = HandlerThread("MotionBoost-GL", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var released = false
    @Volatile private var targetFps = 60
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    @Volatile private var dirty = true

    // ---- EGL ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- GL objects ----
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var fbo = 0
    private var copyProg: Prog? = null
    private var lumaProg: Prog? = null
    private var halfProg: Prog? = null
    private var meProg: Prog? = null
    private var refineProg: Prog? = null
    private var smoothProg: Prog? = null
    private var interpProg: Prog? = null
    private var slots: Array<Slot> = emptyArray()
    private var mvTexture = 0   // final (smoothed) motion field, sampled with LINEAR filtering
    private var mvScratch = 0   // intermediate field between the passes

    // ---- sizes ----
    private var srcW = 0
    private var srcH = 0
    private var workW = 0
    private var workH = 0
    private var lumaW = 0
    private var lumaH = 0
    private var halfW = 0
    private var halfH = 0
    private var blocksW = 0
    private var blocksH = 0

    // ---- frame history ----
    private var cur: Slot? = null
    private var prev: Slot? = null
    private var mvFor: Slot? = null
    private var pendingFrame = false
    private val texMatrix = FloatArray(16)
    private var frameIntervalNs = 0L
    private var leadNs = 20_000_000L
    private var vsyncPeriodNs = 8_333_333L
    private var lastVsyncNs = 0L
    private var lastPresentNs = 0L

    // ---- redraw bookkeeping ----
    private var lastDrawnCur: Slot? = null
    private var lastDrawnPrev: Slot? = null
    private var lastDrawnAlpha = -1f

    // ---- stats ----
    private var statsWindowStartNs = 0L
    private var drawnInWindow = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released) return
            choreographer?.postFrameCallback(this)
            try {
                tick(frameTimeNanos)
            } catch (t: Throwable) {
                fail("render failed", t)
            }
        }
    }
    private var choreographer: Choreographer? = null

    // =====================================================================
    // Public API (any thread)
    // =====================================================================

    fun start() {
        handler.post { runGuarded("init failed") { initGl() } }
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(1, 240)
    }

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        dirty = true
    }

    fun setSourceSize(width: Int, height: Int) {
        handler.post { runGuarded("resize failed") { applySourceSize(width, height) } }
    }

    /**
     * Stops rendering and frees all GL / EGL resources. Waits (briefly) for the GL thread so the output
     * Surface is no longer in use when the caller lets it go (e.g. in surfaceDestroyed).
     */
    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        val done = CountDownLatch(1)
        val posted = handler.post {
            choreographer?.removeFrameCallback(frameCallback)
            releaseGl()
            thread.quitSafely()
            done.countDown()
        }
        if (posted && Looper.myLooper() !== thread.looper) {
            done.await(500, TimeUnit.MILLISECONDS)
        }
    }

    // =====================================================================
    // Setup
    // =====================================================================

    private fun initGl() {
        if (released) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "no ES3 EGL config"
        }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }
        EGL14.eglSwapInterval(eglDisplay, 1)

        copyProg = Prog(VERTEX, COPY_OES_FRAGMENT)
        lumaProg = Prog(VERTEX, LUMA_FRAGMENT)
        halfProg = Prog(VERTEX, HALF_FRAGMENT)
        meProg = Prog(VERTEX, MOTION_FRAGMENT)
        refineProg = Prog(VERTEX, REFINE_FRAGMENT)
        smoothProg = Prog(VERTEX, SMOOTH_FRAGMENT)
        interpProg = Prog(VERTEX, INTERP_FRAGMENT)

        val ids = IntArray(2)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]

        GLES30.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTexture)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        val s = Surface(st)
        inputSurface = s
        callbacks.onInputSurfaceReady(s)

        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
    }

    /** (Re)allocates the frame slots for a new source resolution and resets the frame history. */
    private fun applySourceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0 || eglContext == EGL14.EGL_NO_CONTEXT) return
        if (width == srcW && height == srcH && slots.isNotEmpty()) return
        srcW = width
        srcH = height

        val scale = min(1f, MAX_WORK_EDGE.toFloat() / max(width, height))
        workW = max(16, ((width * scale).toInt()) and 1.inv())
        workH = max(16, ((height * scale).toInt()) and 1.inv())
        // luma + motion grid: multiples of the 4-texel block so uv <-> block lookups are exact
        lumaW = max(BLOCK, ((workW / 4 + BLOCK - 1) / BLOCK) * BLOCK)
        lumaH = max(BLOCK, ((workH / 4 + BLOCK - 1) / BLOCK) * BLOCK)
        blocksW = lumaW / BLOCK
        blocksH = lumaH / BLOCK
        // half-resolution luma for the fine motion pass: a block is exactly 8x8 texels there
        halfW = lumaW * 2
        halfH = lumaH * 2

        freeSlots()
        slots = Array(SLOT_COUNT) {
            Slot(
                full = newTexture(workW, workH, GLES30.GL_RGBA8, GLES30.GL_LINEAR),
                luma = newTexture(lumaW, lumaH, GLES30.GL_R8, GLES30.GL_LINEAR),
                half = newTexture(halfW, halfH, GLES30.GL_R8, GLES30.GL_LINEAR)
            )
        }
        mvTexture = newTexture(blocksW, blocksH, GLES30.GL_RGBA8, GLES30.GL_LINEAR)
        mvScratch = newTexture(blocksW, blocksH, GLES30.GL_RGBA8, GLES30.GL_NEAREST)
        cur = null
        prev = null
        mvFor = null
        lastDrawnCur = null
        lastDrawnPrev = null
        dirty = true

        // A frame may have been waiting for the size.
        if (pendingFrame) {
            pendingFrame = false
            onFrameAvailable()
        }
    }

    // =====================================================================
    // Frame intake
    // =====================================================================

    private fun onFrameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        if (slots.isEmpty()) {
            // Size not known yet: leave the frame queued in the SurfaceTexture and pick it up later.
            pendingFrame = true
            return
        }
        try {
            st.updateTexImage()
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }
        st.getTransformMatrix(texMatrix)

        val now = System.nanoTime()
        var ts = st.timestamp
        // If the codec did not stamp a render time the value is not on our clock: use arrival time.
        if (abs(ts - now) > 2_000_000_000L) ts = now

        val newSlot = slots.first { it !== cur && it !== prev }
        copyFrameInto(newSlot)
        newSlot.tsNs = ts

        val c = cur
        if (c != null && MotionBoostTiming.isContinuous(c.tsNs, ts)) {
            val interval = ts - c.tsNs
            if (interval in MIN_FRAME_NS..MAX_FRAME_NS) {
                frameIntervalNs = if (frameIntervalNs == 0L) interval else (frameIntervalNs * 7 + interval) / 8
            }
            prev = c
        } else {
            prev = null // seek, pause, new clip: never interpolate across it
        }
        cur = newSlot
        val lead = (ts - now).coerceIn(-50_000_000L, 200_000_000L)
        leadNs = (leadNs * 7 + lead) / 8
    }

    private fun copyFrameInto(slot: Slot) {
        val copy = copyProg!!
        bindTarget(slot.full, workW, workH)
        copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(copy.loc("uTex"), 0)
        GLES30.glUniformMatrix4fv(copy.loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val luma = lumaProg!!
        bindTarget(slot.luma, lumaW, lumaH)
        luma.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.full)
        GLES30.glUniform1i(luma.loc("uSrc"), 0)
        GLES30.glUniform2f(luma.loc("uTexel"), 1f / workW, 1f / workH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val half = halfProg!!
        bindTarget(slot.half, halfW, halfH)
        half.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.full)
        GLES30.glUniform1i(half.loc("uSrc"), 0)
        GLES30.glUniform2f(half.loc("uTexel"), 1f / workW, 1f / workH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Three passes over the block grid:
     *  1. coarse search on 1/4-size luma (+-32 px reach, 4 px steps)  -> mvScratch
     *  2. fine search on 1/2-size luma around the coarse vector (2 px)  -> mvTexture
     *  3. 3x3 median filter removes outlier vectors (the cause of shimmering) -> mvScratch
     * The result is left in [mvScratch]; it is swapped into [mvTexture] so the interpolator reads it.
     */
    private fun estimateMotion(a: Slot, b: Slot) {
        // 1. coarse
        val me = meProg!!
        bindTarget(mvScratch, blocksW, blocksH)
        me.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.luma)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.luma)
        GLES30.glUniform1i(me.loc("uA"), 0)
        GLES30.glUniform1i(me.loc("uB"), 1)
        GLES30.glUniform2i(me.loc("uLumaSize"), lumaW, lumaH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 2. refine
        val refine = refineProg!!
        bindTarget(mvTexture, blocksW, blocksH)
        refine.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.half)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.half)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvScratch)
        GLES30.glUniform1i(refine.loc("uA"), 0)
        GLES30.glUniform1i(refine.loc("uB"), 1)
        GLES30.glUniform1i(refine.loc("uCoarse"), 2)
        GLES30.glUniform2i(refine.loc("uHalfSize"), halfW, halfH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 3. smooth (reads mvTexture, writes mvScratch)
        val smooth = smoothProg!!
        bindTarget(mvScratch, blocksW, blocksH)
        smooth.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexture)
        GLES30.glUniform1i(smooth.loc("uMV"), 0)
        GLES30.glUniform2i(smooth.loc("uBlocks"), blocksW, blocksH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // keep the sampled (LINEAR) texture in mvTexture, scratch is the NEAREST one
        val t = mvTexture
        mvTexture = mvScratch
        mvScratch = t
        setMvFilters()
        mvFor = b
    }

    private fun setMvFilters() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvScratch)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    // =====================================================================
    // Presentation (runs on every vsync)
    // =====================================================================

    private fun tick(vsyncNs: Long) {
        if (lastVsyncNs != 0L) {
            val period = vsyncNs - lastVsyncNs
            if (period in 3_000_000L..40_000_000L) vsyncPeriodNs = (vsyncPeriodNs * 7 + period) / 8
        }
        lastVsyncNs = vsyncNs

        val c = cur ?: return
        val fps = targetFps
        // Render only when a new output frame is due (e.g. every 2nd vsync for 60 fps on 120 Hz).
        if (vsyncNs - lastPresentNs < (1_000_000_000.0 / fps * 0.8).toLong()) return

        val p = prev
        val sourceFps = if (frameIntervalNs > 0) 1_000_000_000f / frameIntervalNs else 0f
        val interpolate = p != null && !MotionBoostTiming.shouldBypass(sourceFps, fps)

        var alpha = 1f
        if (interpolate) {
            val hold = MotionBoostTiming.holdNs(frameIntervalNs, leadNs, vsyncPeriodNs)
            // The buffer we draw now reaches the screen about one vsync from now.
            val contentTime = vsyncNs + vsyncPeriodNs - hold
            alpha = MotionBoostTiming.alpha(contentTime, p!!.tsNs, c.tsNs)
        }

        if (!dirty && c === lastDrawnCur && p === lastDrawnPrev && alpha == lastDrawnAlpha) {
            reportStats(vsyncNs, sourceFps)
            return // paused / nothing new: leave the last presented frame on screen
        }

        if (interpolate && mvFor !== c) estimateMotion(p!!, c)

        drawOutput(a = if (interpolate) p!! else c, b = c, alpha = alpha, warp = interpolate)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            fail("eglSwapBuffers failed (0x${Integer.toHexString(EGL14.eglGetError())})", null)
            return
        }
        lastPresentNs = vsyncNs
        lastDrawnCur = c
        lastDrawnPrev = p
        lastDrawnAlpha = alpha
        dirty = false
        drawnInWindow++
        reportStats(vsyncNs, sourceFps)
    }

    private fun drawOutput(a: Slot, b: Slot, alpha: Float, warp: Boolean) {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, max(1, w[0]), max(1, h[0]))

        val ip = interpProg!!
        ip.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.full)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.full)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexture)
        GLES30.glUniform1i(ip.loc("uA"), 0)
        GLES30.glUniform1i(ip.loc("uB"), 1)
        GLES30.glUniform1i(ip.loc("uMV"), 2)
        GLES30.glUniform1f(ip.loc("uAlpha"), alpha)
        GLES30.glUniform1i(ip.loc("uWarp"), if (warp) 1 else 0)
        GLES30.glUniform2f(ip.loc("uInvHalf"), 1f / halfW, 1f / halfH)
        GLES30.glUniform3f(ip.loc("uGrade"), brightness, contrast, saturation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun reportStats(nowNs: Long, sourceFps: Float) {
        if (statsWindowStartNs == 0L) statsWindowStartNs = nowNs
        val elapsed = nowNs - statsWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            val out = drawnInWindow * 1_000_000_000f / elapsed
            callbacks.onStats(sourceFps, out)
            statsWindowStartNs = nowNs
            drawnInWindow = 0
        }
    }

    // =====================================================================
    // GL helpers
    // =====================================================================

    private fun bindTarget(texture: Int, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0
        )
        GLES30.glViewport(0, 0, width, height)
    }

    private fun newTexture(width: Int, height: Int, internalFormat: Int, filter: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun freeSlots() {
        if (slots.isNotEmpty()) {
            val ids = IntArray(slots.size * 3 + 2)
            slots.forEachIndexed { i, s -> ids[i * 3] = s.full; ids[i * 3 + 1] = s.luma; ids[i * 3 + 2] = s.half }
            ids[ids.size - 2] = mvTexture
            ids[ids.size - 1] = mvScratch
            GLES30.glDeleteTextures(ids.size, ids, 0)
        }
        slots = emptyArray()
        mvTexture = 0
        mvScratch = 0
    }

    private fun releaseGl() {
        runCatching {
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            freeSlots()
            if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            copyProg?.delete(); lumaProg?.delete(); halfProg?.delete(); meProg?.delete()
            refineProg?.delete(); smoothProg?.delete(); interpProg?.delete()
        }
        surfaceTexture = null
        inputSurface = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private inline fun runGuarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail(what, t)
        }
    }

    private fun fail(what: String, t: Throwable?) {
        if (released) return
        Log.e(TAG, what, t)
        released = true
        choreographer?.removeFrameCallback(frameCallback)
        callbacks.onError("$what${t?.message?.let { ": $it" } ?: ""}")
        handler.post {
            releaseGl()
            thread.quitSafely()
        }
    }

    private class Prog(vertex: String, fragment: String) {
        private val id: Int
        private val locations = HashMap<String, Int>()

        init {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, vs)
            GLES30.glAttachShader(id, fs)
            GLES30.glLinkProgram(id)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { "link: " + GLES30.glGetProgramInfoLog(id) }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
        }

        fun use() = GLES30.glUseProgram(id)
        fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
        fun delete() = GLES30.glDeleteProgram(id)

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { "shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
    }

    companion object {
        private const val TAG = "MotionBoost"
        private const val SLOT_COUNT = 3
        private const val BLOCK = 4 // motion block = 4x4 luma texels = 16x16 working pixels
        private const val MAX_WORK_EDGE = 1920
        private const val MIN_FRAME_NS = 4_000_000L
        private const val MAX_FRAME_NS = 200_000_000L

        /** Full-screen triangle strip generated from gl_VertexID (no vertex buffers needed). */
        private const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val COPY_OES_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
in vec2 vUv;
out vec4 o;
void main() {
  vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
  o = vec4(texture(uTex, uv).rgb, 1.0);
}
"""

        /** 4x4 box average of luma: four bilinear taps at +-1 source pixel. */
        private const val LUMA_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 o;
void main() {
  vec3 c = texture(uSrc, vUv + vec2(-1.0, -1.0) * uTexel).rgb
         + texture(uSrc, vUv + vec2( 1.0, -1.0) * uTexel).rgb
         + texture(uSrc, vUv + vec2(-1.0,  1.0) * uTexel).rgb
         + texture(uSrc, vUv + vec2( 1.0,  1.0) * uTexel).rgb;
  o = vec4(dot(c * 0.25, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
"""

        /** Half-size luma (slightly blurred, which calms sensor noise before matching). */
        private const val HALF_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 o;
void main() {
  vec3 c = texture(uSrc, vUv + vec2(-0.5, -0.5) * uTexel).rgb
         + texture(uSrc, vUv + vec2( 0.5, -0.5) * uTexel).rgb
         + texture(uSrc, vUv + vec2(-0.5,  0.5) * uTexel).rgb
         + texture(uSrc, vUv + vec2( 0.5,  0.5) * uTexel).rgb;
  o = vec4(dot(c * 0.25, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
"""

        /**
         * Pass 1 - coarse search. One fragment per 4x4 luma-texel block (16 px). Output r,g = vector in
         * luma texels encoded as (d * 8 + 128) / 255. A vector is only used if it clearly beats "no motion".
         */
        private const val MOTION_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uA;
uniform sampler2D uB;
uniform ivec2 uLumaSize;
out vec4 o;

float a[16];

float fetchB(ivec2 p) {
  return texelFetch(uB, clamp(p, ivec2(0), uLumaSize - 1), 0).r;
}

float sad(ivec2 org, ivec2 d) {
  float s = 0.0;
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      s += abs(a[j * 4 + i] - fetchB(org + ivec2(i, j) + d));
    }
  }
  return s * 0.0625;
}

void main() {
  ivec2 org = ivec2(gl_FragCoord.xy) * 4;
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      a[j * 4 + i] = texelFetch(uA, clamp(org + ivec2(i, j), ivec2(0), uLumaSize - 1), 0).r;
    }
  }
  ivec2 best = ivec2(0);
  float zero = sad(org, best);
  float amb = 0.0;                              // 1 = several very different vectors fit equally well
  if (zero > 0.015) {
    float bestCost = zero * 0.85 - 0.002;     // must clearly beat zero motion
    float costs[81];
    for (int dy = -8; dy <= 8; dy += 2) {
      for (int dx = -8; dx <= 8; dx += 2) {
        ivec2 d = ivec2(dx, dy);
        float c = sad(org, d) + 0.0015 * float(abs(dx) + abs(dy));
        costs[((dy + 8) / 2) * 9 + (dx + 8) / 2] = c;
        if ((dx != 0 || dy != 0) && c < bestCost) { bestCost = c; best = d; }
      }
    }
    ivec2 center = best;
    if (center != ivec2(0)) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          if (dx == 0 && dy == 0) continue;
          ivec2 d = center + ivec2(dx, dy);
          float c = sad(org, d) + 0.0015 * float(abs(d.x) + abs(d.y));
          if (c < bestCost) { bestCost = c; best = d; }
        }
      }
      // Repeating patterns (window blinds, fences, grids, text): a vector one period away matches
      // almost as well as the true one. If another, clearly different vector is nearly as good, the
      // match is a guess - flag it so the picture falls back to the real frame there.
      float farCost = 1.0e9;
      for (int iy = 0; iy < 9; iy++) {
        for (int ix = 0; ix < 9; ix++) {
          ivec2 d = ivec2(ix * 2 - 8, iy * 2 - 8);
          if (max(abs(d.x - best.x), abs(d.y - best.y)) >= 4) farCost = min(farCost, costs[iy * 9 + ix]);
        }
      }
      if (farCost < bestCost * 1.10 + 0.0015) amb = 1.0;
    }
  }
  o = vec4((vec2(best) * 8.0 + 128.0) / 255.0, amb, 1.0);
}
"""

        /**
         * Pass 2 - fine search on half-size luma (8x8 texels per block, 2 px accuracy) around the coarse
         * vector. Output r,g = vector in half-size texels encoded as (d * 4 + 128) / 255, b = residual * 2.
         */
        private const val REFINE_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uCoarse;
uniform ivec2 uHalfSize;
out vec4 o;

float a[64];

float fetchB(ivec2 p) {
  return texelFetch(uB, clamp(p, ivec2(0), uHalfSize - 1), 0).r;
}

float sad(ivec2 org, ivec2 d) {
  float s = 0.0;
  for (int j = 0; j < 8; j++) {
    for (int i = 0; i < 8; i++) {
      s += abs(a[j * 8 + i] - fetchB(org + ivec2(i, j) + d));
    }
  }
  return s * 0.015625;
}

void main() {
  ivec2 blk = ivec2(gl_FragCoord.xy);
  ivec2 org = blk * 8;
  for (int j = 0; j < 8; j++) {
    for (int i = 0; i < 8; i++) {
      a[j * 8 + i] = texelFetch(uA, clamp(org + ivec2(i, j), ivec2(0), uHalfSize - 1), 0).r;
    }
  }
  vec4 coarseT = texelFetch(uCoarse, blk, 0);
  vec2 coarse = (coarseT.rg * 255.0 - 128.0) / 8.0;                        // luma texels
  ivec2 start = ivec2(floor(coarse * 2.0 + 0.5));                          // half-size texels

  ivec2 best = ivec2(0);
  float zero = sad(org, best);
  float bestSad = zero;
  if (zero > 0.010 && start != ivec2(0)) {
    float bestCost = zero * 0.85 - 0.002;
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -2; dx <= 2; dx++) {
        ivec2 d = start + ivec2(dx, dy);
        float c = sad(org, d) + 0.0008 * float(abs(d.x) + abs(d.y));
        if (c < bestCost) { bestCost = c; best = d; }
      }
    }
    if (best != ivec2(0)) bestSad = sad(org, best);
  }
  float res = clamp(bestSad * 2.0, 0.0, 1.0);
  if (coarseT.b > 0.5) res = max(res, 0.6);     // ambiguous (periodic) match: treat as unreliable
  o = vec4((vec2(best) * 4.0 + 128.0) / 255.0, res, 1.0);
}
"""

        /** Pass 3 - 3x3 component-wise median of the vectors; residual = mean of own and worst neighbour. */
        private const val SMOOTH_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uMV;
uniform ivec2 uBlocks;
out vec4 o;

void main() {
  ivec2 blk = ivec2(gl_FragCoord.xy);
  float vx[9];
  float vy[9];
  float worst = 0.0;
  float own = 0.0;
  int k = 0;
  for (int dy = -1; dy <= 1; dy++) {
    for (int dx = -1; dx <= 1; dx++) {
      vec4 t = texelFetch(uMV, clamp(blk + ivec2(dx, dy), ivec2(0), uBlocks - 1), 0);
      vx[k] = t.r * 255.0;
      vy[k] = t.g * 255.0;
      worst = max(worst, t.b);
      if (dx == 0 && dy == 0) own = t.b;
      k++;
    }
  }
  for (int i = 0; i < 5; i++) {
    int mx = i;
    int my = i;
    for (int j = i + 1; j < 9; j++) {
      if (vx[j] < vx[mx]) mx = j;
      if (vy[j] < vy[my]) my = j;
    }
    float tx = vx[i]; vx[i] = vx[mx]; vx[mx] = tx;
    float ty = vy[i]; vy[i] = vy[my]; vy[my] = ty;
  }
  // Vector disagreement around this block (half-size texels). A clean field is locally uniform;
  // a noisy one (periodic texture, fast motion past the search range) is not.
  float dev = 0.0;
  for (int i = 0; i < 9; i++) dev += abs(vx[i] - vx[4]) + abs(vy[i] - vy[4]);
  dev = dev / 9.0 / 4.0;
  float noisy = 0.4 * smoothstep(3.0, 8.0, dev);
  o = vec4(vx[4] / 255.0, vy[4] / 255.0, clamp(0.5 * (own + worst) + noisy, 0.0, 1.0), 1.0);
}
"""

        private const val INTERP_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uMV;      // LINEAR filtered: vectors vary smoothly across block borders
uniform float uAlpha;
uniform int uWarp;
uniform vec2 uInvHalf;      // 1 / half-size luma dimensions (vector unit = half-size texel)
uniform vec3 uGrade;        // brightness, contrast, saturation
in vec2 vUv;
out vec4 o;

vec2 decode(vec2 e) { return (e * 255.0 - 128.0) / 4.0; }

void main() {
  vec3 rgb;
  if (uWarp == 0) {
    rgb = texture(uB, vUv).rgb;
  } else {
    // motion at this pixel, then re-read it where the object was in frame A
    vec2 d0 = decode(texture(uMV, vUv).rg);
    vec4 m = texture(uMV, vUv - uAlpha * d0 * uInvHalf);
    vec2 d = decode(m.rg);
    float residual = m.b * 0.5;

    vec3 ca = texture(uA, vUv - uAlpha * d * uInvHalf).rgb;
    vec3 cb = texture(uB, vUv + (1.0 - uAlpha) * d * uInvHalf).rgb;
    vec3 warped = mix(ca, cb, uAlpha);

    // Soft fallback: where the match is poor, drift towards a plain cross-fade of the unmoved frames
    // (smooth, no per-pixel switching) rather than hard-switching between frames.
    vec3 pa = texture(uA, vUv).rgb;
    vec3 pb = texture(uB, vUv).rgb;
    vec3 plain = mix(pa, pb, uAlpha);
    // Very bad match (scene cut): show the nearest real frame instead of a dissolve. The choice is the
    // same for the whole picture, only the blend amount varies smoothly with the block residual.
    vec3 nearest = (uAlpha < 0.5) ? pa : pb;
    // Where the warp is not trusted, show the real frame (identical to the cross-fade in static areas,
    // but never a ghosted double image on moving ones).
    plain = nearest;
    // Trust the warp only when every test agrees:
    //  - block match error is low,
    //  - the two warped samples really show the same picture (full-resolution check),
    //  - the vector is the same where the pixel came from (no tearing at occlusions / streaks),
    //  - the vector is not at the edge of the search range (true motion may be even faster).
    float conf = (1.0 - smoothstep(0.035, 0.09, residual))
               * (1.0 - smoothstep(0.10, 0.26, length(ca - cb)))
               * (1.0 - smoothstep(1.5, 4.0, length(d - d0)))
               * (1.0 - smoothstep(12.0, 17.0, max(abs(d.x), abs(d.y))));
    rgb = mix(plain, warped, conf);
  }
  rgb += uGrade.x;
  rgb = (rgb - 0.5) * uGrade.y + 0.5;
  float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(luma), rgb, uGrade.z);
  o = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""
    }
}
