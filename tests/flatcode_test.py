#!/usr/bin/env python3
"""The C coder (jni/flatcode.c) against its Python original (tools/coder/flatcoder.py): the bytes must be identical, in both directions.
Made-up maps (blobs, noise, one colour, palette change) and, when numpy and gcc are there, the real filter's pictures from hold_test's scene.
Also a damage test: cut and corrupted data must decode to something or fail, never crash (run under ASan/UBSan)."""
import ctypes, os, random, subprocess, sys, tempfile
if "libasan" not in os.environ.get("LD_PRELOAD", ""):     # the sanitizer runtime must come first when Python loads the C library
    asan = subprocess.run(["gcc", "-print-file-name=libasan.so"], capture_output=True, text=True).stdout.strip()
    os.execvpe(sys.executable, [sys.executable] + sys.argv, dict(os.environ, LD_PRELOAD=asan, ASAN_OPTIONS="detect_leaks=0"))
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools", "coder"))
import flatcoder  # noqa: E402

tmp = tempfile.mkdtemp()
so = os.path.join(tmp, "flatcode.so")
subprocess.run(["gcc", "-O1", "-g", "-Wall", "-Wextra", "-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-shared", "-fPIC", "-o", so,
                os.path.join(HERE, "..", "jni", "flatcode.c")], check=True)
L = ctypes.CDLL(so)
U8 = ctypes.c_char_p
for f in ("flatcode_encode_key", "flatcode_encode_delta", "flatcode_decode_key", "flatcode_decode_delta"):
    getattr(L, f).restype = ctypes.c_int


def c_key(lab, w, h, pal):
    out = ctypes.create_string_buffer(w * h * 4 + 4096)
    n = L.flatcode_encode_key(bytes(lab), w, h, bytes(pal), len(pal) // 3, out, len(out))
    assert n >= 0
    return out.raw[:n]


def c_delta(prev, lab, w, h, k, pal):
    out = ctypes.create_string_buffer(w * h * 4 + 4096)
    n = L.flatcode_encode_delta(bytes(prev), bytes(lab), w, h, k, bytes(pal) if pal else None, out, len(out))
    assert n >= 0
    return out.raw[:n]


def c_dkey(data, w, h):
    lab, pal = ctypes.create_string_buffer(w * h), ctypes.create_string_buffer(96)
    k = L.flatcode_decode_key(data, len(data), w, h, lab, pal)
    return k, lab.raw, pal.raw[:3 * k if k > 0 else 0]


def c_ddelta(data, prev, w, h, k):
    lab, pal = ctypes.create_string_buffer(w * h), ctypes.create_string_buffer(96)
    r = L.flatcode_decode_delta(data, len(data), bytes(prev), w, h, k, lab, pal)
    return r, lab.raw, pal.raw[:3 * k] if r == 1 else None


def check_clip(labs, pals, w, h, key_every, what):
    py = flatcoder.encode_clip(labs, pals, w, h, key_every)
    total = 0
    for n, (is_key, data) in enumerate(py):
        k = len(pals[n]) // 3
        cdata = c_key(labs[n], w, h, pals[n]) if is_key else c_delta(labs[n - 1], labs[n], w, h, k, pals[n] if pals[n] != pals[n - 1] else None)
        assert cdata == data, f"{what}: picture {n}: C bytes differ from Python ({len(cdata)} against {len(data)})"
        if is_key:
            kk, lab, pal = c_dkey(data, w, h)
            assert kk == k and lab == bytes(labs[n]) and pal == bytes(pals[n]), f"{what}: C decode of key {n} differs"
        else:
            r, lab, pal = c_ddelta(data, labs[n - 1], w, h, k)
            assert lab == bytes(labs[n]) and (pal == bytes(pals[n]) if r == 1 else pals[n] == pals[n - 1]), f"{what}: C decode of delta {n} differs"
        total += len(data)
    print(f"ok   {what}: {len(py)} pictures, {total} bytes, C and Python identical, C decodes Python's bytes")
    return py


def main():
    rnd = random.Random(1)
    w, h, k = 48, 36, 8
    pal = bytes(rnd.randrange(256) for _ in range(3 * k))

    def blobs(shift):
        return bytes((((x + shift) // 9) + (y // 7) * 3 + (1 if (x - 20 - shift) ** 2 + (y - 18) ** 2 < 90 else 0)) % k for y in range(h) for x in range(w))
    labs = [blobs(s) for s in (0, 0, 1, 3)] + [bytes(rnd.randrange(k) for _ in range(w * h))]
    labs += [labs[-1], bytes(w * h)]
    pals = [pal] * 4 + [bytes(reversed(pal))] * 3
    for ke in (1, 3, 100):
        py = check_clip(labs, pals, w, h, ke, f"made-up maps, key every {ke}")
    for kk in (2, 4, 16, 32):                                   # other numbers of colours, odd sizes
        ww, hh = 37, 29
        p2 = bytes(rnd.randrange(256) for _ in range(3 * kk))
        l2 = [bytes(((x * (n + 1)) // 5 + y // 4) % kk if rnd.random() > 0.05 else rnd.randrange(kk) for y in range(hh) for x in range(ww)) for n in range(5)]
        check_clip(l2, [p2] * 5, ww, hh, 4, f"{kk} colours, 37x29")
    # real pictures from the filter (hold_test's scene), when numpy is there
    try:
        import numpy as np
        sys.path.insert(0, HERE)
        import hold_test as ht
        src, faces = ht.make_clip()
        r = ht.run(src, faces, "real", **ht.T, coarse=3, box=0.40)
        n = 20
        labs = [bytes(x) for x in r["lab"][:n].reshape(n, -1)]
        pals = [bytes(x) for x in r["pal"][:n]]
        check_clip(labs, pals, ht.W, ht.H, 10, "filter pictures, 320x240")
    except ImportError:
        print("skip real pictures (no numpy)")
    # damage: cut and flipped bytes must not crash
    data = py[0][1]
    for cut in range(0, len(data), max(1, len(data) // 30)):
        c_dkey(data[:cut], w, h)
    for _ in range(300):
        bad = bytearray(data)
        for _ in range(rnd.randrange(1, 4)):
            bad[rnd.randrange(len(bad))] ^= 1 << rnd.randrange(8)
        c_dkey(bytes(bad), w, h)
    dd = [d for key, d in py if not key][0]
    for _ in range(300):
        bad = bytearray(dd)
        bad[rnd.randrange(len(bad))] ^= 1 << rnd.randrange(8)
        c_ddelta(bytes(bad), labs[0] if False else bytes(w * h), w, h, k)
    print("ok   damaged and cut data decode or fail without crashing")


if __name__ == "__main__":
    main()
