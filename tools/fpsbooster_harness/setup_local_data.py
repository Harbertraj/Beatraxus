#!/usr/bin/env python3
"""
Offline fallback for fetch_data.sh / ai/fetch_train_media.sh.

Some machines (CI, sandboxes) cannot reach raw.githubusercontent.com. This script fills `data/` with the still images the
harness expects, using the natural photographs that ship inside scikit-image (BSD-3), under the SAME file names, so
run_eval.py / check_regression.py / ai/gen_data.py run unchanged. It never overwrites a file that already exists, so the
real OpenCV media always wins when it was downloaded.

Held-out images (building / fruits / butterfly, used only for evaluation) map to photos that are NOT used for training.
Videos and the Middlebury flow pair cannot be substituted; `make_sample_clip.py` renders a stand-in clip instead.

    python3 setup_local_data.py
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

import cv2

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"

# harness name -> scikit-image file
MAP = {
    # held out (evaluation only)
    "building.jpg": "motorcycle_left.png",
    "fruits.jpg": "coffee.png",
    "butterfly.jpg": "chelsea.png",
    # training / scene canvases
    "home.jpg": "rocket.jpg",
    "lena.jpg": "astronaut.png",
    "baboon.jpg": "retina.jpg",
    "messi5.jpg": "horse.png",
    "starry_night.jpg": "hubble_deep_field.jpg",
    "graf1.png": "brick.png",
    "aloeL.jpg": "grass.png",
    "board.jpg": "gravel.png",
    "stuff.jpg": "camera.png",
}


def main() -> int:
    try:
        import skimage
    except ImportError:
        print("scikit-image is not installed (pip install scikit-image); nothing to do", file=sys.stderr)
        return 1
    src_dir = Path(os.path.dirname(skimage.__file__)) / "data"
    DATA.mkdir(exist_ok=True)
    made = 0
    for dst_name, src_name in MAP.items():
        dst = DATA / dst_name
        if dst.exists() and dst.stat().st_size > 0:
            continue
        img = cv2.imread(str(src_dir / src_name), cv2.IMREAD_COLOR)
        if img is None:
            print(f"missing {src_name}", file=sys.stderr)
            continue
        if dst.suffix == ".jpg":
            cv2.imwrite(str(dst), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        else:
            shutil.copyfile(src_dir / src_name, dst) if src_name.endswith(".png") else cv2.imwrite(str(dst), img)
        made += 1
    print(f"wrote {made} stand-in images to {DATA}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
