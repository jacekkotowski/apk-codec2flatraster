# FlatRaster

Hand-built Android APK, no Gradle: aapt2, javac, d8, zipalign, apksigner; NDK clang for the native libraries with the 16 KB page flag. Package `pl.flatraster`. WebView controls in `assets/index.html`, app code in `src/pl/flatraster/`, filter in `jni/flat.c`. Interface text is English.

The voice path in `src/pl/dyktafon/` is copied from Codec2 Recorder 2.7: 16 kHz voice chain and DRED (Opus 1.6.1, `third_party/opus-1.6.1`, flags in `opusflags.sh`, built into `libdred.so`). Codec 2 was removed in 0.13. Since 0.15 the sound settings are the recorder's, with its defaults (`SoundSettings`): microphone, audio source VOICE_RECOGNITION (6, confirmed on the owner's phone in Codec2 Recorder 2.4), rec level, Denoise (on), noise sample (`noise16.bin`), denoise strength, rumble cut, compressor, noise gate, consonant lift, treble boost, voice sensitivity and hold. Keep them the same as the recorder's; microphone routing is copied in `MicRoute`. The chain keeps the 2.4 behaviour at 16 kHz with the DRED limiter ceiling 18000; DRED settings are not yet confirmed on a phone. Don't change them without measuring. `.frv` version 2 carries the DRED bitstream version (12, still experimental in Opus 1.6.1): keep this exact Opus version for as long as such files exist.

0.16 (2026-11-16, first part of stage 6b of `ROADMAP.md` in `apk-codec2-recorder`, not run on a phone): the camera's frames between two pictures are joined into the picture (`flat_gather` in `jni/flat.c`: sharpest frame, averaging within the measured noise; `tests/cam_test.py`), the screen shows the picture enlarged with smooth borders (`jni/smooth.c`, `tests/smooth_test.py`; display only, the recording is unchanged), and border rounding is left out below 150 pixels on the short side (`MainActivity.roundFor`). Each has its figures in `PLAN.md`, "0.16"; don't change these without measuring.

Self-test (stage 0 of that roadmap, 0.15.1 and 0.15.2): `PictureTest`, `VideoTest`, `SelfTestRun`, and `src/pl/dyktafon/SelfTest.java`, a copy of the recorder's file (identical except `public`). Computer test `tests/selftest/run.sh`. Stage 6a of that roadmap (2026-11-16): `tools/coder/` holds a prototype picture coder in Python and its measurement against video encoders (`bash tools/coder/run.sh`); it is not part of the app, and by the rule set beforehand it should not be built as the video coder (D8 waits for the owner; `PLAN.md`, "Stage 6a"). D1 was decided 2026-11-16: one repository (apk-codec2-recorder); FlatRaster's code moves there in stage 1, and this repository is then archived with a note.

Rules:

- Never commit `*.keystore` or `signing.env`. Keys live in the private `apk-signing-keys` repository, folder `apk-flatraster`.
- Don't hand over a build unless it was built and tested from the current source. State plainly what was not tested on a phone.
- Raise `versionCode` and the version name with each delivered build.
- Run `bash tests/run.sh` before delivering.
- Before stating what exists, fetch and list all branches. Start from `PLAN.md` "Start here".
- Put each delivered APK in `dist/` (replace the old one, update the README link) and also hand it to the owner as a file in the chat. End every session with `PLAN.md` updated and the work pushed to `main`.
