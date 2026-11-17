# FlatRaster plan

Last updated: 2026-11-17 (0.18: Exact video format; edge codec research stopped, see PLAN-edge.md section 9)

## Start here

- **0.20 (2026-11-17, Sonnet): Puppet smaller.** `dist/FlatRaster-0.20.apk`, installs over 0.19; tested on a computer (`bash tests/run.sh` passes), not run on a phone. From the owner's 0.19 recording (22.8 s, 13.4 kB: key pictures 4.7 kB (5 of them, interval 5 s), points 5.5 kB (82 packets, about 3.7 pictures a second, 66 B each), voice 2.0 kB, packet headers 1.2 kB). Changes: body points 25 to 8 (BlazePose 11 to 16 and 23, 24), position step 1/320 to 1/160 of the width (re-encoding the owner's real points: 5458 B became 2395 B, 29 B a picture), short packets (`.frv` version 4, `FrvWriter.writeShort`: 6 or 7 bytes instead of 12, absolute time at key pictures, relative between; damage costs one packet, tested). 0.19 Puppet files are refused with a message; 0.18 Exact files (version 3) still play. **Settings advice that needs no new build:** full picture interval 30 s and 2 pictures a second. Expected for a 22 s clip: about 1 kB for one key picture, 1.3 kB of points, 2 kB voice, 0.5 kB headers: about 5 kB. **Visible flaw still open:** the person is a faded ghost in the key picture (needs a person cut-out model, ROADMAP.md 7.5 "first neural step"); the background is not drawn as edge lines (option, costs more per key picture).
- **0.19 first phone report (owner, 2026-11-17): Puppet works on the phone.** Recording of 22 s: 13 kB in the file, 4.71 kbps in all (voice included); "it followed my head perfectly well". So the MediaPipe models start and run on the phone, in Java, and the points follow the head. Not yet reported: mouth, arms, playback smoothness, speed. **The size is more than expected** (points about 30 B a picture and a key picture every 10 s should give 2 to 5 kB with voice at 160 x 120); likely causes, to check with the settings used (picture size, pictures a second, full-picture interval): a bigger picture size (a 320 x 240 key picture with real camera noise is several kB, three of them in 22 s), more pictures a second, real points noisier than the made-up test. Next: get the file (or the settings), measure what is in it (bytes of KEY, POINTS, AUDIO packets), then cut: smaller key picture, longer key interval, coarser point step.
- **0.19 (2026-11-17, Sonnet): the neural route, first working version: Video format "Puppet"** (`PLAN.md` "For later: neural network solutions", the puppet stream). `dist/FlatRaster-0.19.apk`, signed with the project key, installs over 0.18. **Built and tested on a computer; the MediaPipe part has never run on a phone or in Java at all** (it needs Android; only its Python twin and the same models were run here). How it works: `Landmarks.java` runs MediaPipe's face landmarker and pose landmarker (lite) on the camera frame (rotated and mirrored like the filter's picture), keeps 22 face points (marks.FACE_IDX) and BlazePose points 0 to 24; `PuppetEncoder.java` writes a KEY packet (Exact picture) at the start and every "full picture" interval and a POINTS packet (type 5) per picture, coded by `jni/motion.c` (a C port of `tools/edge/motion.py`, byte-identical, `tests/points_test.py`); `PuppetDraw.java` draws the puppet for `Player` (key picture faded to 40%, orange frame). `.frv` version 3, codec 4. Hold face still is switched off in this mode (the points are in the whole camera frame). Telemetry: MediaPipe's own statistics logger is replaced by a class that does nothing (`src/com/google/mediapipe/...`, `third_party/mediapipe/get.sh`, which fetches the pinned libraries and models by SHA-256). Libraries: arm64-v8a only (23 MB APK); other processors get a message. Sizes on a computer: points about 30 bytes a picture (1.8 kB for 60 pictures at 2 a second in the test), plus key pictures 0.3 to 1.4 kB each at 160 x 120. Tests in `tests/run.sh`: `points_test.py`, `puppet_test.sh`. A real-landmark drawing check is in the session notes (the puppet looks right on real frames). **Not done:** SAVE MP4 and sending over radio for Puppet files; hands' fingers (hands only as the pose model's wrist points); smoothing between pictures (the puppet moves at the picture rate); the person is still in the faded key picture; anything on a phone.
  **Phone test list for 0.19:** (1) Video format Puppet, Picture size 160 x 120, record 30 s talking in front of the camera; does it start (a message appears if the models cannot start)? (2) Play it back: is the puppet where you are, does the mouth open when you talk, do the arms follow? (3) File size in the list. (4) Speed: does the camera stay smooth at 2 pictures a second? (5) Anything on the screen that says what failed, pasted back.
- **Owner's direction, stated 2026-11-17 (after 0.18): the wanted work is the neural route in "For later: neural network solutions" below** (pose/face "puppet" stream, learned video codec and face re-animation), accepted by the owner as the plan. The Exact format of 0.18 and the edge-drawing codec of `PLAN-edge.md` were not that and are not the goal; do not extend them further. First step for the next session: ask the owner which of the options in that section to start with (the plan says: puppet stream first, then learned codec / re-animation), then do it as a bounded computer experiment with few measurements, and check ROADMAP.md D9 and section 9 (do not invent content) with the owner, who may overrule them. Reuse from this week: MediaPipe landmarks (`tools/edge/marks.py`, needs libegl1), the clips and rig in `tools/edge/`.
- **0.18 (2026-11-17, Sonnet): the working tool, first step: Video format "Exact".** `dist/FlatRaster-0.18.apk` (signed with the project key, installs over 0.17, 16 KB aligned libraries). Built and tested on a computer; **nothing of it has run on a phone**. What it is: the stage 6a exact coder ported to C (`jni/flatcode.c`, `jni/exactjni.c`, `Exact.java`, `ExactEncoder.java`, `PictureEncoder.java`), a new setting **Video format: Exact** (default stays "Best available"), `.frv` version 3 (header codec 3; version 2 files still play; each KEY/DELTA packet is one picture of the coder). Recording takes the filter's picture and palette straight into the coder, no phone video encoder and no bit rate setting; playback decodes and shows it with the smooth enlarging (tolerance 0). Tests: `tests/flatcode_test.py` (C and Python give identical bytes on made-up maps and on the real filter's pictures, ASan/UBSan, cut and corrupted data), `tests/exact_test.sh` (filter, then exact coder, then `.frv` version 3 write and read, then decode: every picture identical; damaged packets do not crash), both in `tests/run.sh`. **Not done:** SAVE MP4 for Exact files (refused with a message), sending over radio (`PLAN-radio.md`; the file is what `Fountain`/`RadioLink` would send), short packet headers (each picture costs 12 bytes of packet header: 60 pictures = 720 B, about 12% of a clip; the plan's task 2.1 has the short header design), and any measure on the phone.
  **What to expect, honestly:** on the made-up scene at 160 x 120, 8 colours, 2 pictures a second, 30 s is 5.3 kB of pictures (6.0 kB in the file), a fraction of the 11.9 to 18.2 kB the phone's own encoders needed at 320 x 240 in the 2026-11-16 self-test. **But on real camera clips (PLAN-edge.md section 9, `tools/edge/sweep.py`) the exact coder was often larger than computer x264**: 160 x 120, 8 colours, 2/s, exact against x264: C1 8.0 against 5.2 kB, C3 4.1 against 3.7, C4 19.8 against 8.1, C5 18.3 against 10.8 (phone encoders were 1.5 to 2x a computer's). Camera noise flickers the borders and the lossless coder pays for every flicker. Fewer pictures help most: at 1 a second, 4 colours, 160 x 120 the exact coder was 1.2 to 6.5 kB per 30 s on those clips, and 128 x 96 at 4 colours 1/s 1.0 to 4.8 kB, but the face is then a blob. Expect 4 to 20 kB per 30 s on a real phone at 160 x 120 depending on light and noise; the owner's recording will say. A lossy delta (let flicker pass) is the next thing to try if it is too big.
  **Phone test list:** (1) Settings: Picture size 160 x 120, Video format Exact, record 30 s talking; look at the file size in the list (the status line says "Exact 160x120") and play it back. (2) The same with Best available; compare sizes and looks. (3) Pictures a second 1 and 2; Colours 8 and 4. (4) Hold face still on (it keeps the background flat, which is what makes this small). (5) Self-test as before.
