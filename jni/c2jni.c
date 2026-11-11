#include <jni.h>
#include <stdint.h>
#include "codec2.h"
#define F(n) Java_pl_dyktafon_C2_##n
JNIEXPORT jlong JNICALL F(create)(JNIEnv *e, jclass c, jint mode) { return (jlong)(intptr_t)codec2_create(mode); }
JNIEXPORT void JNICALL F(destroy)(JNIEnv *e, jclass c, jlong h) { if (h) codec2_destroy((struct CODEC2 *)(intptr_t)h); }
JNIEXPORT jint JNICALL F(samples)(JNIEnv *e, jclass c, jlong h) { return codec2_samples_per_frame((struct CODEC2 *)(intptr_t)h); }
JNIEXPORT jint JNICALL F(bytes)(JNIEnv *e, jclass c, jlong h) { return codec2_bytes_per_frame((struct CODEC2 *)(intptr_t)h); }
JNIEXPORT void JNICALL F(encode)(JNIEnv *e, jclass c, jlong h, jshortArray in, jbyteArray out) {
  jshort *s = (*e)->GetShortArrayElements(e, in, 0); jbyte *b = (*e)->GetByteArrayElements(e, out, 0);
  codec2_encode((struct CODEC2 *)(intptr_t)h, (unsigned char *)b, s);
  (*e)->ReleaseShortArrayElements(e, in, s, JNI_ABORT); (*e)->ReleaseByteArrayElements(e, out, b, 0);
}
JNIEXPORT void JNICALL F(decode)(JNIEnv *e, jclass c, jlong h, jbyteArray in, jshortArray out) {
  jbyte *b = (*e)->GetByteArrayElements(e, in, 0); jshort *s = (*e)->GetShortArrayElements(e, out, 0);
  codec2_decode((struct CODEC2 *)(intptr_t)h, s, (const unsigned char *)b);
  (*e)->ReleaseByteArrayElements(e, in, b, JNI_ABORT); (*e)->ReleaseShortArrayElements(e, out, s, 0);
}
JNIEXPORT void JNICALL F(eq)(JNIEnv *e, jclass c, jlong h, jboolean en) { codec2_700c_eq((struct CODEC2 *)(intptr_t)h, en ? 1 : 0); }
