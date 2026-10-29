#!/usr/bin/env python3
"""
GPU-free regression gate for the optical-flow engine (runs on Mesa llvmpipe).
Fails if image quality drops below thresholds set slightly under the measured values (see docs/FPS_BOOSTER.md).

    ./fetch_data.sh && python3 check_regression.py            # hand-tuned path + AI fusion path
    python3 check_regression.py --no-ai                        # hand-tuned path only
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
# Same scenes through the AI fusion path (balanced, 1280x720). Measured 49.1 / 53.8 / 47.6 / 44.1 / 44.4 / 47.2 / 41.7 dB
# with the first trained weights; floors sit a few dB under. A retrained ai_fusion.glsl must not drop below them.
AI_FLOORS = {
    "pan 3,0px/f": 45.0, "pan 10,0px/f": 50.0, "pan 28,0px/f": 43.0, "pan 56,0px/f": 40.0,
    "pan 20,12px/f": 40.0, "zoom x1.012/f rot 0.35deg/f": 43.0, "layers bg 4 / fg -22px/f": 38.0,
}
AI_MAX_LOSS_DB = 0.5   # the AI path may never be worse than the hand-tuned one by more than this, on any scene


def check_whip(eng, ai, blend) -> bool:
    """Whole-frame safeguard (cut_detect.frag .a / FAIL_LO..HI in flow_interp.frag): when the flow fails across the picture
    the output fades to a plain blend, so it must never be worse than blend (the failure it replaces is ragged patchwork)."""
    ok = True
    for speed, blur in ((300, 80), (500, 130)):
        sc = S.whip(W, H, speed, blur)
        b = R.eval_scene(blend, sc, False, "x")["psnr"]
        for label, e in (("hand-tuned", eng), ("AI", ai)):
            r = R.eval_scene(e, sc, False, "x")["psnr"]
            good = r >= b - 0.5
            ok &= good
            print(f"{'PASS' if good else 'FAIL'}  whip {speed}px/f {label:10s} {r:5.1f} dB  (blend {b:.1f}, must not be worse)")
    return ok


def check_ai(eng, ai, scenes, blend) -> bool:
    """AI fusion regression: floors, never worse than the hand-tuned path, clean scene cut, real footage, fast interior motion."""
    ok = True
    for name, floor in AI_FLOORS.items():
        r = R.eval_scene(eng, scenes[name], False, "x")
        a = R.eval_scene(ai, scenes[name], False, "x")
        good = a["psnr"] >= floor and a["psnr"] >= r["psnr"] - AI_MAX_LOSS_DB
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  AI {name:30s} {a['psnr']:5.1f} dB  (floor {floor}, hand-tuned {r['psnr']:.1f})")
    # a different preset: the network was trained on all three, so Performance must hold up too
    perf, perf_ai = N.NewEngine("performance"), N.NewEngine("performance", ai=True)
    for name, floor in (("pan 56,0px/f", 38.0), ("layers bg 4 / fg -22px/f", 34.0)):
        r = R.eval_scene(perf, scenes[name], False, "x")
        a = R.eval_scene(perf_ai, scenes[name], False, "x")
        good = a["psnr"] >= floor and a["psnr"] >= r["psnr"] - AI_MAX_LOSS_DB
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  AI performance {name:24s} {a['psnr']:5.1f} dB  (floor {floor}, hand-tuned {r['psnr']:.1f})")
    interior_floor = {"pan 28,0px/f": 38.0, "pan 56,0px/f": 38.0, "pan 90,0px/f": 50.0, "pan 140,0px/f": 55.0,
                      "pan 200,0px/f": 50.0, "pan 60,30px/f": 42.0}
    for sc, bx, by in EI.scenes(W, H):
        floor = interior_floor.get(sc.name)
        if floor is None:
            continue
        p = EI.eval_scene_interior(ai, sc, bx, by)
        good = p >= floor
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  AI interior {sc.name:19s} {p:5.1f} dB  (floor {floor})")
    cut = R.eval_scene(ai, S.scene_cut(W, H), False, "x")
    good = cut["psnr"] >= 90.0
    ok &= good
    print(f"{'PASS' if good else 'FAIL'}  AI scene cut stays clean (never a mix of two shots): {cut['psnr']:.1f} dB")
    triples = S.real_triples()
    real = R.eval_real(ai, triples)
    hand = R.eval_real(eng, triples)
    rb = R.eval_real(blend, triples)
    good = real["ssim"] >= rb["ssim"] - 0.002 and real["ssim"] >= hand["ssim"] - 0.002
    ok &= good
    print(f"{'PASS' if good else 'FAIL'}  AI real footage SSIM {real['ssim']:.3f} (hand-tuned {hand['ssim']:.3f}, blend {rb['ssim']:.3f})")
    return ok


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
    ok &= check_whip(eng, N.NewEngine("balanced", ai=True) if "--no-ai" not in sys.argv else eng, blend)
    if "--no-ai" not in sys.argv:
        ok &= check_ai(eng, N.NewEngine("balanced", ai=True), scenes, blend)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
