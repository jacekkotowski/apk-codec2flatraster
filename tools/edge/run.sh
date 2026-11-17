#!/bin/bash
# PLAN-edge.md task 1.9: phase 1 from a clean checkout. Setup is only requirements.txt and get_models.sh (plus get_clips.sh and, for
# MediaPipe on a bare Linux, the EGL library: apt-get install libegl1 libgles2). Needs gcc, ffmpeg with libx264, libsvtav1.
# Takes a while (the clips are 1.1 GB, MediaPipe runs on 480 frames of each clip). Writes results.md.
set -e
cd "$(dirname "$0")"
pip install -q -r requirements.txt
bash get_models.sh
bash get_clips.sh
python3 clips.py
python3 marks.py
python3 keypic.py
python3 motion.py | tail -1
python3 warp.py | tail -1
python3 patch.py
python3 legible.py 2>/dev/null | tail -1
python3 baseline.py
python3 table.py > /dev/null
echo "results.md written"
