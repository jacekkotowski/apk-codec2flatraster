#include "smooth.h"
#include <stdlib.h>

/* How it works. A posterized picture is a few flat colours. Enlarged pixel by pixel, every border becomes a staircase; blurred,
   it becomes mush and gains colours that were never there. Here each output pixel asks: of the colours in the 4x4 source pixels
   around me, which has the most weight? The weights are those of a smooth bell (the cubic B-spline), so this is the same as
   blurring each colour's own area a little and giving every point to the colour whose blurred area is strongest there. The
   border between two colours then runs where their blurred areas are equal: a smooth line through the staircase, with corners
   slightly rounded. No colour is invented, because areas are compared, not colour values (blurring the values of black and white
   would give grey, and a grey line would appear wherever grey is also one of the colours).
   Within about one output pixel of that line the two strongest colours are mixed in proportion, which takes the last jaggedness
   off (anti-aliasing). A line one source pixel wide survives, because the bell gives its own pixel two thirds of the weight.
   Cost: a source cell whose 16 neighbours are all one colour is filled at once; only cells on a border do the sums. */

static inline int near(uint32_t a, uint32_t b, int tol) {
    return abs((int)((a >> 16) & 255) - (int)((b >> 16) & 255)) + abs((int)((a >> 8) & 255) - (int)((b >> 8) & 255)) + abs((int)(a & 255) - (int)(b & 255)) <= tol;
}

void smooth_upscale(const uint32_t *src, int w, int h, int s, int tol, uint32_t *dst) {
    if (s < 1) s = 1;
    if (s > 8) s = 8;
    int ow = w * s;
    /* Bell weights for each of the s positions an output pixel can have inside a source pixel, times 1024.
       Output pixel k of a source pixel sits at (k + 0.5) / s - 0.5 from its centre; below 0 it leans on the pixel before. */
    int wt[8][4], lean[8];
    for (int k = 0; k < s; k++) {
        double p = (k + 0.5) / s - 0.5, t = p < 0 ? p + 1 : p;
        lean[k] = p < 0 ? -1 : 0; /* which source pixel is tap 1 */
        double b[4] = {(1 - t) * (1 - t) * (1 - t) / 6, (3 * t * t * t - 6 * t * t + 4) / 6, (-3 * t * t * t + 3 * t * t + 3 * t + 1) / 6, t * t * t / 6};
        int sum = 0;
        for (int i = 0; i < 4; i++) { wt[k][i] = (int)(b[i] * 1024 + 0.5); sum += wt[k][i]; }
        wt[k][1] += 1024 - sum;
    }
    for (int y = 0; y < h; y++) for (int ky = 0; ky < s; ky++) {
        int y0 = y + lean[ky] - 1;                    /* first of the four source rows */
        uint32_t *out = dst + (size_t)(y * s + ky) * ow;
        for (int x = 0; x < w; x++) for (int kx = 0; kx < s; kx++) {
            int x0 = x + lean[kx] - 1;
            uint32_t px[16]; int same = 1;
            for (int j = 0; j < 4; j++) {
                int yy = y0 + j; yy = yy < 0 ? 0 : yy >= h ? h - 1 : yy;
                for (int i = 0; i < 4; i++) {
                    int xx = x0 + i; xx = xx < 0 ? 0 : xx >= w ? w - 1 : xx;
                    px[4 * j + i] = src[yy * w + xx];
                    if (px[4 * j + i] != px[0]) same = 0;
                }
            }
            if (same) { *out++ = px[0]; continue; }
            /* group the 16 pixels by colour; each group keeps its weight and its weighted mean colour */
            uint32_t rep[16]; int gw[16], gr[16], gg[16], gb[16], groups = 0;
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
                uint32_t c = px[4 * j + i]; int q = wt[ky][j] * wt[kx][i], g = 0;
                while (g < groups && !(rep[g] == c || (tol && near(rep[g], c, tol)))) g++;
                if (g == groups) { rep[g] = c; gw[g] = gr[g] = gg[g] = gb[g] = 0; groups++; }
                gw[g] += q >> 6; /* weights now add up to 16384 */
                gr[g] += (q >> 6) * (int)((c >> 16) & 255); gg[g] += (q >> 6) * (int)((c >> 8) & 255); gb[g] += (q >> 6) * (int)(c & 255);
            }
            int a = 0, b = -1;
            for (int g = 1; g < groups; g++) if (gw[g] > gw[a]) a = g;
            for (int g = 0; g < groups; g++) if (g != a && (b < 0 || gw[g] > gw[b])) b = g;
            int ra = gr[a] / (gw[a] ? gw[a] : 1), ga = gg[a] / (gw[a] ? gw[a] : 1), ba = gb[a] / (gw[a] ? gw[a] : 1);
            if (b < 0 || gw[b] == 0) { *out++ = 0xff000000u | (uint32_t)ra << 16 | (uint32_t)ga << 8 | (uint32_t)ba; continue; }
            /* mix the two strongest near the line where they are equal: full colour once ahead by 1/(1.2 s) of the weight,
               which is about one output pixel either side of the line */
            int t = 512 + (gw[a] - gw[b]) * 6 * s / 160; /* 0.5 + 0.6 s (wa - wb), in 1024ths, weights in 16384ths */
            if (t > 1024) t = 1024;
            int rb = gr[b] / gw[b], gb2 = gg[b] / gw[b], bb = gb[b] / gw[b];
            int r = (ra * t + rb * (1024 - t) + 512) >> 10, g2 = (ga * t + gb2 * (1024 - t) + 512) >> 10, bl = (ba * t + bb * (1024 - t) + 512) >> 10;
            *out++ = 0xff000000u | (uint32_t)r << 16 | (uint32_t)g2 << 8 | (uint32_t)bl;
        }
    }
}
