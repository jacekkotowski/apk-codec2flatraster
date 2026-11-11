#!/usr/bin/env python3
"""Desktop test for jni/flat.c. Needs gcc, numpy, opencv-python(-headless); ffmpeg is optional.
Checks: colour count, still scenes give identical frames despite noise, speed. Prints AV1/HEVC size if ffmpeg is there."""
import os, shutil, subprocess, sys, tempfile
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from bench import synth_clip  # noqa: E402

W, H, FPS, COLORS = 320, 240, 3, 8
tmp = tempfile.mkdtemp()
exe = os.path.join(tmp, "flat_test")
subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-o", exe, os.path.join(HERE, "flat_test.c"),
                os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)


def run(frames, name, colors=COLORS, smooth=2, hold=12, clean=1):
    src, dst = os.path.join(tmp, name + ".rgb"), os.path.join(tmp, name + ".i420")
    with open(src, "wb") as f:
        for fr in frames:
            f.write(np.ascontiguousarray(fr[:, :, ::-1]).tobytes())  # BGR -> RGB
    out = subprocess.run([exe, src, str(W), str(H), str(colors), str(smooth), str(hold), str(clean), dst],
                         check=True, capture_output=True, text=True).stdout
    rows = [[float(x) for x in line.split()] for line in out.splitlines()]
    return rows, dst


fails = []
def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails.append(what)


# 1. moving scene
moving = synth_clip(60, W, H, FPS)
rows, dst = run(moving, "moving")
check(len(rows) == 60, "all 60 frames processed")
check(all(1 <= r[1] <= COLORS for r in rows), f"never more than {COLORS} colours (max seen {max(r[1] for r in rows):.0f})")
check(rows[0][3] == 1 and sum(r[3] for r in rows) <= 3, f"palette built once, rebuilt rarely ({sum(r[3] for r in rows):.0f} builds)")
ms = sorted(r[4] for r in rows)[len(rows) // 2]
check(ms < 100, f"median {ms:.1f} ms per 320x240 frame on this computer")
changed = np.mean([r[2] for r in rows[1:]]) / (W * H)
print(f"     moving scene: {100 * changed:.1f}% of pixels change per frame")

# 2. still scene with fresh sensor noise each frame: output must not change
rng = np.random.default_rng(7)
base = moving[0].astype(np.float32)
still = [np.clip(base + rng.normal(0, 4, base.shape), 0, 255).astype(np.uint8) for _ in range(20)]
rows2, _ = run(still, "still")
worst = max(r[2] for r in rows2[2:])
check(worst <= W * H * 0.002, f"still scene with noise: at most {worst:.0f} of {W * H} pixels change per frame")
rows3, _ = run(still, "still_nohold", hold=0)
print(f"     same scene without temporal hold: up to {max(r[2] for r in rows3[2:]):.0f} pixels change")

# 3. colour count setting is respected at the limits
for k in (2, 4, 32):
    r, _ = run(moving[:5], f"k{k}", colors=k)
    check(all(x[1] <= k for x in r), f"{k} colours requested, {max(x[1] for x in r):.0f} used")

# 4. encoded size, for information
if shutil.which("ffmpeg"):
    for name, args in (("svt-av1 crf45", ["-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "999"]),
                       ("x265 crf40", ["-c:v", "libx265", "-preset", "slow", "-crf", "40", "-g", "999", "-x265-params", "log-level=none"])):
        out = os.path.join(tmp, name.split()[0] + ".mp4")
        r = subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p",
                            "-s", f"{W}x{H}", "-framerate", str(FPS), "-i", dst, *args, out], capture_output=True)
        if r.returncode == 0:
            print(f"     {name}: {os.path.getsize(out) * 8 / (60 / FPS) / 1000:.2f} kbps for the moving scene")

print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
