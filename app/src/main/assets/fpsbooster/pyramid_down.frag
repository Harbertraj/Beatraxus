#version 300 es
// FPS Booster: next (half-size) level of the luma pyramid, exact 2x2 box average.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uSrc;      // R8, NEAREST
uniform ivec2 uSrcSize;
out vec4 o;
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy) * 2;
  ivec2 hi = uSrcSize - 1;
  float s = texelFetch(uSrc, clamp(p, ivec2(0), hi), 0).r
          + texelFetch(uSrc, clamp(p + ivec2(1, 0), ivec2(0), hi), 0).r
          + texelFetch(uSrc, clamp(p + ivec2(0, 1), ivec2(0), hi), 0).r
          + texelFetch(uSrc, clamp(p + ivec2(1, 1), ivec2(0), hi), 0).r;
  o = vec4(s * 0.25, 0.0, 0.0, 1.0);
}
