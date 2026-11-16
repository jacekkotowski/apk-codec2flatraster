package pl.flatraster;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import pl.dyktafon.*;

/**
 * Self-test of the picture side (stage 0 of ROADMAP.md): a made-up camera scene goes through the posterize filter (jni/flat.c) with the app's
 * default settings. Reports how fast the filter is on this phone and gives the posterized pictures to VideoTest. No Android code, so it
 * also runs on a computer (tests/selftest/).
 */
final class PictureTest {
    static final int SRC_W = 640, SRC_H = 480, OUT_W = 320, OUT_H = 240;

    /** One camera frame in I420, 640 x 480: a wall with a gradient and a window, a person's head and shoulders that sways, camera noise. */
    static byte[] scene(int t, java.util.Random noise) {
        byte[] f = new byte[SRC_W * SRC_H * 3 / 2];
        double cx = 320 + 30 * Math.sin(t / 7.0), cy = 230 + 8 * Math.cos(t / 5.0);
        for (int y = 0; y < SRC_H; y++) for (int x = 0; x < SRC_W; x++) {
            double v = 90 + 60.0 * y / SRC_H; // wall, lighter towards the bottom
            if (x > 470 && x < 600 && y > 60 && y < 220) v = 200; // window
            double dx = (x - cx) / 80, dy = (y - cy) / 105;
            if (dx * dx + dy * dy < 1) { v = 165; // head
                if (Math.abs(x - cx + 30) < 12 && Math.abs(y - cy + 25) < 6) v = 60; // eyes
                if (Math.abs(x - cx - 30) < 12 && Math.abs(y - cy + 25) < 6) v = 60;
                if (Math.abs(x - cx) < 28 && Math.abs(y - cy - 45) < 5) v = 80; // mouth
            }
            if (y > 400 && Math.abs(x - cx) < 200 - (y - 400)) v = 70; // shoulders
            f[y * SRC_W + x] = (byte) Math.max(0, Math.min(255, v + 2.5 * noise.nextGaussian()));
        }
        for (int y = 0; y < SRC_H / 2; y++) for (int x = 0; x < SRC_W / 2; x++) {
            boolean head = Math.pow((2 * x - cx) / 80, 2) + Math.pow((2 * y - cy) / 105, 2) < 1;
            f[SRC_W * SRC_H + y * (SRC_W / 2) + x] = (byte) (head ? 110 : 128); // U: warmer in the head
            f[SRC_W * SRC_H * 5 / 4 + y * (SRC_W / 2) + x] = (byte) (head ? 150 : 128); // V
        }
        return f;
    }

    /** 0.16: the time of the two new steps. Storing a camera frame for joining happens for every frame the camera makes (15 to 30 a second),
     *  so it must be a few milliseconds; enlarging the picture smoothly happens once per picture shown. */
    private static void extraSpeed(SelfTest.Log log, long h, ByteBuffer y, ByteBuffer u, ByteBuffer v, int[] argb) {
        java.util.Random noise = new java.util.Random(6);
        double gather = 0, join = 0; int info = 0; byte[] i420 = new byte[OUT_W * OUT_H * 3 / 2];
        final int rounds = 4, per = 6;
        for (int r = 0; r < rounds && !log.cancelled(); r++) {
            for (int k = 0; k <= per; k++) {
                byte[] f = scene(60 + r, noise);
                y.clear(); y.put(f, 0, SRC_W * SRC_H); y.rewind(); u.clear(); u.put(f, SRC_W * SRC_H, SRC_W * SRC_H / 4); u.rewind(); v.clear(); v.put(f, SRC_W * SRC_H * 5 / 4, SRC_W * SRC_H / 4); v.rewind();
                long a = System.nanoTime();
                if (k < per) { Flat.gather(h, y, SRC_W, u, v, SRC_W / 2, 1, SRC_W, SRC_H, 0, false); gather += (System.nanoTime() - a) / 1e6; }
                else { Flat.process(h, y, SRC_W, u, v, SRC_W / 2, 1, SRC_W, SRC_H, 0, false, 2, 12, 1, 0, i420, argb); join += (System.nanoTime() - a) / 1e6; info = Flat.gatherInfo(h); }
            }
        }
        int f = Flat.viewFactor(OUT_W, OUT_H); int[] big = new int[OUT_W * f * OUT_H * f];
        long a = System.nanoTime();
        for (int r = 0; r < rounds; r++) Flat.upscale(argb, OUT_W, OUT_H, f, 0, big);
        double up = (System.nanoTime() - a) / 1e6 / rounds;
        log.line(SelfTest.f("Joining camera frames: %.1f ms to store one frame, %.1f ms for a picture made from %d frames (noise limit %d). Enlarging %dx%d %d times for the screen: %.1f ms.",
                gather / (rounds * per), join / rounds, (info & 255) + 1, info >> 16, OUT_W, OUT_H, f, up));
    }

    /** Runs frames pictures through the filter at the app's defaults (320 x 240, 8 colours, smoothing 2, clean-up 1, borders rounded).
     *  Returns the posterized I420 pictures for the encoders. */
    static List<byte[]> filterSpeed(SelfTest.Log log, int frames) {
        java.util.Random noise = new java.util.Random(5);
        long h = Flat.create(OUT_W, OUT_H, 8);
        if (h == 0) throw new IllegalStateException("the filter did not start");
        List<byte[]> out = new ArrayList<>(); double total = 0, worst = 0, firstMs = 0;
        try {
            Flat.setTrack(h, 0, 2); Flat.setRound(h, 1);
            ByteBuffer y = ByteBuffer.allocateDirect(SRC_W * SRC_H), u = ByteBuffer.allocateDirect(SRC_W * SRC_H / 4), v = ByteBuffer.allocateDirect(SRC_W * SRC_H / 4);
            int[] argb = new int[OUT_W * OUT_H]; int palettes = 0;
            for (int t = 0; t < frames && !log.cancelled(); t++) {
                byte[] f = scene(t, noise);
                y.clear(); y.put(f, 0, SRC_W * SRC_H); y.rewind(); u.clear(); u.put(f, SRC_W * SRC_H, SRC_W * SRC_H / 4); u.rewind(); v.clear(); v.put(f, SRC_W * SRC_H * 5 / 4, SRC_W * SRC_H / 4); v.rewind();
                byte[] i420 = new byte[OUT_W * OUT_H * 3 / 2];
                long a = System.nanoTime();
                int r = Flat.process(h, y, SRC_W, u, v, SRC_W / 2, 1, SRC_W, SRC_H, 0, false, 2, 12, 1, 0, i420, argb);
                double ms = (System.nanoTime() - a) / 1e6;
                if (r < 0) throw new IllegalStateException("the filter refused a frame");
                if (r > 0) palettes++;
                if (t == 0) firstMs = ms; else { total += ms; worst = Math.max(worst, ms); }
                out.add(i420);
            }
            int n = Math.max(1, out.size() - 1);
            log.line(SelfTest.f("Posterize filter, camera frame %dx%d to %dx%d, 8 colours: %.1f ms a picture on average, %.1f ms at worst (first picture %.1f ms), palette rebuilt %d times in %d pictures.",
                    SRC_W, SRC_H, OUT_W, OUT_H, total / n, worst, firstMs, palettes, out.size()));
            log.line(SelfTest.f("The filter alone could make %.0f pictures a second; the app's default is 2.", 1000 / Math.max(0.01, total / n)));
            extraSpeed(log, h, y, u, v, argb);
        } finally { Flat.destroy(h); }
        return out;
    }
}
