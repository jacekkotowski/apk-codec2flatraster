#include "flat.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

struct Flat {
    int w, h, k, have, bad;
    uint8_t *y, *u, *v;      /* current frame, smoothed in place */
    uint8_t *ty, *tu, *tv;   /* scratch for smoothing */
    uint8_t *ry, *ru, *rv;   /* colour each pixel had when its label was last set */
    uint8_t *raw, *out, *tmp;/* labels: before cleanup, after cleanup, scratch */
    uint8_t py[FLAT_MAX_COLORS], pu[FLAT_MAX_COLORS], pv[FLAT_MAX_COLORS];
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
    uint8_t *block = calloc(12, n);
    if (!block) { free(f); return NULL; }
    uint8_t **p[] = {&f->y, &f->u, &f->v, &f->ty, &f->tu, &f->tv, &f->ry, &f->ru, &f->rv, &f->raw, &f->out, &f->tmp};
    for (int i = 0; i < 12; i++) *p[i] = block + i * n;
    for (int d = 0; d < 256; d++) f->lut[d] = (int)(256 * exp(-(double)d * d / (2 * 14.0 * 14.0)));
    return f;
}

void flat_destroy(Flat *f) { if (f) { free(f->y); free(f); } }
void flat_reset(Flat *f) { f->have = 0; }
const uint8_t *flat_labels(const Flat *f) { return f->out; }
int flat_colors(const Flat *f) { return f->k; }

void flat_load_rgb(Flat *f, const uint8_t *rgb) {
    int n = f->w * f->h;
    for (int i = 0; i < n; i++) {
        int r = rgb[3 * i], g = rgb[3 * i + 1], b = rgb[3 * i + 2];
        f->y[i] = clamp8((77 * r + 150 * g + 29 * b + 128) >> 8);
        f->u[i] = clamp8(((-43 * r - 85 * g + 128 * b + 128) >> 8) + 128);
        f->v[i] = clamp8(((128 * r - 107 * g - 21 * b + 128) >> 8) + 128);
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
        int sy_ = 0, su = 0, sv = 0;
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
            int c = (sy / 2) * uvStride + (sx / 2) * uvPixStride;
            su += u[c]; sv += v[c];
        }
        int n = taps * taps, o = oy * w + ox;
        f->y[o] = (uint8_t)((sy_ + n / 2) / n); f->u[o] = (uint8_t)((su + n / 2) / n); f->v[o] = (uint8_t)((sv + n / 2) / n);
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
        int d = dist2(y - f->py[c], u - f->pu[c], v - f->pv[c]);
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
    if (seeded) for (int c = 0; c < k; c++) { cy[c] = f->py[c]; cu[c] = f->pu[c]; cv[c] = f->pv[c]; }
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
    free(sm); free(dmin);
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

int flat_run(Flat *f, int smooth, int hold, int clean) {
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
    int fresh = rebuilt || !f->have;
    for (int i = 0; i < n; i++) {
        int y = f->y[i], u = f->u[i], v = f->v[i];
        /* Temporal hold: small changes (sensor noise) keep the old label, so still areas stay bit-identical. */
        if (!fresh && abs(y - f->ry[i]) + abs(u - f->ru[i]) + abs(v - f->rv[i]) <= hold) continue;
        f->raw[i] = (uint8_t)nearest(f, y, u, v, NULL);
        f->ry[i] = (uint8_t)y; f->ru[i] = (uint8_t)u; f->rv[i] = (uint8_t)v;
    }
    f->have = 1;
    if (clean <= 0) memcpy(f->out, f->raw, n);
    else {
        if (clean > 3) clean = 3;
        const uint8_t *src = f->raw;
        for (int p = 0; p < clean; p++) {
            uint8_t *dst = (p == clean - 1) ? f->out : (p & 1) ? f->ty : f->tmp;
            majority(f, src, dst); src = dst;
        }
    }
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
