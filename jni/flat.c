#include "flat.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

struct Flat {
    int w, h, k, have, bad;
    uint8_t *y, *u, *v;      /* current frame, smoothed in place */
    uint8_t *ty, *tu, *tv;   /* scratch for smoothing */
    uint8_t *ry, *ru, *rv;   /* unused since 0.7 (the hold rule no longer compares a pixel with its own past) */
    uint8_t *raw, *out, *tmp;/* labels: before cleanup, after cleanup, scratch */
    uint8_t *disp, *sent;    /* labels after the repeating-movement filter; labels of the last picture that was sent */
    uint8_t *praw, *run, *act; /* repeating-movement filter: previous label, frames it has lasted, recent restlessness */
    uint8_t *cnt;            /* per pixel, how often each colour was seen lately: w*h*k counters */
    unsigned frameNo;
    int ignoring;
    uint8_t *focus;          /* 1 where the pixel lies inside a focus box (a face) */
    int box[4 * FLAT_MAX_FOCUS], boxes, coarse;
    uint8_t merge[2][FLAT_MAX_COLORS];
    int sep[FLAT_MAX_COLORS][FLAT_MAX_COLORS];
    int round;
    uint8_t py[FLAT_MAX_COLORS], pu[FLAT_MAX_COLORS], pv[FLAT_MAX_COLORS]; /* colours shown */
    uint8_t qy[FLAT_MAX_COLORS], qu[FLAT_MAX_COLORS], qv[FLAT_MAX_COLORS]; /* colours as fitted to the present picture: they decide the borders */
    double ty_[FLAT_MAX_COLORS], tu_[FLAT_MAX_COLORS], tv_[FLAT_MAX_COLORS]; /* the fit, unrounded */
    int trackFull;           /* 1: refit completely every picture; 0: glide towards the fit */
    int track;               /* 0: palette locked. Else: refit every picture; a shown colour follows once the fit is this far from it */
    double baseErr;
    int lut[256];
};

/* Chroma counts double: in YUV a clear change of hue is a small number. */
static inline int dist2(int dy, int du, int dv) { return dy * dy + 2 * (du * du + dv * dv); }
static inline int clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

Flat *flat_create(int w, int h, int colors) {
    if (w < 2 || h < 2 || (w & 1) || (h & 1)) return NULL;
    Flat *f = calloc(1, sizeof(Flat));
    if (!f) return NULL;
    f->w = w; f->h = h; f->k = colors < 2 ? 2 : colors > FLAT_MAX_COLORS ? FLAT_MAX_COLORS : colors;
    size_t n = (size_t)w * h;
    uint8_t *block = calloc(18, n);
    f->cnt = calloc(n, (size_t)f->k);
    if (!block || !f->cnt) { free(block); free(f->cnt); free(f); return NULL; }
    uint8_t **p[] = {&f->y, &f->u, &f->v, &f->ty, &f->tu, &f->tv, &f->ry, &f->ru, &f->rv, &f->raw, &f->out, &f->tmp, &f->disp, &f->sent, &f->praw, &f->run, &f->act, &f->focus};
    for (int i = 0; i < 18; i++) *p[i] = block + i * n;
    for (int d = 0; d < 256; d++) f->lut[d] = (int)(256 * exp(-(double)d * d / (2 * 14.0 * 14.0)));
    return f;
}

void flat_destroy(Flat *f) { if (f) { free(f->y); free(f->cnt); free(f); } }
void flat_reset(Flat *f) { f->have = 0; }
void flat_set_track(Flat *f, int threshold, int full) { f->track = threshold < 0 ? 0 : threshold; f->trackFull = full != 0; }
void flat_set_round(Flat *f, int passes) { f->round = passes < 0 ? 0 : passes > 2 ? 2 : passes; }

void flat_set_focus(Flat *f, const int *rects, int count, int coarse) {
    f->boxes = count < 0 ? 0 : count > FLAT_MAX_FOCUS ? FLAT_MAX_FOCUS : count;
    if (f->boxes) memcpy(f->box, rects, sizeof(int) * 4 * f->boxes);
    f->coarse = coarse < 0 ? 0 : coarse > 2 ? 2 : coarse;
}

