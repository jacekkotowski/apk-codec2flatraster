package pl.flatraster;

/** Draws the puppet: the key picture faded, and over it the face (eyes, brows, nose, mouth, jaw) and the upper body (shoulders, arms,
 *  hands, hips) from the landmark points. Pure Java, no Android, so it runs in tests. Everything drawn here is the puppet, not the camera:
 *  the picture gets an orange frame so nobody takes it for video of the person. */
final class PuppetDraw {
    /** Points per object: the face subset (marks.FACE_IDX of tools/edge, 22 points of MediaPipe's face mesh) and eight BlazePose points (BODY_IDX). */
    static final int FACE = 30, BODY = 8, CAM = 1, UNITS = 320, CAM_ZERO = 512;   // UNITS: integer coordinate units across the picture width (PuppetEncoder)
    /** BlazePose points kept for the body: shoulders 11, 12, elbows 13, 14, wrists 15, 16, hips 23, 24 (in this order, local numbers 0 to 7). */
    static final int[] BODY_IDX = {11, 12, 13, 14, 15, 16, 23, 24};
    static final int[] SIZES = {FACE, BODY, CAM};   // the third object is one point: the background's shift since the key picture, (CAM_ZERO + sx, CAM_ZERO + sy) in units
    /** Face subset order: 0,1 left eye corners, 2,3 right eye corners, 4,5 left lids (upper, lower), 6,7 right lids, 8,9 left brow, 10,11 right brow,
     *  12,13 mouth corners, 14,15 inner lips (upper, lower), 16,17 outer lips (upper, lower), 18 nose tip, 19 chin, 20,21 face sides,
     *  22,23 outer upper lip left and right of 16, 24,25 outer lower lip left and right of 17, 26,27 inner upper lip left and right of 14, 28,29 inner lower lip left and right of 15. */
    private static final int[][] FACE_LINES = {{0, 4}, {4, 1}, {1, 5}, {5, 0}, {2, 6}, {6, 3}, {3, 7}, {7, 2}, {8, 9}, {10, 11},
            {12, 22}, {22, 16}, {16, 23}, {23, 13}, {13, 25}, {25, 17}, {17, 24}, {24, 12},      // outer lips
            {12, 26}, {26, 14}, {14, 27}, {27, 13}, {13, 29}, {29, 15}, {15, 28}, {28, 12},       // inner lips: the opening
            {20, 19}, {19, 21}, {18, 18}};
    private static final int[][] BODY_LINES = {{0, 1}, {0, 2}, {2, 4}, {1, 3}, {3, 5}, {0, 6}, {1, 7}, {6, 7}};   // shoulders, arms, torso (local numbers)
    static final int ORANGE = 0xffff8c00;

    /** base: the key picture (argb, w x h) or null for a plain light picture. face: 22 (x, y) pairs or null; body: 8 pairs or null; all in
     *  picture pixels. Returns a new picture. */
    /** Which lines are drawn (the Puppet lines setting): 0 none, 1 mouth and eyes, 2 all. The orange frame is always there. */
    static volatile int lines = 1;
    /** Face lines of mode 1: eyes, brows and the lips (indices into FACE_LINES). */
    private static boolean eyesAndMouth(int[] l) { return l[0] != 20 && l[0] != 19 && l[0] != l[1]; }

    static int[] draw(int[] base, int w, int h, float[] face, float[] body) {
        int[] o = new int[w * h];
        for (int i = 0; i < o.length; i++) {
            int p = base == null ? 0xffe0e0e0 : base[i];
            o[i] = 0xff000000 | (p & 0xffffff);                                  // the key picture at full colour: the cut-out left no ghost to hide
        }
        int t = Math.max(1, h / 120);                                   // line width in pixels: 1 at 160 x 120 (the screen enlarges it with the picture)
        int mode = lines;
        if (body != null && mode == 2) {
            for (int[] l : BODY_LINES) line(o, w, h, body[l[0] * 2], body[l[0] * 2 + 1], body[l[1] * 2], body[l[1] * 2 + 1], t, ALPHA_BODY);
            for (int i = 0; i <= 5; i++) dot(o, w, h, body[i * 2], body[i * 2 + 1], t, ALPHA_BODY);
        }
        if (face != null && mode >= 1) {
            for (int[] l : FACE_LINES) {
                if (mode == 1 && !eyesAndMouth(l)) continue;
                if (l[0] == l[1]) dot(o, w, h, face[l[0] * 2], face[l[0] * 2 + 1], t, ALPHA_FACE);
                else line(o, w, h, face[l[0] * 2], face[l[0] * 2 + 1], face[l[1] * 2], face[l[1] * 2 + 1], t, ALPHA_FACE);
            }
        }
        int b = Math.max(2, h / 60);                                  // the frame that says "puppet"
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (x < b || y < b || x >= w - b || y >= h - b) o[y * w + x] = ORANGE;
        return o;
    }

