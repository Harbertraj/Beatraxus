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
import com.beatraxus.app.model.VideoOrientation
import com.beatraxus.app.motionboost.MotionBoostTiming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GPU-accelerated RIFE AI Renderer for BeatRaxus:
 * Combines GL surface rendering with ONNX Runtime RIFE 4.26 neural synthesis, incorporating full
 * Optical-Flow safeguards (TimestampRegularizer, median vsync, drift-free stepNs, 1-in-flight deadline
 * inference, GPU scene cut detector, soft blend fallback, and regional risk guards).
 */
class RifeRenderer(
    private val outputSurface: Surface,
    private val callbacks: Callbacks,
    private val context: Context
) {

    interface Callbacks {
        fun onInputSurfaceReady(surface: Surface)
        fun onStats(stats: BoosterStats)
        fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {}
        fun onError(reason: FallbackReason, message: String)
    }

    private class Slot(val full: Int, val luma: Int) {
        var tsNs: Long = 0L
    }

    private val thread = HandlerThread("RifeRenderer-GL", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    private val rifeEngine = RifeAiEngine(context)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var released = false
    @Volatile private var targetFps = 60
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
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
    private var cutProg: Prog? = null
    private var interpProg: Prog? = null
    private var slots: Array<Slot> = emptyArray()
    private var synthTexture = 0
    private var cutTexture = 0

    // ---- Inference & PBO Buffers ----
    private var pboA = 0
    private var pboB = 0
    private var inferenceInFlight = false
    private var activeInferenceTask: Future<*>? = null
    private var inferenceStartNs = 0L
    private var flowSkipped = false
    private val estimateLatency = EstimateLatency()

    private var inputBufA: FloatBuffer? = null
    private var inputBufB: FloatBuffer? = null
    private var outputBuf: FloatBuffer? = null

    // ---- Safeguard Helpers ----
    private val regularizer = TimestampRegularizer()
    private val statsAcc = StatsAccumulator()

    // ---- Sizes ----
    private var srcW = 0
    private var srcH = 0
    private var frameW = 0
    private var frameH = 0
    private var modelW = 640
    private var modelH = 360

    // ---- Frame History ----
    private var cur: Slot? = null
    private var prev: Slot? = null
    private var synthFor: Slot? = null
    private var pendingFrame = false
    private val texMatrix = FloatArray(16)
    private var frameIntervalNs = 0L
    private var leadNs = 20_000_000L
    private var vsyncPeriodNs = 8_333_333L
    private var lastVsyncNs = 0L
    @Volatile private var orientationMatrix: FloatArray = VideoOrientation.NONE.videoFromScreen()
    @Volatile private var deinterlaceMode = 0
    private var nextDueNs = 0L

    // ---- Vsync Median Ring ----
    private val vsyncRing = LongArray(9)
    private var vsyncHead = 0
    private var vsyncCount = 0
    private val vsyncScratch = LongArray(9)

    // ---- Redraw Bookkeeping ----
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

    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness; this.contrast = contrast; this.saturation = saturation
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
                0x3100, 0x3103,
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

        rifeEngine.initialize(BoosterQuality.PERFORMANCE)
        modelW = rifeEngine.modelWidth
        modelH = rifeEngine.modelHeight

        val tensorElems = 3 * modelW * modelH
        inputBufA = ByteBuffer.allocateDirect(tensorElems * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        inputBufB = ByteBuffer.allocateDirect(tensorElems * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        outputBuf = ByteBuffer.allocateDirect(tensorElems * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun applySourceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0 || eglContext == EGL14.EGL_NO_CONTEXT) return
        if (width == srcW && height == srcH && slots.isNotEmpty()) return
        cancelEstimate()
        srcW = width
        srcH = height

        frameW = max(16, width and 1.inv())
        frameH = max(16, height and 1.inv())

        freeSlots()
        slots = Array(SLOT_COUNT) {
            Slot(
                full = newTexture(frameW, frameH, GLES30.GL_RGBA8, GLES30.GL_LINEAR),
                luma = newTexture(128, 72, GLES30.GL_R8, GLES30.GL_LINEAR)
            )
        }
        synthTexture = newTexture(modelW, modelH, GLES30.GL_RGBA8, GLES30.GL_LINEAR)
        cutTexture = newTexture(1, 1, GLES30.GL_RGBA8, GLES30.GL_NEAREST)

        cur = null; prev = null; synthFor = null
        lastDrawnCur = null; lastDrawnPrev = null
        dirty = true

        if (pendingFrame) {
            pendingFrame = false
            onFrameAvailable()
        }
    }

    private fun cancelEstimate() {
        activeInferenceTask?.cancel(true)
        activeInferenceTask = null
        inferenceInFlight = false
        synthFor = null
        flowSkipped = false
        regularizer.reset()
        estimateLatency.reset()
        nextDueNs = 0L
    }

    private fun onFrameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        if (slots.isEmpty()) { pendingFrame = true; return }
        try { st.updateTexImage() } catch (t: Throwable) { Log.w(TAG, "updateTexImage failed", t); return }
        st.getTransformMatrix(texMatrix)

        val now = System.nanoTime()
        var rawTs = st.timestamp
        if (abs(rawTs - now) > 2_000_000_000L) rawTs = now

        val regTs = regularizer.next(rawTs, vsyncPeriodNs)

        val newSlot = slots.firstOrNull { it !== cur && it !== prev && (synthFor == null || it !== synthFor) }
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

        val p = prev
        if (p != null && cur != null && synthFor !== cur) {
            triggerAsyncInference(p, cur!!)
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
        bindTarget(slot.luma, 128, 72)
        luma.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.full)
        GLES30.glUniform1i(luma.loc("uSrc"), 0)
        GLES30.glUniform2f(luma.loc("uTexel"), 1f / frameW, 1f / frameH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun triggerAsyncInference(a: Slot, b: Slot) {
        if (inferenceInFlight) {
            flowSkipped = true
            statsAcc.onEstimateSkipped()
            return
        }

        // Run GPU 1x1 scene cut detector pass first
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

        inferenceInFlight = true
        inferenceStartNs = System.nanoTime()

        val bufA = inputBufA
        val bufB = inputBufB
        val bufOut = outputBuf

        if (bufA != null && bufB != null && bufOut != null) {
            activeInferenceTask = inferenceExecutor.submit {
                val ok = rifeEngine.interpolateBuffer(bufA, bufB, bufOut, 0.5f)
                if (ok) {
                    synthFor = b
                    flowSkipped = false
                } else {
                    flowSkipped = true
                }
                val durNs = System.nanoTime() - inferenceStartNs
                estimateLatency.onSample(durNs)
                statsAcc.onEstimateMs(durNs / 1e6f)
                inferenceInFlight = false
            }
        } else {
            flowSkipped = true
            inferenceInFlight = false
        }
    }

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
            warp = synthFor === c && !flowSkipped
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
        lastDrawnCur = c
        lastDrawnPrev = p
        lastDrawnAlpha = alpha
        dirty = false
        reportStats(vsyncNs, sourceFps)
    }

    private fun drawOutput(a: Slot, b: Slot, alpha: Float, warp: Boolean) {
        val w = IntArray(1); val h = IntArray(1)
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
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, synthTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cutTexture)

        GLES30.glUniform1i(ip.loc("uA"), 0)
        GLES30.glUniform1i(ip.loc("uB"), 1)
        GLES30.glUniform1i(ip.loc("uSynth"), 2)
        GLES30.glUniform1i(ip.loc("uCutTex"), 3)
        GLES30.glUniform1f(ip.loc("uAlpha"), alpha)
        GLES30.glUniform1i(ip.loc("uWarp"), if (warp) 1 else 0)
        GLES30.glUniform3f(ip.loc("uGrade"), brightness, contrast, saturation)
        GLES30.glUniformMatrix3fv(ip.loc("uColorDelta"), 1, false, colorDelta, 0)
        GLES30.glUniformMatrix2fv(ip.loc("uOrient"), 1, false, orientationMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun reportStats(nowNs: Long, sourceFps: Float) {
        val win = statsAcc.poll(nowNs)
        if (win != null) {
            callbacks.onStats(
                BoosterStats(
                    running = true,
                    engine = BoosterEngine.AI_RIFE,
                    sourceFps = sourceFps,
                    targetFps = targetFps,
                    renderedFps = win.renderedFps,
                    generatedFps = win.generatedFps,
                    droppedFrames = win.dropped.toLong(),
                    estimateLatencyMs = win.avgEstimateMs,
                    drawMs = win.avgDrawMs,
                    workEdge = modelW
                )
            )
            callbacks.onWindow(win, sourceFps, modelW, estimateLatency.smoothedNs / 1e6f)
        }
    }

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
            val ids = IntArray(slots.size * 2 + 2)
            slots.forEachIndexed { i, s -> ids[i * 2] = s.full; ids[i * 2 + 1] = s.luma }
            ids[ids.size - 2] = synthTexture
            ids[ids.size - 1] = cutTexture
            GLES30.glDeleteTextures(ids.size, ids, 0)
        }
        slots = emptyArray()
        synthTexture = 0
        cutTexture = 0
    }

    private fun releaseGl() {
        runCatching {
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            freeSlots()
            if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            copyProg?.delete(); lumaProg?.delete(); cutProg?.delete(); interpProg?.delete()
            rifeEngine.release()
            inferenceExecutor.shutdownNow()
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
        try { block() } catch (t: Throwable) { fail(what, t) }
    }

    private fun fail(what: String, t: Throwable?) {
        if (released) return
        Log.e(TAG, what, t)
        released = true
        choreographer?.removeFrameCallback(frameCallback)
        callbacks.onError(FallbackReason.GPU_ERROR, "$what${t?.message?.let { ": $it" } ?: ""}")
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
        private const val TAG = "RifeRenderer"
        private const val SLOT_COUNT = 4
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
uniform sampler2D uSynth;
uniform sampler2D uCutTex;
uniform float uAlpha;
uniform int uWarp;
uniform vec3 uGrade;
uniform mat3 uColorDelta;
in vec2 vUv;
out vec4 o;

void main() {
  vec3 rgb;
  float isCut = texture(uCutTex, vec2(0.5)).g;
  if (uWarp == 0 || isCut > 0.5) {
    vec3 pa = texture(uA, vUv).rgb;
    vec3 pb = texture(uB, vUv).rgb;
    rgb = (uAlpha < 0.5) ? pa : pb;
  } else {
    vec3 synth = texture(uSynth, vUv).rgb;
    vec3 pa = texture(uA, vUv).rgb;
    vec3 pb = texture(uB, vUv).rgb;
    vec3 plain = mix(pa, pb, uAlpha);

    // Regional hallucination safeguard
    vec3 minCol = min(pa, pb);
    vec3 maxCol = max(pa, pb);
    float dev = length(max(vec3(0.0), synth - maxCol) + max(vec3(0.0), minCol - synth));
    float conf = 1.0 - smoothstep(0.15, 0.35, dev);

    rgb = mix(plain, synth, conf);
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
