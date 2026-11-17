/* Runs flat.c over raw I420 camera frames with face boxes, the way the app does, to test holding the face still (flat_set_hold)
   and the one-colour background (flat_set_focus level 3). Of every `per` camera frames the last becomes a picture; the others are
   joined into it (flat_gather).
   Usage: hold_test in.i420 faces.txt sw sh w h colors smooth hold clean round per coarse zoom alpha out deadzone box1000 [track]
     faces.txt: one line per camera frame: cx cy fw fh x0 y0 x1 y1 (cx < 0: no face): the face rectangle's centre and size, and a focus box
     already widened and snapped. coarse 0 uses no focus box. zoom 100 or alpha 0 hold nothing; box1000 >= 0 makes the box in flat.c.
   Writes out.lab, out.pal, out.i420. Prints per picture: number, rebuilt, pixels changed, window left top width height (thousandths). */
#include "../jni/flat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 19) { fprintf(stderr, "usage\n"); return 2; }
    int sw = atoi(argv[3]), sh = atoi(argv[4]), w = atoi(argv[5]), h = atoi(argv[6]), n = w * h;
    int smooth = atoi(argv[8]), hold = atoi(argv[9]), clean = atoi(argv[10]), per = atoi(argv[12]), coarse = atoi(argv[13]), zoom = atoi(argv[14]), alpha = atoi(argv[15]), dz = atoi(argv[17]), boxw = atoi(argv[18]), track = argc > 19 ? atoi(argv[19]) : 0;
    Flat *f = flat_create(w, h, atoi(argv[7]));
    char name[1024]; FILE *in = fopen(argv[1], "rb"), *fc = fopen(argv[2], "r"), *o[3]; const char *ext[3] = {"lab", "pal", "i420"};
    for (int i = 0; i < 3; i++) { snprintf(name, sizeof name, "%s.%s", argv[16], ext[i]); o[i] = fopen(name, "wb"); }
    if (!f || !in || !fc || !o[0] || !o[1] || !o[2]) { fprintf(stderr, "open failed\n"); return 2; }
    flat_set_track(f, 0, 2); flat_set_round(f, atoi(argv[11]));
    size_t fs = (size_t)sw * sh * 3 / 2;
    uint8_t *src = malloc(fs), *i420 = malloc(n * 3 / 2), *prev = malloc(n), pal[3 * FLAT_MAX_COLORS];
    for (int fr = 0, pic = 0; fread(src, 1, fs, in) == fs; fr++) {
        int cx, cy, fw, fh, box[4];
        if (fscanf(fc, "%d %d %d %d %d %d %d %d", &cx, &cy, &fw, &fh, &box[0], &box[1], &box[2], &box[3]) != 8) return 3;
        flat_set_focus(f, box, cx >= 0 && coarse ? 1 : 0, coarse);
        if (zoom > 100 && alpha > 0) { flat_set_hold(f, cx, cy, fw, fh, zoom, alpha, dz, boxw); if (track) flat_track(f, src, sw, sw, sh); } else flat_set_hold(f, -1, 0, 0, 0, zoom, 1000, 0, -1);
        const uint8_t *u = src + sw * sh, *v = src + sw * sh * 5 / 4;
        if (fr % per != per - 1) { flat_gather(f, src, sw, u, v, sw / 2, 1, sw, sh, 0, 0); continue; }
        flat_load_yuv420(f, src, sw, u, v, sw / 2, 1, sw, sh, 0, 0);
        int rebuilt = flat_run(f, smooth, hold, clean, 0), changed = 0, win[4];
        const uint8_t *l = flat_labels(f);
        if (pic) for (int i = 0; i < n; i++) changed += l[i] != prev[i];
        memcpy(prev, l, n);
        flat_palette_rgb(f, pal); flat_i420(f, i420); flat_window(f, &win[0], &win[1], &win[2], &win[3]);
        fwrite(l, 1, n, o[0]); fwrite(pal, 1, 3 * flat_colors(f), o[1]); fwrite(i420, 1, n * 3 / 2, o[2]);
        printf("%d %d %d %d %d %d %d\n", pic++, rebuilt, changed, win[0], win[1], win[2], win[3]);
    }
    flat_destroy(f); free(src); free(i420); free(prev); fclose(fc); for (int i = 0; i < 3; i++) fclose(o[i]); fclose(in);
    return 0;
}
