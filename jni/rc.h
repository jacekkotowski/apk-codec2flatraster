/* The range coder shared by flatcode.c (pictures) and motion.c (landmark points): a 32-bit range coder with carry and adaptive symbol
   counts, a port of the Encoder/Decoder/Model classes of tools/coder/flatcoder.py. Keep the three in step. */
#ifndef RC_H
#define RC_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define TOP (1u << 24)
#define STEP 32
#define LIMIT (1 << 16)

#define RC_MAX_SYMBOLS 33
typedef struct { int n, tot, fixed; int f[RC_MAX_SYMBOLS]; } Model;   /* fixed: counts never change (motion.py _Static) */

static inline void model_init(Model *m, int n) { m->n = n; m->fixed = 0; m->tot = n; for (int i = 0; i < n; i++) m->f[i] = 1; }
static inline void model_update(Model *m, int s) {
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

static inline void put(Coder *c, int b) { if (c->n < c->cap) c->out[c->n] = (uint8_t)b; else c->err = 1; c->n++; }

static inline void shift(Coder *c) {
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

static inline int in_skip(int i, const int *skip, int ns) { for (int j = 0; j < ns; j++) if (skip[j] == i) return 1; return 0; }

/* Codes symbol s (encoder) or returns the decoded one. skip: symbols known to be impossible. */
static inline int symbol(Coder *c, Model *m, int s, const int *skip, int ns) {
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
    if (!m->fixed) model_update(m, s);
    return s;
}

static inline void enc_start(Coder *c, uint8_t *out, int cap) { memset(c, 0, sizeof *c); c->enc = 1; c->range = 0xFFFFFFFFu; c->pending = 1; c->out = out; c->cap = cap; }
/* Returns the number of bytes written to out (the first byte, always 0, is dropped, trailing zero bytes too). */
static inline int enc_finish(Coder *c) {
    c->low = (c->low + 0xFFFFFFull) & ~0xFFFFFFull;
    for (int i = 0; i < 5; i++) shift(c);
    if (c->err || c->n > c->cap) return -1;
    memmove(c->out, c->out + 1, (size_t)c->n - 1);
    int n = c->n - 1;
    while (n > 0 && c->out[n - 1] == 0) n--;
    return n;
}
static inline void dec_start(Coder *c, const uint8_t *d, int len, int pos) {
    memset(c, 0, sizeof *c); c->d = d; c->len = len; c->pos = pos; c->range = 0xFFFFFFFFu;
    for (int i = 0; i < 4; i++) { int b = c->pos < c->len ? c->d[c->pos] : 0; c->pos++; c->code = (c->code << 8) | (uint32_t)b; }
}


/* A symbol of n equally likely values that never learns (motion.py _Static). */
static inline int uniform(Coder *c, int n, int s) {
    if (c->enc) {
        uint32_t r = c->range / (uint32_t)n;
        c->low += (uint64_t)r * (uint64_t)s;
        c->range = r;
        while (c->range < TOP) { c->range <<= 8; shift(c); }
        return s;
    }
    uint32_t r = c->range / (uint32_t)n;
    uint32_t t = c->code / r; if (t > (uint32_t)(n - 1)) t = (uint32_t)(n - 1);
    c->code -= r * t;
    c->range = r;
    while (c->range < TOP) {
        int b = c->pos < c->len ? c->d[c->pos] : 0; c->pos++;
        c->code = (c->code << 8) | (uint32_t)b;
        c->range <<= 8;
    }
    return (int)t;
}

#endif
