package com.beatraxus.app.fpsbooster

import android.content.Context
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
import com.beatraxus.app.motionboost.MotionBoostTiming
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Optical-flow FPS Booster engine.
 *
 * ExoPlayer renders into [OpticalFlowRenderer]'s input Surface (a SurfaceTexture); this class owns one GL
 * thread that, for every decoded frame pair, estimates dense motion in BOTH directions with a hierarchical
 * (4-level luma pyramid) block search, checks the two fields against each other to find occlusions, and then
 * on every display vsync that is due draws an interpolated frame for the matching point in time.
 *
 * Shaders live in the assets/fpsbooster folder (.frag files) and are the same files the desktop harness
 * (tools/fpsbooster_harness) validates against ground truth. The orchestration below mirrors
 * tools/fpsbooster_harness/new_engine.py: keep the two in sync.
 *
 * Resource policy
 *  - Exactly [SLOT_COUNT] frame slots exist (bounded queue). A new decoded frame overwrites the oldest slot
 *    that is not part of the current pair, so a slow GPU drops source frames instead of queueing unboundedly.
 *  - All textures are allocated once per (source size, quality level) and reused for every frame.
 *  - A seek, pause, long gap or new clip breaks the pair ([MotionBoostTiming.isContinuous]); nothing is
 *    interpolated across it and the stale flow is discarded.
 *  - [release] stops the GL thread and frees everything; it waits briefly so the caller can destroy the
 *    output Surface safely. Any GL failure is reported once through [Callbacks.onError] and the owner falls
 *    back to standard playback.
 */
