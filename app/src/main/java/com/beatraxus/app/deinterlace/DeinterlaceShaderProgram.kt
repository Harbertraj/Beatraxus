package com.beatraxus.app.deinterlace

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleFrameGlShaderProgram
import com.beatraxus.app.model.DeinterlaceMath

/**
 * Single-frame deinterlacer (no history needed, so it works the same while playing, paused and after a seek).
 *
 *  - AUTO: [DeinterlaceMath.combMetric] finds lines that are out of step with the lines above and below; those are
 *    replaced by the average of their neighbours, weighted smoothly so there are no visible patches.
 *  - ALWAYS: a 1-2-1 vertical blend of every line.
 *
 * The thresholds come from [DeinterlaceMath], the same constants the unit tests check.
 */
@UnstableApi
class DeinterlaceShaderProgram(
    useHdr: Boolean,
    private val effect: DeinterlaceEffect
) : SingleFrameGlShaderProgram(useHdr) {

    private val glProgram: GlProgram
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
            // highp matters here: one video line is ~0.001 in uv units, finer than half-float (mediump) can hold near
            // 1.0, so with mediump the "line above / below" samples land on the wrong lines on many GPUs.
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform float uTexelHeight;
            uniform int uMode; // 0 off, 1 auto, 2 always
            varying vec2 vTexSamplingCoords;

            const float COMB_LOW = ${glFloat(DeinterlaceMath.COMB_LOW)};
            const float COMB_HIGH = ${glFloat(DeinterlaceMath.COMB_HIGH)};

            void main() {
                vec2 uv = vTexSamplingCoords;
                vec4 cur = texture2D(uTexSampler, uv);
                if (uMode == 0) {
                    gl_FragColor = cur;
                    return;
                }
                vec4 up = texture2D(uTexSampler, uv - vec2(0.0, uTexelHeight));
                vec4 dn = texture2D(uTexSampler, uv + vec2(0.0, uTexelHeight));

                vec3 rgb;
                if (uMode == 2) {
                    rgb = 0.25 * up.rgb + 0.5 * cur.rgb + 0.25 * dn.rgb;
                } else {
                    vec3 lumaW = vec3(0.299, 0.587, 0.114);
                    float lu = dot(up.rgb, lumaW);
                    float lc = dot(cur.rgb, lumaW);
                    float ld = dot(dn.rgb, lumaW);
                    float comb = max(0.0, (lu - lc) * (ld - lc));
                    float w = smoothstep(COMB_LOW, COMB_HIGH, comb);
                    rgb = mix(cur.rgb, 0.5 * (up.rgb + dn.rgb), w);
                }
                gl_FragColor = vec4(rgb, cur.a);
            }
        """.trimIndent()
        glProgram = GlProgram(vertexShader, fragmentShader)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelHeight = if (inputHeight > 0) 1f / inputHeight else 0f
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTextureId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTextureId, 0)
        glProgram.setFloatUniform("uTexelHeight", texelHeight)
        glProgram.setIntUniform("uMode", effect.mode.shaderValue)
        glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        glProgram.setBufferAttribute("aTexSamplingCoords", GlUtil.getTextureCoordinateBounds(), 4)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }

    private companion object {
        /** GLSL ES needs a decimal point in float literals ("0.002", never "2e-3" on every driver). */
        fun glFloat(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)
    }
}
