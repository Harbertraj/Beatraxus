package com.beatraxus.app.fpsbooster

import android.content.Context
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.beatraxus.app.model.VideoOrientation
import com.beatraxus.app.motionboost.MotionBoostTiming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * GPU-accelerated HYBRID FPS Booster Renderer:
 * Always presents at the full target FPS using the GPU optical-flow path, and blends RIFE AI neural synthesis
 * output when it is ready in time. Never delays or drops output frames when RIFE inference is slow.
 */
class HybridRenderer(
    outputSurface: Surface,
    private val callbacks: Callbacks,
    private val context: Context,
    private val quality: BoosterQuality = BoosterQuality.PERFORMANCE
) {

    interface Callbacks {
        fun onInputSurfaceReady(surface: Surface)
        fun onStats(stats: BoosterStats)
        fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {}
        fun onError(reason: FallbackReason, message: String)
    }

    private val rifeEngine = RifeAiEngine(context)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    private val flowCallbacks = object : OpticalFlowRenderer.Callbacks {
        override fun onInputSurfaceReady(surface: Surface) = callbacks.onInputSurfaceReady(surface)
        override fun onWindow(stats: WindowStats, sourceFps: Float, workEdge: Int, addedDelayMs: Float) {
            callbacks.onWindow(stats, sourceFps, workEdge, addedDelayMs)
        }
        override fun onError(reason: FallbackReason, message: String) = callbacks.onError(reason, message)
    }

    private val flowRenderer = OpticalFlowRenderer(outputSurface, flowCallbacks, context)
    private val rifeOverlayHook = HybridRifeHook()

    init {
        flowRenderer.rifeOverlayHook = rifeOverlayHook
    }

    fun setTargetFps(fps: Int) = flowRenderer.setTargetFps(fps)
    fun setLevel(level: QualityLevel) = flowRenderer.setLevel(level)
    fun setColorGrade(brightness: Float, contrast: Float, saturation: Float) =
        flowRenderer.setColorGrade(brightness, contrast, saturation)
    fun setColorTune(hueDegrees: Float, temperature: Float) =
        flowRenderer.setColorTune(hueDegrees, temperature)
    fun setOrientation(orientation: VideoOrientation) = flowRenderer.setOrientation(orientation)
    fun setDeinterlace(mode: Int) = flowRenderer.setDeinterlace(mode)
    fun setSourceSize(width: Int, height: Int) {
        flowRenderer.setSourceSize(width, height)
        rifeOverlayHook.setSourceSize(width, height)
    }
    fun setAiFusion(enabled: Boolean) = flowRenderer.setAiFusion(enabled)

    fun start() {
        // Load the model now so the first real pair does not pay for the session start (it would look like a slow network).
        inferenceExecutor.submit { runCatching { rifeEngine.initialize(quality) } }
        flowRenderer.start()
    }

    fun release() {
        flowRenderer.release()
        runCatching { inferenceExecutor.shutdown() }
    }

    /**
     * GL-thread hook managing async PBO readback, adaptive RIFE execution scheduling, and texture uploading.
     */
    private inner class HybridRifeHook : OpticalFlowRenderer.RifeOverlayHook {
        private var srcW = 0
        private var srcH = 0
        private var modelW = 640
        private var modelH = 360
        /** Model size the GL objects and buffers below were built for (0 = nothing allocated). */
        private var allocW = 0
        private var allocH = 0

        // GL objects
        private var smallTex = 0
        private var synthTexture = 0
        private var readPbo = 0
        private var readFence = 0L
        private var fbo = 0
        private var scaleProg: Prog? = null

        // ByteBuffers & FloatBuffers (allocated once)
        private var curBytes: ByteBuffer? = null
        private var prevBytes: ByteBuffer? = null
        private var inA: ByteBuffer? = null
        private var inB: ByteBuffer? = null
        private var packedIn: FloatBuffer? = null
        private var outPlanar: FloatBuffer? = null
        private var synthBytes: ByteBuffer? = null

        // Fencing and timestamps
        private var curTsNs = 0L
        private var prevTsNs = 0L
        private var pendingTsNs = 0L
        private var synthForTs = 0L
        private var generation = 0
        private var rifePairCount = 0L
        @Volatile private var inferenceInFlight = false
        @Volatile private var avgInferenceNs = 0L

        // RIFE-first cascade bookkeeping
        private var announcedTs = 0L
        private var lastIntervalNs = 0L
        private var samples = 0

        private fun midBudgetOk(): Boolean =
            MID_FRAME_CASCADE && HybridScheduling.canInjectMid(avgInferenceNs, lastIntervalNs)

        override fun supportsMidFrames(): Boolean = MID_FRAME_CASCADE

        override fun wantsMidFrame(): Boolean {
            val ok = midBudgetOk() && !inferenceInFlight && curTsNs > 0L && pendingTsNs > 0L
            announcedTs = if (ok) pendingTsNs else 0L
            return ok
        }

        /**
         * Any thread. The model size follows the video size and the GL objects of this hook are sized by it, so the change
         * is applied on the GL thread (the one that uses them), not on the player's thread.
         */
        fun setSourceSize(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            flowRenderer.postToGlThread { applySourceSize(w, h) }
        }

        /** GL thread only. The GL objects and buffers are rebuilt for the new model size by [ensureGlResources]. */
        private fun applySourceSize(w: Int, h: Int) {
            if (w <= 0 || h <= 0 || (w == srcW && h == srcH)) return
            srcW = w
            srcH = h
            val longEdge = when (quality) {
                BoosterQuality.PERFORMANCE -> 640
                BoosterQuality.BALANCED -> 960
                BoosterQuality.QUALITY -> 1280
            }
            val pair = RifePacking.modelSize(w, h, longEdge)
            modelW = pair.first
            modelH = pair.second
            generation++
            // Free the old-size objects now, not at the next frame: the vsync poll must not read a PBO of the old size.
            if (allocW != modelW || allocH != modelH) releaseSizedResources()
            resetHistory()
        }

        /** Frames of the previous picture size must never be paired with frames of the new one. */
        private fun resetHistory() {
            curTsNs = 0L
            prevTsNs = 0L
            pendingTsNs = 0L
            synthForTs = 0L
            announcedTs = 0L
        }

        override fun onFrameCopied(slotFullTex: Int, tsNs: Long, frameW: Int, frameH: Int) {
            if (srcW <= 0 || srcH <= 0) applySourceSize(frameW, frameH)
            ensureGlResources()

            val prog = scaleProg ?: return
            bindTarget(smallTex, modelW, modelH)
            prog.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slotFullTex)
            GLES30.glUniform1i(prog.loc("uSrc"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, readPbo)
            GLES30.glReadPixels(0, 0, modelW, modelH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

            if (readFence != 0L) GLES30.glDeleteSync(readFence)
            readFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()

            pendingTsNs = tsNs
        }

        override fun onTick() {
            // Poll PBO readback fence
            if (readFence != 0L) {
                val status = GLES30.glClientWaitSync(readFence, 0, 0L)
                if (status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED) {
                    GLES30.glDeleteSync(readFence)
                    readFence = 0L
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, readPbo)
                    val buf = GLES30.glMapBufferRange(
                        GLES30.GL_PIXEL_PACK_BUFFER, 0, modelW * modelH * 4, GLES30.GL_MAP_READ_BIT
                    ) as? ByteBuffer
                    if (buf != null) {
                        curBytes?.let { cb ->
                            prevBytes?.let { pb ->
                                cb.rewind(); pb.rewind()
                                pb.put(cb)
                                cb.rewind(); pb.rewind()
                            }
                            cb.rewind(); buf.rewind()
                            cb.put(buf)
                            cb.rewind(); buf.rewind()
                        }
                        prevTsNs = curTsNs
                        curTsNs = pendingTsNs
                        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                    }
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

                    submitInferenceIfReady()
                }
            }
        }

        private fun submitInferenceIfReady() {
            val ps = prevTsNs
            val cs = curTsNs
            val announced = cs != 0L && announcedTs == cs
            if (announced) announcedTs = 0L
            if (ps <= 0L || cs <= 0L || !MotionBoostTiming.isContinuous(ps, cs) || inferenceInFlight) {
                if (announced) flowRenderer.midUnavailable(cs)
                return
            }

            val intervalNs = cs - ps
            lastIntervalNs = intervalNs
            if (!announced) {
                // old overlay path (cascade off or too slow): RIFE on every stride-th pair, used as a guarded candidate
                rifePairCount++
                val stride = HybridScheduling.computeRifeStride(avgInferenceNs, intervalNs)
                if (rifePairCount % stride != 0L) return
            }

            val mw = modelW
            val mh = modelH
            val curB = curBytes ?: return
            val prevB = prevBytes ?: return
            val inputA = inA ?: return
            val inputB = inB ?: return
            val pIn = packedIn ?: return
            val pOut = outPlanar ?: return
            val sBytes = synthBytes ?: return

            if (announced && HybridScheduling.meanLumaDiff(prevB, curB, mw, mh) > HybridScheduling.CUT_MEAN_DIFF) {
                // scene cut: RIFE would invent a picture between two unrelated shots
                flowRenderer.midUnavailable(cs)
                return
            }

            prevB.rewind(); inputA.rewind()
            inputA.put(prevB)
            prevB.rewind(); inputA.rewind()

            curB.rewind(); inputB.rewind()
            inputB.put(curB)
            curB.rewind(); inputB.rewind()

            val gen = ++generation
            inferenceInFlight = true
            val targetTs = cs
            val aTs = ps

            inferenceExecutor.submit {
                PboPacker.packAndInfer(
                    inputA, inputB, mw, mh, pIn, pOut, sBytes, rifeEngine
                )?.let { durNs ->
                    flowRenderer.postToGlThread {
                        onInferenceDone(gen, aTs, targetTs, durNs, announced)
                    }
                } ?: run {
                    flowRenderer.postToGlThread {
                        // always: a size change bumps the generation while a run is in flight, and the flag must not stay set
                        inferenceInFlight = false
                        if (announced) flowRenderer.midUnavailable(targetTs)
                    }
                }
            }
        }

        private fun onInferenceDone(gen: Int, aTs: Long, targetTs: Long, durNs: Long, announced: Boolean) {
            inferenceInFlight = false
            if (gen != generation) return
            samples++
            // the first runs include driver / delegate warm-up: they would make the engine look too slow for ever
            if (samples > WARMUP_SAMPLES) {
                avgInferenceNs = if (avgInferenceNs == 0L) durNs else (avgInferenceNs * 7 + durNs) / 8
            }
            synthBytes?.let { sb ->
                sb.rewind()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, synthTexture)
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, modelW, modelH,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, sb
                )
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                synthForTs = targetTs
                if (announced) flowRenderer.injectMidFrame(synthTexture, aTs, targetTs)
            }
        }

        override fun getRifeTexture(aTsNs: Long, bTsNs: Long): Int {
            // with the cascade the AI picture is a frame of the queue, not an overlay candidate
            if (midBudgetOk()) return 0
            if (HybridScheduling.isRifeValid(synthForTs, bTsNs, generation, generation)) {
                return synthTexture
            }
            return 0
        }

        override fun onGlRelease() {
            releaseSizedResources()
            if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
            scaleProg?.delete(); scaleProg = null
        }

        /** GL thread only: frees everything whose size depends on the model size. */
        private fun releaseSizedResources() {
            if (allocW != 0) {
                if (readFence != 0L) { GLES30.glDeleteSync(readFence); readFence = 0L }
                val tex = listOf(smallTex, synthTexture).filter { it != 0 }
                if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex.toIntArray(), 0)
                if (readPbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(readPbo), 0)
            }
            readFence = 0L
            smallTex = 0; synthTexture = 0; readPbo = 0
            curBytes = null; prevBytes = null; inA = null; inB = null
            packedIn = null; outPlanar = null; synthBytes = null
            allocW = 0; allocH = 0
            resetHistory()
        }

        private fun ensureGlResources() {
            // The model size changed (next video, other aspect ratio): textures built for the old size would show the
            // AI pictures cropped or stretched next to the real frames, so everything sized by it is rebuilt.
            if (allocW != modelW || allocH != modelH) releaseSizedResources()
            if (scaleProg == null) {
                scaleProg = Prog(VERTEX, SCALE_FRAG)
            }
            if (fbo == 0) {
                val id = IntArray(1)
                GLES30.glGenFramebuffers(1, id, 0)
                fbo = id[0]
            }
            if (allocW == 0) {
                val mw = modelW
                val mh = modelH
                smallTex = newTexture(mw, mh, GLES30.GL_RGBA8)
                synthTexture = newTexture(mw, mh, GLES30.GL_RGBA8)
                val id = IntArray(1)
                GLES30.glGenBuffers(1, id, 0)
                readPbo = id[0]
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, readPbo)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, mw * mh * 4, null, GLES30.GL_DYNAMIC_READ)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                val bytesNeeded = mw * mh * 4
                curBytes = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                prevBytes = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                inA = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                inB = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                synthBytes = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                packedIn = FloatBuffer.allocate(7 * mw * mh)
                outPlanar = FloatBuffer.allocate(3 * mw * mh)
                allocW = mw
                allocH = mh
            }
        }

        private fun bindTarget(texture: Int, width: Int, height: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0
            )
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
    }

    private class Prog(vsSrc: String, fsSrc: String) {
        val id: Int = GLES30.glCreateProgram()
        private val locations = HashMap<String, Int>()

        init {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
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

    private companion object {
        /** RIFE first (AI middle frame), optical flow second. Set to false to get the old overlay-only Hybrid back. */
        private const val MID_FRAME_CASCADE = true
        private const val WARMUP_SAMPLES = 2

        private const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val SCALE_FRAG = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
in vec2 vUv;
out vec4 o;
void main() {
  o = texture(uSrc, vUv);
}
"""
    }
}

/** Helper object for off-GL-thread NCHW packing and RIFE inference execution. */
private object PboPacker {
    fun packAndInfer(
        inA: ByteBuffer,
        inB: ByteBuffer,
        mw: Int,
        mh: Int,
        packedIn: FloatBuffer,
        outPlanar: FloatBuffer,
        synthBytes: ByteBuffer,
        engine: RifeAiEngine
    ): Long? {
        return runCatching {
            packedIn.rewind()
            outPlanar.rewind()
            synthBytes.rewind()
            RifePacking.rgbaToPlanar(inA, mw, mh, packedIn, 0)
            RifePacking.rgbaToPlanar(inB, mw, mh, packedIn, 3 * mw * mh)
            RifePacking.fillPlane(packedIn, 6 * mw * mh, mw, mh, 0.5f)

            val startNs = System.nanoTime()
            val ok = engine.infer(packedIn, mw, mh, outPlanar)
            val durNs = System.nanoTime() - startNs
            if (ok) {
                outPlanar.rewind()
                synthBytes.rewind()
                RifePacking.planarToRgba(outPlanar, mw, mh, synthBytes)
                durNs
            } else null
        }.getOrNull()
    }
}
