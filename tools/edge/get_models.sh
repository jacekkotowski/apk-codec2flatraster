#!/bin/bash
# PLAN-edge.md section 5: MediaPipe models. Not committed. SHA-256 is checked after download (recorded 2026-11-17).
set -e
cd "$(dirname "$0")"; mkdir -p out/models; cd out/models
B=https://storage.googleapis.com/mediapipe-models
[ -s face_landmarker.task ] || curl -sS --retry 3 -o face_landmarker.task $B/face_landmarker/face_landmarker/float16/1/face_landmarker.task
[ -s hand_landmarker.task ] || curl -sS --retry 3 -o hand_landmarker.task $B/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
echo "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff  face_landmarker.task
fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1  hand_landmarker.task" | sha256sum -c -
