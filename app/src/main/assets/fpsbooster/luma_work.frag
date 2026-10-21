#version 300 es
// FPS Booster: luma of the frame at the (smaller) motion-estimation resolution.
//
// An exact box average over the footprint of one work pixel: uTaps x uTaps bilinear taps spread evenly across
// it. uTaps = ceil(frame size / work size) (1 when the frame is not larger than the work image, then it is a
// single tap on the pixel centre). The old fixed 2x2 pattern left gaps when the frame was 3x or more larger
// than the work image (1080p -> 640, 4K -> 1280): the luma was aliased, the block search matched noise and the
// resulting vectors smeared moving detail.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uSrc;      // RGBA8 frame, LINEAR
uniform vec2 uTexel;         // 1 / work size
uniform int uTaps;           // taps per axis, 1..6
in vec2 vUv;
out vec4 o;
void main() {
  vec3 c = vec3(0.0);
  float inv = 1.0 / float(uTaps);
  for (int j = 0; j < uTaps; j++) {
    for (int i = 0; i < uTaps; i++) {
      vec2 off = (vec2(float(i), float(j)) + 0.5) * inv - 0.5;
      c += texture(uSrc, vUv + off * uTexel).rgb;
    }
  }
  c *= inv * inv;
  o = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
