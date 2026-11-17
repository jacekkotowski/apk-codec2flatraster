#!/usr/bin/env python3
"""After PLAN-edge.md G1 failed: how small can real video (the 0.17 path, jni/flat.c with Hold face still, flat background, face only
box) get? Sweeps colours, pictures a second and picture size; coded with x264 (veryslow crf 40, GOP 10 s), svt-av1 (preset 4 crf 45) and the
exact coder; bytes per 30 s. `python3 sweep.py` -> out/sweep.json and a table. No legibility measure (see PLAN-edge.md section 9)."""
import json, os, subprocess, sys, tempfile, shutil
from multiprocessing import Pool
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import baseline, clips, flatcoder  # noqa: E402

CLIPS = ["C1", "C3", "C4", "C5"]
# name: (W, H, colours, camera frames per picture)  16 camera frames a second: 8 -> 2 pictures/s, 16 -> 1 picture/s
VARS = {"160x120 8c 2/s": (160, 120, 8, 8), "160x120 4c 2/s": (160, 120, 4, 8), "160x120 8c 1/s": (160, 120, 8, 16), "160x120 4c 1/s": (160, 120, 4, 16),
        "128x96 8c 2/s": (128, 96, 8, 8), "128x96 4c 1/s": (128, 96, 4, 16), "96x72 4c 2/s": (96, 72, 4, 8), "96x72 4c 1/s": (96, 72, 4, 16)}


def one(a):
    clip, name = a
    W, H, k, per = VARS[name]
    rate = 16 // per
    marks = dict(np.load(os.path.join(clips.OUT, clip + ".marks.npz")))
    tmp = tempfile.mkdtemp()
    try:
        ff = os.path.join(tmp, "f.txt"); baseline.faces_file(ff, baseline.face_boxes(marks), 0.15)
        out = os.path.join(tmp, "o")
        log = subprocess.run([baseline.EXE, baseline.i420_file(clip), ff, "640", "480", str(W), str(H), str(k), "2", "12", "1", "0", str(per), "3", "125", "500", out, "0", "150", "1"],
                             check=True, capture_output=True, text=True).stdout
        n = len(log.splitlines())
        lab = np.fromfile(out + ".lab", np.uint8).reshape(n, H, W); pal = np.fromfile(out + ".pal", np.uint8).reshape(n, -1)
        key = 10 * rate
        coded = flatcoder.encode_clip([bytes(l) for l in lab.reshape(n, -1)], [bytes(p) for p in pal], W, H, key)
        res = {"exact": sum(len(d) for _, d in coded)}
        for tag, args in (("x264", ["-c:v", "libx264", "-preset", "veryslow", "-crf", "40", "-g", str(key), "-keyint_min", str(key), "-sc_threshold", "0", "-bsf:v", "filter_units=remove_types=6"]),
                          ("svt", ["-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", str(key), "-svtav1-params", "scd=0"])):
            mkv = out + tag + ".mkv"
            subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", str(rate), "-i", out + ".i420", *args, mkv], check=True, capture_output=True)
            rows = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=size", "-of", "csv=p=0", mkv], capture_output=True, text=True, check=True).stdout.split()
            res[tag] = sum(int(x.strip(",")) for x in rows)
        return clip, name, res
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    baseline.build()
    with Pool(4) as p:
        R = p.map(one, [(c, v) for v in VARS for c in CLIPS], chunksize=1)
    d = {f"{c}|{v}": r for c, v, r in R}
    json.dump(d, open(os.path.join(clips.OUT, "sweep.json"), "w"))
    print("bytes per 30 s, best of exact / x264 / svt-av1 (smallest in brackets which)")
    print(f"{'variant':16s} " + " ".join(f"{c:>12s}" for c in CLIPS))
    for v in VARS:
        cells = []
        for c in CLIPS:
            r = d[f"{c}|{v}"]; b = min(r, key=r.get); cells.append(f"{r[b]:7d} {b[:4]:>4s}")
        print(f"{v:16s} " + " ".join(f"{x:>12s}" for x in cells))
