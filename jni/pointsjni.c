/* JNI for pl.flatraster.Points: the landmark point coder (motion.c). */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "motion.h"
#define P(n) Java_pl_flatraster_Points_##n

JNIEXPORT jlong JNICALL P(create)(JNIEnv *e, jclass c, jintArray n, jint alpha) {
    (void)c;
    if (!n) return 0;
    jint cnt = (*e)->GetArrayLength(e, n);
    if (cnt < 1 || cnt > MOTION_MAX_OBJECTS) return 0;
    jint v[MOTION_MAX_OBJECTS];
    (*e)->GetIntArrayRegion(e, n, 0, cnt, v);
    int nn[MOTION_MAX_OBJECTS];
    for (int i = 0; i < cnt; i++) nn[i] = v[i];
    return (jlong)(intptr_t)motion_create(cnt, nn, alpha);
}
JNIEXPORT void JNICALL P(destroy)(JNIEnv *e, jclass c, jlong h) { (void)e; (void)c; motion_destroy((MotionCodec *)(intptr_t)h); }
JNIEXPORT void JNICALL P(reset)(JNIEnv *e, jclass c, jlong h) { (void)e; (void)c; if (h) motion_reset((MotionCodec *)(intptr_t)h); }

/* present: one 0/1 per object; units: the points of the present objects (x, y integer units each). Returns the coded length, or -1. */
JNIEXPORT jint JNICALL P(encode)(JNIEnv *e, jclass c, jlong h, jintArray present, jintArray units, jbyteArray out) {
    (void)c;
    if (!h || !present || !units || !out) return -1;
    jint np = (*e)->GetArrayLength(e, present), nu = (*e)->GetArrayLength(e, units), cap = (*e)->GetArrayLength(e, out);
    if (np < 1 || np > MOTION_MAX_OBJECTS || nu < 1) return -1;
    jint pr[MOTION_MAX_OBJECTS];
    (*e)->GetIntArrayRegion(e, present, 0, np, pr);
    int32_t *u = malloc(sizeof(int32_t) * (size_t)nu); uint8_t *buf = malloc((size_t)cap);
    int r = -1;
    if (u && buf) {
        (*e)->GetIntArrayRegion(e, units, 0, nu, u);
        r = motion_encode((MotionCodec *)(intptr_t)h, pr, u, nu, buf, cap);
        if (r >= 0) (*e)->SetByteArrayRegion(e, out, 0, r, (const jbyte *)buf);
    }
    free(u); free(buf);
    return r;
}

/* Decodes into present (one per object) and units (room for all points of all objects). Returns 0, or -1. */
JNIEXPORT jint JNICALL P(decode)(JNIEnv *e, jclass c, jlong h, jbyteArray data, jint len, jintArray present, jintArray units) {
    (void)c;
    if (!h || !data || !present || !units || len < 0 || len > (*e)->GetArrayLength(e, data)) return -1;
    jint np = (*e)->GetArrayLength(e, present), nu = (*e)->GetArrayLength(e, units);
    if (np < 1 || np > MOTION_MAX_OBJECTS || nu < 1) return -1;
    uint8_t *d = malloc((size_t)len + 1); int32_t *u = calloc((size_t)nu, sizeof(int32_t));
    int32_t pr[MOTION_MAX_OBJECTS] = {0};
    int r = -1;
    if (d && u && nu >= motion_points((MotionCodec *)(intptr_t)h) * 2) {
        (*e)->GetByteArrayRegion(e, data, 0, len, (jbyte *)d);
        /* the units buffer must hold all points: the caller sizes it; a smaller one is refused, never overrun */
        r = motion_decode((MotionCodec *)(intptr_t)h, d, len, pr, u);
        if (r == 0) {
            (*e)->SetIntArrayRegion(e, present, 0, np, pr);
            (*e)->SetIntArrayRegion(e, units, 0, nu, u);
        }
    }
    free(d); free(u);
    return r;
}
