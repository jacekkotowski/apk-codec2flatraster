# FlatRaster plan

Last updated: 2026-11-16 (stage 6a: the picture coder measured on a computer; no change to the app)

## Start here

- **Stage 6a of `ROADMAP.md` is done (2026-11-16, on a computer, nothing in the app changed): the purpose-built picture coder was measured, and by the rule set beforehand it should not be built as the video coder.** A prototype (`tools/coder/flatcoder.py`) codes the posterized pictures exactly. Its full pictures are small (290 bytes for the self-test scene, where the phone's encoders made 900 to 1500), but its delta pictures are bigger than a video encoder's on every moving scene, so 30 s of the self-test scene is 9.1 kB: less than the phone's 11.9 to 18.2 kB, more than the 5.8 to 7.9 kB that computer encoders of the same formats need. Table, limits and recommendation: "Stage 6a" under "What was measured" below. **D8 waits for the owner.** Stages 6b and 6c are not started.

- **Review and big plan for both apps (2026-11-15): `ROADMAP.md` in `apk-codec2-recorder`.** The owner's decisions are in its section 2 (D2 to D10 as recommended; **D1 was decided 2026-11-16: one repository**). For FlatRaster it proposes: one repository with Codec2 Recorder and a shared core; free legibility steps (sharpest picture, averaging, face-first colours, smooth upscaling); face crop and a sketch mode; measuring a purpose-built coder for flat pictures against AV1; a "postcard" (one picture and a voice message) as the first radio product. It supersedes `PLAN-radio.md` if accepted.
- **Stage 0 of that roadmap is built here as 0.15.2 (2026-11-15, Sonnet 5.5): a Self-test panel** (button "Self-test" under the controls). RUN SELF-TEST (about a minute) times the voice chain and DRED coding and playing on the phone, times the posterize filter on a made-up camera scene, and for AV1, HEVC and H.264 lists the phone's encoders (software or hardware, lowest bit rate) and reports what the app's own `VideoEncoder` really produces from that scene at 5 and 2 kbit/s asked: bytes, real kbit/s, full-picture size, time. SAVE TEST CLIP records 30 s of the microphone with no processing by the app (Download/FlatRaster). COPY REPORT copies it and SHARE REPORT sends it; the report is also kept inside the app. Code: `PictureTest.java` (filter, no Android), `VideoTest.java` (encoders), `SelfTestRun.java` (phone side), and `src/pl/dyktafon/SelfTest.java`, which is copied from Codec2 Recorder's file of the same name (identical except `public`; stage 1 merges them). Tested on a computer: `bash tests/selftest/run.sh` (also in `tests/run.sh`), and the page panel in `tests/ui_test.js`. **Not tested on a phone**: `VideoTest` and `SelfTestRun` use Android's encoders and microphone. The scene is synthetic, so real camera noise will cost more bytes than the report shows. **First phone report, 2026-11-16 (Xiaomi 2602BPC18G, Android 16, arm64, 0.15.2): everything passed.** Voice speed as in the recorder (DRED coding 220x, playing 26x, recording 65x real time). The posterize filter takes 21 ms a picture (47 a second possible; the app uses 2). Encoders on this phone: H.264 and HEVC in software and in the camera chip (MediaTek), AV1 in software only; all accept 320x240 and bit rates down to almost 0. The app tries software first, so on this phone it uses AV1 software, and the chip encoders were not tested. **None of them keeps to a low bit rate.** On the made-up scene (30 s, 2 pictures a second, 3 full pictures), asked 5 kbit/s: H.264 3.77, HEVC 4.85, AV1 4.75 kbit/s really; asked 2 kbit/s: H.264 3.18, HEVC 3.93, AV1 3.97, so about double the ask. Full pictures are 0.9 to 1.5 kB each. H.264 made the fewest bytes here (11.9 to 14.1 kB per 30 s), AV1 the smallest full pictures but larger delta pictures. This confirms the estimate in ROADMAP.md 6 and 7.1 that 30 s of video is about 12 to 18 kB, too big for a radio link as it is, and supports measuring a purpose-built coder (stage 6a). Caveats: the scene is synthetic (real camera noise will cost more), one scene only, and the choice of default codec should not be changed on this alone. The report file is `selftest_2026-11-16_01-20-34.txt` on the phone. It also answers which encoder the phone gives and at what real bit rate (ROADMAP.md 7.1).
- Current version **0.15.2** (0.15 plus the self-test) on `main`, APK in `dist/`. Since 0.13 the voice is DRED (Codec 2 is gone; `.frv` files from 0.12 or older don't play), 0.14 put the voice in step with the picture, 0.15 gave it the sound settings of Codec2 Recorder. None of 0.2 to 0.15 has been tried on a phone.
- Next real step: **the owner's phone test of 0.15**: recording with voice, playback in step, SAVE MP4 (its sound is now AAC at 16 kHz, untried on the phone), the sound settings, a Bluetooth microphone.
- Older note, superseded by `ROADMAP.md` (its stage 6d takes the transport from the shared core; the order of work is the roadmap's): sending `.frv` over radio. Take the modem library and file transport from Codec2 Recorder (`third_party/codec2-1.2`, `fdvflags.sh`, `jni/fdvjni.c`, `Fountain`, `RadioLink`); see `PLAN-radio.md` and its 2026-11-14 update. Codec2 Recorder 2.8 also has RADE voice messages (`libvoice.so`, `third_party/rade_c`, `radeflags.sh`); whether FlatRaster wants them is the owner's call.
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

## Stage 6a: a coder made for flat pictures, measured (2026-11-16)

Question (ROADMAP.md 7.4, decision D8): would a small coder made for posterized pictures need clearly fewer bytes than the phone's video encoder? Rule set before measuring: build it only if it makes about half the bytes, or fewer, on both full and delta pictures across the clips.

What was done, all on a computer (`bash tools/coder/run.sh` repeats it in about two minutes; full tables in `tools/coder/results.md`):

- Scenes, each through the real filter (`jni/flat.c`), 320x240, 8 colours: the self-test scene (`PictureTest.scene`, 60 pictures at 2 a second, the app's defaults; the pictures are byte for byte those the self-test gives the encoders on a computer), and the scenes of the existing tests with their own settings: moving shapes and the still scene with camera noise (`tests/flat_test.py`), flickering leaves with a walker (`tests/motion_test.py`) with the repeating-movement filter off and at level 2.
- Prototype, `tools/coder/flatcoder.py` (Python, not in the app). Full picture: the palette, then every pixel predicted from the four neighbours already sent (left, above, above-left, above-right), arithmetic coded with probabilities learned while coding. Delta picture: a change map coded the same way (context: the four neighbours in the map, and whether the pixel sat on a border in the previous picture), then the new colours of the changed pixels. Every picture of every clip decoded to exactly the input.
- Video encoders on the computer, as `tools/bench.py` uses them: x264 veryslow, x265 slow, svt-av1 preset 4, libaom cpu 4, each at three quality steps, a full picture every 10 s as in the app. The table shows the step FlatRaster's earlier measurements used (x264 and x265 40, AV1 45). Bytes are the encoder's packets only.
- "Off": share of pixels whose brightness or colour is more than 10 of 255 away from the posterized picture after decoding. The prototype is exact, so it has none.
- "30 s" is three full pictures plus the delta pictures between them, from the measured means, so clips of other lengths compare.

| Scene | Coder | Bytes per full picture | Bytes per delta picture | 30 s | Pixels off |
|---|---|---|---|---|---|
| Self-test scene: a head that sways in front of a wall, 2 pictures a second | **Prototype** | **290** | **145** | **9.1 kB** | exact |
| | Phone, H.264 software, asked 2 and 5 kbit/s | 900 to 1500 (all phone coders) | about 130 to 160, and 170 to 200 | 11.9 and 14.1 kB | not measured |
| | Phone, HEVC software, asked 2 and 5 kbit/s | | about 180 to 210, and 240 to 270 | 14.7 and 18.2 kB | not measured |
| | Phone, AV1 software, asked 2 and 5 kbit/s | | about 180 to 215, and 235 to 265 | 14.9 and 17.8 kB | not measured |
| | Computer H.264 | 907 | 91 | 7.9 kB | 0.44% |
| | Computer HEVC | 806 | 95 | 7.8 kB | 0.19% |
| | Computer AV1 (svt-av1) | 695 | 73 | 6.2 kB | 0.15% |
| | Computer AV1 (libaom) | 493 | 76 | 5.8 kB | 0.13% |
| Moving shapes on a gradient, 3 pictures a second | **Prototype** | **518** | **175** | **16.7 kB** | exact |
| | Computer H.264 | 696 | 141 | 14.3 kB | 1.03% |
| | Computer HEVC | 419 | 103 | 10.2 kB | 0.87% |
| | Computer AV1 (svt-av1) | 674 | 71 | 8.2 kB | 0.72% |
| | Computer AV1 (libaom) | 540 | 74 | 8.0 kB | 0.75% |
| Still scene with camera noise, 3 pictures a second | **Prototype** | **611** | **1** | **1.9 kB** | exact |
| | Computer H.264 | 828 | 22 | 4.4 kB | 0.47% |
| | Computer HEVC | 464 | 39 | 4.8 kB | 0.39% |
| | Computer AV1 (svt-av1) | 990 | 23 | 5.0 kB | 0.07% |
| | Computer AV1 (libaom) | 718 | 21 | 4.0 kB | 0.03% |
| Flickering leaves and a walker, 2 pictures a second | **Prototype** | **1096** | **828** | **50.5 kB** | exact |
| | Computer H.264 | 838 | 298 | 19.5 kB | 1.82% |
| | Computer HEVC | 1243 | 369 | 24.8 kB | 0.89% |
| | Computer AV1 (svt-av1) | 735 | 341 | 21.6 kB | 0.31% |
| | Computer AV1 (libaom) | 591 | 211 | 13.8 kB | 0.12% |
| The same with the repeating-movement filter at level 2 | **Prototype** | **1406** | **359** | **24.7 kB** | exact |
| | Computer H.264 | 2129 | 196 | 17.6 kB | 2.47% |
| | Computer HEVC | 2536 | 281 | 23.6 kB | 1.26% |
| | Computer AV1 (svt-av1) | 1209 | 271 | 19.1 kB | 0.43% |
| | Computer AV1 (libaom) | 1042 | 244 | 17.0 kB | 0.36% |

The phone rows are from the self-test report of 2026-11-16 (totals, and full pictures of 900 to 1500 bytes). The report did not give the delta size, so it is worked out here as the total less three full pictures, over 57 delta pictures.

What this says:

- **Full pictures: the prototype wins clearly only on the self-test scene** (290 bytes; a third to a fifth of the phone's, 0.3 to 0.6 times the computer encoders'). On the other scenes it is between 0.55 and 1.85 times the size of the computer encoders' full pictures. A patch of fine random texture (the leaves) is as hard for it as for them.
- **Delta pictures: the prototype loses on every scene where something moves**, 1.2 to 3.9 times the bytes of the computer encoders. Against the phone it is level with H.264 at 2 kbit/s asked and 20 to 45% below HEVC and AV1. It wins only when nothing moves (1 byte against 21 to 39).
- **Why.** In the self-test scene all of the 475 pixels that change per picture lie on the borders of the moving person; the wall is perfectly still after the filter. The prototype pays about 2.4 bits for each changed pixel, nearly all of it in the change map (137 of 145 bytes). A video encoder shifts whole blocks of the previous picture (motion prediction) and is allowed to be slightly wrong.
- **30 s of the self-test scene:** 9.1 kB, against 11.9 to 18.2 kB from the phone (23 to 50% less) and 5.8 to 7.9 kB from the computer encoders (15 to 57% more).
- The estimate in ROADMAP.md 7.4 was 0.8 to 1.5 kB for a full picture of 320x240 and tens of bytes for a delta picture of a still scene. Measured: 0.3 to 1.4 kB, and 1 byte. The estimate did not cover delta pictures of a moving scene, and that is where the bytes are.
- The earlier estimate in `tools/bench.py` (index map packed with lzma) is 1.5 to 2.4 times the prototype for full pictures and 2 to 4 times for delta pictures, so the prototype is not a weak one of its kind.

Limits, plainly:

- Every scene is synthetic. Real camera noise that gets through the filter makes borders flicker, and an exact coder must send every flickering pixel, where a lossy encoder may smooth it away. So real footage is likely to be worse for the prototype than this table, not better.
- The comparison is exact against lossy. At the steps in the table the video encoders show 0.03 to 2.5% of the pixels in a visibly different shade. At their finest step measured (0.05% off or less on the self-test scene) H.264 and HEVC need 14 to 16 kB for the self-test scene, more than the prototype, but both AV1 encoders still need less (7.9 and 8.7 kB).
- The computer encoders ran at slow, thorough settings a phone could not use live, and they are not in the app: the app can only use the phone's own encoders unless one is bundled (several MB, speed on a phone unknown). The phone's software encoders made 11.9 to 18.2 kB from the pictures that computer encoders of the same formats code in 5.8 to 7.9 kB, so most of the phone's excess comes from its encoders, not from the formats. The phone's hardware encoders were not tested, and the quality of the phone's output was not measured.
- The phone and the computer ran the same scene code and the same filter; whether their pictures are identical byte for byte was not checked.
- One design was measured, the one in the plan. One other was tried and was no better (each pixel of a delta picture coded with the previous picture's pixel as a fifth neighbour). The border context in the change map was worth 11 to 20% of a delta picture. A delta coder with motion prediction was not designed or measured; it would be a different and larger piece of work than ROADMAP.md 7.4 describes.

**Recommendation for D8 (the owner decides).** By the rule, do not build this coder as FlatRaster's video coder: it does not reach half the bytes on delta pictures on any moving scene, nor on full pictures across the scenes. What the measurement does support is narrower: for a single full picture of a calm scene (the postcard and slideshow of ROADMAP.md 7.6) the full-picture half of the coder is exact at 0.3 to 0.6 kB where the phone made 0.9 to 1.5 kB. Whether that is worth building can be decided when stage 6d comes, after a check on real pictures from the phone.

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
