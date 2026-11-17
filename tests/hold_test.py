#!/usr/bin/env python3
"""Desktop test for holding the face still (flat_set_hold in jni/flat.c) and for a one-colour background (focus level 3), 0.17.

Scene: a talking head in front of a room (a wall with a gradient, a bookshelf of stripes, a window), 640x480 camera frames at 16 a
second, 2 pictures a second kept, 60 pictures. The head sways (40 pixels sideways over 4 s, 10 up and down, plus a quicker small
wobble) and the mouth opens and closes at random-looking speed: that movement is what a listener is shown, and what must survive.
Eyes and mouth are only a little darker than the skin, as in poor light. The camera's face rectangle is the head's bounding box with
the jitter a real face detector has (centre +-3 pixels, size +-5%), widened by `box` of its size on every side and snapped
outwards to the 32-pixel grid CameraSource uses (the centre for the window is not snapped).

Every row is the same scene through a different set of options. 8 colours, 320x240 pictures, joined camera frames, as the app.
  bytes     for 30 s: the exact prototype coder (tools/coder/flatcoder.py), x264 and svt-av1 (step 40 and 45, a full picture every 10 s)
  changed   pixels that change from one picture to the next
  eyes      share of the eye pixels shown in another colour than the skin around them (100% = fully visible)
  mouth r   correlation between how far the mouth is open (truth) and how many mouth-area pixels are not skin colour (1.00 = every
            opening and closing can be followed)
  face sd   how far the face centre wanders in the picture, standard deviation in picture pixels (0 = held still)
Needs gcc and numpy; ffmpeg optional."""
import os, shutil, subprocess, sys, tempfile
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools", "coder"))
import flatcoder  # noqa: E402

SW, SH, W, H, PER, PICS = 640, 480, 320, 240, 8, 60
tmp = tempfile.mkdtemp()
exe = os.path.join(tmp, "hold_test")
subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-fsanitize=address,undefined", "-o", exe, os.path.join(HERE, "hold_test.c"),
                os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)
yy, xx = np.mgrid[0:SH, 0:SW].astype(np.float32)
y2, x2 = np.mgrid[0:SH // 2, 0:SW // 2].astype(np.float32)
FPS_CAM = 16.0


def truth(t):
    """Head centre, mouth opening (pixels), at time t in seconds."""
    cx = 320 + 40 * np.sin(2 * np.pi * t / 4.0) + 8 * np.sin(2 * np.pi * t / 0.9 + 1)
    cy = 235 + 10 * np.sin(2 * np.pi * t / 3.1) + 3 * np.sin(2 * np.pi * t / 0.7)
    mouth = 3 + 15 * abs(np.sin(2 * np.pi * 1.7 * t + 0.4)) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.43 * t))
    return cx, cy, mouth


