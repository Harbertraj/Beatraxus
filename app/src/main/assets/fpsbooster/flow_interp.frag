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
//
// ---- AI fusion (optional) -------------------------------------------------------------------------------------
// One source file, three programs (the loader prepends the #defines, see InterpShaderSource.kt):
//   (default)            hand-tuned trust logic above -> picture. This is the original behaviour, unchanged.
//   AI_FUSION 1          picture = per-pixel blend of up to six candidate colours, weights read from the fusion map.
//   FUSE_PASS 1          writes that fusion map: a small neural network (ai_fusion.glsl, trained offline by
//                        tools/fpsbooster_harness/ai) looks at the same evidence the hand-tuned logic uses - flow
//                        consistency, match cost, motion edges, colour agreement of the warps - plus a few more
//                        cues, and decides how much of each candidate to take. It runs at the motion-search size
//                        (not at screen size) once per generated frame, so its cost stays small on a phone GPU.
//   AI_DUMP 1 (+FUSE_PASS)  desktop harness only: writes the network inputs and candidate colours instead of weights,
//                        which is how the network is trained on exactly the numbers the shader sees.
// Candidates (K): 0 nearest real frame, 1 forward-flow warp and 2 backward-flow warp of the NEARER frame,
// 3 dominant-motion warp of the nearer frame, 4 / 5 the same two flow warps taken from the FARTHER frame (what a
// pixel uncovered by a moving object looks like: it exists only in the far frame).
// (Defaults for a strict preprocessor: an undefined name in #if is an error on some drivers.)
#ifndef AI_FUSION
#define AI_FUSION 0
#endif
#ifndef FUSE_PASS
#define FUSE_PASS 0
#endif
#ifndef AI_DUMP
#define AI_DUMP 0
#endif
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uA;        // RGBA8 frame A, LINEAR
uniform sampler2D uB;        // RGBA8 frame B, LINEAR
uniform sampler2D uFw;       // RGBA16F, LINEAR: rg = A->B flow (work px), b = match cost, a = fw/bw inconsistency (px)
uniform sampler2D uBw;       // RGBA16F, LINEAR: B->A
uniform sampler2D uCut;      // 1x1 RGBA8/16F: b = 1 on a scene cut
uniform sampler2D uGlobal;   // 1x1 RGBA16F, NEAREST: rg = dominant A->B motion (work px), b = share of the picture that agrees
uniform sampler2D uFuse0;    // AI composite only: RGBA16F fusion map, LINEAR: weights of candidates 0..3
uniform sampler2D uFuse1;    // AI composite only: RGBA16F fusion map, LINEAR: weights of candidates 4, 5
uniform float uT;
uniform int uWarp;           // 0: just show the nearest real frame (no previous frame / flow not valid)
uniform vec2 uWork;          // work resolution in pixels (flow vector unit)
uniform vec2 uFlowUv;        // work size / (flow grid * 4): uv scale into the flow textures
uniform vec3 uGrade;         // brightness, contrast, saturation (applied last, same as the legacy renderer)
uniform mat3 uColorDelta;    // hue + colour temperature matrix minus identity, column-major (zeros = off)
uniform float uConsLo;       // inconsistency (px) below which a warp is fully trusted
uniform float uConsHi;       // ... and above which it is rejected
uniform int uSharp;          // 1: keep generated frames as sharp as real ones (bicubic resample of one source frame)
#if AI_DUMP
uniform int uDumpPage;       // harness: which 4-float page of the training record to write
#endif
in vec2 vUv;
#if FUSE_PASS
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
#else
out vec4 o;
#endif

const float STATIC_DIFF = 0.008;      // max channel difference (0..1) between A and B below which a pixel is "unchanged"
const float STATIC_FLOW2 = 0.12;      // squared flow length (px^2) below which there is no motion around the pixel
const float GLOBAL_MIN_SHARE = 0.30;  // dominant motion needs this share of the picture behind it (as in flow_search.frag)
const float GLOBAL_MIN_SPEED = 1.0;   // below this (px) the nearest frame is as good as a warp
const float AI_MIN_WEIGHT = 0.02;     // a candidate with less weight than this is not fetched at all

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

