# Edge codec: a talking head over FreeDV DATAC1

Written 2026-11-17 (on the stronger model) from the owner's draft "Ultra-Low Bitrate Neural-Edge Video Codec", for a Sonnet session to execute step by step. Nothing in it is built yet. It is stage 6c/6d work of `ROADMAP.md` (in `apk-codec2-recorder`) and replaces nothing there; where it departs from the owner's draft, section 2 says why.

**How to use this file (Sonnet):** do the tasks in order. Each task names its files, what to do, and "Done when". Tick the box, write the figures into section 9 ("Results") with the date, commit, push. Obey section 8 ("Rules for the executing session"). Do not decide a gate yourself when section 8 says to stop: write the problem into section 9 and `PLAN.md` "Start here" and end the session.

## 1. The target, in numbers

The owner's decision (2026-11-17): **the link is FreeDV DATAC1 now, DATAC3 later; FlatRaster is upgraded, no new app.** The modem and transport are Codec2 Recorder's (`libfdv.so`, `Fountain`, `RadioLink`), which send a whole file with repair frames.

What the link carries, from the recorder's measured burst times (its `PLAN.md`, "Time on air"): DRED smallest, 2.1 kB, +50% repair, takes 35 s in Fast (DATAC1) and 1.6 min in Robust (DATAC3). So the **useful rate is about 480 bit/s (60 bytes a second) on DATAC1 and about 175 bit/s (22 bytes a second) on DATAC3**, repair frames included.

So live video is not possible on DATAC1: the voice alone (DRED smallest, 390 bit/s with headers) takes most of it. The product is **store and send**: record 30 s, send it as one file.

| | Budget | Why |
|---|---|---|
| **Goal** | **30 s clip, voice and picture, on air in at most 60 s on DATAC1** (and so about 2.7 min on DATAC3) | Twice the real time is still a usable exchange on a radio |
| Total file | 3.6 kB per 30 s | 60 s x 60 bytes |
| Voice | 1.5 kB per 30 s | DRED smallest q12, 390 bit/s with packet headers (`PLAN-dred.md`) |
| **Picture** | **at most 2.0 kB per 30 s, about 530 bit/s** (the rest is the container) | |
| What exists today, best case (0.17, face only, made-up head, `PLAN.md` "0.17") | 4.6 kB (svt-av1 on a computer), 6.1 kB (x264), 7.1 kB (exact prototype coder); the phone's own encoders were 1.5 to 2x a computer's | So the picture must shrink **2.3x against the best computer encoder** and more against the phone |

Per picture at 2 pictures a second, 2.0 kB per 30 s is **33 bytes a picture on average**. That number governs every choice below: a key picture can cost a few hundred bytes only because it is rare, and a motion picture must fit in about 15 to 25 bytes.

## 2. Changes from the owner's draft, and why

| Draft | This plan | Why |
|---|---|---|
| 3 to 8 kbit/s | 530 bit/s for the picture | DATAC1 carries about 480 bit/s useful (section 1). At 3 to 8 kbit/s the phone's own AV1 already works (self-test 2026-11-16: 4 to 5 kbit/s real) and no new codec is needed |
| 8 pictures a second | Motion data at 2 or 4 a second (measured), shown at 8 by interpolation | 8 a second of landmark deltas alone is over the budget |
| All face and hand landmarks, 4-bit deltas | A chosen subset (about 20 face points, 21 per hand), deltas entropy coded with the arithmetic coder of `tools/coder/flatcoder.py` | 478 face points x 2 x 4 bits = 239 bytes a picture, 7x the budget |
| zstd for the edge map and the colour map | zstd measured as the draft's baseline; the context-modelled arithmetic coder of stage 6a measured beside it; the smaller one is kept | Stage 6a: context modelling of neighbours is what makes flat maps small. A general compressor does not see two-dimensional context |
| Own packet header, CRC16 per packet, ACK | The clip is one file (`.frv` version 3) sent by the recorder's `Fountain` + `RadioLink`; losses are repaired by repair frames and "need N more" (`ROADMAP.md` 6.1 item 6) | The modem frames already carry their own CRC; the file transport already carries a CRC32 and needs no list of missing parts. A per-packet stream format is only for a later live mode (task 2.4, optional) |
| Kotlin, CameraX, OpenCV, MediaPipe on Android | Java and C in the existing hand build; the picture side in `jni/`; MediaPipe only if a spike shows it can be linked by hand (task 3.1) | Project rule: no Gradle, no new framework (`CLAUDE.md`, `ROADMAP.md` 11). OpenCV for Android alone is larger than the whole app |
| Thin-plate-spline warp shown as video | Built and measured as an **experiment**; the player marks warped pictures as such ("moved, not seen") and never hides that a picture is not a real one | `ROADMAP.md` 11: nothing that invents content. A warp shows the key picture moved, which is not what the camera saw. The owner decides after seeing it (gate G3) |
| 10 s test sequence | 30 s, real clips (section 4), as the rest of FlatRaster's measurements | Compare with the 0.17 table on the same terms |