    /** The points of one object (n points from point number `from` of the decoded units) in picture pixels, or null if the object is absent. */
    static float[] unitsToPixels(int[] units, int from, int n, boolean present, int width) {
        if (!present) return null;
        float[] f = new float[n * 2];
        for (int i = 0; i < n * 2; i++) f[i] = units[from * 2 + i] * (width / (float) UNITS);
        return f;
    }

    // ---- the limited warp of the key picture -----------------------------------------------------------------------------------------------

    /** Moves the key picture the way the points moved since the key: a thin-plate spline from the current points back to the points the key had
     *  (tools/edge/warp.py is the research version), and then limited: the movement fades to nothing
     *  further than R2 from the nearest current point (so the background stays still) and is never longer than MAXMOVE of the width (if it would be, the whole movement is scaled down together). Objects missing
     *  in the key's points or the present ones are left out. Colours are taken as they are (nearest pixel), so the flat colours stay flat. */
    static final float R1 = 0.10f, R2 = 0.22f, MAXMOVE = 0.25f;
    static final int GRID = 4;

    static int[] warp(int[] base, int w, int h, float[] refFace, float[] refBody, float[] curFace, float[] curBody) {
        java.util.ArrayList<float[]> cur = new java.util.ArrayList<>(), ref = new java.util.ArrayList<>();
        addPairs(cur, ref, curFace, refFace); addPairs(cur, ref, curBody, refBody);
        headAnchors(cur, ref, refFace, curFace);
        if (cur.size() < 3) return base;
        float sc = w / 160f;                                            // the spline works in units of a 160 pixel wide picture
        int[][] anchors = {};                                           // no fixed points on the border: the spline had to fall from "moved" at the hair to "still" at the edge within a few pixels, overshot, and made a bump over the head; the fade below keeps the background still instead
        int n = cur.size() + anchors.length;
        double[] cx = new double[n], cy = new double[n], rx = new double[n], ry = new double[n];
        for (int i = 0; i < cur.size(); i++) { cx[i] = cur.get(i)[0] / sc; cy[i] = cur.get(i)[1] / sc; rx[i] = ref.get(i)[0] / sc; ry[i] = ref.get(i)[1] / sc; }
        for (int i = 0; i < anchors.length; i++) { int k = cur.size() + i; cx[k] = rx[k] = anchors[i][0] / sc; cy[k] = ry[k] = anchors[i][1] / sc; }
        int m = n + 3;
        double[][] A = new double[m][m + 2];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) A[i][j] = kernel(cx[i] - cx[j], cy[i] - cy[j]);
            A[i][i] += 1.0;                                             // smoothing: landmark jitter must not fold the picture
            A[i][n] = 1; A[i][n + 1] = cx[i]; A[i][n + 2] = cy[i];
            A[n][i] = 1; A[n + 1][i] = cx[i]; A[n + 2][i] = cy[i];
            A[i][m] = rx[i]; A[i][m + 1] = ry[i];
        }
        if (!solve(A, m)) return base;
        int gw = (w + GRID - 1) / GRID + 1, gh = (h + GRID - 1) / GRID + 1;
        float[] ddx = new float[gw * gh], ddy = new float[gw * gh];
        float maxMove = MAXMOVE * w;
        double maxLen = 0;
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            double x = Math.min(w - 1, gx * GRID), y = Math.min(h - 1, gy * GRID), px = x / sc, py = y / sc;
            double sx = A[n][m] + A[n + 1][m] * px + A[n + 2][m] * py, sy = A[n][m + 1] + A[n + 1][m + 1] * px + A[n + 2][m + 1] * py;
            for (int i = 0; i < n; i++) { double u = kernel(px - cx[i], py - cy[i]); sx += A[i][m] * u; sy += A[i][m + 1] * u; }
            double dx = sx * sc - x, dy = sy * sc - y;
            double near = Double.MAX_VALUE;
            for (float[] c : cur) near = Math.min(near, Math.hypot(c[0] - x, c[1] - y));
            double wgt = near <= R1 * w ? 1 : near >= R2 * w ? 0 : (R2 * w - near) / ((R2 - R1) * w);
            dx *= wgt; dy *= wgt;
            maxLen = Math.max(maxLen, Math.hypot(dx, dy));
            ddx[gy * gw + gx] = (float) dx; ddy[gy * gw + gx] = (float) dy;
        }
        if (maxLen > maxMove) {                                         // too much movement: scale the whole field, which keeps its shape (a per-pixel cap made dents)
            float k = (float) (maxMove / maxLen);
            for (int i = 0; i < ddx.length; i++) { ddx[i] *= k; ddy[i] *= k; }
        }
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float fx = (float) x / GRID, fy = (float) y / GRID; int gx = (int) fx, gy = (int) fy; float ax = fx - gx, ay = fy - gy;
            int g00 = gy * gw + gx, g10 = Math.min(gx + 1, gw - 1) + gy * gw, g01 = gx + Math.min(gy + 1, gh - 1) * gw, g11 = Math.min(gx + 1, gw - 1) + Math.min(gy + 1, gh - 1) * gw;
            float dx = (1 - ay) * ((1 - ax) * ddx[g00] + ax * ddx[g10]) + ay * ((1 - ax) * ddx[g01] + ax * ddx[g11]);
            float dy = (1 - ay) * ((1 - ax) * ddy[g00] + ax * ddy[g10]) + ay * ((1 - ax) * ddy[g01] + ax * ddy[g11]);
            int sx = Math.max(0, Math.min(w - 1, Math.round(x + dx))), sy = Math.max(0, Math.min(h - 1, Math.round(y + dy)));
            o[y * w + x] = base[sy * w + sx];
        }
        return o;
    }

    /** Nothing in the face points holds the forehead and the hair, so a spline alone bends them (the owner saw the top of the face go concave, and a heart
     *  shape when he raised his head). Anchors above the face, placed in the key's face and carried to the present by the affine map (shift, turn, scale,
     *  and the foreshortening of a nod) that best maps the key's face points onto the present ones: the top of the head then moves as one piece with the
     *  face, and shrinks with it when the head tilts back. */
    private static void headAnchors(java.util.List<float[]> cur, java.util.List<float[]> ref, float[] rf, float[] cf) {
        if (rf == null || cf == null || rf.length != cf.length || rf.length < 44) return;
        int n = rf.length / 2;
        double sxx = 0, sxy = 0, syy = 0, sx = 0, sy = 0, bx0 = 0, bx1 = 0, bx2 = 0, by0 = 0, by1 = 0, by2 = 0;     // normal equations of c = m0 x + m1 y + m2
        for (int i = 0; i < n; i++) {
            double x = rf[2 * i], y = rf[2 * i + 1], cx = cf[2 * i], cy = cf[2 * i + 1];
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y;
            bx0 += x * cx; bx1 += y * cx; bx2 += cx; by0 += x * cy; by1 += y * cy; by2 += cy;
        }
        double[][] N = {{sxx, sxy, sx, bx0, by0}, {sxy, syy, sy, bx1, by1}, {sx, sy, n, bx2, by2}};
        for (int c = 0; c < 3; c++) {                                    // Gauss elimination, two right-hand sides
            int p = c; for (int r = c + 1; r < 3; r++) if (Math.abs(N[r][c]) > Math.abs(N[p][c])) p = r;
            if (Math.abs(N[p][c]) < 1e-9) return;
            double[] t = N[c]; N[c] = N[p]; N[p] = t;
            for (int r = 0; r < 3; r++) if (r != c) { double f = N[r][c] / N[c][c]; for (int k = c; k < 5; k++) N[r][k] -= f * N[c][k]; }
        }
        double[] mx = {N[0][3] / N[0][0], N[1][3] / N[1][1], N[2][3] / N[2][2]}, my = {N[0][4] / N[0][0], N[1][4] / N[1][1], N[2][4] / N[2][2]};
        double bx = (rf[16] + rf[18] + rf[20] + rf[22]) / 4, by = (rf[17] + rf[19] + rf[21] + rf[23]) / 4;     // between the brows (subset points 8 to 11)
        double ux = bx - rf[38], uy = by - rf[39];                       // from the chin (19) up to the brows
        double[][] v = {{bx + 0.5 * ux, by + 0.5 * uy}, {bx + 1.1 * ux, by + 1.1 * uy},
                {rf[40] + 0.5 * ux, rf[41] + 0.5 * uy}, {rf[42] + 0.5 * ux, rf[43] + 0.5 * uy}};              // and above the two sides of the face (20, 21)
        for (double[] p : v) {
            cur.add(new float[]{(float) (mx[0] * p[0] + mx[1] * p[1] + mx[2]), (float) (my[0] * p[0] + my[1] * p[1] + my[2])});
            ref.add(new float[]{(float) p[0], (float) p[1]});
        }
    }

    // ---- background shift, lip gain --------------------------------------------------------------------------------------------------------

    /** The picture moved by (sx, sy) pixels (content at (x, y) appears at (x + sx, y + sy)); the edges repeat. */
    static int[] shift(int[] base, int w, int h, float sx, float sy) {
        if (Math.abs(sx) < 0.05f && Math.abs(sy) < 0.05f) return base;
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) o[y * w + x] = base[Math.max(0, Math.min(h - 1, Math.round(y - sy))) * w + Math.max(0, Math.min(w - 1, Math.round(x - sx)))];
        return o;
    }

    /** Lip points that are exaggerated (face subset numbers: mouth corners 12, 13, inner lips 14, 15, outer lips 16, 17, extra lip points 22 to 29). */
    static final int[] LIPS = {12, 13, 14, 15, 16, 17, 22, 23, 24, 25, 26, 27, 28, 29};
    /** How much the mouth movement is exaggerated when playing (the Lip movement setting): 1 as recorded. The movement is real but small at 160 x 120. */
    static volatile float lipGain = 1.6f;

    /** The face points with the lips' movement since the key picture multiplied: up and down by `gain`, sideways by half as much extra. */
    static float[] lipsStronger(float[] ref, float[] cur, float gain) {
        if (cur == null || ref == null || ref.length != cur.length || gain == 1f) return cur;
        float[] o = cur.clone();
        float gx = 1 + 0.5f * (gain - 1);
        for (int i : LIPS) { o[2 * i] = ref[2 * i] + gx * (cur[2 * i] - ref[2 * i]); o[2 * i + 1] = ref[2 * i + 1] + gain * (cur[2 * i + 1] - ref[2 * i + 1]); }
        return o;
    }

    private static void addPairs(java.util.List<float[]> cur, java.util.List<float[]> ref, float[] c, float[] r) {
        if (c == null || r == null || c.length != r.length) return;
        for (int i = 0; i < c.length; i += 2) { cur.add(new float[]{c[i], c[i + 1]}); ref.add(new float[]{r[i], r[i + 1]}); }
    }

    private static double kernel(double dx, double dy) { double r2 = dx * dx + dy * dy; return r2 > 1e-12 ? 0.5 * r2 * Math.log(r2) : 0; }

    /** Gauss elimination with partial pivoting on the m x (m + 2) system A (two right-hand sides in the last columns); the solution replaces them. */
    private static boolean solve(double[][] A, int m) {
        for (int c = 0; c < m; c++) {
            int p = c; for (int r = c + 1; r < m; r++) if (Math.abs(A[r][c]) > Math.abs(A[p][c])) p = r;
            if (Math.abs(A[p][c]) < 1e-12) return false;
            double[] t = A[c]; A[c] = A[p]; A[p] = t;
            for (int r = 0; r < m; r++) if (r != c) { double f = A[r][c] / A[c][c]; if (f != 0) for (int k = c; k < m + 2; k++) A[r][k] -= f * A[c][k]; }
        }
        for (int r = 0; r < m; r++) { A[r][m] /= A[r][r]; A[r][m + 1] /= A[r][r]; }
        return true;
    }

    /** Lines are thin, semi-transparent and contrast-adaptive, as landmark overlays usually are: where the picture underneath is light the ink is dark,
     *  where it is dark the ink is light, mixed in at ALPHA, so the line shows on any skin, hair or wall colour without hiding what is under it. */
    static final float ALPHA_FACE = 0.62f, ALPHA_BODY = 0.5f;

    private static void dot(int[] o, int w, int h, float x, float y, int r, float alpha) {
        int cx = Math.round(x), cy = Math.round(y);
        for (int dy = -r / 2; dy <= r / 2; dy++) for (int dx = -r / 2; dx <= r / 2; dx++) put(o, w, h, cx + dx, cy + dy, alpha);
    }

    private static void line(int[] o, int w, int h, float x0, float y0, float x1, float y1, int t, float alpha) {
        float dx = x1 - x0, dy = y1 - y0;
        int n = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
        for (int i = 0; i <= n; i++) dot(o, w, h, x0 + dx * i / n, y0 + dy * i / n, t, alpha);
    }

    private static void put(int[] o, int w, int h, int x, int y, float alpha) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int p = o[y * w + x], r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
        int ink = (r * 299 + g * 587 + b * 114) / 1000 > 140 ? 0x18 : 0xf4;                 // dark on light, light on dark
        o[y * w + x] = 0xff000000 | (int) (r + (ink - r) * alpha) << 16 | (int) (g + (ink - g) * alpha) << 8 | (int) (b + (ink - b) * alpha);
    }
}
