"""
Interpolation engines driven through the real shaders.

`LegacyEngine`  : faithful port of the orchestration in motionboost/MotionBoostRenderer.kt (slots, luma /
                  half-luma pyramids, 3-pass motion estimation, interpolation pass). The shader text itself
                  is read from the Kotlin file, so it is byte-identical to what ships.
`RepeatEngine`  : nearest-frame (what you get with no interpolation at all).
`BlendEngine`   : plain linear cross-fade (what a naive "frame blend" does).

Engines share a tiny interface:
    reset(); push(frame_rgb_uint8); interpolate(t) -> rgb_uint8 (between the last two pushed frames).
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

import glrunner as G
from kotlin_shaders import extract

REPO = Path(__file__).resolve().parents[2]
LEGACY_KT = REPO / "app/src/main/java/com/beatraxus/app/motionboost/MotionBoostRenderer.kt"


class Engine:
    name = "engine"

    def reset(self) -> None: ...
    def push(self, frame: np.ndarray) -> None: raise NotImplementedError
    def interpolate(self, t: float) -> np.ndarray: raise NotImplementedError
    def stats(self) -> dict: return {}


class RepeatEngine(Engine):
    name = "repeat (nearest)"

    def reset(self): self.prev = self.cur = None

    def push(self, frame):
        self.prev, self.cur = self.cur, frame

    def interpolate(self, t):
        return self.prev if (self.prev is not None and t < 0.5) else self.cur


class BlendEngine(RepeatEngine):
    name = "blend (cross-fade)"

    def interpolate(self, t):
        if self.prev is None:
            return self.cur
        return np.clip(self.prev.astype(np.float32) * (1 - t) + self.cur.astype(np.float32) * t + 0.5, 0, 255).astype(np.uint8)


class LegacyEngine(Engine):
    name = "legacy (motionboost)"
    BLOCK = 4
    MAX_WORK_EDGE = 1920
    MAX_FRAME_EDGE = 3840

    def __init__(self) -> None:
        self.gl = G.get_gl()
        sh = extract(LEGACY_KT)
        self.sh = sh
        gl = self.gl
        self.p_luma = gl.program(sh["LUMA_FRAGMENT"])
        self.p_half = gl.program(sh["HALF_FRAGMENT"])
        self.p_me = gl.program(sh["MOTION_FRAGMENT"])
        self.p_refine = gl.program(sh["REFINE_FRAGMENT"])
        self.p_smooth = gl.program(sh["SMOOTH_FRAGMENT"])
        self.p_interp = gl.program(sh["INTERP_FRAGMENT"])
        self.size = None
        self.slots: list[dict] = []
        self.reset()

    # ---- sizes (mirror applySourceSize) ----
    def _alloc(self, w: int, h: int) -> None:
        gl = self.gl
        self._free()
        self.srcW, self.srcH = w, h
        fs = min(1.0, self.MAX_FRAME_EDGE / max(w, h))
        self.frameW = max(16, int(w * fs) & ~1)
        self.frameH = max(16, int(h * fs) & ~1)
        sc = min(1.0, self.MAX_WORK_EDGE / max(w, h))
        self.workW = max(16, int(w * sc) & ~1)
        self.workH = max(16, int(h * sc) & ~1)
        B = self.BLOCK
        self.lumaW = max(B, ((self.workW // 4 + B - 1) // B) * B)
        self.lumaH = max(B, ((self.workH // 4 + B - 1) // B) * B)
        self.blocksW, self.blocksH = self.lumaW // B, self.lumaH // B
        self.halfW, self.halfH = self.lumaW * 2, self.lumaH * 2
        self.slots = [dict(
            full=gl.texture(self.frameW, self.frameH, G.GL_RGBA8),
            luma=gl.texture(self.lumaW, self.lumaH, G.GL_R8),
            half=gl.texture(self.halfW, self.halfH, G.GL_R8)) for _ in range(3)]
        self.mv = gl.texture(self.blocksW, self.blocksH, G.GL_RGBA8, G.GL_LINEAR)
        self.mv_scratch = gl.texture(self.blocksW, self.blocksH, G.GL_RGBA8, G.GL_NEAREST)
        self.out = gl.texture(self.frameW, self.frameH, G.GL_RGBA8)
        self.size = (w, h)

    def _free(self) -> None:
        for s in self.slots:
            for k in ("full", "luma", "half"):
                self.gl.delete_texture(s[k])
        if self.size is not None:
            for t in (self.mv, self.mv_scratch, self.out):
                self.gl.delete_texture(t)
        self.slots = []
        self.size = None

    def reset(self) -> None:
        self.cur = self.prev = self.mv_for = None
        self._n = 0

    def push(self, frame: np.ndarray) -> None:
        h, w = frame.shape[:2]
        if self.size != (w, h):
            self._alloc(w, h)
            self.reset()
        gl = self.gl
        slot = next(s for s in self.slots if s is not self.cur and s is not self.prev)
        gl.upload_rgba8(slot["full"], frame)
        gl.bind_target(slot["luma"], self.lumaW, self.lumaH)
        self.p_luma.use().tex(0, "uSrc", slot["full"]).f2("uTexel", 1 / self.workW, 1 / self.workH); gl.draw()
        gl.bind_target(slot["half"], self.halfW, self.halfH)
        self.p_half.use().tex(0, "uSrc", slot["full"]).f2("uTexel", 1 / self.workW, 1 / self.workH); gl.draw()
        self.prev = self.cur
        self.cur = slot
        self._n += 1

    def _estimate(self, a: dict, b: dict) -> None:
        gl = self.gl
        gl.bind_target(self.mv_scratch, self.blocksW, self.blocksH)
        self.p_me.use().tex(0, "uA", a["luma"]).tex(1, "uB", b["luma"]).i2("uLumaSize", self.lumaW, self.lumaH); gl.draw()
        gl.bind_target(self.mv, self.blocksW, self.blocksH)
        self.p_refine.use().tex(0, "uA", a["half"]).tex(1, "uB", b["half"]).tex(2, "uCoarse", self.mv_scratch) \
            .i2("uHalfSize", self.halfW, self.halfH); gl.draw()
        gl.bind_target(self.mv_scratch, self.blocksW, self.blocksH)
        self.p_smooth.use().tex(0, "uMV", self.mv).i2("uBlocks", self.blocksW, self.blocksH); gl.draw()
        self.mv, self.mv_scratch = self.mv_scratch, self.mv
        gl.set_filter(self.mv, G.GL_LINEAR)
        gl.set_filter(self.mv_scratch, G.GL_NEAREST)
        self.mv_for = b

    def interpolate(self, t: float) -> np.ndarray:
        gl = self.gl
        if self.prev is None:
            gl.bind_target(self.out, self.frameW, self.frameH)
        if self.prev is not None and self.mv_for is not self.cur:
            self._estimate(self.prev, self.cur)
        a = self.prev if self.prev is not None else self.cur
        b = self.cur
        warp = self.prev is not None
        gl.bind_target(self.out, self.frameW, self.frameH)
        self.p_interp.use().tex(0, "uA", a["full"]).tex(1, "uB", b["full"]).tex(2, "uMV", self.mv) \
            .f1("uAlpha", t).i1("uWarp", 1 if warp else 0).f2("uInvHalf", 1 / self.halfW, 1 / self.halfH) \
            .f3("uGrade", 0.0, 1.0, 1.0); gl.draw()
        return gl.read_rgba8(self.out, self.frameW, self.frameH)[:, :, :3]

    def stats(self) -> dict:
        return dict(work=(self.workW, self.workH), blocks=(self.blocksW, self.blocksH))
