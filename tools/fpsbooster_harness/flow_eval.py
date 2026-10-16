#!/usr/bin/env python3
"""Flow accuracy of the new engine on the Middlebury RubberWhale pair (real image, ground-truth flow)."""
import cv2, numpy as np
import engines as E, metrics as M, scenarios as S, new_engine as N, glrunner as G

a = cv2.cvtColor(cv2.imread(str(S.DATA/"RubberWhale1.png")), cv2.COLOR_BGR2RGB)
b = cv2.cvtColor(cv2.imread(str(S.DATA/"RubberWhale2.png")), cv2.COLOR_BGR2RGB)
gt = M.read_flo(str(S.DATA/"RubberWhale.flo"))
gt[np.abs(gt) > 1e8] = 0
h, w = a.shape[:2]
res = {}
for preset in ("performance", "balanced", "quality"):
    eng = N.NewEngine(preset); eng.reset(); eng.push(a); eng.push(b); eng._estimate(eng.prev, eng.cur)
    gl = eng.gl; gw, gh = eng.grids[0]
    f = gl.read_rgba32f(eng.final["fw"], gw, gh)           # grid-resolution field, work px
    ww, wh = eng.sizes[0]
    up = cv2.resize(f[:, :, :2], (gw * 4, gh * 4), interpolation=cv2.INTER_LINEAR)[:wh, :ww]
    flow = cv2.resize(up, (w, h), interpolation=cv2.INTER_LINEAR) * np.array([w / ww, h / wh], np.float32)
    res[preset] = M.epe(flow, gt)
g1, g2 = cv2.cvtColor(a, cv2.COLOR_RGB2GRAY), cv2.cvtColor(b, cv2.COLOR_RGB2GRAY)
far = cv2.calcOpticalFlowFarneback(g1, g2, None, 0.5, 4, 15, 3, 5, 1.2, 0)
res["opencv Farneback (CPU reference)"] = M.epe(far, gt)
res["zero flow"] = M.epe(np.zeros_like(gt), gt)
print(f"RubberWhale {w}x{h}, mean end-point error in px (lower is better); GT mean magnitude {np.linalg.norm(gt,axis=2).mean():.2f}px")
for k, v in res.items(): print(f"  {k:36s} {v:6.3f}")
