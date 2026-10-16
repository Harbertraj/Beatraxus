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
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uRef;      // R8 luma, this level (LINEAR)
uniform sampler2D uTgt;      // R8 luma, this level (LINEAR)
uniform sampler2D uParent;   // RGBA16F flow (NEAREST): coarser level (mode 0) or this level (mode 1)
uniform ivec2 uSize;         // this level size in pixels
uniform ivec2 uGrid;         // this level block grid
uniform ivec2 uParentGrid;   // grid of uParent
uniform vec2 uScale;         // this level size / parent size (per axis); (1,1) for mode 1
uniform int uMode;
uniform int uRange;          // mode 2 only
uniform int uSubpel;         // 1: finish with half- and quarter-pixel refinement
uniform float uLambda;       // smoothness penalty per pixel of deviation from the prediction
out vec4 o;

const int PITCH = 4;
const int WIN = 8;
float a[64];
ivec2 wo;                    // window origin (this level px)
vec2 invSize;

float tgt(vec2 px) { return texture(uTgt, (px + 0.5) * invSize).r; }

// mean abs diff between the reference window and the target window displaced by v; stride 2 = 16 taps
float sad(vec2 v, int stride) {
  float s = 0.0;
  vec2 base = vec2(wo) + v;
  for (int j = 0; j < WIN; j += stride) {
    for (int i = 0; i < WIN; i += stride) {
      s += abs(a[j * WIN + i] - tgt(base + vec2(float(i), float(j))));
    }
  }
  return s * (stride == 2 ? 0.0625 : 0.015625);
}

vec2 pred;                   // smooth prediction for the regulariser
float total(vec2 v, int stride) { return sad(v, stride) + uLambda * length(v - pred); }

void main() {
  ivec2 blk = ivec2(gl_FragCoord.xy);
  invSize = 1.0 / vec2(uSize);
  wo = blk * PITCH - (WIN - PITCH) / 2;
  for (int j = 0; j < WIN; j++) {
    for (int i = 0; i < WIN; i++) {
      a[j * WIN + i] = texelFetch(uRef, clamp(wo + ivec2(i, j), ivec2(0), uSize - 1), 0).r;
    }
  }

  vec2 best = vec2(0.0);
  pred = vec2(0.0);
  float bestC;

  if (uMode == 2) {
    bestC = total(vec2(0.0), 2);
    for (int dy = -uRange; dy <= uRange; dy++) {
      for (int dx = -uRange; dx <= uRange; dx++) {
        vec2 v = vec2(float(dx), float(dy));
        float c = total(v, 2);
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
    bestC = total(best, 2);
    vec2 cand[5] = vec2[5](pred, v00, v10, v01, v11);
    for (int k = 0; k < 5; k++) {
      float c = total(cand[k], 2);
      if (c < bestC) { bestC = c; best = cand[k]; }
    }
  } else {
    vec2 own = texelFetch(uParent, blk, 0).xy;
    pred = own;
    best = own;
    bestC = total(own, 2);
    ivec2 hi = uGrid - 1;
    ivec2 nb[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    for (int k = 0; k < 4; k++) {
      vec2 v = texelFetch(uParent, clamp(blk + nb[k], ivec2(0), hi), 0).xy;
      float c = total(v, 2);
      if (c < bestC) { bestC = c; best = v; }
    }
  }

  // local refinement: two integer rings, then (optionally) half and quarter pixel
  for (int pass = 0; pass < 2; pass++) {
    vec2 c0 = best;
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dy == 0) continue;
        vec2 v = c0 + vec2(float(dx), float(dy));
        float c = total(v, 2);
        if (c < bestC) { bestC = c; best = v; }
      }
    }
  }
  // switch to the full 64-tap cost for the final decisions
  bestC = total(best, 1);
  if (uSubpel == 1) {
    for (int pass = 0; pass < 2; pass++) {
      float step = (pass == 0) ? 0.5 : 0.25;
      vec2 c0 = best;
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          if (dx == 0 && dy == 0) continue;
          vec2 v = c0 + vec2(float(dx), float(dy)) * step;
          float c = total(v, 1);
          if (c < bestC) { bestC = c; best = v; }
        }
      }
    }
  }
  o = vec4(best, sad(best, 1), 0.0);
}
