#!/usr/bin/env python3
"""
Fast, repeatable real-footage evaluation with per-frame results (companion of eval_clip.py).

    python3 eval_real.py clip.mp4 --dur 19.3 --engines blend,new,new_ai --json out/real_new.json
    FPSB_ASSETS=/path/to/shader_copy python3 eval_real.py ... --engines new_ai      # try a modified shader set
    FPSB_AI_WEIGHTS=/path/to/ai_fusion.glsl python3 eval_real.py ... --engines new_ai  # try other fusion weights

Every kept frame k is rebuilt from k-1 and k+1 (t = 0.5) and compared with the real frame k, exactly like eval_clip.py,
but the decoded footage is cached next to the video's name hash and every frame's PSNR / SSIM / tear / edge PSNR is
written to a JSON file, so two variants can be compared frame by frame and by segment (--segments N).

--only-every N scores every Nth triple (quick look), --range a:b restricts to kept-frame indices (train / test split).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import numpy as np

import eval_clip as EC
import metrics as M
from run_eval import make_engine

CACHE = Path(__file__).resolve().parent / "out" / "cache"


def load_cached(video: str, start: float, dur: float, width: int, keep_repeats: bool):
    key = hashlib.sha1(f"{video}|{start}|{dur}|{width}|{keep_repeats}".encode()).hexdigest()[:12]
    CACHE.mkdir(parents=True, exist_ok=True)
    fpath, mpath = CACHE / f"{key}_frames.npy", CACHE / f"{key}_meta.json"
    if fpath.exists() and mpath.exists():
        return np.load(fpath, mmap_mode="r"), json.loads(mpath.read_text())
    fr = EC.load(video, start, dur, width)
    if not keep_repeats:
        fr = EC.dedupe(fr)
    cuts = sorted(EC.find_cuts(fr))
    cut_k = sorted({k for k in range(1, len(fr) - 1) if (k - 1) in cuts or k in cuts})
    mot = {str(k): EC.motion_px(fr[k - 1], fr[k + 1]) for k in range(1, len(fr) - 1) if k not in cut_k}
    arr = np.stack(fr)
    np.save(fpath, arr)
    meta = dict(cuts=cuts, cut_k=cut_k, motion=mot)
    mpath.write_text(json.dumps(meta))
    return np.load(fpath, mmap_mode="r"), meta


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--dur", type=float, default=19.3)
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--engines", default="blend,new,new_ai")
    ap.add_argument("--keep-repeats", action="store_true")
    ap.add_argument("--only-every", type=int, default=1)
    ap.add_argument("--range", default="", help="a:b kept-frame index range (inclusive a, exclusive b)")
    ap.add_argument("--segments", type=int, default=4, help="report the score in this many equal time segments")
    ap.add_argument("--stream", action="store_true",
                    help="streaming protocol: even / odd frames are fed to the engine as consecutive source frames (so engine "
                         "state such as the previous pair's cut flag behaves as in the app) and the skipped frame is the truth")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    fr, meta = load_cached(args.video, args.start, args.dur, args.width, args.keep_repeats)
    cut_k = set(meta["cut_k"])
    mot = {int(k): v for k, v in meta["motion"].items()}
    lo, hi = 1, len(fr) - 1
    if args.range:
        a, b = args.range.split(":")
        lo, hi = max(1, int(a)), min(len(fr) - 1, int(b))
    triples = [k for k in range(lo, hi) if (k - lo) % args.only_every == 0]
    print(f"{len(fr)} frames {fr.shape[2]}x{fr.shape[1]}, {len(triples)} triples, cuts at {sorted(cut_k)}", file=sys.stderr)

    out: dict[str, dict] = {}
    for en in args.engines.split(","):
        eng = make_engine(en)
        rec = {}
        t0 = time.time()
        def score(k, o):
            a, b, gt = np.asarray(fr[k - 1]), np.asarray(fr[k + 1]), np.asarray(fr[k])
            if k in cut_k:
                rec[k] = dict(psnr=max(M.psnr(o, a), M.psnr(o, b)), ssim=max(M.ssim(o, a), M.ssim(o, b)), cut=1)
            else:
                rec[k] = dict(psnr=M.psnr(o, gt), ssim=M.ssim(o, gt), tear=M.tear(o, gt), edge=M.edge_psnr(o, gt, a, b), cut=0)

        if args.stream:
            want = set(triples)
            for parity in (0, 1):
                eng.reset()
                first = lo - 1 - ((lo - 1 - parity) % 2)     # first source frame of this parity at or below lo-1
                if first < 0:
                    first += 2
                eng.push(np.asarray(fr[first]))
                k = first + 1
                while k + 1 < len(fr) and k < hi:
                    eng.push(np.asarray(fr[k + 1]))
                    o = eng.interpolate(0.5)          # always: keeps the engine's per-pair state in step with the app
                    if k in want:
                        score(k, o)
                    k += 2
        else:
            for k in triples:
                eng.reset(); eng.push(np.asarray(fr[k - 1])); eng.push(np.asarray(fr[k + 1]))
                score(k, eng.interpolate(0.5))
        out[en] = rec
        print(f"[{en}] {len(triples)} triples in {time.time() - t0:.0f}s", file=sys.stderr)

    names = list(out)
    ok = [k for k in triples if k not in cut_k]
    tiers = [("all non-cut", ok), ("fast (>25 px)", [k for k in ok if mot.get(k, 0) > 25]),
             ("very fast (>50 px)", [k for k in ok if mot.get(k, 0) > 50]), ("slow (<2 px)", [k for k in ok if mot.get(k, 0) < 2]),
             ("moderate (<=25 px)", [k for k in ok if mot.get(k, 0) <= 25])]
    print(f"{'subset':22s} {'n':>4s} " + "".join(f"| {n[:30]:<30s}" for n in names))
    for label, ks in tiers:
        if ks:
            print(f"{label:22s} {len(ks):4d} " + "".join(
                f"| {np.mean([out[n][k]['psnr'] for k in ks]):6.2f} dB ssim {np.mean([out[n][k]['ssim'] for k in ks]):.3f}    " for n in names))
    cks = [k for k in triples if k in cut_k]
    if cks:
        print(f"{'shot cuts':22s} {len(cks):4d} " + "".join(f"| {np.mean([out[n][k]['psnr'] for k in cks]):6.2f} dB                  " for n in names))
    if args.segments > 1 and ok:
        edges = np.linspace(ok[0], ok[-1] + 1, args.segments + 1).astype(int)
        for s in range(args.segments):
            ks = [k for k in ok if edges[s] <= k < edges[s + 1]]
            if ks:
                print(f"{'segment %d (%d-%d)' % (s + 1, edges[s], edges[s + 1]):22s} {len(ks):4d} " + "".join(
                    f"| {np.mean([out[n][k]['psnr'] for k in ks]):6.2f} dB                  " for n in names))
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(dict(meta=dict(cut_k=sorted(cut_k), motion=mot), results=out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
