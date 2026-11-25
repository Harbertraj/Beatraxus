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
 *  - Exactly [SLOT_COUNT] frame slots exist (bounded queue, ordered by timestamp). A new decoded frame takes a
 *    free slot; when none is free the oldest queued frame is dropped, so a slow GPU drops source frames
 *    instead of queueing unboundedly. Each slot also owns the flow fields of the pair (previous frame -> this
 *    frame), so the pair being shown is never overwritten while the next one is estimated.
 *  - Motion is estimated when a frame ARRIVES (between vsyncs), not on the vsync that needs it, so the
 *    estimate never delays a presented frame. The pair to show is chosen by content time ([FrameSelection]),
 *    so frames that arrive early just wait in the queue ([HoldController] keeps the delay stable and adds the
 *    measured estimate latency).
 *  - A new estimate is only started when the previous one has finished on the GPU (fence). A pair that arrives
 *    while the GPU is still busy gets no flow and is shown as the nearest real frame (chosen by alpha); this
 *    bounds the GPU backlog, which is what made heavy presets glitch and lag on fast motion: a backlog delays
 *    every later estimate AND every presented frame queued behind it. Skipped estimates are counted and reach
 *    [AdaptiveQualityController] as overload. The vsync tick never starts an estimate while the GPU is busy.
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

    interface RifeOverlayHook {
        fun onFrameCopied(slotFullTex: Int, tsNs: Long, frameW: Int, frameH: Int)
        fun onTick()
        fun getRifeTexture(aTsNs: Long, bTsNs: Long): Int
        fun onGlRelease()

        /**
         * Called right after [onFrameCopied] for a frame that has a real predecessor. True = the hook will deliver an AI
         * middle frame for (previous, this) through [OpticalFlowRenderer.injectMidFrame], so the flow estimate of this pair is
         * deferred and runs on (A, M) and (M, B) instead (RIFE first, optical flow second). False = normal path.
         */
        fun wantsMidFrame(): Boolean = false

        /** True when mid frames can be injected: the renderer then reserves two extra frame slots. */
        fun supportsMidFrames(): Boolean = false
    }

    @Volatile var rifeOverlayHook: RifeOverlayHook? = null

    /** [full] is the stored frame; everything else is analysis data sized by the quality level, which [reconfigureAnalysis] replaces in place. */
    private class Slot(val full: Int, var luma: IntArray, var fw: Int, var bw: Int, var cut: Int, var glob: Int, var risk: Int) {
        var tsNs: Long = 0L
        /** fw / bw / cut / glob / risk hold the flow from the previous queued frame to this one. */
        var hasFlow: Boolean = false
        /** The estimate for this frame was not started because the GPU was busy: show real frames, do not retry. */
        var flowSkipped: Boolean = false
        /** When this real frame arrived (ns, System.nanoTime); a mid frame inherits it from the frame it precedes. */
        var arrivalNs: Long = 0L
        /** RIFE-first: waiting for the AI middle frame before (previous, this) is estimated. */
        var midPending: Boolean = false
        var midDeadlineNs: Long = 0L
        /** The flow of this slot is deferred (waiting for the mid frame, or queued behind another estimate). */
        var flowPending: Boolean = false
        /** Synthesised by the AI engine (lives between two real frames in the queue). */
        var isMid: Boolean = false
        /** The pair (previous frame, this frame) is waiting for the GPU to finish the running estimate: see [deferredB]. */
        var flowDeferred: Boolean = false
    }

    private val thread = HandlerThread("FpsBooster-GL", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var released = false
    @Volatile private var targetFps = 60
    @Volatile private var level: QualityLevel = AdaptiveQualityController.LADDER[1]
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    /** Hue + colour temperature as (matrix - identity), column-major; all zeros = no change. */
    @Volatile private var colorDelta: FloatArray = ColorTune.NO_DELTA
    @Volatile private var dirty = true
    @Volatile private var deinterlaceMode = 0
    @Volatile private var orientationMatrix: FloatArray = VideoOrientation.NONE.videoFromScreen()
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
    private var riskProg: Prog? = null
    private var globalProg: Prog? = null
    private var interpProg: Prog? = null            // the hand-tuned blend; always present, also the fallback
    private var blitProg: Prog? = null              // plain texture copy (AI middle frame -> frame slot)
    private var aiInterpProg: Prog? = null          // composite that reads the fusion map (only with fuseProg + fuseTex)

    // ---- AI fusion (optional, off by default; see [setAiFusion]) ----
    /** Writes the fusion map: a small network picks the blend weights of six candidate colours per pixel. */
    private var fuseProg: Prog? = null
    private var fuseTex = IntArray(0)                       // two RGBA16F attachments (weights of candidates 0..3 and 4..5)
    private var fuseW = 0
    private var fuseH = 0
    @Volatile private var aiFusionWanted = false

    private var slots: Array<Slot> = emptyArray()
    private val queue = ArrayList<Slot>(SLOT_COUNT)        // decoded frames, ascending timestamp
    private val freeSlots = ArrayList<Slot>(SLOT_COUNT)
    /** Estimates waiting for the GPU, in order: (A, M) then (M, B) for every injected mid frame M. */
    private val midChain = ArrayList<Pair<Slot, Slot>>()
    /**
     * The one pair that arrived while the GPU was still busy and waits for it ("preload"): it starts the moment the
     * running estimate has finished, while its frames are still ahead of the picture on screen (the video hold keeps
     * the shown picture a few frames behind the decoder). A second pair arriving meanwhile is skipped, so the GPU
     * backlog stays bounded at one pair. Before, every pair that met a busy GPU was skipped for good, and one slow
     * estimate in a row made most of the following pairs repeat real frames instead of generating new ones.
     */
    private var deferredB: Slot? = null
    private var flowTex: Array<Array<IntArray>> = emptyArray()   // [direction 0=fw 1=bw][level] -> ping/pong
    private var globalCoarse = IntArray(0)                       // [direction] 1x1: dominant motion of the coarsest field

    // ---- sizes ----
    private var srcW = 0
    private var srcH = 0
    private var frameW = 0
    private var frameH = 0
    private var levels: FlowPyramid.Levels = FlowPyramid.levels(32, 32)
    private var levelW = IntArray(0)
    private var levelH = IntArray(0)
    private var gridW = IntArray(0)
    private var gridH = IntArray(0)

    // ---- frame history ----
    private var pendingFrame = false
    private val texMatrix = FloatArray(16)
    private var frameIntervalNs = 0L
    private val holdCtl = HoldController()
    private var vsyncPeriodNs = 8_333_333L
    private var lastVsyncNs = 0L
    private var nextDueNs = 0L
    /** Exact n-vsync cadence for the presented frames (see [CadenceGovernor]). */
    private val cadence = CadenceGovernor()
    private var lastDrawnA: Slot? = null
    private var lastDrawnB: Slot? = null
    private var lastDrawnTsA = 0L
    private var lastDrawnTsB = 0L
    private var lastDrawnAlpha = -1f
    private var lastDrawnWarp = false
    private var lastHoldNs = 0L
    private var lastRawTsNs = 0L
    private val tsRegularizer = TimestampRegularizer()

    // ---- motion estimation steps ----
    private val estSteps = ArrayList<() -> Unit>()
    private var estIdx = 0
    private var estA: Slot? = null
    private var estB: Slot? = null
    private var estArrivalNs = 0L
    private var estFirstSubmitNs = 0L
    private var estPerTick = 1
    /** Keeps the analysis steps from delaying the presenter: fewer steps per vsync while presenting is late or slow. */
    private val estimatePacer = EstimatePacer()

    // ---- vsync period estimation (late callbacks must never read as a slower panel: see VsyncPeriodEstimator) ----
    private val vsyncEstimator = VsyncPeriodEstimator()
    private val panelHint = PanelRateHint(context)

    // Fence of the motion estimate in flight (at most one): measures every pair's latency without a glFinish
    // (which would stall the vsync callback) and tells the intake whether the GPU is still busy.
    private var fence = 0L
    private var fenceStartNs = 0L
    private val estimateLatency = EstimateLatency()

    // ---- stats ----
    private val acc = StatsAccumulator()
    /** Tells generated / original / repeated frames apart so the statistics are never inflated by repeats. */
    private val frameClassifier = FrameClassifier()
    /** Shows a cheap real frame instead of a generated one when a 90 / 120 FPS frame is already a whole vsync late. */
    private val deadline = FrameDeadlinePolicy()

    // ---- motion complexity (see MotionComplexity.kt) ----
    // The dominant-motion texel and the cut texel of every finished pair are copied into a small PBO right before the
    // estimate fence is created, so the fence that already retires the estimate also covers the copy: the CPU never waits
    // for it (it is mapped only after the fence has signalled). The result plans the NEXT pair's search effort.
    private val complexityClassifier = MotionComplexityClassifier()
    private var pairComplexity = MotionComplexity.UNKNOWN
    private var complexityPbo = 0
    private var complexityPending = false
    /**
     * True while the previous motion estimate has not finished on the GPU. When the fence has signalled, its
     * latency (frame arrival -> flow ready) is recorded here, for the stats and for the hold.
     *
     * The fence is checked from [tick] (once per vsync) and from the intake paths; there is no timer polling it.
     * [detectionLagNs] is how long ago the fence may already have signalled when it is found by a vsync check
     * (on average half a vsync); it is taken off the measured latency so that the quantised check does not
     * make the estimate - and through it the video hold - look longer than it was.
     */
    private fun gpuBusy(detectionLagNs: Long = 0L): Boolean {
        if (estIdx < estSteps.size) return true
        if (fence == 0L) return false
        return when (GLES30.glClientWaitSync(fence, 0, 0L)) {
            GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> {
                val now = System.nanoTime()
                val doneNs = max(fenceStartNs, now - detectionLagNs)
                acc.onEstimateMs(max(0L, doneNs - estFirstSubmitNs) / 1e6f)
                estimateLatency.onSample(doneNs - fenceStartNs)
                GLES30.glDeleteSync(fence); fence = 0L
                if (complexityPending) consumeComplexity()
                false
            }
            GLES30.GL_WAIT_FAILED -> { GLES30.glDeleteSync(fence); fence = 0L; false }
            else -> true
        }
    }

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

    /** Hue (degrees) and colour temperature (-1 cool .. 1 warm); the booster draws the video itself, so it does both. */
    fun setColorTune(hueDegrees: Float, temperature: Float) {
        colorDelta = ColorTune.deltaColumnMajor(hueDegrees, temperature)
        dirty = true
    }

    /** Rotate / flip / mirror of the picture; applied in the present pass only (see [PRESENT_VERTEX]). */
    fun setOrientation(orientation: VideoOrientation) {
        orientationMatrix = orientation.videoFromScreen()
        dirty = true
    }

    /** Deinterlacing done in the copy pass: 0 = off, 1 = auto, 2 = always (see [DeinterlaceGlsl]). */
    fun setDeinterlace(mode: Int) {
        if (mode == deinterlaceMode) return
        deinterlaceMode = mode
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

    /**
     * Replaces the hand-tuned "is this warp trustworthy" logic by a small trained network (generated GLSL, no runtime,
     * no model file). Off by default. If the GPU cannot build it the renderer keeps the hand-tuned path and this stays
     * off: it never takes the booster down.
     */
    fun setAiFusion(enabled: Boolean) {
        if (aiFusionWanted == enabled) return
        aiFusionWanted = enabled
        handler.post { if (!released && eglContext != EGL14.EGL_NO_CONTEXT) applyAiFusion() }
    }

    /** Posts an action to run on the GL thread. */
    fun postToGlThread(action: Runnable) {
        if (!released) handler.post(action)
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
        // The motion search is heavy GPU work that shares the GPU with the app's UI (controls, sheets, Compose
        // animations). Ask for a LOW priority context where the driver supports it, so the UI's frames preempt the
        // booster instead of waiting behind it. Optional: any failure falls back to the default priority.
        var ctx = EGL14.EGL_NO_CONTEXT
        if (targetFps <= 60 && EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS)?.contains("EGL_IMG_context_priority") == true) {
            ctx = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG,
                    EGL14.EGL_NONE
                ), 0
            )
            if (ctx == EGL14.EGL_NO_CONTEXT) Log.w(TAG, "low-priority EGL context refused, using default priority")
        }
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            ctx = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
        }
        eglContext = ctx
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
        riskProg = Prog(VERTEX, asset("risk_map.frag"))
        globalProg = Prog(VERTEX, asset("global_motion.frag"))
        interpProg = Prog(PRESENT_VERTEX, asset("flow_interp.frag"))
        blitProg = Prog(VERTEX, BLIT_FRAGMENT)
        if (aiFusionWanted) applyAiFusion()

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
        if (width == srcW && height == srcH && slots.isNotEmpty() && sameFrameSize(width, height, lv)) {
            // Only the motion-search settings changed (the stored frame has the video's own size at every level): keep
            // the frames, the queue and the timing state, and replace just the analysis data. No black frame, no lost
            // history, no hold / timestamp re-learning on every adaptive step.
            appliedLevel = lv
            reconfigureAnalysis(lv, width, height)
            return
        }
        srcW = width
        srcH = height
        appliedLevel = lv
        cancelEstimate()
        estimatePacer.reset()
        vsyncEstimator.reset()
        freeResources()

        FrameSizing.frame(width, height, lv.frameEdge).let { frameW = it.first; frameH = it.second }
        setupLevels(width, height, lv)

        slots = Array(SLOT_COUNT + if (rifeOverlayHook?.supportsMidFrames() == true) MID_EXTRA_SLOTS else 0) {
            Slot(
                full = newTexture(frameW, frameH, GLES30.GL_RGBA8),
                luma = newLuma(),
                fw = newTexture(gridW[0], gridH[0], GLES30.GL_RGBA16F),
                bw = newTexture(gridW[0], gridH[0], GLES30.GL_RGBA16F),
                cut = newTexture(1, 1, GLES30.GL_RGBA16F),
                glob = newTexture(1, 1, GLES30.GL_RGBA16F),
                risk = newTexture(riskW(), riskH(), GLES30.GL_RGBA16F)
            )
        }
        allocFlowTextures()
        queue.clear(); freeSlots.clear(); freeSlots.addAll(slots)
        midChain.clear(); deferredB = null
        holdCtl.reset()
        estimateLatency.reset()
        tsRegularizer.reset()

        // Some GPUs advertise the extension but still reject RGBA16F attachments: verify once, loudly.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, slots[0].fw, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "RGBA16F framebuffer incomplete"
        }

        lastDrawnA = null; lastDrawnB = null
        dirty = true
        acc.reset()
        frameClassifier.reset()
        deadline.reset()
        complexityClassifier.reset(); pairComplexity = MotionComplexity.UNKNOWN; complexityPending = false
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
        val ptsNs = FramePts.lookupNs(ts)
        // A codec that did not stamp a render time gives a value that is not on our clock: use arrival time.
        if (abs(ts - now) > 2_000_000_000L) ts = now

        val slot = acquireSlot()
        uploadFrame(slot)
        slot.hasFlow = false
        slot.flowSkipped = false
        acc.onSourceFrame()

        val last = queue.lastOrNull()
        val prevRawTs = lastRawTsNs
        val gap = if (last != null) ts - prevRawTs else 0L
        lastRawTsNs = ts
        val continuous = last != null && MotionBoostTiming.isContinuous(prevRawTs, ts) &&
            !FramePacing.isGap(gap, frameIntervalNs)
        if (continuous) {
            if (gap in MIN_FRAME_NS..MAX_FRAME_NS) {
                frameIntervalNs = if (frameIntervalNs == 0L) gap else (frameIntervalNs * 7 + gap) / 8
            }
        } else {
            // seek / pause / new clip: never interpolate across it
            cancelEstimate()
            while (queue.isNotEmpty()) freeSlots.add(queue.removeAt(0))
            midChain.clear(); deferredB = null
            holdCtl.reset()
            tsRegularizer.reset()
        }
        // The decoder releases frames on vsync, so the stamp is quantised to the panel grid (24 fps on 60 Hz
        // arrives as 33 / 50 ms steps). Evenly spaced source frames get evenly spaced stamps back.
        ts = tsRegularizer.next(ts, vsyncPeriodNs, ptsNs)
        slot.tsNs = ts
        rifeOverlayHook?.onFrameCopied(slot.full, ts, frameW, frameH)
        if (continuous) holdCtl.onFrame(ts - now)
        slot.arrivalNs = now
        slot.midPending = false
        slot.flowPending = false
        slot.flowDeferred = false
        slot.isMid = false
        queue.add(slot)

        if (continuous && wantsInterpolation()) {
            if (rifeOverlayHook?.wantsMidFrame() == true) {
                // RIFE first: wait for the AI middle frame, then estimate (A, M) and (M, B). The deadline in serviceMid
                // falls back to the plain (A, B) estimate, so a slow network never stalls the picture.
                slot.midPending = true
                slot.flowPending = true
                slot.midDeadlineNs = now + midWaitNs()
            } else if (gpuBusy()) {
                // The previous estimate is still running. One pair may wait for it (started from the vsync tick as soon
                // as the GPU is free); more than one would only grow the backlog that delays every later estimate and
                // every presented frame, so any further pair is shown as real frames.
                val waiting = deferredB
                if (waiting == null || !waiting.flowDeferred || !queue.contains(waiting)) {
                    slot.flowDeferred = true
                    deferredB = slot
                } else {
                    slot.flowSkipped = true
                    acc.onEstimateSkipped()
                }
            } else {
                beginEstimate(last!!, slot, now)
            }
        }
    }

    private fun wantsInterpolation(): Boolean {
        val sourceFps = if (frameIntervalNs > 0) 1_000_000_000f / frameIntervalNs else 0f
        return !MotionBoostTiming.shouldBypass(sourceFps, cadence.effectiveTarget(targetFps))
    }

    /** A free slot, or the oldest queued frame when every slot is in use (a slow GPU drops source frames). */
    private fun acquireSlot(): Slot {
        if (freeSlots.isEmpty()) {
            val idx = queue.indexOfFirst { it !== estA && it !== estB }
            if (idx >= 0) {
                freeSlots.add(queue.removeAt(idx))
            } else {
                freeSlots.add(queue.removeAt(0))
            }
        }
        return freeSlots.removeAt(freeSlots.size - 1)
    }


    private fun sameFrameSize(width: Int, height: Int, lv: QualityLevel): Boolean =
        FrameSizing.frame(width, height, lv.frameEdge).let { it.first == frameW && it.second == frameH }

    /** Pyramid, grid and work sizes of [lv] for a [width] x [height] source. */
    private fun setupLevels(width: Int, height: Int, lv: QualityLevel) {
        val (wW, wH) = FrameSizing.work(width, height, lv.workEdge)
        levels = FlowPyramid.levels(wW, wH)
        levelW = levels.width
        levelH = levels.height
        gridW = levels.gridW
        gridH = levels.gridH
    }

    private fun riskW() = (levelW[0] + RISK_CELL - 1) / RISK_CELL
    private fun riskH() = (levelH[0] + RISK_CELL - 1) / RISK_CELL
    private fun newLuma() = IntArray(levels.count) { l -> newTexture(levelW[l], levelH[l], GLES30.GL_R8) }

    /** Shared flow ping/pong textures, the global-motion textures and (when on) the AI fusion map. */
    private fun allocFlowTextures() {
        val n = levels.count
        flowTex = Array(2) { Array(n) { l -> IntArray(2) { newTexture(gridW[l], gridH[l], GLES30.GL_RGBA16F) } } }
        if (fuseProg != null) {
            try { allocFuseTextures() } catch (t: Throwable) {
                Log.w(TAG, "AI fusion map allocation failed, keeping the hand-tuned blend", t)
                dropAiFusion()
            }
        }
        globalCoarse = IntArray(2) { newTexture(1, 1, GLES30.GL_RGBA16F) }
    }

    /**
     * Replaces the analysis textures for quality level [lv] and keeps everything about the frames. Queued frames get
     * their luma pyramid rebuilt from the stored picture at the new work size; their old flow is gone, so they show
     * as real frames until the next pair brings a new estimate (at most one source interval).
     */
    private fun reconfigureAnalysis(lv: QualityLevel, width: Int, height: Int) {
        cancelEstimate()
        estimatePacer.reset()
        val old = ArrayList<Int>()
        slots.forEach { sl -> sl.luma.forEach { old.add(it) }; old.add(sl.fw); old.add(sl.bw); old.add(sl.cut); old.add(sl.glob); old.add(sl.risk) }
        flowTex.forEach { dir -> dir.forEach { pair -> pair.forEach { old.add(it) } } }
        globalCoarse.forEach { old.add(it) }
        fuseTex.forEach { old.add(it) }
        fuseTex = IntArray(0)
        if (old.isNotEmpty()) GLES30.glDeleteTextures(old.size, old.toIntArray(), 0)

        setupLevels(width, height, lv)
        for (sl in slots) {
            sl.luma = newLuma()
            sl.fw = newTexture(gridW[0], gridH[0], GLES30.GL_RGBA16F)
            sl.bw = newTexture(gridW[0], gridH[0], GLES30.GL_RGBA16F)
            sl.cut = newTexture(1, 1, GLES30.GL_RGBA16F)
            sl.glob = newTexture(1, 1, GLES30.GL_RGBA16F)
            sl.risk = newTexture(riskW(), riskH(), GLES30.GL_RGBA16F)
            sl.hasFlow = false
            sl.flowPending = false
            sl.midPending = false
        }
        allocFlowTextures()
        midChain.clear()
        estimateLatency.reset()
        for (sl in queue) {
            buildLuma(sl)
            sl.flowSkipped = true
        }
        dirty = true
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
        GLES30.glUniform1f(copy.loc("uTexelH"), if (srcH > 0) 1f / srcH else 0f)
        GLES30.glUniform1i(copy.loc("uMode"), deinterlaceMode)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        buildLuma(slot)
    }

    /** Luma level 0 at work size from [Slot.full], then the pyramid. */
    private fun buildLuma(slot: Slot) {
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
    // RIFE first, optical flow second (mid frames)
    // =====================================================================

    private fun midWaitNs(): Long = (frameIntervalNs * 5 / 4).coerceIn(30_000_000L, 90_000_000L)

    /**
     * GL thread only. The AI engine finished the middle picture of real frames A ([aTsNs]) and B ([bTsNs]) in [synthTex].
     * It becomes a queue frame M halfway between them; flow is then estimated on (A, M) and (M, B), so every generated
     * output frame is a warp of one nearby sharp frame and no pair spans more than half a source interval.
     * Anything unexpected (frames gone, no free slot, wrong neighbour) just takes the plain path for this pair.
     */
    fun injectMidFrame(synthTex: Int, aTsNs: Long, bTsNs: Long) {
        if (released) return
        val b = queue.firstOrNull { it.tsNs == bTsNs && it.midPending } ?: return
        val bi = queue.indexOf(b)
        val a = queue.getOrNull(bi - 1)
        val bp = blitProg
        if (a == null || a.isMid || a.tsNs != aTsNs || freeSlots.isEmpty() || bp == null || synthTex == 0) {
            giveUpMid(b)
            return
        }
        val m = freeSlots.removeAt(freeSlots.size - 1)
        bindTarget(m.full, frameW, frameH)
        bp.use()
        tex(0, bp, "uSrc", synthTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        buildLuma(m)
        m.tsNs = HybridScheduling.midTimestampNs(a.tsNs, b.tsNs)
        m.arrivalNs = b.arrivalNs
        m.hasFlow = false
        m.flowSkipped = false
        m.midPending = false
        m.flowPending = true
        m.isMid = true
        queue.add(bi, m)
        b.midPending = false
        b.flowPending = true
        midChain.add(a to m)
        midChain.add(m to b)
        if (!gpuBusy()) startNextChained()
        dirty = true
    }

    /** GL thread only. The AI engine will not deliver a mid frame for the pair ending at [bTsNs] (cut, busy, failed). */
    fun midUnavailable(bTsNs: Long) {
        if (released) return
        queue.firstOrNull { it.tsNs == bTsNs && it.midPending }?.let { giveUpMid(it) }
    }

    /** Plain (previous, [b]) estimate for a pair that was waiting for a mid frame. */
    private fun giveUpMid(b: Slot) {
        b.midPending = false
        b.flowPending = false
        val i = queue.indexOf(b)
        if (i < 1) { b.flowSkipped = true; return }
        if (gpuBusy()) {
            b.flowSkipped = true
            acc.onEstimateSkipped()
        } else {
            beginEstimate(queue[i - 1], b, b.arrivalNs)
        }
    }

    /** Starts the next queued (A, M) / (M, B) estimate; entries whose frames have left the queue are marked skipped. */
    private fun startNextChained() {
        while (midChain.isNotEmpty()) {
            val (x, y) = midChain.removeAt(0)
            y.flowPending = false
            val ix = queue.indexOf(x)
            val iy = queue.indexOf(y)
            if (ix < 0 || iy != ix + 1) { y.flowSkipped = true; continue }
            beginEstimate(x, y, y.arrivalNs)
            return
        }
    }

    /**
     * Starts the waiting pair once the GPU is free. A pair whose second frame is already behind the picture on screen
     * is no longer worth estimating (it would only land after it was needed): it is counted as skipped instead.
     */
    private fun startDeferredEstimate(nowNs: Long) {
        val b = deferredB ?: return
        val i = queue.indexOf(b)
        if (!b.flowDeferred || i < 1) { b.flowDeferred = false; deferredB = null; return }   // left the queue / was recycled
        if (gpuBusy()) return
        b.flowDeferred = false
        deferredB = null
        val content = FramePacing.contentTimeNs(nowNs, vsyncPeriodNs, lastHoldNs)
        if (b.tsNs <= content) {
            b.flowSkipped = true
            acc.onEstimateSkipped()
            return
        }
        beginEstimate(queue[i - 1], b, b.arrivalNs)
    }

    /** Every vsync: give up on mid frames that are late, and feed the GPU the next queued estimate. */
    private fun serviceMid(nowNs: Long) {
        var i = 1
        while (i < queue.size) {
            val s = queue[i]
            if (s.midPending && nowNs > s.midDeadlineNs) giveUpMid(s)
            i++
        }
        if (midChain.isNotEmpty() && !gpuBusy()) startNextChained()
    }

    // =====================================================================
    // Motion estimation
    // =====================================================================

    private fun search(
        ref: Int, tgt: Int, parent: Int, dst: Int, lvl: Int, mode: Int, range: Int, subpel: Boolean, parentLvl: Int,
        global: Int, globalScale: Float, fullCost: Boolean
    ) {
        val p = searchProg!!
        bindTarget(dst, gridW[lvl], gridH[lvl])
        p.use()
        tex(0, p, "uRef", ref); tex(1, p, "uTgt", tgt); tex(2, p, "uParent", parent); tex(3, p, "uGlobal", global)
        val pw = if (parentLvl >= 0) levelW[parentLvl] else levelW[lvl]
        val ph = if (parentLvl >= 0) levelH[parentLvl] else levelH[lvl]
        val pgw = if (parentLvl >= 0) gridW[parentLvl] else gridW[lvl]
        val pgh = if (parentLvl >= 0) gridH[parentLvl] else gridH[lvl]
        GLES30.glUniform2i(p.loc("uSize"), levelW[lvl], levelH[lvl])
        GLES30.glUniform2i(p.loc("uGrid"), gridW[lvl], gridH[lvl])
        GLES30.glUniform2i(p.loc("uParentGrid"), pgw, pgh)
        GLES30.glUniform2f(p.loc("uScale"), levelW[lvl].toFloat() / pw, levelH[lvl].toFloat() / ph)
        GLES30.glUniform1f(p.loc("uGlobalScale"), globalScale)
        GLES30.glUniform1i(p.loc("uMode"), mode)
        GLES30.glUniform1i(p.loc("uRange"), range)
        GLES30.glUniform1i(p.loc("uSubpel"), if (subpel) 1 else 0)
        GLES30.glUniform1i(p.loc("uFullCost"), if (fullCost) 1 else 0)
        GLES30.glUniform1f(p.loc("uLambda"), LAMBDA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Dominant motion of the [lvl]-level field [flow] into the 1x1 texture [dst] (no read-back). */
    private fun globalMotion(flow: Int, lvl: Int, dst: Int) {
        val p = globalProg!!
        bindTarget(dst, 1, 1)
        p.use()
        tex(0, p, "uFlow", flow)
        val (sx, sy) = FlowPyramid.globalSamples(gridW[lvl], gridH[lvl])
        GLES30.glUniform2i(p.loc("uGrid"), gridW[lvl], gridH[lvl])
        GLES30.glUniform2i(p.loc("uSamples"), sx, sy)
        GLES30.glUniform1f(p.loc("uMaxCost"), FlowPyramid.GLOBAL_MAX_COST)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun cancelEstimate() {
        estSteps.clear()
        estIdx = 0
        estA = null
        estB = null
        estArrivalNs = 0L
        estFirstSubmitNs = 0L
        if (fence != 0L) {
            GLES30.glDeleteSync(fence)
            fence = 0L
        }
        complexityPending = false
    }

    /** Copies the dominant-motion texel (offset 0) and the cut texel (offset 16) of [b] into the complexity PBO; GPU-side, non-blocking. */
    private fun queueComplexityReadback(b: Slot) {
        if (complexityPbo == 0) {
            val id = IntArray(1)
            GLES30.glGenBuffers(1, id, 0)
            complexityPbo = id[0]
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, complexityPbo)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, COMPLEXITY_BYTES, null, GLES30.GL_STREAM_READ)
        } else {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, complexityPbo)
        }
        bindTarget(b.glob, 1, 1)
        GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, 0)
        bindTarget(b.cut, 1, 1)
        GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, 16)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        complexityPending = true
    }

    /** Called once the estimate fence has signalled: the PBO is ready, so mapping it does not wait for the GPU. */
    private fun consumeComplexity() {
        complexityPending = false
        if (complexityPbo == 0) return
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, complexityPbo)
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, COMPLEXITY_BYTES, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
        if (mapped != null) {
            val f = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val c = complexityClassifier.onPair(
                dominantX = f.get(0), dominantY = f.get(1), support = f.get(2),
                meanCost = f.get(4), poorShare = f.get(5), cutVerdict = f.get(6),
                workEdge = if (levelW.isNotEmpty()) max(levelW[0], levelH[0]) else 0
            )
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            pairComplexity = if (c == MotionComplexity.CUT) MotionComplexity.UNKNOWN else c
            acc.onComplexity(c)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    private fun pumpEstimate() {
        if (estIdx >= estSteps.size) return
        val slice = if (estFirstSubmitNs == 0L) estPerTick else estimatePacer.slice(estPerTick)   // the first slice is never held back
        if (slice == 0) return
        if (estFirstSubmitNs == 0L) estFirstSubmitNs = System.nanoTime()
        val end = min(estIdx + slice, estSteps.size)
        while (estIdx < end) {
            estSteps[estIdx].invoke()
            estIdx++
        }
        GLES30.glFlush()
    }

    private fun beginEstimate(a: Slot, b: Slot, arrivalNs: Long) {
        cancelEstimate()
        estA = a
        estB = b
        estArrivalNs = arrivalNs
        estFirstSubmitNs = 0L

        val n = levelW.size
        // Search effort of THIS pair from the scene's measured complexity (1-pair lag). Per pair, so it costs no reallocation.
        val plan = MotionPlan.plan(pairComplexity, level, gpuHeadroom = estimatePacer.share >= 1.0)

        // Forward flow search per pyramid level
        for (lvl in n - 1 downTo 0) {
            val fA = flowTex[0][lvl][0]
            val fB = flowTex[0][lvl][1]
            val sub = lvl == 0 && plan.subpixel
            val exact = levels.needsExactCost(lvl)
            val gScale = if (lvl < n - 1) levels.globalScale(lvl) else 0f
            val g = globalCoarse[0]

            if (lvl == n - 1) {
                estSteps.add { search(a.luma[lvl], b.luma[lvl], fB, fA, lvl, 2, plan.searchRange, false, -1, g, 0f, false) }
            } else {
                val parentRes = flowTex[0][lvl + 1][1]
                estSteps.add { search(a.luma[lvl], b.luma[lvl], parentRes, fA, lvl, 0, 0, false, lvl + 1, g, gScale, false) }
            }
            estSteps.add { search(a.luma[lvl], b.luma[lvl], fA, fB, lvl, 1, 0, sub, -1, g, gScale, exact) }
            if (lvl == n - 1 && n > 1) {
                estSteps.add { globalMotion(fB, lvl, g) }
            }
        }

        // Backward flow search per pyramid level
        for (lvl in n - 1 downTo 0) {
            val fA = flowTex[1][lvl][0]
            val fB = flowTex[1][lvl][1]
            val sub = lvl == 0 && plan.subpixel
            val exact = levels.needsExactCost(lvl)
            val gScale = if (lvl < n - 1) levels.globalScale(lvl) else 0f
            val g = globalCoarse[1]

            if (lvl == n - 1) {
                estSteps.add { search(b.luma[lvl], a.luma[lvl], fB, fA, lvl, 2, plan.searchRange, false, -1, g, 0f, false) }
            } else {
                val parentRes = flowTex[1][lvl + 1][1]
                estSteps.add { search(b.luma[lvl], a.luma[lvl], parentRes, fA, lvl, 0, 0, false, lvl + 1, g, gScale, false) }
            }
            estSteps.add { search(b.luma[lvl], a.luma[lvl], fA, fB, lvl, 1, 0, sub, -1, g, gScale, exact) }
            if (lvl == n - 1 && n > 1) {
                estSteps.add { globalMotion(fB, lvl, g) }
            }
        }

        // Consistency x2
        estSteps.add {
            val cons = consProg!!
            bindTarget(b.fw, gridW[0], gridH[0])
            cons.use()
            tex(0, cons, "uSelf", flowTex[0][0][1]); tex(1, cons, "uOther", flowTex[1][0][1])
            GLES30.glUniform2i(cons.loc("uGrid"), gridW[0], gridH[0])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        estSteps.add {
            val cons = consProg!!
            bindTarget(b.bw, gridW[0], gridH[0])
            cons.use()
            tex(0, cons, "uSelf", flowTex[1][0][1]); tex(1, cons, "uOther", flowTex[0][0][1])
            GLES30.glUniform2i(cons.loc("uGrid"), gridW[0], gridH[0])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Global motion on b.fw
        estSteps.add { globalMotion(b.fw, 0, b.glob) }

        // Cut detect
        estSteps.add {
            val cut = cutProg!!
            bindTarget(b.cut, 1, 1)
            cut.use()
            tex(0, cut, "uFlow", flowTex[0][n - 1][1])
            tex(1, cut, "uCons", b.fw)
            GLES30.glUniform2i(cut.loc("uGrid"), gridW[n - 1], gridH[n - 1])
            GLES30.glUniform2i(cut.loc("uConsGrid"), gridW[0], gridH[0])
            GLES30.glUniform1f(cut.loc("uMeanThresh"), CUT_MEAN)
            GLES30.glUniform1f(cut.loc("uPoorThresh"), CUT_POOR)
            // The previous pair's verdict tells a whip pan (many flagged pairs in a row: cross-fade) from a hard cut
            // (one isolated flagged pair: show the nearest real frame). a.cut is the previous pair's result.
            tex(2, cut, "uPrev", a.cut)
            GLES30.glUniform1i(cut.loc("uPrevValid"), if (a.hasFlow) 1 else 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Risk map
        estSteps.add {
            val risk = riskProg!!
            bindTarget(b.risk, (levelW[0] + RISK_CELL - 1) / RISK_CELL, (levelH[0] + RISK_CELL - 1) / RISK_CELL)
            risk.use()
            tex(0, risk, "uA", a.full); tex(1, risk, "uB", b.full); tex(2, risk, "uFw", b.fw); tex(3, risk, "uBw", b.bw); tex(4, risk, "uGlobal", b.glob)
            GLES30.glUniform2f(risk.loc("uWork"), levelW[0].toFloat(), levelH[0].toFloat())
            GLES30.glUniform2f(risk.loc("uFlowUv"), levelW[0].toFloat() / (gridW[0] * PITCH), levelH[0].toFloat() / (gridH[0] * PITCH))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Final step
        estSteps.add {
            b.hasFlow = true
            b.flowSkipped = false
            queueComplexityReadback(b)
            fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            fenceStartNs = estArrivalNs
            GLES30.glFlush()
            estA = null
            estB = null
        }

        val ticksAvail = max(1, ((0.5 * frameIntervalNs) / vsyncPeriodNs).toInt())
        estPerTick = max(1, kotlin.math.ceil(estSteps.size.toDouble() / ticksAvail).toInt())
        estIdx = 0
        pumpEstimate()
    }

    // =====================================================================
    // Presentation (every vsync)
    // =====================================================================

    private fun tick(vsyncNs: Long) {
        if (lastVsyncNs != 0L) {
            vsyncPeriodNs = vsyncEstimator.onGap(vsyncNs - lastVsyncNs, panelHint.hz(vsyncNs))
        }
        lastVsyncNs = vsyncNs
        // Retire the finished motion estimate (records its latency) with one non-blocking check per vsync.
        if (fence != 0L) gpuBusy(vsyncPeriodNs / 2)
        startDeferredEstimate(vsyncNs)
        rifeOverlayHook?.onTick()
        emitWindow(vsyncNs)
        serviceMid(vsyncNs)

        if (queue.isEmpty()) return
        // One output frame every n whole vsyncs: an even cadence, whatever target was asked for.
        val step = cadence.stepNs(targetFps, vsyncPeriodNs)
        acc.onCadence(cadence.vsyncsPerFrame, cadence.displayHz)
        if (!MotionBoostTiming.isDue(vsyncNs, nextDueNs, vsyncPeriodNs)) {
            // Not a presenting vsync (30 / 40 / 60 FPS on a faster panel): spend it on the analysis. The slice is
            // planned per vsync, so the estimate used to take n times longer than planned with n vsyncs per frame.
            pumpEstimate()
            return
        }

        val newest = queue.last()
        // How far behind its due time this frame is being produced (also used for the drop count and the estimate pacer below).
        val lateNowNs = if (nextDueNs != 0L) vsyncNs - nextDueNs else 0L
        var a = newest
        var b = newest
        var alpha = 1f
        var warp = false
        var hold = 0L
        var shedThisFrame = false
        if (queue.size >= 2 && wantsInterpolation()) {
            hold = holdCtl.update(frameIntervalNs, vsyncPeriodNs, estimateLatency.smoothedNs)
            val content = FramePacing.contentTimeNs(vsyncNs, vsyncPeriodNs, hold)
            val pick = FrameSelection.pick(queue.size, { queue[it].tsNs }, content)
            a = queue[pick.a]; b = queue[pick.b]; alpha = pick.alpha
            // frames older than the pair are no longer needed
            var i = 0
            while (i < pick.a && queue.isNotEmpty()) {
                val head = queue[0]
                if (head === estA || head === estB) break
                freeSlots.add(queue.removeAt(0))
                i++
            }
            if (a !== b) {
                // Rare: interpolation was switched on after b arrived. Only when the GPU is idle - a late estimate
                // queued behind a running one would delay this very frame - and never for a pair that was skipped.
                if (!b.hasFlow && !b.flowSkipped && !b.flowPending && !b.flowDeferred && !gpuBusy()) beginEstimate(a, b, System.nanoTime())
                warp = b.hasFlow
                if (warp && !deadline.shouldGenerate(cadence.effectiveTarget(targetFps).toFloat(), lateNowNs, vsyncPeriodNs)) {
                    // Already a vsync behind on this frame: a full warp would make the next ones late too. A real frame
                    // is shown instead (counted as original / repeated, never as generated) so the queue can drain.
                    warp = false
                    shedThisFrame = true
                }
                if (!warp) {
                    // No flow for this pair (estimate skipped or still running): the nearest REAL frame by phase,
                    // not always b - which would jump ahead by up to one source frame for the first half.
                    val nearest = if (alpha < 0.5f) a else b
                    a = nearest; b = nearest; alpha = 1f
                }
            }
        } else {
            // Not interpolating (source already at target rate, or only one frame): newest frame, lowest latency.
            while (queue.size > 1) {
                val head = queue[0]
                if (head === estA || head === estB) break
                freeSlots.add(queue.removeAt(0))
            }
        }
        lastHoldNs = hold

        if (!dirty && a === lastDrawnA && b === lastDrawnB && a.tsNs == lastDrawnTsA && b.tsNs == lastDrawnTsB &&
            alpha == lastDrawnAlpha && warp == lastDrawnWarp
        ) {
            // paused / nothing new: leave the last presented frame; do not count the idle time as missed output
            nextDueNs = vsyncNs + step
            pumpEstimate()
            return
        }

        // Frames that were due but could not be produced in time (GPU / thread stall).
        if (nextDueNs != 0L && vsyncNs - nextDueNs > step + vsyncPeriodNs / 2) {
            acc.onDropped(((vsyncNs - nextDueNs) / step).toInt().coerceIn(1, 8))
            acc.onMissedVsyncs(((vsyncNs - nextDueNs) / vsyncPeriodNs).toInt().coerceIn(1, 16))
        }

        val lateNs = lateNowNs
        val workStart = System.nanoTime()
        drawOutput(a = a, b = b, alpha = alpha, warp = warp)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            fail(FallbackReason.GPU_ERROR, "eglSwapBuffers failed (0x${Integer.toHexString(EGL14.eglGetError())})", null)
            return
        }
        val drawMs = (System.nanoTime() - workStart) / 1e6f
        acc.onDrawMs(drawMs)
        // "slow" is judged against the OUTPUT step (2 vsyncs at 60 FPS on 120 Hz), not one panel vsync: 5 ms of draw+swap is normal there
        estimatePacer.onPresented(lateNs >= vsyncPeriodNs, drawMs, step / 1e6f)
        pumpEstimate()
        reportWorkDuration(System.nanoTime() - workStart, step)
        nextDueNs = MotionBoostTiming.nextDueOnGrid(vsyncNs, step)
        lastDrawnA = a; lastDrawnB = b; lastDrawnTsA = a.tsNs; lastDrawnTsB = b.tsNs
        lastDrawnAlpha = alpha; lastDrawnWarp = warp
        dirty = false
        acc.onPresented(frameClassifier.classify(warp, alpha, a.tsNs, b.tsNs))
        if (shedThisFrame) acc.onShed()   // only a frame that was really presented counts
    }

    // ---- Performance hint (ADPF, Android 12+) ----
    // Tells the phone's scheduler that this thread is a real-time job with a deadline of one output frame, so it raises
    // the CPU / GPU clocks in time instead of after frames were already late. Mid-range chips (Dimensity, Snapdragon 7)
    // idle their clocks down between light frames, and that ramp-up delay is a common cause of uneven FPS.
    private var hintSession: PerformanceHintManager.Session? = null
    private var hintTargetNs = 0L
    private var hintFailed = false

    private fun reportWorkDuration(workNs: Long, targetNs: Long) {
        if (hintFailed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || workNs <= 0L || targetNs <= 0L) return
        try {
            var session = hintSession
            if (session == null) {
                val mgr = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                session = mgr?.createHintSession(intArrayOf(thread.threadId), targetNs)
                if (session == null) { hintFailed = true; return }
                hintSession = session
                hintTargetNs = targetNs
            }
            if (targetNs != hintTargetNs) {
                session.updateTargetWorkDuration(targetNs)
                hintTargetNs = targetNs
            }
            session.reportActualWorkDuration(workNs)
        } catch (t: Throwable) {
            Log.w(TAG, "Performance hint unavailable", t)
            hintFailed = true
            runCatching { hintSession?.close() }
            hintSession = null
        }
    }

    private fun emitWindow(nowNs: Long) {
        val w = acc.poll(nowNs) ?: return
        val sourceFps = if (frameIntervalNs > 0) 1_000_000_000f / frameIntervalNs else w.sourceFps
        callbacks.onWindow(w, sourceFps, levelW.firstOrNull() ?: 0, lastHoldNs / 1e6f)
    }

    /**
     * Builds (or removes) the AI programs on the GL thread. The plain hand-tuned program ([interpProg]) is never
     * replaced: the AI composite is a separate program ([aiInterpProg]) that [drawOutput] only uses while the fusion map
     * exists, so a failure at any point (compile, two float targets, allocation) leaves the picture exactly as it was.
     */
    private fun applyAiFusion() {
        val want = aiFusionWanted
        if (want == (fuseProg != null)) return
        if (!want) {
            aiInterpProg?.delete(); fuseProg?.delete()
            aiInterpProg = null; fuseProg = null
            freeFuseTextures()
            dirty = true
            return
        }
        var newInterp: Prog? = null
        var newFuse: Prog? = null
        try {
            val base = asset("flow_interp.frag")
            val maxDraw = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, maxDraw, 0)
            check(maxDraw[0] >= 2) { "needs 2 draw buffers" }
            newFuse = Prog(VERTEX, InterpShaderSource.build(base, fuse = true, weights = asset("ai_fusion.glsl")))
            newInterp = Prog(PRESENT_VERTEX, InterpShaderSource.build(base, ai = true))
            fuseProg = newFuse
            aiInterpProg = newInterp
            freeFuseTextures()
            allocFuseTextures()          // no-op until the source size is known; applySourceSize allocates then
        } catch (t: Throwable) {
            Log.w(TAG, "AI fusion unavailable, keeping the hand-tuned blend", t)
            newFuse?.delete(); newInterp?.delete()
            fuseProg = null; aiInterpProg = null
            dropAiFusion()
        }
        dirty = true
    }

    /** Back to the hand-tuned blend for good (until [setAiFusion] is called again): frees the AI programs and the map. */
    private fun dropAiFusion() {
        aiInterpProg?.delete(); fuseProg?.delete()
        aiInterpProg = null; fuseProg = null
        freeFuseTextures()           // (allocFuseTextures already restored the single-target state before it failed)
        aiFusionWanted = false
    }

    private fun allocFuseTextures() {
        if (levelW.isEmpty()) return   // sizes not known yet: applySourceSize allocates
        val (w, h) = InterpShaderSource.fuseSize(levelW[0], levelH[0])
        fuseW = w; fuseH = h
        fuseTex = IntArray(2) { newTexture(w, h, GLES30.GL_RGBA16F) }
        // verify once that the GPU accepts two float attachments
        bindFuseTargets()
        val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        releaseFuseTargets()
        check(ok) { "fusion map framebuffer incomplete" }
    }

    private fun freeFuseTextures() {
        if (fuseTex.isNotEmpty()) GLES30.glDeleteTextures(fuseTex.size, fuseTex, 0)
        fuseTex = IntArray(0)
    }

    private fun bindFuseTargets() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fuseTex[0], 0)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + 1, GLES30.GL_TEXTURE_2D, fuseTex[1], 0)
        GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT0 + 1), 0)
    }

    /** Back to the single-target state every other pass assumes. */
    private fun releaseFuseTargets() {
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + 1, GLES30.GL_TEXTURE_2D, 0, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
    }

    /** Uniforms / textures the hand-tuned program, the fusion pass and the AI composite all read. */
    private fun setInterpInputs(p: Prog, a: Slot, b: Slot, alpha: Float, warp: Boolean) {
        tex(0, p, "uA", a.full); tex(1, p, "uB", b.full)
        tex(2, p, "uFw", b.fw); tex(3, p, "uBw", b.bw); tex(4, p, "uCut", b.cut); tex(5, p, "uGlobal", b.glob); tex(8, p, "uRisk", b.risk)
        GLES30.glUniform1f(p.loc("uT"), if (warp) alpha else 1f)
        GLES30.glUniform1i(p.loc("uWarp"), if (warp) 1 else 0)
        GLES30.glUniform2f(p.loc("uWork"), levelW[0].toFloat(), levelH[0].toFloat())
        GLES30.glUniform2f(p.loc("uFlowUv"), levelW[0].toFloat() / (gridW[0] * PITCH), levelH[0].toFloat() / (gridH[0] * PITCH))
        GLES30.glUniform3f(p.loc("uGrade"), brightness, contrast, saturation)
        GLES30.glUniformMatrix3fv(p.loc("uColorDelta"), 1, false, colorDelta, 0)
        GLES30.glUniform1f(p.loc("uConsLo"), CONS_LO)
        GLES30.glUniform1f(p.loc("uConsHi"), CONS_HI)
        GLES30.glUniform1i(p.loc("uSharp"), if (PRESERVE_SHARPNESS) 1 else 0)
    }

    /** Writes the fusion map for this pair / phase (once per generated frame, at motion-search size). */
    private fun fusePass(p: Prog, a: Slot, b: Slot, alpha: Float) {
        bindFuseTargets()
        GLES30.glViewport(0, 0, fuseW, fuseH)
        p.use()
        setInterpInputs(p, a, b, alpha, true)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        releaseFuseTargets()
    }

    private fun drawOutput(a: Slot, b: Slot, alpha: Float, warp: Boolean) {
        val fp = fuseProg
        val aip = aiInterpProg
        val aiReady = fp != null && aip != null && fuseTex.size == 2
        if (aiReady && warp) fusePass(fp!!, a, b, alpha)
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, max(1, w[0]), max(1, h[0]))

        val ip = if (aiReady) aip!! else interpProg!!
        ip.use()
        setInterpInputs(ip, a, b, alpha, warp)
        if (aiReady) { tex(6, ip, "uFuse0", fuseTex[0]); tex(7, ip, "uFuse1", fuseTex[1]) }
        val rifeTex = rifeOverlayHook?.getRifeTexture(a.tsNs, b.tsNs) ?: 0
        tex(8, ip, "uRife", if (rifeTex != 0) rifeTex else 0)
        GLES30.glUniform1i(ip.loc("uRifeValid"), if (rifeTex != 0) 1 else 0)
        GLES30.glUniformMatrix2fv(ip.loc("uOrient"), 1, false, orientationMatrix, 0)
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
        cancelEstimate()
        val ids = ArrayList<Int>()
        slots.forEach { sl -> ids.add(sl.full); sl.luma.forEach { ids.add(it) }; ids.add(sl.fw); ids.add(sl.bw); ids.add(sl.cut); ids.add(sl.glob); ids.add(sl.risk) }
        flowTex.forEach { dir -> dir.forEach { pair -> pair.forEach { ids.add(it) } } }
        globalCoarse.forEach { ids.add(it) }
        fuseTex.forEach { ids.add(it) }
        fuseTex = IntArray(0)
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        slots = emptyArray(); flowTex = emptyArray(); globalCoarse = IntArray(0)
        queue.clear(); freeSlots.clear(); midChain.clear(); deferredB = null
    }

    private fun releaseGl() {
        runCatching { hintSession?.close() }
        hintSession = null
        runCatching {
            rifeOverlayHook?.onGlRelease()
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            freeResources()
            if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (complexityPbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(complexityPbo), 0)
            complexityPbo = 0
            listOf(copyProg, lumaProg, downProg, searchProg, consProg, cutProg, riskProg, globalProg, interpProg, blitProg, aiInterpProg, fuseProg).forEach { it?.delete() }
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
        // EGL_IMG_context_priority (not in android.opengl.EGL14)
        private const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
        private const val EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103
        private const val MID_EXTRA_SLOTS = 2   // AI middle frames live in the same queue as the decoded ones
        private const val SLOT_COUNT = 4         // bounded frame queue: the shown pair plus up to two frames that arrived early
        private const val PITCH = FlowPyramid.PITCH   // flow block pitch (matches flow_search.frag)
        private const val LAMBDA = 0.003f         // flow smoothness penalty per px
        private const val CONS_LO = 1.0f          // forward/backward inconsistency (px): trusted below
        private const val CONS_HI = 3.0f          //                                        rejected above
        /** Generated frames are resampled bicubically from the nearest real frame, so they are as sharp as the original. */
        private const val PRESERVE_SHARPNESS = true
        private const val CUT_MEAN = 0.12f
        /** Two RGBA32F texels: dominant motion (offset 0) and cut verdict (offset 16). */
        private const val COMPLEXITY_BYTES = 32
        private const val CUT_POOR = 0.10f
        /** Work px per texel of the regional risk map (risk_map.frag). Keep in sync with RISK_CELL in new_engine.py. */
        private const val RISK_CELL = 16
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

        /**
         * Vertex shader of the final (on-screen) pass only. uOrient maps a screen position to the picture position to
         * sample, about the picture centre, so rotate / flip / mirror cost nothing and every texture read of the
         * fragment shader (frames, motion vectors) stays in picture space.
         */
        private const val PRESENT_VERTEX = """#version 300 es
uniform mat2 uOrient;
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = uOrient * (p - 0.5) + 0.5;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        /** Plain bilinear copy of a texture (the AI middle frame, model size) into a full-size frame slot. */
        private const val BLIT_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
in vec2 vUv;
out vec4 o;
void main() {
  o = vec4(texture(uSrc, vUv).rgb, 1.0);
}
"""

        /** Copies the decoded OES frame, deinterlacing it on the way (see [DeinterlaceGlsl]). */
        private val COPY_OES_FRAGMENT: String = DeinterlaceGlsl.oesCopyFragment()
    }
}
