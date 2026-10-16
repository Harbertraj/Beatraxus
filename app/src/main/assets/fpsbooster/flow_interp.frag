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
in vec2 vUv;
out vec4 o;

vec4 fw(vec2 uv) { return texture(uFw, uv * uFlowUv); }
vec4 bw(vec2 uv) { return texture(uBw, uv * uFlowUv); }

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
    vec3 fa = texture(uA, vUv - uT * f.xy * inv).rgb;
    vec3 fb = texture(uB, vUv + (1.0 - uT) * f.xy * inv).rgb;
    // --- backward-field warp ---
    vec4 b0 = bw(vUv);
    vec4 b = bw(vUv - (1.0 - uT) * b0.xy * inv);
    vec3 ba = texture(uA, vUv + uT * b.xy * inv).rgb;
    vec3 bb = texture(uB, vUv - (1.0 - uT) * b.xy * inv).rgb;

    float wf = (1.0 - smoothstep(uConsLo, uConsHi, f.a))
             * (1.0 - smoothstep(0.06, 0.22, length(fa - fb)))
             * (1.0 - smoothstep(0.35, 0.60, f.b))
             * (1.0 - smoothstep(1.0, 4.0, length(f.xy - f0.xy)));
    float wb = (1.0 - smoothstep(uConsLo, uConsHi, b.a))
             * (1.0 - smoothstep(0.06, 0.22, length(ba - bb)))
             * (1.0 - smoothstep(0.35, 0.60, b.b))
             * (1.0 - smoothstep(1.0, 4.0, length(b.xy - b0.xy)));

    vec3 wFw = mix(fa, fb, uT);
    vec3 wBw = mix(ba, bb, uT);
    float wsum = wf + wb;
    vec3 warped = (wf * wFw + wb * wBw) / max(wsum, 1e-4);
    // both warps agreeing is stronger evidence than one
    float conf = clamp(max(wf, wb) + 0.25 * min(wf, wb), 0.0, 1.0);
    rgb = mix(nearest, warped, conf);
  }

  rgb += uGrade.x;
  rgb = (rgb - 0.5) * uGrade.y + 0.5;
  float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(l), rgb, uGrade.z);
  o = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
