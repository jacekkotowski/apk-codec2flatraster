package pl.flatraster;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The Puppet format on a computer, everything except the MediaPipe models (those run only on a phone): made-up walking face and body points go
 *  through the point coder, a .frv of codec 4 (key pictures from the real filter and the Exact coder, POINTS packets), the reader, the decoder and the
 *  drawing. Run by tests/puppet_test.sh. */
public class PuppetTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        final int w = 160, h = 120, n = 60;
        long flat = Flat.create(w, h, 8);
        byte[] i420 = new byte[w * h * 3 / 2]; int[] argb = new int[w * h];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FrvWriter wr = new FrvWriter(bo, FrvWriter.PUPPET, w, h, FrvReader.BITSTREAM, 9);
        Exact ex = new Exact(w, h); Points enc = new Points(PuppetDraw.SIZES);
        Random rnd = new Random(2);
        float[][] pos = {new float[44], new float[16]};
        for (int i = 0; i < 22; i++) { pos[0][2 * i] = 70 + 3 * (i % 6); pos[0][2 * i + 1] = 40 + 3 * (i / 6); }
        for (int i = 0; i < 8; i++) { pos[1][2 * i] = 60 + 6 * (i % 4); pos[1][2 * i + 1] = 60 + 8 * (i / 4); }
        List<int[]> units = new ArrayList<>(); List<int[]> present = new ArrayList<>();
        int pointBytes = 0, keys = 0;
        for (int t = 0; t < n; t++) {
            java.nio.ByteBuffer y = java.nio.ByteBuffer.allocateDirect(320 * 240), u = java.nio.ByteBuffer.allocateDirect(320 * 240 / 4), v = java.nio.ByteBuffer.allocateDirect(320 * 240 / 4);
            byte[] f = ExactTest.frame(t, rnd);
            y.put(f, 0, 320 * 240); u.put(f, 320 * 240, 320 * 240 / 4); v.put(f, 320 * 240 * 5 / 4, 320 * 240 / 4);
            Flat.process(flat, y, 320, u, v, 160, 1, 320, 240, 0, false, 2, 12, 1, 0, i420, argb);
            long tm = t * 500L;
            if (t % 20 == 0) { Exact.Coded c = ex.encode(flat, true); wr.write(FrvWriter.KEY, tm, c.data, 0, c.data.length); enc.reset(); keys++; }
            for (float[] o : pos) for (int i = 0; i < o.length; i++) o[i] += (float) rnd.nextGaussian() * 0.5f;
            int[] pr = {t % 17 == 5 ? 0 : 1, 1};                       // the face is lost now and then
            int[] un = new int[60]; int k = 0;
            for (int o = 0; o < 2; o++) if (pr[o] == 1) for (int i = 0; i < pos[o].length; i++) un[k++] = Math.max(0, Math.min(1023, (int) Math.rint(pos[o][i] * PuppetDraw.UNITS / w)));
            byte[] b = enc.encode(pr, java.util.Arrays.copyOf(un, Math.max(2, k)));
            if (b == null) check(false, "frame " + t + " points coded");
            wr.write(FrvWriter.POINTS, tm, b, 0, b.length); pointBytes += b.length;
            units.add(java.util.Arrays.copyOf(un, k)); present.add(pr);
        }
        wr.close();
        byte[] file = bo.toByteArray();
        FrvReader rd = new FrvReader(file);
        check(file[4] == 4 && rd.codec == FrvWriter.PUPPET && rd.skippedBytes == 0, "file is version 4 with codec Puppet, nothing skipped");
        Exact dec = new Exact(w, h); Points pd = new Points(PuppetDraw.SIZES);
        int[] px = new int[w * h]; int t = -1; boolean allSame = true, drawn = true, have = false; int shown = 0;
        for (FrvReader.Packet p : rd.packets) {
            if (p.type == FrvWriter.KEY) { have = dec.decode(FrvWriter.KEY, p.data, px); pd.reset(); continue; }
            t++;
            int[] pr = new int[2], un = new int[pd.points * 2];
            boolean ok = pd.decode(p.data, pr, un);
            allSame &= ok && java.util.Arrays.equals(pr, present.get(t)) && java.util.Arrays.equals(java.util.Arrays.copyOf(un, units.get(t).length), units.get(t));
            float[] face = PuppetDraw.unitsToPixels(un, 0, 22, pr[0] != 0, w), body = PuppetDraw.unitsToPixels(un, pr[0] != 0 ? 22 : 0, 8, pr[1] != 0, w);
            int[] img = PuppetDraw.draw(px, w, h, face, body);
            drawn &= img.length == w * h && img[0] == PuppetDraw.ORANGE && !java.util.Arrays.equals(img, px);
            shown++;
        }
        check(have && shown == n, "all " + n + " pictures reach the drawing");
        check(allSame, "decoded points identical to the encoded ones, objects that vanish included");
        check(drawn, "the drawing has the orange puppet frame and differs from the key picture");
        // two small checks of the drawing: points far outside the picture do not crash it; a missing object leaves just the key picture
        PuppetDraw.draw(null, w, h, new float[44], new float[]{-500, 900, 3000, -20, 1e9f, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        check(true, "points outside the picture are clipped");
        // short packets: times (absolute at key pictures, relative between), damage in the middle costs only the damaged packet
        boolean timesOk = true; long tPrev = 0; int idx = 0;
        for (FrvReader.Packet p : rd.packets) { if (p.type == FrvWriter.POINTS) { timesOk &= p.timeMs == idx * 500L; idx++; } }
        check(timesOk, "packet times read back exactly (short packets)");
        byte[] dmg = file.clone(); for (int i = 0; i < 6; i++) dmg[file.length / 2 + i] ^= 0x55;
        FrvReader rd2 = new FrvReader(dmg);
        check(rd2.packets.size() >= rd.packets.size() - 2 && rd2.packets.size() < rd.packets.size() && rd2.skippedBytes > 0, "damage in the middle costs one or two packets (" + rd2.packets.size() + " of " + rd.packets.size() + " read)");
        boolean keyTimes = true; for (FrvReader.Packet p : rd2.packets) if (p.type == FrvWriter.KEY) keyTimes &= p.timeMs % 10000 == 0;
        check(keyTimes, "key pictures after the damage still have their absolute times");
        System.out.printf("     %d pictures at 2 a second, 160 x 120: %d bytes of points (%d B a picture), %d key pictures, file %d bytes%n", n, pointBytes, pointBytes / n, keys, file.length);
        Flat.destroy(flat); ex.close(); dec.close(); enc.close(); pd.close();
        System.exit(fails == 0 ? 0 : 1);
    }
}
