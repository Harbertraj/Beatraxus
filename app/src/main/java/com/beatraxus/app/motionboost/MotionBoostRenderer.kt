package com.beatraxus.app.motionboost

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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.beatraxus.app.colorgrade.ColorTune
import com.beatraxus.app.deinterlace.DeinterlaceGlsl
import com.beatraxus.app.fpsbooster.EstimateLatency
import com.beatraxus.app.fpsbooster.FramePacing
import com.beatraxus.app.fpsbooster.StatsAccumulator
import com.beatraxus.app.fpsbooster.TimestampRegularizer
import com.beatraxus.app.fpsbooster.WindowStats
import com.beatraxus.app.model.VideoOrientation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FPS Booster engine: takes decoded video from ExoPlayer, builds intermediate frames on the GPU,
 * and presents them at the chosen target rate (45 / 60 / 90 / 120 FPS).
 *
 * Classic engine with full Optical-Flow safeguards ported:
 *  1. Asynchronous, fence-gated motion estimation (1 estimate in flight, non-blocking vsync thread).
 *  2. Timestamp regularization and drift-free stepNs pacing.
 *  3. GPU-resident 1x1 scene-cut detector.
 *  4. Soft blend fallback + regional risk fading.
 *  5. StatsAccumulator reporting WindowStats for FpsBoosterManager integration.
 */
