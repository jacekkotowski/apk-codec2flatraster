#!/usr/bin/env python3
"""Desktop test for joining the camera's frames between two pictures (flat_gather in jni/flat.c, 0.16).
The camera makes 16 frames a second here and the app keeps 2: up to 0.15 the other 14 were thrown away.
Scene: the self-test scene (a head that sways in front of a wall with a window), with camera noise at three strengths, and once
with a shaken hand (some frames blurred sideways). Each case is run both ways and compared with the ideal: the same scene
without noise or blur, every pixel decided afresh.
  border   length of the borders between colours, times the ideal's (1.00 = as smooth as the ideal)
  changed  pixels that change from one picture to the next
  face     how far the face is from the ideal's: the pixels shown in the skin colour in one picture but not the other, as a
           share of the ideal's. It covers the outline of the head and the eyes and mouth inside it, and it does not depend on
           which shades the palette happened to pick for the wall (they differ from run to run without either being wrong).
  bytes    for 30 s, by the exact prototype coder (tools/coder/flatcoder.py) and, if ffmpeg is there, x264 and svt-av1
Needs gcc and numpy; ffmpeg optional."""
import os, shutil, subprocess, sys, tempfile
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools", "coder"))
import flatcoder  # noqa: E402

SW, SH, W, H, PER, PICS, FPS = 640, 480, 320, 240, 8, 60, 2
tmp = tempfile.mkdtemp()
exe = os.path.join(tmp, "cam_test")
subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-fsanitize=address,undefined", "-o", exe, os.path.join(HERE, "cam_test.c"),
                os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)
yy, xx = np.mgrid[0:SH, 0:SW].astype(np.float32)
y2, x2 = np.mgrid[0:SH // 2, 0:SW // 2].astype(np.float32)


def frame(t):
    """PictureTest.scene at time t (in pictures), without noise: brightness and the two colour planes."""
    cx, cy = 320 + 30 * np.sin(t / 7.0), 230 + 8 * np.cos(t / 5.0)
    v = 90 + 60.0 * yy / SH
    v = np.where((xx > 470) & (xx < 600) & (yy > 60) & (yy < 220), 200, v)
    head = ((xx - cx) / 80) ** 2 + ((yy - cy) / 105) ** 2 < 1
    v = np.where(head, 165, v)
    eyes = ((np.abs(xx - cx + 30) < 12) | (np.abs(xx - cx - 30) < 12)) & (np.abs(yy - cy + 25) < 6)
    v = np.where(head & eyes, 60, v)
    v = np.where(head & (np.abs(xx - cx) < 28) & (np.abs(yy - cy - 45) < 5), 80, v)
    v = np.where((yy > 400) & (np.abs(xx - cx) < 200 - (yy - 400)), 70, v)
    h2 = ((2 * x2 - cx) / 80) ** 2 + ((2 * y2 - cy) / 105) ** 2 < 1
    return v.astype(np.float32), np.where(h2, 110, 128).astype(np.uint8), np.where(h2, 150, 128).astype(np.uint8)


def camera(noise, shake, seed=5):
    """Writes PICS * PER camera frames. shake: two frames of every eight are blurred sideways over 13 camera pixels, as a shaken
    hand does, and in every second picture so is the frame the app would have kept."""
    rng = np.random.default_rng(seed)
    path = os.path.join(tmp, f"cam_{noise}_{int(shake)}.i420")
    with open(path, "wb") as f:
        for i in range(PICS * PER):
            y, u, v = frame(i / PER)
            pic, k = divmod(i, PER)
            if shake and (k in (1, 4) or (k == PER - 1 and pic % 2 == 1)):
                c = np.cumsum(np.pad(y, ((0, 0), (7, 6)), mode="edge"), axis=1)
                y = (c[:, 13:] - c[:, :-13]) / 13
                y = np.pad(y, ((0, 0), (0, SW - y.shape[1])), mode="edge")
            if noise:
                y = y + rng.normal(0, noise, y.shape)
            f.write(np.clip(y + 0.5, 0, 255).astype(np.uint8).tobytes() + u.tobytes() + v.tobytes())
    return path


def run(src, name, mode, hold=12, rounding=1):
    out = os.path.join(tmp, name)
    log = subprocess.run([exe, src, str(SW), str(SH), str(W), str(H), "8", "2", str(hold), "1", str(rounding), str(PER), str(mode), out],
                         check=True, capture_output=True, text=True).stdout
    rows = np.array([[int(x) for x in line.split()] for line in log.splitlines()])
    lab = np.fromfile(out + ".lab", np.uint8).reshape(PICS, H, W)
    yuv = np.fromfile(out + ".i420", np.uint8).reshape(PICS, -1)
    pal = np.fromfile(out + ".pal", np.uint8).reshape(PICS, -1)
    return {"rows": rows, "lab": lab, "y": yuv[:, :W * H].reshape(PICS, H, W).astype(np.int16), "pal": pal, "i420": out + ".i420"}


def skin(r):
    """Per picture, the pixels shown in the skin colour: the palette entry that is most red against blue."""
    pal = r["pal"].reshape(PICS, -1, 3).astype(int)
    idx = (pal[:, :, 0] - pal[:, :, 2]).argmax(axis=1)
    return r["lab"] == idx[:, None, None]


def face(r, ideal):
    a, b = skin(r)[5:], skin(ideal)[5:]
    return float((a != b).sum() / b.sum())


def border(lab):
    return float(((np.diff(lab, axis=1) != 0).sum(axis=(1, 2)) + (np.diff(lab, axis=2) != 0).sum(axis=(1, 2)))[5:].mean())


def proto_bytes(r):
    labs, pals = [bytes(l) for l in r["lab"].reshape(PICS, -1)], [bytes(p) for p in r["pal"]]
    coded = flatcoder.encode_clip(labs, pals, W, H, 10 * FPS)
    assert flatcoder.decode_clip(coded, W, H) == (labs, pals)
    return sum(len(d) for _, d in coded)


def video_bytes(r, args):
    out = r["i420"] + ".mkv"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", str(FPS),
                    "-i", r["i420"], *args, out], check=True, capture_output=True)
    rows = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=size", "-of", "csv=p=0", out],
                          capture_output=True, text=True, check=True).stdout.split()
    return sum(int(x.strip(",")) for x in rows)


