/* DRED recording experiment (PLAN.md stage 9, part A). Computer only, no Android code.
   Audio in and out: 16 kHz, 16 bit, mono, raw.

   Two ways of making a file:
     enc1 / dec1  public Opus interface only. Runs the normal Opus encoder with DRED on and keeps one
                  whole packet every N frames. The player rebuilds the dropped frames from DRED.
     enc2 / dec2  DRED on its own, through the library's internal DRED encoder. One DRED block every N
                  frames, every chunk at the same quality level q (0 finest, 15 coarsest). No normal
                  Opus sound is stored.

   File layout for both: for each block, a 2-byte length (big endian) and then the bytes. */
#ifdef HAVE_CONFIG_H
#include "config.h"
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opus.h"
#include "cpu_support.h"
#include "dred_encoder.h"
#include "dred_decoder.h"

#define FS 16000
#define FRAME 320 /* 20 ms */

static int read_frame(FILE *f, short *s) {
  int n = (int)fread(s, sizeof(short), FRAME, f);
  if (n <= 0) return 0;
  for (int i = n; i < FRAME; i++) s[i] = 0;
  return 1;
}
static void put(FILE *f, const unsigned char *b, int n) { fputc(n >> 8, f); fputc(n & 255, f); fwrite(b, 1, n, f); }
static int get(FILE *f, unsigned char *b, int max) {
  int hi = fgetc(f), lo = fgetc(f);
  if (hi == EOF || lo == EOF) return -1;
  int n = hi << 8 | lo;
  if (n > max || (int)fread(b, 1, n, f) != n) return -1;
  return n;
}

/* A decoder that has never seen a packet can only give silence. One normal packet of silence starts it. */
static OpusDecoder *start_decoder(void) {
  int err; unsigned char pkt[400]; short z[FRAME] = {0}, out[FRAME];
  OpusEncoder *e = opus_encoder_create(FS, 1, OPUS_APPLICATION_VOIP, &err);
  opus_encoder_ctl(e, OPUS_SET_BITRATE(12000));
  int n = opus_encode(e, z, FRAME, pkt, sizeof pkt);
  opus_encoder_destroy(e);
  OpusDecoder *d = opus_decoder_create(FS, 1, &err);
  opus_decoder_ctl(d, OPUS_SET_COMPLEXITY(5)); /* 5 and up turns on the neural concealment that plays DRED */
  if (opus_decode(d, pkt, n, out, FRAME, 0) < 0) fprintf(stderr, "decoder start failed\n");
  return d;
}

/* ---- public interface ---- */
static int enc1(int bitrate, int loss, int every, const char *in, const char *out) {
  int err; FILE *fi = fopen(in, "rb"), *fo = fopen(out, "wb"); if (!fi || !fo) return 1;
  OpusEncoder *e = opus_encoder_create(FS, 1, OPUS_APPLICATION_VOIP, &err);
  opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
  opus_encoder_ctl(e, OPUS_SET_PACKET_LOSS_PERC(loss));
  opus_encoder_ctl(e, OPUS_SET_DRED_DURATION(104));
  short s[FRAME]; unsigned char pkt[4000], tmp[4000];
  long frames = 0, kept = 0, bytes = 0, mainBytes = 0;
  while (read_frame(fi, s)) {
    int n = opus_encode(e, s, FRAME, pkt, sizeof pkt);
    if (n < 0) { fprintf(stderr, "encode error %d\n", n); return 1; }
    if (frames % every == every - 1) {
      put(fo, pkt, n); kept++; bytes += n + 2;
      memcpy(tmp, pkt, n); int m = opus_packet_unpad(tmp, n); if (m > 0) mainBytes += m;
    }
    frames++;
  }
  fclose(fi); fclose(fo);
  double sec = frames * 0.02;
  printf("enc1 seconds %.1f blocks %ld bytes %ld normal_opus_bytes %ld bit_per_s %.0f kB_per_min %.2f\n",
         sec, kept, bytes, mainBytes, bytes * 8 / sec, bytes * 60 / sec / 1000);
  return 0;
}
static int dec1(int every, const char *in, const char *out) {
  int err; FILE *fi = fopen(in, "rb"), *fo = fopen(out, "wb"); if (!fi || !fo) return 1;
  OpusDecoder *d = start_decoder();
  OpusDREDDecoder *dd = opus_dred_decoder_create(&err); OpusDRED *dred = opus_dred_alloc(&err);
  unsigned char pkt[4000]; short pcm[FRAME]; int n, lost = every - 1;
  while ((n = get(fi, pkt, sizeof pkt)) >= 0) {
    int end = 0, have = opus_dred_parse(dd, dred, pkt, n, lost * FRAME, FS, &end, 0);
    for (int fr = 0; fr < lost; fr++) {
      int r = have > 0 ? opus_decoder_dred_decode(d, dred, (lost - fr) * FRAME, pcm, FRAME) : opus_decode(d, NULL, 0, pcm, FRAME, 0);
      if (r > 0) fwrite(pcm, sizeof(short), r, fo);
    }
    int r = opus_decode(d, pkt, n, pcm, FRAME, 0);
    if (r > 0) fwrite(pcm, sizeof(short), r, fo);
  }
  fclose(fi); fclose(fo); return 0;
}

