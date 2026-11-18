package pl.flatraster;

/** Draws the puppet: the key picture faded, and over it the face (eyes, brows, nose, mouth, jaw) and the upper body (shoulders, arms,
 *  hands, hips) from the landmark points. Pure Java, no Android, so it runs in tests. Everything drawn here is the puppet, not the camera:
 *  the picture gets an orange frame so nobody takes it for video of the person. */
final class PuppetDraw {
    /** Points per object: the face subset (marks.FACE_IDX of tools/edge, 22 points of MediaPipe's face mesh) and eight BlazePose points (BODY_IDX). */
    static final int FACE = 22, BODY = 8, UNITS = 160;   // UNITS: integer coordinate units across the picture width (PuppetEncoder)
    /** BlazePose points kept for the body: shoulders 11, 12, elbows 13, 14, wrists 15, 16, hips 23, 24 (in this order, local numbers 0 to 7). */
    static final int[] BODY_IDX = {11, 12, 13, 14, 15, 16, 23, 24};
    static final int[] SIZES = {FACE, BODY};
    /** Face subset order: 0,1 left eye corners, 2,3 right eye corners, 4,5 left lids (upper, lower), 6,7 right lids, 8,9 left brow, 10,11 right brow,
     *  12,13 mouth corners, 14,15 inner lips (upper, lower), 16,17 outer lips (upper, lower), 18 nose tip, 19 chin, 20,21 face sides. */
    private static final int[][] FACE_LINES = {{0, 4}, {4, 1}, {1, 5}, {5, 0}, {2, 6}, {6, 3}, {3, 7}, {7, 2}, {8, 9}, {10, 11},
            {12, 16}, {16, 13}, {13, 17}, {17, 12}, {14, 15}, {20, 19}, {19, 21}, {18, 18}};
    private static final int[][] BODY_LINES = {{0, 1}, {0, 2}, {2, 4}, {1, 3}, {3, 5}, {0, 6}, {1, 7}, {6, 7}};   // shoulders, arms, torso (local numbers)
    static final int WHITE = 0xffffffff, ORANGE = 0xffff8c00, INK = 0xff101010, BODYC = 0xff1060d0;

    /** base: the key picture (argb, w x h) or null for a plain light picture. face: 22 (x, y) pairs or null; body: 8 pairs or null; all in
     *  picture pixels. Returns a new picture. */
    static int[] draw(int[] base, int w, int h, float[] face, float[] body) {
        int[] o = new int[w * h];
        for (int i = 0; i < o.length; i++) {
            int p = base == null ? 0xffe0e0e0 : base[i];
            o[i] = 0xff000000 | (p & 0xffffff);                                  // the key picture at full colour: the cut-out left no ghost to hide
        }
        int t = Math.max(1, h / 70);
        if (body != null) {
            for (int[] l : BODY_LINES) line(o, w, h, body[l[0] * 2], body[l[0] * 2 + 1], body[l[1] * 2], body[l[1] * 2 + 1], t + 3, WHITE);
            for (int[] l : BODY_LINES) line(o, w, h, body[l[0] * 2], body[l[0] * 2 + 1], body[l[1] * 2], body[l[1] * 2 + 1], t + 1, BODYC);
            for (int i = 0; i <= 5; i++) dot(o, w, h, body[i * 2], body[i * 2 + 1], t + 3, WHITE);
            for (int i = 0; i <= 5; i++) dot(o, w, h, body[i * 2], body[i * 2 + 1], t + 2, BODYC);
        }
        if (face != null) {
            for (int[] l : FACE_LINES) {                                      // a white outline under the ink so the lines show on dark and light colours
                if (l[0] == l[1]) dot(o, w, h, face[l[0] * 2], face[l[0] * 2 + 1], t + 3, WHITE);
                else line(o, w, h, face[l[0] * 2], face[l[0] * 2 + 1], face[l[1] * 2], face[l[1] * 2 + 1], t + 2, WHITE);
            }
            for (int[] l : FACE_LINES) {
                if (l[0] == l[1]) dot(o, w, h, face[l[0] * 2], face[l[0] * 2 + 1], t + 1, INK);
                else line(o, w, h, face[l[0] * 2], face[l[0] * 2 + 1], face[l[1] * 2], face[l[1] * 2 + 1], t, INK);
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
     *  (tools/edge/warp.py is the research version), fixed at eight points on the picture border, and then limited: the movement fades to nothing
     *  further than R2 from the nearest current point (so the background stays still) and is never longer than MAXMOVE of the width. Objects missing
     *  in the key's points or the present ones are left out. Colours are taken as they are (nearest pixel), so the flat colours stay flat. */
    static final float R1 = 0.10f, R2 = 0.22f, MAXMOVE = 0.15f;
    static final int GRID = 4;

    static int[] warp(int[] base, int w, int h, float[] refFace, float[] refBody, float[] curFace, float[] curBody) {
        java.util.ArrayList<float[]> cur = new java.util.ArrayList<>(), ref = new java.util.ArrayList<>();
        addPairs(cur, ref, curFace, refFace); addPairs(cur, ref, curBody, refBody);
        if (cur.size() < 3) return base;
        float sc = w / 160f;                                            // the spline works in units of a 160 pixel wide picture
        int[][] anchors = {{0, 0}, {w / 2, 0}, {w - 1, 0}, {0, h / 2}, {w - 1, h / 2}, {0, h - 1}, {w / 2, h - 1}, {w - 1, h - 1}};
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
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            double x = Math.min(w - 1, gx * GRID), y = Math.min(h - 1, gy * GRID), px = x / sc, py = y / sc;
            double sx = A[n][m] + A[n + 1][m] * px + A[n + 2][m] * py, sy = A[n][m + 1] + A[n + 1][m + 1] * px + A[n + 2][m + 1] * py;
            for (int i = 0; i < n; i++) { double u = kernel(px - cx[i], py - cy[i]); sx += A[i][m] * u; sy += A[i][m + 1] * u; }
            double dx = sx * sc - x, dy = sy * sc - y;
            double near = Double.MAX_VALUE;
            for (float[] c : cur) near = Math.min(near, Math.hypot(c[0] - x, c[1] - y));
            double wgt = near <= R1 * w ? 1 : near >= R2 * w ? 0 : (R2 * w - near) / ((R2 - R1) * w);
            dx *= wgt; dy *= wgt;
            double len = Math.hypot(dx, dy);
            if (len > maxMove) { dx *= maxMove / len; dy *= maxMove / len; }
            ddx[gy * gw + gx] = (float) dx; ddy[gy * gw + gx] = (float) dy;
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

    private static void dot(int[] o, int w, int h, float x, float y, int r, int c) {
        int cx = Math.round(x), cy = Math.round(y);
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) if (dx * dx + dy * dy <= r * r) put(o, w, h, cx + dx, cy + dy, c);
    }

    private static void line(int[] o, int w, int h, float x0, float y0, float x1, float y1, int t, int c) {
        float dx = x1 - x0, dy = y1 - y0;
        int n = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
        for (int i = 0; i <= n; i++) dot(o, w, h, x0 + dx * i / n, y0 + dy * i / n, Math.max(0, t / 2), c);
    }

    private static void put(int[] o, int w, int h, int x, int y, int c) { if (x >= 0 && y >= 0 && x < w && y < h) o[y * w + x] = c; }
}
