#!/usr/bin/env python3
"""Borders must not fray while the camera's exposure and white balance drift (the fault seen on the phone up to 0.6).
Still scene, slow drift, sensor noise. Border length = neighbouring pixel pairs with different colours. Needs gcc, numpy, opencv."""
import os, subprocess, sys, tempfile
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from bench import synth_clip
W, H, N = 320, 240, 90
tmp = tempfile.mkdtemp(); exe = os.path.join(tmp, "flat_test")
subprocess.run(["gcc", "-O2", "-o", exe, os.path.join(HERE, "flat_test.c"), os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)
rng = np.random.default_rng(3)
base = synth_clip(1, W, H, 3)[0].astype(np.float32)
src = os.path.join(tmp, "in.rgb")
with open(src, "wb") as f:
    for i in range(N):
        g = 1 + 0.10 * np.sin(2 * np.pi * i / 45); wb = 1 + 0.05 * np.sin(2 * np.pi * i / 70)
        fr = base * g; fr[..., 2] *= wb; fr[..., 0] /= wb
        f.write(np.ascontiguousarray(np.clip(fr + rng.normal(0, 4, fr.shape), 0, 255).astype(np.uint8)[:, :, ::-1]).tobytes())
def run(hold):
    dst = os.path.join(tmp, f"o{hold}.i420")
    subprocess.run([exe, src, str(W), str(H), "8", "2", str(hold), "1", dst], check=True, capture_output=True)
    y = np.fromfile(dst, np.uint8).reshape(N, H * W * 3 // 2)[:, :W * H].reshape(N, H, W).astype(np.int16)
    edges = (np.diff(y, axis=1) != 0).sum(axis=(1, 2)) + (np.diff(y, axis=2) != 0).sum(axis=(1, 2))
    return edges, (np.diff(y, axis=0) != 0).sum(axis=(1, 2))
fails = 0
e0, c0 = run(0)
for hold in (12, 18, 26):
    e, c = run(hold)
    ratio = e[20:].mean() / e[0]
    ok = ratio < 1.15 and c[20:].mean() < 0.25 * c0[20:].mean()
    fails += not ok
    print(f"{'ok  ' if ok else 'FAIL'} hold {hold}: borders stay {ratio:.2f}x the first picture's length; {c[20:].mean():.0f} pixels change per picture (no hold: {c0[20:].mean():.0f})")
print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
