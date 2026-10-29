#!/usr/bin/env python3
"""
Generates the training set of the AI fusion network by running the SHIPPED shaders (flow_interp.frag in dump
mode) on ground-truth scenes, so the network is trained on exactly the numbers it sees on the phone.

Each record = one (frame A, frame B, phase t) triple. For every pixel the fusion pass would send through the
network we store: the network inputs x (22), the six candidate colours (bilinear taps, as the shader computes
them) and the true colour of the in-between frame at the same position.

Scene sources
  * synthetic, rendered from a continuous-time model, so the true in-between frame is exact: a panning /
    zooming / rotating background with 0-4 independently moving textured sprites (occlusion + disocclusion),
    optional sensor noise, brightness drift and non-rigid wobble
  * real footage ("drop a frame and rebuild it"): frames k and k+s of a video, truth = frame k+j at t = j/s

Held out of training (evaluation only): building.jpg, fruits.jpg, butterfly.jpg, vtest.avi.

    ./fetch_train_media.sh
    python3 gen_data.py --out data/ai --seed 1 --pairs 300 &
    python3 gen_data.py --out data/ai --seed 2 --pairs 300 &
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import new_engine as N  # noqa: E402
import scenarios as S  # noqa: E402

DATA = HERE.parent / "data"
TRAIN_IMAGES = ["lena.jpg", "baboon.jpg", "messi5.jpg", "starry_night.jpg", "graf1.png", "aloeL.jpg", "board.jpg",
                "stuff.jpg", "home.jpg"]
TRAIN_VIDEOS = ["Megamind.avi", "big_buck_bunny.mp4", "768x576.avi", "david.webm"]
PRESETS = ["performance", "balanced", "quality"]
PRESET_P = [0.3, 0.4, 0.3]
PHASE_POOL = [0.2, 0.25, 0.4, 0.5, 0.6, 0.75, 0.8]
SAMPLE_CONTESTED = 5000     # per record, pixels where the candidates disagree (where the decision matters)
SAMPLE_RANDOM = 1500        # per record, any valid pixel
CONTESTED_SPREAD = 0.02     # max colour difference between a candidate and the nearest frame that counts as "contested"


# ---------------------------------------------------------------------------------------------------------------
# synthetic scenes
# ---------------------------------------------------------------------------------------------------------------
def _affine(cx: float, cy: float, tx: float, ty: float, rot_deg: float, scale: float) -> np.ndarray:
    """2x3 matrix mapping destination -> source about (cx, cy): rotate/scale about the centre, then translate."""
    a = np.deg2rad(rot_deg)
    R = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]]) / scale
    c = np.array([cx, cy]) - R @ np.array([cx, cy]) + np.array([tx, ty])
    return np.hstack([R, c[:, None]])


class Sprite:
    def __init__(self, rng: np.random.Generator, w: int, h: int, vbg: np.ndarray, speed_scale: float) -> None:
        self.r = float(rng.uniform(0.05, 0.22) * h)
        size = int(2 * self.r * 1.5) | 1
        tex = S.make_canvas(str(rng.choice(TRAIN_IMAGES)), size, size, int(rng.integers(1 << 30)))
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
        c = (size - 1) / 2
        ax = self.r * float(rng.uniform(0.6, 1.0))
        ay = self.r * float(rng.uniform(0.6, 1.0))
        shape = rng.choice(["disc", "ellipse", "rect"])
        if shape == "rect":
            d = np.maximum(np.abs(xx - c) / ax, np.abs(yy - c) / ay) * min(ax, ay)
            edge = min(ax, ay)
        else:
            d = np.sqrt(((xx - c) / ax) ** 2 + ((yy - c) / ay) ** 2) * min(ax, ay)
            edge = min(ax, ay)
        feather = float(rng.uniform(1.0, 3.5))
        self.mask = np.clip((edge - d) / feather, 0, 1).astype(np.float32)
        self.tex = tex
        self.c = c
        ang = rng.uniform(0, 2 * np.pi)
        rel = speed_scale * float(rng.choice([0.01, 0.03, 0.06, 0.1, 0.14])) * w
        self.v = vbg + rel * np.array([np.cos(ang), np.sin(ang)])
        self.rot = float(rng.normal(0, 2.0))
        self.zoom = float(1.0 + rng.normal(0, 0.01))
        self.p0 = np.array([rng.uniform(0.2, 0.8) * w, rng.uniform(0.2, 0.8) * h]) - 0.5 * self.v   # centre at t = 0.5 is the pick

    def render_into(self, base: np.ndarray, tau: float, w: int, h: int) -> np.ndarray:
        cx, cy = self.p0 + self.v * tau
        M = _affine(self.c, self.c, 0.0, 0.0, -self.rot * tau, self.zoom ** tau)
        # dst(x, y) = sprite(M @ [x - x0, y - y0]) with x0 = cx - c
        x0, y0 = cx - self.c, cy - self.c
        T = np.array([[1, 0, -x0], [0, 1, -y0], [0, 0, 1]], np.float64)
        Mfull = np.vstack([M, [0, 0, 1]]) @ T
        M2 = Mfull[:2]
        s = cv2.warpAffine(self.tex, M2, (w, h), flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP, borderValue=0).astype(np.float32)
        m = cv2.warpAffine(self.mask, M2, (w, h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderValue=0)[:, :, None]
        return base * (1 - m) + s * m


class RandomScene:
    """Background + sprites, all with their own continuous motion. render(tau) -> uint8 RGB at time tau (A = 0, B = 1)."""

    def __init__(self, rng: np.random.Generator, w: int, h: int) -> None:
        self.rng = rng
        self.w, self.h = w, h
        speed = float(rng.choice([0.004, 0.01, 0.02, 0.04, 0.07, 0.11]))      # fraction of the width per frame
        ang = rng.uniform(0, 2 * np.pi)
        self.vbg = speed * w * np.array([np.cos(ang), np.sin(ang)])
        self.rot = float(rng.normal(0, 0.25)) if rng.random() < 0.5 else 0.0
        self.zoom = float(1.0 + rng.normal(0, 0.004)) if rng.random() < 0.5 else 1.0
        margin = int(np.abs(self.vbg).max() * 2 + 48)
        self.margin = margin
        self.canvas = S.make_canvas(str(rng.choice(TRAIN_IMAGES)), int((w + 2 * margin) * 1.2), int((h + 2 * margin) * 1.2),
                                    int(rng.integers(1 << 30)))
        self.sprites = [Sprite(rng, w, h, self.vbg, 1.0) for _ in range(int(rng.choice([0, 1, 1, 2, 2, 3, 4])))]
        self.wobble = (float(rng.uniform(0, 3.0)), float(rng.uniform(0.01, 0.04)), float(rng.uniform(0, 2 * np.pi))) \
            if rng.random() < 0.3 else None
        self.sigma = float(rng.choice([0.0, 0.0, 1.0, 2.5]))
        self.gain = float(rng.normal(0, 0.012))
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        self.grid = (xx, yy)

    def render(self, tau: float, noise_seed: int) -> np.ndarray:
        w, h = self.w, self.h
        cx, cy = self.canvas.shape[1] / 2, self.canvas.shape[0] / 2
        M = _affine(cx, cy, self.vbg[0] * tau, self.vbg[1] * tau, self.rot * tau, self.zoom ** tau)
        # the (w, h) window sits in the middle of the canvas
        win = np.array([[1, 0, cx - w / 2], [0, 1, cy - h / 2], [0, 0, 1]], np.float64)
        Mw = (np.vstack([M, [0, 0, 1]]) @ win)[:2]
        if self.wobble is not None:
            amp, k, ph = self.wobble
            xx, yy = self.grid
            # smooth non-rigid displacement that changes with time (sub-pixel to a few px)
            dx = amp * np.sin(k * yy + 2.0 * tau + ph)
            dy = amp * np.cos(k * xx + 2.0 * tau + ph)
            mx = Mw[0, 0] * (xx + dx) + Mw[0, 1] * (yy + dy) + Mw[0, 2]
            my = Mw[1, 0] * (xx + dx) + Mw[1, 1] * (yy + dy) + Mw[1, 2]
            base = cv2.remap(self.canvas, mx.astype(np.float32), my.astype(np.float32), cv2.INTER_CUBIC,
                             borderMode=cv2.BORDER_REFLECT_101).astype(np.float32)
        else:
            base = cv2.warpAffine(self.canvas, Mw, (w, h), flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP,
                                  borderMode=cv2.BORDER_REFLECT_101).astype(np.float32)
        for sp in self.sprites:
            base = sp.render_into(base, tau, w, h)
        base = base * (1.0 + self.gain * tau)
        if self.sigma > 0:
            n = np.random.default_rng(noise_seed).standard_normal(base.shape).astype(np.float32)
            base = base + n * self.sigma
        return np.clip(base + 0.5, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------------------------------------------------------
# real footage
# ---------------------------------------------------------------------------------------------------------------
class VideoPool:
    def __init__(self, names: list[str], max_frames: int = 400) -> None:
        self.videos: dict[str, list[np.ndarray]] = {}
        for n in names:
            p = DATA / n
            if not p.exists():
                continue
            cap = cv2.VideoCapture(str(p))
            fr = []
            while len(fr) < max_frames:
                ok, f = cap.read()
                if not ok:
                    break
                fr.append(cv2.cvtColor(f, cv2.COLOR_BGR2RGB))
            if len(fr) > 8:
                self.videos[n] = fr
        if not self.videos:
            raise SystemExit("no training videos found - run ai/fetch_train_media.sh")

    def triple(self, rng: np.random.Generator, width: int):
        name = str(rng.choice(list(self.videos)))
        fr = self.videos[name]
        s = int(rng.choice([2, 2, 3, 4]))
        k = int(rng.integers(0, len(fr) - s - 1))
        sc = width / fr[k].shape[1]
        sc = min(sc, 1.5)
        def rs(f):
            if abs(sc - 1.0) < 1e-3:
                return f
            return cv2.resize(f, (int(round(f.shape[1] * sc)) & ~1, int(round(f.shape[0] * sc)) & ~1),
                              interpolation=cv2.INTER_AREA if sc < 1 else cv2.INTER_CUBIC)
        A, B = rs(fr[k]), rs(fr[k + s])
        js = rng.choice(np.arange(1, s), size=min(2, s - 1), replace=False)
        return name, A, B, [(float(j) / s, rs(fr[k + int(j)])) for j in js]


# ---------------------------------------------------------------------------------------------------------------
def extract(rng: np.random.Generator, d: dict, gt: np.ndarray, fw_: int, fh_: int) -> dict | None:
    x, cand, valid = d["x"], d["cand"], d["valid"]
    if valid.sum() < 500:
        return None
    gtm = cv2.resize(gt, (fw_, fh_), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0
    spread = np.abs(cand - cand[:, :, :1, :]).max(axis=(2, 3))
    contested = np.flatnonzero((valid & (spread > CONTESTED_SPREAD)).ravel())
    anyv = np.flatnonzero(valid.ravel())
    pick = []
    if len(contested):
        pick.append(rng.choice(contested, size=min(SAMPLE_CONTESTED, len(contested)), replace=False))
    pick.append(rng.choice(anyv, size=min(SAMPLE_RANDOM, len(anyv)), replace=False))
    idx = np.unique(np.concatenate(pick))
    n = fw_ * fh_
    return dict(
        x=x.reshape(n, -1)[idx].astype(np.float16),
        cand=cand.reshape(n, N.AI_NK, 3)[idx].astype(np.float16),
        gt=gtm.reshape(n, 3)[idx].astype(np.float16),
        contested_share=np.float32(len(contested) / max(1, len(anyv))),
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DATA / "ai"))
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--pairs", type=int, default=100)
    ap.add_argument("--real-share", type=float, default=0.35)
    ap.add_argument("--shard", type=int, default=20, help="pairs per shard file")
    ap.add_argument("--sizes", default="960x540:6,1280x720:2,1920x1080:1,640x360:1",
                    help="synthetic frame sizes with weights, e.g. 960x540:6,1280x720:2")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)
    sizes = [(tuple(int(v) for v in s.split(":")[0].split("x")), float(s.split(":")[1])) for s in args.sizes.split(",")]
    size_p = np.array([p for _, p in sizes]) / sum(p for _, p in sizes)
    pool = VideoPool(TRAIN_VIDEOS)
    engines: dict[str, N.NewEngine] = {}
    shard: list[dict] = []
    meta: list[tuple[int, int, int]] = []
    shard_i = 0
    t0 = time.time()
    for i in range(args.pairs):
        preset = str(rng.choice(PRESETS, p=PRESET_P))
        eng = engines.get(preset) or engines.setdefault(preset, N.NewEngine(preset))
        real = rng.random() < args.real_share
        try:
            if real:
                width = int(rng.choice([640, 768, 960, 1280]))
                name, A, B, gts = pool.triple(rng, width)
                src = 1
            else:
                (w, h), = [sizes[int(rng.choice(len(sizes), p=size_p))][0]]
                scene = RandomScene(rng, w, h)
                nseed = int(rng.integers(1 << 30))
                A, B = scene.render(0.0, nseed), scene.render(1.0, nseed + 1)
                phases = rng.choice(PHASE_POOL, size=3, replace=False)
                gts = [(float(t), scene.render(float(t), nseed + 2 + j)) for j, t in enumerate(phases)]
                src = 0
        except Exception as e:  # a bad random scene must not kill a long run
            print("scene error", repr(e), file=sys.stderr)
            continue
        eng.reset()
        eng.push(A)
        eng.push(B)
        for t, gt in gts:
            d = eng.dump(t)
            rec = extract(rng, d, gt, eng.fuseW, eng.fuseH)
            if rec is None:
                continue
            shard.append(rec)
            meta.append((src, PRESETS.index(preset), int(round(t * 100))))
        if (i + 1) % args.shard == 0 or i + 1 == args.pairs:
            if shard:
                np.savez_compressed(out / f"shard_s{args.seed}_{shard_i:03d}.npz",
                                    x=np.concatenate([r["x"] for r in shard]),
                                    cand=np.concatenate([r["cand"] for r in shard]),
                                    gt=np.concatenate([r["gt"] for r in shard]),
                                    rec_len=np.array([len(r["x"]) for r in shard]),
                                    meta=np.array(meta, np.int32))
                shard_i += 1
                shard, meta = [], []
            print(f"[seed {args.seed}] {i + 1}/{args.pairs} pairs, {time.time() - t0:.0f}s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
