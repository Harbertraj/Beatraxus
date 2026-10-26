"""
Harness port of the new FPS Booster optical-flow engine (app/src/main/assets/fpsbooster/*.frag).

The shader files are loaded straight from the app's assets, so this exercises the exact GLSL that ships.
The orchestration here is mirrored 1:1 by fpsbooster/OpticalFlowRenderer.kt (and the pyramid rule by
fpsbooster/FlowPyramid.kt). Keep them in sync.
"""
from __future__ import annotations

import math
import os
from pathlib import Path

import numpy as np

import glrunner as G
from engines import Engine

ASSETS = Path(os.environ.get("FPSB_ASSETS") or Path(__file__).resolve().parents[2] / "app/src/main/assets/fpsbooster")
PITCH = 4
COARSEST_EDGE = 40   # keep in sync with FlowPyramid.COARSEST_EDGE: the coarsest level is the one closest to this
MAX_LEVELS = 6       # ... and FlowPyramid.MAX_LEVELS
GLOBAL_SAMPLES = (12, 8)   # keep in sync with OpticalFlowRenderer.GLOBAL_SAMPLES_X / _Y
GLOBAL_MAX_COST = 0.08     # ... GLOBAL_MAX_COST

PRESETS = {  # work_edge, coarsest search range, sub-pixel refinement on the finest level
    "performance": dict(work_edge=640, range=10, subpel=0),
    "balanced": dict(work_edge=960, range=10, subpel=1),
    "quality": dict(work_edge=1280, range=10, subpel=1),
}


