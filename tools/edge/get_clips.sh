#!/bin/bash
# PLAN-edge.md section 4: public codec test sequences from Google's aom-test-data bucket. Not committed (large; faces of real people).
set -e
cd "$(dirname "$0")"; mkdir -p out/clips; cd out/clips
for n in niklas_640_480_30.yuv paris_352_288_30.y4m thaloundeskmtg_640_480_30.yuv macmarcostationary_640_480_30.yuv macmarcomoving_640_480_30.yuv; do
  [ -s "$n" ] || curl -sS --retry 3 -o "$n" "https://storage.googleapis.com/aom-test-data/$n"
  echo "$n $(stat -c %s "$n") bytes"
done
