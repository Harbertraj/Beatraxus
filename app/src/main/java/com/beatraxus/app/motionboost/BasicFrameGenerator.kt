package com.beatraxus.app.motionboost

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * STAGE 2A ONLY: this is a crossfade, not motion interpolation.
 * It WILL show ghosting on fast motion. This exists to validate the capture->render
 * pipeline before Stage 2B replaces the shader with real motion-vector warping.
 * Never present this to the user as 'Motion Boost enabled' in the UI — Stage 2A
 * output must not be user-visible yet.
 */
class BasicFrameGenerator : FrameInterpolator {

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureAHandle: Int = 0
    private var textureBHandle: Int = 0
    private var alphaHandle: Int = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(VERTS)
        .apply { position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(TEX_COORDS)
        .apply { position(0) }

    override suspend fun initialize(config: MotionBoostConfig) {
        val vertexShaderCode = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_TextureA;
            uniform sampler2D u_TextureB;
            uniform float u_Alpha;
            void main() {
                vec4 colorA = texture2D(u_TextureA, v_TexCoord);
                vec4 colorB = texture2D(u_TextureB, v_TexCoord);
                gl_FragColor = mix(colorA, colorB, u_Alpha);
            }
        """.trimIndent()

        program = createProgram(vertexShaderCode, fragmentShaderCode)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureAHandle = GLES20.glGetUniformLocation(program, "u_TextureA")
        textureBHandle = GLES20.glGetUniformLocation(program, "u_TextureB")
        alphaHandle = GLES20.glGetUniformLocation(program, "u_Alpha")
    }

    override suspend fun interpolate(
        frameA: VideoFrame,
        frameB: VideoFrame,
        interpolationTime: Float
    ): VideoFrame {
        // Prepare output texture
        val outputTextureId = createTexture(frameA.width, frameA.height)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, outputTextureId, 0
        )

        GLES20.glViewport(0, 0, frameA.width, frameA.height)
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

        GLES20.glUniform1f(alphaHandle, interpolationTime)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

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
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createTexture(width: Int, height: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    companion object {
        private val VERTS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
    }
}