- **PLAN-edge.md phase 1 was run (2026-11-17, Sonnet): gate G1 NOT passed, and the session STOPPED for a stronger-model session.** Results in `PLAN-edge.md` section 9 and `tools/edge/results.md`. In short: on real clips the picture is 2.4 to 9.9 kB per 30 s with the plan's design (4 to 5 times the 2.0 kB target; only a held key picture, no motion, gets under it) and the 0.17 fallback is 3.0 to 10.8 kB at its best, so neither reaches the target. MediaPipe cannot find faces in posterized or drawn pictures (0 to 17% of frames on three clips), so the legibility measures cannot decide criteria 2 and 3; the warp also fails visibly on large pose changes. C2's source clip is only 1.2 s. Not started: phases 2 to 4. **Owner decision 2026-11-17: the picture budget is raised to 4.0 kB per 30 s (clip with voice about 5.5 kB, about 90 s on DATAC1, 4.2 min on DATAC3); see `PLAN-edge.md` section 1.** **Stage 1 refresh rule tried (2026-11-17): no good inside 4 kB except for a still camera; recommendation: postcard (one key picture plus voice) first, see `PLAN-edge.md` section 9.** Needed before going on: a legibility measure that works on drawings (or an owner look at `tools/edge/out/*.edge.mp4`, which are not committed), and a decision on whether to lower the target (for example a postcard: one key picture plus voice) or raise the budget. Nothing in the app changed.

