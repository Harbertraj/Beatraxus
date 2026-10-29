package com.beatraxus.app.fpsbooster

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one-time CPU + GPU test behind the FPS Booster's recommended settings.
 *
 * The GPU part is not a generic graphics benchmark: it runs the booster's real `flow_search.frag` (the motion
 * search) over the same image pyramid the renderer builds for each quality preset, forward and backward, so the
 * millisecond figures are what the Optical flow engine will really cost on this phone. A second pass measures
 * the per-output-frame warp at 1080p. The CPU part is a short multi-threaded floating-point loop.
 *
 * Runs on the caller's thread (call it off the main thread), owns a private pbuffer EGL context and never
 * touches the video pipeline. Total time is about 3 seconds on a normal phone; a device that is too slow for
 * a preset skips the larger ones instead of grinding through them.
 */
object BoosterBenchmarkRunner {
    private const val TAG = "BoosterBenchmark"
    private const val PITCH = 4
    private const val LAMBDA = 0.003f
    private const val TIMED_RUNS = 7
    /** A single frame-pair that takes longer than this is hopeless; larger presets are not even tried. */
    private const val GIVE_UP_PAIR_MS = 120f
    private const val CPU_SECONDS = 0.45
    private const val CPU_POINTS_DIVISOR = 4f

    @Volatile private var cpuSink = 0f

