#!/usr/bin/env python3
"""
Renders a film-like sample clip for the FPS Booster harness when no real footage can be downloaded.

It is built from real photographs (data/*.jpg|png, see setup_local_data.py) and mimics what makes real films hard for a
frame interpolator, none of which the plain synthetic scenes have:

  * handheld camera: slow drift + 0.4-3.5 Hz shake + slight roll, occasional dolly (zoom) and whip pans,
  * independently moving subjects with feathered edges that cross each other and the background (occlusion / disocclusion),
  * 180-degree shutter motion blur (each frame integrates 7 sub-frames over half the frame interval),
  * film grain, vignette, slow exposure drift,
  * hard shot cuts every 1.5-3 s,
  * lossy H.264 (CRF 20, 4:2:0): block / ringing artefacts like a real file.

It is NOT camera footage: the motion is rendered from a continuous-time model, so the clip is a stress test with real
photographic texture, not a substitute for a field test. `eval_clip.py` consumes it exactly like a real file:

    python3 make_sample_clip.py data/sample_clip.mp4 --seconds 12 --fps 24
    python3 eval_clip.py data/sample_clip.mp4 --dur 12 --width 960 --engines repeat,blend,new,new_ai
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scenarios as S  # noqa: E402

# Two disjoint photo pools so a clip used to TRAIN the fusion network never shares a photo with the clip used to EVALUATE it
# (the evaluation pool is the held-out set of gen_data.py: building / fruits / butterfly).
POOLS = {
    "eval": (["building.jpg", "fruits.jpg", "butterfly.jpg"], ["butterfly.jpg", "fruits.jpg", "building.jpg"]),
    "train": (["home.jpg", "starry_night.jpg", "baboon.jpg", "aloeL.jpg", "graf1.png"], ["lena.jpg", "messi5.jpg", "stuff.jpg", "board.jpg"]),
}
BACKGROUNDS, SUBJECTS = POOLS["eval"]
SUBFRAMES = 7
SHUTTER = 0.5            # fraction of the frame interval the shutter is open (180 degrees)


def smooth_noise(rng: np.random.Generator, n: int, hz_lo: float, hz_hi: float, fps: float) -> np.ndarray:
    """Band-limited noise, one sample per sub-frame step, unit RMS."""
    x = rng.standard_normal(n).astype(np.float64)
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, d=1.0 / fps)
    spec[(f < hz_lo) | (f > hz_hi)] = 0
    y = np.fft.irfft(spec, n)
    return y / max(y.std(), 1e-9)


class Subject:
    def __init__(self, rng: np.random.Generator, w: int, h: int, speed: float) -> None:
        r = float(rng.uniform(0.10, 0.26) * h)
        size = int(2 * r * 1.4) | 1
        self.tex = S.make_canvas(str(rng.choice(SUBJECTS)), size, size, int(rng.integers(1 << 30))).astype(np.float32)
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
        c = (size - 1) / 2
        ax, ay = r * rng.uniform(0.55, 1.0), r * rng.uniform(0.7, 1.0)
        d = np.sqrt(((xx - c) / ax) ** 2 + ((yy - c) / ay) ** 2)
        feather = float(rng.uniform(1.5, 4.0)) / max(ax, ay)
        self.mask = np.clip((1.0 - d) / feather, 0, 1).astype(np.float32)
        self.c = c
        ang = rng.uniform(0, 2 * np.pi)
        self.v = speed * w * np.array([np.cos(ang), np.sin(ang)]) / 24.0       # px / second at 24 fps scale
        self.p0 = np.array([rng.uniform(0.25, 0.75) * w, rng.uniform(0.25, 0.75) * h])
        self.rot = float(rng.normal(0, 12.0))                                    # deg / second
        self.wob = (float(rng.uniform(0.003, 0.012) * h), float(rng.uniform(1.0, 3.0)), float(rng.uniform(0, 6.28)))

    def draw(self, base: np.ndarray, t: float, w: int, h: int) -> np.ndarray:
        cx = self.p0[0] + self.v[0] * t
        cy = self.p0[1] + self.v[1] * t + self.wob[0] * np.sin(self.wob[1] * 2 * np.pi * t + self.wob[2])
        a = np.deg2rad(self.rot * t)
        R = np.array([[np.cos(a), np.sin(a)], [-np.sin(a), np.cos(a)]])       # dst -> src
        off = np.array([self.c, self.c]) - R @ np.array([cx, cy])
        M = np.hstack([R, off[:, None]])
        s = cv2.warpAffine(self.tex, M, (w, h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderValue=0)
        m = cv2.warpAffine(self.mask, M, (w, h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderValue=0)[:, :, None]
        return base * (1 - m) + s * m


class Shot:
    def __init__(self, rng: np.random.Generator, w: int, h: int, seconds: float, fps: float) -> None:
        self.w, self.h = w, h
        self.seconds = seconds
        n = int(seconds * fps * SUBFRAMES) + SUBFRAMES * 2
        sub_fps = fps * SUBFRAMES
        style = str(rng.choice(["calm", "handheld", "handheld", "action", "whip"]))
        self.style = style
        pan = {"calm": 0.03, "handheld": 0.07, "action": 0.14, "whip": 0.16}[style] * w     # px / second drift scale
        shake = {"calm": 0.0015, "handheld": 0.004, "action": 0.007, "whip": 0.005}[style] * w
        ang = rng.uniform(0, 2 * np.pi)
        t = np.arange(n) / sub_fps
        drift = np.stack([np.cos(ang) * t, np.sin(ang) * t * 0.5], 1) * pan
        self.cam = drift + np.stack([smooth_noise(rng, n, 0.4, 3.5, sub_fps), smooth_noise(rng, n, 0.4, 3.5, sub_fps)], 1) * shake
        if style == "whip":     # one fast pan in the middle of the shot
            k = n // 2
            bump = np.exp(-0.5 * ((np.arange(n) - k) / (0.12 * fps * SUBFRAMES / 3)) ** 2)
            self.cam[:, 0] += np.cumsum(bump) / bump.sum() * 0.9 * w * rng.choice([-1, 1])
        self.roll = smooth_noise(rng, n, 0.5, 4.0, sub_fps) * (0.25 if style != "calm" else 0.08)     # degrees
        self.zoom = 1.0 + 0.02 * np.sin(2 * np.pi * rng.uniform(0.05, 0.2) * t + rng.uniform(0, 6.28)) * (style != "calm")
        self.exposure = 1.0 + 0.04 * np.sin(2 * np.pi * rng.uniform(0.1, 0.4) * t)
        margin = int(np.abs(self.cam).max() + 0.12 * w + 32)
        cw, ch = int((w + 2 * margin) * 1.1), int((h + 2 * margin) * 1.1)
        self.canvas = S.make_canvas(str(rng.choice(BACKGROUNDS)), cw, ch, int(rng.integers(1 << 30))).astype(np.float32)
        nsub = {"calm": 1, "handheld": 2, "action": 3, "whip": 1}[style]
        speed = {"calm": 0.05, "handheld": 0.12, "action": 0.25, "whip": 0.1}[style]
        self.subjects = [Subject(rng, w, h, speed) for _ in range(nsub if rng.random() < 0.9 else 0)]
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
        self.vignette = (1.0 - 0.18 * np.clip(r - 0.4, 0, 1) ** 2)[:, :, None].astype(np.float32)
        self.grain = float(rng.choice([1.5, 2.5, 4.0]))

    def sub_frame(self, j: int, shot_t: float) -> np.ndarray:
        w, h = self.w, self.h
        cx, cy = self.canvas.shape[1] / 2, self.canvas.shape[0] / 2
        a = np.deg2rad(self.roll[j])
        z = self.zoom[j]
        R = np.array([[np.cos(a), np.sin(a)], [-np.sin(a), np.cos(a)]]) / z
        centre = np.array([cx, cy]) + self.cam[j]
        M = np.hstack([R, (centre - R @ np.array([w / 2, h / 2]))[:, None]])
        img = cv2.warpAffine(self.canvas, M, (w, h), flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_REFLECT_101)
        for s in self.subjects:
            img = s.draw(img, shot_t, w, h)
        return img * self.exposure[j]

    def frame(self, k: int, fps: float, rng: np.random.Generator) -> np.ndarray:
        """Frame k: average of SUBFRAMES samples over the open-shutter part of the interval."""
        acc = np.zeros((self.h, self.w, 3), np.float32)
        base = k * SUBFRAMES
        for s in range(SUBFRAMES):
            j = base + int(round(s * (SHUTTER * SUBFRAMES - 1) / max(SUBFRAMES - 1, 1)))
            acc += self.sub_frame(j, (k + SHUTTER * s / SUBFRAMES) / fps)
        img = acc / SUBFRAMES * self.vignette
        img += rng.standard_normal(img.shape[:2]).astype(np.float32)[:, :, None] * self.grain * np.array([1.0, 1.0, 1.1], np.float32)
        return np.clip(img + 0.5, 0, 255).astype(np.uint8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--fps", type=float, default=24.0)
    ap.add_argument("--size", default="960x540")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--crf", type=int, default=20)
    ap.add_argument("--pool", choices=sorted(POOLS), default="eval", help="photo pool: eval (held out) or train")
    args = ap.parse_args()
    global BACKGROUNDS, SUBJECTS
    BACKGROUNDS, SUBJECTS = POOLS[args.pool]
    w, h = (int(v) for v in args.size.split("x"))
    rng = np.random.default_rng(args.seed)
    total = int(round(args.seconds * args.fps))
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(args.fps), "-i", "-",
           "-c:v", "libx264", "-preset", "medium", "-crf", str(args.crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart", args.out]
    ff = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    done = 0
    cuts = []
    while done < total:
        n = min(total - done, int(rng.uniform(1.5, 3.0) * args.fps))
        shot = Shot(rng, w, h, n / args.fps, args.fps)
        print(f"shot {len(cuts)} frames {done}-{done + n - 1} style={shot.style} subjects={len(shot.subjects)}", file=sys.stderr)
        for k in range(n):
            ff.stdin.write(shot.frame(k, args.fps, rng).tobytes())
        cuts.append(done)
        done += n
    ff.stdin.close()
    ff.wait()
    print(f"wrote {args.out}: {total} frames {w}x{h} @ {args.fps} fps, shot cuts at frames {cuts[1:]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
