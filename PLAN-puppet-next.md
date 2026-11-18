# Puppet: state and next steps

Written 2026-11-18 at the end of a long session (Sonnet), for the next session. Read `CLAUDE.md`, then this file, then `PLAN.md` "Start here". The owner's direction (2026-11-17): the neural route of `PLAN.md` "For later: neural network solutions", a recognisable picture of the owner at the lowest bit rate. No rush; he asked for these ideas to be kept for later.

## 1. Where it stands (FlatRaster 0.28, `dist/FlatRaster-0.28.apk`)

Video format **Puppet** (`.frv` version 4, codec 5):
- **Key picture** at the start and every "Full picture every" (2, 5, 10, 30 s ...): the filter's posterized picture, as a **person cut-out** (MediaPipe pose model with the person mask, run only on the key frame) over a **coarse colour map** background (one colour per width/20 block), coded by the Exact coder (`jni/flatcode.c`). The person keeps the filter's colours.
- **Points** per picture: 30 face points (the 22 of `tools/edge/marks.py` plus 8 lip points) and 8 body points (shoulders, elbows, wrists, hips) from MediaPipe's face and pose landmarkers, coded by `jni/motion.c` (byte-identical with `tools/edge/motion.py`), step 1/320 of the width, about 30 to 50 bytes a picture.
- **Playback** (`Player`, `PuppetDraw`): the key picture is warped by a thin-plate spline from the key's points to the current ones (`PuppetDraw.warp`): head anchors above the face by an affine fit, no border anchors, movement fading beyond 22% of the width from the nearest point, whole-field limit 25% of the width; thin semi-transparent contrast-adaptive lines (setting "Puppet lines when playing": mouth and eyes / all / none); an orange frame always (it is a puppet, not video).
- Owner's phone reports: the MediaPipe models run and follow the head; the cut-out works; the first warp bent the top of the head (fixed in 0.27 with a test, not yet confirmed on the phone); lip movement weak in 0.25 (improved in 0.26, not yet confirmed).
- Sizes so far on the owner's phone: 0.19 22.8 s 13.4 kB; 0.20 25.2 s 9.1 kB (keys 2.8, points 2.0, voice 4.3 kB, keys every 5 s because palette rebuilds forced them; fixed in 0.22).

## 2. Ideas for later (the owner asked for them to be saved; estimates are mine, not measurements)

Where bytes go: background and face only in key pictures; anything that moves between keys is free if driven by data already sent.

1. **[DONE in 0.29]** **Background follows the camera** (cheapest, probably most visible). One global shift (dx, dy) per picture from camera motion (2 to 3 bytes; estimate it on the sender from the camera frames, the filter already tracks the face, `flat_track` in `jni/flat.c` is a model). The player shifts the background (not the person) by it. Hand-held phones otherwise leave a frozen background.
2. **[DONE in 0.29]** **Cross-fade between key pictures** on playback: free, replaces the jump every few seconds by a smooth change. Needs the player to know the next key (it has the whole file).
3. **Vector shapes for the background**: trace the label map into flat-colour polygons (contour tracing, Douglas-Peucker simplification), draw them at any size (crisp, no blocks). Guess 300 to 900 bytes against about 100 to 200 for the coarse blocks: **measure first** (`tools/coder/` has the rig: compare bytes and look against the coarse blocks at equal legibility; keep it only if it looks clearly better for the bytes). Animated variant: tween shapes between keys (free).
4. **Vector face** (skin, hair, eyes, mouth as shapes driven by the same points): crisp but more cartoon-like and less recognisable than the warped real picture; only if the raster face stays unsatisfying. Must be derived from the real picture (the project rule: invent nothing).
5. **Lips**: if still weak after 0.26 on the phone: exaggerate the mouth opening in the drawing (it is real but small at 160 x 120), a bigger picture size, or a smaller quantisation step; check with the owner's `.frv` (parse POINTS sizes).
6. **Face patch**: a small key of the face box (about 150 bytes) when the expression or pose has changed a lot since the key, so the face does not go stale between keys (the research patch code is `tools/edge/patch.py`).
7. **[DONE in 0.29]** **Smoothing between pictures** on playback (interpolate the points to 10 to 15 frames a second from 2 to 4 recorded): free in bytes; needs the player to look ahead one picture (it has the file).
8. **Radio**: sending the `.frv` over the recorder's modem (`PLAN-radio.md`, `Fountain`, `RadioLink`), SAVE MP4 for Exact and Puppet files, both not done. The owner's size target: about 7 kB for 30 s with voice (DATAC1 in about 2 minutes); a recording of 25 s was 9.1 kB with keys every 5 s.

## 3. Open questions for the owner

- Do the lips read now (0.26)? Is the top of the head fixed (0.27)? Are the thin lines readable (0.28)?
- Which idea first? (Suggested order: 1, 2, 7, then measure 3.)
- Is "Mouth and eyes" the right default for the lines?
- Voice is now the largest part of a recording (4.3 of 9.1 kB): lower DRED level and "Skip silence" are the levers; test by ear.

## 4. How to work on it

- Build: `source /home/user/jacekkotowski/apk-codec2-recorder/env.sh && bash build.sh` (needs `apk-codec2-recorder`'s `setup.sh` run once for the Android SDK, 3 GB; keys from the private `apk-signing-keys`, folder `apk-flatraster`: copy `flatraster.keystore` and `signing.env` here, never commit them). `build.sh` fetches MediaPipe (`third_party/mediapipe/get.sh`, pinned, SHA-256 checked, needs `libegl1` only for the Python tools).
- Tests: `bash tests/run.sh` (about 8 minutes; it now keeps its temp files in one directory that it removes: before that each run left 1.2 GB in /tmp and filled the container's disk twice). Puppet tests: `tests/puppet_test.sh` (format, warp, drawing, lines), `tests/points_test.py` (C against Python), `tests/exact_test.sh`.
- Code map: `src/pl/flatraster/` `Landmarks.java` (MediaPipe), `PuppetEncoder.java`, `Player.java`, `PuppetDraw.java` (drawing, warp), `Points.java`, `Exact.java`; `jni/` `motion.c`, `flatcode.c`, `rc.h`, `exactjni.c`, `pointsjni.c`; research in `tools/edge/` (edge-drawing codec, G1 not passed, `PLAN-edge.md`).
- Visual checks of the warp need real frames: `tools/edge/run.sh` re-downloads the test clips (about 4 GB, 25 minutes); the container's disk is limited, delete `tools/edge/out` when done.
- The MediaPipe Java code has run on the owner's phone only; the status line (`PuppetEncoder.describe`) says whether the cut-out and the points were found and why not; ask for it or the `.frv` file when something fails.
- Hand the APK to the owner as a file in the chat as well as in `dist/`; end the session with `PLAN.md` updated and the work on `main` (the owner allowed pushing to `main` in this session).
