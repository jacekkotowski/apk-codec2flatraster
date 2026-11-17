#!/usr/bin/env python3
"""PLAN-edge.md task 1.6: the whole clip. encode_clip(frames, marks, params) -> records; decode_clip(records, params) -> frames.

Time is in "slots": one motion picture per slot, `per_second` a second (2 or 4); camera frames are at 16 a second, so a slot is
stride = 16 / per_second frames. Records, in stream order (type, slot, payload); a record's slot is implicit in the file (the order):
  K  slot 0, and at a cut:   varint(len(key picture)) + key picture (keypic.py) + the key's landmark set (motion.py, no "last")
  M  every other slot:       motion.py payload (models and "last" reset at each K)
  P  after an M, at most one a second: patch.py payload
A slot has either K, or M (+ optional P). Without warp (variant E7) there are no M records: only K and P.
What the receiver shows (Receiver): base picture (the drawing of the last K, with the last P pasted over it) and the landmarks that
belong to it (base_pts). M gives the landmarks of the slot, the picture is base warped from base_pts to them (warp.py). A P pastes a
box and makes the result the new base, with the landmarks of that slot as base_pts. Between two motion pictures the screen
interpolates the landmarks linearly (never past the last received one: so a frame between M_n and M_n+1 is made when M_n+1 is there).
decode_clip returns the frames for the screen at 8 a second and, for each, a flag: 'K' a key picture just arrived, 'P' a patch just
arrived (in both the frame is real, at least in part), 'W' warped or interpolated (not seen by the camera), 'H' held (E7: the last real
picture, no movement). write_video marks 'W' frames with an orange frame border in the file; the frames themselves have no border.
The sender mirrors the receiver (same class, fed with the bytes it wrote), so what it decides on is what the receiver has.
Cut rule: a block comparison of the receiver's picture against what a key picture of the camera frame would show (20x15 blocks, grey,
more than half differ by more than 25): a new K. A timer ("every 10 s", E5) is a variant.
Run: python3 codec.py CLIP [VARIANT ...]  (e.g. C1 E1): prints bytes per type per 30 s and writes out/CLIP.VARIANT.edge.mp4"""
import json, os, subprocess, sys, time
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import clips, keypic, motion, warp, patch  # noqa: E402

FPS = 16
BASE = dict(size=(160, 120), cmap=(32, 24), k=16, coder="best", per_second=2, step=0.5, key_every=None, patches=True, warp=True,
            thresh=20, canny=keypic.CANNY, scale=4, cut_blocks=0.5, cut_diff=25, patch_gap=1.0)
VARIANTS = {
    "E1": {},
    "E2": dict(per_second=4),
    "E3": dict(size=(128, 96), k=8),
    "E4": dict(size=(96, 72), cmap=(16, 12), k=8),
    "E5": dict(key_every=10),
    "E6": dict(patches=False),
    "E7": dict(warp=False),
}


def params_for(name, **over):
    p = dict(BASE); p.update(VARIANTS[name]); p.update(over)
    p["stride"] = FPS // p["per_second"]
    return p


def _varint(n):
    return keypic._varint(n)


def pts_of(marks, f, scale):
    return motion.points_of(marks, f, scale)       # lists in coded pixels of the *camera* scale/scale; the caller passes the coded scale


class Receiver:
    """What the receiver has and shows. Fed with records one by one."""
    def __init__(self, p):
        self.p = p
        self.md = motion.MotionDecoder(p["step"])
        self.base = None
        self.base_pts = [None] * 3
        self.slots = []          # per slot: dict(base, base_pts, cur, kind)  (state after the slot's records)
        self.render_scale = (p["size"][0] * p["scale"], p["size"][1] * p["scale"])

    def key(self, payload):
        n, pos = keypic._read_varint(payload, 0)
        e, c, pal = keypic.unpack_key(payload[pos:pos + n])
        self.base = keypic.render_key(e, c, pal, self.render_scale)
        self.md.reset()
        pts = self.md.decode(payload[pos + n:])
        self.base_pts = pts
        self.slots.append(dict(base=self.base, base_pts=pts, cur=pts, kind="K"))

    def motion(self, payload):
        cur = self.md.decode(payload)
        self.slots.append(dict(base=self.base, base_pts=self.base_pts, cur=cur, kind="W"))
        return cur

    def warped(self, cur):
        return warp.render_motion(self.base, self.base_pts, cur, self.p["scale"]) if self.p["warp"] else self.base

    def patch(self, payload):
        box, e, c, pal = patch.unpack_patch(payload)
        cur = self.slots[-1]["cur"] if self.p["warp"] else self.base_pts
        shown = patch.apply_patch(self.warped(cur), box, patch.render_patch(box, e, c, pal, self.p["scale"]), self.p["scale"])
        self.base = shown
        self.base_pts = cur
        s = self.slots[-1]
        s["base"], s["base_pts"], s["kind"] = shown, cur, "P"

    def hold_slot(self):         # E7: a slot without any record
        self.slots.append(dict(base=self.base, base_pts=self.base_pts, cur=self.base_pts, kind="H"))


