/* Runs flat.c over raw I420 camera frames the way the app does: of every `per` camera frames the last one becomes a picture.
   mode 0: the frames between are dropped (as up to 0.15). mode 1: they are gathered and joined into the picture (flat_gather).
   Usage: cam_test in.i420 sw sh w h colors smooth hold clean round per mode out [faceWeight x0 y0 x1 y1]
   Writes out.lab (palette indexes), out.pal (RGB palette per picture), out.i420. Prints one line per picture:
   number, palette rebuilt, pixels changed, frames gathered, frame chosen (0 the last one), noise threshold, thousandths of pixels averaged. */
#include "../jni/flat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 14) { fprintf(stderr, "usage\n"); return 2; }
    int sw = atoi(argv[2]), sh = atoi(argv[3]), w = atoi(argv[4]), h = atoi(argv[5]), n = w * h;
    int smooth = atoi(argv[7]), hold = atoi(argv[8]), clean = atoi(argv[9]), per = atoi(argv[11]), mode = atoi(argv[12]);
    Flat *f = flat_create(w, h, atoi(argv[6]));
    char name[1024]; FILE *in = fopen(argv[1], "rb"), *o[3]; const char *ext[3] = {"lab", "pal", "i420"};
    for (int i = 0; i < 3; i++) { snprintf(name, sizeof name, "%s.%s", argv[13], ext[i]); o[i] = fopen(name, "wb"); }
    if (!f || !in || !o[0] || !o[1] || !o[2]) { fprintf(stderr, "open failed\n"); return 2; }
    flat_set_track(f, 0, 2); flat_set_round(f, atoi(argv[10]));
    if (argc > 18) { int box[4] = {atoi(argv[15]), atoi(argv[16]), atoi(argv[17]), atoi(argv[18])}; flat_set_focus(f, box, 1, 0); flat_set_face_weight(f, atoi(argv[14])); }
    size_t fs = (size_t)sw * sh * 3 / 2;
    uint8_t *src = malloc(fs), *i420 = malloc(n * 3 / 2), *prev = malloc(n), pal[3 * FLAT_MAX_COLORS];
    for (int fr = 0, pic = 0; fread(src, 1, fs, in) == fs; fr++) {
        const uint8_t *u = src + sw * sh, *v = src + sw * sh * 5 / 4;
        if (fr % per != per - 1) { if (mode) flat_gather(f, src, sw, u, v, sw / 2, 1, sw, sh, 0, 0); continue; }
        flat_load_yuv420(f, src, sw, u, v, sw / 2, 1, sw, sh, 0, 0);
        int rebuilt = flat_run(f, smooth, hold, clean, 0), changed = 0, g[4];
        const uint8_t *l = flat_labels(f);
        if (pic) for (int i = 0; i < n; i++) changed += l[i] != prev[i];
        memcpy(prev, l, n);
        flat_palette_rgb(f, pal); flat_i420(f, i420); flat_gather_info(f, &g[0], &g[1], &g[2], &g[3]);
        fwrite(l, 1, n, o[0]); fwrite(pal, 1, 3 * flat_colors(f), o[1]); fwrite(i420, 1, n * 3 / 2, o[2]);
        printf("%d %d %d %d %d %d %d\n", pic++, rebuilt, changed, g[0], g[1], g[2], g[3]);
    }
    flat_destroy(f); free(src); free(i420); free(prev); for (int i = 0; i < 3; i++) fclose(o[i]); fclose(in);
    return 0;
}
