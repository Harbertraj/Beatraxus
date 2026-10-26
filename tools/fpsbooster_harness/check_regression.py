#!/usr/bin/env python3
"""
GPU-free regression gate for the optical-flow engine (runs on Mesa llvmpipe).
Fails if image quality drops below thresholds set slightly under the measured values (see docs/FPS_BOOSTER.md).

    ./fetch_data.sh && python3 check_regression.py
"""
import sys

import engines as E
import eval_interior as EI
import metrics as M
import new_engine as N
import run_eval as R
import scenarios as S

W, H = 1280, 720
# scene name -> minimum mean PSNR (dB) for the 'balanced' preset
FLOORS = {
    "pan 3,0px/f": 40.0, "pan 10,0px/f": 38.0, "pan 28,0px/f": 27.0, "pan 56,0px/f": 23.0,
    "pan 20,12px/f": 24.0, "zoom x1.012/f rot 0.35deg/f": 36.0, "layers bg 4 / fg -22px/f": 30.0,
}


def main() -> int:
    eng = N.NewEngine("balanced")
    ok = True
    scenes = {s.name: s for s in (S.pan(W, H, 3), S.pan(W, H, 10), S.pan(W, H, 28), S.pan(W, H, 56),
                                  S.pan(W, H, 20, 12), S.zoom_rot(W, H), S.layers(W, H))}
    blend = E.BlendEngine()
    for name, floor in FLOORS.items():
        r = R.eval_scene(eng, scenes[name], False, "x")
        b = R.eval_scene(blend, scenes[name], False, "x")
        good = r["psnr"] >= floor and r["psnr"] > b["psnr"] + 3.0
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  {name:30s} new {r['psnr']:5.1f} dB  (floor {floor}, blend {b['psnr']:.1f})")
    # fast motion, interior only (see eval_interior.py); floors sit a few dB under the measured values
    interior_floor = {"pan 28,0px/f": 38.0, "pan 56,0px/f": 38.0, "pan 90,0px/f": 50.0, "pan 140,0px/f": 55.0,
                      "pan 200,0px/f": 50.0, "pan 60,30px/f": 42.0}
    for sc, bx, by in EI.scenes(W, H):
        floor = interior_floor.get(sc.name)
        if floor is None:
            continue
        p = EI.eval_scene_interior(eng, sc, bx, by)
        good = p >= floor
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  interior {sc.name:22s} new {p:5.1f} dB  (floor {floor})")
    cut = R.eval_scene(eng, S.scene_cut(W, H), False, "x")
    good = cut["psnr"] >= 90.0
    ok &= good
    print(f"{'PASS' if good else 'FAIL'}  scene cut stays clean (never a mix of two shots): {cut['psnr']:.1f} dB")
    real = R.eval_real(eng, S.real_triples())
    rb = R.eval_real(blend, S.real_triples())
    good = real["ssim"] >= rb["ssim"] - 0.002
    ok &= good
    print(f"{'PASS' if good else 'FAIL'}  real footage SSIM {real['ssim']:.3f} vs blend {rb['ssim']:.3f}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
