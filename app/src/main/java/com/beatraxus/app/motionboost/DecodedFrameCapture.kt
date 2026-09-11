package com.beatraxus.app.motionboost

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.common.util.Size
import androidx.media3.exoplayer.ExoPlayer

/**
 * Capture pipeline for decoded video frames using Media3's Effects API.
 * 
 * We use the Effects API (androidx.media3.effect) because it is the most robust and
 * performant way to intercept frames in Media3 1.5.0. It handles color space (HDR/SDR),
 * scaling, and synchronization within the playback pipeline, avoiding the complexities
 * and potential sync issues of manual SurfaceTexture management.
 */
@UnstableApi
class DecodedFrameCapture(
    private val onFrameAvailable: (VideoFrame) -> Unit
) {

    /**
     * Attaches the capture effect to the given ExoPlayer.
     */
    fun attachToPlayer(player: ExoPlayer) {
        player.setVideoEffects(listOf<Effect>(CaptureEffect()))
    }

    /**
     * Removes all video effects from the player.
     */
    fun detachFromPlayer(player: ExoPlayer) {
        player.setVideoEffects(emptyList())
    }

    private inner class CaptureEffect : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return CaptureShaderProgram(useHdr)
        }
    }

    private inner class CaptureShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private var inputWidth = 0
        private var inputHeight = 0
        private var glProgram: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth
            this.inputHeight = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTextureId: Int, presentationTimeUs: Long) {
            Log.v("DecodedFrameCapture", "drawFrame: tex=$inputTextureId time=$presentationTimeUs")
            // Deliver the texture information to the callback
            val frame = VideoFrame(
                textureId = inputTextureId, 
                width = inputWidth, 
                height = inputHeight, 
                presentationTimeUs = presentationTimeUs
            )
            onFrameAvailable(frame)
            
            // Robust passthrough drawing using Media3 GlProgram
            try {
                if (glProgram == null) {
                    // Standard Media3 passthrough shaders
                    val vertexShader = """
                        attribute vec4 aFramePosition;
                        attribute vec4 aTexSamplingCoords;
                        varying vec2 vTexSamplingCoords;
                        void main() {
                          gl_Position = aFramePosition;
                          vTexSamplingCoords = aTexSamplingCoords.xy;
                        }
                    """.trimIndent()
                    val fragmentShader = """
                        precision mediump float;
                        uniform sampler2D uTexSampler;
                        varying vec2 vTexSamplingCoords;
                        void main() {
                          gl_FragColor = texture2D(uTexSampler, vTexSamplingCoords);
                        }
                    """.trimIndent()
                    glProgram = GlProgram(vertexShader, fragmentShader)
                }
                
                glProgram?.let { program ->
                    program.use()
                    program.setSamplerTexIdUniform("uTexSampler", inputTextureId, 0)
                    program.setBufferAttribute(
                        "aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(),
                        4
                    )
                    program.setBufferAttribute(
                        "aTexSamplingCoords",
                        GlUtil.getTextureCoordinateBounds(),
                        4
                    )
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            } catch (e: Exception) {
                Log.e("DecodedFrameCapture", "Error drawing frame", e)
            }
        }

        override fun release() {
            super.release()
            glProgram?.delete()
        }
    }
}
