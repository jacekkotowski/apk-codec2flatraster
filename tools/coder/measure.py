#!/usr/bin/env python3
"""Stage 6a (ROADMAP.md): bytes needed for the same posterized pictures by the prototype coder (flatcoder.py) and by
AV1, HEVC and H.264 encoders on a computer. Run clips.py first. Needs numpy and ffmpeg with libx264, libx265, libsvtav1, libaom.

For every clip and coder: number and mean size of key pictures, mean size of delta pictures, total bytes, the bytes for 30 s
(three full pictures and the delta pictures between them, from those means), and how true the decoded picture is:
  PSNR    of the brightness plane against the posterized picture (the prototype is exact, so it has none)
  off     share of pixels whose brightness or colour is more than 10 of 255 away from the posterized picture after decoding:
          pixels a viewer sees in a different shade. The prototype has none.
A full picture comes every 10 s, as in the app and in the phone self-test, for every coder.
The video encoders are the ones tools/bench.py uses, with its quality steps. Their bytes are the encoder's packets
(no container, no stream header), which is what the phone self-test counted. The AV1 encoders keep some pictures back
and send them together with a later one, so their smallest packets are 3 bytes and their largest hold several pictures;
the mean per delta picture is still right.
"""
import json, os, subprocess, sys, time
from multiprocessing import Pool
import numpy as np
import flatcoder
from clips import CLIPS, OUT, W, H

KEY_SECONDS = 10
# name, ffmpeg arguments with {q} (quality step) and {g} (pictures between full pictures), quality steps
ENCODERS = [
    ("H.264 x264 veryslow", ["-c:v", "libx264", "-preset", "veryslow", "-crf", "{q}", "-g", "{g}", "-keyint_min", "{g}", "-sc_threshold", "0",
                             "-bsf:v", "filter_units=remove_types=6"], [30, 40, 48]),          # the filter drops x264's text about itself
    ("HEVC x265 slow", ["-c:v", "libx265", "-preset", "slow", "-crf", "{q}",
                        "-x265-params", "keyint={g}:min-keyint={g}:scenecut=0:info=0:log-level=none"], [30, 40, 48]),
    ("AV1 svt-av1 preset 4", ["-c:v", "libsvtav1", "-preset", "4", "-crf", "{q}", "-g", "{g}", "-svtav1-params", "scd=0"], [35, 45, 55]),
    ("AV1 libaom cpu 4", ["-c:v", "libaom-av1", "-cpu-used", "4", "-crf", "{q}", "-b:v", "0", "-g", "{g}", "-keyint_min", "{g}"], [35, 45, 55]),
]
# The settings FlatRaster's earlier measurements used (tests/flat_test.py: svt-av1 45, x265 40); these rows are the comparison.
REFERENCE = {"H.264 x264 veryslow": 40, "HEVC x265 slow": 40, "AV1 svt-av1 preset 4": 45, "AV1 libaom cpu 4": 45}


def load(name):
    lab = np.fromfile(os.path.join(OUT, name + ".lab"), np.uint8).reshape(-1, H, W)
    pal = np.fromfile(os.path.join(OUT, name + ".pal"), np.uint8).reshape(len(lab), -1)
    yuv = np.fromfile(os.path.join(OUT, name + ".i420"), np.uint8).reshape(len(lab), -1)
    return lab, pal, yuv


