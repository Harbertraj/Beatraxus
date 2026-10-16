#version 300 es
// FPS Booster: luma of the frame at the (smaller) motion-estimation resolution.
// 4 taps at +-0.25 work pixel = a 2x2 box average when the frame is up to ~2x larger than the work size.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uSrc;      // RGBA8 frame, LINEAR
uniform vec2 uTexel;         // 1 / work size
in vec2 vUv;
out vec4 o;
void main() {
  vec3 c = texture(uSrc, vUv + vec2(-0.25, -0.25) * uTexel).rgb
         + texture(uSrc, vUv + vec2( 0.25, -0.25) * uTexel).rgb
         + texture(uSrc, vUv + vec2(-0.25,  0.25) * uTexel).rgb
         + texture(uSrc, vUv + vec2( 0.25,  0.25) * uTexel).rgb;
  o = vec4(dot(c * 0.25, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