/* ---- DRED on its own ---- */
static int enc2(int q, int every, const char *in, const char *out) {
  FILE *fi = fopen(in, "rb"), *fo = fopen(out, "wb"); if (!fi || !fo) return 1;
  int arch = opus_select_arch();
  DREDEnc *e = calloc(1, sizeof(DREDEnc)); dred_encoder_init(e, FS, 1);
  if (!e->loaded) { fprintf(stderr, "DRED encoder model missing\n"); return 1; }
  unsigned char act[DRED_MAX_FRAMES * 4]; memset(act, 1, sizeof act); /* everything counts as speech; pauses are the app's job */
  short s[FRAME]; float f[FRAME]; unsigned char buf[DRED_MAX_DATA_SIZE];
  long frames = 0, blocks = 0, bytes = 0; int more = 1, tail = 0;
  /* Two extra frames of silence at the end push the last speech through the coder's look-ahead. */
  while (more || tail < 2 || frames % every) {
    if (more && !read_frame(fi, s)) more = 0;
    if (!more) { memset(s, 0, sizeof s); tail++; }
    for (int i = 0; i < FRAME; i++) f[i] = s[i] / 32768.f;
    dred_compute_latents(e, f, FRAME, 0, arch);
    frames++;
    if (frames % every == 0) {
      int n = dred_encode_silk_frame(e, buf, every / 2, sizeof buf, q, 0, q, act, arch);
      put(fo, buf, n); blocks++; bytes += n + 2;
    }
  }
  fclose(fi); fclose(fo);
  double sec = frames * 0.02;
  printf("enc2 q %d seconds %.1f blocks %ld bytes %ld bit_per_s %.0f kB_per_min %.2f\n", q, sec, blocks, bytes, bytes * 8 / sec, bytes * 60 / sec / 1000);
  return 0;
}
static int dec2(int every, const char *in, const char *out) {
  int err; FILE *fi = fopen(in, "rb"), *fo = fopen(out, "wb"); if (!fi || !fo) return 1;
  OpusDecoder *d = start_decoder();
  OpusDREDDecoder *dd = opus_dred_decoder_create(&err); OpusDRED *dred = opus_dred_alloc(&err);
  unsigned char buf[DRED_MAX_DATA_SIZE]; short pcm[FRAME]; int n;
  while ((n = get(fi, buf, sizeof buf)) >= 0) {
    if (n == 0) { memset(pcm, 0, sizeof pcm); for (int k = 0; k < every; k++) fwrite(pcm, sizeof(short), FRAME, fo); continue; }
    dred_ec_decode(dred, buf, n, 2 * every, 0);
    opus_dred_process(dd, dred, dred);
    /* Frame k counted back from the newest. The half-frame shift makes the block's 2*every feature frames line up exactly, none skipped, none used twice. */
    for (int k = every; k >= 1; k--) {
      int r = opus_decoder_dred_decode(d, dred, FRAME * k - FRAME / 2, pcm, FRAME);
      if (r > 0) fwrite(pcm, sizeof(short), r, fo);
    }
  }
  fclose(fi); fclose(fo); return 0;
}

int main(int argc, char **argv) {
  if (argc == 7 && !strcmp(argv[1], "enc1")) return enc1(atoi(argv[2]), atoi(argv[3]), atoi(argv[4]), argv[5], argv[6]);
  if (argc == 5 && !strcmp(argv[1], "dec1")) return dec1(atoi(argv[2]), argv[3], argv[4]);
  if (argc == 6 && !strcmp(argv[1], "enc2")) return enc2(atoi(argv[2]), atoi(argv[3]), argv[4], argv[5]);
  if (argc == 5 && !strcmp(argv[1], "dec2")) return dec2(atoi(argv[2]), argv[3], argv[4]);
  fprintf(stderr, "usage:\n  dredtest enc1 BITRATE LOSS_PERCENT EVERY in.raw out.pkt\n  dredtest dec1 EVERY in.pkt out.raw\n"
                  "  dredtest enc2 Q EVERY in.raw out.dred\n  dredtest dec2 EVERY in.dred out.raw\n"
                  "EVERY is in 20 ms frames, even, at most 52. Q is 0 (finest) to 15 (coarsest).\n");
  return 2;
}
