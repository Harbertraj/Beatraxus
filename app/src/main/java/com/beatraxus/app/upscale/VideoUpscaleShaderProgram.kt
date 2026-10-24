package com.beatraxus.app.upscale

import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleFrameGlShaderProgram
import com.beatraxus.app.model.UpscaleSizing

/** Single-pass upscaler: bicubic (Catmull-Rom, 9 taps) + optional edge-directed smoothing + limited sharpen. */
@UnstableApi
class VideoUpscaleShaderProgram(
    useHdr: Boolean,
    private val effect: VideoUpscaleEffect,
    private val displayLongSide: Int
) : SingleFrameGlShaderProgram(useHdr) {

    private val glProgram: GlProgram
    private var inW = 1f
    private var inH = 1f
    private var outW = 1f
    private var outH = 1f

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
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform vec2 uInSize;     // input size in pixels
            uniform vec2 uOutTexel;   // one OUTPUT pixel in uv units
            uniform int uMode;        // 0 off, 1 clean, 2 edge-smooth
            uniform float uStrength;  // 0..1
            varying vec2 vTexSamplingCoords;

            vec3 tex(vec2 p) { return texture2D(uTexSampler, p).rgb; }
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            // Catmull-Rom bicubic with 9 bilinear taps (needs a linearly filtered texture).
            vec3 bicubic(vec2 uv) {
                vec2 samplePos = uv * uInSize;
                vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
                vec2 f = samplePos - texPos1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 offset12 = w2 / w12;
                vec2 p0 = (texPos1 - 1.0) / uInSize;
                vec2 p3 = (texPos1 + 2.0) / uInSize;
                vec2 p12 = (texPos1 + offset12) / uInSize;
                vec3 c = vec3(0.0);
                c += tex(vec2(p0.x, p0.y)) * (w0.x * w0.y);
                c += tex(vec2(p12.x, p0.y)) * (w12.x * w0.y);
                c += tex(vec2(p3.x, p0.y)) * (w3.x * w0.y);
                c += tex(vec2(p0.x, p12.y)) * (w0.x * w12.y);
                c += tex(vec2(p12.x, p12.y)) * (w12.x * w12.y);
                c += tex(vec2(p3.x, p12.y)) * (w3.x * w12.y);
                c += tex(vec2(p0.x, p3.y)) * (w0.x * w3.y);
                c += tex(vec2(p12.x, p3.y)) * (w12.x * w3.y);
                c += tex(vec2(p3.x, p3.y)) * (w3.x * w3.y);
                return clamp(c, 0.0, 1.0);
            }

            void main() {
                vec2 uv = vTexSamplingCoords;
                if (uMode == 0) {
                    gl_FragColor = vec4(tex(uv), 1.0);
                    return;
                }
                vec3 base = bicubic(uv);
                vec3 rgb = base;

                if (uMode == 2) {
                    // Local edge direction from the luma gradient around the nearest input pixel.
                    vec2 inTexel = 1.0 / uInSize;
                    vec2 c = (floor(uv * uInSize) + 0.5) * inTexel;
                    float lL = luma(tex(c - vec2(inTexel.x, 0.0)));
                    float lR = luma(tex(c + vec2(inTexel.x, 0.0)));
                    float lU = luma(tex(c - vec2(0.0, inTexel.y)));
                    float lD = luma(tex(c + vec2(0.0, inTexel.y)));
                    vec2 g = vec2(lR - lL, lD - lU);
                    float mag = length(g);
                    float edge = smoothstep(0.03, 0.20, mag);
                    if (edge > 0.0) {
                        // Average ALONG the edge (perpendicular to the gradient): removes staircase jaggies.
                        vec2 along = vec2(-g.y, g.x) / max(mag, 0.0001) * inTexel;
                        vec3 a = tex(uv - 1.5 * along) * 0.15 + tex(uv - 0.5 * along) * 0.35
                               + tex(uv + 0.5 * along) * 0.35 + tex(uv + 1.5 * along) * 0.15;
                        rgb = mix(base, a, edge * 0.8);
                    }
                }

                // Sharpen at output resolution (unsharp mask), with the boost limited so edges get no halos.
                vec3 blur = (tex(uv + vec2(uOutTexel.x, 0.0)) + tex(uv - vec2(uOutTexel.x, 0.0))
                           + tex(uv + vec2(0.0, uOutTexel.y)) + tex(uv - vec2(0.0, uOutTexel.y))) * 0.25;
                vec3 boost = clamp((rgb - blur) * (uStrength * 1.5), -0.12, 0.12);
                gl_FragColor = vec4(clamp(rgb + boost, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
        glProgram = GlProgram(vertexShader, fragmentShader)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth.coerceAtLeast(1).toFloat()
        inH = inputHeight.coerceAtLeast(1).toFloat()
        val (w, h) = UpscaleSizing.outputSize(inputWidth, inputHeight, displayLongSide)
        outW = w.coerceAtLeast(1).toFloat()
        outH = h.coerceAtLeast(1).toFloat()
        return Size(w, h)
    }

    override fun drawFrame(inputTextureId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTextureId, 0)
        glProgram.setFloatsUniform("uInSize", floatArrayOf(inW, inH))
        glProgram.setFloatsUniform("uOutTexel", floatArrayOf(1f / outW, 1f / outH))
        glProgram.setIntUniform("uMode", effect.mode.shaderValue)
        glProgram.setFloatUniform("uStrength", effect.strength.coerceIn(0f, 1f))
        glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        glProgram.setBufferAttribute("aTexSamplingCoords", GlUtil.getTextureCoordinateBounds(), 4)
        glProgram.bindAttributesAndUniforms()
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }
}