def _key_record(rgb, marks_f, p):
    maps = keypic.make_maps(rgb, p["size"], p["cmap"], p["k"], p["canny"])
    kb = keypic.pack_key(*maps, p["coder"])
    return kb, maps


def encode_clip(frames, marks, params, log=None):
    """frames: (n,480,640,3). Returns records [(type, slot, bytes)] and the Receiver mirror."""
    p = params
    sc = p["scale"]
    stride = p["stride"]
    n_slots = len(frames) // stride
    cscale = 640 / p["size"][0]           # camera pixels per coded pixel
    recs = []
    rx = Receiver(p)
    me = motion.MotionEncoder(p["step"])
    last_p = -10 ** 9
    last_key = 0
    motion.ALPHA = 1 if p["per_second"] >= 4 else 0
    for n in range(n_slots):
        f = n * stride
        rgb = np.ascontiguousarray(frames[f])
        cur_true = motion.points_of(marks, f, cscale)
        key_now = n == 0 or (p["key_every"] and n - last_key >= p["key_every"] * p["per_second"])
        edges = None
        if not key_now:
            edges = keypic.edge_map(rgb, p["size"], p["canny"])
            cm, pal = keypic.colour_map(rgb, p["cmap"], p["k"])
            ideal = keypic.render_key(edges, cm, pal, p["size"])             # one render pixel per coded pixel: cheap
            shown = cv2.resize(rx.warped(cur_true) if p["warp"] else rx.base, p["size"], interpolation=cv2.INTER_AREA)
            a = cv2.resize(cv2.cvtColor(shown, cv2.COLOR_RGB2GRAY), (20, 15), interpolation=cv2.INTER_AREA).astype(np.float32)
            b = cv2.resize(cv2.cvtColor(ideal, cv2.COLOR_RGB2GRAY), (20, 15), interpolation=cv2.INTER_AREA).astype(np.float32)
            if (np.abs(a - b) > p["cut_diff"]).mean() > p["cut_blocks"]:
                key_now = True
        if key_now:
            kb, maps = _key_record(rgb, cur_true, p)
            me.reset()
            pb = me.encode(cur_true)
            rec = _varint(len(kb)) + kb + pb
            recs.append(("K", n, rec))
            rx.key(rec)
            last_key, last_p = n, n
            continue
        if p["warp"]:
            mb = me.encode(cur_true)
            recs.append(("M", n, mb))
            cur = rx.motion(mb)
        else:
            rx.hold_slot()
            cur = cur_true
        if p["patches"] and (n - last_p) >= p["patch_gap"] * p["per_second"]:
            warped = rx.warped(cur) if p["warp"] else rx.base
            sel = patch.choose_patch(warped, rgb, edges, cur if p["warp"] else _quant(cur_true, p), rx.base_pts, p["thresh"], sc, p["coder"])
            if sel is not None:
                recs.append(("P", n, sel[0]))
                rx.patch(sel[0])
                last_p = n
    return recs, rx


def _quant(pts, p):
    """E7 has no motion records, so the sender's own points are used for boxes only (the receiver never sees them)."""
    return pts


def decode_clip(records, params):
    """-> frames uint8 (n_out,480,640,3) at 8 a second, flags list of 'K','P','W','H'."""
    p = params
    rx = Receiver(p)
    by_slot = {}
    for t, s, b in records:
        by_slot.setdefault(s, []).append((t, b))
    n_slots = max(by_slot) + 1 if by_slot else 0
    last = max(by_slot) if by_slot else -1
    for s in range(n_slots):
        recs = by_slot.get(s)
        if recs is None:
            rx.hold_slot()
            continue
        for t, b in recs:
            if t == "K":
                rx.key(b)
            elif t == "M":
                rx.motion(b)
            elif t == "P":
                rx.patch(b)
    return render_screen(rx, p)


