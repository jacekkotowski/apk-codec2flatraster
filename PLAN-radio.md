# FlatRaster over radio: plan for a new session

Written 2026-11-11, at FlatRaster 0.12. Nothing in this file is built yet. Speeds and sizes marked "from memory" are not checked and must be measured or looked up first.

## Goal

Send a FlatRaster recording (posterized video + Codec 2 voice, `.frv`) from one phone to another through an HF SSB radio, as a file. Live video is a later question (see "Live", below).

## Where the project stands

- App: `pl.flatraster`, hand-built APK without Gradle (`bash build.sh`), rules in `CLAUDE.md`, status in `PLAN.md`.
- `.frv` format: 14-byte header, then packets `0xF5 0x7A, type, time ms (4), length (3), payload, CRC16`. Types: setup, key picture, delta picture, audio. Each packet checks itself; `FrvReader` skips damaged ones. See `src/pl/flatraster/FrvWriter.java`, tested by `tests/frv_test.sh`.
- A typical stream, measured on synthetic clips only: video about 2 to 3.5 kbps at 320x240 and 3 pictures/s (AV1), voice 1200 bit/s plus about 100 bit/s packet overhead. So a 30 s recording is roughly 12 to 18 kB.
- Tests on a computer: `bash tests/run.sh`.

## Correction to carry over

In the chat I said the repo's Codec 2 copy "includes three data modems". That was wrong. `third_party/codec2/src` holds the 17 voice-codec `.c` files and all headers (including `freedv_api.h`, which names the modes DATAC0, DATAC1, DATAC3), but **not** the modem sources (`freedv_api.c`, `ofdm.c`, the LDPC and FSK files and others). The copy comes from tschudin/Codec2Recorder, Codec 2 version 1.0. The modem code must be fetched from upstream Codec 2 (LGPL 2.1, same licence as now).

## Two modem candidates

| | FreeDV data modes (Codec 2 project) | HamDRM (as in QSSTV, EasyPal) |
|---|---|---|
| Licence | LGPL 2.1, same as the Codec 2 code already used | QSSTV is GPL-3.0: using its code makes the app GPL-3.0 |
| Net speed, from memory | DATAC1 about 1 kbps, DATAC3 about 300 bit/s, DATAC0 about 300 bit/s (short packets) | about 1 kbps robust, up to 4 or 5 kbps on a clean path |
| Built for | poor HF channels, with LDPC error correction | picture and file transfer on HF, resend of bad segments |
| Other side | another FlatRaster, or the FreeDV tools on a PC | QSSTV or EasyPal on a PC: many hams have it |
| Code source | github.com/drowe67/codec2 | QSSTV source |

Recommendation: FreeDV data modes first (no licence change, C code that fits this build). HamDRM only if talking to existing QSSTV/EasyPal stations matters more than the licence.

Both need the owner's decision before code goes in: see "Decisions for the owner".

## Step 0: things to settle before any code

1. Network: the cloud environment `apk-codec2-recorder` allows `dl.google.com` only. Fetching upstream Codec 2 needs GitHub access to `drowe67/codec2` (add the repo to the session read-only, or allow the host). Record the exact commit used.
2. Check that upstream at that commit still builds the voice codec the app uses with identical output (`tests` in apk-codec2-recorder had a Codec 2 round-trip test; port one here). If upstream voice differs from version 1.0, keep the current voice files and add only modem files.
3. Read upstream `README_data.md` and `freedv_api.h` for the real numbers: payload bytes per frame, frame duration, bandwidth and required SNR for each DATAC mode. Replace every "from memory" number in this file.
4. The phone's Android SDK/NDK are not in a fresh container: install as before (cmdline-tools, build-tools 36.0.0, platform 36, NDK r28c under `$ANDROID_SDK_ROOT/android-ndk-r28c`). The signing key is in the private `apk-signing-keys` repo, folder `apk-flatraster`.

## Step 1: measure on a computer, no phone, no radio

Build upstream Codec 2 for the desktop and use its own tools (`freedv_data_raw_tx`, `freedv_data_raw_rx`, and the channel simulator `ch`) to answer:

- How long does a real 30 s `.frv` take to send in each mode? (file bytes / payload rate, plus preamble and frame overhead)
- How many frames are lost at several signal-to-noise levels, on a plain noisy channel and on the simulated fading HF channels?
- What does a received file look like with those losses: how many `.frv` packets survive, does the video still play, how much voice is missing?

Deliverable: a table in `PLAN.md` (mode, seconds to send 30 s of recording, loss at each channel) and a script `tools/radio_bench.py` that repeats it. Use a recording from the owner's phone, not only synthetic clips.

Stop and report after this step. If even DATAC1 needs many minutes per 30 s recording, the stream has to shrink first (Step 2) or the goal changes.

