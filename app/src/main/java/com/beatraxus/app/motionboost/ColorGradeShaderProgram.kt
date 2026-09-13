package com.beatraxus.app.motionboost

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.common.util.Size

/**
 * A [BaseGlShaderProgram] that applies color grading (brightness, contrast, saturation).
 */
@UnstableApi
class ColorGradeShaderProgram(
    useHdr: Boolean,
    private val brightness: Float,
    private val contrast: Float,
    private val saturation: Float
) : BaseGlShaderProgram(useHdr, 1) {

    private var glProgram: GlProgram? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTextureId: Int, presentationTimeUs: Long) {
        try {
            if (glProgram == null) {
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
                    uniform float uBrightness;
                    uniform float uContrast;
                    uniform float uSaturation;
                    varying vec2 vTexSamplingCoords;
                    
                    void main() {
                        vec4 color = texture2D(uTexSampler, vTexSamplingCoords);
                        vec3 rgb = color.rgb;
                        
                        // 1. Brightness
                        rgb += uBrightness;
                        
                        // 2. Contrast
                        rgb = (rgb - 0.5) * uContrast + 0.5;
                        
                        // 3. Saturation
                        float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
                        rgb = mix(vec3(luma), rgb, uSaturation);
                        
                        gl_FragColor = vec4(rgb, color.a);
                    }
                """.trimIndent()
                glProgram = GlProgram(vertexShader, fragmentShader)
            }

            glProgram?.let { program ->
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTextureId, 0)
                program.setFloatUniform("uBrightness", brightness)
                program.setFloatUniform("uContrast", contrast)
                program.setFloatUniform("uSaturation", saturation)
                
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
            android.util.Log.e("ColorGradeShader", "Error drawing frame", e)
        }
    }

    override fun release() {
        super.release()
        glProgram?.delete()
    }
}
