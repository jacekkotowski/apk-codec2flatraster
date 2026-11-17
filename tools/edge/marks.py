#!/usr/bin/env python3
"""PLAN-edge.md task 1.3: MediaPipe face and hand landmarks for every frame of a clip.

`python3 marks.py [name ...]` writes out/<name>.marks.npz with, per frame f (pixel units of the 640x480 frame):
  face      bool[f]         a face was found
  pose      float32[f,6]    yaw, pitch, roll (degrees, from the facial transformation matrix), centre x, centre y, scale
                            (centre/scale: mean and RMS spread of the 22 subset points, in pixels)
  fpts      float32[f,22,2] the face subset of PLAN-edge.md section 7 (zeros when no face)
  hand      bool[f,2]       hand 0/1 found (hands in the order MediaPipe gives them; 0 = left-most by wrist x so the order is stable)
  hpts      float32[f,2,21,2]
Also `detect(frames)`, the same detection on any list of RGB frames (used by legible.py), `FACE_IDX`.
Running mode VIDEO, time stamps from the frame number at 16 a second."""
import os, sys
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "out", "models")
# PLAN-edge.md section 7 (fixed): eye corners, eyelids, brows, mouth corners, inner lips, outer lips, nose tip, chin, sides
FACE_IDX = [33, 133, 362, 263, 159, 145, 386, 374, 70, 105, 300, 334, 61, 291, 13, 14, 0, 17, 1, 152, 234, 454]
EYE_L, EYE_R = (33, 133), (362, 263)   # indices into the full mesh; used with FACE_IDX positions below
POS = {i: k for k, i in enumerate(FACE_IDX)}
FPS = 16


def _euler(m):
    """yaw, pitch, roll in degrees from the 4x4 facial transformation matrix (rotation part)."""
    R = np.array(m, np.float64).reshape(4, 4)[:3, :3]
    R = R / np.linalg.norm(R, axis=0)
    sy = np.hypot(R[0, 0], R[1, 0])
    pitch = np.degrees(np.arctan2(R[2, 1], R[2, 2]))
    yaw = np.degrees(np.arctan2(-R[2, 0], sy))
    roll = np.degrees(np.arctan2(R[1, 0], R[0, 0]))
    return yaw, pitch, roll


def make_landmarkers():
    face = vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
        base_options=mpp.BaseOptions(model_asset_path=os.path.join(MODELS, "face_landmarker.task")),
        running_mode=vision.RunningMode.VIDEO, num_faces=1, output_facial_transformation_matrixes=True))
    hand = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=mpp.BaseOptions(model_asset_path=os.path.join(MODELS, "hand_landmarker.task")),
        running_mode=vision.RunningMode.VIDEO, num_hands=2))
    return face, hand


def detect(frames, step=1, every=None):
    """Landmarks of the frames (iterable of RGB uint8). Returns the dict of arrays described above, one row per frame given.
    `every`: only for logging."""
    face, hand = make_landmarkers()
    F, P, FP, HD, HP = [], [], [], [], []
    for k, rgb in enumerate(frames):
        h, w = rgb.shape[:2]
        img = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))
        ts = int(k * step * 1000 / FPS)
        fr = face.detect_for_video(img, ts)
        hr = hand.detect_for_video(img, ts)
        fpts = np.zeros((22, 2), np.float32); pose = np.zeros(6, np.float32); ok = False
        if fr.face_landmarks:
            lm = fr.face_landmarks[0]
            fpts = np.array([[lm[i].x * w, lm[i].y * h] for i in FACE_IDX], np.float32)
            c = fpts.mean(0)
            yaw, pitch, roll = _euler(fr.facial_transformation_matrixes[0]) if fr.facial_transformation_matrixes else (0, 0, 0)
            pose = np.array([yaw, pitch, roll, c[0], c[1], np.sqrt(((fpts - c) ** 2).sum(1).mean())], np.float32)
            ok = True
        hp = np.zeros((2, 21, 2), np.float32); hd = np.zeros(2, bool)
        hands = sorted(((np.array([[p.x * w, p.y * h] for p in hl], np.float32)) for hl in hr.hand_landmarks), key=lambda a: a[0, 0])
        for j, a in enumerate(hands[:2]):
            hp[j] = a; hd[j] = True
        F.append(ok); P.append(pose); FP.append(fpts); HD.append(hd); HP.append(hp)
    face.close(); hand.close()
    return dict(face=np.array(F), pose=np.array(P), fpts=np.array(FP), hand=np.array(HD), hpts=np.array(HP))


def main():
    sys.path.insert(0, HERE)
    import clips
    names = sys.argv[1:] or [n for n in list(clips.SOURCES) + ["made"] if os.path.exists(os.path.join(clips.OUT, n + ".rgb"))]
    print(f"{'clip':6s} {'frames':>6s} {'face %':>7s} {'hand %':>7s} {'two hands %':>12s}")
    for n in names:
        fr = clips.load(n)
        d = detect(fr)
        np.savez_compressed(os.path.join(clips.OUT, n + ".marks.npz"), **d)
        print(f"{n:6s} {len(fr):6d} {100 * d['face'].mean():7.1f} {100 * d['hand'].any(1).mean():7.1f} {100 * d['hand'].all(1).mean():12.1f}", flush=True)


if __name__ == "__main__":
    main()
