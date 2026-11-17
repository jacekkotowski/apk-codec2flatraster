/* Landmark points of one picture coded from the last one: a C port of tools/edge/motion.py (byte-identical, tests/motion_test.py).
   Objects (face, pose, ...) of n[o] points each; coordinates are integers in units of the quantisation step (the caller rounds). Layout of the
   coded bytes: see the header of tools/edge/motion.py. State (models and the last two pictures) lives in the codec; motion_reset at each key
   picture, on both sides. Decoder never reads or writes outside its arguments; damaged data decodes to something or returns -1. */
#ifndef MOTION_H
#define MOTION_H
#include <stdint.h>
#define MOTION_MAX_OBJECTS 8
#define MOTION_MAX_POINTS 64
typedef struct MotionCodec MotionCodec;
MotionCodec *motion_create(int nobj, const int *n, int alpha);   /* alpha 0: predict the last position; 1: last + half the last step */
void motion_destroy(MotionCodec *m);
void motion_reset(MotionCodec *m);
/* present[o]: 0/1. units: the points of the present objects, in order, x then y each (sum of n[o]*2 over all objects fits; absent ones are skipped). */
int motion_points(const MotionCodec *m);   /* total number of points of all objects */
int motion_encode(MotionCodec *m, const int32_t *present, const int32_t *units, int nunits, uint8_t *out, int cap);   /* -1 if units holds fewer values than the present objects need */
int motion_decode(MotionCodec *m, const uint8_t *data, int len, int32_t *present, int32_t *units);
#endif
