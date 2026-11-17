/* JNI for pl.flatraster.Exact: the exact picture coder (flatcode.c) fed straight from the filter's picture and palette. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "flat.h"
#include "flatcode.h"
#define X(n) Java_pl_flatraster_Exact_##n

typedef struct { int w, h, k, have; uint8_t *lab; uint8_t pal[3 * FLATCODE_MAX_COLORS]; } Ex;   /* the last picture sent / shown */

JNIEXPORT jlong JNICALL X(create)(JNIEnv *e, jclass c, jint w, jint h) {
    if (w < 2 || h < 2 || w > 4096 || h > 4096) return 0;
    Ex *x = calloc(1, sizeof *x);
    if (!x) return 0;
    x->w = w; x->h = h; x->lab = calloc((size_t)w * (size_t)h, 1);
    if (!x->lab) { free(x); return 0; }
    return (jlong)(intptr_t)x;
}
JNIEXPORT void JNICALL X(destroy)(JNIEnv *e, jclass c, jlong h) { Ex *x = (Ex *)(intptr_t)h; if (x) { free(x->lab); free(x); } }

/* Codes the filter's present picture. forceKey, or no picture yet, or another number of colours: a key picture. Returns the length of
   the coded picture | 1 << 30 for a key picture; -1 if it could not be coded (buffer too small, more colours than the coder takes). */
JNIEXPORT jint JNICALL X(encode)(JNIEnv *e, jclass c, jlong h, jlong flat, jboolean forceKey, jbyteArray out) {
    Ex *x = (Ex *)(intptr_t)h; Flat *f = (Flat *)(intptr_t)flat;
    if (!x || !f || !out) return -1;
    int k = flat_colors(f);
    if (k < 1 || k > FLATCODE_MAX_COLORS) return -1;
    uint8_t pal[3 * FLATCODE_MAX_COLORS]; flat_palette_rgb(f, pal);
    const uint8_t *lab = flat_labels(f);
    int cap = (*e)->GetArrayLength(e, out);
    uint8_t *buf = malloc((size_t)cap);
    if (!buf) return -1;
    int key = forceKey || !x->have || x->k != k, n;
    if (key) n = flatcode_encode_key(lab, x->w, x->h, pal, k, buf, cap);
    else n = flatcode_encode_delta(x->lab, lab, x->w, x->h, k, memcmp(pal, x->pal, (size_t)(3 * k)) ? pal : NULL, buf, cap);
    if (n >= 0) {
        (*e)->SetByteArrayRegion(e, out, 0, n, (const jbyte *)buf);
        memcpy(x->lab, lab, (size_t)x->w * (size_t)x->h); memcpy(x->pal, pal, (size_t)(3 * k)); x->k = k; x->have = 1;
    }
    free(buf);
    return n < 0 ? -1 : (n | (key ? 1 << 30 : 0));
}

/* Decodes one packet into argb (w*h ints). Returns 0, or -1 if the packet does not decode or a delta comes before any key picture. */
JNIEXPORT jint JNICALL X(decode)(JNIEnv *e, jclass c, jlong h, jboolean key, jbyteArray data, jintArray argb) {
    Ex *x = (Ex *)(intptr_t)h;
    if (!x || !data || !argb || (*e)->GetArrayLength(e, argb) < x->w * x->h) return -1;
    int len = (*e)->GetArrayLength(e, data);
    jbyte *d = (*e)->GetByteArrayElements(e, data, NULL);
    if (!d) return -1;
    uint8_t pal[3 * FLATCODE_MAX_COLORS]; uint8_t *lab = malloc((size_t)x->w * (size_t)x->h);
    int r = -1;
    if (lab) {
        if (key) {
            int k = flatcode_decode_key((const uint8_t *)d, len, x->w, x->h, lab, pal);
            if (k > 0) { memcpy(x->pal, pal, (size_t)(3 * k)); x->k = k; r = 0; }
        } else if (x->have) {
            int p = flatcode_decode_delta((const uint8_t *)d, len, x->lab, x->w, x->h, x->k, lab, pal);
            if (p >= 0) { if (p == 1) memcpy(x->pal, pal, (size_t)(3 * x->k)); r = 0; }
        }
        if (r == 0) {
            memcpy(x->lab, lab, (size_t)x->w * (size_t)x->h); x->have = 1;
            jint *o = (*e)->GetIntArrayElements(e, argb, NULL);
            if (o) {
                for (int i = 0; i < x->w * x->h; i++) { int v = x->lab[i] < x->k ? x->lab[i] : 0; o[i] = (jint)(0xff000000u | (uint32_t)x->pal[3 * v] << 16 | (uint32_t)x->pal[3 * v + 1] << 8 | x->pal[3 * v + 2]); }
                (*e)->ReleaseIntArrayElements(e, argb, o, 0);
            } else r = -1;
        }
        free(lab);
    }
    (*e)->ReleaseByteArrayElements(e, data, d, JNI_ABORT);
    return r;
}