class OpticalFlowRenderer(
    private val outputSurface: Surface,
    private val callbacks: Callbacks,
    private val context: Context
) {

    interface Callbacks {
        /** GL thread: ExoPlayer can now be pointed at [surface]. */
        fun onInputSurfaceReady(surface: Surface)

        /** GL thread, about once per second. [addedDelayMs] is how far video is held behind the source clock. */
        fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float)

        fun onError(reason: FallbackReason, message: String)
    }

    private class Slot(val full: Int, val luma: IntArray) {
        var tsNs: Long = 0L
    }

    private val thread = HandlerThread("FpsBooster-GL", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var released = false
    @Volatile private var targetFps = 60
    @Volatile private var level: QualityLevel = AdaptiveQualityController.LADDER[1]
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    @Volatile private var dirty = true
    private var appliedLevel: QualityLevel? = null

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
    private var downProg: Prog? = null
    private var searchProg: Prog? = null
    private var consProg: Prog? = null
    private var cutProg: Prog? = null
    private var interpProg: Prog? = null

    private var slots: Array<Slot> = emptyArray()
    private var flowTex: Array<Array<IntArray>> = emptyArray()   // [direction 0=fw 1=bw][level] -> ping/pong
    private var finalFw = 0
    private var finalBw = 0
    private var cutTex = 0

    // ---- sizes ----
    private var srcW = 0
    private var srcH = 0
    private var frameW = 0
    private var frameH = 0
    private var levelW = IntArray(0)
    private var levelH = IntArray(0)
    private var gridW = IntArray(0)
    private var gridH = IntArray(0)

    // ---- frame history ----
    private var cur: Slot? = null
    private var prev: Slot? = null
    private var flowFor: Slot? = null
    private var pendingFrame = false
    private val texMatrix = FloatArray(16)
    private var frameIntervalNs = 0L
    private var leadNs = 20_000_000L
    private var vsyncPeriodNs = 8_333_333L
    private var lastVsyncNs = 0L
    private var nextDueNs = 0L
    private var lastDrawnCur: Slot? = null
    private var lastDrawnPrev: Slot? = null
    private var lastDrawnAlpha = -1f
    private var lastHoldNs = 0L

    // ---- stats ----
    private val acc = StatsAccumulator()
    private var estimateCount = 0

    private var choreographer: Choreographer? = null
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released) return
            choreographer?.postFrameCallback(this)
            try {
                tick(frameTimeNanos)
            } catch (t: Throwable) {
                fail(FallbackReason.GPU_ERROR, "render failed", t)
            }
        }
    }

    // =====================================================================
    // Public API (any thread)
    // =====================================================================

    fun start() {
        handler.post { runGuarded("init failed") { initGl() } }
    }

    fun setTargetFps(fps: Int) { targetFps = fps.coerceIn(1, 240) }

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        dirty = true
    }

    /** Changes the processing resolution / search settings (reallocates GL resources on the GL thread). */
    fun setLevel(newLevel: QualityLevel) {
        if (newLevel == level && appliedLevel == newLevel) return
        level = newLevel
        handler.post { runGuarded("resize failed") { if (srcW > 0 && srcH > 0) applySourceSize(srcW, srcH) } }
    }

    fun setSourceSize(width: Int, height: Int) {
        handler.post { runGuarded("resize failed") { applySourceSize(width, height) } }
    }

    /** Stops rendering and frees all GL / EGL resources; waits briefly for the GL thread. */
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
        if (posted && Looper.myLooper() !== thread.looper) done.await(500, TimeUnit.MILLISECONDS)
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
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }
        EGL14.eglSwapInterval(eglDisplay, 1)

        check(GlCapabilityProbe.hasFloatRenderTargets(GLES30.glGetString(GLES30.GL_EXTENSIONS))) {
            "GPU cannot render to float textures"
        }

        copyProg = Prog(VERTEX, COPY_OES_FRAGMENT)
        lumaProg = Prog(VERTEX, asset("luma_work.frag"))
        downProg = Prog(VERTEX, asset("pyramid_down.frag"))
        searchProg = Prog(VERTEX, asset("flow_search.frag"))
        consProg = Prog(VERTEX, asset("flow_consistency.frag"))
        cutProg = Prog(VERTEX, asset("cut_detect.frag"))
        interpProg = Prog(VERTEX, asset("flow_interp.frag"))

        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        for ((p, v) in listOf(
            GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
            GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE
        )) GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p, v)

        val st = SurfaceTexture(oesTexture)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
        callbacks.onInputSurfaceReady(inputSurface!!)

        runCatching { Process.setThreadPriority(thread.threadId, Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun asset(name: String): String =
        context.assets.open("fpsbooster/$name").bufferedReader().use { it.readText() }

    /** (Re)allocates all per-size resources for [width]x[height] at the current [level]; resets frame history. */
    private fun applySourceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0 || eglContext == EGL14.EGL_NO_CONTEXT) return
        val lv = level
        if (width == srcW && height == srcH && slots.isNotEmpty() && appliedLevel == lv) return
        srcW = width
        srcH = height
        appliedLevel = lv
        freeResources()

        val fs = min(1f, lv.frameEdge.toFloat() / max(width, height))
        frameW = max(16, (width * fs).toInt() and 1.inv())
        frameH = max(16, (height * fs).toInt() and 1.inv())
        val ws = min(1f, lv.workEdge.toFloat() / max(width, height))
        val wW = max(32, (width * ws).toInt() and 1.inv())
        val wH = max(32, (height * ws).toInt() and 1.inv())

        val lw = ArrayList<Int>().apply { add(wW) }
        val lh = ArrayList<Int>().apply { add(wH) }
        while (max(lw.last(), lh.last()) > COARSEST_EDGE && lw.size < MAX_LEVELS) {
            lw.add(max(8, (lw.last() + 1) / 2))
            lh.add(max(8, (lh.last() + 1) / 2))
        }
        levelW = lw.toIntArray()
        levelH = lh.toIntArray()
        gridW = IntArray(levelW.size) { (levelW[it] + PITCH - 1) / PITCH }
        gridH = IntArray(levelH.size) { (levelH[it] + PITCH - 1) / PITCH }
        val n = levelW.size

        slots = Array(SLOT_COUNT) {
            Slot(
                full = newTexture(frameW, frameH, GLES30.GL_RGBA8),
                luma = IntArray(n) { l -> newTexture(levelW[l], levelH[l], GLES30.GL_R8) }
            )
        }
        flowTex = Array(2) { Array(n) { l -> IntArray(2) { newTexture(gridW[l], gridH[l], GLES30.GL_RGBA16F) } } }
        finalFw = newTexture(gridW[0], gridH[0], GLES30.GL_RGBA16F)
        finalBw = newTexture(gridW[0], gridH[0], GLES30.GL_RGBA16F)
        cutTex = newTexture(1, 1, GLES30.GL_RGBA16F)

        // Some GPUs advertise the extension but still reject RGBA16F attachments: verify once, loudly.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, finalFw, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "RGBA16F framebuffer incomplete"
        }

        cur = null; prev = null; flowFor = null
        lastDrawnCur = null; lastDrawnPrev = null
        dirty = true
        acc.reset()
        if (pendingFrame) { pendingFrame = false; onFrameAvailable() }
    }

    // =====================================================================
    // Frame intake
    // =====================================================================

    private fun onFrameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        if (slots.isEmpty()) { pendingFrame = true; return }   // size not known yet: keep the frame queued in the SurfaceTexture
        try {
            st.updateTexImage()
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }
        st.getTransformMatrix(texMatrix)

        val now = System.nanoTime()
        var ts = st.timestamp
        // A codec that did not stamp a render time gives a value that is not on our clock: use arrival time.
        if (abs(ts - now) > 2_000_000_000L) ts = now

        val slot = slots.first { it !== cur && it !== prev }
        uploadFrame(slot)
        slot.tsNs = ts
        acc.onSourceFrame()

        val c = cur
        if (c != null && MotionBoostTiming.isContinuous(c.tsNs, ts)) {
            val interval = ts - c.tsNs
            if (interval in MIN_FRAME_NS..MAX_FRAME_NS) {
                frameIntervalNs = if (frameIntervalNs == 0L) interval else (frameIntervalNs * 7 + interval) / 8
            }
            prev = c
        } else {
            prev = null          // seek / pause / new clip: never interpolate across it
            flowFor = null
        }
        cur = slot
        leadNs = (leadNs * 7 + (ts - now).coerceIn(-50_000_000L, 200_000_000L)) / 8
    }

    /** OES frame -> slot.full (RGBA8), then luma level 0 at work size, then the pyramid. */
    private fun uploadFrame(slot: Slot) {
        val copy = copyProg!!
        bindTarget(slot.full, frameW, frameH)
        copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(copy.loc("uTex"), 0)
        GLES30.glUniformMatrix4fv(copy.loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val luma = lumaProg!!
        bindTarget(slot.luma[0], levelW[0], levelH[0])
        luma.use()
        tex(0, luma, "uSrc", slot.full)
        GLES30.glUniform2f(luma.loc("uTexel"), 1f / levelW[0], 1f / levelH[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val down = downProg!!
        for (l in 1 until levelW.size) {
            bindTarget(slot.luma[l], levelW[l], levelH[l])
            down.use()
            tex(0, down, "uSrc", slot.luma[l - 1])
            GLES30.glUniform2i(down.loc("uSrcSize"), levelW[l - 1], levelH[l - 1])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // =====================================================================
    // Motion estimation
    // =====================================================================

    private fun search(ref: Int, tgt: Int, parent: Int, dst: Int, lvl: Int, mode: Int, range: Int, subpel: Boolean, parentLvl: Int) {
        val p = searchProg!!
        bindTarget(dst, gridW[lvl], gridH[lvl])
        p.use()
        tex(0, p, "uRef", ref); tex(1, p, "uTgt", tgt); tex(2, p, "uParent", parent)
        val pw = if (parentLvl >= 0) levelW[parentLvl] else levelW[lvl]
        val ph = if (parentLvl >= 0) levelH[parentLvl] else levelH[lvl]
        val pgw = if (parentLvl >= 0) gridW[parentLvl] else gridW[lvl]
        val pgh = if (parentLvl >= 0) gridH[parentLvl] else gridH[lvl]
        GLES30.glUniform2i(p.loc("uSize"), levelW[lvl], levelH[lvl])
        GLES30.glUniform2i(p.loc("uGrid"), gridW[lvl], gridH[lvl])
        GLES30.glUniform2i(p.loc("uParentGrid"), pgw, pgh)
        GLES30.glUniform2f(p.loc("uScale"), levelW[lvl].toFloat() / pw, levelH[lvl].toFloat() / ph)
        GLES30.glUniform1i(p.loc("uMode"), mode)
        GLES30.glUniform1i(p.loc("uRange"), range)
        GLES30.glUniform1i(p.loc("uSubpel"), if (subpel) 1 else 0)
        GLES30.glUniform1f(p.loc("uLambda"), LAMBDA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Hierarchical search from the coarsest level down; returns the texture holding the level-0 result. */
    private fun flowDirection(dir: Int, ref: Slot, tgt: Slot): Int {
        val n = levelW.size
        var result = 0
        for (lvl in n - 1 downTo 0) {
            val a = flowTex[dir][lvl][0]
            val b = flowTex[dir][lvl][1]
            val sub = lvl == 0 && level.subpixel
            if (lvl == n - 1) {
                // mode 2 ignores the parent; bind the other texture so it is never sampler and target at once
                search(ref.luma[lvl], tgt.luma[lvl], b, a, lvl, 2, level.searchRange, false, -1)
            } else {
                search(ref.luma[lvl], tgt.luma[lvl], result, a, lvl, 0, 0, false, lvl + 1)
            }
            search(ref.luma[lvl], tgt.luma[lvl], a, b, lvl, 1, 0, sub, -1)   // propagation, ping-pong a -> b
            result = b
        }
        return result
    }

    private fun estimateMotion(a: Slot, b: Slot) {
        val fw = flowDirection(0, a, b)
        val bw = flowDirection(1, b, a)
        val cons = consProg!!
        for ((dst, self, other) in listOf(Triple(finalFw, fw, bw), Triple(finalBw, bw, fw))) {
            bindTarget(dst, gridW[0], gridH[0])
            cons.use()
            tex(0, cons, "uSelf", self); tex(1, cons, "uOther", other)
            GLES30.glUniform2i(cons.loc("uGrid"), gridW[0], gridH[0])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        val n = levelW.size
        val cut = cutProg!!
        bindTarget(cutTex, 1, 1)
        cut.use()
        tex(0, cut, "uFlow", flowTex[0][n - 1][1])
        GLES30.glUniform2i(cut.loc("uGrid"), gridW[n - 1], gridH[n - 1])
        GLES30.glUniform1f(cut.loc("uMeanThresh"), CUT_MEAN)
        GLES30.glUniform1f(cut.loc("uPoorThresh"), CUT_POOR)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        flowFor = b
    }

    // =====================================================================
    // Presentation (every vsync)
    // =====================================================================

    private fun tick(vsyncNs: Long) {
        if (lastVsyncNs != 0L) {
            val period = vsyncNs - lastVsyncNs
            if (period in 3_000_000L..40_000_000L) vsyncPeriodNs = (vsyncPeriodNs * 7 + period) / 8
        }
        lastVsyncNs = vsyncNs
        emitWindow(vsyncNs)

        val c = cur ?: return
        val fps = targetFps
        val intervalNs = FramePacing.intervalNs(fps)
        if (!MotionBoostTiming.isDue(vsyncNs, nextDueNs, vsyncPeriodNs)) return

        val p = prev
        val sourceFps = if (frameIntervalNs > 0) 1_000_000_000f / frameIntervalNs else 0f
        val interpolate = p != null && !MotionBoostTiming.shouldBypass(sourceFps, fps)

        var alpha = 1f
        var hold = 0L
        if (interpolate) {
            hold = MotionBoostTiming.holdNs(frameIntervalNs, leadNs, vsyncPeriodNs)
            alpha = MotionBoostTiming.alpha(FramePacing.contentTimeNs(vsyncNs, vsyncPeriodNs, hold), p.tsNs, c.tsNs)
        }
        lastHoldNs = hold

        if (!dirty && c === lastDrawnCur && p === lastDrawnPrev && alpha == lastDrawnAlpha) {
            // paused / nothing new: leave the last presented frame; do not count the idle time as missed output
            nextDueNs = vsyncNs + intervalNs
            return
        }

        // Frames that were due but could not be produced in time (GPU / thread stall).
        if (nextDueNs != 0L && vsyncNs - nextDueNs > intervalNs + vsyncPeriodNs / 2) {
            acc.onDropped(((vsyncNs - nextDueNs) / intervalNs).toInt().coerceIn(1, 8))
        }

        val workStart = System.nanoTime()
        if (interpolate && flowFor !== c) {
            // Sample the true GPU+CPU cost of a motion estimate on every 4th pair (glFinish stalls only this thread).
            val sample = (estimateCount++ and 3) == 0
            val t0 = System.nanoTime()
            estimateMotion(p, c)
            if (sample) {
                GLES30.glFinish()
                acc.onEstimateMs((System.nanoTime() - t0) / 1e6f)
            }
        }

        drawOutput(a = if (interpolate) p else c, b = c, alpha = alpha, warp = interpolate)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            fail(FallbackReason.GPU_ERROR, "eglSwapBuffers failed (0x${Integer.toHexString(EGL14.eglGetError())})", null)
            return
        }
        acc.onDrawMs((System.nanoTime() - workStart) / 1e6f)
        nextDueNs = MotionBoostTiming.nextDue(nextDueNs, vsyncNs, intervalNs)
        lastDrawnCur = c; lastDrawnPrev = p; lastDrawnAlpha = alpha
        dirty = false
        acc.onPresented(generatedFrame = interpolate && alpha > 0.02f && alpha < 0.98f)
    }

    private fun emitWindow(nowNs: Long) {
        val w = acc.poll(nowNs) ?: return
        val sourceFps = if (frameIntervalNs > 0) 1_000_000_000f / frameIntervalNs else w.sourceFps
        callbacks.onWindow(w, sourceFps, levelW.firstOrNull() ?: 0, lastHoldNs / 1e6f)
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
        tex(0, ip, "uA", a.full); tex(1, ip, "uB", b.full)
        tex(2, ip, "uFw", finalFw); tex(3, ip, "uBw", finalBw); tex(4, ip, "uCut", cutTex)
        GLES30.glUniform1f(ip.loc("uT"), if (warp) alpha else 1f)
        GLES30.glUniform1i(ip.loc("uWarp"), if (warp) 1 else 0)
        GLES30.glUniform2f(ip.loc("uWork"), levelW[0].toFloat(), levelH[0].toFloat())
        GLES30.glUniform2f(ip.loc("uFlowUv"), levelW[0].toFloat() / (gridW[0] * PITCH), levelH[0].toFloat() / (gridH[0] * PITCH))
        GLES30.glUniform3f(ip.loc("uGrade"), brightness, contrast, saturation)
        GLES30.glUniform1f(ip.loc("uConsLo"), CONS_LO)
        GLES30.glUniform1f(ip.loc("uConsHi"), CONS_HI)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    // =====================================================================
    // GL helpers
    // =====================================================================

    private fun tex(unit: Int, p: Prog, name: String, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(p.loc(name), unit)
    }

    private fun bindTarget(texture: Int, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glViewport(0, 0, width, height)
    }

    private fun newTexture(width: Int, height: Int, internalFormat: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun freeResources() {
        val ids = ArrayList<Int>()
        slots.forEach { s -> ids.add(s.full); s.luma.forEach { ids.add(it) } }
        flowTex.forEach { dir -> dir.forEach { pair -> pair.forEach { ids.add(it) } } }
        if (finalFw != 0) ids.add(finalFw)
        if (finalBw != 0) ids.add(finalBw)
        if (cutTex != 0) ids.add(cutTex)
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        slots = emptyArray(); flowTex = emptyArray()
        finalFw = 0; finalBw = 0; cutTex = 0
    }

    private fun releaseGl() {
        runCatching {
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            freeResources()
            if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            listOf(copyProg, lumaProg, downProg, searchProg, consProg, cutProg, interpProg).forEach { it?.delete() }
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
        try { block() } catch (t: Throwable) { fail(FallbackReason.GPU_ERROR, what, t) }
    }

    private fun fail(reason: FallbackReason, what: String, t: Throwable?) {
        if (released) return
        Log.e(TAG, what, t)
        released = true
        choreographer?.removeFrameCallback(frameCallback)
        callbacks.onError(reason, "$what${t?.message?.let { ": $it" } ?: ""}")
        handler.post { releaseGl(); thread.quitSafely() }
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
        private const val TAG = "FpsBoosterGL"
        private const val SLOT_COUNT = 3          // bounded frame queue: previous, current, one being filled
        private const val PITCH = 4               // flow block pitch (matches flow_search.frag)
        private const val COARSEST_EDGE = 128     // stop building pyramid levels below this size
        private const val MAX_LEVELS = 5
        private const val LAMBDA = 0.003f         // flow smoothness penalty per px
        private const val CONS_LO = 1.0f          // forward/backward inconsistency (px): trusted below
        private const val CONS_HI = 3.0f          //                                        rejected above
        private const val CUT_MEAN = 0.12f
        private const val CUT_POOR = 0.10f
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
    }
}
