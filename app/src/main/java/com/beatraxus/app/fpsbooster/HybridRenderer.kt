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
    private val context: Context
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

    fun start() = flowRenderer.start()

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

        fun setSourceSize(w: Int, h: Int) {
            if (w <= 0 || h <= 0 || (w == srcW && h == srcH)) return
            srcW = w
            srcH = h
            val pair = RifePacking.modelSize(w, h, 640)
            modelW = pair.first
            modelH = pair.second
            generation++
            synthForTs = 0L
        }

        override fun onFrameCopied(slotFullTex: Int, tsNs: Long, frameW: Int, frameH: Int) {
            if (srcW <= 0 || srcH <= 0) setSourceSize(frameW, frameH)
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
            if (ps <= 0L || cs <= 0L || !MotionBoostTiming.isContinuous(ps, cs) || inferenceInFlight) return

            rifePairCount++
            val intervalNs = cs - ps
            val stride = HybridScheduling.computeRifeStride(avgInferenceNs, intervalNs)
            if (rifePairCount % stride != 0L) return

            val mw = modelW
            val mh = modelH
            val curB = curBytes ?: return
            val prevB = prevBytes ?: return
            val inputA = inA ?: return
            val inputB = inB ?: return
            val pIn = packedIn ?: return
            val pOut = outPlanar ?: return
            val sBytes = synthBytes ?: return

            prevB.rewind(); inputA.rewind()
            inputA.put(prevB)
            prevB.rewind(); inputA.rewind()

            curB.rewind(); inputB.rewind()
            inputB.put(curB)
            curB.rewind(); inputB.rewind()

            val gen = ++generation
            inferenceInFlight = true
            val targetTs = cs

            inferenceExecutor.submit {
                PboPacker.packAndInfer(
                    inputA, inputB, mw, mh, pIn, pOut, sBytes, rifeEngine
                )?.let { durNs ->
                    flowRenderer.postToGlThread {
                        onInferenceDone(gen, targetTs, durNs)
                    }
                } ?: run {
                    flowRenderer.postToGlThread {
                        if (gen == generation) inferenceInFlight = false
                    }
                }
            }
        }

        private fun onInferenceDone(gen: Int, targetTs: Long, durNs: Long) {
            inferenceInFlight = false
            if (gen != generation) return
            avgInferenceNs = if (avgInferenceNs == 0L) durNs else (avgInferenceNs * 7 + durNs) / 8
            synthBytes?.let { sb ->
                sb.rewind()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, synthTexture)
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, modelW, modelH,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, sb
                )
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                synthForTs = targetTs
            }
        }

        override fun getRifeTexture(aTsNs: Long, bTsNs: Long): Int {
            if (HybridScheduling.isRifeValid(synthForTs, bTsNs, generation, generation)) {
                return synthTexture
            }
            return 0
        }

        override fun onGlRelease() {
            if (readFence != 0L) { GLES30.glDeleteSync(readFence); readFence = 0L }
            val ids = mutableListOf<Int>()
            if (smallTex != 0) ids.add(smallTex)
            if (synthTexture != 0) ids.add(synthTexture)
            if (readPbo != 0) ids.add(readPbo)
            if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
            smallTex = 0; synthTexture = 0; readPbo = 0
            if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
            scaleProg?.delete(); scaleProg = null
        }

        private fun ensureGlResources() {
            if (scaleProg == null) {
                scaleProg = Prog(VERTEX, SCALE_FRAG)
            }
            if (fbo == 0) {
                val id = IntArray(1)
                GLES30.glGenFramebuffers(1, id, 0)
                fbo = id[0]
            }
            if (smallTex == 0) {
                smallTex = newTexture(modelW, modelH, GLES30.GL_RGBA8)
            }
            if (synthTexture == 0) {
                synthTexture = newTexture(modelW, modelH, GLES30.GL_RGBA8)
            }
            if (readPbo == 0) {
                val id = IntArray(1)
                GLES30.glGenBuffers(1, id, 0)
                readPbo = id[0]
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, readPbo)
                GLES30.glBufferData(
                    GLES30.GL_PIXEL_PACK_BUFFER, modelW * modelH * 4, null, GLES30.GL_DYNAMIC_READ
                )
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            }
            val bytesNeeded = modelW * modelH * 4
            if (curBytes == null || curBytes!!.capacity() < bytesNeeded) {
                curBytes = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                prevBytes = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                inA = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                inB = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                synthBytes = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder())
                packedIn = FloatBuffer.allocate(7 * modelW * modelH)
                outPlanar = FloatBuffer.allocate(3 * modelW * modelH)
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