def level_sizes(w: int, h: int, coarsest: float = COARSEST_EDGE, max_levels: int = MAX_LEVELS) -> list[tuple[int, int]]:
    """Pyramid sizes: halve while that brings the longest edge closer to `coarsest` (in the log sense)."""
    stop = coarsest * math.sqrt(2.0)
    sizes = [(w, h)]
    while max(sizes[-1]) > stop and len(sizes) < max_levels:
        pw, ph = sizes[-1]
        sizes.append((max(8, (pw + 1) // 2), max(8, (ph + 1) // 2)))
    return sizes


def global_samples(gw: int, gh: int) -> tuple[int, int]:
    return max(1, min(GLOBAL_SAMPLES[0], gw)), max(1, min(GLOBAL_SAMPLES[1], gh))


def _src(name: str) -> str:
    return (ASSETS / name).read_text()


class NewEngine(Engine):
    """`over` overrides preset / pipeline settings (used to reproduce the pre-fix behaviour as a baseline):
    stop (pyramid stop edge), range, subpel, work_edge, use_global (dominant-motion candidate)."""

    def __init__(self, preset: str = "balanced", lam: float = 0.003, cons=(1.0, 3.0), **over) -> None:
        self.name = f"new optical-flow [{preset}]"
        self.cfg = {**PRESETS[preset], "stop": COARSEST_EDGE * math.sqrt(2.0), "use_global": True, **over}
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
        gm = ASSETS / "global_motion.frag"
        self.p_glob = gl.program(gm.read_text()) if gm.exists() else None
        self.size = None
        self.slots: list[dict] = []
        self.reset()

    # ---- allocation ----
    def _level_sizes(self, w: int, h: int) -> list[tuple[int, int]]:
        sizes = [(w, h)]
        while max(sizes[-1]) > self.cfg["stop"] and len(sizes) < MAX_LEVELS:
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
        self.gcoarse = {d: gl.texture(1, 1, G.GL_RGBA16F, G.GL_NEAREST) for d in ("fw", "bw")}   # dominant motion, coarsest field
        self.gfinal = gl.texture(1, 1, G.GL_RGBA16F, G.GL_NEAREST)                                # dominant motion, final fw field
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
        for t in (*self.final.values(), *self.gcoarse.values(), self.cut, self.gfinal, self.out):
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
        self.p_luma.use().tex(0, "uSrc", slot["full"]).f2("uTexel", 1 / lw, 1 / lh)
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
    def _search(self, ref, tgt, parent, dst, lvl, mode, rng=0, subpel=0, parent_lvl=None,
                gtex=None, gscale=0.0, full_cost=0) -> None:
        gl = self.gl
        sw, sh = self.sizes[lvl]
        gw, gh = self.grids[lvl]
        pg = self.grids[parent_lvl] if parent_lvl is not None else (gw, gh)
        ps = self.sizes[parent_lvl] if parent_lvl is not None else (sw, sh)
        gl.bind_target(dst, gw, gh)
        p = self.p_search.use().tex(0, "uRef", ref).tex(1, "uTgt", tgt).tex(2, "uParent", parent)
        p.tex(3, "uGlobal", gtex if gtex is not None else self.gfinal)
        p.i2("uSize", sw, sh).i2("uGrid", gw, gh).i2("uParentGrid", pg[0], pg[1])
        p.f2("uScale", sw / ps[0], sh / ps[1]).i1("uMode", mode).i1("uRange", rng).i1("uSubpel", subpel)
        p.f1("uGlobalScale", gscale).i1("uFullCost", full_cost)
        p.f1("uLambda", self.lam)
        gl.draw()
        self.gpu_passes += 1

    def _global_motion(self, flow_tex: int, grid: tuple[int, int], dst: int) -> None:
        gl = self.gl
        sx, sy = global_samples(*grid)
        gl.bind_target(dst, 1, 1)
        self.p_glob.use().tex(0, "uFlow", flow_tex).i2("uGrid", grid[0], grid[1]).i2("uSamples", sx, sy)
        self.p_glob.f1("uMaxCost", GLOBAL_MAX_COST)
        gl.draw()
        self.gpu_passes += 1

    def _flow_dir(self, d: str, ref_slot: dict, tgt_slot: dict) -> int:
        """Hierarchical search; returns the texture holding the level-0 result."""
        n = len(self.sizes)
        tex = self.flow[d]
        result = None
        gtex = None
        use_global = self.cfg["use_global"] and self.p_glob is not None and n > 1
        for lvl in range(n - 1, -1, -1):
            ref, tgt = ref_slot["luma"][lvl], tgt_slot["luma"][lvl]
            a, b = tex[lvl]
            sub = self.cfg["subpel"] if lvl == 0 else 0
            exact = 1 if (lvl == 0 or lvl == n - 1) else 0      # only these levels' costs are read later
            gscale = (self.sizes[lvl][0] / self.sizes[n - 1][0]) if (use_global and lvl < n - 1) else 0.0
            if lvl == n - 1:
                # parent is unused in mode 2; bind the *other* texture so it is never sampler + target at once
                self._search(ref, tgt, b, a, lvl, 2, rng=self.cfg["range"])
            else:
                self._search(ref, tgt, result, a, lvl, 0, subpel=0, parent_lvl=lvl + 1, gtex=gtex, gscale=gscale)
            # one propagation pass, ping-pong a -> b
            self._search(ref, tgt, a, b, lvl, 1, subpel=sub, gtex=gtex, gscale=gscale, full_cost=exact)
            result = b
            if lvl == n - 1 and use_global:
                gtex = self.gcoarse[d]
                self._global_motion(b, self.grids[lvl], gtex)
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
        # dominant motion of the final forward field (fill-in warp of the interpolation shader)
        if self.p_glob is not None:
            self._global_motion(self.final["fw"], (gw, gh), self.gfinal)
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
            .tex(3, "uBw", self.final["bw"]).tex(4, "uCut", self.cut).tex(5, "uGlobal", self.gfinal)
        if self.prev is None:  # nothing to interpolate with: show the only frame
            p.f1("uT", 1.0).i1("uWarp", 0)
        else:
            p.f1("uT", t).i1("uWarp", 1)
        p.f2("uWork", *self.sizes[0]).f2("uFlowUv", self.sizes[0][0] / (gw * PITCH), self.sizes[0][1] / (gh * PITCH))
        p.f3("uGrade", 0.0, 1.0, 1.0).f1("uConsLo", self.cons[0]).f1("uConsHi", self.cons[1])
        gl.draw()
        return gl.read_rgba8(self.out, self.frameW, self.frameH)[:, :, :3]
