#!/usr/bin/env python3
"""
Fusion-network training records from ONE real clip ("drop a frame and rebuild it"), with a time split so the result
can be validated on footage the network never saw.

    python3 gen_clip_data.py clip.mp4 --range 1:255   --out ../data/clip_train
    python3 gen_clip_data.py clip.mp4 --range 255:364 --out ../data/clip_val

Triples are (k, k+s) with s in 2..4 and every frame in between as the truth at phase j/s. Pairs that straddle a shot
cut are skipped. Records have the same format as gen_data.py's, so train_fusion.py reads both together.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import eval_real as ER  # noqa: E402
import gen_data as G  # noqa: E402
import new_engine as N  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--range", required=True, help="a:b first-frame indices of the triples (inclusive a, exclusive b)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--dur", type=float, default=19.3)
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--step", type=int, default=1, help="use every Nth first-frame index")
    ap.add_argument("--shard", type=int, default=40)
    args = ap.parse_args()

    fr, meta = ER.load_cached(args.video, 0.0, args.dur, args.width, False)
    cuts = set(meta["cuts"])
    a, b = (int(v) for v in args.range.split(":"))
    rng = np.random.default_rng(args.seed)
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    engines: dict[str, N.NewEngine] = {}
    recs, shard_i, n_pairs, t0 = [], 0, 0, time.time()
    for k in range(max(0, a), b, args.step):
        s = int(rng.choice([2, 2, 3, 4]))
        if k + s >= len(fr) or any(i in cuts for i in range(k, k + s)):
            continue
        preset = str(rng.choice(G.PRESETS, p=G.PRESET_P))
        eng = engines.get(preset) or engines.setdefault(preset, N.NewEngine(preset))
        eng.reset(); eng.push(np.asarray(fr[k])); eng.push(np.asarray(fr[k + s]))
        for j in rng.choice(np.arange(1, s), size=min(2, s - 1), replace=False):
            d = eng.dump(float(j) / s)
            r = G.extract(rng, d, np.asarray(fr[k + int(j)]), eng.fuseW, eng.fuseH)
            if r is not None:
                recs.append(r)
        n_pairs += 1
        if n_pairs % args.shard == 0:
            shard_i = flush(out, recs, shard_i, args.seed); recs = []
            print(f"{n_pairs} pairs, {time.time() - t0:.0f}s", flush=True)
    flush(out, recs, shard_i, args.seed)
    print(f"done: {n_pairs} pairs")
    return 0


def flush(out: Path, recs: list, i: int, seed: int) -> int:
    if not recs:
        return i
    np.savez_compressed(out / f"shard_c{seed}_{i:03d}.npz", x=np.concatenate([r["x"] for r in recs]),
                        cand=np.concatenate([r["cand"] for r in recs]), gt=np.concatenate([r["gt"] for r in recs]),
                        rec_len=np.array([len(r["x"]) for r in recs]))
    return i + 1


if __name__ == "__main__":
    sys.exit(main())