Defaults this plan takes where the owner did not answer (each can be overruled; say so in `PLAN.md` and change this section): store and send; computer first, Android only after gate G1; warping as a marked experiment; public test clips, no owner participation (section 4).

## 3. The design in one page

A clip is: key pictures (rare), motion pictures (often), patches (when something new appears in the hand), voice (DRED, unchanged).

- **Key picture (K).** From the camera frame, after FlatRaster's own filter where it helps (joining frames, holding the face still, flat background from 0.17):
  1. Edge map: bilateral filter, then Canny, at the coded size (default 160x120, measured also at 128x96 and 96x72), one bit a pixel. Thin it to one pixel wide.
  2. Colour map: the picture shrunk to 32x24 (measured also 16x12, 48x36), posterized to 16 colours by median cut (8 measured too); palette 16 x 3 bytes.
  3. Code both: (a) zstd level 19 (the draft); (b) the arithmetic coder with the four-neighbour context of `flatcoder.py` (edge map as a 2-colour map; colour map as is). Keep the smaller per map.
  4. Receiver: enlarge the colour map with joint bilateral upsampling guided by the edge map (colours do not bleed across an edge), then draw the edges dark over it. The result is the "drawing" the receiver shows.
- **Motion picture (M).** Landmarks from MediaPipe (computer) for the face subset and both hands; head pose as 3 angles and a centre. Each point's position is predicted from the last two pictures (constant speed); the error is quantised to 1/2 pixel of the coded size and arithmetic coded, with a "no hand" / "no face" flag. The receiver warps the last key picture (or patch) with a thin-plate spline from the key picture's landmarks to the new ones, and interpolates between motion pictures for the screen.
- **Patch (P).** Each motion picture, the sender warps its own last key picture the same way the receiver will and compares it with the camera frame inside the hand box (and the face box). Where the difference is large (threshold measured, section 6), it sends that box as a small key picture (edges and colours, same coder) instead of waiting for the next key picture. Rate-limited: at most one patch a second, at most 300 bytes.
- **When to send a key picture:** at the start; when the patch rule fires over more than half the picture (a cut, a turned camera); never on a timer by default (a timer is measured as a variant). Key pictures cost the most, so this is the main lever.
- **Fallback, always measured beside it:** the 0.17 path (posterized, held face, flat background) with the stage 6a exact coder and with svt-av1, at the same picture rate and size. If the edge codec does not beat it by the rule of gate G1, the fallback is what goes forward.

## 4. Test clips (no owner needed)

The owner has no time to record (2026-11-17), so the clips are public codec test sequences from Google's `aom-test-data` bucket, which the cloud container can reach (Wikimedia, Pexels, xiph and the like are blocked). Raw YUV 4:2:0 (`.yuv`, size in the name, 30 frames a second) or `.y4m`. Download in `tools/edge/get_clips.sh` to `tools/edge/out/clips/` (not committed: large, and faces of real people), each from `https://storage.googleapis.com/aom-test-data/<name>`:

