#version 300 es
// FPS Booster: dominant ("camera") motion of a flow field. One fragment, one 1x1 output, no CPU read-back.
//
// A lattice of uSamples blocks is examined; the vector that most reliable blocks agree on wins (mode of the
// field, not the mean, so a moving object cannot drag the answer away from the background), and the result is
// the average of its supporters.
// Output (RGBA16F): rg = dominant vector in pixels of the field's level,
//                   b  = share of the examined blocks that agree with it (0..1) - how much to trust it,
//                   a  = 1.
// Used (1) on the coarsest field, as an extra candidate for every finer level of the search, and (2) on the
// final field, as a verified fill-in warp for pixels whose own vector could not be trusted (flow_interp.frag).
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uFlow;     // RGBA16F field (NEAREST): rg = vector, b = match cost
uniform ivec2 uGrid;         // field size in blocks
uniform ivec2 uSamples;      // examined lattice (at most 12 x 8)
uniform float uMaxCost;      // blocks matched worse than this are ignored
out vec4 o;

vec4 sampleAt(int i) {
  ivec2 p = ivec2(i % uSamples.x, i / uSamples.x);
  ivec2 blk = min(((p * 2 + 1) * uGrid) / (uSamples * 2), uGrid - 1);
  return texelFetch(uFlow, blk, 0);
}

float tolerance(vec2 v) { return 0.75 + 0.06 * length(v); }

void main() {
  int n = uSamples.x * uSamples.y;
  vec2 bestV = vec2(0.0);
  float bestSupport = 0.0;
  for (int i = 0; i < n; i++) {
    vec4 si = sampleAt(i);
    if (si.z > uMaxCost) continue;
    float tol = tolerance(si.xy);
    float support = 0.0;
    for (int j = 0; j < n; j++) {
      vec4 sj = sampleAt(j);
      if (sj.z <= uMaxCost && length(sj.xy - si.xy) < tol) support += 1.0;
    }
    if (support > bestSupport) { bestSupport = support; bestV = si.xy; }
  }
  vec2 sum = vec2(0.0);
  float cnt = 0.0;
  float tol = tolerance(bestV);
  for (int j = 0; j < n; j++) {
    vec4 sj = sampleAt(j);
    if (sj.z <= uMaxCost && length(sj.xy - bestV) < tol) { sum += sj.xy; cnt += 1.0; }
  }
  vec2 v = cnt > 0.0 ? sum / cnt : vec2(0.0);
  o = vec4(v, bestSupport / float(n), 1.0);
}
