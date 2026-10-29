#!/usr/bin/env python3
"""
Real-clip evaluation: every frame k of a segment is rebuilt from its neighbours k-1 and k+1 (t = 0.5) and compared with the
real frame k. This is the honest test for fast film footage: the "ground truth" is a real camera frame, with real motion
blur, real occlusions and real shot cuts.

    python3 eval_clip.py clip.mp4 --start 12.0 --dur 3.0 --engines repeat,blend,new,new_ai --width 960 --save

Triples that straddle a hard shot cut are reported separately (no true in-between exists; the engine must output a clean
A or B), everything else is scored with PSNR / SSIM. --save writes strips (output | real | 4x error) of the worst triples.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

import metrics as M
from run_eval import make_engine

OUT = Path(__file__).resolve().parent / "out"
CUT_ABS = 18.0     # a shot cut: step difference (mean abs, 0..255, 96x54) above this ...
CUT_RATIO = 1.8    # ... and this many times larger than the steps next to it


def dedupe(frames: list[np.ndarray], thresh: float = 0.4) -> list[np.ndarray]:
    """Screen recordings / 24 -> 30 FPS containers repeat every 5th frame. Interpolating from a repeat is not what the app
    sees (it gets the 24 FPS decoder output), and a repeated frame as "ground truth" is not a real in-between. Drop frames that
    are (almost) identical to the previous kept one."""
    out = [frames[0]]
    prev = cv2.resize(frames[0], (160, 90)).astype(np.float32)
    for f in frames[1:]:
        cur = cv2.resize(f, (160, 90)).astype(np.float32)
        if np.abs(cur - prev).mean() >= thresh:
            out.append(f)
        prev = cur
    return out


def load(path: str, start: float, dur: float, width: int) -> list[np.ndarray]:
    probe = cv2.VideoCapture(path)
    w0, h0 = int(probe.get(cv2.CAP_PROP_FRAME_WIDTH)), int(probe.get(cv2.CAP_PROP_FRAME_HEIGHT))
    probe.release()
    w = width & ~1
    h = int(round(h0 * w / w0)) & ~1
    cmd = ["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(dur), "-i", path,
           "-vf", f"scale={w}:{h}:flags=lanczos", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    n = len(raw) // (w * h * 3)
    return list(np.frombuffer(raw[: n * w * h * 3], np.uint8).reshape(n, h, w, 3))


def find_cuts(fr: list[np.ndarray]) -> set[int]:
    """Indices i with a hard shot cut between frame i and i+1: that step differs far more than its neighbours do
    (fast motion changes a lot too, but gradually; a cut jumps)."""
    th = [cv2.resize(f, (96, 54)).astype(np.int16) for f in fr]
    d = [float(np.abs(th[i + 1] - th[i]).mean()) for i in range(len(th) - 1)]
    cuts = set()
    for i, v in enumerate(d):
        nb = max(d[i - 1] if i > 0 else 0.0, d[i + 1] if i + 1 < len(d) else 0.0)
        if v > CUT_ABS and v > CUT_RATIO * nb:
            cuts.add(i)
    return cuts


def motion_px(a: np.ndarray, b: np.ndarray) -> float:
    """90th percentile of the Farneback flow magnitude between two frames (px, at the evaluated width)."""
    ga, gb = (cv2.cvtColor(cv2.resize(x, (480, int(x.shape[0] * 480 / x.shape[1]))), cv2.COLOR_RGB2GRAY) for x in (a, b))
    f = cv2.calcOpticalFlowFarneback(ga, gb, None, 0.5, 4, 21, 3, 5, 1.1, 0)
    return float(np.percentile(np.linalg.norm(f, axis=2), 90)) * a.shape[1] / 480.0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--dur", type=float, default=3.0)
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--engines", default="repeat,blend,new,new_ai")
    ap.add_argument("--keep-repeats", action="store_true", help="do not drop repeated frames (24 -> 30 FPS pulldown)")
    ap.add_argument("--save", action="store_true")
    ap.add_argument("--worst", type=int, default=6)
    ap.add_argument("--only", default="", help="comma list of triple indices to save strips for (with --save), instead of the worst ones")
    ap.add_argument("--tag", default="clip")
    args = ap.parse_args()

    fr = load(args.video, args.start, args.dur, args.width)
    if not args.keep_repeats:
        n0 = len(fr)
        fr = dedupe(fr)
        print(f"dropped {n0 - len(fr)} repeated frames ({n0} -> {len(fr)})", file=sys.stderr)
    print(f"{len(fr)} frames {fr[0].shape[1]}x{fr[0].shape[0]} from {args.start}s +{args.dur}s", file=sys.stderr)
    triples = [k for k in range(1, len(fr) - 1)]
    cuts = find_cuts(fr)
    cut_k = {k for k in triples if (k - 1) in cuts or k in cuts}
    mot = {k: motion_px(fr[k - 1], fr[k + 1]) for k in triples if k not in cut_k}
    print(f"cuts at frames {sorted(cut_k)}", file=sys.stderr)

    res: dict[str, dict[int, tuple[float, float]]] = {}
    tears: dict[str, dict[int, float]] = {}
    outs: dict[str, dict[int, np.ndarray]] = {}
    for en in args.engines.split(","):
        eng = make_engine(en)
        r, o, tr = {}, {}, {}
        for k in triples:
            eng.reset()
            eng.push(fr[k - 1])
            eng.push(fr[k + 1])
            out = eng.interpolate(0.5)
            o[k] = out
            if k in cut_k:   # judge by cleanliness: nearest of A / B
                r[k] = (max(M.psnr(out, fr[k - 1]), M.psnr(out, fr[k + 1])), max(M.ssim(out, fr[k - 1]), M.ssim(out, fr[k + 1])))
            else:
                r[k] = (M.psnr(out, fr[k]), M.ssim(out, fr[k]))
                tr[k] = M.tear(out, fr[k])
        res[eng.name], outs[eng.name], tears[eng.name] = r, o, tr
        print(f"[{eng.name}] done", file=sys.stderr)

    names = list(res)
    ok = [k for k in triples if k not in cut_k]
    tiers = [("all non-cut", ok), ("fast (p90 motion > 25 px)", [k for k in ok if mot[k] > 25]),
             ("very fast (> 50 px)", [k for k in ok if mot[k] > 50]), ("moderate (<= 25 px)", [k for k in ok if mot[k] <= 25])]
    print(f"{'subset':28s} {'n':>3s} " + "".join(f"| {n[:34]:<34s}" for n in names))
    for label, ks in tiers:
        if not ks:
            continue
        print(f"{label:28s} {len(ks):3d} " + "".join(
            f"| {np.mean([res[n][k][0] for k in ks]):5.2f} dB  ssim {np.mean([res[n][k][1] for k in ks]):.3f} tear {np.mean([tears[n][k] for k in ks]):.2f} "
            for n in names))
    if cut_k:
        print(f"{'shot cuts (clean A/B)':28s} {len(cut_k):3d} " + "".join(
            f"| {np.mean([res[n][k][0] for k in cut_k]):5.2f} dB  ssim {np.mean([res[n][k][1] for k in cut_k]):.3f}            " for n in names))

    base = next((n for n in names if n.startswith("blend")), names[0])
    sub = next(n for n in names if "optical-flow" in n) if any("optical-flow" in n for n in names) else names[-1]
    worse = sorted(ok, key=lambda k: res[sub][k][0] - res[base][k][0])[: args.worst]
    print(f"\nworst triples of [{sub}] relative to [{base}]:")
    for k in worse:
        print(f"  frame {k:3d} motion {mot[k]:5.1f}px  " + "  ".join(f"{n[:14]} {res[n][k][0]:5.2f}" for n in names))
    if args.save:
        OUT.mkdir(exist_ok=True)
        hand = next((n for n in names if "optical-flow" in n and "+AI" not in n), None)
        ai = next((n for n in names if "+AI" in n), None)
        for k in ([int(v) for v in args.only.split(",")] if args.only else worse):
            gt = fr[k]
            label = lambda img, text: cv2.putText(img.copy(), text, (12, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2, cv2.LINE_AA)
            panels = [("real frame", gt)]
            panels += [(n.split("[")[0].strip() if n.startswith(("blend", "repeat")) else ("hand-tuned" if n == hand else "AI fusion"),
                        outs[n][k]) for n in names]
            tiles = [label(img, f"{t}  {res[n][k][0]:.1f} dB" if n else t) for (t, img), n in zip(panels, [None] + names)]
            while len(tiles) % 2:
                tiles.append(np.zeros_like(tiles[0]))
            rows = [np.hstack(tiles[i:i + 2]) for i in range(0, len(tiles), 2)]
            cv2.imwrite(str(OUT / f"{args.tag}_f{k:03d}.png"), cv2.cvtColor(np.vstack(rows), cv2.COLOR_RGB2BGR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