| Clip | File | Size | What it tests |
|---|---|---|---|
| C1 talking head | `niklas_640_480_30.yuv` | 217 MB, about 16 s | the main case |
| C2 two presenters, hands, objects on the desk | `paris_352_288_30.y4m` | check | hands, a second face |
| C3 desk meeting | `thaloundeskmtg_640_480_30.yuv` | check | several people, small faces |
| C4 person, still camera | `macmarcostationary_640_480_30.yuv` | check | head and shoulders |
| C5 person, moving camera | `macmarcomoving_640_480_30.yuv` | check | hand-held |

Steps: download with `curl -sS -o`, print each size; convert raw `.yuv` with ffmpeg (`-f rawvideo -pix_fmt yuv420p -s 640x480 -r 30`); if shorter than 30 s, play forward then backward to make 30 s and say so in the results. Before any measurement run MediaPipe on every 10th frame and write into section 9 the share of frames with a face and with a hand per clip; drop a clip that has a face in fewer than 80% of frames (and say so). G1 then needs four of the clips that remain (three if only four remain).

Not covered by these clips: holding up objects to the camera, and poor light. Write that as a known gap in the verdict; the owner's phone test (phase 4) covers it. If the owner later puts own clips in `tools/edge/clips/`, add them to the table and re-run.

## 5. Tasks

All of it lives in `tools/edge/` (Python, not in the app) until gate G1, as `tools/coder/` did. Pin versions in `tools/edge/requirements.txt`: `numpy`, `opencv-python-headless`, `zstandard==0.25.0`, `mediapipe==1.2.3`. Models (download in `tools/edge/get_models.sh`, not committed; record their SHA-256 in the script):
`https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`,
`https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`.

### Phase 1: prototype and measurement (computer)

