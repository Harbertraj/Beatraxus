#!/usr/bin/env python3
"""
Interior-only quality table for fast motion.

A pan reveals a strip of new picture at the frame edge that no interpolator can know; scoring the whole frame
mixes that unavoidable error into every number. Here only the interior is scored: a border of |v| + 16 px on
each side is excluded (|vx|+16 left/right, |vy|+16 top/bottom; the layers scene uses its background pan, zoom 0).

    python3 eval_interior.py                          # new engine, Balanced
    python3 eval_interior.py --preset quality
    python3 eval_interior.py --baseline               # the pre-fix pipeline (old shaders, 64 px pyramid, range 6)

--baseline needs the previous shaders in a directory (FPSB_ASSETS=<dir>, e.g. `git show <rev>:<file>` of the
six old .frag files); without it the flags only switch the orchestration back (stop edge / range / no dominant
motion candidate).
"""
from __future__ import annotations

import argparse
import math
import sys

import numpy as np

import metrics as M
import scenarios as S

W, H = 1280, 720
PHASES = S.PHASES


def border_of(scene_name: str, vx: float, vy: float, zoom_extra: float = 0.0) -> tuple[int, int]:
    return int(math.ceil(abs(vx) + zoom_extra)) + 16, int(math.ceil(abs(vy) + zoom_extra)) + 16


def scenes(w: int = W, h: int = H):
    """(scene, border_x, border_y)"""
    out = []
    for v in (10, 28, 56, 90, 140, 200):
        out.append((S.pan(w, h, v), *border_of("pan", v, 0)))
    out.append((S.pan(w, h, 60, 30), *border_of("diag", 60, 30)))
    # fast layers: textured disc at -80 px/frame over a background panning at +16 px/frame (border from the background pan)
    out.append((S.layers(w, h, bg_v=16.0, fg_v=-80.0), *border_of("layers", 16, 0)))
    # zoom + rotation have no global translation: just the 16 px margin
    out.append((S.zoom_rot(w, h), *border_of("zoom", 0, 0)))
    return out


def interior_psnr(out: np.ndarray, gt: np.ndarray, bx: int, by: int) -> float:
    h, w = gt.shape[:2]
    bx, by = min(bx, w // 2 - 8), min(by, h // 2 - 8)
    return M.psnr(out[by:h - by, bx:w - bx], gt[by:h - by, bx:w - bx])


def eval_scene_interior(eng, scene, bx: int, by: int) -> float:
    a, b = scene.pair()
    eng.reset()
    eng.push(a)
    eng.push(b)
    vals = [interior_psnr(eng.interpolate(t), scene.gt(t), bx, by) for t in PHASES]
    return float(np.mean(vals))


def make_engine(preset: str, baseline: bool, **over):
    import new_engine as N
    if baseline:
        over = {"stop": 64, "range": 6, "use_global": False, **over}
    return N.NewEngine(preset, **over)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preset", default="balanced", choices=["performance", "balanced", "quality"])
    ap.add_argument("--baseline", action="store_true")
    ap.add_argument("--stop", type=float, default=None, help="pyramid stop edge override (px)")
    ap.add_argument("--range", type=int, default=None, help="coarsest search range override")
    ap.add_argument("--no-global", action="store_true")
    args = ap.parse_args()
    over = {}
    if args.stop is not None:
        over["stop"] = args.stop
    if args.range is not None:
        over["range"] = args.range
    if args.no_global:
        over["use_global"] = False
    eng = make_engine(args.preset, args.baseline, **over)
    vals = []
    for sc, bx, by in scenes():
        p = eval_scene_interior(eng, sc, bx, by)
        vals.append(p)
        print(f"{sc.name:34s} border {bx:3d}x{by:3d}  interior PSNR {p:5.1f} dB", flush=True)
    print(" / ".join(f"{v:.1f}" for v in vals))
    return 0


if __name__ == "__main__":
    sys.exit(main())
