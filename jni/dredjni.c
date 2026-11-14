/* DRED coder and player for the app, the same calls as enc2 and dec2 in tests/dred/dredtest.c.
   Audio is 16 kHz, 16 bit, mono, in frames of 320 samples (20 ms). A block is 50 frames (one second). */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "opus.h"
#include "cpu_support.h"
#include "dred_config.h"
#include "dred_encoder.h"
#include "dred_decoder.h"

#define FS 16000
#define FRAME 320
#define EVERY 50
#define F(n) Java_pl_dyktafon_DredLib_##n

typedef struct { DREDEnc e; int q, frames, arch; unsigned char act[DRED_MAX_FRAMES * 4]; } Enc;
typedef struct { OpusDecoder *d; OpusDREDDecoder *dd; OpusDRED *dred; } Dec;

JNIEXPORT jint JNICALL F(bitstream)(JNIEnv *e, jclass c) { return DRED_EXPERIMENTAL_VERSION; }

JNIEXPORT jlong JNICALL F(encOpen)(JNIEnv *e, jclass c, jint q) {
  Enc *h = calloc(1, sizeof(Enc));
  if (!h) return 0;
  h->arch = opus_select_arch();
  dred_encoder_init(&h->e, FS, 1);
  if (!h->e.loaded) { free(h); return 0; }
  h->q = q;
  memset(h->act, 1, sizeof h->act); /* everything counts as speech; pauses are the app's job */
  return (jlong)(intptr_t)h;
}

/* Codes one frame. Returns the block length when this frame completes a block (bytes in out), otherwise -1. */
JNIEXPORT jint JNICALL F(encFrame)(JNIEnv *e, jclass c, jlong hh, jshortArray in, jbyteArray out) {
  Enc *h = (Enc *)(intptr_t)hh; float f[FRAME]; jshort s[FRAME];
  (*e)->GetShortArrayRegion(e, in, 0, FRAME, s);
  for (int i = 0; i < FRAME; i++) f[i] = s[i] / 32768.f;
  dred_compute_latents(&h->e, f, FRAME, 0, h->arch);
  if (++h->frames % EVERY) return -1;
  unsigned char buf[DRED_MAX_DATA_SIZE];
  int n = dred_encode_silk_frame(&h->e, buf, EVERY / 2, sizeof buf, h->q, 0, h->q, h->act, h->arch);
  if (n < 0) n = 0;
  (*e)->SetByteArrayRegion(e, out, 0, n, (const jbyte *)buf);
  return n;
}

/* Frames fed since the last whole block. */
JNIEXPORT jint JNICALL F(encPending)(JNIEnv *e, jclass c, jlong hh) { return ((Enc *)(intptr_t)hh)->frames % EVERY; }
JNIEXPORT void JNICALL F(encClose)(JNIEnv *e, jclass c, jlong hh) { free((Enc *)(intptr_t)hh); }

/* A decoder that has never seen a packet can only give silence. One normal packet of silence starts it. */
JNIEXPORT jlong JNICALL F(decOpen)(JNIEnv *e, jclass c) {
  int err; unsigned char pkt[400]; opus_int16 z[FRAME] = {0}, out[FRAME];
  OpusEncoder *en = opus_encoder_create(FS, 1, OPUS_APPLICATION_VOIP, &err);
  if (!en) return 0;
  opus_encoder_ctl(en, OPUS_SET_BITRATE(12000));
  int n = opus_encode(en, z, FRAME, pkt, sizeof pkt);
  opus_encoder_destroy(en);
  Dec *h = calloc(1, sizeof(Dec));
  if (!h) return 0;
  h->d = opus_decoder_create(FS, 1, &err);
  h->dd = opus_dred_decoder_create(&err);
  h->dred = opus_dred_alloc(&err);
  if (!h->d || !h->dd || !h->dred || n < 0) goto fail;
  opus_decoder_ctl(h->d, OPUS_SET_COMPLEXITY(5)); /* 5 and up turns on the neural concealment that plays DRED */
  if (opus_decode(h->d, pkt, n, out, FRAME, 0) < 0) goto fail;
  return (jlong)(intptr_t)h;
fail:
  if (h->d) opus_decoder_destroy(h->d);
  if (h->dd) opus_dred_decoder_destroy(h->dd);
  if (h->dred) opus_dred_free(h->dred);
  free(h); return 0;
}

/* Plays one block of n bytes into out (EVERY * FRAME samples). Returns the samples written, or a negative Opus error. */
JNIEXPORT jint JNICALL F(decBlock)(JNIEnv *e, jclass c, jlong hh, jbyteArray in, jint n, jshortArray out) {
  Dec *h = (Dec *)(intptr_t)hh; opus_int16 pcm[EVERY * FRAME];
  if (n <= 0) { memset(pcm, 0, sizeof pcm); (*e)->SetShortArrayRegion(e, out, 0, EVERY * FRAME, pcm); return EVERY * FRAME; }
  unsigned char buf[DRED_MAX_DATA_SIZE];
  if (n > DRED_MAX_DATA_SIZE) return OPUS_BAD_ARG;
  (*e)->GetByteArrayRegion(e, in, 0, n, (jbyte *)buf);
  dred_ec_decode(h->dred, buf, n, 2 * EVERY, 0);
  int r = opus_dred_process(h->dd, h->dred, h->dred);
  if (r < 0) return r;
  /* Frame k counted back from the newest. The half-frame shift makes the block's 2*EVERY feature frames line up exactly. */
  int done = 0;
  for (int k = EVERY; k >= 1; k--) {
    r = opus_decoder_dred_decode(h->d, h->dred, FRAME * k - FRAME / 2, pcm + done, FRAME);
    if (r < 0) return r;
    done += r;
  }
  (*e)->SetShortArrayRegion(e, out, 0, done, pcm);
  return done;
}

JNIEXPORT void JNICALL F(decClose)(JNIEnv *e, jclass c, jlong hh) {
  Dec *h = (Dec *)(intptr_t)hh; if (!h) return;
  opus_decoder_destroy(h->d); opus_dred_decoder_destroy(h->dd); opus_dred_free(h->dred); free(h);
}
