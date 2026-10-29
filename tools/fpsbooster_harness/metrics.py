"""Image-quality metrics for interpolation evaluation (PSNR, SSIM, flow EPE)."""
from __future__ import annotations

import cv2
import numpy as np


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    if mse <= 1e-12:
        return 99.0
    return 10.0 * np.log10(255.0 * 255.0 / mse)


def ssim(a: np.ndarray, b: np.ndarray) -> float:
    """Mean SSIM on luma (11x11 Gaussian window, sigma 1.5), standard constants."""
    x = cv2.cvtColor(a, cv2.COLOR_RGB2GRAY).astype(np.float64)
    y = cv2.cvtColor(b, cv2.COLOR_RGB2GRAY).astype(np.float64)
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    k = (11, 11)
    mx, my = cv2.GaussianBlur(x, k, 1.5), cv2.GaussianBlur(y, k, 1.5)
    sxx = cv2.GaussianBlur(x * x, k, 1.5) - mx * mx
    syy = cv2.GaussianBlur(y * y, k, 1.5) - my * my
    sxy = cv2.GaussianBlur(x * y, k, 1.5) - mx * my
    m = ((2 * mx * my + c1) * (2 * sxy + c2)) / ((mx * mx + my * my + c1) * (sxx + syy + c2))
    return float(m.mean())


def epe(flow: np.ndarray, gt: np.ndarray, valid: np.ndarray | None = None) -> float:
    d = np.linalg.norm(flow - gt, axis=2)
    if valid is not None:
        d = d[valid]
    return float(d.mean())


def read_flo(path: str) -> np.ndarray:
    with open(path, "rb") as f:
        magic = np.fromfile(f, np.float32, 1)[0]
        if magic != 202021.25:
            raise ValueError("bad .flo magic")
        w, h = np.fromfile(f, np.int32, 2)
        return np.fromfile(f, np.float32, w * h * 2).reshape(h, w, 2)


def tear(out: np.ndarray, ref: np.ndarray, dil: int = 9, floor: float = 0.01) -> float:
    """Tearing / patchwork score (lower is better, x1000): mean strength of edges in `out` that have no counterpart within
    `dil` px in the real frame `ref`. A cross-fade ghost has soft edges (low score), a torn "peeling paint" patch has hard
    edges where the real picture has none (high score). PSNR cannot tell the two apart: both are equally "wrong"."""
    def grad(img: np.ndarray) -> np.ndarray:
        g = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY).astype(np.float32) / 255.0
        return np.hypot(cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)) / 8.0
    near = cv2.dilate(grad(ref), np.ones((dil, dil), np.uint8))
    return float(np.maximum(0.0, grad(out) - near - floor).mean() * 1000.0)
