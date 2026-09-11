package com.beatraxus.app.motionboost

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GLSL shader that warps Frame A forward and Frame B backward using a motion vector field.
 */
class WarpShaderProgram {

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureAHandle: Int = 0
    private var textureBHandle: Int = 0
    private var vectorHandle: Int = 0
    private var alphaHandle: Int = 0

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

        val fragmentShader = """
            precision highp float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_TextureA;
            uniform sampler2D u_TextureB;
            uniform sampler2D u_VectorField;
            uniform float u_Alpha;
            
            void main() {
                // Sample vector from the low-res vector field
                vec2 vector = texture2D(u_VectorField, v_TexCoord).rg - 0.5;
                vector *= 128.0; // Rescale from [0,1] to pixels (approx)
                
                // Normalizing vectors to UV space (this is a simplified approximation)
                vec2 uvOffset = vector / 2000.0; 
                
                // Forward warp Frame A
                vec2 uvA = v_TexCoord - (uvOffset * u_Alpha);
                vec4 colorA = texture2D(u_TextureA, uvA);
                
                // Backward warp Frame B
                vec2 uvB = v_TexCoord + (uvOffset * (1.0 - u_Alpha));
                vec4 colorB = texture2D(u_TextureB, uvB);
                
                // Blend warped frames
                gl_FragColor = mix(colorA, colorB, u_Alpha);
            }
        """.trimIndent()

        program = createProgram(vertexShader, fragmentShader)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureAHandle = GLES20.glGetUniformLocation(program, "u_TextureA")
        textureBHandle = GLES20.glGetUniformLocation(program, "u_TextureB")
        vectorHandle = GLES20.glGetUniformLocation(program, "u_VectorField")
        alphaHandle = GLES20.glGetUniformLocation(program, "u_Alpha")
    }

    fun draw(frameA: VideoFrame, frameB: VideoFrame, vectorField: MotionVectorField, alpha: Float) {
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
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorField.textureId)
        GLES20.glUniform1i(vectorHandle, 2)
        
        GLES20.glUniform1f(alphaHandle, alpha)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
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
}
