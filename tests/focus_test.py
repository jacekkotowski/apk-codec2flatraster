#!/usr/bin/env python3
"""Desktop test for focus boxes in jni/flat.c: inside a box the picture is untouched, outside it uses fewer colours.
Needs gcc, numpy, opencv (for the test clip); ffmpeg optional."""
import os, shutil, subprocess, sys, tempfile
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from bench import synth_clip
W, H, FPS, N = 320, 240, 3, 60
BOX = (96, 64, 224, 176)   # x0 y0 x1 y1
tmp = tempfile.mkdtemp(); exe = os.path.join(tmp, "flat_test")
subprocess.run(["gcc", "-O2", "-fsanitize=address,undefined", "-o", exe, os.path.join(HERE, "flat_test.c"), os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)
src = os.path.join(tmp, "in.rgb")
with open(src, "wb") as f:
    for fr in synth_clip(N, W, H, FPS): f.write(np.ascontiguousarray(fr[:, :, ::-1]).tobytes())
def run(name, coarse, box):
    dst = os.path.join(tmp, name + ".i420")
    subprocess.run([exe, src, str(W), str(H), "8", "2", "12", "1", dst, "0", str(coarse), *map(str, box)], check=True, capture_output=True)
    y = np.fromfile(dst, np.uint8).reshape(N, H * W * 3 // 2)[:, :W * H].reshape(N, H, W)
    return y, dst
fails = []
def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok: fails.append(what)
plain, d0 = run("plain", 0, (0, 0, 0, 0))
x0, y0, x1, y1 = BOX
sizes = {"whole picture fine (8 colours)": d0}
full = len(np.unique(plain))
for coarse, name in ((1, "half the colours"), (2, "a quarter of the colours")):
    y, d = run(f"box{coarse}", coarse, BOX)
    check(np.array_equal(y[:, y0:y1, x0:x1], plain[:, y0:y1, x0:x1]), f"{name}: inside the box the picture is identical to the plain one")
    out = y.copy(); out[:, y0:y1, x0:x1] = out[0, 0, 0]
    used = len(np.unique(out)); want = max(2, 8 >> coarse)
    check(used <= want, f"{name}: outside the box {used} brightness levels are used (plain picture: {full}, allowed: {want})")
    allc, da = run(f"all{coarse}", coarse, (0, 0, 0, 0))
    check(len(np.unique(allc)) <= want, f"{name}: with no box the whole picture is reduced")
    sizes[f"{name} outside the box"] = d; sizes[f"{name} everywhere"] = da
if shutil.which("ffmpeg"):
    for name, path in sizes.items():
        o = path + ".mp4"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", str(FPS), "-i", path,
                        "-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "999", o], capture_output=True)
        if os.path.exists(o): print(f"     AV1, {name}: {os.path.getsize(o) * 8 / (N / FPS) / 1000:.2f} kbps")
print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