static inline uint8_t inFocus(const Flat *f, int sx, int sy) {
    for (int b = 0; b < f->boxes; b++) { const int *r = f->box + 4 * b; if (sx >= r[0] && sx < r[2] && sy >= r[1] && sy < r[3]) return 1; }
    return 0;
}
const uint8_t *flat_labels(const Flat *f) { return f->out; }
int flat_colors(const Flat *f) { return f->k; }

void flat_load_rgb(Flat *f, const uint8_t *rgb) {
    int n = f->w * f->h;
    for (int i = 0; i < n; i++) {
        int r = rgb[3 * i], g = rgb[3 * i + 1], b = rgb[3 * i + 2];
        f->y[i] = clamp8((77 * r + 150 * g + 29 * b + 128) >> 8);
        f->u[i] = clamp8(((-43 * r - 85 * g + 128 * b + 128) >> 8) + 128);
        f->v[i] = clamp8(((128 * r - 107 * g - 21 * b + 128) >> 8) + 128);
        f->focus[i] = inFocus(f, i % f->w, i / f->w);
    }
}

void flat_load_yuv420(Flat *f, const uint8_t *y, int yStride, const uint8_t *u, const uint8_t *v,
                      int uvStride, int uvPixStride, int sw, int sh, int rot, int mirror) {
    int w = f->w, h = f->h;
    int rw = (rot == 90 || rot == 270) ? sh : sw, rh = (rot == 90 || rot == 270) ? sw : sh;
    /* Centre crop of the upright picture with the output's shape. */
    int cw = rw, ch = (int)((long long)rw * h / w);
    if (ch > rh) { ch = rh; cw = (int)((long long)rh * w / h); }
    int x0 = (rw - cw) / 2, y0 = (rh - ch) / 2;
    int taps = cw / w; if (taps < 1) taps = 1; if (taps > 4) taps = 4;
    for (int oy = 0; oy < h; oy++) for (int ox = 0; ox < w; ox++) {
        int sy_ = 0, su = 0, sv = 0, fx = 0, fy = 0;
        for (int j = 0; j < taps; j++) for (int i = 0; i < taps; i++) {
            int x = x0 + (int)(((long long)(ox * taps + i) * cw) / (w * taps));
            int yy = y0 + (int)(((long long)(oy * taps + j) * ch) / (h * taps));
            if (mirror) x = rw - 1 - x;
            int sx, sy;
            switch (rot) {
                case 90: sx = yy; sy = sh - 1 - x; break;
                case 180: sx = sw - 1 - x; sy = sh - 1 - yy; break;
                case 270: sx = sw - 1 - yy; sy = x; break;
                default: sx = x; sy = yy;
            }
            sx = sx < 0 ? 0 : sx >= sw ? sw - 1 : sx;
            sy = sy < 0 ? 0 : sy >= sh ? sh - 1 : sy;
            sy_ += y[sy * yStride + sx];
            if (i == taps / 2 && j == taps / 2) { fx = sx; fy = sy; }
            int c = (sy / 2) * uvStride + (sx / 2) * uvPixStride;
            su += u[c]; sv += v[c];
        }
        int n = taps * taps, o = oy * w + ox;
        f->y[o] = (uint8_t)((sy_ + n / 2) / n); f->u[o] = (uint8_t)((su + n / 2) / n); f->v[o] = (uint8_t)((sv + n / 2) / n);
        f->focus[o] = inFocus(f, fx, fy); /* boxes are in source pixels, so rotation and cropping need no extra maths */
    }
}

/* One bilateral pass, 5x5: neighbours count only if their colour is close, so edges stay sharp. */
static void bilateral(Flat *f) {
    int w = f->w, h = f->h;
    for (int yy = 0; yy < h; yy++) for (int x = 0; x < w; x++) {
        int o = yy * w + x, cy = f->y[o], cu = f->u[o], cv = f->v[o];
        int sw_ = 0, sy = 0, su = 0, sv = 0;
        for (int j = -2; j <= 2; j++) {
            int y2 = yy + j; if (y2 < 0 || y2 >= h) continue;
            for (int i = -2; i <= 2; i++) {
                int x2 = x + i; if (x2 < 0 || x2 >= w) continue;
                int p = y2 * w + x2, ny = f->y[p], nu = f->u[p], nv = f->v[p];
                int d = abs(ny - cy) + abs(nu - cu) + abs(nv - cv); if (d > 255) d = 255;
                int wt = f->lut[d];
                sw_ += wt; sy += wt * ny; su += wt * nu; sv += wt * nv;
            }
        }
        f->ty[o] = (uint8_t)((sy + sw_ / 2) / sw_); f->tu[o] = (uint8_t)((su + sw_ / 2) / sw_); f->tv[o] = (uint8_t)((sv + sw_ / 2) / sw_);
    }
    size_t n = (size_t)w * h;
    memcpy(f->y, f->ty, n); memcpy(f->u, f->tu, n); memcpy(f->v, f->tv, n);
}

