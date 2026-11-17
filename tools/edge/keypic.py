#!/usr/bin/env python3
"""PLAN-edge.md task 1.2: the key picture. A drawing: a one-pixel edge map plus a small posterized colour map.

BYTE LAYOUT of a key picture (for a C port); all sizes in bytes, bit 0 = least significant:
  0        flags      bit 0: edge map coded with the arithmetic coder (else zstd); bit 1: the same for the colour map
  1        ew / 4     edge map width = 4 * this   (160 -> 40)
  2        eh / 4     edge map height             (120 -> 30)
  3        cw         colour map width            (32)
  4        ch         colour map height           (24)
  5        k          number of colours, 1..16
  6        palette    3 * k bytes, R G B
           varint     length L of the edge payload (LEB128: 7 bits a byte, low first, high bit = more)
           edge map   L bytes
           colour map the rest of the bytes
Payloads:
  zstd:  the map packed into bytes, rows left to right, top to bottom, most significant bit first (edge map: 1 bit a pixel, rows
         padded to a whole byte; colour map: 4 bits a pixel, high nibble first, rows padded), compressed as a zstd frame at
         level 19 with no content size, checksum or dictionary id.
  arith: the arithmetic coder of tools/coder/flatcoder.py (imported, not copied), `_pixels` with k = 2 (edge map) or k (colour
         map), as for a key picture there, no header byte: the Encoder's finish() bytes.
Edge map: 1 = edge. Colour map: palette indexes.
The size of the picture that was seen is not in the file; the receiver draws at any scale (render_key).

Functions: make_maps(rgb, ...) -> edges, cmap, palette; pack_key / unpack_key; encode_key / decode_key; render_key.
Run this file: round-trip test on 20 made-up frames."""
import os, sys
import numpy as np
import cv2
import zstandard

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.join(HERE, "..", "coder"))
import flatcoder  # noqa: E402

ZC = zstandard.ZstdCompressor(level=19, write_content_size=False, write_checksum=False, write_dict_id=False)
ZD = zstandard.ZstdDecompressor()
CANNY = (30, 80)      # thresholds after the bilateral filter; measured in 1.9 together with the rest (parameter, not a fixed choice)


# ---- the maps ---------------------------------------------------------------------------------------------------------------

def _thin(e):
    """Zhang-Suen thinning of a 0/1 uint8 map, until nothing changes (at most 10 rounds)."""
    e = e.astype(np.uint8).copy()
    for _ in range(10):
        changed = False
        for step in (0, 1):
            p = np.pad(e, 1)
            P2, P3, P4, P5, P6, P7, P8, P9 = (p[:-2, 1:-1], p[:-2, 2:], p[1:-1, 2:], p[2:, 2:], p[2:, 1:-1], p[2:, :-2], p[1:-1, :-2], p[:-2, :-2])
            B = P2.astype(int) + P3 + P4 + P5 + P6 + P7 + P8 + P9
            seq = [P2, P3, P4, P5, P6, P7, P8, P9, P2]
            A = sum(((seq[i] == 0) & (seq[i + 1] == 1)).astype(int) for i in range(8))
            if step == 0:
                c = (P2 * P4 * P6 == 0) & (P4 * P6 * P8 == 0)
            else:
                c = (P2 * P4 * P8 == 0) & (P2 * P6 * P8 == 0)
            kill = (e == 1) & (B >= 2) & (B <= 6) & (A == 1) & c
            if kill.any():
                e[kill] = 0
                changed = True
        if not changed:
            break
    return e


def edge_map(rgb, size=(160, 120), canny=CANNY):
    """Bilateral filter, Canny, thinned; 1 = edge. size = (w, h)."""
    small = cv2.resize(rgb, size, interpolation=cv2.INTER_AREA)
    g = cv2.cvtColor(small, cv2.COLOR_RGB2GRAY)
    g = cv2.bilateralFilter(g, 5, 40, 3)
    return _thin(cv2.Canny(g, canny[0], canny[1]) > 0)


def median_cut(pix, k):
    """pix: (n,3) float. Returns k x 3 palette (uint8) by median cut: split the box with most variance along its widest channel."""
    boxes = [pix]
    while len(boxes) < k:
        i = max(range(len(boxes)), key=lambda j: len(boxes[j]) * (boxes[j].max(0) - boxes[j].min(0)).max() if len(boxes[j]) > 1 else -1)
        b = boxes[i]
        if len(b) < 2 or (b.max(0) - b.min(0)).max() == 0:
            break
        ch = int(np.argmax(b.max(0) - b.min(0)))
        b = b[np.argsort(b[:, ch], kind="stable")]
        m = len(b) // 2
        boxes[i:i + 1] = [b[:m], b[m:]]
    return np.clip(np.array([b.mean(0) for b in boxes]) + 0.5, 0, 255).astype(np.uint8)


