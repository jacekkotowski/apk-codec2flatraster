/* Enlarges a flat picture for the screen so that the borders between colours come out smooth instead of as blocks. */
#ifndef SMOOTH_H
#define SMOOTH_H
#include <stdint.h>

/* src: w*h pixels 0xAARRGGBB. dst: (w*factor)*(h*factor) pixels. factor 1..8. Colours within `tol` (sum of the three channel
   differences) count as one colour: 0 for an exact posterized picture, about 40 for one that went through a video coder. */
void smooth_upscale(const uint32_t *src, int w, int h, int factor, int tol, uint32_t *dst);

#endif
