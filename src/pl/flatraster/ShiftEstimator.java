package pl.flatraster;

/** How far the background has moved between two grey pictures (the key picture's camera frame and the present one): the camera moved. Brute-force
 *  search of whole-pixel shifts by the mean absolute difference over the part that overlaps, left out inside a box (the person), and a parabola through
 *  the three best neighbours for a fraction of a pixel. Pure Java so it is tested on a computer. */
final class ShiftEstimator {
    /** Returns {dx, dy}: the content of `key` appears at (x + dx, y + dy) in `cur`. box: {x0, y0, x1, y1} in pixels to leave out, or null. range: pixels searched each way.
     *  Returns {0, 0} when no shift fits clearly better than none (a flat or noisy background must not wander). */
    static float[] estimate(byte[] key, byte[] cur, int w, int h, int[] box, int range) {
        int n = 2 * range + 1;
        double[][] sad = new double[n][n];
        double best = Double.MAX_VALUE; int bx = 0, by = 0;
        for (int dy = -range; dy <= range; dy++) for (int dx = -range; dx <= range; dx++) {
            double s = 0; long c = 0;
            for (int y = Math.max(0, dy); y < Math.min(h, h + dy); y++) for (int x = Math.max(0, dx); x < Math.min(w, w + dx); x++) {
                if (box != null && x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) continue;
                s += Math.abs((cur[y * w + x] & 255) - (key[(y - dy) * w + (x - dx)] & 255)); c++;
            }
            double m = c > w * h / 8 ? s / c : Double.MAX_VALUE;            // too little of the picture left to judge: not a candidate
            sad[dy + range][dx + range] = m;
            if (m < best) { best = m; bx = dx; by = dy; }
        }
        double zero = sad[range][range];
        if (best == Double.MAX_VALUE || zero == Double.MAX_VALUE || best > zero * 0.9 - 0.5) return new float[]{0, 0};
        float fx = bx, fy = by;
        if (bx > -range && bx < range) fx += parabola(sad[by + range][bx - 1 + range], sad[by + range][bx + range], sad[by + range][bx + 1 + range]);
        if (by > -range && by < range) fy += parabola(sad[by - 1 + range][bx + range], sad[by + range][bx + range], sad[by + 1 + range][bx + range]);
        return new float[]{fx, fy};
    }

    private static float parabola(double a, double b, double c) {
        if (a == Double.MAX_VALUE || c == Double.MAX_VALUE) return 0;
        double d = a - 2 * b + c;
        return d <= 1e-9 ? 0 : (float) Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / d));
    }

    /** Grey picture of gw x gh from RGBA (ow x oh): the mean of each block. */
    static byte[] grey(byte[] rgba, int ow, int oh, int gw, int gh) {
        byte[] g = new byte[gw * gh];
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            int x0 = x * ow / gw, x1 = Math.max(x0 + 1, (x + 1) * ow / gw), y0 = y * oh / gh, y1 = Math.max(y0 + 1, (y + 1) * oh / gh), s = 0, c = 0;
            for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) { int o = (yy * ow + xx) * 4; s += ((rgba[o] & 255) * 299 + (rgba[o + 1] & 255) * 587 + (rgba[o + 2] & 255) * 114) / 1000; c++; }
            g[y * gw + x] = (byte) (s / c);
        }
        return g;
    }
}