// Largest difference (px) between the motion vectors of the 4 blocks around uv: large right at a motion boundary
// (object edge, occlusion), ~0 inside one moving surface. Only the AI path uses it.
float flowEdge(sampler2D s, vec2 uv) {
  vec2 sz = vec2(textureSize(s, 0));
  vec2 g = uv * uFlowUv * sz - 0.5;
  ivec2 i0 = ivec2(floor(g));
  ivec2 hi = ivec2(sz) - 1;
  vec2 a = texelFetch(s, clamp(i0, ivec2(0), hi), 0).xy;
  vec2 b = texelFetch(s, clamp(i0 + ivec2(1, 0), ivec2(0), hi), 0).xy;
  vec2 c = texelFetch(s, clamp(i0 + ivec2(0, 1), ivec2(0), hi), 0).xy;
  vec2 d = texelFetch(s, clamp(i0 + ivec2(1, 1), ivec2(0), hi), 0).xy;
  return max(max(max(length(a - b), length(a - c)), max(length(a - d), length(b - c))),
             max(length(b - d), length(c - d)));
}

// Catmull-Rom (bicubic) fetch in 5 bilinear taps. A plain bilinear fetch at a fractional (motion-compensated)
// position low-passes the picture, so every generated frame came out softer than the real ones around it.
// Catmull-Rom keeps edges and fine detail at a sub-pixel shift.
vec3 sampleSharp(sampler2D s, vec2 uv) {
  vec2 sz = vec2(textureSize(s, 0));
  vec2 pos = uv * sz;
  vec2 c = floor(pos - 0.5) + 0.5;
  vec2 f = pos - c;
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);
  vec2 w12 = w1 + w2;
  vec2 t0 = (c - 1.0) / sz;
  vec2 t3 = (c + 2.0) / sz;
  vec2 t12 = (c + w2 / w12) / sz;
  vec3 r = texture(s, vec2(t12.x, t0.y)).rgb * (w12.x * w0.y)
         + texture(s, vec2(t0.x, t12.y)).rgb * (w0.x * w12.y)
         + texture(s, vec2(t12.x, t12.y)).rgb * (w12.x * w12.y)
         + texture(s, vec2(t3.x, t12.y)).rgb * (w3.x * w12.y)
         + texture(s, vec2(t12.x, t3.y)).rgb * (w12.x * w3.y);
  float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
  return clamp(r / wsum, 0.0, 1.0);
}

vec4 fw(vec2 uv) { return flowAt(uFw, uv); }
vec4 bw(vec2 uv) { return flowAt(uBw, uv); }

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// Final colour grade shared by every path.
vec4 finish(vec3 rgb) {
  rgb += uGrade.x;
  rgb = (rgb - 0.5) * uGrade.y + 0.5;
  float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(l), rgb, uGrade.z);
  rgb += uColorDelta * rgb;
  return vec4(clamp(rgb, 0.0, 1.0), 1.0);
}

#if FUSE_PASS
// =====================================================================================================================
// Fusion map pass: network inputs -> six candidate weights.
// =====================================================================================================================
// @AI_FUSION_WEIGHTS@
// (the loader replaces the line above with ai_fusion.glsl: AI_NF inputs, AI_NK = 6 outputs and
//  `void aiNet(in float x[AI_NF], out float w[AI_NK])`)

float q4(float v) { return min(v, 4.0); }   // keeps every network input in a sane range

