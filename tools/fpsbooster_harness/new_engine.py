"""
Harness port of the new FPS Booster optical-flow engine (app/src/main/assets/fpsbooster/*.frag).

The shader files are loaded straight from the app's assets, so this exercises the exact GLSL that ships.
The orchestration here is mirrored 1:1 by fpsbooster/OpticalFlowEngine.kt. Keep them in sync.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

import glrunner as G
from engines import Engine

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/fpsbooster"
PITCH = 4

PRESETS = {  # work_edge, coarsest_range, subpel, refine_passes handled in shader
    "performance": dict(work_edge=640, range=6, subpel=1),
    "balanced": dict(work_edge=960, range=6, subpel=1),
    "quality": dict(work_edge=1280, range=8, subpel=1),
}


def _src(name: str) -> str:
    return (ASSETS / name).read_text()


class NewEngine(Engine):
    def __init__(self, preset: str = "balanced", lam: float = 0.003, cons=(1.0, 3.0)) -> None:
        self.name = f"new optical-flow [{preset}]"
        self.cfg = PRESETS[preset]
        self.sharp = int(__import__("os").environ.get("FPSB_SHARP", "1"))
        self.lam = lam
        self.cons = cons
        self.gl = G.get_gl()
        gl = self.gl
        self.p_luma = gl.program(_src("luma_work.frag"))
        self.p_down = gl.program(_src("pyramid_down.frag"))
        self.p_search = gl.program(_src("flow_search.frag"))
        self.p_cons = gl.program(_src("flow_consistency.frag"))
        self.p_cut = gl.program(_src("cut_detect.frag"))
        self.p_interp = gl.program(_src("flow_interp.frag"))
        self.size = None
        self.slots: list[dict] = []
        self.reset()

    # ---- allocation ----
    def _level_sizes(self, w: int, h: int) -> list[tuple[int, int]]:
        sizes = [(w, h)]
        while max(sizes[-1]) > 128 and len(sizes) < 5:
            pw, ph = sizes[-1]
            sizes.append((max(8, (pw + 1) // 2), max(8, (ph + 1) // 2)))
        return sizes

    def _alloc(self, w: int, h: int) -> None:
        gl = self.gl
        self._free()
        edge = self.cfg["work_edge"]
        sc = min(1.0, edge / max(w, h))
        self.frameW, self.frameH = w & ~1, h & ~1
        self.workW = max(32, int(w * sc) & ~1)
        self.workH = max(32, int(h * sc) & ~1)
        self.sizes = self._level_sizes(self.workW, self.workH)
        self.grids = [((sw + PITCH - 1) // PITCH, (sh + PITCH - 1) // PITCH) for sw, sh in self.sizes]
        self.slots = []
        for _ in range(3):
            self.slots.append(dict(
                full=gl.texture(self.frameW, self.frameH, G.GL_RGBA8),
                luma=[gl.texture(sw, sh, G.GL_R8, G.GL_LINEAR) for sw, sh in self.sizes]))
        # per direction, per level: ping + pong flow textures
        self.flow = {d: [[gl.texture(gw, gh, G.GL_RGBA16F, G.GL_NEAREST) for _ in range(2)] for gw, gh in self.grids]
                     for d in ("fw", "bw")}
        g0 = self.grids[0]
        self.final = {d: gl.texture(g0[0], g0[1], G.GL_RGBA16F, G.GL_LINEAR) for d in ("fw", "bw")}
        self.cut = gl.texture(1, 1, G.GL_RGBA16F, G.GL_NEAREST)
        self.out = gl.texture(self.frameW, self.frameH, G.GL_RGBA8)
        self.size = (w, h)

    def _free(self) -> None:
        if self.size is None:
            return
        gl = self.gl
        for s in self.slots:
            gl.delete_texture(s["full"])
            for t in s["luma"]:
                gl.delete_texture(t)
        for d in self.flow.values():
            for pair in d:
                for t in pair:
                    gl.delete_texture(t)
        for t in (*self.final.values(), self.cut, self.out):
            gl.delete_texture(t)
        self.slots = []
        self.size = None

    def reset(self) -> None:
        self.cur = self.prev = self.flow_for = None
        self.gpu_passes = 0

    # ---- frame intake ----
    def push(self, frame: np.ndarray) -> None:
        h, w = frame.shape[:2]
        if self.size != (w, h):
            self._alloc(w, h)
            self.reset()
        gl = self.gl
        slot = next(s for s in self.slots if s is not self.cur and s is not self.prev)
        gl.upload_rgba8(slot["full"], frame)
        lw, lh = self.sizes[0]
        gl.bind_target(slot["luma"][0], lw, lh)
        taps = max(1, min(6, int(np.ceil(max(self.frameW / lw, self.frameH / lh) - 0.01))))
        self.p_luma.use().tex(0, "uSrc", slot["full"]).f2("uTexel", 1 / lw, 1 / lh).i1("uTaps", taps)
        gl.draw()
        for i in range(1, len(self.sizes)):
            pw, ph = self.sizes[i - 1]
            sw, sh = self.sizes[i]
            gl.set_filter(slot["luma"][i - 1], G.GL_NEAREST)
            gl.bind_target(slot["luma"][i], sw, sh)
            self.p_down.use().tex(0, "uSrc", slot["luma"][i - 1]).i2("uSrcSize", pw, ph)
            gl.draw()
            gl.set_filter(slot["luma"][i - 1], G.GL_LINEAR)
        self.prev = self.cur
        self.cur = slot

    # ---- flow ----
    def _search(self, ref, tgt, parent, dst, lvl, mode, rng=0, subpel=0, parent_lvl=None) -> None:
        gl = self.gl
        sw, sh = self.sizes[lvl]
        gw, gh = self.grids[lvl]
        pg = self.grids[parent_lvl] if parent_lvl is not None else (gw, gh)
        ps = self.sizes[parent_lvl] if parent_lvl is not None else (sw, sh)
        gl.bind_target(dst, gw, gh)
        p = self.p_search.use().tex(0, "uRef", ref).tex(1, "uTgt", tgt).tex(2, "uParent", parent)
        p.i2("uSize", sw, sh).i2("uGrid", gw, gh).i2("uParentGrid", pg[0], pg[1])
        p.f2("uScale", sw / ps[0], sh / ps[1]).i1("uMode", mode).i1("uRange", rng).i1("uSubpel", subpel)
        p.f1("uLambda", self.lam)
        gl.draw()
        self.gpu_passes += 1

    def _flow_dir(self, d: str, ref_slot: dict, tgt_slot: dict) -> int:
        """Hierarchical search; returns the texture holding the level-0 result."""
        n = len(self.sizes)
        tex = self.flow[d]
        result = None
        for lvl in range(n - 1, -1, -1):
            ref, tgt = ref_slot["luma"][lvl], tgt_slot["luma"][lvl]
            a, b = tex[lvl]
            sub = self.cfg["subpel"] if lvl == 0 else 0
            if lvl == n - 1:
                # parent is unused in mode 2; bind the *other* texture so it is never sampler + target at once
                self._search(ref, tgt, b, a, lvl, 2, rng=self.cfg["range"])
            else:
                self._search(ref, tgt, result, a, lvl, 0, subpel=0, parent_lvl=lvl + 1)
            # one propagation pass, ping-pong a -> b
            self._search(ref, tgt, a, b, lvl, 1, subpel=sub)
            result = b
        return result

    def _estimate(self, A: dict, B: dict) -> None:
        gl = self.gl
        fw0 = self._flow_dir("fw", A, B)
        bw0 = self._flow_dir("bw", B, A)
        gw, gh = self.grids[0]
        for dst, self_t, other_t in (("fw", fw0, bw0), ("bw", bw0, fw0)):
            gl.set_filter(other_t, G.GL_LINEAR)
            gl.bind_target(self.final[dst], gw, gh)
            self.p_cons.use().tex(0, "uSelf", self_t).tex(1, "uOther", other_t).i2("uGrid", gw, gh)
            gl.draw()
            gl.set_filter(other_t, G.GL_NEAREST)
        # cut detector reads the coarsest-level forward field
        n = len(self.sizes)
        cw, ch = self.grids[n - 1]
        gl.bind_target(self.cut, 1, 1)
        self.p_cut.use().tex(0, "uFlow", self.flow["fw"][n - 1][1]).i2("uGrid", cw, ch)
        self.p_cut.f1("uMeanThresh", 0.12).f1("uPoorThresh", 0.10)
        gl.draw()
        self.flow_for = self.cur

    def interpolate(self, t: float) -> np.ndarray:
        gl = self.gl
        A = self.prev if self.prev is not None else self.cur
        B = self.cur
        if self.prev is not None and self.flow_for is not self.cur:
            self._estimate(self.prev, self.cur)
        gw, gh = self.grids[0]
        gl.bind_target(self.out, self.frameW, self.frameH)
        p = self.p_interp.use().tex(0, "uA", A["full"]).tex(1, "uB", B["full"]).tex(2, "uFw", self.final["fw"]) \
            .tex(3, "uBw", self.final["bw"]).tex(4, "uCut", self.cut)
        if self.prev is None:  # nothing to interpolate with: show the only frame
            p.f1("uT", 1.0).i1("uWarp", 0)
        else:
            p.f1("uT", t).i1("uWarp", 1)
        p.f2("uWork", *self.sizes[0]).f2("uFlowUv", self.sizes[0][0] / (gw * PITCH), self.sizes[0][1] / (gh * PITCH))
        p.f3("uGrade", 0.0, 1.0, 1.0).f1("uConsLo", self.cons[0]).f1("uConsHi", self.cons[1]).i1("uSharp", self.sharp)
        gl.draw()
        return gl.read_rgba8(self.out, self.frameW, self.frameH)[:, :, :3]
