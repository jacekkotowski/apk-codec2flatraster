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
    static final int ORANGE = 0xffff8c00, INK = 0xff101010, BODYC = 0xff1060d0;

    /** base: the key picture (argb, w x h) or null for a plain light picture. face: 22 (x, y) pairs or null; body: 8 pairs or null; all in
     *  picture pixels. Returns a new picture. */
    static int[] draw(int[] base, int w, int h, float[] face, float[] body) {
        int[] o = new int[w * h];
        for (int i = 0; i < o.length; i++) {
            int p = base == null ? 0xffe0e0e0 : base[i];
            int r = ((p >> 16 & 255) * 2 + 255 * 3) / 5, g = ((p >> 8 & 255) * 2 + 255 * 3) / 5, b = ((p & 255) * 2 + 255 * 3) / 5;   // 40% of the key picture, 60% white
            o[i] = 0xff000000 | r << 16 | g << 8 | b;
        }
        int t = Math.max(1, h / 70);
        if (body != null) {
            for (int[] l : BODY_LINES) line(o, w, h, body[l[0] * 2], body[l[0] * 2 + 1], body[l[1] * 2], body[l[1] * 2 + 1], t + 1, BODYC);
            for (int i = 0; i <= 5; i++) dot(o, w, h, body[i * 2], body[i * 2 + 1], t + 2, BODYC);
        }
        if (face != null) {
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