static inline int nearest(const Flat *f, int y, int u, int v, int *dOut) {
    int best = 0, bd = 1 << 30;
    for (int c = 0; c < f->k; c++) {
        int d = dist2(y - f->qy[c], u - f->qu[c], v - f->qv[c]);
        if (d < bd) { bd = d; best = c; }
    }
    if (dOut) *dOut = bd;
    return best;
}

static int sampleStep(const Flat *f) { int s = (int)sqrt((double)f->w * f->h / 5000.0); return s < 1 ? 1 : s; }

/* Mean distance from the picture to the palette, on a subsample. */
static double paletteError(const Flat *f) {
    int s = sampleStep(f), n = 0; double sum = 0;
    for (int yy = s / 2; yy < f->h; yy += s) for (int x = s / 2; x < f->w; x += s) {
        int o = yy * f->w + x, d; nearest(f, f->y[o], f->u[o], f->v[o], &d); sum += sqrt((double)d); n++;
    }
    return n ? sum / n : 0;
}

static void buildMerge(Flat *f);

/* k-means on a subsample. seeded=1 starts from the current palette, so colour indexes keep their meaning. */
static void buildPalette(Flat *f, int seeded) {
    int s = sampleStep(f), w = f->w, h = f->h, k = f->k, n = 0;
    int cap = (w / s + 1) * (h / s + 1);
    uint8_t *sm = malloc((size_t)cap * 3); int *dmin = malloc(sizeof(int) * cap);
    if (!sm || !dmin) { free(sm); free(dmin); return; }
    for (int yy = s / 2; yy < h; yy += s) for (int x = s / 2; x < w; x += s) {
        int o = yy * w + x; sm[3 * n] = f->y[o]; sm[3 * n + 1] = f->u[o]; sm[3 * n + 2] = f->v[o]; n++;
    }
    double cy[FLAT_MAX_COLORS], cu[FLAT_MAX_COLORS], cv[FLAT_MAX_COLORS];
    if (seeded) for (int c = 0; c < k; c++) { cy[c] = f->ty_[c]; cu[c] = f->tu_[c]; cv[c] = f->tv_[c]; }
    else {
        /* Farthest-point start: fixed, so the same picture always gives the same palette. */
        cy[0] = sm[3 * (n / 2)]; cu[0] = sm[3 * (n / 2) + 1]; cv[0] = sm[3 * (n / 2) + 2];
        for (int i = 0; i < n; i++) dmin[i] = 1 << 30;
        for (int c = 1; c < k; c++) {
            int far = 0, fd = -1;
            for (int i = 0; i < n; i++) {
                int d = dist2(sm[3 * i] - (int)cy[c - 1], sm[3 * i + 1] - (int)cu[c - 1], sm[3 * i + 2] - (int)cv[c - 1]);
                if (d < dmin[i]) dmin[i] = d;
                if (dmin[i] > fd) { fd = dmin[i]; far = i; }
            }
            cy[c] = sm[3 * far]; cu[c] = sm[3 * far + 1]; cv[c] = sm[3 * far + 2];
        }
    }
    for (int it = 0; it < (seeded ? 8 : 16); it++) {
        double ay[FLAT_MAX_COLORS] = {0}, au[FLAT_MAX_COLORS] = {0}, av[FLAT_MAX_COLORS] = {0}; int cnt[FLAT_MAX_COLORS] = {0};
        int far = 0; double fd = -1;
        for (int i = 0; i < n; i++) {
            int best = 0; double bd = 1e30;
            for (int c = 0; c < k; c++) {
                double dy = sm[3 * i] - cy[c], du = sm[3 * i + 1] - cu[c], dv = sm[3 * i + 2] - cv[c];
                double d = dy * dy + 2 * (du * du + dv * dv);
                if (d < bd) { bd = d; best = c; }
            }
            ay[best] += sm[3 * i]; au[best] += sm[3 * i + 1]; av[best] += sm[3 * i + 2]; cnt[best]++;
            if (bd > fd) { fd = bd; far = i; }
        }
        for (int c = 0; c < k; c++) {
            if (cnt[c]) { cy[c] = ay[c] / cnt[c]; cu[c] = au[c] / cnt[c]; cv[c] = av[c] / cnt[c]; }
            else { cy[c] = sm[3 * far]; cu[c] = sm[3 * far + 1]; cv[c] = sm[3 * far + 2]; } /* unused colour moves to the worst-served pixel */
        }
    }
    if (!seeded) { /* order by brightness: a stable, meaningful index order */
        for (int a = 1; a < k; a++) for (int b = a; b > 0 && cy[b] < cy[b - 1]; b--) {
            double t;
            t = cy[b]; cy[b] = cy[b - 1]; cy[b - 1] = t; t = cu[b]; cu[b] = cu[b - 1]; cu[b - 1] = t; t = cv[b]; cv[b] = cv[b - 1]; cv[b - 1] = t;
        }
    }
    for (int c = 0; c < k; c++) { f->py[c] = clamp8((int)(cy[c] + 0.5)); f->pu[c] = clamp8((int)(cu[c] + 0.5)); f->pv[c] = clamp8((int)(cv[c] + 0.5)); }
    for (int c = 0; c < k; c++) { f->ty_[c] = cy[c]; f->tu_[c] = cu[c]; f->tv_[c] = cv[c]; f->qy[c] = f->py[c]; f->qu[c] = f->pu[c]; f->qv[c] = f->pv[c]; }
    free(sm); free(dmin);
    buildMerge(f);
}

