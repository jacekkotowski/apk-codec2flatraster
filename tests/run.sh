#!/bin/bash
# Runs every FlatRaster test that works on a computer. Needs gcc, a JDK, python3 with numpy and opencv, node with Playwright.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d)
echo "== filter"; python3 flat_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== repeating movement"; python3 motion_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== borders under camera drift"; python3 border_test.py
echo "== borders around soft moving things"; python3 soft_test.py
echo "== focus boxes"; python3 focus_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== camera frame loading"; gcc -O2 -Wall -Wextra -fsanitize=address,undefined -o "$T/rot" rot_test.c ../jni/flat.c -lm && "$T/rot"
echo "== file format"; bash frv_test.sh
echo "== controls page"; node ui_test.js "$(cd .. && pwd)/assets/index.html" "$T" | tail -1
