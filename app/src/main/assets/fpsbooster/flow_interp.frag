#version 300 es
// FPS Booster: bidirectional, occlusion-aware motion-compensated interpolation of the frame at time uT
// (0 = frame A, 1 = frame B).
//
// Two dense fields are used: uFw (A -> B) and uBw (B -> A), each carrying a forward-backward consistency
// error in .a. Every output pixel is built from up to two independent warps (one from each field); a warp
// is trusted only where its field is self-consistent AND the two warped samples show the same picture.
// Occluded / disoccluded areas fail one field's consistency test and are taken from the other warp.
// Where neither warp is trusted the nearest real frame is shown (judder instead of ghosting) - unless a third,
// independent candidate explains the pixel: the dominant ("camera") motion of the whole picture (uGlobal, found
// on the GPU by global_motion.frag). It is used as a fill-in only where the per-pixel flow is untrusted, and only
// where the two frames really agree under that motion (colour check), so a moving object is never dragged along
// with the background. This removes most of the patchwork of unmoved frame on fast pans.
// Pixels that are identical in both frames and have no motion around them skip all of this (static early-out).
// A scene cut (uCut.b) shows the nearest real frame everywhere.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uA;        // RGBA8 frame A, LINEAR
uniform sampler2D uB;        // RGBA8 frame B, LINEAR
uniform sampler2D uFw;       // RGBA16F, LINEAR: rg = A->B flow (work px), b = match cost, a = fw/bw inconsistency (px)
uniform sampler2D uBw;       // RGBA16F, LINEAR: B->A
uniform sampler2D uCut;      // 1x1 RGBA8/16F: b = 1 on a scene cut
uniform sampler2D uGlobal;   // 1x1 RGBA16F, NEAREST: rg = dominant A->B motion (work px), b = share of the picture that agrees
uniform float uT;
uniform int uWarp;           // 0: just show the nearest real frame (no previous frame / flow not valid)
uniform vec2 uWork;          // work resolution in pixels (flow vector unit)
uniform vec2 uFlowUv;        // work size / (flow grid * 4): uv scale into the flow textures
uniform vec3 uGrade;         // brightness, contrast, saturation (applied last, same as the legacy renderer)
uniform mat3 uColorDelta;    // hue + colour temperature matrix minus identity, column-major (zeros = off)
uniform float uConsLo;       // inconsistency (px) below which a warp is fully trusted
uniform float uConsHi;       // ... and above which it is rejected
in vec2 vUv;
out vec4 o;

const float STATIC_DIFF = 0.008;      // max channel difference (0..1) between A and B below which a pixel is "unchanged"
const float STATIC_FLOW2 = 0.12;      // squared flow length (px^2) below which there is no motion around the pixel
const float GLOBAL_MIN_SHARE = 0.30;  // dominant motion needs this share of the picture behind it (as in flow_search.frag)
const float GLOBAL_MIN_SPEED = 1.0;   // below this (px) the nearest frame is as good as a warp

// Bilinear fetch of a flow field with the VECTOR weighted by how trustworthy each of the 4 neighbouring blocks
// is. Plain bilinear blends the vectors of two different objects at a motion edge into a third vector that
// matches neither (smeared / warped edges). Here an occluded or badly matched neighbour barely contributes to
// the vector; cost (.b) and inconsistency (.a) stay plain bilinear so an untrusted neighbour still lowers the
// confidence of the result.
vec4 flowAt(sampler2D s, vec2 uv) {
  vec2 sz = vec2(textureSize(s, 0));
  vec2 g = uv * uFlowUv * sz - 0.5;
  vec2 gi = floor(g);
  vec2 f = g - gi;
  ivec2 i0 = ivec2(gi);
  ivec2 hi = ivec2(sz) - 1;
  vec4 t00 = texelFetch(s, clamp(i0, ivec2(0), hi), 0);
  vec4 t10 = texelFetch(s, clamp(i0 + ivec2(1, 0), ivec2(0), hi), 0);
  vec4 t01 = texelFetch(s, clamp(i0 + ivec2(0, 1), ivec2(0), hi), 0);
  vec4 t11 = texelFetch(s, clamp(i0 + ivec2(1, 1), ivec2(0), hi), 0);
  float w00 = (1.0 - f.x) * (1.0 - f.y);
  float w10 = f.x * (1.0 - f.y);
  float w01 = (1.0 - f.x) * f.y;
  float w11 = f.x * f.y;
  float k00 = w00 * (0.05 + 1.0 - smoothstep(uConsLo, uConsHi, t00.a));
  float k10 = w10 * (0.05 + 1.0 - smoothstep(uConsLo, uConsHi, t10.a));
  float k01 = w01 * (0.05 + 1.0 - smoothstep(uConsLo, uConsHi, t01.a));
  float k11 = w11 * (0.05 + 1.0 - smoothstep(uConsLo, uConsHi, t11.a));
  vec2 v = (t00.xy * k00 + t10.xy * k10 + t01.xy * k01 + t11.xy * k11) / (k00 + k10 + k01 + k11);
  vec4 plain = t00 * w00 + t10 * w10 + t01 * w01 + t11 * w11;
  return vec4(v, plain.z, plain.w);
}

