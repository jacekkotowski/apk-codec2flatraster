#!/usr/bin/env python3
"""Desktop test for the repeating-movement filter in jni/flat.c.
Scene: still background, a patch of 'leaves' that flicker between two greens at random, and a red walker who
crosses the picture (also in front of the leaves) between frames 60 and 85. Needs gcc and numpy; ffmpeg optional."""
import os, shutil, subprocess, sys, tempfile
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
W, H, N = 320, 240, 110
LEAF = (slice(40, 120), slice(180, 300))      # rows, columns
tmp = tempfile.mkdtemp(); exe = os.path.join(tmp, "flat_test")
subprocess.run(["gcc", "-O2", "-o", exe, os.path.join(HERE, "flat_test.c"), os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)

rng = np.random.default_rng(5)
yy, xx = np.mgrid[0:H, 0:W]
base = np.zeros((H, W, 3), np.float32)
base[..., 0] = 90 + 60 * (yy > 150); base[..., 1] = 110 + 40 * (xx > 160); base[..., 2] = 150 - 60 * (yy > 150)
frames = []
for i in range(N):
    img = base.copy()
    blobs = rng.random((20, 30)) < 0.5                       # 4x4 pixel leaves, each light or dark at random
    leaf = np.kron(blobs, np.ones((4, 4))).astype(bool)
    img[LEAF] = np.where(leaf[..., None], (60, 170, 60), (20, 90, 30))
    if 60 <= i < 85:
        x = (i - 60) * 12
        img[70:150, x:x + 24] = (220, 40, 40)
    img += rng.normal(0, 3, img.shape)
    frames.append(np.clip(img, 0, 255).astype(np.uint8))
src = os.path.join(tmp, "in.rgb")
with open(src, "wb") as f:
    for fr in frames: f.write(fr.tobytes())

def run(ignore):
    dst = os.path.join(tmp, f"o{ignore}.i420")
    subprocess.run([exe, src, str(W), str(H), "8", "1", "12", "1", dst, str(ignore)], check=True, capture_output=True)
    raw = np.fromfile(dst, np.uint8).reshape(N, H * W * 3 // 2)
    return raw[:, :W * H].reshape(N, H, W), raw[:, W * H:].reshape(N, -1), dst

fails = []
def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok: fails.append(what)

def changed(y, region, a, b):   # mean pixels changed per frame in a region over frames a..b
    return float(np.mean([(y[i][region] != y[i - 1][region]).sum() for i in range(a, b)]))

y0, _, d0 = run(0)
leaf_px = 80 * 120
off = changed(y0, LEAF, 30, 58)
check(off > 0.2 * leaf_px, f"filter off: leaves change {off:.0f} of {leaf_px} pixels per frame")
for level in (1, 2, 3):
    y, v, d = run(level)
    on = changed(y, LEAF, 30, 58)
    limit = {1: 0.3, 2: 0.1, 3: 0.03}[level] * off        # each level must cut the flicker further
    check(on < limit, f"level {level}: leaves change {on:.0f} pixels per frame once learned ({100 - 100 * on / off:.0f}% less than off)")
    # The walker must appear as he does with the filter off (the locked palette has no red, so compare pictures, not colours).
    # Frame 70: he is at x 120..144, away from the leaves. Frame 75: x 180..204, in front of them.
    for frame, box, where in ((70, (slice(74, 146), slice(124, 140)), "on the plain background"), (75, (slice(74, 118), slice(184, 200)), "in front of the leaves")):
        ref = (y0[frame][box] != y0[55][box]).mean()        # how much of his area differs from the empty scene, filter off
        got = (y[frame][box] == y0[frame][box]).mean() if where.startswith("on") else (y[frame][box] != y[55][box]).mean()
        check(ref > 0.5 and got > 0.9 * ref, f"level {level}: walker shown {where} ({100 * got:.0f}%, filter off {100 * ref:.0f}%)")
    gone = changed(y, (slice(0, H), slice(0, W)), 95, N)
    plain = (slice(74, 146), slice(0, 170))                 # his path over the plain background
    trace = (y[100][plain] != y[55][plain]).mean()
    check(trace < 0.002 and gone < 1.5 * limit, f"level {level}: after he leaves, {100 * trace:.2f}% of his path differs from before and {gone:.0f} pixels change per frame")
    still = sum(1 for i in range(30, 58) if (y[i] != y[i - 1]).sum() < 0.005 * W * H)
    print(f"     level {level}: {still} of 28 pictures before the walker could be skipped as still (filter off: "
          f"{sum(1 for i in range(30, 58) if (y0[i] != y0[i - 1]).sum() < 0.005 * W * H)})")
    if shutil.which("ffmpeg") and level == 2:
        for name, path in (("filter off", d0), ("level 2", d)):
            out = path + ".mp4"
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", "2",
                            "-i", path, "-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "999", out], capture_output=True)
            if os.path.exists(out): print(f"     AV1, {name}: {os.path.getsize(out) * 8 / (N / 2) / 1000:.2f} kbps")
print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
