#!/usr/bin/env python3
"""PLAN-edge.md task 1.7: the fallback on the same clips. FlatRaster 0.17's real filter (jni/flat.c, through tests/hold_test.c, which
runs it as the app does) with Hold face still, flat background, 8 colours, 2 pictures a second (of every 8 camera frames the last
one becomes a picture, the others are joined into it), at 320x240 and 160x120, face only (box 0.15) and head (box 0.40); coded with
  exact   the stage 6a prototype (tools/coder/flatcoder.py), a full picture every 10 s
  svt     svt-av1 preset 4, crf 45, GOP 20 pictures (10 s)
  x264    x264 veryslow, crf 40, GOP 20
`python3 baseline.py [CLIP ...]` writes out/CLIP.base_<cfg>.json (bytes per 30 s per coder) and out/CLIP.base_<cfg>.npz (decoded pictures per
coder, as RGB at the coded size, and the window of each picture). cfg: 320h 320f 160h 160f.
Assumption: the app gives the filter the camera's face rectangle; here it is made from MediaPipe's 22 points (width between the face
sides, 0.76 as wide as tall, centred between brows and chin), i.e. a detector as good as MediaPipe, with its natural tremble.
The picture is a window that follows the face (zoom 1.25): legible.py crops the original to the same window before comparing."""
import json, os, subprocess, sys, shutil, tempfile
from multiprocessing import Pool
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append(os.path.join(HERE, "..", "coder"))
import clips, flatcoder  # noqa: E402

ROOT = os.path.join(HERE, "..", "..")
OUT = clips.OUT
SW, SH, PER = 640, 480, 8
CFG = {"320h": (320, 240, 0.40), "320f": (320, 240, 0.15), "160h": (160, 120, 0.40), "160f": (160, 120, 0.15)}
X264 = ["-c:v", "libx264", "-preset", "veryslow", "-crf", "40", "-g", "20", "-keyint_min", "20", "-sc_threshold", "0", "-bsf:v", "filter_units=remove_types=6"]
SVT = ["-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "20", "-svtav1-params", "scd=0"]
EXE = os.path.join(OUT, "hold_run")


def build():
    subprocess.run(["gcc", "-O2", "-o", EXE, os.path.join(ROOT, "tests", "hold_test.c"), os.path.join(ROOT, "jni", "flat.c"), "-lm"], check=True)


def face_boxes(marks):
    """Per camera frame (cx, cy, fw, fh) from the 22 points, or None."""
    out = []
    for f in range(len(marks["face"])):
        if not marks["face"][f]:
            out.append(None); continue
        p = marks["fpts"][f]
        sides = p[[20, 21]]                       # 234, 454
        fw = float(np.hypot(*(sides[1] - sides[0]))) * 1.05
        fh = fw / 0.76
        cx = float(p[:, 0].mean())
        cy = float((p[[8, 9, 10, 11], 1].mean() + p[19, 1]) / 2 + 0.0)
        out.append((cx, cy, fw, fh))
    return out


def faces_file(path, boxes, box):
    last = None
    with open(path, "w") as f:
        for b in boxes:
            b = b or last
            if b is None:
                f.write("-1 0 0 0 0 0 0 0\n"); continue
            last = b
            cx, cy, fw, fh = b
            gx, gy = box * fw, box * fh
            x0, y0 = max(0, int(np.floor((cx - fw / 2 - gx) / 32)) * 32), max(0, int(np.floor((cy - fh / 2 - gy) / 32)) * 32)
            x1, y1 = min(SW, int(np.ceil((cx + fw / 2 + gx) / 32)) * 32), min(SH, int(np.ceil((cy + fh / 2 + gy) / 32)) * 32)
            f.write(f"{int(round(cx))} {int(round(cy))} {int(round(fw))} {int(round(fh))} {x0} {y0} {x1} {y1}\n")


def i420_file(clip):
    path = os.path.join(OUT, clip + ".i420")
    if not os.path.exists(path):
        fr = clips.load(clip)
        with open(path, "wb") as f:
            for i in range(len(fr)):
                f.write(cv2.cvtColor(np.ascontiguousarray(fr[i]), cv2.COLOR_RGB2YUV_I420).tobytes())
    return path


def video(i420, W, H, args, tag):
    mkv = i420 + "." + tag + ".mkv"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", "2", "-i", i420, *args, mkv], check=True, capture_output=True)
    rows = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=size", "-of", "csv=p=0", mkv], capture_output=True, text=True, check=True).stdout.split()
    size = sum(int(x.strip(",")) for x in rows)
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", mkv, "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"], capture_output=True, check=True).stdout
    fs = W * H * 3 // 2
    pics = np.frombuffer(raw, np.uint8).reshape(-1, fs)
    rgb = np.array([cv2.cvtColor(p.reshape(H * 3 // 2, W), cv2.COLOR_YUV2RGB_I420) for p in pics])
    return size, rgb


def one(args):
    clip, cfg = args
    W, H, box = CFG[cfg]
    marks = dict(np.load(os.path.join(OUT, clip + ".marks.npz")))
    tmp = tempfile.mkdtemp()
    try:
        ff = os.path.join(tmp, "faces.txt")
        faces_file(ff, face_boxes(marks), box)
        out = os.path.join(tmp, "o")
        # settings of tests/hold_test.py T: hold from the image (alpha 500, track, own box), zoom 1.25, flat background (coarse 3)
        log = subprocess.run([EXE, i420_file(clip), ff, str(SW), str(SH), str(W), str(H), "8", "2", "12", "1", "0" if W < 320 else "1", str(PER), "3", "125", "500", out, "0", str(int(box * 1000)), "1"],
                             check=True, capture_output=True, text=True).stdout
        rows = np.array([[int(x) for x in l.split()] for l in log.splitlines()])
        n = len(rows)
        lab = np.fromfile(out + ".lab", np.uint8).reshape(n, H, W)
        pal = np.fromfile(out + ".pal", np.uint8).reshape(n, -1)
        i420 = out + ".i420"
        res = {"pictures": n}
        # exact: the posterized pictures, lossless
        pr = [bytes(l) for l in lab.reshape(n, -1)]
        coded = flatcoder.encode_clip(pr, [bytes(p) for p in pal], W, H, 20)
        res["exact"] = sum(len(d) for _, d in coded)
        # decoded pictures of the exact coder: the palette colours
        ex = np.array([pal[i].reshape(-1, 3)[lab[i]] for i in range(n)])
        pics = {"exact": ex}
        for tag, a in (("x264", X264), ("svt", SVT)):
            res[tag], pics[tag] = video(i420, W, H, a, tag)
        res["window"] = rows[:, 3:7].tolist()
        np.savez_compressed(os.path.join(OUT, f"{clip}.base_{cfg}.npz"), window=rows[:, 3:7], **pics)
        json.dump(res, open(os.path.join(OUT, f"{clip}.base_{cfg}.json"), "w"))
        return clip, cfg, res
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    build()
    names = sys.argv[1:] or [n for n in list(clips.SOURCES) + ["made"] if os.path.exists(os.path.join(OUT, n + ".marks.npz"))]
    jobs = [(c, g) for c in names for g in CFG]
    print(f"{'clip':5s} {'cfg':5s} {'exact':>7s} {'x264':>7s} {'svt-av1':>8s}   bytes per 30 s", flush=True)
    with Pool(4) as p:
        for clip, cfg, r in p.imap_unordered(one, jobs):
            print(f"{clip:5s} {cfg:5s} {r['exact']:7d} {r['x264']:7d} {r['svt']:8d}", flush=True)


if __name__ == "__main__":
    main()