X264 = ["-c:v", "libx264", "-preset", "veryslow", "-crf", "40", "-g", "20", "-keyint_min", "20", "-sc_threshold", "0", "-bsf:v", "filter_units=remove_types=6"]
SVT = ["-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "20", "-svtav1-params", "scd=0"]
fails = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails.append(what)


ideal = run(camera(0, False), "ideal", 0, hold=0)
ideal_border = border(ideal["lab"])
clean = run(camera(0, False), "clean_joined", 1)
plain = run(camera(0, False), "clean_one", 0)
have_ffmpeg = bool(shutil.which("ffmpeg"))
print(f"{'case':34s} {'frames':>12s} {'border':>7s} {'changed':>8s} {'face':>7s} {'exact coder':>12s}" + (f" {'x264':>8s} {'svt-av1':>8s}" if have_ffmpeg else ""))
res = {}
for noise, shake in ((2.5, False), (8, False), (16, False), (8, True)):
    src = camera(noise, shake)
    for mode in (0, 1):
        r = run(src, f"n{noise}_{int(shake)}_{mode}", mode)
        m = {"border": border(r["lab"]) / ideal_border, "changed": float(r["rows"][5:, 2].mean()),
             "off": face(r, ideal), "bytes": proto_bytes(r),
             "thr": float(r["rows"][5:, 5].mean()), "avg": float(r["rows"][5:, 6].mean()) / 10, "older": int((r["rows"][:, 4] > 0).sum())}
        if have_ffmpeg:
            m["x264"], m["svt"] = video_bytes(r, X264), video_bytes(r, SVT)
        res[noise, shake, mode] = m
        case = f"noise {noise}" + (", shaken hand" if shake else "")
        print(f"{case:34s} {'all joined' if mode else 'one kept':>12s} {m['border']:7.2f} {m['changed']:8.0f} {100 * m['off']:6.2f}% {m['bytes']:12d}"
              + (f" {m['x264']:8d} {m['svt']:8d}" if have_ffmpeg else ""))
    a, b = res[noise, shake, 0], res[noise, shake, 1]
    print(f"     joined: limit {b['thr']:.0f}, {b['avg']:.0f}% of pixels averaged, an earlier frame chosen for {b['older']} of {PICS} pictures")

same = float((clean["lab"] != plain["lab"]).mean())
check(same < 0.002, f"no noise: joining the frames changes {100 * same:.3f}% of the pixels, so a moving head is not smeared")
for (noise, shake) in ((2.5, False), (8, False), (16, False)):
    a, b = res[noise, shake, 0], res[noise, shake, 1]
    check(b["off"] <= a["off"] * 1.05 + 0.002, f"noise {noise}: the face stays as near the ideal ({100 * a['off']:.2f}% to {100 * b['off']:.2f}% of its pixels differ)")
    check(b["border"] <= a["border"] * 1.01, f"noise {noise}: borders no longer than with one frame ({a['border']:.2f} to {b['border']:.2f} times the ideal)")
    check(b["bytes"] <= a["bytes"] * 1.02, f"noise {noise}: no more bytes ({a['bytes']} to {b['bytes']} for 30 s, exact coder)")
    check(b["older"] == 0, f"noise {noise}: with no blur the newest frame is always the one chosen")
a, b = res[16, False, 0], res[16, False, 1]
check(b["border"] < 0.92 * a["border"] and b["bytes"] < 0.9 * a["bytes"], "strong noise: clearly smoother borders and clearly fewer bytes")
a, b = res[8, True, 0], res[8, True, 1]
check(b["older"] == PICS // 2, f"shaken hand: an earlier, sharp frame is chosen for exactly the {PICS // 2} pictures whose last frame is blurred ({b['older']})")
check(b["off"] < 0.5 * a["off"] and b["border"] < a["border"] and b["bytes"] < a["bytes"],
      f"shaken hand: the face is nearer the ideal ({100 * a['off']:.1f}% to {100 * b['off']:.1f}% of its pixels differ), borders are shorter and bytes fewer")
print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
