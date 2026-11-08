#!/usr/bin/env python3
"""
Object-boundary benchmark for the interpolator (halos / smeared or doubled edges of moving objects).

Whole-frame PSNR barely moves when only motion boundaries improve: they are a few percent of the picture. This builds
scenes where the ONLY motion is independent, feathered, textured subjects (1-4 of them, several speeds, some rotating)
over a static photographic background, renders the exact in-between frame from the continuous-time model
(ai/gen_data.py RandomScene / Sprite), and scores the output on:

  edge  PSNR on moving edges only (metrics.edge_psnr: within 5 px of a strong edge, where the two source frames differ)
  all   whole-frame PSNR
  tear  hard edges in the output that do not exist in the truth (metrics.tear)
  flick temporal instability: mean |(out(t2) - out(t1)) - (truth(t2) - truth(t1))| over consecutive phases (0..255). Generated
        frames must change from phase to phase the way the real scene does; popping boundaries or a vector that flips between
        two objects from one output frame to the next show up here even when every single frame has a fine PSNR.

    FPSB_EDGE=0 python3 eval_edges.py --engines new,new_ai      # before (edge-aware vector selection off)
    FPSB_EDGE=1 python3 eval_edges.py --engines new,new_ai      # after
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "ai"))

import metrics as M  # noqa: E402
from run_eval import make_engine  # noqa: E402
import gen_data as GD  # noqa: E402


def make_scene(rng: np.random.Generator, w: int, h: int):
    sc = GD.RandomScene(rng, w, h)
    sc.vbg = np.zeros(2)                       # static camera: the only motion is the subjects'
    sc.rot, sc.zoom, sc.wobble, sc.sigma, sc.gain = 0.0, 1.0, None, 1.0, 0.0
    # at least two subjects, regenerated so they exist whatever the random draw gave
    while len(sc.sprites) < 2:
        sc.sprites.append(GD.Sprite(rng, w, h, np.zeros(2), 1.0))
    for s in sc.sprites:
        s.v = s.v - s.v * 0.0                  # keep each subject's own velocity
    return sc


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engines", default="new,new_ai")
    ap.add_argument("--scenes", type=int, default=24)
    ap.add_argument("--size", default="960x540")
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--phases", default="0.2,0.4,0.6,0.8")
    args = ap.parse_args()
    w, h = (int(v) for v in args.size.split("x"))
    phases = [float(p) for p in args.phases.split(",")]

    rng = np.random.default_rng(args.seed)
    cases = []
    for i in range(args.scenes):
        sc = make_scene(rng, w, h)
        nseed = int(rng.integers(1 << 30))
        a, b = sc.render(0.0, nseed), sc.render(1.0, nseed + 1)
        gts = [(t, sc.render(t, nseed + 2 + j)) for j, t in enumerate(phases)]
        cases.append((a, b, gts))

    print(f"{args.scenes} scenes x {len(phases)} phases, {w}x{h}, static background, independent subjects")
    for en in args.engines.split(","):
        eng = make_engine(en)
        ed, al, te, fl = [], [], [], []
        for a, b, gts in cases:
            eng.reset()
            eng.push(a)
            eng.push(b)
            prev = None
            for t, gt in gts:
                out = eng.interpolate(t)
                if prev is not None:
                    fl.append(float(np.abs((out.astype(np.float32) - prev[0]) - (gt.astype(np.float32) - prev[1])).mean()))
                prev = (out.astype(np.float32), gt.astype(np.float32))
                ed.append(M.edge_psnr(out, gt, a, b))
                al.append(M.psnr(out, gt))
                te.append(M.tear(out, gt))
        print(f"{eng.name:40s} edge {np.mean(ed):6.2f} dB   all {np.mean(al):6.2f} dB   tear {np.mean(te):5.2f}   flick {np.mean(fl):5.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
