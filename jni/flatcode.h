/* The exact picture coder: a posterized picture (a map of palette indexes) sent without loss with an adaptive range coder.
   A C port of tools/coder/flatcoder.py (stage 6a); the bytes are identical, tests/flatcode_test.py checks that. Format in that file's header.
   Key picture:   k (1 byte), palette (3*k bytes RGB), coded map.
   Delta picture: flags (1 byte, bit 0: a new palette follows), [palette 3*k bytes], coded change map + coded new colours of the changed pixels.
   Encoders return the length, or -1 if `cap` is too small. Decoders return the number of colours (key) or 1/0 = palette present/absent
   (delta), or -1 if the data does not decode. A damaged picture can decode to nonsense but never reads or writes outside its buffers. */
#ifndef FLATCODE_H
#define FLATCODE_H
#include <stdint.h>
#define FLATCODE_MAX_COLORS 32
int flatcode_encode_key(const uint8_t *lab, int w, int h, const uint8_t *pal, int k, uint8_t *out, int cap);
int flatcode_encode_delta(const uint8_t *prev, const uint8_t *lab, int w, int h, int k, const uint8_t *pal_or_null, uint8_t *out, int cap);
int flatcode_decode_key(const uint8_t *data, int len, int w, int h, uint8_t *lab, uint8_t *pal);
int flatcode_decode_delta(const uint8_t *data, int len, const uint8_t *prev, int w, int h, int k, uint8_t *lab, uint8_t *pal);
#endif
