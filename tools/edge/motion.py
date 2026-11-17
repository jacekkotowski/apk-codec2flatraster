#!/usr/bin/env python3
"""PLAN-edge.md task 1.4: the motion picture. Landmarks of one picture, coded from the last two.

Three objects per picture by default (N; the puppet stream of the app sets N = (22, 33): face and body), in this order: face (22 points, marks.FACE_IDX), hand 0 and hand 1 (21 points each; hands sorted by wrist x).
Coordinates are in pixels of the coded picture (the 160x120 edge map), i.e. camera pixels / `scale` (4 for 640x480 -> 160x120).
Everything inside is integers in units of `step` coded pixels (0.5 by default): u = round(x / step); the decoder returns u * step, so every
decoded point is within step/2 of the input in each axis.

BYTE LAYOUT of one motion record payload (the record's type and length are the container's business, see PLAN-edge.md 2.1): one
arithmetic-coded stream (tools/coder/flatcoder.py Encoder, no header) of these symbols, for object o in face, hand0, hand1:
  present      1 symbol (2 values); context: object, was it present in the last picture
  if present and not present before ("appears"):
     point 0 absolute: x_u, then y_u, each 1 symbol of a 1024-value uniform model (values above 1023 are clipped: the picture is at
     most 160 coded pixels wide, 640 units at step 1/4);
     points 1..n-1: each is coded relative to the point before it in this object (residual in units, as below)
  if present and present before:
     prediction of each point: the same point in the last picture (variant, ALPHA = 1: last + round((last - the one before) / 2), which
     measured 5 to 12% worse at 2 pictures a second, so PLAN-edge.md section 3's constant speed is not the default); residual r = u - prediction; the object's shift S = round(mean r) per axis is sent first
     (axis x then y), then each point's r - S, x then y.
  A residual is a symbol of the alphabet -T..T (symbol q + T) and ESC (2T+1); T = 10. After ESC: the number of extra bits b (symbol, 16
  values, model shared) then the value's |q| - T - 1 in b bits (each bit a symbol of a 2-value model that never adapts), then the sign
  (one symbol, 2 values). Models: per (axis, hand or face, min(2, |previous residual of the same axis in this object|)); they start with
  a prior peaked at 0 and live from one key picture to the next one (reset at each K): a motion record cannot be decoded without the
  motion records between it and its key picture.
The key picture carries its own landmark set: encoded with the same function with no "last" at all (all objects "appear").

Classes MotionEncoder / MotionDecoder keep the models and the last two decoded pictures; encode_motion / decode_motion are the
stateless wrappers asked for in the plan (prev2, prev1, cur) -> bytes, with fresh models.
Run this file: self-test on the made-up clip, then (if present) the real clips."""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.join(HERE, "..", "coder"))
import flatcoder  # noqa: E402

N = (22, 21, 21)
T = 10
ESC = 2 * T + 1


def _prior():
    m = flatcoder.Model(2 * T + 2)
    m.f = [1 + int(round(40 * math.exp(-abs(s - T) / 1.2))) if s < 2 * T + 1 else 1 for s in range(2 * T + 2)]
    m.tot = sum(m.f)
    return m


class _Models:
    def __init__(self):
        self.res = {}
        self.pres = {(o, w): flatcoder.Model(2) for o in range(8) for w in (0, 1)}
        self.nbits = flatcoder.Model(16)
        self.sign = flatcoder.Model(2)

    def r(self, axis, kind, c):
        k = (axis, kind, c)
        if k not in self.res:
            self.res[k] = _prior()
        return self.res[k]


class _Static:                       # a model that never learns (flatcoder's Encoder/Decoder call .update)
    def __init__(self, n):
        self.f, self.tot = [1] * n, n

    def update(self, s):
        pass


def _res(coder, md, axis, kind, c, q):
    """Codes one residual (encoder: q given; decoder: q ignored). Returns q."""
    enc = isinstance(coder, flatcoder.Encoder)
    m = md.r(axis, kind, c)
    if enc:
        s = q + T if -T <= q <= T else ESC
    else:
        s = None
    s = coder.symbol(m, s)
    if s < ESC:
        return s - T
    if enc:
        v = abs(q) - T - 1
        b = v.bit_length()
        coder.symbol(md.nbits, min(b, 15))
        for i in range(b - 1, -1, -1):
            coder.symbol(_Static(2), (v >> i) & 1)
        coder.symbol(md.sign, int(q < 0))
        return q
    b = coder.symbol(md.nbits)
    v = 0
    for _ in range(b):
        v = (v << 1) | coder.symbol(_Static(2))
    neg = coder.symbol(md.sign)
    return -(v + T + 1) if neg else v + T + 1


