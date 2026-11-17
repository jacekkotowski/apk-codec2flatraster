/* See flatcode.h. A line-by-line port of tools/coder/flatcoder.py: keep the two in step. */
#include "flatcode.h"
#include <stdlib.h>
#include <string.h>

#define TOP (1u << 24)
#define STEP 32
#define LIMIT (1 << 16)

typedef struct { int n, tot; int f[FLATCODE_MAX_COLORS + 1]; } Model;

static void model_init(Model *m, int n) { m->n = n; m->tot = n; for (int i = 0; i < n; i++) m->f[i] = 1; }
static void model_update(Model *m, int s) {
    m->f[s] += STEP; m->tot += STEP;
    if (m->tot > LIMIT - STEP) { int t = 0; for (int i = 0; i < m->n; i++) { m->f[i] = (m->f[i] + 1) >> 1; t += m->f[i]; } m->tot = t; }
}

typedef struct {
    int enc, err;
    /* encoder */
    uint64_t low; uint32_t range; int cache; long pending;
    uint8_t *out; int cap, n;
    /* decoder */
    const uint8_t *d; int len, pos; uint32_t code;
} Coder;

static void put(Coder *c, int b) { if (c->n < c->cap) c->out[c->n] = (uint8_t)b; else c->err = 1; c->n++; }

static void shift(Coder *c) {
    uint64_t low = c->low;
    if (low < 0xFF000000ull || low > 0xFFFFFFFFull) {
        int carry = (int)(low >> 32);
        put(c, (c->cache + carry) & 0xFF);
        for (long i = 0; i < c->pending - 1; i++) put(c, (0xFF + carry) & 0xFF);
        c->pending = 0;
        c->cache = (int)((low >> 24) & 0xFF);
    }
    c->pending++;
    c->low = (low & 0x00FFFFFFull) << 8;
}

static int in_skip(int i, const int *skip, int ns) { for (int j = 0; j < ns; j++) if (skip[j] == i) return 1; return 0; }

/* Codes symbol s (encoder) or returns the decoded one. skip: symbols known to be impossible. */
static int symbol(Coder *c, Model *m, int s, const int *skip, int ns) {
    int tot = m->tot;
    for (int j = 0; j < ns; j++) tot -= m->f[skip[j]];
    if (tot <= 0) { c->err = 1; return 0; }
    if (c->enc) {
        int cum = 0;
        for (int i = 0; i < s; i++) if (!in_skip(i, skip, ns)) cum += m->f[i];
        uint32_t r = c->range / (uint32_t)tot;
        c->low += (uint64_t)r * (uint64_t)cum;
        c->range = r * (uint32_t)m->f[s];
        while (c->range < TOP) { c->range <<= 8; shift(c); }
    } else {
        uint32_t r = c->range / (uint32_t)tot;
        uint32_t t = c->code / r; if (t > (uint32_t)(tot - 1)) t = (uint32_t)(tot - 1);
        int cum = 0; s = 0;
        for (;;) {
            if (s >= m->n) { c->err = 1; return 0; }
            if (!in_skip(s, skip, ns)) { if (cum + m->f[s] > (int)t) break; cum += m->f[s]; }
            s++;
        }
        c->code -= r * (uint32_t)cum;
        c->range = r * (uint32_t)m->f[s];
        while (c->range < TOP) {
            int b = c->pos < c->len ? c->d[c->pos] : 0; c->pos++;
            c->code = (c->code << 8) | (uint32_t)b;
            c->range <<= 8;
        }
    }
    model_update(m, s);
    return s;
}

static void enc_start(Coder *c, uint8_t *out, int cap) { memset(c, 0, sizeof *c); c->enc = 1; c->range = 0xFFFFFFFFu; c->pending = 1; c->out = out; c->cap = cap; }
/* Returns the number of bytes written to out (the first byte, always 0, is dropped, trailing zero bytes too). */
static int enc_finish(Coder *c) {
    c->low = (c->low + 0xFFFFFFull) & ~0xFFFFFFull;
    for (int i = 0; i < 5; i++) shift(c);
    if (c->err || c->n > c->cap) return -1;
    memmove(c->out, c->out + 1, (size_t)c->n - 1);
    int n = c->n - 1;
    while (n > 0 && c->out[n - 1] == 0) n--;
    return n;
}
static void dec_start(Coder *c, const uint8_t *d, int len, int pos) {
    memset(c, 0, sizeof *c); c->d = d; c->len = len; c->pos = pos; c->range = 0xFFFFFFFFu;
    for (int i = 0; i < 4; i++) { int b = c->pos < c->len ? c->d[c->pos] : 0; c->pos++; c->code = (c->code << 8) | (uint32_t)b; }
}

