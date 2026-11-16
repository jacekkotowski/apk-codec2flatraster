/* Stage 6a (ROADMAP.md): runs the real posterize filter (jni/flat.c) over raw frames and writes what a picture coder needs.
   Usage: dump yuv|rgb in sw sh w h colors smooth hold clean ignore round out
     yuv: I420 camera frames of sw x sh, loaded as the app does (flat_load_yuv420, no rotation); rgb: RGB24 frames of w x h (sw, sh ignored).
   Writes out.lab (w*h palette indexes per picture), out.pal (colors*3 RGB bytes per picture), out.i420 (what the app gives a video encoder).
   Prints one line per picture: number, palette rebuilt (0 or 1), pixels changed since the previous picture. */
#include "../../jni/flat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 14) { fprintf(stderr, "usage: dump yuv|rgb in sw sh w h colors smooth hold clean ignore round out\n"); return 2; }
    int yuv = !strcmp(argv[1], "yuv"), sw = atoi(argv[3]), sh = atoi(argv[4]), w = atoi(argv[5]), h = atoi(argv[6]), n = w * h;
    int colors = atoi(argv[7]), smooth = atoi(argv[8]), hold = atoi(argv[9]), clean = atoi(argv[10]), ignore = atoi(argv[11]), round_ = atoi(argv[12]);
    Flat *f = flat_create(w, h, colors);
    char name[1024];
    FILE *in = fopen(argv[2], "rb"), *o[3];
    const char *ext[3] = {"lab", "pal", "i420"};
    for (int i = 0; i < 3; i++) { snprintf(name, sizeof name, "%s.%s", argv[13], ext[i]); o[i] = fopen(name, "wb"); }
    if (!f || !in || !o[0] || !o[1] || !o[2]) { fprintf(stderr, "open failed\n"); return 2; }
    flat_set_track(f, 0, 2); /* as PictureTest and the app's default: colours locked between rebuilds */
    flat_set_round(f, round_);
    size_t fs = yuv ? (size_t)sw * sh * 3 / 2 : (size_t)n * 3;
    uint8_t *src = malloc(fs), *i420 = malloc(n * 3 / 2), *prev = malloc(n), pal[3 * FLAT_MAX_COLORS];
    for (int fr = 0; fread(src, 1, fs, in) == fs; fr++) {
        if (yuv) flat_load_yuv420(f, src, sw, src + sw * sh, src + sw * sh * 5 / 4, sw / 2, 1, sw, sh, 0, 0);
        else flat_load_rgb(f, src);
        int rebuilt = flat_run(f, smooth, hold, clean, ignore), changed = 0;
        const uint8_t *l = flat_labels(f);
        if (fr) for (int i = 0; i < n; i++) changed += l[i] != prev[i];
        memcpy(prev, l, n);
        flat_palette_rgb(f, pal); flat_i420(f, i420);
        fwrite(l, 1, n, o[0]); fwrite(pal, 1, 3 * flat_colors(f), o[1]); fwrite(i420, 1, n * 3 / 2, o[2]);
        printf("%d %d %d\n", fr, rebuilt, changed);
    }
    flat_destroy(f); for (int i = 0; i < 3; i++) fclose(o[i]); fclose(in);
    return 0;
}