/* A pixel whose label is rare among its 8 neighbours takes the most common one. */
static void majority(Flat *f, const uint8_t *src, uint8_t *dst) {
    int w = f->w, h = f->h;
    for (int yy = 0; yy < h; yy++) for (int x = 0; x < w; x++) {
        uint8_t cnt[FLAT_MAX_COLORS] = {0}; int o = yy * w + x, self = src[o], best = self, bc = 0;
        for (int j = -1; j <= 1; j++) {
            int y2 = yy + j; if (y2 < 0 || y2 >= h) continue;
            for (int i = -1; i <= 1; i++) {
                int x2 = x + i; if (x2 < 0 || x2 >= w) continue;
                int l = src[y2 * w + x2]; if (++cnt[l] > bc) { bc = cnt[l]; best = l; }
            }
        }
        dst[o] = cnt[self] <= 2 ? (uint8_t)best : (uint8_t)self;
    }
}

/* Fills merge[level]: each colour's stand-in when only a half (level 0) or a quarter (level 1) of the colours may be
   used. The two closest colours are merged, again and again, until few enough are left. */
static void buildMerge(Flat *f) {
    int k = f->k, rep[FLAT_MAX_COLORS], left = k;
    for (int c = 0; c < k; c++) rep[c] = c;
    for (int level = 0; level < 2; level++) {
        int target = k >> (level + 1); if (target < 2) target = 2;
        while (left > target) {
            int ba = 0, bb = 1, bd = 1 << 30;
            for (int a = 0; a < k; a++) if (rep[a] == a) for (int b = a + 1; b < k; b++) if (rep[b] == b) {
                int d = dist2(f->py[a] - f->py[b], f->pu[a] - f->pu[b], f->pv[a] - f->pv[b]);
                if (d < bd) { bd = d; ba = a; bb = b; }
            }
            for (int c = 0; c < k; c++) if (rep[c] == bb) rep[c] = ba;
            left--;
        }
        for (int c = 0; c < k; c++) f->merge[level][c] = (uint8_t)rep[c];
    }
}

/* Outside the focus boxes only half or a quarter of the colours are used, and small regions are cleaned away once
   more: fewer edges there, so the bits go to the boxes. (Drawing the rest in blocks was tried and made the stream
   bigger: staircase edges cost more than smooth ones.) */
