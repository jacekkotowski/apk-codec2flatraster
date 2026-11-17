#!/usr/bin/env python3
"""PLAN-edge.md task 1.5 (second part): patches. A patch is a small key picture of a box, sent when what the receiver would show
(the last picture warped) differs too much from what the camera sees there.

BYTE LAYOUT of a patch payload:
  0   x0 / 4     left edge of the box in coded pixels / 4
  1   y0 / 4     top edge
  2.. a key picture (keypic.py layout) of the box: edge map w x h (multiples of 4) cropped from the camera frame's edge map,
      colour map max(2, w//5) x max(2, h//5), k colours (8).
The receiver pastes the drawing of the box (render_key at the render scale) over what it shows, and from then on the picture it
shows is the new base picture for warping, with the points of that moment as its key points (codec.py does this; the sender runs
the same steps, so both stay the same).

Decision (sender, per motion picture, at most one patch a second, at most 300 bytes): for the box of each object (face, hands
present now or in the base picture), render what a patch would show and compare with the warped base in grey levels: mean absolute
difference inside the box above THRESH (10, 20 or 30; measured in 1.9). The box with the largest difference wins.
Run this file: patches must round-trip exactly."""
import os, sys
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import keypic  # noqa: E402

SCALE = 4
THRESH = 20
MAX_BYTES = 300
PATCH_K = 8


def snap_box(pts_list, size, margin=0.25, minsize=16):
    """Box (x0, y0, w, h) in coded pixels, multiples of 4, around the points of all arrays in pts_list, inside the picture `size` = (ew, eh)."""
    p = np.vstack(pts_list)
    x0, y0 = p.min(0); x1, y1 = p.max(0)
    mx, my = max(margin * (x1 - x0), 2), max(margin * (y1 - y0), 2)
    x0, y0, x1, y1 = x0 - mx, y0 - my, x1 + mx, y1 + my
    ew, eh = size
    x0 = max(0, int(np.floor(x0 / 4)) * 4); y0 = max(0, int(np.floor(y0 / 4)) * 4)
    x1 = min(ew, int(np.ceil(x1 / 4)) * 4); y1 = min(eh, int(np.ceil(y1 / 4)) * 4)
    w, h = max(minsize, x1 - x0), max(minsize, y1 - y0)
    x0 = min(x0, ew - w); y0 = min(y0, eh - h)
    return int(max(0, x0)), int(max(0, y0)), int(min(w, ew)), int(min(h, eh))


def patch_maps(rgb, edges_full, box, scale=SCALE, k=PATCH_K):
    x0, y0, w, h = box
    crop = rgb[y0 * scale:(y0 + h) * scale, x0 * scale:(x0 + w) * scale]
    cm, pal = keypic.colour_map(crop, (max(2, w // 5), max(2, h // 5)), k)
    return edges_full[y0:y0 + h, x0:x0 + w].copy(), cm, pal


def pack_patch(box, edges, cm, pal, coder="best"):
    return bytes([box[0] // 4, box[1] // 4]) + keypic.pack_key(edges, cm, pal, coder)


def unpack_patch(data):
    e, c, p = keypic.unpack_key(data[2:])
    return (data[0] * 4, data[1] * 4, e.shape[1], e.shape[0]), e, c, p


def render_patch(box, edges, cm, pal, scale=SCALE):
    x0, y0, w, h = box
    return keypic.render_key(edges, cm, pal, (w * scale, h * scale))


def apply_patch(frame, box, drawing, scale=SCALE):
    out = frame.copy()
    x0, y0, w, h = box
    out[y0 * scale:(y0 + h) * scale, x0 * scale:(x0 + w) * scale] = drawing
    return out


def choose_patch(warped, rgb, edges_full, cur_pts, base_pts, thresh=THRESH, scale=SCALE, coder="best"):
    """The sender's rule. Returns (patch bytes, box, the new picture) or None. `warped` is what the receiver shows now."""
    ew, eh = edges_full.shape[1], edges_full.shape[0]
    cands = []
    for o in range(3):
        sets = [p for p in (cur_pts[o], base_pts[o]) if p is not None]
        if sets:
            cands.append(snap_box(sets, (ew, eh)))
    best = None
    for box in cands:
        e, cm, pal = patch_maps(rgb, edges_full, box, scale)
        d = render_patch(box, e, cm, pal, scale)
        x0, y0, w, h = box
        a = cv2.cvtColor(warped[y0 * scale:(y0 + h) * scale, x0 * scale:(x0 + w) * scale], cv2.COLOR_RGB2GRAY).astype(np.float32)
        b = cv2.cvtColor(d, cv2.COLOR_RGB2GRAY).astype(np.float32)
        diff = float(np.abs(a - b).mean())
        if diff > thresh and (best is None or diff > best[0]):
            best = (diff, box, e, cm, pal)
    if best is None:
        return None
    _, box, e, cm, pal = best
    data = pack_patch(box, e, cm, pal, coder)
    if len(data) > MAX_BYTES:
        return None
    # what the receiver will show: decode what was sent (identical to e, cm, pal, checked in the test), paste its drawing
    box2, e2, c2, p2 = unpack_patch(data)
    return data, box2, apply_patch(warped, box2, render_patch(box2, e2, c2, p2, scale), scale)


def _selftest():
    import clips
    frames = clips.load("made")
    sizes = []
    for f in range(0, 480, 40):
        rgb = np.ascontiguousarray(frames[f])
        edges = keypic.edge_map(rgb)
        for box in ((40, 20, 60, 72), (0, 0, 16, 16), (144, 104, 16, 16), (60, 40, 32, 48)):
            e, c, p = patch_maps(rgb, edges, box)
            for coder in ("zstd", "arith", "best"):
                data = pack_patch(box, e, c, p, coder)
                box2, e2, c2, p2 = unpack_patch(data)
                assert box2 == box and np.array_equal(e2, e) and np.array_equal(c2, c) and np.array_equal(p2, p), f"patch differs {box} {coder}"
            sizes.append(len(data))
        # the render of the received patch equals the render of the sent one
        assert np.array_equal(render_patch(box, e, c, p), render_patch(*unpack_patch(data)))
    print(f"ok: {len(sizes)} patches round-trip exactly with all three coders and render the same; mean size {np.mean(sizes):.0f} B, largest {max(sizes)} B")


if __name__ == "__main__":
    _selftest()
