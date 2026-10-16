#version 300 es
// FPS Booster: scene-cut / flash detector. One fragment averages the best-match cost of the coarsest-level
// field. Real motion still matches well (low cost); an unrelated picture does not. Written to a 1x1 texture so
// the decision never has to be read back to the CPU.
// r = mean cost, g = fraction of blocks with a poor match, b = 1 if cut.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uFlow;     // RGBA16F coarsest field (after refinement)
uniform ivec2 uGrid;
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
  o = vec4(mean, frac, (mean > uMeanThresh && frac > 0.5) ? 1.0 : 0.0, 1.0);
}
