/* Runs flat.c over raw RGB24 frames. Usage: flat_test in.rgb w h colors smooth hold clean out.i420 [ignore [coarse x0 y0 x1 y1]]
   Prints one line per frame: distinct colours, pixels changed since the previous frame, palette rebuilt, ms. */
#include "../jni/flat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

int main(int argc, char **argv) {
    if (argc < 9) { fprintf(stderr, "usage\n"); return 2; }
    int w = atoi(argv[2]), h = atoi(argv[3]), n = w * h;
    Flat *f = flat_create(w, h, atoi(argv[4]));
    FILE *in = fopen(argv[1], "rb"), *out = fopen(argv[8], "wb");
    if (!f || !in || !out) { fprintf(stderr, "open failed\n"); return 2; }
    uint8_t *rgb = malloc(n * 3), *i420 = malloc(n * 3 / 2), *prev = malloc(n * 3 / 2);
    uint32_t *argb = malloc(n * 4);
    for (int fr = 0; fread(rgb, 1, n * 3, in) == (size_t)n * 3; fr++) {
        struct timespec a, b; clock_gettime(CLOCK_MONOTONIC, &a);
        if (argc > 14) { int box[4] = {atoi(argv[11]), atoi(argv[12]), atoi(argv[13]), atoi(argv[14])}; flat_set_focus(f, box, box[2] > box[0], atoi(argv[10])); }
        flat_load_rgb(f, rgb);
        int rebuilt = flat_run(f, atoi(argv[5]), atoi(argv[6]), atoi(argv[7]), argc > 9 ? atoi(argv[9]) : 0);
        flat_i420(f, i420); flat_argb(f, argb);
        clock_gettime(CLOCK_MONOTONIC, &b);
        int seen[FLAT_MAX_COLORS] = {0}, distinct = 0, changed = 0;
        const uint8_t *l = flat_labels(f);
        for (int i = 0; i < n; i++) if (!seen[l[i]]++) distinct++;
        if (fr) for (int i = 0; i < n; i++) if (i420[i] != prev[i]) changed++;
        memcpy(prev, i420, n * 3 / 2);
        fwrite(i420, 1, n * 3 / 2, out);
        printf("%d %d %d %d %.2f\n", fr, distinct, changed, rebuilt, (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6);
    }
    flat_destroy(f); free(rgb); free(i420); free(prev); free(argb); fclose(in); fclose(out);
    return 0;
}