## Step 2: shape the stream for radio

Only what Step 1 shows is needed. Candidates, cheapest first:

- A "Radio" preset: 160x120, 1 picture/s or less, 4 colours, voice 700C, long full-picture interval. Measure its bitrate on a phone clip.
- Shorter packet header for radio transport (12 bytes per packet now). Keep reading old files.
- Packets sized to the modem frame: one `.frv` packet may span several modem frames; a lost modem frame should cost one packet, not the rest of the file. Either split large packets (key pictures) into numbered fragments, or add a transport layer below `.frv` (Step 3).
- Full pictures: a lost delta spoils the picture until the next full one. For file transfer with resend this does not matter; for one-way sending, repeat full pictures more often or send them twice.

## Step 3: transport design (file transfer)

Design before coding; write it into this file.

- Fragment the file into modem-frame payloads: `file id, fragment number, total, bytes`, each with the modem's own CRC.
- One-way mode (broadcast): send all fragments, optionally twice or with extra parity fragments. Receiver assembles what it got; `FrvReader` already tolerates holes.
- Two-way mode (later): receiver answers with the list of missing fragments, sender resends only those (the idea HamDRM calls bad segment report). Needs transmit/receive turnaround handling.
- Start of transmission: a short header frame repeated a few times (file id, size, fragment count, name).
- Decide: fixed mode chosen by the user, or a robust header frame that names the mode of the rest.

## Step 4: modem in the app

- Add the needed upstream modem sources under `third_party/codec2` (list them in its README with the commit), build into `libc2.so` or a new `libmodem.so` in `build.sh`, 16 KB page flag as now. Mind binary size and that some upstream files expect CMake-generated headers (as `generated/` does for the codec).
- JNI: open mode, bytes in -> audio samples out (transmit); audio samples in -> bytes out with sync and SNR readings (receive).
- Desktop test first: transmit a file to a WAV with the app's own JNI-free C wrapper, pass it through the `ch` simulator, receive it, compare. Add to `tests/run.sh`.
- Modem sample rate is 8 kHz (to confirm); Android audio at 48 kHz needs resampling both ways (`Resampler.java` exists in apk-codec2-recorder, not copied here yet).

## Step 5: audio path to the radio

- Transmit: `AudioTrack` to the headset/USB output. Level control and a test tone; no audio effects. Android may apply processing on some routes: use the plainest stream type and check with a loopback cable.
- Receive: `AudioRecord` with source UNPROCESSED or VOICE_RECOGNITION (least processing; to be tested), from a wired or USB audio input.
- Keying the transmitter: VOX in the radio first (nothing to build). Later options: a USB serial RTS/DTR line or CAT command; Android USB host code needed.
- Receive screen: sync indicator, SNR, fragments received/missing, save result as `.frv` and play it.
- Transmit screen: pick a recording, pick mode, show time needed, start/stop.
- Acoustic test without a radio: phone speaker to another phone's microphone is a poor channel but a quick first check; a cable between two phones is the fair test.

## Step 6: on-air test (owner)

Needs the owner's licence, radio and a second station. Checklist to prepare: frequency and mode chosen within the band plan, bandwidth of the chosen mode stated, station identification (most rules require call sign identification in plain form: plan a voice or CW ID, or check what the local rules accept for data), power and duty cycle (OFDM has high peaks: reduce drive, watch ALC).

## Live video and voice (later, not planned in detail)

At about 1 kbps total the stream would have to be about 160x120, under 1 picture/s, 4 colours, voice 700 bit/s, with full pictures repeated. Decide after Step 1 and 2 numbers exist. The neural "puppet" stream in `PLAN.md` is the other route to such bitrates.

## Decisions for the owner

1. Modem: FreeDV data modes (recommended) or HamDRM with a move to GPL-3.0?
2. First target: one-way file sending, or two-way with resend?
3. How the phone connects to the radio (cable/interface, VOX or serial keying), and which radio.
4. Whether talking to existing PC software (QSSTV, EasyPal, FreeDV tools) is required or two FlatRaster phones are enough.

## What can and cannot be tested in a cloud session

- Can: Steps 1 to 4 on a computer, including simulated HF channels and building the APK.
- Cannot: anything with real audio hardware, a phone, or a radio. The emulator in the container has no hardware acceleration and was too slow to be useful (it crashed in the WebView under load).

## Prompt to start the new session

"Read `CLAUDE.md`, `PLAN.md` and `PLAN-radio.md` in apk-codec2flatraster. Do Step 0 and Step 1 of `PLAN-radio.md`: get upstream Codec 2, replace the from-memory numbers with real ones, and measure how long a recording takes to send in each FreeDV data mode and how it survives simulated HF channels. Report before going on to Step 2."