static void coarsen(Flat *f) {
    int n = f->w * f->h; const uint8_t *m = f->merge[f->coarse - 1];
    for (int i = 0; i < n; i++) f->tmp[i] = f->focus[i] ? f->out[i] : m[f->out[i]];
    majority(f, f->tmp, f->ty);
    for (int i = 0; i < n; i++) f->out[i] = f->focus[i] ? f->out[i] : f->ty[i];
}

/* Refits the colours to the present picture: a few k-means steps from where they were. The borders between colours
   then always fall where a brand-new palette would put them (between the picture's real colour groups, where edges
   are steep and therefore smooth), instead of drifting into flat areas where noise makes them ragged.
   The colours that are shown stay as they were until the fit has moved more than `track` away, so a region does not
   change its colour value, and cost bits, for every small shift. Returns 1 if any deciding colour moved. */
static int trackPalette(Flat *f) {
    int s = sampleStep(f), w = f->w, h = f->h, k = f->k, moved = 0, shown = 0;
    double cy[FLAT_MAX_COLORS], cu[FLAT_MAX_COLORS], cv[FLAT_MAX_COLORS];
    for (int c = 0; c < k; c++) { cy[c] = f->ty_[c]; cu[c] = f->tu_[c]; cv[c] = f->tv_[c]; }
    for (int it = 0; it < (f->trackFull ? 10 : 3); it++) { /* full: run until the fit has settled, as a new palette does */
        double ay[FLAT_MAX_COLORS] = {0}, au[FLAT_MAX_COLORS] = {0}, av[FLAT_MAX_COLORS] = {0}; int cnt[FLAT_MAX_COLORS] = {0};
        for (int yy = s / 2; yy < h; yy += s) for (int x = s / 2; x < w; x += s) {
            int o = yy * w + x, best = 0; double bd = 1e30;
            for (int c = 0; c < k; c++) {
                double dy = f->y[o] - cy[c], du = f->u[o] - cu[c], dv = f->v[o] - cv[c], d = dy * dy + 2 * (du * du + dv * dv);
                if (d < bd) { bd = d; best = c; }
            }
            ay[best] += f->y[o]; au[best] += f->u[o]; av[best] += f->v[o]; cnt[best]++;
        }
        for (int c = 0; c < k; c++) if (cnt[c]) { cy[c] = ay[c] / cnt[c]; cu[c] = au[c] / cnt[c]; cv[c] = av[c] / cnt[c]; } /* a colour nobody uses now waits where it is */
    }
    for (int c = 0; c < k; c++) {
        /* half-way steps: the fit glides instead of jumping with each picture's noise */
        double g = f->trackFull ? 1.0 : 0.5;
        f->ty_[c] += g * (cy[c] - f->ty_[c]); f->tu_[c] += g * (cu[c] - f->tu_[c]); f->tv_[c] += g * (cv[c] - f->tv_[c]);
        int y = clamp8((int)(f->ty_[c] + 0.5)), u = clamp8((int)(f->tu_[c] + 0.5)), v = clamp8((int)(f->tv_[c] + 0.5));
        if (y != f->qy[c] || u != f->qu[c] || v != f->qv[c]) { f->qy[c] = (uint8_t)y; f->qu[c] = (uint8_t)u; f->qv[c] = (uint8_t)v; moved = 1; }
        if (abs(y - f->py[c]) + abs(u - f->pu[c]) + abs(v - f->pv[c]) > f->track) { f->py[c] = (uint8_t)y; f->pu[c] = (uint8_t)u; f->pv[c] = (uint8_t)v; shown = 1; }
    }
    if (shown) buildMerge(f);
    return moved;
}

int flat_changed(const Flat *f) {
    int n = f->w * f->h, c = 0;
    for (int i = 0; i < n; i++) c += f->out[i] != f->sent[i];
    return c;
}

void flat_mark_sent(Flat *f) { memcpy(f->sent, f->out, (size_t)f->w * f->h); }

/* Repeating movement (leaves, water, flicker): a pixel that keeps swapping between the same few colours shows
   only one of them. A colour the pixel has rarely shown is real movement and is shown at once. */