class MotionBoostRenderer(
    private val outputSurface: Surface,
    private val callbacks: Callbacks,
    private val context: Context? = null
) {

    interface Callbacks {
        /** Called on the GL thread once ExoPlayer can be pointed at the input surface. */
        fun onInputSurfaceReady(surface: Surface)

        /** Called on the GL thread about once per second with raw FPS. */
        fun onStats(sourceFps: Float, outputFps: Float)

        /** Full statistics window for FpsBoosterManager / AdaptiveQualityController integration. */
        fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {}

        fun onError(message: String)
    }

    private class Slot(val full: Int, val luma: Int, val half: Int) {
        var tsNs: Long = 0L
    }

    private val thread = HandlerThread("MotionBoost-GL", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var released = false
    @Volatile private var targetFps = 60
    /** Extreme performance: lighter working resolution + CPU/GPU performance hints. */
    @Volatile private var extreme = false
    private var appliedExtreme = false
    private var hintSession: PerformanceHintManager.Session? = null
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    /** Hue + colour temperature as (matrix - identity), column-major; all zeros = no change. */
    @Volatile private var colorDelta: FloatArray = ColorTune.NO_DELTA
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
    private var cutProg: Prog? = null
    private var interpProg: Prog? = null
    private var slots: Array<Slot> = emptyArray()
    private var mvTexture = 0   // final (smoothed) motion field, sampled with LINEAR filtering
    private var mvScratch = 0   // intermediate field between the passes
    private var cutTexture = 0  // 1x1 scene cut result texture

    // ---- Fence and Async Estimation ----
    private var estimateFence: Long = 0L
    private var estimateInFlight = false
    private var estimateStartNs = 0L
    private var flowSkipped = false
    private val estimateLatency = EstimateLatency()

    // ---- Safeguard Helpers ----
    private val regularizer = TimestampRegularizer()
    private val statsAcc = StatsAccumulator()

    // ---- sizes ----
    private var srcW = 0
    private var srcH = 0
    private var workW = 0   // motion-estimation working size
    private var workH = 0
    private var frameW = 0  // size of stored frames shown on screen
    private var frameH = 0
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
    @Volatile private var orientationMatrix: FloatArray = VideoOrientation.NONE.videoFromScreen()
    @Volatile private var deinterlaceMode = 0
    private var nextDueNs = 0L

    // ---- Vsync Median Ring (9 samples) ----
    private val vsyncRing = LongArray(9)
    private var vsyncHead = 0
    private var vsyncCount = 0
    private val vsyncScratch = LongArray(9)

    // ---- redraw bookkeeping ----
    private var lastDrawnCur: Slot? = null
    private var lastDrawnPrev: Slot? = null
    private var lastDrawnAlpha = -1f

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
        val changed = fps != targetFps
        targetFps = fps.coerceIn(1, 240)
        if (changed) {
            handler.post { cancelEstimate() }
        }
    }

    fun setExtreme(enabled: Boolean) {
        if (extreme == enabled) return
        extreme = enabled
        handler.post {
            runGuarded("resize failed") {
                updateHintSession()
                if (srcW > 0 && srcH > 0) applySourceSize(srcW, srcH)
            }
        }
    }

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        dirty = true
    }

    fun setColorTune(hueDegrees: Float, temperature: Float) {
        colorDelta = ColorTune.deltaColumnMajor(hueDegrees, temperature)
        dirty = true
    }

    fun setOrientation(orientation: VideoOrientation) {
        orientationMatrix = orientation.videoFromScreen()
        dirty = true
    }

    fun setDeinterlace(mode: Int) {
        if (mode == deinterlaceMode) return
        deinterlaceMode = mode
        dirty = true
    }

    fun setSourceSize(width: Int, height: Int) {
        handler.post { runGuarded("resize failed") { applySourceSize(width, height) } }
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        val done = CountDownLatch(1)
        val posted = handler.post {
            choreographer?.removeFrameCallback(frameCallback)
            cancelEstimate()
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

        val ext = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        val lowPrioritySupported = ext.contains("EGL_IMG_context_priority")
        val ctxAttribs = if (lowPrioritySupported && targetFps <= 60) {
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                0x3100, 0x3103, // EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        }

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
        cutProg = Prog(VERTEX, CUT_FRAGMENT)
        interpProg = Prog(PRESENT_VERTEX, INTERP_FRAGMENT)

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

        updateHintSession()
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun applySourceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0 || eglContext == EGL14.EGL_NO_CONTEXT) return
        if (width == srcW && height == srcH && slots.isNotEmpty() && appliedExtreme == extreme) return
        cancelEstimate()
        srcW = width
        srcH = height
        appliedExtreme = extreme

        val frameEdge = if (appliedExtreme) EXTREME_FRAME_EDGE else MAX_FRAME_EDGE
        val meEdge = if (appliedExtreme) EXTREME_ME_EDGE else MAX_WORK_EDGE
        val frameScale = min(1f, frameEdge.toFloat() / max(width, height))
        frameW = max(16, ((width * frameScale).toInt()) and 1.inv())
        frameH = max(16, ((height * frameScale).toInt()) and 1.inv())
        val scale = min(1f, meEdge.toFloat() / max(width, height))
        workW = max(16, ((width * scale).toInt()) and 1.inv())
        workH = max(16, ((height * scale).toInt()) and 1.inv())
        lumaW = max(BLOCK, ((workW / 4 + BLOCK - 1) / BLOCK) * BLOCK)
        lumaH = max(BLOCK, ((workH / 4 + BLOCK - 1) / BLOCK) * BLOCK)
        blocksW = lumaW / BLOCK
        blocksH = lumaH / BLOCK
        halfW = lumaW * 2
        halfH = lumaH * 2

        freeSlots()
        slots = Array(SLOT_COUNT) {
            Slot(
                full = newTexture(frameW, frameH, GLES30.GL_RGBA8, GLES30.GL_LINEAR),
                luma = newTexture(lumaW, lumaH, GLES30.GL_R8, GLES30.GL_LINEAR),
                half = newTexture(halfW, halfH, GLES30.GL_R8, GLES30.GL_LINEAR)
            )
        }
        mvTexture = newTexture(blocksW, blocksH, GLES30.GL_RGBA8, GLES30.GL_LINEAR)
        mvScratch = newTexture(blocksW, blocksH, GLES30.GL_RGBA8, GLES30.GL_NEAREST)
        cutTexture = newTexture(1, 1, GLES30.GL_RGBA8, GLES30.GL_NEAREST)
        cur = null
        prev = null
        mvFor = null
        lastDrawnCur = null
        lastDrawnPrev = null
        dirty = true

        if (pendingFrame) {
            pendingFrame = false
            onFrameAvailable()
        }
    }

    private fun cancelEstimate() {
        if (estimateFence != 0L) {
            GLES30.glDeleteSync(estimateFence)
            estimateFence = 0L
        }
        estimateInFlight = false
        mvFor = null
        flowSkipped = false
        regularizer.reset()
        estimateLatency.reset()
        nextDueNs = 0L
    }

    // =====================================================================
    // Frame intake
    // =====================================================================

    private fun onFrameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        if (slots.isEmpty()) {
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
        var rawTs = st.timestamp
        if (abs(rawTs - now) > 2_000_000_000L) rawTs = now

        val regTs = regularizer.next(rawTs, vsyncPeriodNs)

        // Protect in-flight slots from recycling
        val newSlot = slots.firstOrNull { it !== cur && it !== prev && (mvFor == null || it !== mvFor) }
            ?: slots.first { it !== cur && it !== prev }
        copyFrameInto(newSlot)
        newSlot.tsNs = regTs

        statsAcc.onSourceFrame()

        val c = cur
        val continuous = c != null && MotionBoostTiming.isContinuous(c.tsNs, regTs) && !FramePacing.isGap(c.tsNs, regTs)
        if (c != null && continuous) {
            val interval = regTs - c.tsNs
            if (interval in MIN_FRAME_NS..MAX_FRAME_NS) {
                frameIntervalNs = if (frameIntervalNs == 0L) interval else (frameIntervalNs * 7 + interval) / 8
            }
            prev = c
        } else {
            prev = null
            cancelEstimate()
        }
        cur = newSlot
        val lead = (regTs - now).coerceIn(-50_000_000L, 200_000_000L)
        leadNs = (leadNs * 7 + lead) / 8

        // Trigger fence-gated estimate off the intake path if continuous pair exists
        val p = prev
        if (p != null && cur != null && mvFor !== cur) {
            triggerAsyncEstimate(p, cur!!)
        }
    }

    private fun copyFrameInto(slot: Slot) {
        val copy = copyProg!!
        bindTarget(slot.full, frameW, frameH)
        copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(copy.loc("uTex"), 0)
        GLES30.glUniformMatrix4fv(copy.loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1f(copy.loc("uTexelH"), if (srcH > 0) 1f / srcH else 0f)
        GLES30.glUniform1i(copy.loc("uMode"), deinterlaceMode)
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

    private fun triggerAsyncEstimate(a: Slot, b: Slot) {
        if (estimateInFlight) {
            flowSkipped = true
            statsAcc.onEstimateSkipped()
            return
        }
        estimateInFlight = true
        estimateStartNs = System.nanoTime()

        // Pass 1: coarse search
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

        // Pass 2: refine search
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

        // Pass 3: smooth
        val smooth = smoothProg!!
        bindTarget(mvScratch, blocksW, blocksH)
        smooth.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexture)
        GLES30.glUniform1i(smooth.loc("uMV"), 0)
        GLES30.glUniform2i(smooth.loc("uBlocks"), blocksW, blocksH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val t = mvTexture
        mvTexture = mvScratch
        mvScratch = t
        setMvFilters()

        // Pass 4: GPU 1x1 Scene Cut Detector
        val cut = cutProg!!
        bindTarget(cutTexture, 1, 1)
        cut.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.luma)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.luma)
        GLES30.glUniform1i(cut.loc("uA"), 0)
        GLES30.glUniform1i(cut.loc("uB"), 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        mvFor = b
        flowSkipped = false

        if (estimateFence != 0L) GLES30.glDeleteSync(estimateFence)
        estimateFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
    }

    private fun pollEstimateFence(): Boolean {
        if (!estimateInFlight) return mvFor != null
        val fence = estimateFence
        if (fence == 0L) return false
        val status = GLES30.glClientWaitSync(fence, 0, 0L)
        if (status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED) {
            GLES30.glDeleteSync(fence)
            estimateFence = 0L
            estimateInFlight = false
            val durNs = System.nanoTime() - estimateStartNs
            estimateLatency.onSample(durNs)
            statsAcc.onEstimateMs(durNs / 1e6f)
            return true
        }
        return false
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
            if (period in 3_000_000L..40_000_000L) {
                vsyncRing[vsyncHead] = period
                vsyncHead = (vsyncHead + 1) % 9
                if (vsyncCount < 9) vsyncCount++
                System.arraycopy(vsyncRing, 0, vsyncScratch, 0, vsyncCount)
                vsyncScratch.sort(0, vsyncCount)
                vsyncPeriodNs = vsyncScratch[vsyncCount / 2]
            }
        }
        lastVsyncNs = vsyncNs

        val c = cur ?: return
        val fps = targetFps
        val rawStepNs = (1_000_000_000.0 / fps).toLong()
        val stepNs = MotionBoostTiming.stepNs(rawStepNs, vsyncPeriodNs)

        if (!MotionBoostTiming.isDue(vsyncNs, nextDueNs, vsyncPeriodNs)) return
        val workStartNs = System.nanoTime()

        val p = prev
        val sourceFps = if (frameIntervalNs > 0) 1_000_000_000f / frameIntervalNs else 0f
        val interpolate = p != null && !MotionBoostTiming.shouldBypass(sourceFps, fps)

        var alpha = 1f
        var warp = false
        if (interpolate) {
            val hold = MotionBoostTiming.holdNs(frameIntervalNs, leadNs, vsyncPeriodNs, estimateLatency.smoothedNs)
            val contentTime = vsyncNs + vsyncPeriodNs - hold
            alpha = MotionBoostTiming.alpha(contentTime, p!!.tsNs, c.tsNs)
            warp = pollEstimateFence() && !flowSkipped
        }

        if (!dirty && c === lastDrawnCur && p === lastDrawnPrev && alpha == lastDrawnAlpha && warp) {
            reportStats(vsyncNs, sourceFps)
            return
        }

        drawOutput(a = if (interpolate) p!! else c, b = c, alpha = alpha, warp = warp)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            fail("eglSwapBuffers failed (0x${Integer.toHexString(EGL14.eglGetError())})", null)
            return
        }

        val drawDurationMs = (System.nanoTime() - workStartNs) / 1e6f
        statsAcc.onDrawMs(drawDurationMs)
        statsAcc.onPresented(warp)

        nextDueNs = MotionBoostTiming.nextDue(nextDueNs, vsyncNs, stepNs)
        reportWorkDuration((drawDurationMs * 1e6f).toLong(), stepNs)
        lastDrawnCur = c
        lastDrawnPrev = p
        lastDrawnAlpha = alpha
        dirty = false
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
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cutTexture)

        GLES30.glUniform1i(ip.loc("uA"), 0)
        GLES30.glUniform1i(ip.loc("uB"), 1)
        GLES30.glUniform1i(ip.loc("uMV"), 2)
        GLES30.glUniform1i(ip.loc("uCutTex"), 3)
        GLES30.glUniform1f(ip.loc("uAlpha"), alpha)
        GLES30.glUniform1i(ip.loc("uWarp"), if (warp) 1 else 0)
        GLES30.glUniform2f(ip.loc("uInvHalf"), 1f / halfW, 1f / halfH)
        GLES30.glUniform3f(ip.loc("uGrade"), brightness, contrast, saturation)
        GLES30.glUniformMatrix3fv(ip.loc("uColorDelta"), 1, false, colorDelta, 0)
        GLES30.glUniformMatrix2fv(ip.loc("uOrient"), 1, false, orientationMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun reportWorkDuration(workNs: Long, targetNs: Long) {
        val session = hintSession ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            session.updateTargetWorkDuration(targetNs)
            session.reportActualWorkDuration(workNs)
        } catch (t: Throwable) {
            hintSession = null
        }
    }

    private fun updateHintSession() {
        runCatching {
            Process.setThreadPriority(
                thread.threadId,
                if (extreme) Process.THREAD_PRIORITY_URGENT_DISPLAY else Process.THREAD_PRIORITY_DISPLAY
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!extreme) {
            runCatching { hintSession?.close() }
            hintSession = null
            return
        }
        if (hintSession != null) return
        hintSession = runCatching {
            val mgr = context?.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
            mgr?.createHintSession(intArrayOf(thread.threadId), 1_000_000_000L / targetFps.coerceAtLeast(1))
        }.getOrNull()
    }

    private fun reportStats(nowNs: Long, sourceFps: Float) {
        val win = statsAcc.poll(nowNs)
        if (win != null) {
            callbacks.onStats(sourceFps, win.renderedFps)
            callbacks.onWindow(win, sourceFps, if (appliedExtreme) EXTREME_ME_EDGE else MAX_WORK_EDGE, estimateLatency.smoothedNs / 1e6f)
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
            val ids = IntArray(slots.size * 3 + 3)
            slots.forEachIndexed { i, s -> ids[i * 3] = s.full; ids[i * 3 + 1] = s.luma; ids[i * 3 + 2] = s.half }
            ids[ids.size - 3] = mvTexture
            ids[ids.size - 2] = mvScratch
            ids[ids.size - 1] = cutTexture
            GLES30.glDeleteTextures(ids.size, ids, 0)
        }
        slots = emptyArray()
        mvTexture = 0
        mvScratch = 0
        cutTexture = 0
    }

    private fun releaseGl() {
        runCatching {
            runCatching { hintSession?.close() }
            hintSession = null
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            freeSlots()
            if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            copyProg?.delete(); lumaProg?.delete(); halfProg?.delete(); meProg?.delete()
            refineProg?.delete(); smoothProg?.delete(); cutProg?.delete(); interpProg?.delete()
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
        private const val SLOT_COUNT = 4
        private const val BLOCK = 4
        private const val MAX_WORK_EDGE = 1920
        private const val MAX_FRAME_EDGE = 3840
        private const val EXTREME_FRAME_EDGE = 1280
        private const val EXTREME_ME_EDGE = 960
        private const val MIN_FRAME_NS = 4_000_000L
        private const val MAX_FRAME_NS = 200_000_000L

        private const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val PRESENT_VERTEX = """#version 300 es
uniform mat2 uOrient;
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = uOrient * (p - 0.5) + 0.5;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private val COPY_OES_FRAGMENT: String = DeinterlaceGlsl.oesCopyFragment()

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
  float amb = 0.0;
  if (zero > 0.015) {
    float bestCost = zero * 0.85 - 0.002;
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
  vec2 coarse = (coarseT.rg * 255.0 - 128.0) / 8.0;
  ivec2 start = ivec2(floor(coarse * 2.0 + 0.5));

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
  if (coarseT.b > 0.5) res = max(res, 0.6);
  o = vec4((vec2(best) * 4.0 + 128.0) / 255.0, res, 1.0);
}
"""

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
  float dev = 0.0;
  for (int i = 0; i < 9; i++) dev += abs(vx[i] - vx[4]) + abs(vy[i] - vy[4]);
  dev = dev / 9.0 / 4.0;
  float noisy = 0.4 * smoothstep(3.0, 8.0, dev);
  o = vec4(vx[4] / 255.0, vy[4] / 255.0, clamp(0.5 * (own + worst) + noisy, 0.0, 1.0), 1.0);
}
"""

        /** 1x1 GPU Scene Cut Detector pass */
        private const val CUT_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uA;
uniform sampler2D uB;
out vec4 o;
void main() {
  float diff = 0.0;
  for (int y = 0; y < 8; y++) {
    for (int x = 0; x < 8; x++) {
      vec2 uv = vec2((float(x) + 0.5) / 8.0, (float(y) + 0.5) / 8.0);
      diff += abs(texture(uA, uv).r - texture(uB, uv).r);
    }
  }
  float mean = diff / 64.0;
  o = vec4(mean, (mean > 0.12) ? 1.0 : 0.0, 0.0, 1.0);
}
"""

        private const val INTERP_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uMV;
uniform sampler2D uCutTex;
uniform float uAlpha;
uniform int uWarp;
uniform vec2 uInvHalf;
uniform vec3 uGrade;
uniform mat3 uColorDelta;
in vec2 vUv;
out vec4 o;

vec2 decode(vec2 e) { return (e * 255.0 - 128.0) / 4.0; }

void main() {
  vec3 rgb;
  float isCut = texture(uCutTex, vec2(0.5)).g;
  if (uWarp == 0 || isCut > 0.5) {
    vec3 pa = texture(uA, vUv).rgb;
    vec3 pb = texture(uB, vUv).rgb;
    rgb = (uAlpha < 0.5) ? pa : pb;
  } else {
    vec2 d0 = decode(texture(uMV, vUv).rg);
    vec4 m = texture(uMV, vUv - uAlpha * d0 * uInvHalf);
    vec2 d = decode(m.rg);
    float residual = m.b * 0.5;

    vec3 ca = texture(uA, vUv - uAlpha * d * uInvHalf).rgb;
    vec3 cb = texture(uB, vUv + (1.0 - uAlpha) * d * uInvHalf).rgb;
    vec3 warped = mix(ca, cb, uAlpha);

    vec3 pa = texture(uA, vUv).rgb;
    vec3 pb = texture(uB, vUv).rgb;
    vec3 plain = mix(pa, pb, uAlpha);

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
  rgb += uColorDelta * rgb;
  o = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""
    }
}
