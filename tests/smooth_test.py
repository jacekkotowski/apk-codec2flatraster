#!/usr/bin/env python3
"""Desktop test for the smooth enlarger (jni/smooth.c, 0.16): a small posterized picture shown large.
Truth: the self-test scene, without noise, posterized at 640x480. The same scene is posterized small (160x120 and 320x240, with
camera noise) and enlarged to 640x480 two ways: as blocks (up to 0.15) and with the smooth enlarger.
  face    the pixels shown in the skin colour in one picture but not the other, as a share of the truth's: the outline of the
          head with the eyes and mouth inside it
          (The length of the borders is no use here: counted along pixel edges a staircase is as long as a smooth line.)
The 160x120 picture is also sent through a video coder first (x264 and svt-av1, if ffmpeg is there), as a played recording is.
Needs gcc and numpy; ffmpeg optional. Writes smooth_test.png beside the temporary files when given a path as argument."""
import ctypes, os, shutil, subprocess, sys, tempfile
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
tmp = tempfile.mkdtemp()
exe, lib = os.path.join(tmp, "cam_test"), os.path.join(tmp, "libsmooth.so")
subprocess.run(["gcc", "-O2", "-o", exe, os.path.join(HERE, "cam_test.c"), os.path.join(HERE, "..", "jni", "flat.c"), "-lm"], check=True)
subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-fsanitize=address,undefined", "-fPIC", "-shared", "-o", lib, os.path.join(HERE, "..", "jni", "smooth.c")], check=True)
asan = subprocess.run(["gcc", "-print-file-name=libasan.so"], capture_output=True, text=True).stdout.strip()
PICS = 12