def render_screen(rx, p, n_total=None):
    stride = p["stride"]
    out, flags = [], []
    S = rx.slots
    for s, slot in enumerate(S):
        for j in range(0, stride, 2):             # 8 a second: camera frames 0, 2, 4, ...
            if j == 0:
                if slot["kind"] in ("K", "P"):
                    out.append(slot["base"]); flags.append(slot["kind"])
                elif slot["kind"] == "H":
                    out.append(slot["base"]); flags.append("H")
                else:
                    out.append(warp.render_motion(slot["base"], slot["base_pts"], slot["cur"], p["scale"])); flags.append("W")
                continue
            if not p["warp"]:
                out.append(slot["base"]); flags.append("H")
                continue
            if s + 1 >= len(S):                    # never past the last received motion picture
                break
            a = j / stride
            nxt = S[s + 1]["cur"]
            pts = [None if c is None else (c if q is None else (1 - a) * c + a * q) for c, q in zip(slot["cur"], nxt)]
            out.append(warp.render_motion(slot["base"], slot["base_pts"], pts, p["scale"])); flags.append("W")
    return np.array(out), flags


def write_video(path, frames, flags):
    """Lossless (x264rgb crf 0), 8 a second. 'W' frames get an orange border 3 pixels wide in the FILE only."""
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{frames.shape[2]}x{frames.shape[1]}", "-r", "8",
           "-i", "-", "-c:v", "libx264rgb", "-crf", "0", "-preset", "veryfast", path]
    pr = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for fr, fl in zip(frames, flags):
        fr = fr.copy()
        if fl == "W":
            fr[:3] = fr[-3:] = fr[:, :3] = fr[:, -3:] = (255, 140, 0)
        pr.stdin.write(fr.tobytes())
    pr.stdin.close(); pr.wait()


def summary(recs, p, seconds=30.0):
    by = {"K": [0, 0], "M": [0, 0], "P": [0, 0]}
    for t, _, b in recs:
        by[t][0] += 1; by[t][1] += len(b)
    tot = sum(v[1] for v in by.values())
    nrec = sum(v[0] for v in by.values())
    return dict(by=by, total=tot, records=nrec, with_headers=tot + 2 * nrec)


def run(clip, variant, video=True, **over):
    p = params_for(variant, **over)
    frames = clips.load(clip)
    marks = dict(np.load(os.path.join(clips.OUT, clip + ".marks.npz")))
    t0 = time.time()
    recs, mirror = encode_clip(frames, marks, p)
    t1 = time.time()
    dec, flags = decode_clip(recs, p)
    # the sender's mirror and the decoder must agree (same bytes in)
    assert len(mirror.slots) == len(decode_slots(recs, p)), "slot count differs"
    s = summary(recs, p)
    if video:
        write_video(os.path.join(clips.OUT, f"{clip}.{variant}.edge.mp4"), dec, flags)
    return p, recs, dec, flags, s, (t1 - t0, time.time() - t1)


def decode_slots(recs, p):
    rx = Receiver(p)
    bs = {}
    for t, s, b in recs:
        bs.setdefault(s, []).append((t, b))
    for s in range(max(bs) + 1):
        if s not in bs:
            rx.hold_slot(); continue
        for t, b in bs[s]:
            {"K": rx.key, "M": rx.motion, "P": rx.patch}[t](b)
    return rx.slots


def main():
    clip = sys.argv[1] if len(sys.argv) > 1 else "made"
    variants = sys.argv[2:] or ["E1"]
    print(f"{'clip':5s} {'var':4s} {'K n/B':>10s} {'M n/B':>10s} {'P n/B':>10s} {'total B':>8s} {'+2B hdr':>8s}  warped%  s(enc/dec)")
    for v in variants:
        p, recs, dec, flags, s, tm = run(clip, v)
        b = s["by"]
        print(f"{clip:5s} {v:4s} {b['K'][0]:3d}/{b['K'][1]:<6d} {b['M'][0]:3d}/{b['M'][1]:<6d} {b['P'][0]:3d}/{b['P'][1]:<6d} {s['total']:8d} {s['with_headers']:8d}  {100 * flags.count("W") / len(flags):6.1f}  {tm[0]:.0f}/{tm[1]:.0f}")


if __name__ == "__main__":
    main()