def off_share(dec, yuv):
    """Share of pixels whose brightness, or whose colour (kept per 2x2 block), is more than 10 of 255 away from the encoder's input."""
    n = W * H
    d = np.abs(dec.astype(np.int16) - yuv)
    y = d[:, :n].reshape(-1, H, W) > 10
    c = (np.maximum(d[:, n:n * 5 // 4], d[:, n * 5 // 4:]) > 10).reshape(-1, H // 2, W // 2)
    return float((y | np.kron(c, np.ones((1, 2, 2), bool))).mean())


def lzma_sizes(labs, pals, fps):
    """The estimate tools/bench.py made earlier: the index map packed with lzma, and for delta pictures the xor with the previous map."""
    import lzma
    pack = lambda b: len(lzma.compress(b, preset=9 | lzma.PRESET_EXTREME))
    keys = [i % (KEY_SECONDS * fps) == 0 for i in range(len(labs))]
    sizes = [pack(labs[i]) + len(pals[i]) if keys[i] else pack(bytes(p ^ q for p, q in zip(labs[i], labs[i - 1]))) for i in range(len(labs))]
    return sizes, keys


def summary(sizes, keys, n, fps):
    k = [s for s, key in zip(sizes, keys) if key]
    d = [s for s, key in zip(sizes, keys) if not key]
    total = sum(sizes)
    return {"pictures": len(sizes), "keys": len(k), "key": float(np.mean(k)) if k else 0, "delta": float(np.mean(d)) if d else 0,
            "delta_min": min(d) if d else 0, "delta_max": max(d) if d else 0, "total": total,
            # 30 s as the app would record it: a full picture every 10 s, delta pictures between them
            "per30": (30 // KEY_SECONDS) * np.mean(k) + (30 * fps - 30 // KEY_SECONDS) * (np.mean(d) if d else 0)}


def prototype(name):
    fps = CLIPS[name][0]
    lab, pal, _ = load(name)
    labs, pals = [bytes(l) for l in lab.reshape(len(lab), -1)], [bytes(p) for p in pal]
    t = time.time()
    coded = flatcoder.encode_clip(labs, pals, W, H, KEY_SECONDS * fps)
    t_enc = time.time() - t
    t = time.time()
    exact = flatcoder.decode_clip(coded, W, H) == (labs, pals)
    r = summary([len(d) for _, d in coded], [key for key, _ in coded], len(labs), fps)
    r.update(exact=exact, psnr=None, off=0.0, enc_s=t_enc, dec_s=time.time() - t)
    z = summary(*lzma_sizes(labs, pals, fps), len(labs), fps)
    z.update(exact=True, psnr=None, off=0.0)
    return r, z


def video(args):
    name, enc, eargs, q = args
    fps = CLIPS[name][0]
    lab, _, yuv = load(name)
    out = os.path.join(OUT, f"{name}_{enc.split()[1]}_{q}.mkv")
    src = ["-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}", "-framerate", str(fps), "-i", os.path.join(OUT, name + ".i420")]
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *src, *[a.format(q=q, g=KEY_SECONDS * fps) for a in eargs], out]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        return {"error": r.stderr[-300:]}
    rows = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=size,flags", "-of", "csv=p=0", out],
                          capture_output=True, text=True, check=True).stdout.split()
    sizes = [int(x.split(",")[0]) for x in rows]
    keys = ["K" in x.split(",")[1] for x in rows]
    res = summary(sizes, keys, len(lab), fps)
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", out, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"],
                         capture_output=True, check=True).stdout
    dec = np.frombuffer(raw, np.uint8).reshape(-1, W * H * 3 // 2)
    os.remove(out)
    if len(dec) != len(lab):
        return {"error": f"{len(dec)} pictures decoded of {len(lab)}"}
    mse = np.mean((dec[:, :W * H].astype(np.float64) - yuv[:, :W * H]) ** 2)
    res.update(exact=False, psnr=float(10 * np.log10(255 ** 2 / mse)) if mse else None, off=off_share(dec, yuv))
    return res


def main():
    names = sys.argv[1:] or list(CLIPS)
    jobs = [(n, e, a, q) for n in names for e, a, qs in ENCODERS for q in qs]
    with Pool() as pool:
        proto = pool.map_async(prototype, names)
        vids = pool.map_async(video, jobs)
        proto, vids = proto.get(), vids.get()
    results = {}
    for n, (p, z) in zip(names, proto):
        lab = load(n)[0]
        results[n] = {"fps": CLIPS[n][0], "pictures": len(lab), "changed": float(np.mean([(lab[i] != lab[i - 1]).mean() for i in range(1, len(lab))])),
                      "rows": [("Prototype", None, p), ("Index map packed with lzma (the earlier estimate in tools/bench.py)", None, z)]}
    for (n, e, _, q), r in zip(jobs, vids):
        results[n]["rows"].append((e, q, r))
    for n in names:
        c = results[n]
        print(f"\n### {n}: {c['pictures']} pictures at {c['fps']} a second ({c['pictures'] / c['fps']:.1f} s), "
              f"{100 * c['changed']:.2f}% of pixels change per picture\n")
        print("| Coder | Quality step | Full pictures | Bytes per full picture | Bytes per delta picture (least to most) | Total bytes | Bytes per 30 s | PSNR dB | Pixels off |")
        print("|---|---|---|---|---|---|---|---|---|")
        for e, q, r in c["rows"]:
            if "error" in r:
                print(f"| {e} | {q} | FAILED: {r['error']} |")
                continue
            ref = " (reference)" if q is not None and REFERENCE.get(e) == q else ""
            print(f"| {e}{ref} | {q if q is not None else 'exact'} | {r['keys']} | {r['key']:.0f} | {r['delta']:.0f} ({r['delta_min']} to {r['delta_max']}) | "
                  f"{r['total']} | {r['per30']:.0f} | {'%.1f' % r['psnr'] if r['psnr'] else 'exact'} | {100 * r['off']:.2f}% |")
        p = c["rows"][0][2]
        print(f"\nPrototype: every picture decoded to exactly the input: {'yes' if p['exact'] else 'NO'}. "
              f"Python took {p['enc_s']:.0f} s to code and {p['dec_s']:.0f} s to decode.")
    with open(os.path.join(OUT, "results.json"), "w") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