def colour_map(rgb, size=(32, 24), k=16):
    """The picture shrunk to `size`, posterized to k colours: (labels uint8 [h,w], palette uint8 [k,3])."""
    small = cv2.resize(rgb, size, interpolation=cv2.INTER_AREA)
    pix = small.reshape(-1, 3).astype(np.float64)
    pal = median_cut(pix, k)
    d = ((pix[:, None, :] - pal[None, :, :].astype(np.float64)) ** 2).sum(2)
    return np.argmin(d, 1).astype(np.uint8).reshape(size[1], size[0]), pal


def make_maps(rgb, size=(160, 120), cmap_size=(32, 24), k=16, canny=CANNY):
    cm, pal = colour_map(rgb, cmap_size, k)
    return edge_map(rgb, size, canny), cm, pal


# ---- coding of the maps -----------------------------------------------------------------------------------------------------

def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _read_varint(d, pos):
    n = s = 0
    while True:
        b = d[pos]; pos += 1
        n |= (b & 0x7F) << s; s += 7
        if not b & 0x80:
            return n, pos


def _pack_edge_bits(e):
    return np.packbits(e.astype(np.uint8), axis=1).tobytes()


def _pack_nibbles(c):
    h, w = c.shape
    c = np.pad(c, ((0, 0), (0, w & 1)))
    return (c[:, 0::2] << 4 | c[:, 1::2]).tobytes()


def _enc_map(m, k, how):
    """Returns (bytes, used) for a 2-D uint8 map; how in zstd/arith/best."""
    h, w = m.shape
    out = {}
    if how in ("zstd", "best"):
        raw = _pack_edge_bits(m) if k == 2 else _pack_nibbles(m)
        out["zstd"] = ZC.compress(raw)
    if how in ("arith", "best"):
        e = flatcoder.Encoder()
        flatcoder._pixels(e, [int(v) for v in m.ravel()], w, h, k)
        out["arith"] = e.finish()
    name = min(out, key=lambda n: len(out[n]))
    return out[name], name == "arith"