def render(t, rng, noise):
    cx, cy, mouth = truth(t)
    v = 80 + 60.0 * yy / SH                                              # the wall
    shelf = (xx > 30) & (xx < 150) & (yy > 40) & (yy < 330)
    v = np.where(shelf, np.where(((xx - 30) // 20).astype(int) % 2 == 0, 62, 108), v)   # bookshelf: stripes
    v = np.where((xx > 480) & (xx < 610) & (yy > 50) & (yy < 210), 190, v)  # window
    head = ((xx - cx) / 80) ** 2 + ((yy - cy) / 105) ** 2 < 1
    v = np.where(head, 150, v)
    eyes = ((np.abs(xx - cx + 30) < 12) | (np.abs(xx - cx - 30) < 12)) & (np.abs(yy - cy + 25) < 6)
    v = np.where(head & eyes, 112, v)
    v = np.where(head & (np.abs(xx - cx) < 28) & (np.abs(yy - cy - 48) < mouth / 2), 100, v)
    v = np.where((yy > 400) & (np.abs(xx - cx) < 200 - (yy - 400)), 45, v)      # shoulders
    h2 = ((2 * x2 - cx) / 80) ** 2 + ((2 * y2 - cy) / 105) ** 2 < 1
    y = v + (rng.normal(0, noise, v.shape) if noise else 0)
    return np.clip(y + 0.5, 0, 255).astype(np.uint8), np.where(h2, 110, 128).astype(np.uint8), np.where(h2, 150, 128).astype(np.uint8)


def make_clip(noise=2.5, jitter=3.0):
    rng = np.random.default_rng(5)
    path = os.path.join(tmp, "camera.i420")
    faces = []
    with open(path, "wb") as f:
        for i in range(PICS * PER):
            t = i / FPS_CAM
            y, u, v = render(t, rng, noise)
            f.write(y.tobytes() + u.tobytes() + v.tobytes())
            cx, cy, _ = truth(t)
            faces.append((cx + rng.normal(0, jitter), cy + rng.normal(0, jitter), 160 * (1 + rng.normal(0, 0.05) / 2), 210 * (1 + rng.normal(0, 0.05) / 2)))
    return path, faces


def faces_file(faces, box):
    """The camera's face box per frame, widened by `box` of its size on every side and snapped outwards to a 32-pixel grid."""
    path = os.path.join(tmp, f"faces_{box}.txt")
    with open(path, "w") as f:
        for cx, cy, fw, fh in faces:
            gx, gy = box * fw, box * fh
            x0, y0 = max(0, int(np.floor((cx - fw / 2 - gx) / 32)) * 32), max(0, int(np.floor((cy - fh / 2 - gy) / 32)) * 32)
            x1, y1 = min(SW, int(np.ceil((cx + fw / 2 + gx) / 32)) * 32), min(SH, int(np.ceil((cy + fh / 2 + gy) / 32)) * 32)
            f.write(f"{int(round(cx))} {int(round(cy))} {int(round(fw))} {int(round(fh))} {x0} {y0} {x1} {y1}\n")
    return path


def run(src, faces, name, coarse=0, zoom=100, alpha=0, box=0.75, rounding=1, dz=0, own=False, track=False):
    out = os.path.join(tmp, name)
    log = subprocess.run([exe, src, faces_file(faces, box), str(SW), str(SH), str(W), str(H), "8", "2", "12", "1", str(rounding), str(PER), str(coarse), str(zoom),
                          str(alpha), out, str(dz), str(int(box * 1000)) if own else "-1", str(int(track))], check=True, capture_output=True, text=True).stdout
    rows = np.array([[int(x) for x in line.split()] for line in log.splitlines()])
    return {"rows": rows, "lab": np.fromfile(out + ".lab", np.uint8).reshape(PICS, H, W), "pal": np.fromfile(out + ".pal", np.uint8).reshape(PICS, -1),
            "i420": out + ".i420"}


def video_bytes(i420, args):
    out = i420 + ".mkv"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", "2", "-i", i420, *args, out],
                   check=True, capture_output=True)
    rows = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=size", "-of", "csv=p=0", out], capture_output=True, text=True, check=True).stdout.split()
    return sum(int(x.strip(",")) for x in rows)


X264 = ["-c:v", "libx264", "-preset", "veryslow", "-crf", "40", "-g", "20", "-keyint_min", "20", "-sc_threshold", "0", "-bsf:v", "filter_units=remove_types=6"]
SVT = ["-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "20", "-svtav1-params", "scd=0"]


def legibility(r):
    """Eyes, mouth and face wander, found by mapping the true positions through the window the filter used."""
    eyes, areas, mouths, centres = [], [], [], []
    pal = r["pal"].reshape(PICS, -1, 3).astype(int)
    for p in range(PICS):
        t = (p * PER + PER - 1) / FPS_CAM
        cx, cy, mouth = truth(t)
        L, T, Wd, Hd = [v / 1000.0 for v in r["rows"][p, 3:7]]
        sx, sy = W / (Wd * SW), H / (Hd * SH)                    # picture pixels per camera pixel
        ox, oy = (cx - L * SW) * sx, (cy - T * SH) * sy
        centres.append((ox, oy))
        lab = r["lab"][p]

        def region(x0, x1, y0, y1):                               # camera-frame box (relative to the head) to picture slices
            a, b = int(round((cx + x0 - L * SW) * sx)), int(round((cx + x1 - L * SW) * sx))
            c, d = int(round((cy + y0 - T * SH) * sy)), int(round((cy + y1 - T * SH) * sy))
            return max(0, c), min(H, d), max(0, a), min(W, b)

        c0, c1, a0, a1 = region(-60, 60, -10, 10)                 # cheeks, between the eyes and the mouth: the skin colour there
        cheek = lab[c0 + int(10 * sy):c1, a0:a1]
        cy0, cy1, ax0, ax1 = region(-45, 45, 8, 24)
        skin_patch = lab[cy0:cy1, ax0:ax1]
        skin = np.bincount(lab[region(-50, 50, -5, 5)[0]:region(-50, 50, -5, 5)[1], region(-50, 50, -5, 5)[2]:region(-50, 50, -5, 5)[3]].ravel()).argmax() if True else 0
        e = 0.0
        for dx in (-30, 30):
            r0, r1, q0, q1 = region(dx - 12, dx + 12, -31, -19)
            if r1 > r0 and q1 > q0:
                e += (lab[r0:r1, q0:q1] != skin).mean() / 2
        eyes.append(e)
        r0, r1, q0, q1 = region(-34, 34, 48 - 22, 48 + 22)
        areas.append(float((lab[r0:r1, q0:q1] != skin).sum()) / (sx * sy) if r1 > r0 and q1 > q0 else 0.0)
        mouths.append(mouth)
    k = slice(5, None)
    cc = np.array(centres)[k]
    return {"eyes": float(np.mean(eyes[5:])), "mouth_r": float(np.corrcoef(np.array(areas)[k], np.array(mouths)[k])[0, 1]),
            "wander": float(np.sqrt(cc[:, 0].var() + cc[:, 1].var()))}


def border(lab):
    return float(((np.diff(lab, axis=1) != 0).sum(axis=(1, 2)) + (np.diff(lab, axis=2) != 0).sum(axis=(1, 2)))[5:].mean())


def measure(r):
    labs, pals = [bytes(l) for l in r["lab"].reshape(PICS, -1)], [bytes(p) for p in r["pal"]]
    coded = flatcoder.encode_clip(labs, pals, W, H, 20)
    m = {"exact": sum(len(d) for _, d in coded), "changed": float(r["rows"][5:, 2].mean())}
    if shutil.which("ffmpeg"):
        m["x264"], m["svt"] = video_bytes(r["i420"], X264), video_bytes(r["i420"], SVT)
    m.update(legibility(r))
    return m


T = dict(alpha=500, track=True, own=True, zoom=125)        # what the app does: the window follows the face as seen in the picture
CASES = [   # name, options
    ("0.16: nothing special", {}),
    ("faces: rest in half the colours (box 0.75, as 0.15)", dict(coarse=1, box=0.75)),
    ("zoom 1.25, window in the middle", dict(zoom=125)),
    ("hold from the detector, no flat background", dict(zoom=125, alpha=1000)),
    ("hold from the detector, smoothed 1/2, flat background, box 0.40", dict(zoom=125, alpha=500, coarse=3, box=0.40, own=True)),
    ("hold from the image, no flat background", dict(T)),
    ("hold from the image, flat background, box 0.75 (head and shoulders)", dict(T, coarse=3, box=0.75)),
    ("hold from the image, flat background, box 0.40 (head)", dict(T, coarse=3, box=0.40)),
    ("hold from the image, flat background, box 0.15 (face only)", dict(T, coarse=3, box=0.15)),
    ("hold from the image, rest in a quarter of the colours, box 0.40", dict(T, coarse=2, box=0.40)),
    ("hold from the image, flat background, box 0.40, zoom 1.10", dict(T, coarse=3, box=0.40, zoom=110)),
    ("hold from the image, flat background, box 0.40, zoom 1.50", dict(T, coarse=3, box=0.40, zoom=150)),
    ("hold from the image, flat background, box 0.40, no rounding", dict(T, coarse=3, box=0.40, rounding=0)),
]


def main():
    fails = []

    def check(ok, what):
        print(("ok   " if ok else "FAIL ") + what)
        if not ok:
            fails.append(what)

    have = bool(shutil.which("ffmpeg"))
    print(f"{'option (detector jitter 3 camera pixels)':66s} {'exact':>6s}" + (f" {'x264':>6s} {'svt':>6s}" if have else "") + f" {'changed':>8s} {'eyes':>5s} {'mouth r':>8s} {'face sd':>8s}")
    src, faces = make_clip(jitter=3.0)
    res = {}
    for name, opt in CASES:
        m = res[name] = measure(run(src, faces, f"c{len(res)}", **opt))
        print(f"{name:66s} {m['exact']:6d}" + (f" {m['x264']:6d} {m['svt']:6d}" if have else "") + f" {m['changed']:8.0f} {100 * m['eyes']:4.0f}% {m['mouth_r']:8.2f} {m['wander']:8.1f}")
    base, det = res[CASES[0][0]], res["hold from the detector, smoothed 1/2, flat background, box 0.40"]
    for name in ("hold from the image, flat background, box 0.40 (head)", "hold from the image, flat background, box 0.15 (face only)"):
        m = res[name]
        check(m["exact"] < 0.8 * base["exact"], f"{name}: the exact coder needs under 0.8 of the bytes ({m['exact']} against {base['exact']})")
        if have:
            check(m["x264"] < 0.97 * base["x264"] and m["svt"] < 0.97 * base["svt"], f"{name}: x264 and svt-av1 need fewer bytes too ({m['x264']} against {base['x264']}, {m['svt']} against {base['svt']})")
        check(m["changed"] < 0.65 * base["changed"], f"{name}: under 0.65 of the pixels change per picture ({m['changed']:.0f} against {base['changed']:.0f})")
        check(m["mouth_r"] > 0.9 and m["eyes"] >= base["eyes"], f"{name}: the mouth can still be followed (r {m['mouth_r']:.2f}) and the eyes are as visible ({100 * m['eyes']:.0f}% against {100 * base['eyes']:.0f}%)")
        check(m["wander"] < 1.5, f"{name}: the face stays put in the picture (sd {m['wander']:.1f} pixels against {base['wander']:.1f})")
    m = res["hold from the image, flat background, box 0.40 (head)"]
    check(m["wander"] < det["wander"] / 2 and m["exact"] < 0.85 * det["exact"], f"following the face in the picture beats following the detector ({m['wander']:.1f} against {det['wander']:.1f} pixels, {m['exact']} against {det['exact']} bytes)")
    h0 = res["hold from the image, no flat background"]
    check(h0["exact"] > base["exact"], f"holding the face without a flat background costs bytes, so the app always uses it together with one ({h0['exact']} against {base['exact']})")
    check(res["faces: rest in half the colours (box 0.75, as 0.15)"]["exact"] > base["exact"], "the box of the Faces setting, which trembles with the detector, costs bytes when the detector is as unsteady as here")
    print("FAILED" if fails else "all checks passed")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
