# FlatRaster (apk-codec2flatraster)

Android app that records low-frame-rate video made flat on purpose (few solid colours, hard edges) so it needs very few bits, together with a Codec 2 voice track. Built so the same packets can later be sent over a narrow link such as HF radio. Everything is stored in one `.frv` file; the app can also save a normal `.mp4`.

Current version: 0.3. Version 0.1 was reported working on the owner's phone. Not yet tried on a phone: the Flip camera button (0.2) and the timed new colours (0.3).

## How it works

- Camera2 gives YUV frames at the chosen rate (0.5 to 5 pictures per second).
- `jni/flat.c` posterizes each frame: edge-preserving smoothing, a colour palette that stays locked between frames (rebuilt when the scene stops fitting it, on the Pick colours now button, or every 5 to 60 s if chosen; each rebuild makes the encoder start a new full picture), small-region cleanup, and a hold so still areas stay identical.
- `VideoEncoder` uses the phone's own encoder: AV1 if it has one, else HEVC, else H.264.
- The voice goes through the Codec2 Recorder voice chain (rumble cut, denoise, level control, limiter) and Codec 2 (default 1200 bit/s). Source VOICE_RECOGNITION, as in Codec2 Recorder 2.4.
- `.frv` format: 14-byte header, then packets (setup, key picture, delta picture, audio), each with its own time and CRC16. A damaged packet costs only itself. Details in `FrvWriter.java`.
- Controls are a WebView page (`assets/index.html`), English.

## Build

Needs: Android SDK with build-tools 36.0.0 and platforms;android-36, JDK 17 or newer, zip, and NDK r28c in `$ANDROID_SDK_ROOT/android-ndk-r28c` or `ANDROID_NDK_ROOT`. Linux only.

1. Set `ANDROID_SDK_ROOT`.
2. Put `flatraster.keystore` and `signing.env` in the project folder. They are kept in the private `apk-signing-keys` repository, folder `apk-flatraster`, and are never committed here.
3. Run `bash build.sh`. The result is `FlatRaster.apk`.

A different signing key means a new version can't install over the old one.

## Tests (on a computer)

`bash tests/run.sh` runs: the filter (colour count, still scenes stay identical, speed), camera-frame rotation and cropping under memory checks, the `.frv` format with damaged files, and the controls page. Needs gcc, a JDK, python3 with numpy and opencv, node with Playwright. `python3 tools/bench.py` compares encoder sizes (x264, x265, AV1) on a clip.

## Layout

- `src/pl/flatraster/` app code. `src/pl/dyktafon/` voice path copied from Codec2 Recorder, plus `VoiceBridge.java`, which exposes it to the app.
- `jni/` filter and Codec 2 JNI. `third_party/codec2/` Codec 2 sources.
- If the voice chain changes in Codec2 Recorder, copy the changed files here too.

## Licences

- Codec 2: LGPL 2.1, `COPYING-codec2`, sources in `third_party/codec2`
- Voice chain files in `src/pl/dyktafon` come from Codec2 Recorder, which started from the MIT-licensed Codec2Recorder by tschudin: `LICENSE-Codec2Recorder`
