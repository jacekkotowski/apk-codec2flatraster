#!/usr/bin/env python3
"""The C landmark coder (jni/motion.c) against its Python original (tools/edge/motion.py): identical bytes both ways, with made-up walking
points (face 22 and body 33 points, objects that vanish and return, jumps that need the escape code, both predictors), under ASan/UBSan;
and cut or corrupted data must not crash."""
import ctypes, os, random, subprocess, sys, tempfile
if "libasan" not in os.environ.get("LD_PRELOAD", ""):
    asan = subprocess.run(["gcc", "-print-file-name=libasan.so"], capture_output=True, text=True).stdout.strip()
    os.execvpe(sys.executable, [sys.executable] + sys.argv, dict(os.environ, LD_PRELOAD=asan, ASAN_OPTIONS="detect_leaks=0"))
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools", "edge"))
import motion  # noqa: E402

so = os.path.join(tempfile.mkdtemp(), "motion.so")
subprocess.run(["gcc", "-O1", "-g", "-Wall", "-Wextra", "-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-shared", "-fPIC", "-o", so,
                os.path.join(HERE, "..", "jni", "motion.c"), "-lm"], check=True)
L = ctypes.CDLL(so)
L.motion_create.restype = ctypes.c_void_p
for f in ("motion_encode", "motion_decode"):
    getattr(L, f).restype = ctypes.c_int
P32 = ctypes.POINTER(ctypes.c_int32)


def run(nobj_sizes, alpha, seed):
    rnd = random.Random(seed)
    motion.N = tuple(nobj_sizes); motion.ALPHA = alpha
    nn = (ctypes.c_int * len(nobj_sizes))(*nobj_sizes)
    cenc, cdec = ctypes.c_void_p(L.motion_create(len(nobj_sizes), nn, alpha)), ctypes.c_void_p(L.motion_create(len(nobj_sizes), nn, alpha))
    penc, pdec = motion.MotionEncoder(0.5), motion.MotionDecoder(0.5)
    pos = [np.array([[40 + 3 * (j % 5) + 4 * (o + 1), 30 + 2 * (j // 5) + 10 * o] for j in range(n)], float) for o, n in enumerate(nobj_sizes)]
    total = 0
    for i in range(160):
        if i % 50 == 0:
            L.motion_reset(cenc); L.motion_reset(cdec); penc.reset(); pdec.reset()
        cur = []
        for o, n in enumerate(nobj_sizes):
            pos[o] += rnd.gauss(0, 0.6) + np.array([rnd.gauss(0, 0.4) for _ in range(2)]) + np.array([[rnd.gauss(0, 0.3), rnd.gauss(0, 0.3)] for _ in range(n)])
            if rnd.random() < 0.04: pos[o] += rnd.choice([-1, 1]) * rnd.uniform(8, 60)       # a jump: the escape code
            if i % 9 == 0: pos[o][0] = [rnd.uniform(0, 160), rnd.uniform(0, 120)]            # an off-pattern point
            cur.append(None if rnd.random() < 0.12 else np.clip(pos[o], 0, 159.5).copy())
        pdata = penc.encode(cur)
        present = (ctypes.c_int32 * len(cur))(*[0 if c is None else 1 for c in cur])
        units = np.concatenate([np.rint(c / 0.5).astype(np.int32).ravel() for c in cur if c is not None] or [np.zeros(0, np.int32)])
        units = np.ascontiguousarray(units if len(units) else np.zeros(2, np.int32))
        out = ctypes.create_string_buffer(4096)
        n = L.motion_encode(cenc, present, units.ctypes.data_as(P32), int(units.size), out, 4096)
        assert n >= 0 and out.raw[:n] == pdata, f"picture {i}: C bytes differ from Python ({n} against {len(pdata)})"
        total += n
        # C decodes Python's bytes, Python decodes C's bytes
        pr2 = (ctypes.c_int32 * len(cur))(); u2 = (ctypes.c_int32 * (sum(nobj_sizes) * 2))()
        buf = ctypes.create_string_buffer(pdata, len(pdata))
        assert L.motion_decode(cdec, buf, len(pdata), pr2, u2) == 0
        got = list(u2)[:int(units.size)] if sum(pr2) else []
        assert list(pr2) == list(present) and got == [int(x) for x in units][:len(got)] and (not sum(pr2) or len(got) == units.size), f"picture {i}: C decode differs"
        pdec.decode(out.raw[:n])
    return total


def main():
    for sizes in ((22, 33), (22, 21, 21), (5,)):
        for alpha in (0, 1):
            t = run(sizes, alpha, 7 + alpha)
            print(f"ok   objects {sizes}, predictor {alpha}: 160 pictures, {t} bytes, C and Python identical both ways")
    # damage
    nn = (ctypes.c_int * 2)(22, 33)
    d = ctypes.c_void_p(L.motion_create(2, nn, 0))
    rnd = random.Random(3)
    pr = (ctypes.c_int32 * 2)(); u = (ctypes.c_int32 * 110)()
    for _ in range(2000):
        data = bytes(rnd.randrange(256) for _ in range(rnd.randrange(0, 80)))
        L.motion_decode(d, ctypes.create_string_buffer(data, max(1, len(data))), len(data), pr, u)
        if rnd.random() < 0.01: L.motion_reset(d)
    print("ok   2000 random byte strings decode or fail without crashing")
    pr1 = (ctypes.c_int32 * 2)(1, 1); short = (ctypes.c_int32 * 10)()
    assert L.motion_encode(d, pr1, short, 10, ctypes.create_string_buffer(64), 64) == -1
    print("ok   too few input values are refused, not read past")


if __name__ == "__main__":
    main()
