#!/usr/bin/env python3
"""
Exact texture-fetch count of the motion search per frame pair (both directions, whole pyramid).

flow_search.frag is instrumented on the fly: every texelFetch / the luma fetch in tgt() increments a per-fragment
counter that is written to the alpha channel of the output, and the harness sums it over the grid after every
pass. So the numbers include the early-outs (static blocks) of the scene that is run and the exact orchestration
(levels, ranges, sub-pixel, global-motion candidate); nothing is estimated from a formula (the counter is stored as half-float, so a block that makes more than 2048 fetches is rounded to a multiple of 2-16: <0.5 % on the total). The two small passes
(global_motion: ~10 k fetches per call, 3 calls per pair; flow_consistency: 2 per block) are listed separately.

    python3 count_fetches.py                       # new shaders, three presets, pan 56 px/frame
    FPSB_ASSETS=<old shader dir> python3 count_fetches.py --baseline   # pre-fix shaders + orchestration
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

import numpy as np

import glrunner as G
import new_engine as N
import scenarios as S

PRELUDE = "float cnt = 0.0;\nvec4 TF(sampler2D s, ivec2 p, int l) { cnt += 1.0; return texelFetch(s, p, l); }\n"


def instrument(src: str) -> str:
    src = src.replace("texelFetch(", "TF(")
    src = src.replace("return texture(uTgt, (px + 0.5) * invSize).r;", "cnt += 1.0; return texture(uTgt, (px + 0.5) * invSize).r;")
    src, n = re.subn(r"(o = vec4\([^;]*), 0\.0\);", r"\1, cnt);", src)
    assert n >= 1, "output line not found"
    i = src.index("precision highp sampler2D;") + len("precision highp sampler2D;")
    return src[:i] + "\n" + PRELUDE + src[i:]


class CountingEngine(N.NewEngine):
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.p_search = self.gl.program(instrument(N._src("flow_search.frag")))
        self.fetches = 0
        self.per_level: dict[int, int] = {}

    def _search(self, ref, tgt, parent, dst, lvl, mode, **kw):
        super()._search(ref, tgt, parent, dst, lvl, mode, **kw)
        gw, gh = self.grids[lvl]
        a = self.gl.read_rgba32f(dst, gw, gh)[:, :, 3]
        n = int(round(float(a.sum())))
        self.fetches += n
        self.per_level[lvl] = self.per_level.get(lvl, 0) + n


def count(preset: str, scene, baseline: bool) -> tuple[int, dict, tuple]:
    over = dict(stop=64, range=6, use_global=False) if baseline else {}
    eng = CountingEngine(preset, **over)
    a, b = scene.pair()
    eng.push(a)
    eng.push(b)
    eng.interpolate(0.5)
    return eng.fetches, eng.per_level, eng.sizes[0]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", action="store_true")
    ap.add_argument("--scene", default="pan56", choices=["pan56", "static", "pan10"])
    args = ap.parse_args()
    W, H = 1280, 720
    if args.scene == "static":
        sc = S.pan(W, H, 0.0)
    else:
        sc = S.pan(W, H, 56 if args.scene == "pan56" else 10)
    print(f"scene {args.scene}, source {W}x{H}, {'baseline' if args.baseline else 'new'} orchestration")
    for preset in ("performance", "balanced", "quality"):
        total, per, size = count(preset, sc, args.baseline)
        lv = "  ".join(f"L{l}:{per[l] / 1e6:.2f}M" for l in sorted(per))
        print(f"{preset:12s} work {size[0]}x{size[1]}  {total / 1e6:7.2f} M fetches / pair   ({lv})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
