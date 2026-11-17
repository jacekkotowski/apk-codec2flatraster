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
/* Camera frames between two pictures (the camera makes 15 to 30 a second, the app keeps 0.5 to 5): each call stores one, same
   arguments as flat_load_yuv420; the last FLAT_GATHER are kept. The next flat_run then starts from the sharpest of them and the
   frame loaded for it (the loaded one unless another is clearly sharper), and where the others agree with it to within the camera's
   noise it takes their average: less noise, so steadier borders and fewer changed pixels, at no cost in bytes. Where something
   moved the frames disagree and only the chosen one counts, so nothing is smeared. flat_gather_clear forgets the stored frames. */
#define FLAT_GATHER 6
void flat_gather(Flat *f, const uint8_t *y, int yStride, const uint8_t *u, const uint8_t *v,
                 int uvStride, int uvPixStride, int sw, int sh, int rot, int mirror);
void flat_gather_clear(Flat *f);
/* What the last flat_run did with the gathered frames: how many it had, which it started from (0 the loaded one, 1.. a gathered
   one, oldest first), the noise threshold it used, and the share of pixels averaged over at least two frames, in thousandths. */
void flat_gather_info(const Flat *f, int *frames, int *base, int *threshold, int *averaged);

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
   coarse 0: no effect. 1 or 2: everything outside the boxes uses only a half or a quarter of the colours. 3: everything
   outside the boxes is one flat colour, so a background has no borders to cost bits. With no boxes that applies to the
   whole picture. Call before loading a frame. */
void flat_set_focus(Flat *f, const int *rects, int count, int coarse);

/* Colour tracking: 0 keeps the palette locked between rebuilds. Above 0 the colours are refitted to every picture
   (borders fall as after a new palette), and a shown colour changes only once the fit is more than `threshold` away.
   full 1: the fit runs until settled every picture; 0: it glides towards it. */
void flat_set_track(Flat *f, int threshold, int full);
/* full 2: a brand-new palette every picture; each new colour within `threshold` of a colour shown in the previous
   picture is shown as that previous colour (closest pairs first, each previous colour used once). */

/* Border rounding: 0 off, 1 or 2 passes of a 5x5 most-common-colour filter after everything else. */
void flat_set_round(Flat *f, int passes);

/* Holds the face still: the part of the camera frame that becomes the picture is a window that follows the face, and the focus box
   follows it too. Call before every flat_load_* and flat_gather with the face rectangle the camera reported, in the camera frame's own
   pixels (cx < 0: no face seen this time; the window stays where it was).
   zoom100: how much smaller the window is than the frame, in hundredths (100 = the whole frame, which leaves no room to follow; 125
   = a window of 80% of the frame, which can follow a face that moves by 10% of the frame to each side).
   alpha1000: how much of the way to the face's present place the face's smoothed position moves at each call, in thousandths (1000 =
   all of it; less smooths out the trembling of a face detector). The face's size is smoothed four times slower.
   deadzone: the window moves only when the smoothed face is more than this many camera pixels from the window's middle, and then to
   the face. 0 moves it at every call. A window that moves every picture moves every border in the picture, and every border that
   moves costs bits; one that stays put until it must move costs them only then.
   box1000: when 0 or more, the focus box is made here instead of by flat_set_focus: the window's middle, the face's size, widened by
   this share (in thousandths) of the size on every side. It then moves with the window, so the border of the flat background does not
   jump about with the detector's trembling. -1 leaves the boxes to flat_set_focus (whose level, coarse, is used either way).
   With zoom100 100 and no calls to this function the picture is as before. */
void flat_set_hold(Flat *f, int cx, int cy, int fw, int fh, int zoom100, int alpha1000, int deadzone, int box1000);
/* Keeps the window on the face by looking at the picture instead of trusting the detector's rectangle, which trembles by a few
   pixels from frame to frame (and every pixel the face shifts in the picture moves its whole outline, which costs bits). Call after
   flat_set_hold and before loading the same camera frame, for every camera frame, with its brightness plane. Each call measures how
   far the face has moved since the previous call (the brightness inside the face rectangle is matched against the previous frame,
   to a fraction of a pixel) and moves the window by that. The detector's smoothed position pulls the window back slowly, so errors
   cannot add up. Without calls to this function the window follows the detector. */
void flat_track(Flat *f, const uint8_t *y, int yStride, int sw, int sh);
/* The window of the last frame loaded, as a share of the frame in thousandths: left, top, width, height (of the upright, unmirrored
   picture). For tests. */
void flat_window(const Flat *f, int *left, int *top, int *width, int *height);

/* Forces a new palette on the next flat_run. */
void flat_reset(Flat *f);

const uint8_t *flat_labels(const Flat *f);            /* w*h palette indexes */
int flat_colors(const Flat *f);
void flat_palette_rgb(const Flat *f, uint8_t *rgb);   /* colors*3 bytes */
void flat_i420(const Flat *f, uint8_t *dst);          /* limited-range BT.601, w*h*3/2 bytes */
void flat_argb(const Flat *f, uint32_t *dst);         /* 0xAARRGGBB, w*h ints */

#endif
