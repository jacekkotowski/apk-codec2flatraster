#!/usr/bin/env python3
"""PLAN-edge.md task 1.5 (first part): the thin-plate-spline warp of the receiver.

render_motion(key_render, key_pts, cur_pts) -> rgb: `key_render` (the drawing, oh x ow x 3) is moved so that every point of key_pts
lands on the matching point of cur_pts. Points are in pixels of the rendered picture (coded pixels * scale). Control points: the
points of every object present in BOTH sets, and 8 fixed points on the border (4 corners, 4 middles) that map to themselves, so
the background stays put. An object missing in either set gives no control points: what the key picture shows stays as it was
(patches, patch.py, are what corrects that).
The spline is fitted backwards (current position -> position in the key picture), evaluated on a grid of every 8 pixels and enlarged
bilinearly, then cv2.remap. A small smoothing term (lam, in coded pixels squared) keeps landmark jitter from making wild folds.
Everything shown with this is marked "moved, not seen" by the player (codec.py draws the coloured border).
Run this file: a made-up shift must move a marked point to within 1 pixel."""
import os, sys
import numpy as np
import cv2

LAM = 1.0
GRID = 8


def border_points(w, h):
    xs, ys = (0, w // 2, w - 1), (0, h // 2, h - 1)
    return np.array([(x, y) for y in ys for x in xs if not (x == w // 2 and y == h // 2)], np.float64)


def _U(r2):
    return np.where(r2 > 0, 0.5 * r2 * np.log(np.maximum(r2, 1e-12)), 0.0)      # r^2 log r with r^2 as input


def tps_backward(cur, key, shape, scale=4.0, lam=LAM):
    """Spline from `cur` points to `key` points (both [n,2] pixels). Returns (mapx, mapy) float32 [h,w] for the whole picture."""
    h, w = shape
    s = float(scale)
    c, k = cur / s, key / s
    n = len(c)
    d2 = ((c[:, None, :] - c[None, :, :]) ** 2).sum(2)
    K = _U(d2) + lam * np.eye(n)
    P = np.hstack([np.ones((n, 1)), c])
    A = np.zeros((n + 3, n + 3))
    A[:n, :n], A[:n, n:], A[n:, :n] = K, P, P.T
    rhs = np.zeros((n + 3, 2))
    rhs[:n] = k
    sol = np.linalg.solve(A + 1e-9 * np.eye(n + 3), rhs)
    gx = np.arange(0, w + GRID, GRID, dtype=np.float64)
    gy = np.arange(0, h + GRID, GRID, dtype=np.float64)
    G = np.stack(np.meshgrid(gx, gy), -1).reshape(-1, 2) / s
    Ud = _U(((G[:, None, :] - c[None, :, :]) ** 2).sum(2))
    out = Ud @ sol[:n] + np.hstack([np.ones((len(G), 1)), G]) @ sol[n:]
    out = (out * s).reshape(len(gy), len(gx), 2).astype(np.float32)
    big = cv2.resize(out, (len(gx) * GRID, len(gy) * GRID), interpolation=cv2.INTER_LINEAR)
    # bilinear enlargement puts samples at pixel centres; shift so that grid point i sits at pixel i*GRID
    big = cv2.warpAffine(big, np.float32([[1, 0, 0.5 - GRID / 2], [0, 1, 0.5 - GRID / 2]]), (big.shape[1], big.shape[0]), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    big = big[:h, :w]
    return big[..., 0], big[..., 1]


def control_points(key_pts, cur_pts, shape, scale=4.0):
    """key_pts, cur_pts: lists of 3 (None or [n,2] coded pixels). Returns (key, cur) control points in render pixels."""
    h, w = shape
    ks, cs = [], []
    for a, b in zip(key_pts, cur_pts):
        if a is not None and b is not None:
            ks.append(np.asarray(a, np.float64) * scale)
            cs.append(np.asarray(b, np.float64) * scale)
    bp = border_points(w, h)
    return np.vstack(ks + [bp]), np.vstack(cs + [bp])


def render_motion(key_render, key_pts, cur_pts, scale=4.0, lam=LAM):
    h, w = key_render.shape[:2]
    kp, cp = control_points(key_pts, cur_pts, (h, w), scale)
    mx, my = tps_backward(cp, kp, (h, w), scale, lam)
    return cv2.remap(key_render, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def _selftest():
    h, w = 480, 640
    rng = np.random.default_rng(1)
    key = np.zeros((h, w, 3), np.uint8)
    pts = np.stack([rng.uniform(250, 390, 22), rng.uniform(150, 330, 22)], 1) / 4        # a face in coded pixels
    mark = np.array([[320.0, 240.0]])                                                    # a marked spot, in render pixels
    cv2.circle(key, (320, 240), 6, (255, 255, 255), -1)
    worst = 0
    for sh in ((10, 0), (0, -14), (20, 12), (-8, 5)):
        cur = pts + np.array(sh) / 4
        out = render_motion(key, [pts, None, None], [cur, None, None])
        # where does the marked spot go? it sits inside the face: by a pure shift it moves by sh
        ys, xs = np.nonzero(out[..., 0] > 128)
        c = np.array([xs.mean(), ys.mean()])
        err = np.abs(c - (mark[0] + np.array(sh))).max()
        worst = max(worst, err)
        print(f"shift {sh}: spot at ({c[0]:.1f}, {c[1]:.1f}), expected ({320 + sh[0]}, {240 + sh[1]}), off by {err:.2f} px")
    assert worst <= 1.0, "warp moves a marked point by more than 1 pixel off"
    # the background does not move: a spot near the border stays
    key2 = np.zeros((h, w, 3), np.uint8); cv2.circle(key2, (30, 30), 5, (255, 255, 255), -1)
    out = render_motion(key2, [pts, None, None], [pts + np.array([6, 3]) / 4, None, None])
    ys, xs = np.nonzero(out[..., 0] > 128)
    print(f"background spot near the corner moves by ({xs.mean() - 30:.2f}, {ys.mean() - 30:.2f}) px")
    print("ok")


if __name__ == "__main__":
    _selftest()
