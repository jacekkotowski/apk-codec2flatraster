#!/usr/bin/env python3
"""Stage 6a (ROADMAP.md): makes the input pictures for the picture coder measurement.

Every clip goes through the real posterize filter (jni/flat.c, built for a computer) and is saved in out/ as
  name.lab   palette-index maps, one byte per pixel, 320 x 240 per picture
  name.pal   the palette of each picture, 8 colours x RGB
  name.i420  the same pictures as the app hands them to a video encoder

Clips (all synthetic):
  selftest  the scene of the phone self-test (PictureTest.scene): 640x480 camera frames to 320x240, 8 colours, the app's defaults
            (smoothing 2, hold 12, clean-up 1, borders rounded), 60 pictures at 2 a second
  moving    tests/flat_test.py part 1 (tools/bench.py synth_clip): moving shaded shapes on a gradient, 60 pictures at 3 a second
  still     tests/flat_test.py part 2: the first picture of `moving` with fresh camera noise each time, 20 pictures at 3 a second
  leaves    tests/motion_test.py: still background, flickering leaves, a walker crossing; 110 pictures at 2 a second, filter for
            repeating movement off
  leaves2   the same with that filter at level 2
The settings of the last four are the ones their tests use (no border rounding).
Needs gcc, a JDK, numpy, opencv-python-headless."""
import os, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(HERE, "out")
sys.path.insert(0, os.path.join(HERE, ".."))
W, H = 320, 240
# name: (pictures a second, colours)
CLIPS = {"selftest": (2, 8), "moving": (3, 8), "still": (3, 8), "leaves": (2, 8), "leaves2": (2, 8)}


def sh(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def filt(kind, src, name, sw, shh, smooth, hold, clean, ignore, rnd):
    log = sh(os.path.join(OUT, "dump"), kind, src, str(sw), str(shh), str(W), str(H), "8", str(smooth), str(hold), str(clean),
             str(ignore), str(rnd), os.path.join(OUT, name))
    rows = [[int(x) for x in line.split()] for line in log.splitlines()]
    print(f"{name:9s} {len(rows)} pictures, palette built {sum(r[1] for r in rows)} time(s), "
          f"{np.mean([r[2] for r in rows[1:]]):.0f} pixels change per picture on average")


def rgb_file(frames_rgb, name):
    path = os.path.join(OUT, name + ".rgb")
    with open(path, "wb") as f:
        for fr in frames_rgb:
            f.write(np.ascontiguousarray(fr).tobytes())
    return path


def main():
    os.makedirs(OUT, exist_ok=True)
    sh("gcc", "-O2", "-o", os.path.join(OUT, "dump"), os.path.join(HERE, "dump.c"), os.path.join(ROOT, "jni", "flat.c"), "-lm")

    # selftest: the camera frames come from the app's own PictureTest.scene, so they are the pictures the phone was given.
    cls = os.path.join(OUT, "classes")
    sh("javac", "-nowarn", "-d", cls, *[os.path.join(ROOT, "src", "pl", "dyktafon", f + ".java") for f in
       ("SelfTest", "Dred", "DredLib", "VoiceChain", "Denoiser", "Vad", "Limiter", "Compressor")],
       os.path.join(ROOT, "src", "pl", "flatraster", "Flat.java"), os.path.join(ROOT, "src", "pl", "flatraster", "PictureTest.java"),
       os.path.join(HERE, "SceneDump.java"))
    cam = os.path.join(OUT, "selftest_camera.i420")
    sh("java", "-cp", cls, "pl.flatraster.SceneDump", cam, "60")
    filt("yuv", cam, "selftest", 640, 480, 2, 12, 1, 0, 1)
    os.remove(cam)

    # moving and still: as tests/flat_test.py makes them (BGR from synth_clip, turned to RGB)
    from bench import synth_clip
    moving = synth_clip(60, W, H, 3)
    filt("rgb", rgb_file([f[:, :, ::-1] for f in moving], "moving"), "moving", W, H, 2, 12, 1, 0, 0)
    rng = np.random.default_rng(7)
    base = moving[0].astype(np.float32)
    still = [np.clip(base + rng.normal(0, 4, base.shape), 0, 255).astype(np.uint8) for _ in range(20)]
    filt("rgb", rgb_file([f[:, :, ::-1] for f in still], "still"), "still", W, H, 2, 12, 1, 0, 0)

    # leaves: the scene of tests/motion_test.py, same seed and order of random draws
    n = 110
    leaf = (slice(40, 120), slice(180, 300))
    rng = np.random.default_rng(5)
    yy, xx = np.mgrid[0:H, 0:W]
    base = np.zeros((H, W, 3), np.float32)
    base[..., 0] = 90 + 60 * (yy > 150); base[..., 1] = 110 + 40 * (xx > 160); base[..., 2] = 150 - 60 * (yy > 150)
    frames = []
    for i in range(n):
        img = base.copy()
        blobs = rng.random((20, 30)) < 0.5
        lf = np.kron(blobs, np.ones((4, 4))).astype(bool)
        img[leaf] = np.where(lf[..., None], (60, 170, 60), (20, 90, 30))
        if 60 <= i < 85:
            x = (i - 60) * 12
            img[70:150, x:x + 24] = (220, 40, 40)
        img += rng.normal(0, 3, img.shape)
        frames.append(np.clip(img, 0, 255).astype(np.uint8))
    src = rgb_file(frames, "leaves")
    filt("rgb", src, "leaves", W, H, 1, 12, 1, 0, 0)
    filt("rgb", src, "leaves2", W, H, 1, 12, 1, 2, 0)
    for name in ("moving", "still", "leaves"):
        os.remove(os.path.join(OUT, name + ".rgb"))


if __name__ == "__main__":
    main()