void main() {
  vec2 inv = 1.0 / uWork;
  vec3 pa = texture(uA, vUv).rgb;
  vec3 pb = texture(uB, vUv).rgb;
  bool nearA = uT < 0.5;

  float w[AI_NK];
  w[0] = 1.0; w[1] = 0.0; w[2] = 0.0; w[3] = 0.0; w[4] = 0.0; w[5] = 0.0;
#if AI_DUMP
  float x[AI_NF];
  for (int i = 0; i < AI_NF; i++) x[i] = 0.0;
  vec3 cand[AI_NK];
  cand[0] = nearA ? pa : pb;
  for (int i = 1; i < AI_NK; i++) cand[i] = cand[0];
  float valid = 0.0;
#endif

  if (uWarp == 1 && texture(uCut, vec2(0.5)).b < 0.5) {
    vec4 f0 = fw(vUv);
    vec3 dab = abs(pa - pb);
    bool isStatic = max(dab.r, max(dab.g, dab.b)) < STATIC_DIFF && dot(f0.xy, f0.xy) < STATIC_FLOW2;
    if (!isStatic) {
      vec4 f = fw(vUv - uT * f0.xy * inv);
      vec3 fa = texture(uA, vUv - uT * f.xy * inv).rgb;
      vec3 fb = texture(uB, vUv + (1.0 - uT) * f.xy * inv).rgb;
      vec4 b0 = bw(vUv);
      vec4 b = bw(vUv - (1.0 - uT) * b0.xy * inv);
      vec3 ba = texture(uA, vUv + uT * b.xy * inv).rgb;
      vec3 bb = texture(uB, vUv - (1.0 - uT) * b.xy * inv).rgb;
      vec4 g4 = texelFetch(uGlobal, ivec2(0), 0);
      vec3 ga = texture(uA, vUv - uT * g4.xy * inv).rgb;
      vec3 gb = texture(uB, vUv + (1.0 - uT) * g4.xy * inv).rgb;
      float edgeF = flowEdge(uFw, vUv - uT * f0.xy * inv);
      float edgeB = flowEdge(uBw, vUv - (1.0 - uT) * b0.xy * inv);
      float sf = 1.0 + 0.02 * length(f.xy);
      float sb = 1.0 + 0.02 * length(b.xy);

      float x2[AI_NF];
      x2[0] = uT;
      x2[1] = q4(0.25 * f.a / sf);                   // forward/backward inconsistency of the forward field
      x2[2] = q4(f.b);                               // match cost of the forward field
      x2[3] = q4(0.25 * length(f.xy - f0.xy) / sf);  // refined vector vs first vector
      x2[4] = 0.2 * log2(1.0 + length(f.xy));        // forward speed
      x2[5] = q4(length(fa - fb));                   // do A and B agree under the forward motion?
      x2[6] = q4(0.25 * b.a / sb);
      x2[7] = q4(b.b);
      x2[8] = q4(0.25 * length(b.xy - b0.xy) / sb);
      x2[9] = 0.2 * log2(1.0 + length(b.xy));
      x2[10] = q4(length(ba - bb));
      x2[11] = q4(length(mix(fa, fb, uT) - mix(ba, bb, uT)));   // do the two flow fields tell the same picture?
      x2[12] = q4(0.1 * length(f.xy + b.xy));        // A->B and B->A should be opposite
      x2[13] = g4.z;                                 // share of the picture behind the dominant motion
      x2[14] = 0.2 * log2(1.0 + length(g4.xy));
      x2[15] = q4(length(ga - gb));
      x2[16] = q4(length(pa - pb));                  // change without any motion compensation
      x2[17] = q4(0.1 * length(f.xy - g4.xy));       // local motion vs dominant motion
      x2[18] = q4(length(fa - ba));
      x2[19] = q4(length(fb - bb));
      x2[20] = q4(0.25 * edgeF);                     // motion boundary in the forward field
      x2[21] = q4(0.25 * edgeB);
      aiNet(x2, w);
#if AI_DUMP
      for (int i = 0; i < AI_NF; i++) x[i] = x2[i];
      cand[0] = nearA ? pa : pb;
      cand[1] = nearA ? fa : fb;
      cand[2] = nearA ? ba : bb;
      cand[3] = nearA ? ga : gb;
      cand[4] = nearA ? fb : fa;
      cand[5] = nearA ? bb : ba;
      valid = 1.0;
#endif
    }
  }

#if AI_DUMP
  // training record: AI_NF inputs, then six candidate colours (rgb each), then the valid flag; 4 floats per page
  float rec[48];
  for (int i = 0; i < 48; i++) rec[i] = 0.0;
  for (int i = 0; i < AI_NF; i++) rec[i] = x[i];
  for (int k = 0; k < AI_NK; k++) {
    rec[AI_NF + 3 * k + 0] = cand[k].r;
    rec[AI_NF + 3 * k + 1] = cand[k].g;
    rec[AI_NF + 3 * k + 2] = cand[k].b;
  }
  rec[AI_NF + 3 * AI_NK] = valid;
  int p = uDumpPage * 4;
  o0 = vec4(rec[p], rec[p + 1], rec[p + 2], rec[p + 3]);
  o1 = vec4(w[0], w[1], w[2], w[3]);
#else
  o0 = vec4(w[0], w[1], w[2], w[3]);
  o1 = vec4(w[4], w[5], 0.0, 1.0);
#endif
}

