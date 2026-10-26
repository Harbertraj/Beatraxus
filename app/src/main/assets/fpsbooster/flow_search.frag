#version 300 es
// FPS Booster: one level of the hierarchical block-matching optical-flow search.
//
// One fragment = one 4x4 block (PITCH) matched with an overlapping 8x8 window (WIN) in the target luma.
// Output (RGBA16F): rg = displacement of the block from uRef to uTgt in pixels of THIS level,
//                   b  = matching cost (mean absolute luma difference of the 8x8 window, 0..1),
//                   a  = 0 (filled by flow_consistency).
//
// uMode 0: hierarchical - candidates come from the 4 nearest blocks of the coarser level (scaled up).
// uMode 1: propagation  - candidates come from the 4 neighbours of the same level (fixes outliers).
// uMode 2: coarsest     - full integer search of +-uRange pixels.
// Cost is regularised towards the coarse-level prediction so flat / noisy areas stay coherent.
//
// Extra candidate (modes 0 and 1): the dominant ("camera") motion of the whole picture, found once per pair by
// global_motion.frag on the coarsest field and passed in through uGlobal. A block whose own neighbourhood was
// matched wrongly at a coarse level (repetitive texture, motion beyond the window) gets a second chance with
// the vector most of the picture agrees on; it only wins when it really lowers the cost.
//
// Cost control (this shader is the GPU budget of the whole booster):
//  - the reference window is read once; both cost functions have literal loop bounds so the compiler can unroll
//    them and keep the window in registers instead of a dynamically indexed array;
//  - a block that already matches at zero motion (and was predicted static) returns immediately - letterbox
//    bars, static backgrounds and UI are typically most of a frame;
//  - the sub-pixel rings run on the 16-tap cost (the old one used the 64-tap cost, 4x the fetches), with one exact
//    64-tap evaluation of the final vector; the coarsest full search uses the exact cost (see totalExact).
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uRef;      // R8 luma, this level (LINEAR)
uniform sampler2D uTgt;      // R8 luma, this level (LINEAR)
uniform sampler2D uParent;   // RGBA16F flow (NEAREST): coarser level (mode 0) or this level (mode 1)
uniform sampler2D uGlobal;   // 1x1 RGBA16F: rg = dominant motion in coarsest-level px, b = share of the picture that agrees
uniform ivec2 uSize;         // this level size in pixels
uniform ivec2 uGrid;         // this level block grid
uniform ivec2 uParentGrid;   // grid of uParent
uniform vec2 uScale;         // this level size / parent size (per axis); (1,1) for mode 1
uniform float uGlobalScale;  // this level size / coarsest level size; 0 = do not use the dominant motion
uniform int uMode;
uniform int uRange;          // mode 2 only
uniform int uSubpel;         // 1: finish with a sub-pixel refinement
uniform int uFullCost;       // 1: report the exact 64-tap cost (levels whose cost is read later); 0: reuse the cheap one
uniform float uLambda;       // smoothness penalty per pixel of deviation from the prediction
out vec4 o;

const int PITCH = 4;
const int WIN = 8;
const float STATIC_COST = 0.006;     // mean luma error (0..1) below which "no motion" is already a perfect match
const float GLOBAL_MIN_SHARE = 0.30; // dominant motion is only trusted when this share of the picture agrees
float a[64];
ivec2 wo;                    // window origin (this level px)
vec2 invSize;

float tgt(vec2 px) { return texture(uTgt, (px + 0.5) * invSize).r; }

// mean abs diff between the reference window and the target window displaced by v: every 2nd texel (16 taps)
float sad16(vec2 v) {
  float s = 0.0;
  vec2 base = vec2(wo) + v;
  for (int j = 0; j < 8; j += 2) {
    for (int i = 0; i < 8; i += 2) {
      s += abs(a[j * 8 + i] - tgt(base + vec2(float(i), float(j))));
    }
  }
  return s * 0.0625;
}

// the same over all 64 texels
float sad64(vec2 v) {
  float s = 0.0;
  vec2 base = vec2(wo) + v;
  for (int j = 0; j < 8; j++) {
    for (int i = 0; i < 8; i++) {
      s += abs(a[j * 8 + i] - tgt(base + vec2(float(i), float(j))));
    }
  }
  return s * 0.015625;
}

vec2 pred;                   // smooth prediction for the regulariser
float total(vec2 v) { return sad16(v) + uLambda * length(v - pred); }
// coarsest level only: the image is tiny (a few dozen blocks), so the exact cost is affordable there and avoids
// the false matches that a 16-tap cost finds in aliased, low-detail pictures when the search range is wide
float totalExact(vec2 v) { return sad64(v) + uLambda * length(v - pred); }

