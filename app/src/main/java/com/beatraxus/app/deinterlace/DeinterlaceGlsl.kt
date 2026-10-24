package com.beatraxus.app.deinterlace

import com.beatraxus.app.model.DeinterlaceMath

/**
 * Deinterlacing inside the FPS Booster's own GL pipeline.
 *
 * While the FPS Booster runs, ExoPlayer is pointed at a bare Surface (the booster's SurfaceTexture), and
 * media3's video-effect pipeline cannot render into a Surface whose size it does not know: the picture stays
 * black while the sound keeps playing. So [DeinterlaceEffect] is NOT installed in the player while the booster
 * is on; the booster's "copy decoded frame" pass does the same deinterlacing instead (same maths and
 * thresholds as [DeinterlaceShaderProgram] / [DeinterlaceMath]).
 */
object DeinterlaceGlsl {

    /**
     * Fragment shader (GLSL ES 3.00) that copies the decoded OES frame and deinterlaces it on the way.
     * Uniforms: uTex, uTexMatrix, uTexelH (one source line in texture coordinates), uMode (0 off, 1 auto, 2 always).
     */
    fun oesCopyFragment(): String = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform float uTexelH;
uniform int uMode;
in vec2 vUv;
out vec4 o;

const float COMB_LOW = ${glFloat(DeinterlaceMath.COMB_LOW)};
const float COMB_HIGH = ${glFloat(DeinterlaceMath.COMB_HIGH)};

void main() {
  vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
  vec3 cur = texture(uTex, uv).rgb;
  if (uMode == 0) {
    o = vec4(cur, 1.0);
    return;
  }
  vec3 up = texture(uTex, uv - vec2(0.0, uTexelH)).rgb;
  vec3 dn = texture(uTex, uv + vec2(0.0, uTexelH)).rgb;
  vec3 rgb;
  if (uMode == 2) {
    rgb = 0.25 * up + 0.5 * cur + 0.25 * dn;
  } else {
    vec3 lumaW = vec3(0.299, 0.587, 0.114);
    float lu = dot(up, lumaW);
    float lc = dot(cur, lumaW);
    float ld = dot(dn, lumaW);
    float comb = max(0.0, (lu - lc) * (ld - lc));
    float w = smoothstep(COMB_LOW, COMB_HIGH, comb);
    rgb = mix(cur, 0.5 * (up + dn), w);
  }
  o = vec4(rgb, 1.0);
}
"""

    /** GLSL needs a decimal point in float literals ("0.0020", never "2e-3" on every driver). */
    private fun glFloat(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)
}
