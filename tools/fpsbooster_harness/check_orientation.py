#!/usr/bin/env python3
"""
Rotate / flip / mirror check for the present pass (hand-tuned blend AND AI fusion composite).

The app draws the final picture with PRESENT_VERTEX (OpticalFlowRenderer.kt): `vUv = uOrient * (p - 0.5) + 0.5`, so
every texture read of flow_interp.frag stays in picture space. The AI fusion map is written by the fusion pass with the
plain, UNoriented vertex shader and read back by the composite at that same picture-space vUv. That only works if the
map and the picture share one space. This script proves it on the real shaders: for all 8 orientations (4 turns x
mirror) the oriented output must be the identity output with its pixels moved by the orientation, i.e. exactly what
a screen-space rotate / flip of the finished frame would give. A fusion pass drawn in screen space (or a map sampled
with the wrong coordinates) would show up as a mismatch on rotated / flipped video.

Frames are square so every orientation maps pixel centres to pixel centres exactly.

    python3 check_orientation.py            # exit 1 on any mismatch
"""
from __future__ import annotations

import sys
from ctypes import c_float

import numpy as np

import new_engine as N
import scenarios as S

SIZE = 384
# Keep in sync with PRESENT_VERTEX in OpticalFlowRenderer.kt.
PRESENT_VERTEX = """#version 300 es
uniform mat2 uOrient;
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = uOrient * (p - 0.5) + 0.5;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""


def orientations() -> list[tuple[str, np.ndarray]]:
    """The 8 signed permutation matrices (math form [[a, b], [c, d]]) = quarter turns, with and without a mirror."""
    out = []
    for turns in range(4):
        for mirror in (False, True):
            m = np.array([[1.0, 0.0], [0.0, 1.0]])
            for _ in range(turns):
                m = np.array([[0.0, -1.0], [1.0, 0.0]]) @ m
            if mirror:
                m = m @ np.array([[-1.0, 0.0], [0.0, 1.0]])
            out.append((f"turns={turns}{' mirror' if mirror else ''}", np.round(m)))
    return out


def render(eng: N.NewEngine, prog, A, B, t: float, m: np.ndarray, ai: bool) -> np.ndarray:
    gl = eng.gl
    gl.bind_target(eng.out, eng.frameW, eng.frameH)
    p = prog.use()
    eng._bind_interp_inputs(p, A, B, t, True)
    if ai:
        p.tex(6, "uFuse0", eng.fuse[0]).tex(7, "uFuse1", eng.fuse[1])
    col_major = (c_float * 4)(m[0, 0], m[1, 0], m[0, 1], m[1, 1])   # GLSL mat2 is column-major
    gl.gl.glUniformMatrix2fv(p.loc("uOrient"), 1, 0, col_major)
    gl.draw()
    return gl.read_rgba8(eng.out, eng.frameW, eng.frameH)[:, :, :3]


def expected(identity: np.ndarray, m: np.ndarray) -> np.ndarray:
    """Pixel (x, y) of the oriented frame = identity pixel at M * (centre offset) + centre (GL row 0 = uv.y 0)."""
    n = identity.shape[0]
    ys, xs = np.mgrid[0:n, 0:n].astype(np.float64)
    cx, cy = (xs + 0.5 - n / 2), (ys + 0.5 - n / 2)
    sx = np.rint(m[0, 0] * cx + m[0, 1] * cy + n / 2 - 0.5).astype(int)
    sy = np.rint(m[1, 0] * cx + m[1, 1] * cy + n / 2 - 0.5).astype(int)
    return identity[sy, sx]


def main() -> int:
    ok = True
    scenes = [S.layers(SIZE, SIZE), S.zoom_rot(SIZE, SIZE), S.pan(SIZE, SIZE, 28)]
    for ai in (False, True):
        eng = N.NewEngine("balanced", ai=ai)
        gl = eng.gl
        prog = gl.program(N.interp_source(ai=ai), PRESENT_VERTEX)
        for sc in scenes:
            a, b = sc.pair()
            eng.reset(); eng.push(a); eng.push(b)
            A, B, warp = eng._prepare()
            for t in (0.25, 0.6):
                if ai:
                    eng.fuse_pass(A, B, t, warp)
                ident = render(eng, prog, A, B, t, np.eye(2), ai)
                for name, m in orientations():
                    got = render(eng, prog, A, B, t, m, ai)
                    diff = np.abs(got.astype(int) - expected(ident, m).astype(int))
                    worst, bad = int(diff.max()), float((diff.max(axis=2) > 1).mean())
                    good = worst <= 2 and bad < 1e-4
                    ok &= good
                    print(f"{'PASS' if good else 'FAIL'}  {'AI ' if ai else 'hand'}  {sc.name[:26]:26s} t={t:.2f}  {name:16s} "
                          f"max diff {worst} LSB, pixels off by >1: {bad * 100:.3f}%")
    print("orientation check:", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
