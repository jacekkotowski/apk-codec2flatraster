package pl.flatraster;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The exact coder inside the app's own classes, on a computer: the real filter (libflat) makes pictures from made-up camera frames,
 *  ExactEncoder codes them into a .frv version 3 file, FrvReader reads it back and Exact decodes it. Every picture must come back
 *  identical to the filter's own. Run by tests/exact_test.sh. */
public class ExactTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    /** A 320x240 I420 frame: a gradient room, a swaying head with a moving mouth, camera noise. */
    static byte[] frame(int t, Random rnd) {
        int w = 320, h = 240; byte[] f = new byte[w * h * 3 / 2];
        double cx = 160 + 30 * Math.sin(t / 8.0), cy = 120 + 6 * Math.sin(t / 5.0), mouth = 2 + 8 * Math.abs(Math.sin(t / 3.0));
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = 80 + 60.0 * y / h;
            if (x > 200 && x < 300 && y > 20 && y < 90) v = 190;
            double dx = (x - cx) / 40, dy = (y - cy) / 52;
            if (dx * dx + dy * dy < 1) { v = 150; if (Math.abs(x - cx) < 14 && Math.abs(y - cy - 24) < mouth / 2) v = 90; }
            f[y * w + x] = (byte) Math.max(0, Math.min(255, v + rnd.nextGaussian() * 1.5));
        }
        for (int i = w * h; i < f.length; i++) f[i] = (byte) 128;
        return f;
    }

    public static void main(String[] a) throws Exception {
        final int sw = 320, sh = 240, w = 160, h = 120, n = 60;
        long flat = Flat.create(w, h, 8);
        byte[] i420 = new byte[w * h * 3 / 2]; int[] argb = new int[w * h];
        Random rnd = new Random(5);
        final List<int[]> shown = new ArrayList<>(); final List<Long> times = new ArrayList<>();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FrvWriter wr = new FrvWriter(bo, FrvWriter.EXACT, w, h, FrvReader.BITSTREAM, 9);
        ExactEncoder enc = new ExactEncoder(flat, w, h, 10, (type, t, d) -> wr.write(type, t, d, 0, d.length));
        int keys = 0;
        for (int i = 0; i < n; i++) {
            byte[] f = frame(i, rnd);
            ByteBuffer y = ByteBuffer.allocateDirect(sw * sh), u = ByteBuffer.allocateDirect(sw * sh / 4), v = ByteBuffer.allocateDirect(sw * sh / 4);
            y.put(f, 0, sw * sh); u.put(f, sw * sh, sw * sh / 4); v.put(f, sw * sh * 5 / 4, sw * sh / 4);
            int r = Flat.process(flat, y, sw, u, v, sw / 2, 1, sw, sh, 0, false, 2, 12, 1, 0, i420, argb);
            if (r < 0) { check(false, "filter took frame " + i); return; }
            if (r == 1) enc.requestKeyFrame();
            long t = i * 500L;
            enc.encode(i420, t);
            shown.add(argb.clone()); times.add(t);
            if (i % 20 == 19) { /* nothing */ }
        }
        check(true, "filter took all " + n + " frames");
        enc.close(); wr.close();
        byte[] file = bo.toByteArray();
        FrvReader rd = new FrvReader(file);
        check(file[4] == 4 && rd.codec == FrvWriter.EXACT && rd.width == w && rd.height == h, "file is version 4 with codec Exact");
        check(rd.packets.size() == n && rd.skippedBytes == 0, "all " + n + " pictures in the file");
        Exact dec = new Exact(w, h); int[] px = new int[w * h]; boolean same = true; int k = 0;
        for (int i = 0; i < rd.packets.size(); i++) {
            FrvReader.Packet p = rd.packets.get(i);
            if (p.type == FrvWriter.KEY) k++;
            boolean ok = dec.decode(p.type, p.data, px);
            same &= ok && java.util.Arrays.equals(px, shown.get(i)) && p.timeMs == times.get(i);
        }
        check(same, "every decoded picture is identical to the filter's picture, with its time");
        check(k >= 1 && k < n, "key pictures: " + k + " of " + n);
        int total = 0; for (FrvReader.Packet p : rd.packets) total += p.data.length;
        System.out.printf("     %d pictures (%d x %d, 8 colours, noisy made-up scene): %d bytes of picture data, %d with packet headers, %.1f kbit/s%n", n, w, h, total, file.length - FrvWriter.HEADER, (file.length - FrvWriter.HEADER) * 8 / (n * 0.5) / 1000);
        // a delta picture before any key picture is refused; a damaged picture fails or decodes to something, never crashes
        Exact fresh = new Exact(w, h);
        check(!fresh.decode(FrvWriter.DELTA, rd.packets.get(1).data, px), "a delta picture before any key picture is refused");
        Random bad = new Random(9); boolean fine = true;
        for (int i = 0; i < 400; i++) {
            FrvReader.Packet p = rd.packets.get(i % n);
            byte[] d = p.data.clone();
            if (d.length > 0) d[bad.nextInt(d.length)] ^= 1 << bad.nextInt(8);
            if (bad.nextInt(4) == 0) d = java.util.Arrays.copyOf(d, bad.nextInt(d.length + 1));
            try { dec.decode(p.type == FrvWriter.KEY || i % 7 == 0 ? p.type : FrvWriter.DELTA, d, px); } catch (Throwable e) { fine = false; }
        }
        check(fine, "400 damaged or cut packets: no crash");
        // an old reader's view: version 2 files still read, and a version 3 header with another codec is refused
        ByteArrayOutputStream b2 = new ByteArrayOutputStream(); new FrvWriter(b2, FrvWriter.AV1, 64, 48, FrvReader.BITSTREAM, 9).close();
        check(new FrvReader(b2.toByteArray()).codec == FrvWriter.AV1, "a version 2 file (AV1) still reads");
        byte[] h3 = b2.toByteArray(); h3[4] = 4; int c = FrvWriter.crc16(h3, 0, 12); h3[12] = (byte) (c >> 8); h3[13] = (byte) c;
        boolean refused = false; try { new FrvReader(h3); } catch (java.io.IOException e) { refused = true; }
        check(refused, "a version 4 header with a video codec is refused");
        Flat.destroy(flat); dec.close(); fresh.close();
        System.exit(fails == 0 ? 0 : 1);
    }
}