static void repeatFilter(Flat *f, int level, int fresh) {
    static const int ratio[4] = {0, 3, 6, 12}; /* a colour seen at least 1/ratio as often as the shown one counts as usual */
    int n = f->w * f->h, k = f->k, R = ratio[level > 3 ? 3 : level];
    if (fresh) { memset(f->cnt, 0, (size_t)n * k); memset(f->run, 0, n); memset(f->act, 0, n); memcpy(f->disp, f->raw, n); memcpy(f->praw, f->raw, n); }
    int decay = (++f->frameNo & 255) == 0; /* old history fades, so a changed scene is relearned */
    for (int i = 0; i < n; i++) {
        uint8_t *c = f->cnt + (size_t)i * k; int r = f->raw[i], d = f->disp[i];
        if (c[r] == 255 || decay) for (int j = 0; j < k; j++) c[j] >>= 1;
        c[r]++;
        /* run: frames the pixel has held its present colour. act: how restless it has been lately. */
        if (r == f->praw[i]) { if (f->run[i] < 255) f->run[i]++; }
        else { f->run[i] = 1; f->act[i] = (uint8_t)(f->act[i] > 235 ? 255 : f->act[i] + 20); f->praw[i] = (uint8_t)r; }
        if (f->act[i]) f->act[i]--;
        if (r == d) continue;
        int rare = c[d] < 8 || c[r] * R < c[d];           /* little history, or a colour seldom seen here: real movement */
        int dominant = c[r] > 2 * c[d];                    /* clearly the usual colour: the background coming back */
        int settled = f->run[i] >= 2 + f->act[i] / 2;      /* held steadily; a restless pixel must hold much longer */
        if (rare || dominant || settled) f->disp[i] = (uint8_t)r;
    }
}

/* The most common colour in the 5x5 around each pixel: rounds ragged borders, at the price of details thinner than
   about 3 pixels. Measured on a soft-edged moving scene: border length 1.22x of the ideal without it, 1.03x with it.
   (Also tried: deciding pixels near movement afresh, without hold. It did not help.) */
static void mode5(Flat *f, const uint8_t *src, uint8_t *dst) {
    int w = f->w, h = f->h;
    for (int yy = 0; yy < h; yy++) for (int x = 0; x < w; x++) {
        uint8_t cnt[FLAT_MAX_COLORS] = {0}; int self = src[yy * w + x], best = self, bc = 0;
        for (int j = -2; j <= 2; j++) { int y2 = yy + j; if (y2 < 0 || y2 >= h) continue;
            for (int i = -2; i <= 2; i++) { int x2 = x + i; if (x2 < 0 || x2 >= w) continue;
                int l = src[y2 * w + x2]; if (++cnt[l] > bc || (cnt[l] == bc && l == self)) { bc = cnt[l]; best = l; } } }
        dst[yy * w + x] = (uint8_t)best;
    }
}

