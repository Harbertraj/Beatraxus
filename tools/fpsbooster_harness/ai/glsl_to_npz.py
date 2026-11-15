#!/usr/bin/env python3
"""Reads the weights back out of a generated ai_fusion.glsl (inverse of train_fusion.export) so they can be fine-tuned.

    python3 ai/glsl_to_npz.py ../../app/src/main/assets/fpsbooster/ai_fusion.glsl /tmp/fusion_init.npz
"""
import re
import sys

import numpy as np

NF, NK = 22, 6


def main(src: str, dst: str) -> None:
    t = open(src).read()
    def vec(m):
        return [float(v) for v in m]
    def layer(prefix, nin, nout, kin):
        w = np.zeros((nin, nout), np.float32); b = np.zeros(nout, np.float32)
        for j in range(nout):
            m = re.search(rf"float {prefix}{j} = (?:aiAct\()?([-+0-9.e]+) \+ (.*);\n", t)
            b[j] = float(m.group(1))
            cols = re.findall(r"dot\(vec4\(([^)]*)\), [a-z]\d+\)", m.group(2))
            flat = [float(v) for c in cols for v in c.split(",")]
            w[:, j] = flat[:nin]
        return w, b
    h1 = len(re.findall(r"float a\d+ =", t)); h2 = len(re.findall(r"float c\d+ =", t))
    w1, b1 = layer("a", NF, h1, 0); w2, b2 = layer("c", h1, h2, 0); w3, b3 = layer("l", h2, NK, 0)
    np.savez(dst, w1=w1, b1=b1, w2=w2, b2=b2, w3=w3, b3=b3)
    print("saved", dst, w1.shape, w2.shape, w3.shape)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
