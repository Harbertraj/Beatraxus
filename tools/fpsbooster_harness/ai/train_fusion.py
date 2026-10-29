#!/usr/bin/env python3
"""
Trains the AI fusion network (22 inputs -> two hidden layers -> softmax over 6 candidates) on the records written by
gen_data.py and exports it as GLSL (app/src/main/assets/fpsbooster/ai_fusion.glsl).

Loss: Charbonnier distance between the weighted blend of the six candidate colours and the true colour.
Needs: numpy, plus (optional) jax jaxlib optax. Without JAX the identical network / loss / optimiser run on a
plain-numpy backend (hand-written backward pass, Adam, cosine schedule): slower per step but the network is ~600
weights, so a CPU-only box trains it in minutes. `--backend auto` (default) picks JAX when it is installed.

    python3 ai/train_fusion.py --data data/ai --out /tmp/fusion.npz --epochs 40
    python3 ai/train_fusion.py --export /tmp/fusion.npz --glsl ../../app/src/main/assets/fpsbooster/ai_fusion.glsl
"""
from __future__ import annotations

import argparse
import glob
import sys
from pathlib import Path

import numpy as np

NF, NK = 22, 6


def load(data_dir: str, max_shards: int | None = None):
    files = sorted(glob.glob(str(Path(data_dir) / "shard_*.npz")))
    if max_shards:
        files = files[:max_shards]
    xs, cs, gs = [], [], []
    for f in files:
        d = np.load(f)
        xs.append(d["x"]); cs.append(d["cand"]); gs.append(d["gt"])
    return files, np.concatenate(xs), np.concatenate(cs), np.concatenate(gs)


def init_params(rng: np.random.Generator, h1: int, h2: int):
    def lin(i, o, scale=1.0):
        return (rng.standard_normal((i, o)) * np.sqrt(2.0 / i) * scale).astype(np.float32), np.zeros(o, np.float32)
    w1, b1 = lin(NF, h1)
    w2, b2 = lin(h1, h2)
    w3, b3 = lin(h2, NK, 0.1)
    b3[0] = 1.0          # start close to "show the nearest real frame"
    return dict(w1=w1, b1=b1, w2=w2, b2=b2, w3=w3, b3=b3)


def _leaky(v):
    return np.where(v > 0, v, 0.05 * v)


def _forward_np(p, xb):
    z1 = xb @ p["w1"] + p["b1"]; h1 = _leaky(z1)
    z2 = h1 @ p["w2"] + p["b2"]; h2 = _leaky(z2)
    lg = h2 @ p["w3"] + p["b3"]
    e = np.exp(lg - lg.max(axis=1, keepdims=True))
    return z1, h1, z2, h2, e / e.sum(axis=1, keepdims=True)