def _code(coder, md, last, last2, cur_u):
    """One picture. last, last2, cur_u: lists of 3 (None or int array [n,2], units). The encoder passes cur_u; the decoder
    passes None. Returns the decoded list (units)."""
    enc = cur_u is not None
    out = []
    for o in range(len(N)):
        n = N[o]
        kind = 0 if o == 0 else 1
        had = last[o] is not None
        pres = coder.symbol(md.pres[(o, int(had))], int(cur_u[o] is not None) if enc else None)
        if not pres:
            out.append(None)
            continue
        pts = np.zeros((n, 2), np.int64)
        if not had:
            for a in range(2):
                v = coder.symbol(_Static(1024), min(1023, max(0, int(cur_u[o][0, a]))) if enc else None)
                pts[0, a] = v
            prevq = [0, 0]
            for j in range(1, n):
                for a in range(2):
                    q = _res(coder, md, a, 2 + kind, min(2, abs(prevq[a])), int(cur_u[o][j, a] - pts[j - 1, a]) if enc else 0)
                    pts[j, a] = pts[j - 1, a] + q
                    prevq[a] = q
        else:
            p1 = last[o]
            p2 = last2[o]
            pred = p1 + (np.rint(0.5 * (p1 - p2)).astype(np.int64) if p2 is not None else 0) if ALPHA else p1
            r = (cur_u[o] - pred) if enc else None
            S = [0, 0]
            for a in range(2):
                S[a] = _res(coder, md, a, 4 + kind, 0, int(np.rint(r[:, a].mean())) if enc else 0)
            prevq = [0, 0]
            for j in range(n):
                for a in range(2):
                    q = _res(coder, md, a, kind, min(2, abs(prevq[a])), int(r[j, a]) - S[a] if enc else 0)
                    pts[j, a] = pred[j, a] + S[a] + q
                    prevq[a] = q
        out.append(pts)
    return out


ALPHA = 0   # 0: prediction = last position (default: 5 to 12% fewer bytes than constant speed at 2 pictures a second, measured 2026-11-17); 1: last + 1/2 of the last step


class MotionEncoder:
    def __init__(self, step=0.5):
        self.step = step
        self.reset()

    def reset(self):
        self.md = _Models()
        self.last = [None] * len(N)
        self.last2 = [None] * len(N)

    def encode(self, cur):
        """cur: list of 3 (None or float array [n,2] in coded pixels). Returns bytes; updates the state (decoded values)."""
        cu = [None if c is None else np.rint(np.asarray(c, np.float64) / self.step).astype(np.int64) for c in cur]
        e = flatcoder.Encoder()
        dec = _code(e, self.md, self.last, self.last2, cu)
        self.last2, self.last = self.last, dec
        return e.finish()

    def decoded(self):
        return [None if p is None else p * self.step for p in self.last]


class MotionDecoder:
    def __init__(self, step=0.5):
        self.step = step
        self.reset()

    reset = MotionEncoder.reset

    def decode(self, data):
        d = flatcoder.Decoder(data, 0)
        dec = _code(d, self.md, self.last, self.last2, None)
        self.last2, self.last = self.last, dec
        return [None if p is None else p * self.step for p in dec]


def encode_motion(prev2, prev1, cur, step=0.5):
    """Stateless form: prev2, prev1, cur as lists of 3 (None or [n,2] coded pixels), previous pictures as the receiver has them
    (already within step/2). Fresh models, so the cost is higher than in a clip."""
    e = MotionEncoder(step)
    q = lambda l: [None if c is None else np.rint(np.asarray(c, np.float64) / step).astype(np.int64) for c in l]
    en = flatcoder.Encoder()
    _code(en, e.md, q(prev1), q(prev2), q(cur))
    return en.finish()


def decode_motion(prev2, prev1, data, step=0.5):
    d = MotionDecoder(step)
    q = lambda l: [None if c is None else np.rint(np.asarray(c, np.float64) / step).astype(np.int64) for c in l]
    out = _code(flatcoder.Decoder(data, 0), d.md, q(prev1), q(prev2), None)
    return [None if p is None else p * step for p in out]


def points_of(marks, f, scale=4.0):
    """The 3 objects of frame f of a marks.npz dict, in coded pixels."""
    return [marks["fpts"][f] / scale if marks["face"][f] else None] + [marks["hpts"][f, h] / scale if marks["hand"][f, h] else None for h in range(2)]


def run_clip(marks, per_second=2, step=0.5, key_every=None, fps=16, scale=4.0):
    """Codes the motion pictures of a clip (every fps/per_second frame; the first picture is the key's point set, coded with no
    'last'), checks the decode. Returns (list of byte counts of motion pictures, key bytes)."""
    enc, dec = MotionEncoder(step), MotionDecoder(step)
    sizes, keyb = [], 0
    stride = fps // per_second
    for n, f in enumerate(range(0, len(marks["face"]), stride)):
        cur = points_of(marks, f, scale)
        if n == 0 or (key_every and n % (key_every * per_second) == 0):
            enc.reset(); dec.reset()
            first = True
        else:
            first = False
        data = enc.encode(cur)
        got = dec.decode(data)
        for a, b in zip(cur, got):
            assert (a is None) == (b is None), "presence differs"
            if a is not None:
                assert np.abs(a - b).max() <= step / 2 + 1e-9, f"point off by {np.abs(a - b).max()} at frame {f}"
        if first:
            keyb += len(data)
        else:
            sizes.append(len(data))
    return sizes, keyb


def main():
    import clips
    print(f"{'clip':6s} {'step':>5s} {'/s':>3s} {'motion records':>14s} {'mean bytes':>11s} {'bytes/30 s':>11s} {'key points B':>13s}")
    for n in [x for x in ["made"] + list(clips.SOURCES) if os.path.exists(os.path.join(clips.OUT, x + ".marks.npz"))]:
        m = dict(np.load(os.path.join(clips.OUT, n + ".marks.npz")))
        for step in (0.5, 1.0, 0.25):
            for ps in (2, 4):
                s, k = run_clip(m, ps, step)
                print(f"{n:6s} {step:5.2f} {ps:3d} {len(s):14d} {np.mean(s):11.1f} {np.sum(s):11d} {k:13d}")
    print("ok: every decoded point is within step/2 of the input")


if __name__ == "__main__":
    sys.path.insert(0, HERE)
    main()
