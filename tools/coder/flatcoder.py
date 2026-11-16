#!/usr/bin/env python3
"""Stage 6a (ROADMAP.md 7.4): prototype of a coder made for posterized pictures. Not part of the app.

A posterized picture is a map of palette indexes with large flat areas. This coder sends the map exactly (no loss):

Key picture     1 byte: number of colours k
                3*k bytes: the palette, RGB
                then, arithmetic coded, every pixel in reading order (left to right, top to bottom).
                The four neighbours already sent (left, above, above-left, above-right) give a short list of
                candidate colours and a context: the pattern of which of the four are equal. The coder sends
                "it is candidate number r" or "none of them" followed by the colour itself. Inside a flat area the
                four neighbours agree and the pixel almost always agrees too, so it costs next to nothing; only
                pixels on a border cost bits.

Delta picture   1 byte: flags; bit 0 set: a new palette follows (3*k bytes)
                then, arithmetic coded:
                  the change map, one yes/no per pixel: does it differ from the previous picture? Context: the
                  same four neighbours in the change map, and whether the pixel sat on a border between colours
                  in the previous picture (changes happen at borders; the receiver has that picture).
                  then the new colour of every changed pixel, coded as in a key picture, except that the colour
                  the pixel had before is ruled out.

Whether a picture is a key or a delta picture, and its width and height, are taken from the packet around it, as in
.frv. All probabilities are learned while coding (counts start equal in every picture), so there are no tables, and a
picture needs only the previous one to be decoded.

The arithmetic coder is a plain 32-bit range coder with carry (the scheme LZMA uses), with symbol counts in place of
binary probabilities so that a nearly certain symbol can cost far less than 0.01 bit.

Run this file to test it on made-up maps: every picture must decode to exactly the input.
"""

TOP = 1 << 24
STEP = 32          # added to a symbol's count each time it is seen
LIMIT = 1 << 16    # counts are halved when their sum would pass this (the range coder needs the sum below 2^16)


class Model:
    """Counts of how often each symbol was seen in one context."""
    __slots__ = ("f", "tot")

    def __init__(self, n):
        self.f = [1] * n
        self.tot = n

    def update(self, s):
        self.f[s] += STEP
        self.tot += STEP
        if self.tot > LIMIT - STEP:
            self.f = [(c + 1) >> 1 for c in self.f]
            self.tot = sum(self.f)


class Encoder:
    def __init__(self):
        self.low, self.range, self.cache, self.pending, self.out = 0, 0xFFFFFFFF, 0, 1, bytearray()

    def _shift(self):
        low = self.low
        if low < 0xFF000000 or low > 0xFFFFFFFF:
            carry = low >> 32
            self.out.append((self.cache + carry) & 0xFF)
            for _ in range(self.pending - 1):
                self.out.append((0xFF + carry) & 0xFF)
            self.pending = 0
            self.cache = (low >> 24) & 0xFF
        self.pending += 1
        self.low = (low & 0x00FFFFFF) << 8

    def symbol(self, m, s, skip=()):
        """Sends symbol s with the counts of model m. Symbols in skip are known to be impossible here."""
        f = m.f
        cum, tot = 0, m.tot
        for i in skip:
            tot -= f[i]
        for i in range(s):
            if i not in skip:
                cum += f[i]
        r = self.range // tot
        self.low += r * cum
        self.range = r * f[s]
        while self.range < TOP:
            self.range <<= 8
            self._shift()
        m.update(s)
        return s

    def finish(self):
        # Any number from low to low + range - 1 decodes the same. Take the one ending in zero bytes and leave
        # those off: the decoder reads zeros past the end.
        self.low = (self.low + 0xFFFFFF) & ~0xFFFFFF
        for _ in range(5):
            self._shift()
        out = self.out[1:]  # the first byte is always 0
        while out and out[-1] == 0:
            out.pop()
        return bytes(out)


