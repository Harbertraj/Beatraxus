"""Extracts GLSL raw strings (`private const val NAME = \"\"\"...\"\"\"`) from a Kotlin source file.

The harness runs the legacy engine on the *exact* shader text that ships in MotionBoostRenderer.kt, so
the baseline numbers cannot drift from the app.
"""
from __future__ import annotations

import re
from pathlib import Path

_PATTERN = re.compile(r'const\s+val\s+([A-Z0-9_]+)\s*=\s*"""(.*?)"""', re.S)


def extract(path: str | Path) -> dict[str, str]:
    text = Path(path).read_text(encoding="utf-8")
    out = {name: body for name, body in _PATTERN.findall(text)}
    for name, body in out.items():
        if "$" in body:
            raise ValueError(f"{name}: '$' would be a Kotlin string template")
    return out
