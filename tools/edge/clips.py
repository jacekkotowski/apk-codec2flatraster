#!/usr/bin/env python3
"""PLAN-edge.md task 1.1: clip loader. Every clip becomes 640x480 RGB at 16 frames a second, 30 s (480 frames) in
out/<name>.rgb (raw, frame after frame) + out/<name>.json (size, count, what was done to make it).

  - raw .yuv (I420, size in the name, 30 a second) and .y4m are read with OpenCV / numpy;
  - frames are taken at 16 a second by nearest time (so 30 -> 16 drops frames, as the camera of FlatRaster would give fewer);
  - a source that is not 4:3 is cropped (centre) to 4:3, then scaled to 640x480;
  - a source shorter than 30 s is played forward, then backward, and so on, up to 30 s: the json says so ("pingpong": true) and
    gives the true length in seconds, so the results can say it.
`python3 clips.py` writes all clips present in out/clips/ and the made-up one ("made") from tests/hold_test.py, and prints a table.
`load(name)` returns a read-only numpy memmap (n, 480, 640, 3) uint8 RGB."""
import json, os, re, sys
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
CLIPS = os.path.join(OUT, "clips")
W, H, FPS, SECONDS = 640, 480, 16, 30
# name: file (raw yuv: size and rate from the file name)
SOURCES = {"C1": "niklas_640_480_30.yuv", "C2": "paris_352_288_30.y4m", "C3": "thaloundeskmtg_640_480_30.yuv",
           "C4": "macmarcostationary_640_480_30.yuv", "C5": "macmarcomoving_640_480_30.yuv"}


def read_y4m(path):
    """Yields RGB frames and the frame rate of a 4:2:0 .y4m."""
    with open(path, "rb") as f:
        head = f.readline().decode().split()
        w = int([t for t in head if t[0] == "W"][0][1:]); h = int([t for t in head if t[0] == "H"][0][1:])
        fr = [t for t in head if t[0] == "F"][0][1:].split(":"); rate = int(fr[0]) / int(fr[1])
        frames = []
        n = w * h * 3 // 2
        while True:
            if not f.readline().startswith(b"FRAME"):
                break
            buf = f.read(n)
            if len(buf) < n:
                break
            frames.append(cv2.cvtColor(np.frombuffer(buf, np.uint8).reshape(h * 3 // 2, w), cv2.COLOR_YUV2RGB_I420))
    return frames, rate


def read_yuv(path):
    m = re.search(r"_(\d+)_(\d+)_(\d+)\.yuv$", path)
    w, h, rate = int(m.group(1)), int(m.group(2)), float(m.group(3))
    raw = np.memmap(path, np.uint8, "r")
    n = raw.size // (w * h * 3 // 2)
    raw = raw[:n * w * h * 3 // 2].reshape(n, h * 3 // 2, w)
    return (cv2.cvtColor(np.ascontiguousarray(raw[i]), cv2.COLOR_YUV2RGB_I420) for i in range(n)), rate, n


def fit(rgb):
    h, w = rgb.shape[:2]
    if w * 3 > h * 4:                      # wider than 4:3: crop the sides
        nw = h * 4 // 3; x0 = (w - nw) // 2; rgb = rgb[:, x0:x0 + nw]
    elif w * 3 < h * 4:                    # taller: crop top and bottom
        nh = w * 3 // 4; y0 = (h - nh) // 2; rgb = rgb[y0:y0 + nh]
    return rgb if rgb.shape[:2] == (H, W) else cv2.resize(rgb, (W, H), interpolation=cv2.INTER_AREA)


def pingpong(i, n):
    """Index of frame i of a clip that plays forward, backward, forward... over n source frames."""
    if n == 1:
        return 0
    k = i % (2 * (n - 1))
    return k if k < n else 2 * (n - 1) - k


def write(name, frames_rgb, rate, n_src, note):
    """frames_rgb: list or sequence of source RGB frames at `rate` a second."""
    nout = FPS * SECONDS
    dur = n_src / rate
    pp = dur < SECONDS - 1e-6
    path = os.path.join(OUT, name + ".rgb")
    with open(path, "wb") as f:
        for i in range(nout):
            j = int(round(i * rate / FPS))          # nearest source frame in time
            f.write(np.ascontiguousarray(fit(frames_rgb[pingpong(j, n_src)])).tobytes())
    info = {"name": name, "w": W, "h": H, "fps": FPS, "frames": nout, "source_seconds": round(dur, 2), "pingpong": pp, "note": note}
    json.dump(info, open(os.path.join(OUT, name + ".json"), "w"))
    return info


def load(name):
    info = json.load(open(os.path.join(OUT, name + ".json")))
    return np.memmap(os.path.join(OUT, name + ".rgb"), np.uint8, "r").reshape(info["frames"], H, W, 3)


def info(name):
    return json.load(open(os.path.join(OUT, name + ".json")))


def make_made():
    """The made-up talking head of tests/hold_test.py (make_clip), 30 s at 16 a second, 640x480: no real face, so the landmarkers
    are expected to say 'no face'."""
    sys.path.insert(0, os.path.join(HERE, "..", "..", "tests"))
    import hold_test
    src, _ = hold_test.make_clip()
    raw = np.fromfile(src, np.uint8)
    n = raw.size // (W * H * 3 // 2)
    raw = raw.reshape(n, H * 3 // 2, W)
    frames = [cv2.cvtColor(raw[i], cv2.COLOR_YUV2RGB_I420) for i in range(n)]
    return write("made", frames, FPS, n, "tests/hold_test.py make_clip (no real face)")


def main():
    os.makedirs(OUT, exist_ok=True)
    infos = []
    for name, fn in SOURCES.items():
        p = os.path.join(CLIPS, fn)
        if not os.path.exists(p):
            print(f"{name}: {fn} missing (run get_clips.sh)")
            continue
        if fn.endswith(".y4m"):
            frames, rate = read_y4m(p); n = len(frames)
        else:
            _, rate, n = read_yuv(p)
            # only the frames that will be used need decoding: the forward-backward walk over the source
            need = {pingpong(int(round(i * rate / FPS)), n) for i in range(FPS * SECONDS)}
            raw = np.memmap(p, np.uint8, "r")
            m = re.search(r"_(\d+)_(\d+)_(\d+)\.yuv$", fn); sw, sh = int(m.group(1)), int(m.group(2))
            raw = raw[:n * sw * sh * 3 // 2].reshape(n, sh * 3 // 2, sw)
            frames = {j: cv2.cvtColor(np.ascontiguousarray(raw[j]), cv2.COLOR_YUV2RGB_I420) for j in need}
        infos.append(write(name, frames, rate, n, fn))
    infos.append(make_made())
    print(f"{'clip':6s} {'size':9s} {'frames':>6s} {'source s':>9s}  note")
    for i in infos:
        print(f"{i['name']:6s} {i['w']}x{i['h']} {i['frames']:6d} {i['source_seconds']:9.1f}  {i['note']}" + ("  (PLAYED FORWARD AND BACKWARD to 30 s)" if i["pingpong"] else ""))


if __name__ == "__main__":
    main()
