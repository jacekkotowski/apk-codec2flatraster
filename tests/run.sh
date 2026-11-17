#!/bin/bash
# Runs every FlatRaster test that works on a computer. Needs gcc, a JDK, python3 with numpy and opencv, node with Playwright.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d)
echo "== filter"; python3 flat_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== repeating movement"; python3 motion_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== camera frames joined between pictures"; python3 cam_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== holding the face still, flat background"; python3 hold_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== smooth enlarging for the screen"; python3 smooth_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== borders under camera drift"; python3 border_test.py
echo "== borders around soft moving things"; python3 soft_test.py
echo "== focus boxes"; python3 focus_test.py 2>&1 | grep -vE "^Svt|^\[lib"
echo "== camera frame loading"; gcc -O2 -Wall -Wextra -fsanitize=address,undefined -o "$T/rot" rot_test.c ../jni/flat.c -lm && "$T/rot"
echo "== file format"; bash frv_test.sh
echo "== controls page"; node ui_test.js "$(cd .. && pwd)/assets/index.html" "$T" | tail -1
echo "== voice: DRED library, resampler, voice track"; bash dred/build.sh > /dev/null
java -cp dred/out pl.dyktafon.RS 2>&1 | grep -v "^Picked up" | tail -1
# 20 s of a made-up voice-like sound with pauses: packets, times and plan; the speech measurements are in PLAN-dred.md.
python3 -c "
import numpy as np; r=np.random.default_rng(1); t=np.arange(16000*20)/16000
x=(np.sin(2*np.pi*140*t)+0.5*np.sin(2*np.pi*420*t))*(np.sin(2*np.pi*0.25*t)>0)*6000+r.standard_normal(len(t))*20
x.astype('<i2').tofile('$T/v.raw')"
for sk in 0 1; do java -Djava.library.path=dred/out -cp dred/out pl.flatraster.VP 9 $sk 120 "$T/v.raw" "$T/v.frv" "$T/v.out" 2>&1 | grep -v "^Picked up"; done
# Sound must sit at the same time as the picture: the decoded voice on the time line against the input, placed at the 120 ms offset.
python3 -c "
import numpy as np
x=np.fromfile('$T/v.raw','<i2').astype(float); y=np.fromfile('$T/v.out','<i2').astype(float)[120*16:]
env=lambda a: np.abs(a[:len(a)//16*16]).reshape(-1,16).mean(1)
a,b=env(x),env(y); n=min(len(a),len(b))-200
lag=max(range(-100,101),key=lambda d: np.corrcoef(a[100:100+n-100],b[100+d:100+d+n-100])[0,1])
print(('ok   ' if abs(lag)<=5 else 'FAIL ')+'voice in step with the picture: %d ms off'%lag)
assert abs(lag)<=5"
echo "== self-test: voice speed on the real DRED library, picture test on the real filter"; bash selftest/run.sh | tail -4
