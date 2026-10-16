#version 300 es
// FPS Booster: forward-backward consistency of a flow field (occlusion / unreliable-vector detector).
// For a block with vector v the opposite-direction field sampled where the block lands should read -v.
// a = |v + w| in pixels of this level. Large values mean the block has no counterpart (occluded) or a bad match.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uSelf;     // RGBA16F, NEAREST
uniform sampler2D uOther;    // RGBA16F, LINEAR (opposite direction)
uniform ivec2 uGrid;
out vec4 o;
const float PITCH = 4.0;
void main() {
  ivec2 blk = ivec2(gl_FragCoord.xy);
  vec4 s = texelFetch(uSelf, blk, 0);
  vec2 landing = (vec2(blk) + 0.5) + s.xy / PITCH;          // in block units
  vec2 w = texture(uOther, landing / vec2(uGrid)).xy;
  o = vec4(s.xy, s.z, length(s.xy + w));
}