/* Palette indexes of a picture in reading order (flatcoder.py _pixels). changed/prev: delta pictures (only marked pixels are coded). */
static int pixels(Coder *c, uint8_t *lab, int w, int h, int k, const uint8_t *changed, const uint8_t *prev) {
    Model *models = calloc(320, sizeof(Model));
    if (!models) return -1;
    Model other; model_init(&other, k);
    for (int y = 0; y < h && !c->err; y++) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            int i = row + x;
            if (changed && !changed[i]) continue;
            int a, b, cc, d;
            if (y == 0) { a = x ? lab[i - 1] : 0; b = cc = d = a; }
            else {
                b = lab[i - w];
                a = x ? lab[i - 1] : b;
                cc = x ? lab[i - w - 1] : b;
                d = x < w - 1 ? lab[i - w + 1] : b;
            }
            int cand[5], n = 0;
            cand[n++] = a;
            if (b != a) cand[n++] = b;
            int f = 0; for (int j = 0; j < n; j++) if (cand[j] == cc) f = 1;
            if (!f) cand[n++] = cc;
            f = 0; for (int j = 0; j < n; j++) if (cand[j] == d) f = 1;
            if (!f) cand[n++] = d;
            #define IDX(v, r) do { r = 0; for (int j_ = 0; j_ < n; j_++) if (cand[j_] == (v)) { r = j_; break; } } while (0)
            int ib, ic, id; IDX(b, ib); IDX(cc, ic); IDX(d, id);
            int ctx = ib | ic << 2 | id << 4;
            int skip[6], ns = 0;
            if (prev) {
                int was = prev[i], rp = n;
                for (int j = 0; j < n; j++) if (cand[j] == was) { rp = j; break; }
                ctx |= rp << 6;
                if (rp < n) skip[ns++] = rp;
            }
            Model *m = &models[ctx];
            if (m->n == 0) model_init(m, n + 1);
            else if (m->n != n + 1) { c->err = 1; break; }   /* cannot happen with data from this coder */
            int s = 0;
            if (c->enc) { int v = lab[i]; s = n; for (int j = 0; j < n; j++) if (cand[j] == v) { s = j; break; } }
            s = symbol(c, m, s, skip, ns);
            if (c->err) break;
            if (s < n) lab[i] = (uint8_t)cand[s];
            else {
                int sk[6], nk = n;
                memcpy(sk, cand, sizeof(int) * (size_t)n);
                if (prev) { int in = 0; for (int j = 0; j < n; j++) if (cand[j] == prev[i]) in = 1; if (!in) sk[nk++] = prev[i]; }
                int v = c->enc ? lab[i] : 0;
                int r = symbol(c, &other, v, sk, nk);
                if (c->err) break;
                lab[i] = (uint8_t)r;
            }
        }
    }
    free(models);
    return c->err ? -1 : 0;
}

/* 1 where a pixel differs from the pixel left, right, above or below it (flatcoder.py _border). */
static void border(const uint8_t *prev, int w, int h, uint8_t *e) {
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        int i = y * w + x, v = prev[i];
        e[i] = (x && prev[i - 1] != v) || (x < w - 1 && prev[i + 1] != v) || (y && prev[i - w] != v) || (y < h - 1 && prev[i + w] != v);
    }
}

