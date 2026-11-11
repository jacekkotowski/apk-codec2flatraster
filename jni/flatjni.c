#include <jni.h>
#include <stdint.h>
#include "flat.h"
#define F(n) Java_pl_flatraster_Flat_##n
#define H(h) ((Flat *)(intptr_t)(h))

JNIEXPORT jlong JNICALL F(create)(JNIEnv *e, jclass c, jint w, jint h, jint colors) { return (jlong)(intptr_t)flat_create(w, h, colors); }
JNIEXPORT void JNICALL F(destroy)(JNIEnv *e, jclass c, jlong h) { if (h) flat_destroy(H(h)); }
JNIEXPORT void JNICALL F(reset)(JNIEnv *e, jclass c, jlong h) { if (h) flat_reset(H(h)); }
JNIEXPORT void JNICALL F(setFocus)(JNIEnv *e, jclass c, jlong h, jintArray rects, jint count, jint coarse) {
    if (!h) return;
    jint box[4 * FLAT_MAX_FOCUS];
    if (count > FLAT_MAX_FOCUS) count = FLAT_MAX_FOCUS;
    if (!rects || count < 0 || (*e)->GetArrayLength(e, rects) < 4 * count) count = 0;
    if (count) (*e)->GetIntArrayRegion(e, rects, 0, 4 * count, box);
    flat_set_focus(H(h), (const int *)box, count, coarse);
}
JNIEXPORT void JNICALL F(setTrack)(JNIEnv *e, jclass c, jlong h, jint threshold, jboolean full) { if (h) flat_set_track(H(h), threshold, full ? 1 : 0); }
JNIEXPORT void JNICALL F(setRound)(JNIEnv *e, jclass c, jlong h, jint passes) { if (h) flat_set_round(H(h), passes); }
JNIEXPORT jint JNICALL F(changed)(JNIEnv *e, jclass c, jlong h) { return h ? flat_changed(H(h)) : 0; }
JNIEXPORT void JNICALL F(markSent)(JNIEnv *e, jclass c, jlong h) { if (h) flat_mark_sent(H(h)); }

/* Planes are the camera Image's direct buffers. Returns 1 if the palette was rebuilt, -1 on bad input. */
JNIEXPORT jint JNICALL F(process)(JNIEnv *e, jclass c, jlong h, jobject yb, jint yStride, jobject ub, jobject vb, jint uvStride, jint uvPix,
                                  jint sw, jint sh, jint rot, jboolean mirror, jint smooth, jint hold, jint clean, jint ignore, jbyteArray i420, jintArray argb) {
    const uint8_t *y = (*e)->GetDirectBufferAddress(e, yb), *u = (*e)->GetDirectBufferAddress(e, ub), *v = (*e)->GetDirectBufferAddress(e, vb);
    if (!h || !y || !u || !v || sw < 2 || sh < 2) return -1;
    /* The last row of a plane may be shorter than its stride: check the furthest byte that will be read. */
    jlong yn = (*e)->GetDirectBufferCapacity(e, yb), un = (*e)->GetDirectBufferCapacity(e, ub), vn = (*e)->GetDirectBufferCapacity(e, vb);
    jlong ymax = (jlong)(sh - 1) * yStride + sw, cmax = (jlong)((sh - 1) / 2) * uvStride + (jlong)((sw - 1) / 2) * uvPix + 1;
    if (ymax > yn || cmax > un || cmax > vn) return -1;
    flat_load_yuv420(H(h), y, yStride, u, v, uvStride, uvPix, sw, sh, rot, mirror ? 1 : 0);
    int rebuilt = flat_run(H(h), smooth, hold, clean, ignore);
    jbyte *o = (*e)->GetByteArrayElements(e, i420, 0); flat_i420(H(h), (uint8_t *)o); (*e)->ReleaseByteArrayElements(e, i420, o, 0);
    jint *a = (*e)->GetIntArrayElements(e, argb, 0); flat_argb(H(h), (uint32_t *)a); (*e)->ReleaseIntArrayElements(e, argb, a, 0);
    return rebuilt;
}
