#!/usr/bin/env bash
# Extra media for TRAINING the AI fusion network (nothing here is committed; see ../.gitignore).
# Held out of training on purpose (used only to evaluate): building.jpg, fruits.jpg, butterfly.jpg, vtest.avi.
# Licences stay with the upstream projects:
#   OpenCV sample data (opencv/opencv, Apache-2.0 repo)       - still images, Megamind.avi
#   OpenCV test data   (opencv/opencv_extra)                   - 768x576.avi, big_buck_bunny (Blender, CC-BY 3.0), david.webm
set -euo pipefail
cd "$(dirname "$0")/.."
bash ./fetch_data.sh
cd data
OCV=https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data
EXTRA=https://raw.githubusercontent.com/opencv/opencv_extra/4.x/testdata
for f in lena.jpg baboon.jpg messi5.jpg starry_night.jpg graf1.png aloeL.jpg board.jpg stuff.jpg Megamind.avi; do
  [ -s "$f" ] || curl -fsSL -o "$f" "$OCV/$f"
done
[ -s 768x576.avi ] || curl -fsSL -o 768x576.avi "$EXTRA/cv/video/768x576.avi"
[ -s big_buck_bunny.mp4 ] || curl -fsSL -o big_buck_bunny.mp4 "$EXTRA/highgui/video/big_buck_bunny.mp4"
[ -s david.webm ] || curl -fsSL -o david.webm "$EXTRA/cv/tracking/david/data/david.webm"
echo ok
