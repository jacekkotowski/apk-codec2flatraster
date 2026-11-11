#!/usr/bin/env python3
"""Phase 0 benchmark: posterize a clip, then compare how many bytes each coder needs.

Needs: numpy, opencv-python(-headless), ffmpeg with libx264/libx265/libsvtav1/libaom.
Usage: bench.py [--clip in.mp4] [--fps 3] [--seconds 20] [--width 320] [--colors 8]
Without --clip a synthetic test clip is used (moving shaded shapes on a gradient with
sensor-like noise). Synthetic results only show trends: rerun with real footage.
"""
import argparse, lzma, os, subprocess, sys, tempfile, zlib
import cv2
import numpy as np


def synth_clip(n, w, h, fps):
    rng = np.random.default_rng(1)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    frames = []
    for i in range(n):
        t = i / fps
        bg = np.stack([60 + 90 * xx / w, 90 + 70 * yy / h, 140 - 50 * xx / w], -1)
        img = bg.copy()
        cx, cy = w * (0.2 + 0.6 * (t % 10) / 10), h * (0.5 + 0.2 * np.sin(t))
        cv2.circle(img, (int(cx), int(cy)), h // 5, (220, 70, 60), -1)
        cv2.rectangle(img, (int(w * 0.6 - 10 * t % w), int(h * 0.15)),
                      (int(w * 0.6 - 10 * t % w) + w // 4, int(h * 0.15) + h // 3),
                      (40, 200, 90), -1)
        shade = 1 - 0.25 * (yy / h)
        img = img * shade[..., None]
        img += rng.normal(0, 6, img.shape)
        frames.append(np.clip(img, 0, 255).astype(np.uint8))
    return frames


def read_clip(path, n, w, fps):
    cap = cv2.VideoCapture(path)
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30
    step = max(1, round(src_fps / fps))
    frames, i = [], 0
    while len(frames) < n:
        ok, f = cap.read()
        if not ok:
            break
        if i % step == 0:
            h = int(f.shape[0] * w / f.shape[1]) // 2 * 2
            frames.append(cv2.resize(f, (w, h), interpolation=cv2.INTER_AREA))
        i += 1
    return frames


class Posterizer:
    """Edge-preserving smooth -> temporally stable palette -> small-region cleanup."""

    def __init__(self, colors, smooth):
        self.k, self.smooth, self.centers = colors, smooth, None

    def __call__(self, bgr):
        f = bgr
        for _ in range(self.smooth):
            f = cv2.bilateralFilter(f, 7, 40, 7)
        lab = cv2.cvtColor(f, cv2.COLOR_BGR2LAB).astype(np.float32)
        flat = lab.reshape(-1, 3)
        if self.centers is None:
            sub = flat[:: max(1, len(flat) // 4000)]
            crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 0.5)
            _, _, c = cv2.kmeans(sub, self.k, None, crit, 3, cv2.KMEANS_PP_CENTERS)
            self.centers = c
        # palette is locked after the first frame -> static areas stay bit-identical
        d = ((flat[:, None, :] - self.centers[None]) ** 2).sum(-1)
        labels = d.argmin(1).astype(np.uint8).reshape(lab.shape[:2])
        labels = cv2.medianBlur(labels, 5)  # drops tiny regions
        return labels

    def render(self, labels):
        lab = self.centers[labels].astype(np.uint8)
        return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print("FAILED:", " ".join(cmd), r.stderr[-400:], file=sys.stderr)
    return r


def psnr(ref_dir, enc, fps):
    r = run(["ffmpeg", "-hide_banner", "-i", enc, "-framerate", str(fps), "-i",
             f"{ref_dir}/%04d.png", "-lavfi", "[0:v]scale=in_range=auto:out_range=tv,format=yuv420p[a];[1:v]scale=in_range=pc:out_range=tv,format=yuv420p[b];[a][b]psnr", "-f", "null", "-"])
    for line in r.stderr.splitlines():
        if "average:" in line:
            return line.split("average:")[1].split()[0]
    return "?"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip")
    ap.add_argument("--fps", type=float, default=3)
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--width", type=int, default=320)
    ap.add_argument("--colors", type=int, default=8)
    ap.add_argument("--smooth", type=int, default=2)
    a = ap.parse_args()
    n = int(a.fps * a.seconds)
    h = a.width * 3 // 4 // 2 * 2
    frames = read_clip(a.clip, n, a.width, a.fps) if a.clip else synth_clip(n, a.width, h, a.fps)
    p = Posterizer(a.colors, a.smooth)
    labels = [p(f) for f in frames]
    dur = len(frames) / a.fps
    tmp = tempfile.mkdtemp()
    for i, l in enumerate(labels):
        cv2.imwrite(f"{tmp}/{i + 1:04d}.png", p.render(l))
    print(f"{len(frames)} frames, {a.width}x{labels[0].shape[0]}, {a.fps} fps, {a.colors} colours, {dur:.0f} s")
    print(f"{'coder':28s} {'bytes':>8s} {'B/frame':>8s} {'kbps':>7s} {'PSNR':>7s} {'s/frame@2.4k':>12s}")

    def report(name, total, ps="-"):
        bpf = total / len(frames)
        print(f"{name:28s} {total:8d} {bpf:8.0f} {total * 8 / dur / 1000:7.2f} {ps:>7s} {bpf * 8 / 2400:12.1f}")

    inp = ["-framerate", str(a.fps), "-i", f"{tmp}/%04d.png", "-pix_fmt", "yuv420p"]
    # (name, ffmpeg args with {crf}, container, crf values): sweep so PSNR can be matched
    enc = [
        ("x264 veryslow", ["-c:v", "libx264", "-preset", "veryslow", "-crf", "{crf}", "-g", "999"], "mp4", [30, 40, 48]),
        ("x265 slow", ["-c:v", "libx265", "-preset", "slow", "-crf", "{crf}", "-g", "999",
                       "-x265-params", "log-level=none"], "mp4", [30, 40, 48]),
        ("svt-av1 p4", ["-c:v", "libsvtav1", "-preset", "4", "-crf", "{crf}", "-g", "999"], "mp4", [35, 45, 55]),
        ("libaom cpu4", ["-c:v", "libaom-av1", "-cpu-used", "4", "-crf", "{crf}", "-b:v", "0",
                         "-g", "999"], "mp4", [35, 45, 55]),
    ]
    for name, args, ext, crfs in enc:
        for crf in crfs:
            out = f"{tmp}/o_{name.split()[0]}_{crf}.{ext}"
            cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inp,
                   *[x.format(crf=crf) for x in args], out]
            if run(cmd).returncode:
                continue
            report(f"{name} crf{crf}", os.path.getsize(out), psnr(tmp, out, a.fps))

    # custom: keyframe = indexed labels, then only changed pixels vs previous frame
    key = lzma.compress(labels[0].tobytes(), preset=9 | lzma.PRESET_EXTREME)
    deltas = [lzma.compress((labels[i] ^ labels[i - 1]).tobytes(), preset=9 | lzma.PRESET_EXTREME)
              for i in range(1, len(labels))]
    report("custom lzma key+delta (est.)", len(key) + sum(map(len, deltas)), "lossless")
    allkeys = sum(len(zlib.compress(l.tobytes(), 9)) for l in labels)
    report("custom all-keyframes zlib", allkeys, "lossless")
    print(f"keyframe alone: {len(key)} B ({len(key) * 8 / 2400:.1f} s at 2400 bps)")
    print(f"frames in {tmp}")


if __name__ == "__main__":
    main()
