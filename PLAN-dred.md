# FlatRaster: DRED replaces Codec 2 for the voice track

Written 2026-11-14, at FlatRaster 0.12, for a session that starts cold. Nothing here is built yet.

## Goal and decisions

- The voice track in `.frv` becomes DRED, the neural speech coder in Opus 1.6.1, as in Codec2 Recorder 2.7 (owner's decision there, 2026-11-14). Codec 2 goes out of the app completely.
- **Old `.frv` files with Codec 2 voice need not play.** The owner does not care about old recordings. The reader refuses them with one plain sentence; no conversion, no second decoder.
- Same four levels as the recorder, default small:

| Name | q | Voice, bit/s, about | Per 1 s packet with 12-byte header |
|---|---|---|---|
| DRED best | 4 | 1330 | 1430 |
| DRED balanced | 7 | 800 | 900 |
| DRED small | 9 | 510 | 610 |
| DRED smallest | 12 | 290 | 390 |

  Codec 2 1200, the current default, is 1200 bit/s plus about 100 of packet overhead. Figures from the recorder's measurements on English speech; DRED size depends on the sound.

## Where everything comes from

Everything except the `.frv` and FlatRaster parts is already built and tested in `jacekkotowski/apk-codec2-recorder` (branch `claude/modest-noether-2xx3jl` until merged to `main`). Attach that repository to the session read-only and copy from it; don't rewrite.

| Copy from apk-codec2-recorder | To FlatRaster | Note |
|---|---|---|
| `third_party/opus-1.6.1/`, `COPYING-opus`, `opusflags.sh` | same paths | Upstream files unchanged, 12 MB |
| `app/jni/dredjni.c` | `jni/dredjni.c` | JNI names are `Java_pl_dyktafon_DredLib_*`, which fits because FlatRaster keeps the voice code in `src/pl/dyktafon/` |
| `app/src/pl/dyktafon/{Dred,DredLib,VoiceChain,Denoiser,Vad,Limiter,Compressor,Resampler}.java` | `src/pl/dyktafon/` | Replace the 8 kHz versions. `Dred.java` brings the level names and constants; FlatRaster does not use the `.dred` file format itself, only `Dred.FS`, `FRAME`, `BLOCK_SAMPLES`, `MAX_BLOCK`, `BITSTREAM`, `NAMES`, `LEVELS`, `qOf`, `nameOf` |
| `tests/dred/{dredtest.c,JT.java,RS.java,build.sh}` | `tests/dred/` | Adjust paths in `build.sh` (`jni/` instead of `app/jni/`, `src/` instead of `app/src/`); drop the `fdvpipe` lines |

The chain at 16 kHz keeps the 2.4 behaviour (same filter corners and time constants, 40 ms frames) with a limiter ceiling of 18000, measured against DRED's peak overshoot. Read "Part B results" in the recorder's `PLAN.md` before changing levels.

## Steps

Each ends with a check; don't go on while a check fails.

1. **Copy and build.** Copy the files above. In `build.sh`: `libdred.so` in place of `libc2.so`, built like the recorder's (`$OPUSFLAGS -DNDEBUG -fPIC -shared -fvisibility=hidden`, 16 KB page flag, all three processor types; build them in parallel as the recorder's `build.sh` does, it takes 158 files). Delete `third_party/codec2`, `jni/c2jni.c`, `src/pl/dyktafon/C2.java`, `COPYING-codec2` (nothing of Codec 2 is left once the voice codec goes; the radio plan will bring the modem library back with its own licence file).
   Check: `bash tests/dred/build.sh`, then `JT` gives byte-identical blocks and sound to `dredtest enc2`/`dec2` (the recorder's step 4 check), and `RS` passes.

2. **`VoiceBridge` for DRED.** Same role, new inside: `VoiceChain(16000, VoiceChain.CEILING_DRED, 640, denoise, 0.18, 1, null, 180, 6, 12, 3)` and a `DredLib.Coder(q)`. `encode(short[640])` runs the chain on one 40 ms chunk, feeds the coder two 20 ms frames, and returns a finished one-second block when there is one. `finish(sink)` passes through `Coder.finish`. Decoder side: `DredLib.Player`, one block in, 16000 samples out. `modeNames()` gives the four level names.

3. **`.frv` version 2.** `FrvWriter.VERSION = 2`. Header stays 14 bytes: byte 10 becomes the DRED bitstream version (`Dred.BITSTREAM`, 12), byte 11 the level q (was 0). `FrvReader`: version 1 gives "This recording was made with Codec 2 voice by version 0.12 or older; this version can't play it." A bitstream version other than 12 is refused the same way as in the recorder. Each AUDIO packet holds exactly one DRED block; its time is the time of the block's first frame. A zero-length AUDIO payload is a second of silence (the coder never makes one, but the reader must accept it).
   Check: `tests/FrvTest.java` and `tests/frv_test.sh` updated: round trip with audio blocks, version 1 refused, unknown bitstream refused.

4. **`AudioCapture` at 16 kHz.** `AudioRecord` at 16 kHz, VOICE_RECOGNITION as now, reads of 640 samples. Write a packet for each block `VoiceBridge` returns.
   - Skip silence: gate the sound before the coder, not coded frames. Keep 400 ms of processed sound as pre-roll and feed it when speech starts. When speech stops, `finish()` the coder (pads the block with silence, at most one second of cheap silence) and start a fresh `Coder` at the next speech, so every block's time stamp is simply the time of its first fed frame.
   - Time stamps: `offsetMs` + fed position, as now, but counted in 20 ms frames and reset to the real clock at each start of speech.
   - Move the packet logic out of `AudioCapture` into a pure-Java class (no `AudioRecord`) so a computer test can feed it a speech file, as `RP.java` does in the recorder.
   Check: computer test feeds 30 s of speech with pauses through that class with skip silence on and off, writes a `.frv` with audio only, reads it back with `FrvReader`: block count, time stamps rising and matching the speech positions within 20 ms, and the decoded audio placed at those times scores the same STOI as the recorder's path (about 0.7 at q9 on clean speech).

5. **`Player`.** `AudioTrack` at 16 kHz. Decoding one block takes about 30 ms on a computer and an unknown time on a phone, longer than a Codec 2 frame. Don't decode on the thread that times the pictures: decode each AUDIO packet on a separate audio thread with a small queue, or decode all audio of the file before starting (a few seconds of work for a long file; simplest). Keep the picture timing as it is. Gaps between packets (skipped silence) are written as silence so sound stays in step with pictures.
   Check on a computer: a pure-Java version of the scheduling (packet list in, sample positions out) puts each block at its time.

6. **`Mp4Export`.** Decode the blocks at 16 kHz, fill gaps with silence as now, AAC at 16 kHz mono (`createAudioFormat("audio/mp4a-latm", 16000, 1)`; 16 kHz is a standard AAC rate, but check the encoder accepts it on the phone, and fall back to resampling to 44.1 kHz with `Resampler` if it does not).

7. **Page and settings.** `assets/index.html`: the select becomes "Voice" with "DRED best, 10 kB/min" and so on, as in the recorder; default `small`; a stored Codec 2 mode (`"1200"` and so on) falls back to small. The status line keeps "voice N bit/s". `MainActivity`: `c2mode` becomes `q`, default 9.
   Check: `tests/ui_test.js` updated for the new options and the fallback.

8. **Docs and delivery.** `CLAUDE.md`: the voice path is DRED, the settings paragraph replaced by the recorder's DRED paragraph ("not yet confirmed on a phone", ceiling 18000, chain at 16 kHz). `README.md` and `PLAN.md` status rows. `PLAN-radio.md`: the voice inside `.frv` is now DRED, about 600 bit/s with packet headers instead of about 1300, so a 30 s recording shrinks by about 2.6 kB; the modem library to use is `libfdv.so` from the recorder (`third_party/codec2-1.2` plus `fdvflags.sh`, already built and measured there), not a fresh fetch from upstream.
   Version 0.13 (`versionCode` 13). `bash tests/run.sh` must pass, plus the new tests. Signed with the key from `apk-signing-keys/apk-flatraster`. Expect the APK to grow by about 7 MB (2.4 to 2.5 MB of DRED per processor type, less `libc2.so`).

## Not to forget

- The owner's phone is the only real test. State plainly that recording, playback and MP4 export with DRED were not run on a phone.
- 12 bytes of packet header per second is now 15 to 30% of the voice track. Shorter headers are already step 4 of "Next steps" in `PLAN.md`; with DRED they matter more. Two blocks per packet would halve it, at the cost of losing two seconds with one damaged packet; decide that with the radio work, not here.
- DRED needs the decoder library exactly as built here for as long as version 2 `.frv` files exist (bitstream version 12, still experimental in Opus 1.6.1). A newer Opus goes in beside it, not instead of it.
- A neural decoder can turn noise into clean-sounding wrong words. FlatRaster records with Denoise off (`VoiceBridge(mode, true, false)`); consider turning it on, since in the recorder's measurements it never hurt and helped a little in noise.
