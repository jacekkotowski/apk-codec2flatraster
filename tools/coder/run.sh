#!/bin/bash
# Stage 6a of ROADMAP.md (in apk-codec2-recorder): the whole picture coder measurement, on a computer. Nothing here is part of the app.
# Needs gcc, a JDK, python3 with numpy and opencv-python-headless, ffmpeg with libx264, libx265, libsvtav1 and libaom.
# Writes the clips to out/ (not kept in the repository) and the tables to results.md. Takes about two minutes.
set -e
cd "$(dirname "$0")"
python3 flatcoder.py   # the prototype's own test: made-up maps must decode to exactly the input
python3 clips.py       # the test scenes through the real posterize filter
python3 measure.py | tee results.md