def camera(k, noise, name):
    """PICS camera frames of the self-test scene at k times 640x480 (k = 1 the camera, 2 for the truth)."""
    sw, sh = 640 * k, 480 * k
    yy, xx = np.mgrid[0:sh, 0:sw].astype(np.float32)
    yy, xx = (yy + 0.5) / k - 0.5, (xx + 0.5) / k - 0.5
    y2, x2 = np.mgrid[0:sh // 2, 0:sw // 2].astype(np.float32)
    y2, x2 = (2 * y2 + 1) / k - 0.5, (2 * x2 + 1) / k - 0.5
    rng = np.random.default_rng(5)
    path = os.path.join(tmp, "camera_" + name + ".i420")
    with open(path, "wb") as f:
        for t in range(PICS):
            cx, cy = 320 + 30 * np.sin(t / 7.0), 230 + 8 * np.cos(t / 5.0)
            v = 90 + 60.0 * yy / 480
            v = np.where((xx > 470) & (xx < 600) & (yy > 60) & (yy < 220), 200, v)
            head = ((xx - cx) / 80) ** 2 + ((yy - cy) / 105) ** 2 < 1
            v = np.where(head, 165, v)
            eyes = ((np.abs(xx - cx + 30) < 12) | (np.abs(xx - cx - 30) < 12)) & (np.abs(yy - cy + 25) < 6)
            v = np.where(head & eyes, 60, v)
            v = np.where(head & (np.abs(xx - cx) < 28) & (np.abs(yy - cy - 45) < 5), 80, v)
            v = np.where((yy > 400) & (np.abs(xx - cx) < 200 - (yy - 400)), 70, v)
            h2 = ((x2 - cx) / 80) ** 2 + ((y2 - cy) / 105) ** 2 < 1
            y = v + (rng.normal(0, noise, v.shape) if noise else 0)
            f.write(np.clip(y + 0.5, 0, 255).astype(np.uint8).tobytes() + np.where(h2, 110, 128).astype(np.uint8).tobytes() + np.where(h2, 150, 128).astype(np.uint8).tobytes())
    return path, sw, sh


def posterize(src, sw, sh, w, h, name, hold):
    out = os.path.join(tmp, name)
    subprocess.run([exe, src, str(sw), str(sh), str(w), str(h), "8", "2", str(hold), "1", "1", "1", "0", out], check=True, capture_output=True)
    lab = np.fromfile(out + ".lab", np.uint8).reshape(PICS, h, w)
    pal = np.fromfile(out + ".pal", np.uint8).reshape(PICS, -1, 3).astype(np.uint32)
    argb = 0xff000000 | pal[:, :, 0] << 16 | pal[:, :, 1] << 8 | pal[:, :, 2]
    return np.take_along_axis(argb[:, None, :], lab.reshape(PICS, 1, -1).astype(np.int64), axis=2).reshape(PICS, h, w).astype(np.uint32), pal, out + ".i420"


def enlarge(pics, factor, tol):
    """Runs smooth_upscale in a child process, so the sanitizer build of the library can be loaded."""
    n, h, w = pics.shape
    a, b = os.path.join(tmp, "in.raw"), os.path.join(tmp, "out.raw")
    pics.tofile(a)
    code = ("import ctypes,numpy as np,sys\nL=ctypes.CDLL(sys.argv[1]);n,h,w,s,tol=map(int,sys.argv[4:9])\n"
            "src=np.fromfile(sys.argv[2],np.uint32).reshape(n,h,w);dst=np.zeros((n,h*s,w*s),np.uint32)\n"
            "for i in range(n): L.smooth_upscale(src[i].ctypes.data_as(ctypes.c_void_p),w,h,s,tol,dst[i].ctypes.data_as(ctypes.c_void_p))\n"
            "dst.tofile(sys.argv[3])")
    subprocess.run([sys.executable, "-c", code, lib, a, b, str(n), str(h), str(w), str(factor), str(tol)], check=True,
                   env={**os.environ, "LD_PRELOAD": asan, "ASAN_OPTIONS": "detect_leaks=0"})
    return np.fromfile(b, np.uint32).reshape(n, h * factor, w * factor)


def rgb(p):
    return np.stack([(p >> 16) & 255, (p >> 8) & 255, p & 255], -1).astype(np.int32)


def skin(pics, pal):
    """Pixels nearer to the skin colour (the brightest palette entry that is clearly red against blue) than to any other palette colour."""
    out = []
    for p, pl in zip(pics, pal.astype(np.int32)):
        d = ((rgb(p)[:, :, None, :] - pl[None, None]) ** 2).sum(-1)
        out.append(d.argmin(-1) == np.where(pl[:, 0] - pl[:, 2] > 30, pl[:, 0], -1).argmax())
    return np.array(out)


def decoded(i420, w, h, args):
    """The clip through a video coder and back to ARGB the way Player.toArgb does it."""
    out = i420 + ".mkv"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{w}x{h}", "-framerate", "2", "-i", i420, *args, out], check=True, capture_output=True)
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", out, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"], capture_output=True, check=True).stdout
    f = np.frombuffer(raw, np.uint8).reshape(PICS, -1).astype(np.int32)
    y = 298 * (f[:, :w * h].reshape(PICS, h, w) - 16)
    u = np.kron(f[:, w * h:w * h * 5 // 4].reshape(PICS, h // 2, w // 2) - 128, np.ones((1, 2, 2), np.int32))
    v = np.kron(f[:, w * h * 5 // 4:].reshape(PICS, h // 2, w // 2) - 128, np.ones((1, 2, 2), np.int32))
    r, g, b = [np.clip(c >> 8, 0, 255).astype(np.uint32) for c in (y + 409 * v + 128, y - 100 * u - 208 * v + 128, y + 516 * u + 128)]
    return (0xff000000 | r << 16 | g << 8 | b).astype(np.uint32)


fails = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails.append(what)


src2, sw2, sh2 = camera(2, 0, "truth")
truth, tpal, _ = posterize(src2, sw2, sh2, 640, 480, "truth", 0)
tskin = skin(truth, tpal)
src1, sw1, sh1 = camera(1, 2.5, "cam")
print(f"{'picture':42s} {'shown as':>8s} {'face':>7s}")
res, tiles = {}, []
for (w, h, s) in ((160, 120, 4), (320, 240, 2)):
    pics, pal, i420 = posterize(src1, sw1, sh1, w, h, f"p{w}", 12)
    cases = [(f"{w}x{h}, as filtered", pics, 0)]
    if w == 160 and shutil.which("ffmpeg"):
        cases.append((f"{w}x{h}, after H.264 (x264 step 40)", decoded(i420, w, h, ["-c:v", "libx264", "-preset", "veryslow", "-crf", "40", "-g", "20"]), 40))
        cases.append((f"{w}x{h}, after AV1 (svt-av1 step 45)", decoded(i420, w, h, ["-c:v", "libsvtav1", "-preset", "4", "-crf", "45", "-g", "20"]), 40))
    for name, p, tol in cases:
        blocks = np.kron(p, np.ones((1, s, s), np.uint32))
        smooth = enlarge(p, s, tol)
        for kind, big in (("blocks", blocks), ("smooth", smooth)):
            m = {"face": float((skin(big, pal) != tskin).sum() / tskin.sum())}
            res[name, kind] = m
            print(f"{name:42s} {kind:>8s} {100 * m['face']:6.2f}%")
        if w == 160:
            tiles += [blocks[6], smooth[6]]
    if w == 160:
        exact = enlarge(pics, s, 0)
        colours = {int(c) for c in np.unique(pics)}
        flat_share = float(np.isin(exact, list(colours)).mean())
        check(flat_share > 0.9, f"{100 * flat_share:.1f}% of the enlarged pixels are plain palette colours; only pixels on a border are mixed")
for name in sorted({k[0] for k in res}):
    a, b = res[name, "blocks"], res[name, "smooth"]
    check(b["face"] < a["face"], f"{name}: the face is nearer the truth ({100 * a['face']:.2f}% to {100 * b['face']:.2f}% of its pixels differ)")
if len(sys.argv) > 1:
    import cv2
    crop = lambda p: rgb(p)[100:330, 190:470][:, :, ::-1].astype(np.uint8)
    cv2.imwrite(sys.argv[1], np.vstack([np.hstack([crop(t) for t in tiles[i:i + 2]] + [crop(truth[6])]) for i in range(0, len(tiles), 2)]))
print("FAILED" if fails else "all checks passed")
sys.exit(1 if fails else 0)
