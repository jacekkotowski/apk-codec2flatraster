# FlatRaster

Hand-built Android APK, no Gradle: aapt2, javac, d8, zipalign, apksigner; NDK clang for the native libraries with the 16 KB page flag. Package `pl.flatraster`. WebView controls in `assets/index.html`, app code in `src/pl/flatraster/`, filter in `jni/flat.c`. Interface text is English.

The voice path in `src/pl/dyktafon/` is copied from the Codec2 Recorder app. Its settings were confirmed good on the owner's phone there: audio source VOICE_RECOGNITION (6), `CEILING = 11000`, `AUTO_PEAK = 8000`, `MAX_AUTO_GAIN = 40`. Don't change them without measuring.

Rules:

- Never commit `*.keystore` or `signing.env`. Keys live in the private `apk-signing-keys` repository, folder `apk-flatraster`.
- Don't hand over a build unless it was built and tested from the current source. State plainly what was not tested on a phone.
- Raise `versionCode` and the version name with each delivered build.
- Run `bash tests/run.sh` before delivering.