int flat_run(Flat *f, int smooth, int hold, int clean, int ignore) {
    int n = f->w * f->h, rebuilt = 0;
    if (smooth > 6) smooth = 6;
    for (int i = 0; i < smooth; i++) bilateral(f);
    if (!f->have) { buildPalette(f, 0); f->baseErr = paletteError(f); f->bad = 0; rebuilt = 1; }
    else {
        /* The palette stays locked until the picture stops fitting it for several frames in a row. */
        double e = paletteError(f);
        if (e > 1.6 * f->baseErr + 4) f->bad++; else f->bad = 0;
        if (f->bad >= 3) { buildPalette(f, 1); f->baseErr = paletteError(f); f->bad = 0; rebuilt = 1; }
    }
    int moved = 0;
    if (f->track && !rebuilt) { moved = trackPalette(f); if (moved) f->baseErr = paletteError(f); }
    int fresh = rebuilt || !f->have;
    /* Hold: a pixel keeps its colour until the picture has moved clearly past the border between that colour and the
       new one, by `hold`/2 colour steps. The border's place is then decided by the picture as it is now, the same for
       all pixels along it, so it stays as smooth as the picture; noise near the border still can't flip pixels.
       (Before 0.7 each pixel compared itself with its own past, so along a border pixels switched one by one as the
       camera's exposure drifted or things moved, and the border frayed until the next new palette.) */
    if (fresh || moved) { /* distance between each pair of colours, for the margin */
        for (int a = 0; a < f->k; a++) for (int b = 0; b < f->k; b++)
            f->sep[a][b] = (int)(sqrt((double)dist2(f->qy[a] - f->qy[b], f->qu[a] - f->qu[b], f->qv[a] - f->qv[b])) + 0.5);
    }
    for (int i = 0; i < n; i++) {
        int y = f->y[i], u = f->u[i], v = f->v[i], bd;
        int best = nearest(f, y, u, v, &bd);
        if (!fresh) {
            int old = f->raw[i];
            if (best == old) continue;
            /* dOld - dBest = 2 * (how far past the midpoint) * (distance between the two colours) */
            int dOld = dist2(y - f->qy[old], u - f->qu[old], v - f->qv[old]);
            if (dOld - bd <= hold * f->sep[old][best]) continue;
        }
        f->raw[i] = (uint8_t)best;
    }
    f->have = 1;
    const uint8_t *lab = f->raw;
    if (ignore > 0) { repeatFilter(f, ignore, fresh || !f->ignoring); lab = f->disp; } /* history restarts when it is switched on */
    f->ignoring = ignore > 0;
    if (clean <= 0) memcpy(f->out, lab, n);
    else {
        if (clean > 3) clean = 3;
        const uint8_t *src = lab;
        for (int p = 0; p < clean; p++) {
            uint8_t *dst = (p == clean - 1) ? f->out : (p & 1) ? f->ty : f->tmp;
            majority(f, src, dst); src = dst;
        }
    }
    for (int p = 0; p < f->round; p++) { mode5(f, f->out, f->ty); memcpy(f->out, f->ty, n); }
    if (f->coarse) coarsen(f);
    return rebuilt;
}

static void paletteRgb(const Flat *f, int c, int *r, int *g, int *b) {
    int y = f->py[c], u = f->pu[c] - 128, v = f->pv[c] - 128;
    *r = clamp8(y + ((359 * v + 128) >> 8));
    *g = clamp8(y - ((88 * u + 183 * v + 128) >> 8));
    *b = clamp8(y + ((454 * u + 128) >> 8));
}

void flat_palette_rgb(const Flat *f, uint8_t *rgb) {
    for (int c = 0; c < f->k; c++) { int r, g, b; paletteRgb(f, c, &r, &g, &b); rgb[3 * c] = (uint8_t)r; rgb[3 * c + 1] = (uint8_t)g; rgb[3 * c + 2] = (uint8_t)b; }
}

void flat_argb(const Flat *f, uint32_t *dst) {
    uint32_t pal[FLAT_MAX_COLORS];
    for (int c = 0; c < f->k; c++) { int r, g, b; paletteRgb(f, c, &r, &g, &b); pal[c] = 0xff000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b; }
    int n = f->w * f->h;
    for (int i = 0; i < n; i++) dst[i] = pal[f->out[i]];
}

void flat_i420(const Flat *f, uint8_t *dst) {
    int w = f->w, h = f->h, n = w * h;
    uint8_t ly[FLAT_MAX_COLORS]; int lu[FLAT_MAX_COLORS], lv[FLAT_MAX_COLORS];
    for (int c = 0; c < f->k; c++) { /* full range to the limited range video encoders expect */
        ly[c] = (uint8_t)(16 + (f->py[c] * 219 + 127) / 255);
        lu[c] = 128 + ((f->pu[c] - 128) * 224) / 255; lv[c] = 128 + ((f->pv[c] - 128) * 224) / 255;
    }
    for (int i = 0; i < n; i++) dst[i] = ly[f->out[i]];
    uint8_t *du = dst + n, *dv = du + n / 4;
    for (int yy = 0; yy < h; yy += 2) for (int x = 0; x < w; x += 2) {
        int a = f->out[yy * w + x], b = f->out[yy * w + x + 1], c = f->out[(yy + 1) * w + x], d = f->out[(yy + 1) * w + x + 1];
        *du++ = (uint8_t)((lu[a] + lu[b] + lu[c] + lu[d] + 2) >> 2);
        *dv++ = (uint8_t)((lv[a] + lv[b] + lv[c] + lv[d] + 2) >> 2);
    }
}
