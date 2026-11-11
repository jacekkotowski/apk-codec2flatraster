/* Posterize filter: edge-preserving smoothing, locked palette, temporal hold, small-region cleanup. */
#ifndef FLAT_H
#define FLAT_H
#include <stdint.h>

#define FLAT_MAX_COLORS 32

typedef struct Flat Flat;

/* w and h must be even. colors is clamped to 2..FLAT_MAX_COLORS. Returns NULL when out of memory. */
Flat *flat_create(int w, int h, int colors);
void flat_destroy(Flat *f);

/* Loads a camera frame (full-range YUV 4:2:0 with strides), rotating clockwise by rot degrees
   (0, 90, 180, 270), optionally mirroring, centre-cropping to the output shape and averaging down. */
void flat_load_yuv420(Flat *f, const uint8_t *y, int yStride, const uint8_t *u, const uint8_t *v,
                      int uvStride, int uvPixStride, int sw, int sh, int rot, int mirror);
/* Loads a tightly packed RGB24 frame that already has the output size. For tests. */
void flat_load_rgb(Flat *f, const uint8_t *rgb);

/* Runs the filter on the loaded frame. smooth: bilateral passes 0..6. hold: 0..60, how far a pixel
   may drift before it is relabelled. clean: majority-filter passes 0..3.
   Returns 1 if the palette was rebuilt on this frame. */
int flat_run(Flat *f, int smooth, int hold, int clean);

/* Forces a new palette on the next flat_run. */
void flat_reset(Flat *f);

const uint8_t *flat_labels(const Flat *f);            /* w*h palette indexes */
int flat_colors(const Flat *f);
void flat_palette_rgb(const Flat *f, uint8_t *rgb);   /* colors*3 bytes */
void flat_i420(const Flat *f, uint8_t *dst);          /* limited-range BT.601, w*h*3/2 bytes */
void flat_argb(const Flat *f, uint32_t *dst);         /* 0xAARRGGBB, w*h ints */

#endif