class Decoder:
    def __init__(self, data, pos):
        self.d, self.pos, self.range, self.code = data, pos, 0xFFFFFFFF, 0
        for _ in range(4):
            self.code = (self.code << 8) | self._next()

    def _next(self):
        b = self.d[self.pos] if self.pos < len(self.d) else 0
        self.pos += 1
        return b

    def symbol(self, m, _s=None, skip=()):
        f = m.f
        tot = m.tot
        for i in skip:
            tot -= f[i]
        r = self.range // tot
        t = min(self.code // r, tot - 1)
        cum, s = 0, 0
        while True:
            if s not in skip:
                if cum + f[s] > t:
                    break
                cum += f[s]
            s += 1
        self.code -= r * cum
        self.range = r * f[s]
        while self.range < TOP:
            self.code = ((self.code << 8) | self._next()) & 0xFFFFFFFF
            self.range <<= 8
        m.update(s)
        return s


def _pixels(coder, lab, w, h, k, changed=None, prev=None):
    """Codes the palette indexes of a picture in reading order; the same code runs in the encoder and the decoder.
    lab is a list: the encoder passes the picture, the decoder a list that is filled in as it goes.
    changed and prev (delta pictures): only pixels marked in changed are coded, and prev[i] is ruled out."""
    models = {}
    other = Model(k)
    for y in range(h):
        row = y * w
        for x in range(w):
            i = row + x
            if changed is not None and not changed[i]:
                continue
            if y == 0:
                a = lab[i - 1] if x else 0
                b = c = d = a
            else:
                b = lab[i - w]
                a = lab[i - 1] if x else b
                c = lab[i - w - 1] if x else b
                d = lab[i - w + 1] if x < w - 1 else b
            cand = [a]
            if b != a:
                cand.append(b)
            if c not in cand:
                cand.append(c)
            if d not in cand:
                cand.append(d)
            n = len(cand)
            ctx = cand.index(b) | cand.index(c) << 2 | cand.index(d) << 4
            skip = ()
            if prev is not None:
                was = prev[i]
                rp = cand.index(was) if was in cand else n
                ctx |= rp << 6
                if rp < n:
                    skip = (rp,)
            m = models.get(ctx)
            if m is None:
                m = models[ctx] = Model(n + 1)
            v = lab[i]
            s = coder.symbol(m, cand.index(v) if v in cand else n, skip)
            if s < n:
                lab[i] = cand[s]
            else:
                if prev is not None and prev[i] not in cand:
                    cand.append(prev[i])
                lab[i] = coder.symbol(other, v, cand)


def _border(prev, w, h):
    """1 where a pixel of the previous picture differs from the pixel left, right, above or below it."""
    e = bytearray(w * h)
    for y in range(h):
        row = y * w
        for x in range(w):
            i = row + x
            v = prev[i]
            if (x and prev[i - 1] != v) or (x < w - 1 and prev[i + 1] != v) or (y and prev[i - w] != v) or (y < h - 1 and prev[i + w] != v):
                e[i] = 1
    return e


def _change_map(coder, ch, prev, w, h, use_border=True):
    """Codes the change map in reading order; the same code runs in the encoder and the decoder."""
    edge = _border(prev, w, h) if use_border else bytes(w * h)
    models = [Model(2) for _ in range(32)]
    for y in range(h):
        row = y * w
        for x in range(w):
            i = row + x
            ctx = edge[i] << 4
            if x:
                ctx |= ch[i - 1]
            if y:
                ctx |= ch[i - w] << 1
                if x:
                    ctx |= ch[i - w - 1] << 2
                if x < w - 1:
                    ctx |= ch[i - w + 1] << 3
            ch[i] = coder.symbol(models[ctx], ch[i])


def encode_key(lab, w, h, palette):
    """lab: w*h palette indexes (bytes). palette: 3 bytes per colour. Returns the coded picture."""
    k = len(palette) // 3
    e = Encoder()
    _pixels(e, list(lab), w, h, k)
    return bytes([k]) + bytes(palette) + e.finish()


def decode_key(data, w, h):
    k = data[0]
    palette = bytes(data[1:1 + 3 * k])
    lab = [0] * (w * h)
    _pixels(Decoder(data, 1 + 3 * k), lab, w, h, k)
    return bytes(lab), palette


def encode_delta(prev, lab, w, h, k, palette=None, use_border=True):
    """prev, lab: the previous and the present picture. palette: only when it has changed."""
    ch = [int(p != q) for p, q in zip(prev, lab)]
    e = Encoder()
    _change_map(e, ch, prev, w, h, use_border)
    _pixels(e, list(lab), w, h, k, ch, prev)
    return bytes([1 if palette else 0]) + (bytes(palette) if palette else b"") + e.finish()


def decode_delta(data, prev, w, h, k, use_border=True):
    pos, palette = 1, None
    if data[0] & 1:
        palette = bytes(data[1:1 + 3 * k])
        pos += 3 * k
    d = Decoder(data, pos)
    ch = [0] * (w * h)
    _change_map(d, ch, prev, w, h, use_border)
    lab = list(prev)
    _pixels(d, lab, w, h, k, ch, prev)
    return bytes(lab), palette


def encode_clip(labs, pals, w, h, key_every):
    """labs, pals: one index map and one palette per picture. A key picture every key_every pictures.
    Returns a list of (is_key, bytes)."""
    out = []
    for n, (lab, pal) in enumerate(zip(labs, pals)):
        if n % key_every == 0:
            out.append((True, encode_key(lab, w, h, pal)))
        else:
            out.append((False, encode_delta(labs[n - 1], lab, w, h, len(pal) // 3, pal if pal != pals[n - 1] else None)))
    return out


def decode_clip(coded, w, h):
    labs, pals = [], []
    for is_key, data in coded:
        if is_key:
            lab, pal = decode_key(data, w, h)
        else:
            lab, pal = decode_delta(data, labs[-1], w, h, len(pals[-1]) // 3)
            pal = pal or pals[-1]
        labs.append(lab)
        pals.append(pal)
    return labs, pals


def _selftest():
    import random
    rnd = random.Random(1)
    w, h, k = 48, 36, 8
    pal = bytes(rnd.randrange(256) for _ in range(3 * k))

    def blobs(shift):
        return bytes((((x + shift) // 9) + (y // 7) * 3 + (1 if (x - 20 - shift) ** 2 + (y - 18) ** 2 < 90 else 0)) % k
                     for y in range(h) for x in range(w))
    labs = [blobs(s) for s in (0, 0, 1, 3)]
    labs.append(bytes(rnd.randrange(k) for _ in range(w * h)))   # noise: the worst case
    labs.append(labs[-1])
    labs.append(bytes(w * h))                                    # one colour
    pals = [pal] * 4 + [bytes(reversed(pal))] * 3                # the palette changes on a delta picture
    for key_every in (1, 3, 100):
        coded = encode_clip(labs, pals, w, h, key_every)
        back = decode_clip(coded, w, h)
        assert back == (labs, pals), "round trip failed"
        print("key every %3d:" % key_every, " ".join(("K" if key else "d") + str(len(d)) for key, d in coded), "bytes")
    print("ok   every picture decoded to exactly the input")


if __name__ == "__main__":
    _selftest()
