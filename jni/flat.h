/* Posterize filter: edge-preserving smoothing, locked palette, temporal hold, small-region cleanup. */
#ifndef FLAT_H
#define FLAT_H
#include <stdint.h>

#define FLAT_MAX_COLORS 32
#define FLAT_MAX_FOCUS 4

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

/* Runs the filter on the loaded frame. smooth: bilateral passes 0..6. hold: 0..60, how far past the border
   between two colours a pixel must be before it changes colour (in half colour steps). clean: majority-filter passes 0..3. ignore: 0 off, 1..3 how readily
   repeating movement (leaves, water) is frozen. Returns 1 if the palette was rebuilt on this frame. */
int flat_run(Flat *f, int smooth, int hold, int clean, int ignore);

/* Pixels that differ from the last picture marked as sent, and the mark itself. Lets the caller skip still pictures. */
int flat_changed(const Flat *f);
void flat_mark_sent(Flat *f);

/* Focus boxes (faces): count boxes of x0,y0,x1,y1 in the pixels of the frame given to the next flat_load_*.
   coarse 0: no effect. 1 or 2: everything outside the boxes uses only a half or a quarter of the colours. With no
   boxes that applies to the whole picture. Call before loading a frame. */
void flat_set_focus(Flat *f, const int *rects, int count, int coarse);

/* Border rounding: 0 off, 1 or 2 passes of a 5x5 most-common-colour filter after everything else. */
void flat_set_round(Flat *f, int passes);

/* Forces a new palette on the next flat_run. */
void flat_reset(Flat *f);

const uint8_t *flat_labels(const Flat *f);            /* w*h palette indexes */
int flat_colors(const Flat *f);
void flat_palette_rgb(const Flat *f, uint8_t *rgb);   /* colors*3 bytes */
void flat_i420(const Flat *f, uint8_t *dst);          /* limited-range BT.601, w*h*3/2 bytes */
void flat_argb(const Flat *f, uint32_t *dst);         /* 0xAARRGGBB, w*h ints */

#endif
