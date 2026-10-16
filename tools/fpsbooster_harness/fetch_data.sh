#!/usr/bin/env bash
# Downloads third-party test media used by the FPS Booster shader harness.
# Nothing here is committed to the repo (see .gitignore); each file keeps its upstream licence:
#   - OpenCV sample images / vtest.avi : https://github.com/opencv/opencv (Apache-2.0 for the repo; see upstream for media)
#   - Middlebury RubberWhale pair + GT : https://vision.middlebury.edu/flow/ (research use) via opencv_extra
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p data && cd data
OCV=https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data
EXTRA=https://raw.githubusercontent.com/opencv/opencv_extra/4.x/testdata/cv/optflow
for f in fruits.jpg building.jpg home.jpg butterfly.jpg vtest.avi; do
  [ -s "$f" ] || curl -fsSL -o "$f" "$OCV/$f"
done
for f in RubberWhale1.png RubberWhale2.png RubberWhale.flo; do
  [ -s "$f" ] || curl -fsSL -o "$f" "$EXTRA/$f"
done
echo "ok"
