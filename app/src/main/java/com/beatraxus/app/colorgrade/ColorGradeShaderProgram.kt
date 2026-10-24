package com.beatraxus.app.colorgrade

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
    private var texelWidth = 0f
    private var texelHeight = 0f

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
            uniform float uGamma;
            uniform float uSharpness;
            uniform vec2 uTexel;
            varying vec2 vTexSamplingCoords;
            
            void main() {
                vec4 color = texture2D(uTexSampler, vTexSamplingCoords);
                vec3 rgb = color.rgb;

                // 1. Sharpness first, on the untouched picture: unsharp mask against the four neighbouring pixels.
                //    (Doing it after gamma compared a gamma-shifted centre with un-shifted neighbours, which
                //    added a mid-tone shift of its own whenever both sliders were in use.)
                if (uSharpness > 0.0) {
                    vec3 blur = (texture2D(uTexSampler, vTexSamplingCoords + vec2(uTexel.x, 0.0)).rgb
                               + texture2D(uTexSampler, vTexSamplingCoords - vec2(uTexel.x, 0.0)).rgb
                               + texture2D(uTexSampler, vTexSamplingCoords + vec2(0.0, uTexel.y)).rgb
                               + texture2D(uTexSampler, vTexSamplingCoords - vec2(0.0, uTexel.y)).rgb) * 0.25;
                    rgb += (rgb - blur) * (uSharpness * 2.0);
                }

                // 2. Brightness
                rgb += uBrightness;

                // 3. Contrast
                rgb = (rgb - 0.5) * uContrast + 0.5;

                // 4. Saturation
                float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
                rgb = mix(vec3(luma), rgb, uSaturation);

                // 5. Gamma (above 1 lifts the mid-tones, below 1 deepens them)
                rgb = pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / max(uGamma, 0.01)));

                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a);
            }
        """.trimIndent()
        glProgram = GlProgram(vertexShader, fragmentShader)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelWidth = 1f / inputWidth.coerceAtLeast(1)
        texelHeight = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTextureId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTextureId, 0)
        glProgram.setFloatUniform("uBrightness", effect.brightness)
        glProgram.setFloatUniform("uContrast", effect.contrast)
        glProgram.setFloatUniform("uSaturation", effect.saturation)
        glProgram.setFloatUniform("uGamma", effect.gamma)
        glProgram.setFloatUniform("uSharpness", effect.sharpness)
        glProgram.setFloatsUniform("uTexel", floatArrayOf(texelWidth, texelHeight))
        
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