vec4 fw(vec2 uv) { return flowAt(uFw, uv); }
vec4 bw(vec2 uv) { return flowAt(uBw, uv); }

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
  vec2 inv = 1.0 / uWork;
  vec3 pa = texture(uA, vUv).rgb;
  vec3 pb = texture(uB, vUv).rgb;
  vec3 nearest = (uT < 0.5) ? pa : pb;
  vec3 rgb = nearest;

  if (uWarp == 1 && texture(uCut, vec2(0.5)).b < 0.5) {
    vec4 f0 = fw(vUv);
    vec3 dab = abs(pa - pb);
    if (max(dab.r, max(dab.g, dab.b)) < STATIC_DIFF && dot(f0.xy, f0.xy) < STATIC_FLOW2) {
      // static pixel: A == B and nothing moves around it. Either frame is the answer; skip the 6 warp fetches.
      rgb = pa;
    } else {
      // --- forward-field warp: refine the vector where the content was at time t ---
      vec4 f = fw(vUv - uT * f0.xy * inv);
      vec3 fa = texture(uA, vUv - uT * f.xy * inv).rgb;
      vec3 fb = texture(uB, vUv + (1.0 - uT) * f.xy * inv).rgb;
      // --- backward-field warp ---
      vec4 b0 = bw(vUv);
      vec4 b = bw(vUv - (1.0 - uT) * b0.xy * inv);
      vec3 ba = texture(uA, vUv + uT * b.xy * inv).rgb;
      vec3 bb = texture(uB, vUv - (1.0 - uT) * b.xy * inv).rgb;

      // Fast motion: vector estimates, the forward/backward check and the refined-vs-first vector are all
      // off by roughly a fixed share of the speed (a 100 px/frame pan is never matched to within 1 px), so the
      // tolerances grow with it. Fixed tolerances rejected most of a fast picture and showed the unmoved frame
      // in patches. The two-frame colour check below still catches a genuinely wrong match.
      float sf = 1.0 + 0.02 * length(f.xy);
      float sb = 1.0 + 0.02 * length(b.xy);
      float wf = (1.0 - smoothstep(uConsLo * sf, uConsHi * sf, f.a))
               * (1.0 - smoothstep(0.06, 0.22, length(fa - fb)))
               * (1.0 - smoothstep(0.35, 0.60, f.b))
               * (1.0 - smoothstep(1.0 * sf, 4.0 * sf, length(f.xy - f0.xy)));
      float wb = (1.0 - smoothstep(uConsLo * sb, uConsHi * sb, b.a))
               * (1.0 - smoothstep(0.06, 0.22, length(ba - bb)))
               * (1.0 - smoothstep(0.35, 0.60, b.b))
               * (1.0 - smoothstep(1.0 * sb, 4.0 * sb, length(b.xy - b0.xy)));

      vec3 wFw = mix(fa, fb, uT);
      vec3 wBw = mix(ba, bb, uT);
      float wsum = wf + wb;
      vec3 warped = (wf * wFw + wb * wBw) / max(wsum, 1e-4);
      // both warps agreeing is stronger evidence than one
      float conf = clamp(max(wf, wb) + 0.25 * min(wf, wb), 0.0, 1.0);
      // A half-trusted warp blended over the nearest frame is a visible double image (ghost): make the
      // transition decisive so a pixel is either motion-compensated or a clean real frame.
      conf = smoothstep(0.25, 0.60, conf);
      rgb = mix(nearest, warped, conf);

      // --- fill-in with the dominant motion where the per-pixel flow is not trusted ---
      if (conf < 0.95) {
        vec4 g4 = texelFetch(uGlobal, ivec2(0), 0);
        float gTrust = smoothstep(GLOBAL_MIN_SHARE, GLOBAL_MIN_SHARE + 0.25, g4.z)
                     * smoothstep(0.5 * GLOBAL_MIN_SPEED, GLOBAL_MIN_SPEED, length(g4.xy));
        if (gTrust > 0.0) {
          vec3 ga = texture(uA, vUv - uT * g4.xy * inv).rgb;
          vec3 gb = texture(uB, vUv + (1.0 - uT) * g4.xy * inv).rgb;
          // stricter than the per-pixel check: here the colour agreement is the only evidence
          float wg = gTrust * (1.0 - smoothstep(0.04, 0.12, length(ga - gb))) * (1.0 - conf);
          rgb = mix(rgb, mix(ga, gb, uT), wg);
        }
      }
    }
  }

  rgb += uGrade.x;
  rgb = (rgb - 0.5) * uGrade.y + 0.5;
  float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(l), rgb, uGrade.z);
  rgb += uColorDelta * rgb;
  o = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