#else
// =====================================================================================================================
// Picture pass
// =====================================================================================================================
void main() {
  vec2 inv = 1.0 / uWork;
  vec3 pa = texture(uA, vUv).rgb;
  vec3 pb = texture(uB, vUv).rgb;
  vec3 nearest = (uT < 0.5) ? pa : pb;
  // The real frame is drawn at the (usually larger) screen size. A bilinear fetch here made every static or
  // untrusted pixel soft while only the motion-compensated pixels were bicubic, so picture detail changed from
  // pixel to pixel and was visibly softer than normal playback. Use the same bicubic resample everywhere
  // (identical to a plain copy when the frame and the screen have the same size).
  if (uSharp == 1) {
    if (uT < 0.5) nearest = sampleSharp(uA, vUv);
    else nearest = sampleSharp(uB, vUv);
  }
  vec3 rgb = nearest;

  if (uWarp == 1 && texture(uCut, vec2(0.5)).b < 0.5) {
    vec4 f0 = fw(vUv);
    vec3 dab = abs(pa - pb);
    if (max(dab.r, max(dab.g, dab.b)) < STATIC_DIFF && dot(f0.xy, f0.xy) < STATIC_FLOW2) {
      // static pixel: A == B and nothing moves around it. Either frame is the answer; skip the 6 warp fetches.
      rgb = nearest;
    } else {
#if AI_FUSION
      // --- learned fusion: blend the candidates the fusion map asks for -------------------------------------------
      vec4 f = fw(vUv - uT * f0.xy * inv);
      vec4 b0 = bw(vUv);
      vec4 b = bw(vUv - (1.0 - uT) * b0.xy * inv);
      vec4 g4 = texelFetch(uGlobal, ivec2(0), 0);
      vec4 q0 = texture(uFuse0, vUv);
      vec4 q1 = texture(uFuse1, vUv);
      // sampling positions: A is read at vUv + oA*, B at vUv + oB*
      vec2 oFa = -uT * f.xy * inv;
      vec2 oFb = (1.0 - uT) * f.xy * inv;
      vec2 oBa = uT * b.xy * inv;
      vec2 oBb = -(1.0 - uT) * b.xy * inv;
      vec2 oGa = -uT * g4.xy * inv;
      vec2 oGb = (1.0 - uT) * g4.xy * inv;
      bool nearA = uT < 0.5;
      vec3 acc = vec3(0.0);
      float wsum = 0.0;
      // 0: nearest real frame (already fetched above)
      if (q0.x > AI_MIN_WEIGHT) { acc += q0.x * nearest; wsum += q0.x; }
      // 1: forward-flow warp of the nearer frame
      if (q0.y > AI_MIN_WEIGHT) {
        vec3 c = (uSharp == 1) ? (nearA ? sampleSharp(uA, vUv + oFa) : sampleSharp(uB, vUv + oFb))
                               : (nearA ? texture(uA, vUv + oFa).rgb : texture(uB, vUv + oFb).rgb);
        acc += q0.y * c; wsum += q0.y;
      }
      // 2: backward-flow warp of the nearer frame
      if (q0.z > AI_MIN_WEIGHT) {
        vec3 c = (uSharp == 1) ? (nearA ? sampleSharp(uA, vUv + oBa) : sampleSharp(uB, vUv + oBb))
                               : (nearA ? texture(uA, vUv + oBa).rgb : texture(uB, vUv + oBb).rgb);
        acc += q0.z * c; wsum += q0.z;
      }
      // 3: dominant-motion warp of the nearer frame
      if (q0.w > AI_MIN_WEIGHT) {
        vec3 c = (uSharp == 1) ? (nearA ? sampleSharp(uA, vUv + oGa) : sampleSharp(uB, vUv + oGb))
                               : (nearA ? texture(uA, vUv + oGa).rgb : texture(uB, vUv + oGb).rgb);
        acc += q0.w * c; wsum += q0.w;
      }
      // 4: forward-flow warp of the FARTHER frame (content that only the far frame shows)
      if (q1.x > AI_MIN_WEIGHT) {
        vec3 c = (uSharp == 1) ? (nearA ? sampleSharp(uB, vUv + oFb) : sampleSharp(uA, vUv + oFa))
                               : (nearA ? texture(uB, vUv + oFb).rgb : texture(uA, vUv + oFa).rgb);
        acc += q1.x * c; wsum += q1.x;
      }
      // 5: backward-flow warp of the farther frame
      if (q1.y > AI_MIN_WEIGHT) {
        vec3 c = (uSharp == 1) ? (nearA ? sampleSharp(uB, vUv + oBb) : sampleSharp(uA, vUv + oBa))
                               : (nearA ? texture(uB, vUv + oBb).rgb : texture(uA, vUv + oBa).rgb);
        acc += q1.y * c; wsum += q1.y;
      }
      rgb = acc / max(wsum, 1e-4);
#else
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
      // off by roughly a fixed share of the speed, so the tolerances grow with it.
      // Strict color check (0.03 to 0.10): if the warped prediction differs significantly in color, it's a wrong match
      // (e.g., stretching background into foreground/body parts). Fall back to clean nearest frame instead of distorting.
      float sf = 1.0 + 0.02 * length(f.xy);
      float sb = 1.0 + 0.02 * length(b.xy);
      float wf = (1.0 - smoothstep(uConsLo * sf, uConsHi * sf, f.a))
               * (1.0 - smoothstep(0.03, 0.10, length(fa - fb)))
               * (1.0 - smoothstep(0.25, 0.50, f.b))
               * (1.0 - smoothstep(1.0 * sf, 3.5 * sf, length(f.xy - f0.xy)));
      float wb = (1.0 - smoothstep(uConsLo * sb, uConsHi * sb, b.a))
               * (1.0 - smoothstep(0.03, 0.10, length(ba - bb)))
               * (1.0 - smoothstep(0.25, 0.50, b.b))
               * (1.0 - smoothstep(1.0 * sb, 3.5 * sb, length(b.xy - b0.xy)));

      // Averaging two resampled frames (A and B) blurs anything the flow did not match to the pixel. With uSharp
      // the colour comes from ONE frame, the one nearer in time, resampled bicubically; fa/fb/ba/bb above are only
      // used for the trust checks.
      vec3 wFw = mix(fa, fb, uT);
      vec3 wBw = mix(ba, bb, uT);
      if (uSharp == 1) {
        bool nearA = uT < 0.5;
        if (wf > 0.001) {
          wFw = nearA ? sampleSharp(uA, vUv - uT * f.xy * inv)
                      : sampleSharp(uB, vUv + (1.0 - uT) * f.xy * inv);
        }
        if (wb > 0.001) {
          wBw = nearA ? sampleSharp(uA, vUv + uT * b.xy * inv)
                      : sampleSharp(uB, vUv - (1.0 - uT) * b.xy * inv);
        }
      }
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
          vec3 gSrc = mix(ga, gb, uT);
          if (uSharp == 1 && wg > 0.001) {
            gSrc = (uT < 0.5) ? sampleSharp(uA, vUv - uT * g4.xy * inv)
                              : sampleSharp(uB, vUv + (1.0 - uT) * g4.xy * inv);
          }
          rgb = mix(rgb, gSrc, wg);
        }
      }
#endif
    }
  }

  o = finish(rgb);
}
#endif