static int change_map(Coder *c, uint8_t *ch, const uint8_t *prev, int w, int h) {
    uint8_t *edge = malloc((size_t)w * (size_t)h);
    if (!edge) return -1;
    border(prev, w, h, edge);
    Model models[32]; for (int i = 0; i < 32; i++) model_init(&models[i], 2);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        int i = y * w + x, ctx = edge[i] << 4;
        if (x) ctx |= ch[i - 1];
        if (y) { ctx |= ch[i - w] << 1; if (x) ctx |= ch[i - w - 1] << 2; if (x < w - 1) ctx |= ch[i - w + 1] << 3; }
        ch[i] = (uint8_t)symbol(c, &models[ctx], ch[i], NULL, 0);
        if (c->err) { free(edge); return -1; }
    }
    free(edge);
    return 0;
}

int flatcode_encode_key(const uint8_t *lab, int w, int h, const uint8_t *pal, int k, uint8_t *out, int cap) {
    if (k < 1 || k > FLATCODE_MAX_COLORS || w < 1 || h < 1 || cap < 1 + 3 * k) return -1;
    uint8_t *l = malloc((size_t)w * (size_t)h); if (!l) return -1;
    memcpy(l, lab, (size_t)w * (size_t)h);
    for (int i = 0; i < w * h; i++) if (l[i] >= k) { free(l); return -1; }
    out[0] = (uint8_t)k; memcpy(out + 1, pal, (size_t)(3 * k));
    Coder c; enc_start(&c, out + 1 + 3 * k, cap - 1 - 3 * k);
    int r = pixels(&c, l, w, h, k, NULL, NULL);
    free(l);
    if (r < 0) return -1;
    int n = enc_finish(&c);
    return n < 0 ? -1 : 1 + 3 * k + n;
}

int flatcode_decode_key(const uint8_t *data, int len, int w, int h, uint8_t *lab, uint8_t *pal) {
    if (len < 1) return -1;
    int k = data[0];
    if (k < 1 || k > FLATCODE_MAX_COLORS || len < 1 + 3 * k) return -1;
    memcpy(pal, data + 1, (size_t)(3 * k));
    memset(lab, 0, (size_t)w * (size_t)h);
    Coder c; dec_start(&c, data, len, 1 + 3 * k);
    if (pixels(&c, lab, w, h, k, NULL, NULL) < 0) return -1;
    return k;
}

int flatcode_encode_delta(const uint8_t *prev, const uint8_t *lab, int w, int h, int k, const uint8_t *pal, uint8_t *out, int cap) {
    int hp = pal != NULL, head = 1 + (hp ? 3 * k : 0);
    if (k < 1 || k > FLATCODE_MAX_COLORS || cap < head) return -1;
    size_t n = (size_t)w * (size_t)h;
    uint8_t *ch = malloc(n), *l = malloc(n);
    if (!ch || !l) { free(ch); free(l); return -1; }
    for (size_t i = 0; i < n; i++) { ch[i] = prev[i] != lab[i]; if (lab[i] >= k) { free(ch); free(l); return -1; } }
    memcpy(l, lab, n);
    out[0] = (uint8_t)hp; if (hp) memcpy(out + 1, pal, (size_t)(3 * k));
    Coder c; enc_start(&c, out + head, cap - head);
    int r = change_map(&c, ch, prev, w, h);
    if (r == 0) r = pixels(&c, l, w, h, k, ch, prev);
    free(ch); free(l);
    if (r < 0) return -1;
    int e = enc_finish(&c);
    return e < 0 ? -1 : head + e;
}

int flatcode_decode_delta(const uint8_t *data, int len, const uint8_t *prev, int w, int h, int k, uint8_t *lab, uint8_t *pal) {
    if (len < 1 || k < 1 || k > FLATCODE_MAX_COLORS) return -1;
    int hp = data[0] & 1, pos = 1;
    if (hp) { if (len < 1 + 3 * k) return -1; memcpy(pal, data + 1, (size_t)(3 * k)); pos += 3 * k; }
    size_t n = (size_t)w * (size_t)h;
    uint8_t *ch = calloc(n, 1);
    if (!ch) return -1;
    Coder c; dec_start(&c, data, len, pos);
    memcpy(lab, prev, n);
    int r = change_map(&c, ch, prev, w, h);
    if (r == 0) r = pixels(&c, lab, w, h, k, ch, prev);
    free(ch);
    return r < 0 ? -1 : hp;
}
