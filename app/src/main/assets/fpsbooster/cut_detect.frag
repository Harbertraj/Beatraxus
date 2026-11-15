#version 300 es
// FPS Booster: scene-cut / flash detector. Written to a 1x1 texture so the decision never has to be read back to the CPU.
// Two independent tests, either one declares a cut (the interpolation shader then shows the nearest real frame everywhere):
//  1. coarse test: one fragment averages the best-match cost of the coarsest-level field. Real motion still matches well
//     (low cost); an unrelated picture does not. This catches cuts between very different pictures, but film cuts are
//     often between two shots of the same set and palette (same warm light, same dark walls), whose coarse images match
//     almost as well as real motion does.
//  2. fine test (on the final forward field, sampled on a 32x18 lattice so one fragment stays cheap): after a cut there is
//     no true motion, so the vectors found are arbitrary. Nearly every block then BOTH matches badly (high cost) AND
//     disagrees with the backward field (forward/backward inconsistency far beyond what the speed explains). Measured on a
//     real 24 FPS action film (14 hard cuts, 353 non-cut pairs incl. whip pans, flying objects, motion blur, grain):
//     share of such lattice points >= 0.65 at every cut, <= 0.45 everywhere else (a synthetic cut: 0.98).
// r = mean cost (coarse), g = fraction of blocks with a poor match (coarse), b = 1 on a cut, 2 on a SUSTAINED break,
// a = share of lattice points that are "unrelated" in the sense of test 2 (diagnostics / harness).
//
// Cut or whip pan? A hard cut is ONE broken pair: the pairs before and after it are ordinary. A whip pan (the camera
// swings so fast that frame and neighbour share no content) breaks many pairs in a row, and showing the nearest real
// frame for all of them freezes the picture for half a second. So when the PREVIOUS pair was flagged too (uPrev, read
// from the previous pair's own texture) the break is "sustained": b = 2, and the interpolation shader cross-fades the two
// frames, which is what a real blurred whip pan looks like. Measured on a real film (19 s clip, 3 cuts, 1 whip pan):
// the cut flag fired on all 13 pairs of the whip pan; the share test alone cannot tell them from cuts (0.56-0.85 vs 0.79-0.94).
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uFlow;     // RGBA16F coarsest field (after refinement)
uniform ivec2 uGrid;
uniform sampler2D uCons;     // RGBA16F final forward field: .xy = flow (work px), .b = match cost, .a = fw/bw inconsistency (px)
uniform ivec2 uConsGrid;
uniform float uMeanThresh;
uniform float uPoorThresh;
uniform sampler2D uPrev;     // 1x1: the previous pair's result of this pass (b >= 0.5 when that pair was flagged)
uniform int uPrevValid;      // 1 only when uPrev really is the pair directly before this one (same sequence, not skipped)
out vec4 o;

const float CUT_COST = 0.012;    // match cost above which a block matches badly (real motion: median 0.003-0.010)
const float CUT_INCONS = 2.0;    // forward/backward inconsistency (px, at zero speed) above which the vector is arbitrary
const float CUT_SHARE = 0.55;    // share of lattice points that must be both: between 0.45 (action) and 0.65 (cuts)

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
  float unrelated = 0.0;
  for (int j = 0; j < SY; j++) {
    for (int i = 0; i < SX; i++) {
      ivec2 p = ivec2((vec2(float(i), float(j)) + 0.5) / vec2(float(SX), float(SY)) * vec2(uConsGrid));
      vec4 f = texelFetch(uCons, clamp(p, ivec2(0), uConsGrid - 1), 0);
      bool arbitrary = f.a > CUT_INCONS * (1.0 + 0.02 * length(f.xy));
      unrelated += (f.b > CUT_COST && arbitrary) ? 1.0 : 0.0;
    }
  }
  float share = unrelated / float(SX * SY);
  bool cut = (mean > uMeanThresh && frac > 0.5) || share > CUT_SHARE;
  bool prevFlagged = (uPrevValid == 1) && texture(uPrev, vec2(0.5)).b >= 0.5;
  o = vec4(mean, frac, cut ? (prevFlagged ? 2.0 : 1.0) : 0.0, share);
}
