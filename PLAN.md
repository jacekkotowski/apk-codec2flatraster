# FlatRaster plan

Last updated: 2026-11-11

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
| Exposure and white balance lock while recording | Built, not tested anywhere |
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

## Radio

Idea: the .frv packets are already separately checkable, so a modem can carry them. Open: which modem (HamDRM as in the Codec2 Recorder plan would bring GPL-3.0), error correction, how often to repeat full pictures.

## Files to keep

`flatraster.keystore` and `signing.env`, in the private `apk-signing-keys` repository, folder `apk-flatraster`. Without them, later versions can't install over the current one.