- **Next work: `PLAN-edge.md` (2026-11-17), the owner's edge-drawing codec, written as tasks for a Sonnet session.** The owner's decision: send over FreeDV DATAC1 now, DATAC3 later, as an upgrade of FlatRaster. That leaves about 530 bit/s for the picture (a 30 s clip with voice on air in 60 s), 2.3x less than the best computer encoder on 0.17's test. Phase 1 is a computer measurement with a pass rule set in advance (gate G1). It uses public codec test clips (section 4), so it needs nothing from the owner. Nothing of it is built yet: start a new session on Sonnet with "Read CLAUDE.md, then PLAN-edge.md, and do phase 1".
- **0.17 (2026-11-17) holds the face still and flattens the background** (stage 6b, ROADMAP.md 7.3 items 2 and 4, asked for by the owner). Built and tested on a computer; **nothing in it has run on a phone**; APK `dist/FlatRaster-0.17.apk`, installs over 0.16. The plan, the measurement and the open work are in "0.17: hold the face still" below. In short: new settings **Hold face still** (default Off) and **Face box** (default Head). On a made-up talking head, with a face detector that trembles by 3 pixels, 30 s needs **27 to 43% fewer bytes** with the face-only box and 8 to 34% fewer with the head box, and 83% fewer changed pixels per picture with the face-only box, with the mouth still followable (r 0.97) and the eyes better visible.
- **What the owner should look at on the phone** (in this order; the phone's face detection is needed, so use the front or back camera that says "Waiting for a face" then "Holding the face still"):
  1. Hold face still On, Face box Head, then Face only: does the face stay put? Is the flat grey around it acceptable? Does it follow when you move your head, and recover when you turn away and come back?
  2. Record 30 s of talking with it On and with it Off, same place: compare file sizes (the plan expects 25 to 45% smaller) and watch both. Does the mouth read as well?
  3. A phone held in the hand, then standing still.
  4. RUN SELF-TEST and paste: new line "Holding the face still: ... ms to follow the face in one camera frame". On a computer it is 0.04 ms.
- **The Faces setting of 0.15 (rest in half or a quarter of the colours) cost bytes in this test**: +26% exact, +11% x264, +11% svt-av1 against doing nothing, because its box follows the detector's trembling and jumps on a 32-pixel grid, and every jump moves a long border. Hold face still makes its own box that follows the window, which does not jump. **Recommendation: leave Faces at "No special treatment", and use Hold face still instead.** Not changed in the app without the phone.

- (0.16, earlier the same day:)

- **0.16 (2026-11-16) is the first part of stage 6b of `ROADMAP.md`: a better picture from the same bytes, and the case for a smaller picture.** Built and tested on a computer; **nothing in it has run on a phone.** APK: `dist/FlatRaster-0.16.apk`, installs over 0.15.2. What is in it, with the figures, is in "0.16" under "What was measured" below. In short:
  1. **Camera frames: Join all** (new setting, on). The camera makes 15 to 30 frames a second and the app kept one in every 3 to 60. Now the last six between two pictures are joined into the picture: it starts from the sharpest and averages the rest in where they agree. On the self-test scene: borders 3 to 12% shorter, 1 to 16% fewer bytes depending on noise; with a shaken hand 42% fewer bytes and 20% shorter borders.
  2. **Picture on screen: Smooth** (new setting, on). The picture is enlarged with smooth borders instead of blocks, live and when playing. It changes nothing in the recording. The face is 9 to 21% nearer the truth than as blocks.
  3. **Border rounding is left out at 160 x 120.** It wiped out eyes and mouths there (face 4.8% off the truth with it, 2.6% without). The Borders setting says so when that size is chosen.
  4. **160 x 120 needs 45 to 55% fewer bytes than 320 x 240** (4.1 against 9.1 kB for 30 s with the exact coder, 4.0 against 8.4 with x264, 3.4 against 6.2 with svt-av1), and shown smooth it is 2.6% off the truth where 320 x 240 as blocks was 1.8%. For a radio link the small size is now the one to try first. The default size is not changed.
  5. Tried and dropped: counting the pixels of the face box several times when the palette is chosen ("faces first", ROADMAP.md 7.2). No gain on the test scene: the camera's box is head and shoulders, and half of it is wall.
- **What the owner should look at on the phone**, in this order:
  1. Live picture and playback with Picture on screen Smooth, then Blocks. Is Smooth better? Does the picture stutter or lag behind?
  2. Camera frames Join all, then Use one, same scene, phone in the hand. Are borders steadier with Join all? The line under the clock says how many frames were joined, and when a sharper earlier one was shown.
  3. Picture size 160 x 120 against 320 x 240, both Smooth: is the small one good enough to read a face? Record 30 s of each and compare the sizes.
  4. RUN SELF-TEST again and paste the report: it has a new line with the time the phone needs to store a camera frame and to enlarge a picture. On a computer these are 1.8 ms and 20 ms.
- **Not done of stage 6b** (ROADMAP.md 7.2, 7.3): local contrast in the face box, a grey palette, crop to the face, holding the eyes still, sketch mode, still background. Crop to the face comes with a caution from stage 6a: a still background costs almost nothing in delta pictures, so cropping saves mainly on full pictures; what a small picture saves is measured in point 4 above.

- **Stage 6a of `ROADMAP.md` is done (2026-11-16, on a computer, nothing in the app changed): the purpose-built picture coder was measured, and by the rule set beforehand it should not be built as the video coder.** A prototype (`tools/coder/flatcoder.py`) codes the posterized pictures exactly. Its full pictures are small (290 bytes for the self-test scene, where the phone's encoders made 900 to 1500), but its delta pictures are bigger than a video encoder's on every moving scene, so 30 s of the self-test scene is 9.1 kB: less than the phone's 11.9 to 18.2 kB, more than the 5.8 to 7.9 kB that computer encoders of the same formats need. Table, limits and recommendation: "Stage 6a" under "What was measured" below. **D8 waits for the owner.** Stages 6b and 6c are not started.

- **Review and big plan for both apps (2026-11-15): `ROADMAP.md` in `apk-codec2-recorder`.** The owner's decisions are in its section 2 (D2 to D10 as recommended; **D1 was decided 2026-11-16: one repository**). For FlatRaster it proposes: one repository with Codec2 Recorder and a shared core; free legibility steps (sharpest picture, averaging, face-first colours, smooth upscaling); face crop and a sketch mode; measuring a purpose-built coder for flat pictures against AV1; a "postcard" (one picture and a voice message) as the first radio product. It supersedes `PLAN-radio.md` if accepted.
- **Stage 0 of that roadmap is built here as 0.15.2 (2026-11-15, Sonnet 5.5): a Self-test panel** (button "Self-test" under the controls). RUN SELF-TEST (about a minute) times the voice chain and DRED coding and playing on the phone, times the posterize filter on a made-up camera scene, and for AV1, HEVC and H.264 lists the phone's encoders (software or hardware, lowest bit rate) and reports what the app's own `VideoEncoder` really produces from that scene at 5 and 2 kbit/s asked: bytes, real kbit/s, full-picture size, time. SAVE TEST CLIP records 30 s of the microphone with no processing by the app (Download/FlatRaster). COPY REPORT copies it and SHARE REPORT sends it; the report is also kept inside the app. Code: `PictureTest.java` (filter, no Android), `VideoTest.java` (encoders), `SelfTestRun.java` (phone side), and `src/pl/dyktafon/SelfTest.java`, which is copied from Codec2 Recorder's file of the same name (identical except `public`; stage 1 merges them). Tested on a computer: `bash tests/selftest/run.sh` (also in `tests/run.sh`), and the page panel in `tests/ui_test.js`. **Not tested on a phone**: `VideoTest` and `SelfTestRun` use Android's encoders and microphone. The scene is synthetic, so real camera noise will cost more bytes than the report shows. **First phone report, 2026-11-16 (Xiaomi 2602BPC18G, Android 16, arm64, 0.15.2): everything passed.** Voice speed as in the recorder (DRED coding 220x, playing 26x, recording 65x real time). The posterize filter takes 21 ms a picture (47 a second possible; the app uses 2). Encoders on this phone: H.264 and HEVC in software and in the camera chip (MediaTek), AV1 in software only; all accept 320x240 and bit rates down to almost 0. The app tries software first, so on this phone it uses AV1 software, and the chip encoders were not tested. **None of them keeps to a low bit rate.** On the made-up scene (30 s, 2 pictures a second, 3 full pictures), asked 5 kbit/s: H.264 3.77, HEVC 4.85, AV1 4.75 kbit/s really; asked 2 kbit/s: H.264 3.18, HEVC 3.93, AV1 3.97, so about double the ask. Full pictures are 0.9 to 1.5 kB each. H.264 made the fewest bytes here (11.9 to 14.1 kB per 30 s), AV1 the smallest full pictures but larger delta pictures. This confirms the estimate in ROADMAP.md 6 and 7.1 that 30 s of video is about 12 to 18 kB, too big for a radio link as it is, and supports measuring a purpose-built coder (stage 6a). Caveats: the scene is synthetic (real camera noise will cost more), one scene only, and the choice of default codec should not be changed on this alone. The report file is `selftest_2026-11-16_01-20-34.txt` on the phone. It also answers which encoder the phone gives and at what real bit rate (ROADMAP.md 7.1).
- Current version **0.17** on `main`, APK in `dist/` (0.16 joined camera frames and smoothed the screen picture; 0.15.2 was 0.15 plus the self-test). Since 0.13 the voice is DRED (Codec 2 is gone; `.frv` files from 0.12 or older don't play), 0.14 put the voice in step with the picture, 0.15 gave it the sound settings of Codec2 Recorder. None of 0.2 to 0.17 has been tried on a phone, apart from the self-test of 0.15.2.
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
| Camera frames joined between pictures; smooth enlarging on the screen; no border rounding at 160 x 120 (0.16) | Filter and enlarger tested on a computer; the Android side built, not run on a phone |
| Hold the face still, flat background, Face box (0.17) | Tested on a computer (`tests/hold_test.py`); the Android side built, not run on a phone |
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

## 0.17: hold the face still (2026-11-17)

**The plan** (from the owner's question: can the face box be smaller, and how else can the picture be made cheaper and stay legible). From stage 6a: bytes go to borders that move; a still area costs almost nothing. In a talking head the thing that moves most is the head's sway, and every pixel it shifts moves the head's outline and the whole background behind it. So: (2) keep the face still in the picture by moving the crop window with the face, and (3) make everything outside a box around the face one flat colour so the moving background costs nothing. Each alone is worse than doing nothing (below); together they are the gain.

Steps and what came of them:

1. **A smaller box.** Done: the box is a setting, Face box: Face only (the face rectangle widened by 15% of its size on every side), Head (40%), Head and shoulders (75%, what 0.15 used).
2. **Measure on a computer before building** (`tests/hold_test.py`, `tests/hold_test.c`). Done, below.
3. **The face detector trembles, so it cannot be followed directly.** Found by measurement: with the detector's centre as it is (3 pixels of noise, which is a guess for a phone), holding the face from it gives none of the gain (13676 bytes against 13746 for doing nothing, exact coder). Following the face by looking at the picture does: `flat_track` matches the brightness inside the face rectangle against the previous camera frame, to a fraction of a pixel, and the detector's smoothed position pulls the window back slowly so errors cannot add up. Done.
4. **The Android side.** Done: `Flat.setHold`, `Flat.track`, the face rectangle from `CameraSource` (unwidened, unsnapped), settings Hold face still (Off) and Face box (Head). Waiting for a face: until the first face is seen the picture is the whole frame, as before.
5. **Phone test** (the list at the top). Open.
6. **After the phone test, depending on it:** make Hold the default; remove the Faces setting; tune the 1.25 zoom, the pull back to the detector (0.02 a frame) and the smoothing (1/2) on real faces; check what happens when the face turns away, nods, or is covered.
7. **Ideas not done** (ROADMAP.md 7.3): store a picture only when the face region changed (the "Still pictures" setting looks at the whole picture); a grey palette; local contrast in the face; sketch mode.

The scene: a made-up talking head (640x480 camera frames at 16 a second, 2 pictures a second kept, 60 pictures = 30 s, 8 colours, 320x240 pictures) in front of a wall with a gradient, a bookshelf of stripes and a window. The head sways by 40 pixels over 4 s with a quicker wobble, the mouth opens and closes at random-looking speed, eyes and mouth are only a little darker than the skin. The face detector's rectangle trembles (centre 3 pixels, size 5%). "Eyes": share of eye pixels shown in another colour than the skin. "Mouth r": correlation between the true mouth opening and the non-skin pixels in the mouth area. "Face sd": how far the face wanders in the picture.

| Option | Exact coder | x264 | svt-av1 | Pixels changed per picture | Eyes | Mouth r | Face sd |
|---|---|---|---|---|---|---|---|
| 0.16, nothing special | 13746 | 9137 | 6461 | 3356 | 75% | 0.98 | 14.9 |
| Faces: rest in half the colours, box 0.75 (0.15) | 17296 | 10116 | 7200 | 6708 | 75% | 0.98 | 14.9 |
| Zoom 1.25, window in the middle | 14673 | 9480 | 7166 | 4595 | 79% | 0.96 | 18.6 |
| Hold from the detector, no flat background | 20601 | 12954 | 9031 | 9330 | 80% | 0.95 | 3.1 |
| Hold from the detector, smoothed, flat background, head | 13912 | 9779 | 6604 | 2709 | 82% | 0.97 | 2.8 |
| Hold from the image, no flat background | 15167 | 11815 | 8254 | 7807 | 84% | 0.97 | 0.7 |
| Hold from the image, flat background, head and shoulders | 13253 | 11351 | 7807 | 4734 | 84% | 0.97 | 0.7 |
| **Hold from the image, flat background, head (the default)** | **9226** | **8539** | **5844** | **1825** | **84%** | **0.97** | **0.7** |
| **Hold from the image, flat background, face only** | **7095** | **6073** | **4574** | **429** | **84%** | **0.97** | **0.7** |
| Hold from the image, quarter of the colours, head | 10083 | 9767 | 6089 | 3163 | 84% | 0.97 | 0.7 |
| Hold from the image, flat background, head, zoom 1.10 | 13596 | 10420 | 6551 | 3197 | 79% | 0.96 | 3.6 |
| Hold from the image, flat background, head, zoom 1.50 | 9444 | 8215 | 5919 | 1338 | 86% | 0.76 | 0.9 |
| Hold from the image, flat background, head, no border rounding | 10263 | 8302 | 5829 | 1845 | 90% | 0.99 | 0.7 |

Against 0.16 (bytes for 30 s): head box 33% fewer with the exact coder, 7% with x264, 10% with svt-av1; face-only box 48%, 34% and 29%. Pixels changed per picture: 46% and 87% fewer.

What this says:

- **Each half alone loses.** Holding the face without a flat background costs 10 to 40% more (the whole background moves in the picture instead); a flat background without holding costs more too (the box jumps with the detector). They work together.
- **The detector is the weak point.** With a perfect detector (no tremble) the same hold gives 8660 exact bytes; with 3 pixels of tremble and following the detector, 13912; with the image-based follower, 9226. At 6 pixels of tremble the image-based follower still gives 9652.
- **Video encoders gain less than the exact coder** (7 to 10% against 33% for the head box), because they already move blocks of the previous picture to follow a moving head; the exact coder of stage 6a does not. So this feature makes the stage 6a prototype competitive with x264 on this scene (9226 against 8539) but not with svt-av1 (5844). Stage 6a's rule (half the bytes on delta pictures) is still not met; D8 is unchanged.
- **Zoom 1.25 is the smallest that works**: the window has to follow a 50-pixel sway. At 1.10 the face escapes the window (face sd 3.6) and the gain is gone. At 1.50 the gain is similar but the mouth is no longer followable (r 0.76, not understood: not looked into).
- **Border rounding costs the held face bytes** (8 to 11%) and buys visibility (eyes 90% against 84%). Not changed: that is for the owner's eyes.
- The flat colour is the middle one of the palette by brightness, the grey of a wall; it hides the edge of the box where it matches the wall.
- Cost: 0.04 ms for one camera frame on a computer (the picture comparison is a plain loop the compiler vectorises); the self-test reports it on the phone.

Limits, plainly: one made-up scene; a made-up detector tremble (3 pixels is a guess, no phone has been measured); the face does not turn, nod, get covered or leave the picture; the image follower can be fooled by a mouth that fills the match area, glasses, a hand across the face (the pull back to the detector limits the damage to a few seconds, but it is not tested); the faces are all one head; real camera noise is partly removed by the camera chip. Everything here is a reason for the phone test, not a reason to doubt the direction.

## 0.16: camera frames joined, smooth enlarging (2026-11-16)

All on a computer, synthetic scenes; `bash tests/run.sh` repeats every figure (`tests/cam_test.py`, `tests/smooth_test.py`). Scene: the self-test scene (a head that sways in front of a wall), 2 pictures a second for 30 s, 8 colours.

**Joining the camera's frames** (`flat_gather` in `jni/flat.c`). The camera gives 16 frames a second here; the last six before each picture are joined with it. The noise limit is measured from the frames themselves (three times the middle difference between them, kept between 6 and 24), so there is nothing to set. "Border" is the length of the borders against the same scene without noise; "face" is how much of the face differs from that ideal.

| Camera noise | Frames | Border | Pixels changed per picture | Face | Bytes, exact coder | x264 | svt-av1 |
|---|---|---|---|---|---|---|---|
| 2.5 (as the self-test) | one kept | 1.06 | 466 | 0.21% | 9202 | 8376 | 6309 |
| | all joined | 1.03 | 466 | 0.21% | 9091 | 8485 | 6054 |
| 8 | one kept | 1.13 | 469 | 0.30% | 9520 | 8619 | 6890 |
| | all joined | 1.09 | 468 | 0.31% | 9333 | 8333 | 6514 |
| 16 | one kept | 1.33 | 554 | 0.55% | 12141 | 10704 | 10484 |
| | all joined | 1.17 | 504 | 1.00% | 10184 | 8991 | 7848 |
| 8, shaken hand | one kept | 1.37 | 869 | 0.88% | 16035 | 9988 | 9157 |
| | all joined | 1.10 | 468 | 0.70% | 9237 | 8629 | 6586 |

- With little noise the gain is small (the filter's smoothing and hold rule already deal with it): borders 3% shorter, bytes within 4% either way.
- With strong noise: borders 12% shorter, 16 to 25% fewer bytes. The price: the moving outline of the head lags by a fraction of a pixel (face 0.55% to 1.00%), because at that noise the limit is as large as the contrast between head and wall.
- A shaken hand (two frames in eight blurred, and in every second picture the frame that would have been kept): the sharper earlier frame is chosen for exactly those pictures; 42% fewer bytes with the exact coder, 14 and 28% with x264 and svt-av1.
- Without noise, joining changes no pixel at all, so movement is not smeared.
- Cost: 1.8 ms on a computer to store a frame. On a phone not measured; the self-test now reports it.
- Limits: the noise is made up and has no pattern, real camera noise is partly removed by the camera chip before the app sees it; the camera's real frame rate in poor light is unknown (the app asks for the lowest range). So the gain on a phone may be nearer the first rows than the last.

**Smooth enlarging on the screen** (`jni/smooth.c`): each output pixel takes the colour that has the most weight in the 4 x 4 source pixels around it (a smooth bell), and within a pixel of a border the two strongest are mixed. No new colours appear, and a line one source pixel wide survives. Truth: the scene without noise posterized at 640 x 480. "Face": pixels in the skin colour in one picture but not the other, as a share of the truth's.

| Picture, enlarged to 640 x 480 | Face, as blocks | Face, smooth | Bytes for 30 s, exact coder | x264 | svt-av1 |
|---|---|---|---|---|---|
| 160 x 120, borders as they fall (what 0.16 records) | 3.31% | 2.63% | 4081 | 3965 | 3433 |
| the same after H.264 (x264 step 40) | 5.69% | 4.78% | | | |
| the same after AV1 (svt-av1 step 45) | 4.20% | 3.34% | | | |
| 160 x 120, borders rounded (what 0.15 recorded) | 5.33% | 4.83% | 3383 | 3968 | 3491 |
| 320 x 240, borders as they fall | 1.72% | 1.46% | 10074 | 8259 | 5852 |
| 320 x 240, borders rounded (the default) | 1.83% | 1.53% | 9057 | 8408 | 6205 |

- Smooth is nearer the truth in every row, by 9 to 21%. The numbers understate what the eye sees: a staircase and a smooth line differ by few pixels.
- Rounding at 160 x 120 nearly doubles the face error, because its 5 x 5 window is wider than an eye. 0.16 leaves it out below 150 pixels on the short side (`MainActivity.roundFor`). At 320 x 240 rounding costs the face little; it saves 10% with the exact coder but costs 2 to 6% with the video encoders, so the earlier case for it (shorter borders on a soft moving scene) is weaker than it looked. The default is not changed without the phone.
- 160 x 120 needs 0.45 times the bytes of 320 x 240 with the exact coder, 0.47 with x264 and 0.55 with svt-av1.
- Cost: 20 ms on a computer to enlarge 320 x 240 four times. On a phone not measured; the self-test now reports it. It runs once per picture shown, not per camera frame.

**Tried and dropped: faces first.** Pixels inside the camera's face box counted 4 or 8 times when the palette is chosen. On a scene made for it (strong wall gradient, face little brighter than the wall, eyes 32 levels darker than the skin; 4, 6 and 8 colours) the share of eye and mouth pixels shown apart from the skin stayed at 80 to 93% with and without it. The box the camera gives is head and shoulders, and about half of it is wall. Not built in.

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
