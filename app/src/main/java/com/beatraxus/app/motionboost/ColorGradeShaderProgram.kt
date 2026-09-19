package com.beatraxus.app.motionboost

import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleFrameGlShaderProgram
import androidx.media3.common.util.Size

/**
 * A [SingleFrameGlShaderProgram] that applies color grading (brightness, contrast, saturation).
 */
@UnstableApi
class ColorGradeShaderProgram(
    useHdr: Boolean,
    private val effect: ColorGradeEffect
) : SingleFrameGlShaderProgram(useHdr) {

    private val glProgram: GlProgram

    init {
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

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTextureId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTextureId, 0)
        glProgram.setFloatUniform("uBrightness", effect.brightness)
        glProgram.setFloatUniform("uContrast", effect.contrast)
        glProgram.setFloatUniform("uSaturation", effect.saturation)
        
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            4
        )
        glProgram.setBufferAttribute(
            "aTexSamplingCoords",
            GlUtil.getTextureCoordinateBounds(),
            4
        )
        glProgram.bindAttributesAndUniforms()
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }
}