def _dec_map(data, w, h, k, arith):
    if arith:
        lab = [0] * (w * h)
        flatcoder._pixels(flatcoder.Decoder(data, 0), lab, w, h, k)
        return np.array(lab, np.uint8).reshape(h, w)
    raw = ZD.decompress(data, max_output_size=w * h)
    if k == 2:
        rb = (w + 7) // 8
        return np.unpackbits(np.frombuffer(raw, np.uint8).reshape(h, rb), axis=1)[:, :w]
    a = np.frombuffer(raw, np.uint8).reshape(h, (w + 1) // 2)
    out = np.empty((h, a.shape[1] * 2), np.uint8)
    out[:, 0::2], out[:, 1::2] = a >> 4, a & 15
    return out[:, :w]


def pack_key(edges, cmap, palette, coder="best"):
    """-> bytes. coder: 'zstd', 'arith' or 'best' (the smaller one per map). Also returns nothing else: see key_parts for sizes."""
    eh, ew = edges.shape
    ch, cw = cmap.shape
    k = len(palette)
    eb, ea = _enc_map(edges, 2, coder)
    cb, ca = _enc_map(cmap, k, coder)
    head = bytes([ea | ca << 1, ew // 4, eh // 4, cw, ch, k]) + bytes(np.asarray(palette, np.uint8).ravel())
    return head + _varint(len(eb)) + eb + cb


def key_parts(data):
    """Sizes of the parts of a packed key picture: dict header (with palette and the length field), edges, cmap."""
    k = data[5]
    pos = 6 + 3 * k
    n, pos2 = _read_varint(data, pos)
    return {"header": pos2, "edges": n, "cmap": len(data) - pos2 - n}


def unpack_key(data):
    """-> edges uint8[eh,ew], cmap uint8[ch,cw], palette uint8[k,3]"""
    fl, ew, eh, cw, ch, k = data[0] & 1, data[1] * 4, data[2] * 4, data[3], data[4], data[5]
    pal = np.frombuffer(data[6:6 + 3 * k], np.uint8).reshape(k, 3)
    n, pos = _read_varint(data, 6 + 3 * k)
    edges = _dec_map(data[pos:pos + n], ew, eh, 2, fl & 1)
    cmap = _dec_map(data[pos + n:], cw, ch, k, (data[0] >> 1) & 1)
    return edges, cmap, pal


def encode_key(rgb, size=(160, 120), cmap_size=(32, 24), k=16, coder="best", canny=CANNY):
    return pack_key(*make_maps(rgb, size, cmap_size, k, canny), coder)


def decode_key(data):
    return unpack_key(data)


# ---- the receiver's drawing -------------------------------------------------------------------------------------------------

def render_key(edges, cmap, palette, out_size=(640, 480), line=(20, 20, 20)):
    """Colours enlarged by joint bilateral upsampling guided by the edge map: a colour sample only counts for a pixel in the same
    region (the same piece of the picture when the edges are walls), so a colour does not bleed across an edge. Weight of a
    sample = gaussian of its distance (sigma 1.2 colour-map cells) x (1 if in the same region else 0); where no sample of the
    region is near, the plain smooth enlargement is used. The edges are then drawn over it as dark lines."""
    ow, oh = out_size
    ch, cw = cmap.shape
    eh, ew = edges.shape
    col = palette[cmap].astype(np.float32)                                    # cw x ch x 3
    wall = cv2.resize(edges.astype(np.uint8), (ow, oh), interpolation=cv2.INTER_LINEAR if ow % ew else cv2.INTER_NEAREST)
    if ow % ew == 0:
        wall = cv2.dilate(cv2.resize(edges.astype(np.uint8), (ow, oh), interpolation=cv2.INTER_NEAREST),
                          np.ones((max(1, ow // ew // 2), max(1, ow // ew // 2)), np.uint8))
    wall = wall > 0
    _, reg = cv2.connectedComponents((~wall).astype(np.uint8), connectivity=4)
    base = cv2.resize(col, (ow, oh), interpolation=cv2.INTER_LINEAR)
    num = np.zeros((oh, ow, 3), np.float32)
    den = np.zeros((oh, ow), np.float32)
    cs_x, cs_y = ow / cw, oh / ch
    R = 2
    sig2 = (1.2 ** 2) * 2
    for j in range(ch):
        for i in range(cw):
            cx, cy = (i + 0.5) * cs_x, (j + 0.5) * cs_y
            x0, x1 = max(0, int(cx - R * cs_x)), min(ow, int(cx + R * cs_x) + 1)
            y0, y1 = max(0, int(cy - R * cs_y)), min(oh, int(cy + R * cs_y) + 1)
            r_here = reg[min(oh - 1, int(cy)), min(ow - 1, int(cx))]
            if r_here == 0:                                                   # the sample sits on an edge pixel: use a free neighbour
                found = False
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        yy, xx = min(oh - 1, max(0, int(cy) + dy * 2)), min(ow - 1, max(0, int(cx) + dx * 2))
                        if reg[yy, xx]:
                            r_here, found = reg[yy, xx], True
                            break
                    if found:
                        break
                if not found:
                    continue
            yy, xx = np.mgrid[y0:y1, x0:x1]
            wgt = np.exp(-(((xx - cx) / cs_x) ** 2 + ((yy - cy) / cs_y) ** 2) / sig2) * (reg[y0:y1, x0:x1] == r_here)
            num[y0:y1, x0:x1] += wgt[..., None] * col[j, i]
            den[y0:y1, x0:x1] += wgt
    out = np.where(den[..., None] > 1e-3, num / np.maximum(den, 1e-6)[..., None], base)
    # a posterized look: snap to the nearest palette colour
    d = ((out[:, :, None, :] - palette[None, None, :, :].astype(np.float32)) ** 2).sum(3)
    out = palette[np.argmin(d, 2)].astype(np.uint8)
    out[wall] = line
    return out


# ---- test --------------------------------------------------------------------------------------------------------------------

def _selftest():
    import clips
    frames = clips.load("made")
    tot = {"edges": 0, "cmap": 0, "header": 0}
    for coder in ("zstd", "arith", "best"):
        sizes = []
        for f in range(0, 480, 24):
            rgb = np.ascontiguousarray(frames[f])
            edges, cm, pal = make_maps(rgb)
            data = pack_key(edges, cm, pal, coder)
            e2, c2, p2 = unpack_key(data)
            assert np.array_equal(e2, edges), f"edge map differs ({coder}, frame {f})"
            assert np.array_equal(c2, cm), f"colour map differs ({coder}, frame {f})"
            assert np.array_equal(p2, pal), "palette differs"
            sizes.append(key_parts(data))
        m = {n: np.mean([s[n] for s in sizes]) for n in sizes[0]}
        print(f"{coder:6s} 20 frames round trip exact; mean bytes: header {m['header']:.0f}, edge map {m['edges']:.0f}, colour map {m['cmap']:.0f}, total {sum(m.values()):.0f}")
    r = render_key(edges, cm, pal)
    assert r.shape == (480, 640, 3)
    cv2.imwrite(os.path.join(HERE, "out", "keypic_test.png"), cv2.cvtColor(np.hstack([rgb, r]), cv2.COLOR_RGB2BGR))
    print("ok: rendered out/keypic_test.png")


if __name__ == "__main__":
    sys.path.insert(0, HERE)
    _selftest()