- [ ] **1.1 Clip loader.** `tools/edge/clips.py`: read a clip with OpenCV (or a `.y4m`), resample to 640x480 at 16 frames a second (FlatRaster's camera rate in the tests), write `out/<name>.rgb` + a JSON with size and count. Also a made-up clip from `tests/hold_test.py`'s `make_clip` for unit tests. Done when: `python3 clips.py` writes all clips present and the made-up one, and prints their names, sizes and frame counts.
- [ ] **1.2 Key picture coder.** `tools/edge/keypic.py`: `encode_key(rgb, size, cmap_size, k, coder) -> bytes`, `decode_key(bytes) -> (edges, cmap, palette)`, `render_key(...) -> rgb` (joint bilateral upsampling, edges drawn). Reuse the arithmetic coder by importing `tools/coder/flatcoder.py` (do not copy it). The byte layout is written in the module docstring (field, size, order) so a C port can follow it. Done when: a round trip on 20 made-up frames gives back exactly the same edge map and colour map, for both coders; a test prints bytes for each part.
- [ ] **1.3 Landmarks.** `tools/edge/marks.py`: run the face and hand landmarkers (VIDEO running mode, timestamps from the frame number) on each frame; keep the subset (section 7 lists the face indices); store per frame: face present, head pose (3 angles from the facial transformation matrix, centre, scale), the subset points, and per hand present + 21 points. Write `out/<name>.marks.npz`. Done when: on each real clip it prints the share of frames with a face and with each hand; on the made-up clip it says "no face" without failing.
- [ ] **1.4 Motion coder.** `tools/edge/motion.py`: `encode_motion(prev2, prev1, cur, step) -> bytes` and the decoder, as in section 3; quantisation step as a parameter (1/2 pixel default, also 1 and 1/4). Done when: decoded points are within half a step of the input on every frame of every clip, and it prints mean bytes per motion picture.
- [ ] **1.5 Warp and patches.** `tools/edge/warp.py`: thin-plate spline from key-picture points (face subset, hands, plus 8 fixed points on the picture border so the background stays put) to current points; `render_motion(key_render, key_pts, cur_pts) -> rgb`. `tools/edge/patch.py`: the sender-side comparison and the patch coder (a key picture of a box, same format plus box position). Done when: on the made-up clip with a known shift, the warp moves a marked point to within 1 pixel; patches round-trip exactly.
- [ ] **1.6 The whole clip.** `tools/edge/codec.py`: `encode_clip(frames, params) -> list of (type, time, bytes)` and `decode_clip(...) -> frames for the screen at 8 a second`, applying the rules of section 3 (key at start and on a cut, patches, motion at 2 or 4 a second). The decoded frames also carry a flag per frame: real (key or patch just received) or warped/interpolated. Done when: a clip encodes and decodes in one run, the sum of bytes is printed by type, and the decoded video is written as `out/<name>.edge.mp4` (lossless, for looking at) with warped frames marked by a thin coloured frame border.
- [ ] **1.7 Fallback numbers on the same clips.** `tools/edge/baseline.py`: the same clips through FlatRaster's real filter (`jni/flat.c`, as `tools/coder/clips.py` and `tests/hold_test.py` call it) with 0.17 settings (hold the face still, flat background, face only and head), 8 colours, 2 pictures a second, at 320x240 and 160x120; coded with the stage 6a exact coder and with svt-av1 (preset 4, step 45) and x264 (veryslow, 40), a full picture every 10 s. Done when: it prints bytes per 30 s for each and writes the decoded pictures for the legibility measure.
- [ ] **1.8 Legibility measures.** `tools/edge/legible.py`, the same for every coder, against the original camera frames (section 6 defines them). Done when: run on the original frames themselves it gives perfect scores (face found 100%, error 0, r 1.00), and on a frame shifted by 5 pixels it reports the shift.
- [ ] **1.9 The table.** `tools/edge/run.sh` runs 1.1 to 1.8 for every clip and every variant (section 6) and writes `tools/edge/results.md`. Copy the summary table into section 9 here. Done when: the run finishes from a clean checkout with only `requirements.txt` and `get_models.sh` as setup, and the numbers in section 9 come from that run.

**Gate G1 (decided by the rule, not by taste; write the verdict in section 9):** the edge codec goes forward to phase 3 only if, on at least four of the five clips (or of the xiph clips used), with the best variant chosen once for all clips:
1. the picture is **at most 2.0 kB per 30 s**, and
2. it is **at most 0.6x the bytes of the best fallback** (1.7) that reaches the same legibility, and
3. its legibility is not worse than that fallback's on face found, mouth r and hand found (section 6: "not worse" = within 0.05 of r and 5 points of the shares).
If 1 holds but 2 or 3 fails, the 0.17 path with the exact coder at 160x120 is what goes forward instead, and phase 3 builds that (task 3.0b). If neither reaches 2.0 kB, stop: write the closest figures and what would be needed, for the owner.

### Phase 2: the file and the transport (computer)

- [ ] **2.1 `.frv` version 3.** A new record type per section 3 (K, M, P), keeping the existing header, voice packets and time stamps; version 1 and 2 files still play (`FrvReader`). Short record headers: 1 byte type and 1 byte length when the payload is under 256 bytes (ROADMAP 6c "short packet headers"). The byte layout goes in `docs/format-frv.md` (create it if stage 2 has not). Done when: `tests/frv_test.sh` writes and reads back a version 3 file with all record types and still reads its version 2 file; a version 3 clip from 1.6 written by the Python writer is read by `FrvReader.java` (a small Java test, as `tests/FrvTest.java`).
- [ ] **2.2 Through the radio, on a computer.** Do not copy the modem code here: use the recorder's own radio test. In `apk-codec2-recorder`, `tests/radio/` (`build.sh`, `run.py`, `chan.py`, `RT.java`) sends a file through DATAC1 and DATAC3 with simulated channels; clone that repository read-only and point it at the clip files. Send three version 3 clips (the median one, the largest, the smallest of 1.9) with +50% repair through DATAC1 at 5 dB and DATAC3 at 0 dB, three runs each. Done when: section 9 has time on air and "received complete in N of 3" for each; the decoded clip is byte-identical to the one sent.
- [ ] **2.3 Damage behaviour.** Even with repair frames a send can end incomplete. Make the reader skip a record whose length runs past the end, and the player continue from the next K record. Done when: a test cuts a clip at 10 random places and the player shows everything up to the cut and nothing wrong after it.
- [ ] **2.4 (Optional, only if the owner asks for a live mode on DATAC3 later.)** A stream framing: each modem frame holds whole records, `[type 1B][seq 1B][len 2B] payload [CRC16-CCITT]` as in the draft, little-endian; M and P records depend on the last K by its sequence number; on a CRC error the receiver drops dependent records until the next K. Not before the owner asks.

### Phase 3: in the app (Android, Java and C)

Starts only after gate G1. If stage 1 of `ROADMAP.md` (one repository) has started, do this in `apk-codec2-recorder/apps/flatraster/` instead and say so here.

- [ ] **3.0 Which path.** If G1 chose the edge codec: 3.1 to 3.6. If it chose the 0.17 path with the exact coder (3.0b): port `flatcoder.py` to C as `jni/flatcode.c` (byte-exact with the Python on the stage 6a clips), add it as a "Coder: Exact" setting writing `.frv` version 3, and skip 3.1 to 3.3.
- [ ] **3.1 Spike: MediaPipe by hand.** In a scratch folder, not in the app: download `com.google.mediapipe:tasks-vision:1.2.3` and `tasks-core` from `dl.google.com/android/maven2` with their declared dependencies, unpack the AARs, put `classes.jar` files on the javac/d8 class path and the `.so` files for arm64-v8a in `lib/`, run the face landmarker on one JPEG in a minimal activity built with the project's `build.sh` steps. Check that every `.so` is aligned for 16 KB pages (`CLAUDE.md`). Report: works or not, APK size added, time per frame in the self-test. Done when: section 9 has the answer. **If it does not work in half a day of trying, stop and report**: the fallback is the camera's own face detection (no points, only the box) plus `flat_track` for head motion, and no hands; then gate G1 is re-run on the computer with that reduced data (task 1.6 with face box and shift only) before going on.
- [ ] **3.2 Native coder.** Port `keypic.py`, `motion.py`, `patch.py` and the arithmetic coder to C in `jni/edge.c` with a JNI layer in `jni/edgejni.c` and `src/pl/flatraster/Edge.java`. Canny and the bilateral filter written in plain C (no OpenCV). Byte-exact with the Python on the clips: a computer test `tests/edge_test.py` compiles `edge.c` with gcc and compares, as `tests/flat_test.py` does for `flat.c`. Built with the 16 KB page flag, added to `build.sh`.
- [ ] **3.3 Sender.** A setting "Picture coder: Video (as now) / Edge drawing" (default Video until the owner's phone test). With Edge drawing: the camera frames from `CameraSource` go to the landmarker (3.1) and `Edge` on a worker thread; records go to `FrvWriter` version 3. The self-test gets a line: "Edge drawing: N ms for a key picture, N ms for landmarks".
- [ ] **3.4 Receiver.** `Player` draws K and P records (joint bilateral upsampling in C, `jni/edge.c`), M records by the warp, interpolated to 8 a second, with the "moved, not seen" mark (a thin coloured frame border, and the word in the status line) whenever the shown picture is warped. SAVE MP4 draws the same.
- [ ] **3.5 Send it.** Wire the recorder's `Fountain`, `RadioLink` and `libfdv.so` (DATAC1 Fast and DATAC3 Robust only) into FlatRaster as in `PLAN-radio.md` and its 2026-11-14 update: SEND on a recording, LISTEN that receives a `.frv` into the list. Time on air shown before SEND.
- [ ] **3.6 Tests and delivery.** `bash tests/run.sh` (with `edge_test.py` added) passes; the page test covers the new settings; build, raise `versionCode` and version (0.18), APK in `dist/`, README link, the APK to the owner in the chat, `PLAN.md` "Start here" with the phone test list: (1) record C1-like 30 s with Edge drawing and Video, compare sizes and look; (2) a hand holding an object; (3) send phone to phone through the speaker and microphone in Fast; (4) self-test lines.

### Phase 4: on the phone (the owner, then a session)

- [ ] **4.1 Speed and heat.** The owner records 5 min with Edge drawing on; the self-test reports per-frame times. Goal: landmarks plus coder under 20% of one core at 2 pictures a second (measured as time per frame x frames a second); the phone not hot to the hand. If over: landmarks at 2 a second instead of 4, smaller key size.
- [ ] **4.2 Tuning on real recordings.** From the owner's phone `.frv` files (shared to the session): re-run the measures of section 6 on them and tune the patch threshold and the key picture rule only by those measures, one change per commit, each with its figures (section 8).

## 6. What is measured, and the variants

Legibility, each computed on the decoded frames shown to the receiver (at the times they are shown) against the original camera frames:

- **Face found**: share of frames where MediaPipe's face landmarker finds a face in the decoded picture, of those where it finds one in the original.
- **Face error**: mean distance between the face subset points found in the decoded and in the original picture, divided by the distance between the eye centres. (0.05 = 5% of the eye distance.)
- **Mouth r**: correlation between mouth opening (inner-lip distance / eye distance) in the original and in the decoded picture over the clip.
- **Hand found**, **hand error**: as for the face, with the hand landmarker, divided by the hand's wrist-to-middle-knuckle distance.
- **Object visible** (C3 only): edge F1 inside the hand box between Canny of the original and Canny of the decoded picture (both at the coded size), when an object is held.
- **Warped share**: share of shown frames that are warped or interpolated, not real.

Variants, all on every clip; the variant chosen for G1 is the one with the fewest bytes among those that meet G1's legibility condition on the most clips, chosen once:

| Name | Key size | Colour map | Colours | Coder | Motion a second | Key picture rule |
|---|---|---|---|---|---|---|
| E1 | 160x120 | 32x24 | 16 | best of zstd / arithmetic | 2 | start, cut |
| E2 | 160x120 | 32x24 | 16 | best | 4 | start, cut |
| E3 | 128x96 | 32x24 | 8 | best | 2 | start, cut |
| E4 | 96x72 | 16x12 | 8 | best | 2 | start, cut |
| E5 | 160x120 | 32x24 | 16 | best | 2 | start, cut, every 10 s |
| E6 | as E1, no patches | | | | | |
| E7 | as E1, no warp: the receiver holds the last real picture (no invented movement) | | | | | |

E7 matters: if it is as legible as E1 for the mouth and hands, the warp (the part that invents) is not needed.

Bytes are reported per type (K, M, P) and per 30 s; time on air on DATAC1 and DATAC3 is computed from the 60 and 22 bytes a second of section 1 together with the voice at 1.5 kB per 30 s.

## 7. Fixed choices (do not change without a measurement)

- Face subset (MediaPipe face mesh indices): eye corners 33, 133, 362, 263; upper and lower eyelid 159, 145, 386, 374; brows 70, 105, 300, 334; mouth corners 61, 291; inner lips 13, 14; outer lips 0, 17; nose tip 1; chin 152; face sides 234, 454. 22 points. Hands: all 21.
- Fixed border points for the warp: the 4 corners and 4 edge middles of the picture.
- Patch: at most one a second, at most 300 bytes; the threshold is the one parameter tuned in 1.9 (try 10, 20, 30 mean absolute difference in grey inside the box).
- Interpolation for the screen: linear in point positions between motion pictures; never past the last received one.

## 8. Rules for the executing session

From `CLAUDE.md` and `ROADMAP.md` 9.1, made specific:

- Work on the session's branch, push to it and to `main` at the end, as `CLAUDE.md` says. One task per commit at least; the commit message names the task number.
- Do not change a FlatRaster default (picture, sound, file format version written by default), the fixed choices of section 7, the targets of section 1 or the gate rules. Adding a setting whose default is the current behaviour is allowed.
- Every figure written into section 9 comes from `tools/edge/run.sh` or a named test, in the same commit as the code that made it.
- **Stop and write the problem into section 9 and `PLAN.md` "Start here", for a stronger-model session, when:** a gate result is within 10% of its limit; the legibility measures disagree with what the decoded video shows when looked at (e.g. a good score on a clip where the face is plainly wrong); MediaPipe finds a face in fewer than 80% of the original frames of a clip; a round trip is not exact; the spike 3.1 fails; a native library crashes or differs from the Python.
- Nothing invented is shown as real: every warped or interpolated frame is marked, in the player, in SAVE MP4 and in the decoded test videos.
- Faces of real people: the clips stay in this repository only if the owner put them there; never upload them anywhere else.
- `bash tests/run.sh` passes before every push; `tools/edge/run.sh` is not part of it (it needs MediaPipe and the clips), but `tests/edge_test.py` (phase 3) is.
- Before delivering an APK: everything in `CLAUDE.md` "Rules".

## 9. Results

### 2026-11-17, phase 1 (tasks 1.1 to 1.9), Sonnet session. Everything in `tools/edge/`; full tables in `tools/edge/results.md` (made by `tools/edge/table.py`, inputs from `run.sh`'s steps).

**Verdict: G1 NOT passed. STOPPED for a stronger-model session (section 8: the legibility measures cannot be trusted on three of five clips; see "Why this is a stop" below).** Phase 2 and 3 not started.

**Picture bytes per 30 s (K+M+P payloads, no record headers), edge variants against the 2000 B limit:**

| clip | best edge variant | bytes | best fallback (0.17 path, any size, box, coder) | bytes |
|---|---|---|---|---|
| C1 talking, turns head | E6 (no patches) | 3804 | 160x120 face only, x264 | 5188 |
| C2 two presenters (**only 1.2 s of source, looped**) | E6 | 4289 | 160x120 face only, x264 | 2984 |
| C3 desk, hands | E6 | 4116 | 160x120 face only, x264 | 3677 |
| C4 person, still camera | E7 (hold, no motion) | 1371 | 160x120 face only, x264 | 8099 |
| C5 person, moving camera | E7 | 1363 | 160x120 face only, x264 | 10750 |

E1 (the plan's main variant): 8768, 7486, 7959, 2382, 3130 B. Mean over clips: E1 5945, E6 3544, E7 3929. Patches cost 40 to 55% of E1's bytes on C1 to C3 (20 to 29 patches of about 150 to 185 B). The key picture alone is 1.3 to 2.3 kB on real clips (320 to 700 B on the made-up scene). Motion records: 15 to 53 B each at 2 a second, 0.7 to 3.1 kB per 30 s, so even motion alone is near the whole 2.0 kB budget on C2 and C3.

**Gate G1 as the code evaluates it:** E7 passes 2 of 5 clips (C4, C5), no other variant passes any; G1 needs 4. The E7 passes are hollow, see below. Criterion 1 (at most 2000 B) holds only for E7 on C4 and C5, which send one key picture and hold it for 30 s (nothing moves). The closest an edge variant with motion gets: E1 2382 B on C4 (19% over; not within the 10% stop band).

**Why this is a stop (section 8, "the legibility measures disagree with what the decoded video shows"):**
1. MediaPipe's face landmarker finds a face in a posterized or drawn picture in only 0 to 17% of frames on C1 to C3 for *every* coder, including the 0.17 fallback at 320x240 (3 to 17%) and every edge variant (0 to 11%). On those clips face error, mouth r and hand found are not measurable (n/a) or noise. So criteria 2 and 3 compare nothing there; "not worse" passes by default.
2. On C4 and C5 both sides score about 0 on mouth r (edge E1 -0.05 and 0.21; fallback -0.12 to 0.30), so "mouth r not worse" is also true of two pictures in which no mouth can be followed by this detector. The E7 passes on C4 and C5 rest on that: a held key picture, mouth r 0.02, face error 0.31 (C4) and 1.22 (C5), is scored equal to the fallback.
3. What the decoded video shows (E1 on C1, C3): the warp works on the made-up head (mouth and head move, background stays) but on real clips with large pose change (C1: profile to frontal) the thin-plate spline twists the face into a swirl, and patches are pasted as visible rectangles. A good number there would have disagreed with the picture.
What would be needed: a legibility measure that works on drawings (for example landmarks run on the camera frame and a face detector trained or tuned on drawings, or a small user study by the owner), before a verdict on criteria 2 and 3 means anything. Criterion 1 is measurable and is not met with motion: with the plan's design the picture is 2.4 to 9.9 kB on real clips, 1.2 to 5x the budget.

**Observations and departures (each a measurement or a choice, for the next session to confirm):**
- Clips: C1 played forward and back from 15.7 s; C3 from 10.0 s; C4 from 23.9 s; **C2 has only 1.2 s in the file (30 frames at 25/s), so its 30 s is that second repeated 25 times: not a real test of 30 s**; C5 32.9 s, used as is. All five have a face in 98.3 to 100% of frames (MediaPipe on every frame, not every 10th), so none dropped. Hands: C1 52%, C2 100%, C3 97%, C4 0%, C5 0.6% (checked on a picture: real hands). C4 and C5 have no hands, so hand criteria apply only to C1 to C3.
- The made-up clip is **not** "no face" as the plan expected: MediaPipe finds a face in the cartoon head in 100% of frames.
- Constant-speed motion prediction (plan section 3) measured 5 to 12% *worse* than last-position at 2 a second, better at 4 a second; `motion.py` uses last position at 2 a second and constant speed (factor 1/2) at 4. The head pose angles are not sent: the receiver warps from the points, which carry the pose.
- Key coder: arithmetic coder beat zstd 19 on both maps on the made-up clip (edge map 181 against 261 B, colour map 109 against 179 B); "best" picks per map. Palette is 48 B of the key's 56 B header.
- The key picture's landmark set is sent with the key (the warp needs the key's points): 36 to 83 B, counted in K.
- Patch rule compares the warped picture with the drawing a patch of the box would show (both drawings), threshold 10/20/30 as E8/E1/E9: 20 and 10 differ by under 1.5%; 30 sends fewer (C1 15 patches against 27) for 25 to 35% fewer bytes, still over budget. No threshold reaches 2000 B on C1 to C3.
- Defined here because the plan left them open: "legibility not worse" = within 0.05 of r and 5 points of a share; reference fallback = fewest-byte fallback at least as legible as the edge variant minus that tolerance; shown instants are every 2nd camera frame (8 a second), the fallback's picture is held. The fallback's face rectangle for the filter is made from MediaPipe's points (the app uses the camera's detector).
- Fallback sizes per 30 s on real clips (exact / x264 / svt-av1): 160x120 face only 4.1 to 19.8 / 3.0 to 8.1 / 3.9 to 12.2 kB (C4 is the noisiest); **real camera noise makes the 0.17 path 3 to 10x its made-up-scene figures** and no fallback is near 2.0 kB either (best 3.0 kB on C2, which is the looped clip; 3.7 kB on C3). Neither path reaches the target on real clips (section 1's table was from a synthetic head).
- Setup facts: mediapipe 1.2.3 needs `libegl1` and `libgles2` on Linux (apt); opencv-python-headless installed as 5.0.0 (requirements not pinned for it); model SHA-256 recorded in `get_models.sh`. The two outputs of `table.py` take about 25 minutes with 4 processes.
- Tests passing in this phase: `keypic.py`, `motion.py`, `warp.py`, `patch.py`, `legible.py` self-tests (round trips exact, warp 0.00 px on a known shift, 5 px shift read as 5.0 px, identical frames score perfectly). `tests/run.sh` was not run (nothing outside `tools/edge/` changed).
- Warp shows 237 frames, not 240: it never goes past the last received motion picture.

**Tasks:** 1.1 to 1.9 done in the sense that the code exists, runs from `run.sh` and produced the tables. Gate G1: not passed (see above). Task ticks left unticked below pending the stronger session's reading of the measure.
