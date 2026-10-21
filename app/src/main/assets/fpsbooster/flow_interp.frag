#version 300 es
// FPS Booster: bidirectional, occlusion-aware motion-compensated interpolation of the frame at time uT
// (0 = frame A, 1 = frame B).
//
// Two dense fields are used: uFw (A -> B) and uBw (B -> A), each carrying a forward-backward consistency
// error in .a. Every output pixel is built from up to two independent warps (one from each field); a warp
// is trusted only where its field is self-consistent AND the two warped samples show the same picture.
// Occluded / disoccluded areas fail one field's consistency test and are taken from the other warp.
// Where neither warp is trusted the nearest real frame is shown (judder instead of ghosting).
// A scene cut (uCut.b) shows the nearest real frame everywhere.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uA;        // RGBA8 frame A, LINEAR
uniform sampler2D uB;        // RGBA8 frame B, LINEAR
uniform sampler2D uFw;       // RGBA16F, LINEAR: rg = A->B flow (work px), b = match cost, a = fw/bw inconsistency (px)
uniform sampler2D uBw;       // RGBA16F, LINEAR: B->A
uniform sampler2D uCut;      // 1x1 RGBA8/16F: b = 1 on a scene cut
uniform float uT;
uniform int uWarp;           // 0: just show the nearest real frame (no previous frame / flow not valid)
uniform vec2 uWork;          // work resolution in pixels (flow vector unit)
uniform vec2 uFlowUv;        // work size / (flow grid * 4): uv scale into the flow textures
uniform vec3 uGrade;         // brightness, contrast, saturation (applied last, same as the legacy renderer)
uniform float uConsLo;       // inconsistency (px) below which a warp is fully trusted
uniform float uConsHi;       // ... and above which it is rejected
uniform int uSharp;          // 1: Catmull-Rom (bicubic) sampling of the warped colour, 0: bilinear (cheapest)
in vec2 vUv;
out vec4 o;

// Catmull-Rom bicubic sample with 5 bilinear taps (weights renormalised). Plain bilinear sampling at a
// fractional position is a [0.5 0.5] box filter at half-pixel offsets: every generated frame came out softer
// than the real frames around it and the picture "breathed" between sharp and soft. Catmull-Rom keeps most
// of the high-frequency detail at any sub-pixel position (its small overshoot is removed by the final clamp).
vec3 sampleSharp(sampler2D s, vec2 uv) {
  vec2 sz = vec2(textureSize(s, 0));
  vec2 pos = uv * sz;
  vec2 p1 = floor(pos - 0.5) + 0.5;
  vec2 f = pos - p1;
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);
  vec2 w12 = w1 + w2;
  vec2 p12 = (p1 + w2 / w12) / sz;
  vec2 p0 = (p1 - 1.0) / sz;
  vec2 p3 = (p1 + 2.0) / sz;
  float k0 = w12.x * w0.y;
  float k1 = w0.x * w12.y;
  float k2 = w12.x * w12.y;
  float k3 = w3.x * w12.y;
  float k4 = w12.x * w3.y;
  vec3 c = textureLod(s, vec2(p12.x, p0.y), 0.0).rgb * k0
         + textureLod(s, vec2(p0.x, p12.y), 0.0).rgb * k1
         + textureLod(s, vec2(p12.x, p12.y), 0.0).rgb * k2
         + textureLod(s, vec2(p3.x, p12.y), 0.0).rgb * k3
         + textureLod(s, vec2(p12.x, p3.y), 0.0).rgb * k4;
  return max(c / (k0 + k1 + k2 + k3 + k4), 0.0);
}

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
    // --- forward-field warp: refine the vector where the content was at time t ---
    vec4 f0 = fw(vUv);
    vec4 f = fw(vUv - uT * f0.xy * inv);
    vec2 fpa = vUv - uT * f.xy * inv;
    vec2 fpb = vUv + (1.0 - uT) * f.xy * inv;
    vec3 fa = texture(uA, fpa).rgb;
    vec3 fb = texture(uB, fpb).rgb;
    // --- backward-field warp ---
    vec4 b0 = bw(vUv);
    vec4 b = bw(vUv - (1.0 - uT) * b0.xy * inv);
    vec2 bpa = vUv + uT * b.xy * inv;
    vec2 bpb = vUv - (1.0 - uT) * b.xy * inv;
    vec3 ba = texture(uA, bpa).rgb;
    vec3 bb = texture(uB, bpb).rgb;

    float wf = (1.0 - smoothstep(uConsLo, uConsHi, f.a))
             * (1.0 - smoothstep(0.06, 0.22, length(fa - fb)))
             * (1.0 - smoothstep(0.35, 0.60, f.b))
             * (1.0 - smoothstep(1.0, 4.0, length(f.xy - f0.xy)));
    float wb = (1.0 - smoothstep(uConsLo, uConsHi, b.a))
             * (1.0 - smoothstep(0.06, 0.22, length(ba - bb)))
             * (1.0 - smoothstep(0.35, 0.60, b.b))
             * (1.0 - smoothstep(1.0, 4.0, length(b.xy - b0.xy)));

    // Colour of each warp. The two samples are only used for the agreement test above; the colour itself comes
    // from sharp (bicubic) samples. The temporal blend leans slightly towards the nearer frame: a frame that is
    // 20 % away is a better picture than a 80/20 mix with a frame whose vector may be a fraction of a pixel off.
    float tb = mix(uT, uT * uT * (3.0 - 2.0 * uT), 0.5);
    vec3 wFw = mix(fa, fb, tb);
    vec3 wBw = mix(ba, bb, tb);
    if (uSharp == 1) {
      wFw = mix(sampleSharp(uA, fpa), sampleSharp(uB, fpb), tb);
      wBw = mix(sampleSharp(uA, bpa), sampleSharp(uB, bpb), tb);
    }
    float wsum = wf + wb;
    vec3 warped = (wf * wFw + wb * wBw) / max(wsum, 1e-4);
    // both warps agreeing is stronger evidence than one
    float conf = clamp(max(wf, wb) + 0.25 * min(wf, wb), 0.0, 1.0);
    // A half-trusted warp blended over the nearest frame is a visible double image (ghost): make the
    // transition decisive so a pixel is either motion-compensated or a clean real frame.
    conf = smoothstep(0.25, 0.60, conf);
    rgb = mix(nearest, warped, conf);
  }

  rgb += uGrade.x;
  rgb = (rgb - 0.5) * uGrade.y + 0.5;
  float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(l), rgb, uGrade.z);
  o = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
