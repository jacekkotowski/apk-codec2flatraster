/* Checks flat_load_yuv420: rotation, mirror, centre crop, padded rows and interleaved chroma (as camera frames have).
   The source is 640x480 with four coloured quarters: top-left red, top-right green, bottom-left blue, bottom-right white. */
#include "../jni/flat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *name(uint32_t c) {
    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
    if (r > 180 && g > 180 && b > 180) return "white";
    if (r > 150 && g < 110 && b < 110) return "red";
    if (g > 150 && r < 110 && b < 110) return "green";
    if (b > 150 && r < 110 && g < 110) return "blue";
    return "?";
}

int main(void) {
    enum { SW = 640, SH = 480, YS = 672, UVS = 672 }; /* strides wider than the picture */
    static uint8_t y[YS * SH], uv[UVS * SH / 2 + 1];  /* V and U interleaved: pixel stride 2 */
    const int qy[4] = {76, 150, 29, 255}, qu[4] = {85, 44, 255, 128}, qv[4] = {255, 21, 107, 128}; /* red green blue white */
    memset(y, 0, sizeof y); memset(uv, 0, sizeof uv);
    for (int r = 0; r < SH; r++) for (int c = 0; c < SW; c++) {
        int q = (r >= SH / 2) * 2 + (c >= SW / 2);
        y[r * YS + c] = (uint8_t)qy[q];
        uv[(r / 2) * UVS + (c / 2) * 2] = (uint8_t)qv[q]; uv[(r / 2) * UVS + (c / 2) * 2 + 1] = (uint8_t)qu[q];
    }
    /* expected colours at the output's top-left, top-right, bottom-left, bottom-right */
    struct { int rot, mirror, w, h; const char *want[4]; } cases[] = {
        {0, 0, 320, 240, {"red", "green", "blue", "white"}},
        {90, 0, 240, 320, {"blue", "red", "white", "green"}},
        {180, 0, 320, 240, {"white", "blue", "green", "red"}},
        {270, 0, 240, 320, {"green", "white", "red", "blue"}},
        {0, 1, 320, 240, {"green", "red", "white", "blue"}},
        {270, 1, 240, 320, {"white", "green", "blue", "red"}},
        {0, 0, 160, 120, {"red", "green", "blue", "white"}},
        {90, 0, 360, 480, {"blue", "red", "white", "green"}},
        {0, 0, 240, 320, {"red", "green", "blue", "white"}}, /* portrait cut from a landscape frame: crop, same corners */
    };
    int fails = 0;
    for (unsigned i = 0; i < sizeof cases / sizeof *cases; i++) {
        int w = cases[i].w, h = cases[i].h;
        Flat *f = flat_create(w, h, 4);
        uint32_t *argb = malloc(sizeof(uint32_t) * w * h);
        flat_load_yuv420(f, y, YS, uv + 1, uv, UVS, 2, SW, SH, cases[i].rot, cases[i].mirror);
        flat_run(f, 0, 0, 0, 0); flat_argb(f, argb);
        int px[4] = {(h / 8) * w + w / 8, (h / 8) * w + w - 1 - w / 8, (h - 1 - h / 8) * w + w / 8, (h - 1 - h / 8) * w + w - 1 - w / 8};
        int ok = 1; const char *got[4];
        for (int k = 0; k < 4; k++) { got[k] = name(argb[px[k]]); ok &= !strcmp(got[k], cases[i].want[k]); }
        /* the border between quarters must be in the middle: equal areas of each colour, within 3% */
        int cnt[4] = {0}; const uint8_t *l = flat_labels(f);
        for (int p = 0; p < w * h; p++) cnt[l[p]]++;
        for (int k = 0; k < 4; k++) if (abs(cnt[k] - w * h / 4) > w * h * 3 / 100) ok = 0;
        printf("%s rot %3d mirror %d to %dx%d: %s %s / %s %s\n", ok ? "ok  " : "FAIL", cases[i].rot, cases[i].mirror, w, h, got[0], got[1], got[2], got[3]);
        fails += !ok; free(argb); flat_destroy(f);
    }
    puts(fails ? "FAILED" : "all checks passed");
    return fails != 0;
}
