package com.beatraxus.app.motionboost

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Implements block-matching motion estimation using a GLES fragment shader.
 * Divides the frame into 16x16 blocks and searches a +/- 8px window.
 */
class BlockMatchingMotionEstimator : MotionEstimator {

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureAHandle: Int = 0
    private var textureBHandle: Int = 0
    private var frameSizeHandle: Int = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        .apply { position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        .apply { position(0) }

    fun initialize() {
        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        // Block matching fragment shader (simplified SAD)
        val fragmentShader = """
            precision highp float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_TextureA;
            uniform sampler2D u_TextureB;
            uniform vec2 u_FrameSize;
            
            void main() {
                vec2 blockSize = vec2(16.0) / u_FrameSize;
                vec2 searchWindow = vec2(8.0) / u_FrameSize;
                
                float minSad = 1000.0;
                vec2 bestVector = vec2(0.0);
                
                // Search +/- 8 pixels
                for (float y = -8.0; y <= 8.0; y += 2.0) {
                    for (float x = -8.0; x <= 8.0; x += 2.0) {
                        vec2 offset = vec2(x, y) / u_FrameSize;
                        
                        // Compute SAD for a few points in the block
                        float sad = 0.0;
                        for (float by = 0.0; by < 16.0; by += 4.0) {
                            for (float bx = 0.0; bx < 16.0; bx += 4.0) {
                                vec2 blockOffset = vec2(bx, by) / u_FrameSize;
                                vec4 colorA = texture2D(u_TextureA, v_TexCoord + blockOffset);
                                vec4 colorB = texture2D(u_TextureB, v_TexCoord + blockOffset + offset);
                                sad += distance(colorA, colorB);
                            }
                        }
                        
                        if (sad < minSad) {
                            minSad = sad;
                            bestVector = vec2(x, y) / 128.0; // Scale to fit in [0,1]
                        }
                    }
                }
                
                // Store vector in RG (offset by 0.5 for signed values)
                gl_FragColor = vec4(bestVector + 0.5, 0.0, 1.0);
            }
        """.trimIndent()

        program = createProgram(vertexShader, fragmentShader)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureAHandle = GLES20.glGetUniformLocation(program, "u_TextureA")
        textureBHandle = GLES20.glGetUniformLocation(program, "u_TextureB")
        frameSizeHandle = GLES20.glGetUniformLocation(program, "u_FrameSize")
    }

    override suspend fun estimateMotion(frameA: VideoFrame, frameB: VideoFrame): MotionData {
        // Output grid size (frame dimensions / 16)
        val gridWidth = frameA.width / 16
        val gridHeight = frameA.height / 16
        
        val vectorTexture = createTexture(gridWidth, gridHeight)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, vectorTexture, 0)
        
        GLES20.glViewport(0, 0, gridWidth, gridHeight)
        GLES20.glUseProgram(program)
        
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameA.textureId)
        GLES20.glUniform1i(textureAHandle, 0)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameB.textureId)
        GLES20.glUniform1i(textureBHandle, 1)
        
        GLES20.glUniform2f(frameSizeHandle, frameA.width.toFloat(), frameA.height.toFloat())
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        
        return MotionData(
            forwardMotion = MotionVectorField(vectorTexture, gridWidth, gridHeight),
            backwardMotion = null, // Simplified for Stage 2B
            confidence = 1.0f
        )
    }

    override fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun createProgram(vCode: String, fCode: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).apply {
            GLES20.glShaderSource(this, vCode)
            GLES20.glCompileShader(this)
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).apply {
            GLES20.glShaderSource(this, fCode)
            GLES20.glCompileShader(this)
        }
        return GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vs)
            GLES20.glAttachShader(this, fs)
            GLES20.glLinkProgram(this)
        }
    }

    private fun createTexture(w: Int, h: Int): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        return tex[0]
    }
}
