#!/usr/bin/env python3
"""PLAN-edge.md task 1.8: legibility measures, the same for every coder (section 6). Run with `selftest`.

score(o, dec_frames, orig_frames=None, step=2) compares what the receiver is shown with the camera frames at the same moments.
  o            landmark dict (marks.py) of the ORIGINAL frames, one row per shown frame
  dec_frames   the shown frames (RGB 640x480, same geometry as the originals: the baseline's window is cropped out of the original first)
  orig_frames  the original frames, for 'object' only
Returns a dict:
  face_found   % of the frames where the original has a face, in which MediaPipe finds one in the shown frame
  face_err     mean distance of the 22 face points, shown vs original, / eye distance (0.05 = 5% of it); face_err_px the same in pixels
  mouth_r      correlation of mouth opening (inner lips distance / eye distance) over the frames where both have a face
  hand_found   % of the original's hands (any frame) with a hand in the shown frame whose wrist is within one hand size
  hand_err     mean distance of the 21 points / hand size (wrist to middle knuckle) over the found hands
  object_f1    edge F1 (1 pixel tolerance; Canny at 160x120 after a bilateral filter, as keypic) inside the hand box of the original,
               over frames with a hand: a proxy for 'a held object is still visible' (no frames are annotated as holding one)
  n_face, n_hand  the numbers the shares are over
The shown frames' detection runs with timestamps `step` camera frames apart (shown frames are 8 a second = step 2)."""
import os, sys
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import marks, keypic  # noqa: E402

LIP_U, LIP_L = 14, 15      # positions of mesh points 13 and 14 in marks.FACE_IDX


def eye_dist(f):
    return float(np.linalg.norm(f[[0, 1]].mean(0) - f[[2, 3]].mean(0)))


def mouth_open(f):
    return float(np.linalg.norm(f[LIP_U] - f[LIP_L])) / eye_dist(f)


def hand_size(h):
    return float(np.linalg.norm(h[9] - h[0]))


def object_f1(orig_frames, dec_frames, o):
    fs = []
    for i in range(len(o["face"])):
        for j in range(2):
            if not o["hand"][i, j]:
                continue
            p = o["hpts"][i, j] / 4.0
            x0, y0 = np.floor(p.min(0) - 0.25 * (p.max(0) - p.min(0)) - 1).astype(int); x1, y1 = np.ceil(p.max(0) + 0.25 * (p.max(0) - p.min(0)) + 1).astype(int)
            x0, y0, x1, y1 = max(0, x0), max(0, y0), min(160, x1), min(120, y1)
            if x1 - x0 < 4 or y1 - y0 < 4:
                continue
            a = keypic.edge_map(np.ascontiguousarray(orig_frames[i]))[y0:y1, x0:x1] > 0
            b = keypic.edge_map(np.ascontiguousarray(dec_frames[i]))[y0:y1, x0:x1] > 0
            k = np.ones((3, 3), np.uint8)
            da, db = cv2.dilate(a.astype(np.uint8), k) > 0, cv2.dilate(b.astype(np.uint8), k) > 0
            if a.sum() == 0 or b.sum() == 0:
                fs.append(0.0 if (a.sum() + b.sum()) else 1.0); continue
            prec, rec = (b & da).sum() / b.sum(), (a & db).sum() / a.sum()
            fs.append(0.0 if prec + rec == 0 else 2 * prec * rec / (prec + rec))
    return 100 * float(np.mean(fs)) if fs else float("nan")


def compare(o, d, orig_frames=None, dec_frames=None):
    n = len(o["face"])
    both = o["face"] & d["face"]
    r = {"n_face": int(o["face"].sum()), "n_hand": int(o["hand"].sum())}
    r["face_found"] = 100 * float(d["face"][o["face"]].mean()) if o["face"].any() else float("nan")
    errs, errpx, mo, md = [], [], [], []
    for i in np.nonzero(both)[0]:
        e = eye_dist(o["fpts"][i])
        dd = np.linalg.norm(o["fpts"][i] - d["fpts"][i], axis=1)
        errs.append(dd.mean() / e); errpx.append(dd.mean())
        mo.append(mouth_open(o["fpts"][i])); md.append(mouth_open(d["fpts"][i]))
    r["face_err"] = float(np.mean(errs)) if errs else float("nan")
    r["face_err_px"] = float(np.mean(errpx)) if errpx else float("nan")
    r["mouth_r"] = float(np.corrcoef(mo, md)[0, 1]) if len(mo) > 2 and np.std(mo) > 0 and np.std(md) > 0 else float("nan")
    found, herr = 0, []
    for i in range(n):
        for j in range(2):
            if not o["hand"][i, j]:
                continue
            hs = hand_size(o["hpts"][i, j])
            best = None
            for k in range(2):
                if d["hand"][i, k]:
                    w = np.linalg.norm(d["hpts"][i, k][0] - o["hpts"][i, j][0])
                    if best is None or w < best[0]:
                        best = (w, k)
            if best is not None and best[0] < hs:
                found += 1
                herr.append(np.linalg.norm(d["hpts"][i, best[1]] - o["hpts"][i, j], axis=1).mean() / hs)
    r["hand_found"] = 100 * found / max(1, r["n_hand"]) if r["n_hand"] else float("nan")
    r["hand_err"] = float(np.mean(herr)) if herr else float("nan")
    r["object_f1"] = object_f1(orig_frames, dec_frames, o) if orig_frames is not None and r["n_hand"] else float("nan")
    return r


def score(o, dec_frames, orig_frames=None, step=2):
    d = marks.detect(dec_frames, step=step)
    return compare(o, d, orig_frames, dec_frames)


def row(r):
    f = lambda v, s: ("   n/a" if v != v else s.format(v))
    return (f"face {f(r['face_found'], '{:5.1f}')}%  err {f(r['face_err'], '{:.3f}')} ({f(r['face_err_px'], '{:.1f}')} px)  mouth r {f(r['mouth_r'], '{:.2f}')}  "
            f"hand {f(r['hand_found'], '{:5.1f}')}%  err {f(r['hand_err'], '{:.3f}')}  object F1 {f(r['object_f1'], '{:.0f}')}%")


def selftest():
    import clips
    name = "C1" if os.path.exists(os.path.join(clips.OUT, "C1.rgb")) else "made"
    fr = clips.load(name)
    idx = list(range(0, 480, 16))[:30]          # 30 shown frames
    frames = np.array([np.ascontiguousarray(fr[i]) for i in idx])
    o = marks.detect(frames, step=16)
    print(f"clip {name}, {len(frames)} frames")
    print("original against itself:    ", row(compare(o, o, frames, frames)))
    r = score(o, frames, frames, step=16)
    print("original, detected again:   ", row(r))
    assert r["face_found"] == 100 and r["face_err"] < 0.01 and r["mouth_r"] > 0.99, "identical frames must score perfectly"
    sh = np.roll(frames, 5, axis=2)             # 5 pixels to the right
    r5 = score(o, sh, frames, step=16)
    print("shifted right by 5 pixels:  ", row(r5))
    assert 4.0 < r5["face_err_px"] < 6.0, f"a 5 pixel shift should read as about 5 pixels, got {r5['face_err_px']:.2f}"
    print("ok")


if __name__ == "__main__":
    selftest()
