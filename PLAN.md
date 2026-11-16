# FlatRaster plan

Last updated: 2026-11-15 (self-test, 0.15.2)

## Start here

- **Review and big plan for both apps (2026-11-15): `ROADMAP.md` in `apk-codec2-recorder`.** The owner's decisions are in its section 2 (D2 to D10 as recommended; **D1 was decided 2026-11-16: one repository**). For FlatRaster it proposes: one repository with Codec2 Recorder and a shared core; free legibility steps (sharpest picture, averaging, face-first colours, smooth upscaling); face crop and a sketch mode; measuring a purpose-built coder for flat pictures against AV1; a "postcard" (one picture and a voice message) as the first radio product. It supersedes `PLAN-radio.md` if accepted.
- **Stage 0 of that roadmap is built here as 0.15.2 (2026-11-15, Sonnet 5.5): a Self-test panel** (button "Self-test" under the controls). RUN SELF-TEST (about a minute) times the voice chain and DRED coding and playing on the phone, times the posterize filter on a made-up camera scene, and for AV1, HEVC and H.264 lists the phone's encoders (software or hardware, lowest bit rate) and reports what the app's own `VideoEncoder` really produces from that scene at 5 and 2 kbit/s asked: bytes, real kbit/s, full-picture size, time. SAVE TEST CLIP records 30 s of the microphone with no processing by the app (Download/FlatRaster). COPY REPORT copies it and SHARE REPORT sends it; the report is also kept inside the app. Code: `PictureTest.java` (filter, no Android), `VideoTest.java` (encoders), `SelfTestRun.java` (phone side), and `src/pl/dyktafon/SelfTest.java`, which is copied from Codec2 Recorder's file of the same name (identical except `public`; stage 1 merges them). Tested on a computer: `bash tests/selftest/run.sh` (also in `tests/run.sh`), and the page panel in `tests/ui_test.js`. **Not tested on a phone**: `VideoTest` and `SelfTestRun` use Android's encoders and microphone. The scene is synthetic, so real camera noise will cost more bytes than the report shows. **Next step is the owner's run on a phone; paste the report.** It also answers which encoder the phone gives and at what real bit rate (ROADMAP.md 7.1).
- Current version **0.15.2** (0.15 plus the self-test) on `main`, APK in `dist/`. Since 0.13 the voice is DRED (Codec 2 is gone; `.frv` files from 0.12 or older don't play), 0.14 put the voice in step with the picture, 0.15 gave it the sound settings of Codec2 Recorder. None of 0.2 to 0.15 has been tried on a phone.
- Next real step: **the owner's phone test of 0.15**: recording with voice, playback in step, SAVE MP4 (its sound is now AAC at 16 kHz, untried on the phone), the sound settings, a Bluetooth microphone.
- Next to build, no phone needed: sending `.frv` over radio. Take the modem library and file transport from Codec2 Recorder (`third_party/codec2-1.2`, `fdvflags.sh`, `jni/fdvjni.c`, `Fountain`, `RadioLink`); see `PLAN-radio.md` and its 2026-11-14 update. Codec2 Recorder 2.8 also has RADE voice messages (`libvoice.so`, `third_party/rade_c`, `radeflags.sh`); whether FlatRaster wants them is the owner's call.
- Before stating what exists, fetch and list all branches. Keys: attach `apk-signing-keys`, folder `apk-flatraster`. Build: the Android SDK from Codec2 Recorder's `setup.sh` works here too (`source env.sh` there, then `bash build.sh` here). Run `bash tests/run.sh` before delivering (needs numpy, opencv-python-headless, Playwright).
- Hand the APK to the owner as a file in the chat as well as in `dist/`, and end the session with this plan updated and the work on `main`.

## Status

| Part | State |
|---|---|
| Record posterized video + Codec 2 voice to .frv, play, save MP4, share | 0.1 reported working on the owner's phone |
| Flip camera button | Built, waiting for phone test |
| New colours on a timer, with a full picture at each change | Built, waiting for phone test |
| Full-picture interval, skip silence, clean-up strength | Built, waiting for phone test |
| Ignore repeating movement, skip still pictures | Filter tested on a computer (leaves and walker scene), waiting for phone test |
| Faces in full colours, rest in fewer colours (camera's own face detection) | Filter tested on a computer; face boxes from the camera not tested anywhere |
| Smooth borders: hold rule based on the border, not on each pixel's past (0.7) | Filter tested on a computer, waiting for phone test |
| Border rounding (5x5 most-common-colour filter), on by default from 0.8 | Filter tested on a computer, waiting for phone test |
| New colours every picture: brand-new palette each picture, each new colour attributed to the nearest previous colour if close enough (0.11) | Filter tested on a computer; cost 5 to 32% more bits than locked colours there. Needs the phone to judge |
| Exposure and white balance lock while recording | Built, not tested anywhere |
| Voice: DRED in place of Codec 2 (0.13) | Built and tested on a computer ([PLAN-dred.md](PLAN-dred.md)); not run on a phone |
| Sound settings as in Codec2 Recorder, Bluetooth and USB microphones, noise sample (0.15) | Page tested against a stub; recording path tested on a computer; not run on a phone |
| Sending over a narrow link | Not started |

## What was measured (synthetic clips, AV1 at one quality setting, 320x240)

- More colours cost more: 4 colours about 2.0 kbps, 8 about 2.2 to 2.5, 32 about 2.5 to 2.6. Smoothing strength changed little.
- AV1 needed about 30% fewer bytes than HEVC.
- Full picture every 10 s instead of once in 20 s: 2.63 against 2.46 kbps.
- Clean-up strength: no difference on the clean test clip.
- Ignoring repeating movement removed 78%, 94% and 98% of leaf flicker at the three levels; the walker stayed fully visible. Stream 5.53 to 4.64 kbps at the middle level.
- Outside a face box, half the colours: 2.46 to 2.22 kbps; a quarter: 2.06. With no face in view: 1.98 and 1.24.
- Tried and dropped: drawing the area outside the box in 2x2 or 4x4 blocks. It made the stream bigger (2.84 kbps): staircase edges cost more than smooth ones.

- Fraying borders (seen on the phone: borders ragged until the next new palette). Cause: up to 0.6 each pixel kept its colour until it differed enough from its own past, so pixels along a border switched one by one as exposure drifted. Still scene with drifting exposure and white balance: border length grew to 1.72x of the first picture, 535 pixels changed per picture, 1.45 kbps. With the 0.7 rule (switch only when clearly past the border between the two colours): 1.00x, 66 pixels, 1.39 kbps.
- Ragged borders behind moving things (seen on the phone). Soft-edged moving scene with noise, border length against the ideal (noise-free, every pixel decided fresh): 0.6 rule 1.78x and 3.34 kbps; 0.7 rule 1.22x and 3.17 kbps; 0.7 rule plus rounding 1.03x and 3.10 kbps. Tried and dropped: deciding pixels near movement afresh without hold (1.25x, no gain).
- Colour fit every picture (owner's idea: refit the colours each picture so borders fall as after a reset, keep the shown colour unless it moved past a threshold). Against locked colours with the 0.8 hold rule and rounding, border length was already 0.92 to 1.00x of the ideal and refitting did not improve it. Stream, locked against refit (threshold 16): still scene 1.45 and 1.64 kbps; exposure drift 3.30 and 3.68; soft moving 3.03 and 3.21; real light change 3.33 and 3.32 (3.17 at threshold 4). So on synthetic scenes it only pays when the light really changes. Kept as an option to compare on the phone. 0.10 makes the refit complete on every picture (as the owner asked): still 1.59, drift 3.74, soft moving 3.42, light change 3.43 kbps at threshold 16; borders 0.98 to 1.02x ideal. Without the per-pixel hold it was worse (1.85, 3.90, 3.55, 3.41), so the hold stays on.
- 0.11, the owner's exact idea: a brand-new palette every picture, each new colour shown as the nearest colour of the previous picture if within a threshold. Borders 0.92 to 0.99x ideal. Stream at threshold 16 (and 28): still 1.91 (1.91), drift 3.71 (3.91), soft moving 3.61 (3.45), light change 3.60 (3.51) kbps, against 1.45, 3.30, 3.03, 3.33 with locked colours. A fresh palette lands slightly differently on each noisy picture, which moves borders a little every time.

None of this is measured on real footage yet. Next measurement: the same tests on clips from the phone.

## Next steps without neural networks

1. Follow the face: crop to head and shoulders. Move the crop in slow steps and only when the face leaves a dead zone, because each move costs a nearly full picture.
2. Send the background rarely and only the moving box often, pasted by the player. Needs the app's own picture packets instead of the phone's video encoder; fits radio best.
3. Classic person detectors for people who don't face the camera: Haar cascades (face, upper body, whole body) and the HOG outline detector. Give a box, not limbs. Run them only where the movement filter saw something unusual. Cost on a phone not measured.
4. Shorter packet headers (12 bytes each now), together with the radio work, with a way to still read old files.
5. Software AV1 through the NDK for phones without an AV1 encoder.
6. Hand shake compensation, so the movement filters also work hand-held.

## For later: neural network solutions

Kept out so far by choice (no AI in the app). Notes for when that changes:

- **Pose estimation, the "puppeteer" stream.** A small model finds the skeleton: about 17 points (MoveNet) or 33 (BlazePose). A similar model gives face points. Send the background once, then only the points; the player draws a stick figure or simple puppet over it. Rough estimate, not measured: 30 to 70 bytes per picture, so a few hundred bit/s at 2 pictures per second, below the voice track.
- What is lost: the real look of the person, clothes, expression, things held in the hands. A still photo now and then can make up for part of it.
- What it needs: a model file of a few megabytes and a runtime library (TensorFlow Lite or MediaPipe, both Apache 2.0) built into this Gradle-free app by hand; a new packet type in .frv for points; a puppet renderer in the player.
- First checks: can the runtime be linked without Gradle; speed and battery on the owner's phone at 1 to 5 pictures per second; how steady the points are in poor light.
- **Person segmentation** (a model that marks which pixels are a person): a cut-out of the person instead of a box, for the same "sharp person, coarse rest" idea.
- **Learned video codecs and face re-animation** (send one photo, then only face points, and redraw the face at the receiver): lowest bitrates known for talking heads, but heavy for a phone and able to invent details that were not there. Only worth a look after the puppet stream.

## Voice track: DRED replaces Codec 2

Built in 0.13, 2026-11-14: [PLAN-dred.md](PLAN-dred.md) has the steps and the results. Codec 2 is out completely; old `.frv` files with Codec 2 voice don't play (owner, 2026-11-14).

## Radio

Detailed plan for a new session: [PLAN-radio.md](PLAN-radio.md). Short version: send `.frv` files first, not live video; FreeDV data modes are the first candidate, HamDRM the second (GPL-3.0). Take the modem library from Codec2 Recorder (`third_party/codec2-1.2`, `fdvflags.sh`, `jni/fdvjni.c`, `Fountain`, `RadioLink`), already built and measured there; it brings back the LGPL licence file. The voice track is now DRED, about 610 bit/s with packet headers.

## Files to keep

`flatraster.keystore` and `signing.env`, in the private `apk-signing-keys` repository, folder `apk-flatraster`. Without them, later versions can't install over the current one.
