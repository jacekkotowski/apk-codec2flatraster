#!/usr/bin/env python3
"""Borders must stay smooth behind and around soft-edged moving things (the fault seen on the phone up to 0.6).
Border length is compared with the ideal: the same scene without noise, every pixel decided fresh. Needs gcc, numpy, opencv."""
import os, subprocess, sys, tempfile
import numpy as np, cv2
HERE = os.path.dirname(os.path.abspath(__file__)); W, H, N = 320, 240, 60
tmp = tempfile.mkdtemp(); exe = os.path.join(tmp, "flat_test")
subprocess.run(["gcc", "-O2", "-o", exe, os.path.join(HERE, "flat_test.c"), os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)
def clip(noise):
    rng = np.random.default_rng(11); yy, xx = np.mgrid[0:H, 0:W].astype(np.float32); out = []
    for i in range(N):
        img = np.stack([70 + 80 * xx / W, 100 + 60 * yy / H, 150 - 60 * xx / W], -1).astype(np.float32)
        x = 30 + i * 4.3; y = 120 + 40 * np.sin(i / 6)
        cv2.circle(img, (int(x), int(y)), 34, (60, 70, 215), -1)
        cv2.rectangle(img, (int(x) - 45, int(y) + 34), (int(x) + 45, H), (90, 160, 60), -1)
        cv2.ellipse(img, (250 - i * 2, 60), (40, 22), i * 5, 0, 360, (200, 200, 90), -1)
        img = cv2.GaussianBlur(img, (0, 0), 3.5)   # soft edges, as from motion blur and a small lens
        if noise: img += rng.normal(0, 4, img.shape)
        out.append(np.clip(img, 0, 255).astype(np.uint8))
    return out
def run(frames, name, hold, rounding):
    src, dst = os.path.join(tmp, name + ".rgb"), os.path.join(tmp, name + ".i420")
    with open(src, "wb") as f:
        for fr in frames: f.write(np.ascontiguousarray(fr[:, :, ::-1]).tobytes())
    subprocess.run([exe, src, str(W), str(H), "8", "2", str(hold), "1", dst], check=True, capture_output=True, env={**os.environ, "FLAT_ROUND": str(rounding)})
    y = np.fromfile(dst, np.uint8).reshape(N, H * W * 3 // 2)[:, :W * H].reshape(N, H, W).astype(np.int16)
    return ((np.diff(y, axis=1) != 0).sum(axis=(1, 2)) + (np.diff(y, axis=2) != 0).sum(axis=(1, 2)))[5:].mean()
ideal = run(clip(False), "ideal", 0, 0)
noisy = clip(True); fails = 0
for name, rounding, limit in (("as they fall", 0, 1.35), ("rounded", 1, 1.10), ("very rounded", 2, 1.10)):
    r = run(noisy, f"r{rounding}", 12, rounding) / ideal
    ok = r < limit; fails += not ok
    print(f"{'ok  ' if ok else 'FAIL'} borders {name}: {r:.2f}x the ideal length (limit {limit})")
print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