    private const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Stand-in for flow_interp.frag: the same kind of work per displayed pixel (2 frame taps, 2 flow taps, blend). */
    private const val WARP_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uFw;
uniform sampler2D uBw;
uniform float uT;
in vec2 vUv;
out vec4 o;
void main() {
  vec2 fw = texture(uFw, vUv).xy * 0.002;
  vec2 bw = texture(uBw, vUv).xy * 0.002;
  vec3 a = texture(uA, vUv - fw * uT).rgb;
  vec3 b = texture(uB, vUv - bw * (1.0 - uT)).rgb;
  vec3 c = mix(a, b, uT);
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  o = vec4(mix(vec3(l), c, 1.1), 1.0);
}
"""

    /** @param onProgress progress 0..1 with a short label. @param isCancelled polled between steps. */
    fun run(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (Float, String) -> Unit
    ): BenchmarkResult {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        onProgress(0.02f, "Testing CPU")
        val cpuScore = cpuScore(cores)
        if (isCancelled()) throw InterruptedException("cancelled")

        onProgress(0.2f, "Testing GPU")
        val gpu = runGpu(context, isCancelled) { f, label -> onProgress(0.2f + 0.78f * f, label) }
        onProgress(1f, "Done")

        // Score: motion-search throughput. 1000 points = 10 ms per megapixel of analysed image (pair, 16:9).
        val gpuScore = gpu.scoreBasis?.let { (q, ms) ->
            val megapixels = workPixels(q) / 1_000_000f
            (10_000f * megapixels / max(ms, 0.01f)).roundToInt()
        } ?: 0

        return BenchmarkResult(
            cpuScore = cpuScore, gpuScore = gpuScore, cores = cores,
            pairMs = gpu.pairMs, warpMs = gpu.warpMs, floatRtOk = gpu.floatRtOk, fuseMs = gpu.fuseMs,
            measuredAt = System.currentTimeMillis()
        )
    }

    // ---------------------------------------------------------------- CPU

    private fun cpuScore(cores: Int): Int {
        val threads = min(cores, 8)
        val ops = LongArray(threads)
        val start = System.nanoTime()
        val deadline = start + (CPU_SECONDS * 1e9).toLong()
        val workers = List(threads) { i ->
            Thread({ ops[i] = cpuWork(deadline) }, "booster-bench-$i").also { it.start() }
        }
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - start) / 1e9
        val totalMops = ops.sum() / seconds / 1e6
        return (totalMops / CPU_POINTS_DIVISOR).roundToInt()
    }

    /** Bounded (converging) float recurrence: 12 flops per iteration, no overflow / denormals. Returns flops done. */
    private fun cpuWork(deadlineNs: Long): Long {
        var a = 1f; var b = 0.5f; var c = 0.25f; var d = 0.125f
        var iterations = 0L
        while (System.nanoTime() < deadlineNs) {
            for (i in 0 until 4096) {
                a = a * 0.5f + b * 0.25f + 1f
                b = b * 0.5f + c * 0.25f + 1f
                c = c * 0.5f + d * 0.25f + 1f
                d = d * 0.5f + a * 0.25f + 1f
            }
            iterations += 4096
        }
        cpuSink = a + b + c + d
        return iterations * 12
    }

    // ---------------------------------------------------------------- GPU

    private class GpuOutcome(
        val pairMs: Map<BoosterQuality, Float>,
        val warpMs: Float,
        val floatRtOk: Boolean,
        val scoreBasis: Pair<BoosterQuality, Float>?,
        val fuseMs: Float = BenchmarkResult.FUSE_NOT_MEASURED
    )

    private fun workSize(q: BoosterQuality): Pair<Int, Int> {
        val w = q.workEdge and 1.inv()
        val h = max(32, (q.workEdge * 9 / 16) and 1.inv())
        return w to h
    }

    private fun workPixels(q: BoosterQuality): Float { val (w, h) = workSize(q); return w.toFloat() * h }

    private fun runGpu(context: Context, isCancelled: () -> Boolean, onProgress: (Float, String) -> Unit): GpuOutcome {
        val none = GpuOutcome(emptyMap(), 0f, false, null)
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return none
        val v = IntArray(2)
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) return none
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var surf: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return none
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return none
            surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return none

            if (!GlCapabilityProbe.hasFloatRenderTargets(GLES30.glGetString(GLES30.GL_EXTENSIONS))) return none
            return measure(context, isCancelled, onProgress)
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "GPU benchmark failed", t)
            return none
        } finally {
            release(dpy, ctx, surf)
        }
    }

    private fun release(dpy: EGLDisplay, ctx: EGLContext, surf: EGLSurface) {
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
        EGL14.eglTerminate(dpy)
    }

    private fun measure(context: Context, isCancelled: () -> Boolean, onProgress: (Float, String) -> Unit): GpuOutcome {
        val vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }
        GLES30.glBindVertexArray(vao[0])
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        val searchSrc = context.assets.open("fpsbooster/flow_search.frag").bufferedReader().use { it.readText() }
        val search = Program(VERTEX, searchSrc)
        val globalSrc = context.assets.open("fpsbooster/global_motion.frag").bufferedReader().use { it.readText() }
        val globalProg = Program(VERTEX, globalSrc)

        val pairMs = LinkedHashMap<BoosterQuality, Float>()
        var basis: Pair<BoosterQuality, Float>? = null
        var floatOk = true
        val presets = listOf(BoosterQuality.PERFORMANCE, BoosterQuality.BALANCED, BoosterQuality.QUALITY)
        for ((idx, q) in presets.withIndex()) {
            if (isCancelled()) throw InterruptedException("cancelled")
            onProgress(idx / 4f, "Testing GPU: ${q.label}")
            val pyramid = Pyramid(q)
            try {
                if (!pyramid.framebuffersComplete(fbo)) { floatOk = false; break }
                val ms = timePairs(pyramid, search, globalProg, fbo)
                pairMs[q] = ms
                basis = q to ms
                if (ms > GIVE_UP_PAIR_MS) break   // heavier presets would only be slower
            } finally {
                pyramid.delete()
            }
        }

        var warp = 0f
        var fuse = BenchmarkResult.FUSE_NOT_MEASURED
        if (floatOk && pairMs.isNotEmpty()) {
            if (isCancelled()) throw InterruptedException("cancelled")
            onProgress(0.75f, "Testing GPU: output")
            warp = timeWarp(fbo)
            if (isCancelled()) throw InterruptedException("cancelled")
            onProgress(0.9f, "Testing GPU: AI fusion")
            fuse = timeFusion(context, fbo)
        }
        search.delete()
        globalProg.delete()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        onProgress(1f, "Done")
        return GpuOutcome(pairMs, warp, floatOk && pairMs.isNotEmpty(), basis.takeIf { floatOk }, fuse)
    }

    /** The renderer's per-pair work: fw + bw hierarchical search (with the dominant-motion pass) at the preset's settings. */
    private fun timePairs(p: Pyramid, search: Program, globalProg: Program, fbo: Int): Float {
        fun runPair() {
            for (dir in 0..1) {
                var result = 0
                val g = p.globalTex[dir]
                for (lvl in p.n - 1 downTo 0) {
                    val a = p.flow[dir][lvl][0]
                    val b = p.flow[dir][lvl][1]
                    val ref = if (dir == 0) p.lumaA[lvl] else p.lumaB[lvl]
                    val tgt = if (dir == 0) p.lumaB[lvl] else p.lumaA[lvl]
                    val gScale = if (lvl < p.n - 1) p.levels.globalScale(lvl) else 0f
                    if (lvl == p.n - 1) p.searchPass(search, fbo, ref, tgt, b, a, lvl, 2, p.q.searchRange, false, -1, g, 0f, false)
                    else p.searchPass(search, fbo, ref, tgt, result, a, lvl, 0, 0, false, lvl + 1, g, gScale, false)
                    p.searchPass(search, fbo, ref, tgt, a, b, lvl, 1, 0, lvl == 0 && p.q.subpixel, -1, g, gScale, p.levels.needsExactCost(lvl))
                    result = b
                    if (lvl == p.n - 1 && p.n > 1) p.globalPass(globalProg, fbo, b, lvl, g)
                }
            }
        }
        fun timeOne(): Float {
            val t0 = System.nanoTime()
            runPair()
            GLES30.glFinish()
            return (System.nanoTime() - t0) / 1e6f
        }
        val first = timeOne()           // includes driver warm-up / shader finalisation
        if (first > GIVE_UP_PAIR_MS * 2) return first
        timeOne(); timeOne()
        return median(List(TIMED_RUNS) { timeOne() })
    }

    private fun timeWarp(fbo: Int): Float {
        val w = 1920; val h = 1080
        val prog = Program(VERTEX, WARP_FRAGMENT)
        val a = newTexture(w, h, GLES30.GL_RGBA8, linear = true)
        val b = newTexture(w, h, GLES30.GL_RGBA8, linear = true)
        val fw = newTexture(480, 270, GLES30.GL_RGBA16F, linear = true)
        val bw = newTexture(480, 270, GLES30.GL_RGBA16F, linear = true)
        val dst = newTexture(w, h, GLES30.GL_RGBA8, linear = false)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dst, 0)
            GLES30.glViewport(0, 0, w, h)
            prog.use()
            for ((unit, pair) in listOf("uA" to a, "uB" to b, "uFw" to fw, "uBw" to bw).withIndex()) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.second)
                GLES30.glUniform1i(prog.loc(pair.first), unit)
            }
            fun one(t: Float): Float {
                val t0 = System.nanoTime()
                GLES30.glUniform1f(prog.loc("uT"), t)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                GLES30.glFinish()
                return (System.nanoTime() - t0) / 1e6f
            }
            one(0.5f); one(0.5f)
            return median(List(12) { one(0.25f + (it % 3) * 0.25f) })
        } finally {
            prog.delete()
            GLES30.glDeleteTextures(5, intArrayOf(a, b, fw, bw, dst), 0)
        }
    }


    /**
     * Cost of the AI fusion pass: the booster's real fusion program (flow_interp.frag with FUSE_PASS + the generated
     * network) writing two RGBA16F attachments at the fusion-map size of the Balanced preset. The inputs are noise with
     * a non-zero flow, so no pixel takes the static early-out: this is the worst case, which is what a gate needs.
     * Returns [BenchmarkResult.FUSE_NOT_MEASURED] if the shader does not compile or the GPU rejects two float targets,
     * and never throws (AI fusion is optional; a failure here must not fail the whole device test).
     */
    private fun timeFusion(context: Context, fbo: Int): Float {
        val ids = ArrayList<Int>()
        var prog: Program? = null
        try {
            val base = context.assets.open("fpsbooster/flow_interp.frag").bufferedReader().use { it.readText() }
            val weights = context.assets.open("fpsbooster/ai_fusion.glsl").bufferedReader().use { it.readText() }
            val maxDraw = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, maxDraw, 0)
            if (maxDraw[0] < 2) return BenchmarkResult.FUSE_NOT_MEASURED
            prog = Program(VERTEX, InterpShaderSource.build(base, fuse = true, weights = weights))

            val (workW, workH) = workSize(BoosterQuality.BALANCED)
            val (fw, fh) = InterpShaderSource.fuseSize(workW, workH)
            val gw = workW / PITCH; val gh = workH / PITCH
            val rnd = Random(11)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

            fun noiseRgba(w: Int, h: Int): Int {
                val t = newTexture(w, h, GLES30.GL_RGBA8, linear = true); ids.add(t)
                val bytes = ByteArray(w * h * 4); rnd.nextBytes(bytes)
                val buf = ByteBuffer.allocateDirect(bytes.size); buf.put(bytes).position(0)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                return t
            }
            fun floatRgba(w: Int, h: Int, linear: Boolean, texel: (Int) -> FloatArray): Int {
                val t = newTexture(w, h, GLES30.GL_RGBA16F, linear); ids.add(t)
                val buf = ByteBuffer.allocateDirect(w * h * 16).order(java.nio.ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                for (i in 0 until w * h) fb.put(texel(i))
                buf.position(0)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
                return t
            }
            val a = noiseRgba(1920, 1080)
            val b = noiseRgba(1920, 1080)
            // flow: x/y vary a little around a real-looking motion, a = forward/backward inconsistency, b = match cost
            val fwTex = floatRgba(gw, gh, true) { floatArrayOf(6f + rnd.nextFloat() * 2f, 3f + rnd.nextFloat(), 0.4f, 0.05f) }
            val bwTex = floatRgba(gw, gh, true) { floatArrayOf(-6f - rnd.nextFloat() * 2f, -3f - rnd.nextFloat(), 0.4f, 0.05f) }
            val cut = newTexture(1, 1, GLES30.GL_RGBA8, linear = false); ids.add(cut)
            // explicit zeros: b < 0.5 means "no scene cut" (texture storage is not guaranteed to start cleared)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(4).also { it.put(ByteArray(4)).position(0) })
            val glob = floatRgba(1, 1, false) { floatArrayOf(5f, 2f, 0.5f, 0f) }
            val dst0 = newTexture(fw, fh, GLES30.GL_RGBA16F, linear = true); ids.add(dst0)
            val dst1 = newTexture(fw, fh, GLES30.GL_RGBA16F, linear = true); ids.add(dst1)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dst0, 0)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + 1, GLES30.GL_TEXTURE_2D, dst1, 0)
            GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT0 + 1), 0)
            try {
                if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    return BenchmarkResult.FUSE_NOT_MEASURED
                }
                GLES30.glViewport(0, 0, fw, fh)
                prog.use()
                for ((unit, pair) in listOf("uA" to a, "uB" to b, "uFw" to fwTex, "uBw" to bwTex, "uCut" to cut, "uGlobal" to glob).withIndex()) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.second)
                    GLES30.glUniform1i(prog.loc(pair.first), unit)
                }
                GLES30.glUniform1i(prog.loc("uWarp"), 1)
                GLES30.glUniform2f(prog.loc("uWork"), workW.toFloat(), workH.toFloat())
                GLES30.glUniform2f(prog.loc("uFlowUv"), workW.toFloat() / (gw * PITCH), workH.toFloat() / (gh * PITCH))
                GLES30.glUniform3f(prog.loc("uGrade"), 0f, 1f, 1f)
                GLES30.glUniformMatrix3fv(prog.loc("uColorDelta"), 1, false, FloatArray(9), 0)
                GLES30.glUniform1f(prog.loc("uConsLo"), 0.5f)
                GLES30.glUniform1f(prog.loc("uConsHi"), 2.5f)
                GLES30.glUniform1i(prog.loc("uSharp"), 1)
                fun one(t: Float): Float {
                    val t0 = System.nanoTime()
                    GLES30.glUniform1f(prog.loc("uT"), t)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    GLES30.glFinish()
                    return (System.nanoTime() - t0) / 1e6f
                }
                while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drop errors left by earlier code */ }
                val first = one(0.5f)   // driver warm-up / shader finalisation
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return BenchmarkResult.FUSE_NOT_MEASURED
                if (first > GIVE_UP_PAIR_MS * 2) return first
                one(0.5f)
                return median(List(TIMED_RUNS) { one(0.25f + (it % 3) * 0.25f) })
            } finally {
                // back to the single-target state every other pass assumes
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + 1, GLES30.GL_TEXTURE_2D, 0, 0)
                GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "AI fusion benchmark unavailable", t)
            return BenchmarkResult.FUSE_NOT_MEASURED
        } finally {
            prog?.delete()
            if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        }
    }

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

    // ---------------------------------------------------------------- GL helpers

    private fun newTexture(w: Int, h: Int, format: Int, linear: Boolean): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
        val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    /** The luma pyramid + flow textures the renderer allocates for one quality preset (16:9 source). */
    private class Pyramid(val q: BoosterQuality) {
        val levelW: IntArray
        val levelH: IntArray
        val gridW: IntArray
        val gridH: IntArray
        val n: Int
        val lumaA: IntArray
        val lumaB: IntArray
        val flow: Array<Array<IntArray>>
        val globalTex: IntArray
        val levels: FlowPyramid.Levels

        init {
            val (w, h) = workSize(q)
            val lv = FlowPyramid.levels(w, h)
            levelW = lv.width; levelH = lv.height
            gridW = lv.gridW; gridH = lv.gridH
            n = lv.count
            levels = lv
            val rnd = Random(7)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            fun noisy(l: Int): Int {
                val t = newTexture(levelW[l], levelH[l], GLES30.GL_R8, linear = true)
                val buf = ByteBuffer.allocateDirect(levelW[l] * levelH[l])
                val bytes = ByteArray(levelW[l] * levelH[l]); rnd.nextBytes(bytes)
                buf.put(bytes).position(0)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, levelW[l], levelH[l], GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
                return t
            }
            lumaA = IntArray(n) { noisy(it) }
            lumaB = IntArray(n) { noisy(it) }
            flow = Array(2) { Array(n) { l -> IntArray(2) { newTexture(gridW[l], gridH[l], GLES30.GL_RGBA16F, linear = false) } } }
            globalTex = IntArray(2) { newTexture(1, 1, GLES30.GL_RGBA16F, linear = false) }
        }

        fun globalPass(p: Program, fbo: Int, flowTex: Int, lvl: Int, dst: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dst, 0)
            GLES30.glViewport(0, 0, 1, 1)
            p.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flowTex)
            GLES30.glUniform1i(p.loc("uFlow"), 0)
            val (sx, sy) = FlowPyramid.globalSamples(gridW[lvl], gridH[lvl])
            GLES30.glUniform2i(p.loc("uGrid"), gridW[lvl], gridH[lvl])
            GLES30.glUniform2i(p.loc("uSamples"), sx, sy)
            GLES30.glUniform1f(p.loc("uMaxCost"), FlowPyramid.GLOBAL_MAX_COST)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** Some GPUs advertise float render targets but reject RGBA16F attachments. */
        fun framebuffersComplete(fbo: Int): Boolean {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, flow[0][0][0], 0)
            return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        }

        fun searchPass(
            p: Program, fbo: Int, ref: Int, tgt: Int, parent: Int, dst: Int, lvl: Int,
            mode: Int, range: Int, subpel: Boolean, parentLvl: Int,
            global: Int, globalScale: Float, fullCost: Boolean
        ) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dst, 0)
            GLES30.glViewport(0, 0, gridW[lvl], gridH[lvl])
            p.use()
            for ((unit, pair) in listOf("uRef" to ref, "uTgt" to tgt, "uParent" to parent, "uGlobal" to global).withIndex()) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.second)
                GLES30.glUniform1i(p.loc(pair.first), unit)
            }
            val pw = if (parentLvl >= 0) levelW[parentLvl] else levelW[lvl]
            val ph = if (parentLvl >= 0) levelH[parentLvl] else levelH[lvl]
            val pgw = if (parentLvl >= 0) gridW[parentLvl] else gridW[lvl]
            val pgh = if (parentLvl >= 0) gridH[parentLvl] else gridH[lvl]
            GLES30.glUniform2i(p.loc("uSize"), levelW[lvl], levelH[lvl])
            GLES30.glUniform2i(p.loc("uGrid"), gridW[lvl], gridH[lvl])
            GLES30.glUniform2i(p.loc("uParentGrid"), pgw, pgh)
            GLES30.glUniform2f(p.loc("uScale"), levelW[lvl].toFloat() / pw, levelH[lvl].toFloat() / ph)
            GLES30.glUniform1f(p.loc("uGlobalScale"), globalScale)
            GLES30.glUniform1i(p.loc("uFullCost"), if (fullCost) 1 else 0)
            GLES30.glUniform1i(p.loc("uMode"), mode)
            GLES30.glUniform1i(p.loc("uRange"), range)
            GLES30.glUniform1i(p.loc("uSubpel"), if (subpel) 1 else 0)
            GLES30.glUniform1f(p.loc("uLambda"), LAMBDA)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun delete() {
            val ids = ArrayList<Int>()
            lumaA.forEach { ids.add(it) }; lumaB.forEach { ids.add(it) }
            flow.forEach { d -> d.forEach { l -> l.forEach { ids.add(it) } } }
            globalTex.forEach { ids.add(it) }
            GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        }
    }

    private class Program(vertex: String, fragment: String) {
        private val id: Int
        private val locations = HashMap<String, Int>()

        init {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, vs); GLES30.glAttachShader(id, fs)
            GLES30.glLinkProgram(id)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { "link: " + GLES30.glGetProgramInfoLog(id) }
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
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
}
