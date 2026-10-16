#!/usr/bin/env python3
"""
Runs interpolation engines on ground-truth scenes and prints a quality table.

    ./fetch_data.sh
    python3 run_eval.py --engines repeat,blend,legacy --size 1280x720

Numbers are PSNR (dB, higher is better) / SSIM (0..1) of the interpolated frame against the true frame,
averaged over the 24->60 / 24->30 phases. They come from a CPU software rasteriser: they measure *image
quality and correctness* of the shaders, NOT speed on a phone GPU.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

import engines as E
import metrics as M
import scenarios as S

OUT = Path(__file__).resolve().parent / "out"


def make_engine(name: str) -> E.Engine:
    if name == "repeat":
        return E.RepeatEngine()
    if name == "blend":
        return E.BlendEngine()
    if name == "legacy":
        return E.LegacyEngine()
    if name == "new":
        import new_engine
        return new_engine.NewEngine()
    raise SystemExit(f"unknown engine {name}")


def eval_scene(eng: E.Engine, scene: S.Scene, save: bool, tag: str) -> dict:
    a, b = scene.pair()
    eng.reset()
    eng.push(a)
    eng.push(b)
    ps, ss, worst = [], [], 99.0
    mix = []
    for t in scene.phases:
        out = eng.interpolate(t)
        if scene.cut:
            # a cut has no "true" in-between: judge by how clean the output is (closest to A or B)
            p = max(M.psnr(out, a), M.psnr(out, b))
            s = max(M.ssim(out, a), M.ssim(out, b))
        else:
            gt = scene.gt(t)
            p, s = M.psnr(out, gt), M.ssim(out, gt)
            if save and abs(t - 0.4) < 1e-6:
                OUT.mkdir(exist_ok=True)
                diff = np.clip(np.abs(out.astype(np.int16) - gt.astype(np.int16)) * 4, 0, 255).astype(np.uint8)
                strip = np.hstack([out, gt, diff])
                cv2.imwrite(str(OUT / f"{tag}.png"), cv2.cvtColor(strip, cv2.COLOR_RGB2BGR))
        ps.append(p); ss.append(s); worst = min(worst, p)
    return dict(psnr=float(np.mean(ps)), ssim=float(np.mean(ss)), worst=float(worst))


def eval_real(eng: E.Engine, triples) -> dict:
    ps, ss = [], []
    for name, a, b, gt in triples:
        eng.reset()
        eng.push(a)
        eng.push(b)
        out = eng.interpolate(0.5)
        ps.append(M.psnr(out, gt)); ss.append(M.ssim(out, gt))
    return dict(psnr=float(np.mean(ps)), ssim=float(np.mean(ss)), worst=float(np.min(ps)))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engines", default="repeat,blend,legacy")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--scenes", default="all", help="comma list of substrings, or 'all'")
    ap.add_argument("--no-real", action="store_true")
    ap.add_argument("--save", action="store_true", help="write out/*.png comparison strips")
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    w, h = (int(v) for v in args.size.lower().split("x"))
    scenes = [
        S.pan(w, h, 3), S.pan(w, h, 10), S.pan(w, h, 28), S.pan(w, h, 56),
        S.pan(w, h, 20, 12), S.zoom_rot(w, h), S.layers(w, h), S.scene_cut(w, h),
    ]
    if args.scenes != "all":
        keys = args.scenes.split(",")
        scenes = [s for s in scenes if any(k in s.name for k in keys)]
    real = [] if args.no_real else S.real_triples()

    names = args.engines.split(",")
    results: dict[str, dict] = {}
    for en in names:
        eng = make_engine(en)
        results[eng.name] = {}
        t0 = time.time()
        for sc in scenes:
            tag = f"{en}_{sc.name.replace(' ', '_').replace('/', '-').replace(',', '_')}"
            results[eng.name][sc.name] = eval_scene(eng, sc, args.save, tag)
        if real:
            results[eng.name]["real footage (vtest, t=0.5)"] = eval_real(eng, real)
        print(f"[{eng.name}] done in {time.time() - t0:.1f}s", file=sys.stderr)

    rows = list(next(iter(results.values())).keys())
    colw = max(len(r) for r in rows) + 2
    head = "scene".ljust(colw) + "".join(f"| {n[:26]:<26}" for n in results)
    print(head)
    print("-" * len(head))
    for r in rows:
        line = r.ljust(colw)
        for n in results:
            v = results[n][r]
            line += f"| {v['psnr']:5.1f} dB  ssim {v['ssim']:.3f}  ".ljust(28)
        print(line)
    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
