#version 300 es
// FPS Booster: scene-cut / flash detector. One fragment averages the best-match cost of the coarsest-level
// field. Real motion still matches well (low cost); an unrelated picture does not. Written to a 1x1 texture so
// the decision never has to be read back to the CPU.
// r = mean cost, g = fraction of blocks with a poor match, b = 1 if cut,
// a = share of the picture whose motion cannot be trusted (forward/backward inconsistency of the final field, sampled
//     on a 32x18 lattice so one fragment stays cheap). The interpolation shader fades to a plain blend as this rises:
//     on very fast / motion-blurred pans the per-pixel trust decision breaks up into ragged patches, and a soft
//     blend of two already-blurred frames looks far better than patchwork.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uFlow;     // RGBA16F coarsest field (after refinement)
uniform ivec2 uGrid;
uniform sampler2D uCons;     // RGBA16F final forward field: .xy = flow (work px), .a = fw/bw inconsistency (px)
uniform ivec2 uConsGrid;
uniform float uBadPx;        // inconsistency (px, at zero speed) above which a vector counts as untrustworthy
uniform float uMeanThresh;
uniform float uPoorThresh;
out vec4 o;
void main() {
  float sum = 0.0;
  float poor = 0.0;
  for (int y = 0; y < uGrid.y; y++) {
    for (int x = 0; x < uGrid.x; x++) {
      float c = texelFetch(uFlow, ivec2(x, y), 0).z;
      sum += c;
      poor += (c > uPoorThresh) ? 1.0 : 0.0;
    }
  }
  float n = float(uGrid.x * uGrid.y);
  float mean = sum / n;
  float frac = poor / n;
  const int SX = 32;
  const int SY = 18;
  float bad = 0.0;
  for (int j = 0; j < SY; j++) {
    for (int i = 0; i < SX; i++) {
      ivec2 p = ivec2((vec2(float(i), float(j)) + 0.5) / vec2(float(SX), float(SY)) * vec2(uConsGrid));
      vec4 f = texelFetch(uCons, clamp(p, ivec2(0), uConsGrid - 1), 0);
      bad += (f.a > uBadPx * (1.0 + 0.02 * length(f.xy))) ? 1.0 : 0.0;
    }
  }
  o = vec4(mean, frac, (mean > uMeanThresh && frac > 0.5) ? 1.0 : 0.0, bad / float(SX * SY));
}