void main() {
  ivec2 blk = ivec2(gl_FragCoord.xy);
  invSize = 1.0 / vec2(uSize);
  wo = blk * PITCH - (WIN - PITCH) / 2;
  for (int j = 0; j < 8; j++) {
    for (int i = 0; i < 8; i++) {
      a[j * 8 + i] = texelFetch(uRef, clamp(wo + ivec2(i, j), ivec2(0), uSize - 1), 0).r;
    }
  }

  vec2 best = vec2(0.0);
  pred = vec2(0.0);
  float bestC;

  // dominant motion of the picture, in this level's pixels (zero when unknown / not wanted)
  vec4 g4 = texelFetch(uGlobal, ivec2(0), 0);
  bool haveGlobal = uGlobalScale > 0.0 && g4.z >= GLOBAL_MIN_SHARE;
  vec2 gv = g4.xy * uGlobalScale;

  if (uMode == 2) {
    bestC = totalExact(vec2(0.0));
    if (bestC < STATIC_COST) {
      o = vec4(0.0, 0.0, bestC, 0.0);
      return;
    }
    for (int dy = -uRange; dy <= uRange; dy++) {
      for (int dx = -uRange; dx <= uRange; dx++) {
        vec2 v = vec2(float(dx), float(dy));
        float c = totalExact(v);
        if (c < bestC) { bestC = c; best = v; }
      }
    }
  } else if (uMode == 0) {
    vec2 ctr = (vec2(blk) + 0.5) * float(PITCH);
    vec2 g = ctr / uScale / float(PITCH) - 0.5;
    ivec2 i0 = ivec2(floor(g));
    vec2 f = g - vec2(i0);
    ivec2 hi = uParentGrid - 1;
    vec2 v00 = texelFetch(uParent, clamp(i0, ivec2(0), hi), 0).xy * uScale;
    vec2 v10 = texelFetch(uParent, clamp(i0 + ivec2(1, 0), ivec2(0), hi), 0).xy * uScale;
    vec2 v01 = texelFetch(uParent, clamp(i0 + ivec2(0, 1), ivec2(0), hi), 0).xy * uScale;
    vec2 v11 = texelFetch(uParent, clamp(i0 + ivec2(1, 1), ivec2(0), hi), 0).xy * uScale;
    pred = mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
    best = vec2(0.0);
    bestC = total(best);
    if (bestC < STATIC_COST && dot(pred, pred) < 0.5625) {
      o = vec4(0.0, 0.0, bestC, 0.0);
      return;
    }
    vec2 cand[5] = vec2[5](pred, v00, v10, v01, v11);
    for (int k = 0; k < 5; k++) {
      float c = total(cand[k]);
      if (c < bestC) { bestC = c; best = cand[k]; }
    }
    if (haveGlobal) {
      float c = total(gv);
      if (c < bestC) { bestC = c; best = gv; }
    }
  } else {
    vec2 own = texelFetch(uParent, blk, 0).xy;
    pred = own;
    best = own;
    bestC = total(own);
    if (bestC < STATIC_COST && dot(own, own) < 0.0001) {
      o = vec4(0.0, 0.0, bestC, 0.0);
      return;
    }
    ivec2 hi = uGrid - 1;
    ivec2 nb[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    for (int k = 0; k < 4; k++) {
      vec2 v = texelFetch(uParent, clamp(blk + nb[k], ivec2(0), hi), 0).xy;
      float c = total(v);
      if (c < bestC) { bestC = c; best = v; }
    }
    if (haveGlobal) {
      float c = total(gv);
      if (c < bestC) { bestC = c; best = gv; }
    }
  }

  // local refinement: two integer rings
  for (int pass = 0; pass < 2; pass++) {
    vec2 c0 = best;
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dy == 0) continue;
        vec2 v = c0 + vec2(float(dx), float(dy));
        float c = total(v);
        if (c < bestC) { bestC = c; best = v; }
      }
    }
  }

  float cost = bestC;
  if (uSubpel == 1) {
    // sub-pixel: a half-pixel ring, then a quarter-pixel ring, both on the cheap 16-tap cost. Fitting a parabola
    // through the integer costs was tried and rejected: SAD is V-shaped around its minimum, a parabola (and even
    // a V fit) recovers only part of the offset and cost 2-4 dB on slow pans.
    float cc = bestC;
    for (int pass = 0; pass < 2; pass++) {
      float step = (pass == 0) ? 0.5 : 0.25;
      vec2 c0 = best;
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          if (dx == 0 && dy == 0) continue;
          vec2 v = c0 + vec2(float(dx), float(dy)) * step;
          float c = total(v);
          if (c < cc) { cc = c; best = v; }
        }
      }
    }
    cost = sad64(best);                                       // exact cost of the final vector
  } else if (uFullCost == 1) {
    cost = sad64(best);
  } else {
    cost = sad16(best);
  }
  o = vec4(best, cost, 0.0);
}
