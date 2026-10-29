#version 300 es
// FPS Booster: regional risk map. One small texture per source pair (about one texel per 16 work px), read with a single
// bilinear fetch by flow_interp.frag.
//
// Why: per-pixel trust checks pass wherever the picture is flat or motion-blurred (any shift matches), so on fast action
// the output breaks into crisp "peeling paint" patches with hard borders next to ghosted neighbours. What marks a region
// as unreliable is that independent estimates of the in-between picture DISAGREE over a neighbourhood: the forward field
// (A and B warped to the middle by the A->B vectors) and the backward field (same with the B->A vectors) each predict a
// picture, and where the two warped frames do not agree the vectors are wrong for that area. Flat areas agree whatever the
// vectors, so they never raise the risk.
// Off on rigid pans (see main). r = mean over a ~66 px window (7x7 taps) of max(|fwdA - fwdB|, |bwdA - bwdB|) at t = 0.5, RGB length.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uA;        // RGBA8 frame A, LINEAR
uniform sampler2D uB;        // RGBA8 frame B, LINEAR
uniform sampler2D uFw;       // RGBA16F, LINEAR: final A->B field (rg = flow in work px)
uniform sampler2D uBw;       // RGBA16F, LINEAR: final B->A field
uniform sampler2D uGlobal;   // 1x1 RGBA16F, NEAREST: dominant A->B motion (work px), b = share of the picture that agrees
uniform vec2 uWork;          // work resolution in pixels
uniform vec2 uFlowUv;        // work size / (flow grid * 4): uv scale into the flow textures
in vec2 vUv;
out vec4 o;

const int HALF = 3;          // taps per side: (2*3+1)^2 = 49
const float STEP_PX = 11.0;  // tap spacing in work px: window = 66 px

void main() {
  vec2 inv = 1.0 / uWork;
  // A rigid pan (the dominant motion explains most of the picture) is what the dominant-motion candidate of
  // flow_interp.frag handles exactly: nothing to guard against. Measured: action footage <= 0.48 of the picture
  // agrees with its dominant motion, every rigid synthetic pan >= 0.79.
  vec4 g4 = texelFetch(uGlobal, ivec2(0), 0);
  float rigid = smoothstep(0.50, 0.75, g4.z);
  if (rigid >= 1.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
  float acc = 0.0;
  for (int j = -HALF; j <= HALF; j++) {
    for (int i = -HALF; i <= HALF; i++) {
      vec2 uv = clamp(vUv + vec2(float(i), float(j)) * STEP_PX * inv, vec2(0.0), vec2(1.0));
      // the same vector refinement as flow_interp.frag: take the vector where the content is at t = 0.5
      vec2 f0 = texture(uFw, uv * uFlowUv).xy;
      vec2 f = texture(uFw, (uv - 0.5 * f0 * inv) * uFlowUv).xy;
      vec2 b0 = texture(uBw, uv * uFlowUv).xy;
      vec2 b = texture(uBw, (uv - 0.5 * b0 * inv) * uFlowUv).xy;
      vec3 fa = texture(uA, uv - 0.5 * f * inv).rgb;
      vec3 fb = texture(uB, uv + 0.5 * f * inv).rgb;
      vec3 ba = texture(uA, uv + 0.5 * b * inv).rgb;
      vec3 bb = texture(uB, uv - 0.5 * b * inv).rgb;
      acc += max(length(fa - fb), length(ba - bb));
    }
  }
  float risk = acc / float((2 * HALF + 1) * (2 * HALF + 1));
  o = vec4(risk * (1.0 - rigid), 0.0, 0.0, 1.0);
}
