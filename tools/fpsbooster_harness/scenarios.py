"""
Ground-truth test scenes for frame interpolation.

Synthetic scenes are rendered from a continuous-time model, so the *true* intermediate frame at any
t in [0, 1] between source frames A (t=0) and B (t=1) is available exactly. Real footage uses the standard
"drop a frame and rebuild it" protocol (interpolate k-1 and k+1, compare against the real frame k).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import cv2
import numpy as np

DATA = Path(__file__).resolve().parent / "data"

# 24 -> 60 fps hits these phases between two source frames (0.4, 0.8, 0.2, 0.6); 24 -> 30 hits 0.8, 0.6, 0.4, 0.2.
PHASES = (0.2, 0.4, 0.6, 0.8)


def _rgb(name: str) -> np.ndarray:
    img = cv2.imread(str(DATA / name), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f"{DATA / name} missing - run fetch_data.sh")
    return cv2.cvtColor(img, cv2.COLOR_BGR2RGB)


def _detail(img: np.ndarray, seed: int) -> np.ndarray:
    """Upscaled stills are soft; add band-limited grain + unsharp so block matching sees real texture."""
    rng = np.random.default_rng(seed)
    h, w = img.shape[:2]
    out = img.astype(np.float32)
    for sigma, amp in ((0.8, 7.0), (2.0, 9.0), (5.0, 8.0)):
        n = rng.standard_normal((h, w)).astype(np.float32)
        n = cv2.GaussianBlur(n, (0, 0), sigma)
        n /= max(float(n.std()), 1e-6)
        out += (n * amp)[:, :, None]
    blur = cv2.GaussianBlur(out, (0, 0), 2.0)
    out = out + 0.8 * (out - blur)
    return np.clip(out, 0, 255).astype(np.uint8)


def make_canvas(name: str, w: int, h: int, seed: int) -> np.ndarray:
    img = _rgb(name)
    s = max(w / img.shape[1], h / img.shape[0])
    img = cv2.resize(img, (int(np.ceil(img.shape[1] * s)), int(np.ceil(img.shape[0] * s))), interpolation=cv2.INTER_CUBIC)
    return _detail(img[:h, :w], seed)


def _sample(canvas: np.ndarray, M: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    """dst(x, y) = canvas(M @ [x, y, 1]); M is 2x3 (dst -> src)."""
    return cv2.warpAffine(canvas, M, size, flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP,
                          borderMode=cv2.BORDER_REFLECT_101)


@dataclass
class Scene:
    name: str
    render: Callable[[float], np.ndarray]           # time in source frames -> RGB uint8
    note: str = ""
    cut: bool = False                               # hard cut between A and B: GT is "show A, then B"
    flow_ab: Callable[[], np.ndarray] | None = None  # optional dense GT flow A->B
    phases: tuple = PHASES

    def pair(self):
        return self.render(0.0), self.render(1.0)

    def gt(self, t: float) -> np.ndarray:
        return self.render(t)


def pan(w: int, h: int, vx: float, vy: float = 0.0, canvas_name: str = "building.jpg", seed: int = 1) -> Scene:
    margin = int(abs(vx) + abs(vy) + 8) * 2
    canvas = make_canvas(canvas_name, w + 2 * margin + 64, h + 2 * margin + 64, seed)

    def render(tau: float) -> np.ndarray:
        ox, oy = margin + 32 + vx * tau, margin + 32 + vy * tau
        return _sample(canvas, np.array([[1, 0, ox], [0, 1, oy]], np.float64), (w, h))

    return Scene(f"pan {vx:g},{vy:g}px/f", render, f"camera pan, {abs(vx) + abs(vy):g} px/frame at {w}x{h}")


def zoom_rot(w: int, h: int, zoom: float = 1.012, rot_deg: float = 0.35, canvas_name: str = "fruits.jpg", seed: int = 2) -> Scene:
    canvas = make_canvas(canvas_name, int(w * 1.6), int(h * 1.6), seed)
    cx, cy = canvas.shape[1] / 2, canvas.shape[0] / 2

    def render(tau: float) -> np.ndarray:
        s = zoom ** tau
        a = np.deg2rad(rot_deg * tau)
        R = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]]) / s
        c = np.array([cx, cy]) - R @ np.array([w / 2, h / 2])
        return _sample(canvas, np.hstack([R, c[:, None]]), (w, h))

    return Scene(f"zoom x{zoom:g}/f rot {rot_deg:g}deg/f", render, "slow zoom + rotation")


def layers(w: int, h: int, bg_v: float = 4.0, fg_v: float = -22.0, radius: int = 150, seed: int = 3) -> Scene:
    """Textured disc moving over a panning background: motion boundary + occlusion + disocclusion."""
    margin = int(abs(bg_v) * 2 + 32)
    bg = make_canvas("building.jpg", w + 2 * margin + 64, h + 2 * margin + 64, seed)
    sprite = make_canvas("fruits.jpg", 2 * radius + 8, 2 * radius + 8, seed + 10)
    yy, xx = np.mgrid[0:sprite.shape[0], 0:sprite.shape[1]].astype(np.float32)
    c = (sprite.shape[1] - 1) / 2
    dist = np.sqrt((xx - c) ** 2 + (yy - c) ** 2)
    mask = np.clip((radius - dist) / 3.0, 0, 1).astype(np.float32)  # 2-D, 3 px feather

    def render(tau: float) -> np.ndarray:
        base = _sample(bg, np.array([[1, 0, margin + 32 + bg_v * tau], [0, 1, margin + 32]], np.float64), (w, h)).astype(np.float32)
        cx = w * 0.62 + fg_v * tau
        cy = h * 0.5
        x0, y0 = cx - c, cy - c
        # sub-pixel placement: warp the sprite and its mask together
        M = np.array([[1, 0, -x0], [0, 1, -y0]], np.float64)  # dst(x,y) = sprite(x - x0, y - y0)
        s = cv2.warpAffine(sprite, M, (w, h), flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP, borderValue=0).astype(np.float32)
        m = cv2.warpAffine(mask, M, (w, h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderValue=0)[:, :, None]
        return np.clip(base * (1 - m) + s * m + 0.5, 0, 255).astype(np.uint8)

    return Scene(f"layers bg {bg_v:g} / fg {fg_v:g}px/f", render, "disc over panning background (occlusion)")


def scene_cut(w: int, h: int) -> Scene:
    a = make_canvas("building.jpg", w, h, 5)
    b = make_canvas("butterfly.jpg", w, h, 6)

    def render(tau: float) -> np.ndarray:
        return a if tau < 1.0 else b

    return Scene("scene cut", render, "hard cut A -> B: output must be A or B, never a mix", cut=True)


def real_triples(max_triples: int = 40, step: int = 12) -> list[tuple[str, np.ndarray, np.ndarray, np.ndarray]]:
    """(name, frame k-1, frame k+1, real frame k) triples from vtest.avi (real pedestrian footage)."""
    cap = cv2.VideoCapture(str(DATA / "vtest.avi"))
    if not cap.isOpened():
        raise FileNotFoundError("vtest.avi missing - run fetch_data.sh")
    frames = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        frames.append(cv2.cvtColor(f, cv2.COLOR_BGR2RGB))
    out = []
    k = 1
    while k + 1 < len(frames) and len(out) < max_triples:
        out.append((f"vtest[{k}]", frames[k - 1], frames[k + 1], frames[k]))
        k += step
    return out