def train_numpy(args) -> int:
    """Same model / loss as the JAX path, trained with a hand-written backward pass and Adam (no JAX needed)."""
    files, x, c, g = load(args.data, args.max_shards)
    n = len(x)
    print(f"{len(files)} shards, {n} samples (numpy backend)", flush=True)
    rng = np.random.default_rng(0)
    perm = rng.permutation(n)
    n_val = min(200_000, n // 10)
    vi, ti = np.sort(perm[:n_val]), perm[n_val:]
    xv, cv, gv = (a[vi].astype(np.float32) for a in (x, c, g))
    p = init_params(rng, args.h1, args.h2)
    m = {k: np.zeros_like(v) for k, v in p.items()}
    v2 = {k: np.zeros_like(v) for k, v in p.items()}
    steps_per_epoch = max(1, len(ti) // args.batch)
    total = args.epochs * steps_per_epoch
    step = 0
    b1, b2, eps = 0.9, 0.999, 1e-8

    def lr_at(i):   # cosine decay to 2% (optax.cosine_decay_schedule(lr, steps, 0.02))
        return args.lr * (0.02 + 0.98 * 0.5 * (1.0 + np.cos(np.pi * min(i, total) / total)))

    best = (-1.0, None)
    for ep in range(args.epochs):
        order = rng.permutation(ti)
        tot = 0.0
        for i in range(steps_per_epoch):
            b = np.sort(order[i * args.batch:(i + 1) * args.batch])
            xb, cb, gb = x[b].astype(np.float32), c[b].astype(np.float32), g[b].astype(np.float32)
            z1, h1, z2, h2, w = _forward_np(p, xb)
            out = np.einsum("nk,nkc->nc", w, cb)
            d = out - gb
            r = np.sqrt(d * d + 1e-6)
            tot += float(r.mean())
            dout = (d / r) / d.size                              # dLoss / dout
            dw = np.einsum("nc,nkc->nk", dout, cb)               # dLoss / dweights
            dl = w * (dw - (w * dw).sum(axis=1, keepdims=True))  # softmax backward
            gr = {"w3": h2.T @ dl, "b3": dl.sum(0)}
            dh2 = (dl @ p["w3"].T) * np.where(z2 > 0, 1.0, 0.05)
            gr["w2"], gr["b2"] = h1.T @ dh2, dh2.sum(0)
            dh1 = (dh2 @ p["w2"].T) * np.where(z1 > 0, 1.0, 0.05)
            gr["w1"], gr["b1"] = xb.T @ dh1, dh1.sum(0)
            step += 1
            lr = lr_at(step)
            for k in p:
                m[k] = b1 * m[k] + (1 - b1) * gr[k]
                v2[k] = b2 * v2[k] + (1 - b2) * gr[k] ** 2
                p[k] = p[k] - lr * (m[k] / (1 - b1 ** step)) / (np.sqrt(v2[k] / (1 - b2 ** step)) + eps)
        *_, wv = _forward_np(p, xv)
        ov = np.einsum("nk,nkc->nc", wv, cv)
        vp = 10 * np.log10(1.0 / np.mean((ov - gv) ** 2))
        vb = 10 * np.log10(1.0 / np.mean((cv[:, 0] - gv) ** 2))
        if vp > best[0]:
            best = (float(vp), {k: a.copy() for k, a in p.items()})
        print(f"epoch {ep + 1}/{args.epochs} loss {tot / steps_per_epoch:.5f}  val PSNR on contested+random pixels {vp:.2f} dB (nearest frame {vb:.2f})", flush=True)
    np.savez(args.out, **best[1])
    print(f"saved {args.out} (best epoch val PSNR {best[0]:.2f} dB)")
    return 0


def train(args) -> int:
    if args.backend == "numpy":
        return train_numpy(args)
    try:
        import jax
        import jax.numpy as jnp
        import optax
    except ImportError:
        if args.backend == "jax":
            raise
        print("JAX / optax not installed: using the numpy backend", flush=True)
        return train_numpy(args)

    files, x, c, g = load(args.data, args.max_shards)
    n = len(x)
    print(f"{len(files)} shards, {n} samples", flush=True)
    rng = np.random.default_rng(0)
    perm = rng.permutation(n)
    n_val = min(200_000, n // 10)
    vi, ti = perm[:n_val], perm[n_val:]
    xv, cv, gv = (jnp.asarray(a[vi].astype(np.float32)) for a in (x, c, g))
    params = {k: jnp.asarray(v) for k, v in init_params(rng, args.h1, args.h2).items()}

    def net(p, xb):
        h = xb @ p["w1"] + p["b1"]
        h = jnp.where(h > 0, h, 0.05 * h)
        h = h @ p["w2"] + p["b2"]
        h = jnp.where(h > 0, h, 0.05 * h)
        return h @ p["w3"] + p["b3"]

    def blend(p, xb, cb):
        w = jax.nn.softmax(net(p, xb), axis=-1)
        return jnp.einsum("nk,nkc->nc", w, cb), w

    def loss_fn(p, xb, cb, gb):
        out, w = blend(p, xb, cb)
        d = out - gb
        return jnp.mean(jnp.sqrt(d * d + 1e-6))

    steps_per_epoch = max(1, len(ti) // args.batch)
    sched = optax.cosine_decay_schedule(args.lr, args.epochs * steps_per_epoch, 0.02)
    opt = optax.adam(sched)
    state = opt.init(params)

    @jax.jit
    def step(p, s, xb, cb, gb):
        l, gr = jax.value_and_grad(loss_fn)(p, xb, cb, gb)
        up, s = opt.update(gr, s, p)
        return optax.apply_updates(p, up), s, l

    @jax.jit
    def val_psnr(p):
        out, _ = blend(p, xv, cv)
        mse = jnp.mean((out - gv) ** 2)
        base = jnp.mean((cv[:, 0] - gv) ** 2)
        return 10 * jnp.log10(1.0 / mse), 10 * jnp.log10(1.0 / base)

    for ep in range(args.epochs):
        order = rng.permutation(ti)
        tot = 0.0
        for i in range(steps_per_epoch):
            b = np.sort(order[i * args.batch:(i + 1) * args.batch])
            params, state, l = step(params, state, jnp.asarray(x[b].astype(np.float32)),
                                    jnp.asarray(c[b].astype(np.float32)), jnp.asarray(g[b].astype(np.float32)))
            tot += float(l)
        vp, vb = val_psnr(params)
        print(f"epoch {ep + 1}/{args.epochs} loss {tot / steps_per_epoch:.5f}  val PSNR on contested+random pixels {float(vp):.2f} dB (nearest frame {float(vb):.2f})", flush=True)
    np.savez(args.out, **{k: np.asarray(v) for k, v in params.items()})
    print("saved", args.out)
    return 0


# ---------------------------------------------------------------------------------------------------------------
def fmt(v: float) -> str:
    s = f"{float(v):.6g}"
    if "e" not in s and "." not in s:
        s += ".0"
    return s


def vec4s(vals: np.ndarray) -> list[str]:
    pad = (-len(vals)) % 4
    v = list(vals) + [0.0] * pad
    return [f"vec4({fmt(v[i])}, {fmt(v[i + 1])}, {fmt(v[i + 2])}, {fmt(v[i + 3])})" for i in range(0, len(v), 4)]


def export(npz: str, out: str) -> None:
    p = {k: v.astype(np.float64) for k, v in np.load(npz).items()}
    h1, h2 = p["w1"].shape[1], p["w2"].shape[1]
    lines = [
        "// GENERATED by tools/fpsbooster_harness/ai/train_fusion.py - do not edit by hand.",
        f"// Network {NF} -> {h1} -> {h2} -> {NK} (leaky ReLU, softmax). Inserted into flow_interp.frag (FUSE_PASS).",
        f"const int AI_NF = {NF};",
        f"const int AI_NK = {NK};",
        "float aiAct(float v) { return v > 0.0 ? v : 0.05 * v; }",
        "void aiNet(in float x[AI_NF], out float w[AI_NK]) {",
    ]
    xin = ["vec4(" + ", ".join(f"x[{j}]" if j < NF else "0.0" for j in range(i, i + 4)) + ")" for i in range(0, NF, 4)]
    for i, e in enumerate(xin):
        lines.append(f"  vec4 i{i} = {e};")
    nin = len(xin)
    for j in range(h1):
        col = vec4s(p["w1"][:, j])
        terms = " + ".join(f"dot({c}, i{k})" for k, c in enumerate(col))
        lines.append(f"  float a{j} = aiAct({fmt(p['b1'][j])} + {terms});")
    a1 = [f"a{j}" for j in range(h1)] + ["0.0"] * ((-h1) % 4)
    for i in range(0, len(a1), 4):
        lines.append(f"  vec4 h{i // 4} = vec4({', '.join(a1[i:i + 4])});")
    for j in range(h2):
        col = vec4s(p["w2"][:, j])
        terms = " + ".join(f"dot({c}, h{k})" for k, c in enumerate(col))
        lines.append(f"  float c{j} = aiAct({fmt(p['b2'][j])} + {terms});")
    c1 = [f"c{j}" for j in range(h2)] + ["0.0"] * ((-h2) % 4)
    for i in range(0, len(c1), 4):
        lines.append(f"  vec4 g{i // 4} = vec4({', '.join(c1[i:i + 4])});")
    for k in range(NK):
        col = vec4s(p["w3"][:, k])
        terms = " + ".join(f"dot({c}, g{m})" for m, c in enumerate(col))
        lines.append(f"  float l{k} = {fmt(p['b3'][k])} + {terms};")
    lines.append("  float m = max(max(max(l0, l1), max(l2, l3)), max(l4, l5));")
    for k in range(NK):
        lines.append(f"  float e{k} = exp(l{k} - m);")
    lines.append("  float inv = 1.0 / (e0 + e1 + e2 + e3 + e4 + e5);")
    for k in range(NK):
        lines.append(f"  w[{k}] = e{k} * inv;")
    lines.append("}")
    Path(out).write_text("\n".join(lines) + "\n")
    print("wrote", out, f"({len(lines)} lines, {sum(v.size for v in p.values())} parameters)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/ai")
    ap.add_argument("--out", default="/tmp/fusion.npz")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=16384)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--h1", type=int, default=16)
    ap.add_argument("--h2", type=int, default=10)
    ap.add_argument("--max-shards", type=int, default=None)
    ap.add_argument("--backend", choices=["auto", "jax", "numpy"], default="auto")
    ap.add_argument("--export", default=None, help="npz to export instead of training")
    ap.add_argument("--glsl", default=None)
    args = ap.parse_args()
    if args.export:
        export(args.export, args.glsl)
        return 0
    return train(args)


if __name__ == "__main__":
    sys.exit(main())
