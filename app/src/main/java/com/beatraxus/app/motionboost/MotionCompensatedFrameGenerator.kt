package com.beatraxus.app.motionboost

import android.opengl.GLES20

/**
 * Implements Motion-Compensated Frame Interpolation.
 * Coordinates Motion Estimation and Warping to produce high-quality intermediate frames.
 */
class MotionCompensatedFrameGenerator : FrameInterpolator {

    private val estimator = BlockMatchingMotionEstimator()
    private val warper = WarpShaderProgram()

    override suspend fun initialize(config: MotionBoostConfig) {
        estimator.initialize()
        warper.initialize()
    }

    override suspend fun interpolate(
        frameA: VideoFrame,
        frameB: VideoFrame,
        interpolationTime: Float
    ): VideoFrame {
        // 1. Estimate Motion
        val motionData = estimator.estimateMotion(frameA, frameB)
        val vectorField = motionData.forwardMotion ?: return frameA // Fallback to Frame A if estimation fails

        // 2. Prepare Output Texture
        val outputTextureId = createTexture(frameA.width, frameA.height)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTextureId, 0)

        // 3. Perform Warp
        GLES20.glViewport(0, 0, frameA.width, frameA.height)
        warper.draw(frameA, frameB, vectorField, interpolationTime)

        // 4. Cleanup temporary vector field
        vectorField.release()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)

        return VideoFrame(
            textureId = outputTextureId,
            width = frameA.width,
            height = frameA.height,
            presentationTimeUs = frameA.presentationTimeUs + 
                ((frameB.presentationTimeUs - frameA.presentationTimeUs) * interpolationTime).toLong()
        )
    }

    override fun release() {
        estimator.release()
        warper.release()
    }

    private fun createTexture(w: Int, h: Int): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return tex[0]
    }
}
