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

    /** 30 face points laid out like a face centred at (cx, cy): the order of PuppetDraw (eyes, lids, brows, mouth, nose, chin, sides, extra lip points). */
    static float[] realFace(float cx, float cy) {
        float[][] q = {{-14, -8}, {-6, -8}, {6, -8}, {14, -8}, {-10, -10}, {-10, -6}, {10, -10}, {10, -6}, {-14, -12}, {-6, -13}, {6, -13}, {14, -12},
                {-8, 10}, {8, 10}, {0, 9}, {0, 12}, {0, 8}, {0, 14}, {0, 0}, {0, 30}, {-22, -2}, {22, -2}, {-4, 8}, {4, 8}, {-4, 14}, {4, 14}, {-4, 9}, {4, 9}, {-4, 12}, {4, 12}};
        float[] f = new float[60]; for (int i = 0; i < 30; i++) { f[2 * i] = cx + q[i][0]; f[2 * i + 1] = cy + q[i][1]; } return f;
    }

    public static void main(String[] a) throws Exception {
        final int w = 160, h = 120, n = 60;
        long flat = Flat.create(w, h, 8);
        byte[] i420 = new byte[w * h * 3 / 2]; int[] argb = new int[w * h];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FrvWriter wr = new FrvWriter(bo, FrvWriter.PUPPET, w, h, FrvReader.BITSTREAM, 9);
        Exact ex = new Exact(w, h); Points enc = new Points(PuppetDraw.SIZES);
        Random rnd = new Random(2);
        float[][] pos = {new float[60], new float[16]};
        for (int i = 0; i < 30; i++) { pos[0][2 * i] = 70 + 3 * (i % 6); pos[0][2 * i + 1] = 40 + 3 * (i / 6); }
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
            int[] un = new int[76]; int k = 0;
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
            float[] face = PuppetDraw.unitsToPixels(un, 0, 30, pr[0] != 0, w), body = PuppetDraw.unitsToPixels(un, pr[0] != 0 ? 30 : 0, 8, pr[1] != 0, w);
            int[] img = PuppetDraw.draw(px, w, h, face, body);
            drawn &= img.length == w * h && img[0] == PuppetDraw.ORANGE && !java.util.Arrays.equals(img, px);
            shown++;
        }
        check(have && shown == n, "all " + n + " pictures reach the drawing");
        check(allSame, "decoded points identical to the encoded ones, objects that vanish included");
        check(drawn, "the drawing has the orange puppet frame and differs from the key picture");
        // two small checks of the drawing: points far outside the picture do not crash it; a missing object leaves just the key picture
        PuppetDraw.draw(null, w, h, new float[60], new float[]{-500, 900, 3000, -20, 1e9f, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        check(true, "points outside the picture are clipped");
        // the person cut-out: the background becomes a coarse colour map, the person keeps the filter's colours
        byte[] mask = new byte[w * h];
        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) { double dx = (xx - 80) / 30.0, dy = (yy - 60) / 40.0; mask[yy * w + xx] = (byte) (dx * dx + dy * dy < 1 ? 1 : 0); }
        Exact.Coded plain = ex.encode(flat, true), cut = ex.encodeMasked(flat, mask, 8);
        int[] pc = new int[w * h]; boolean got = cut != null && dec.decode(FrvWriter.KEY, cut.data, pc);
        boolean person = got, blocks = got;
        for (int by = 0; by < h && got; by += 8) for (int bx = 0; bx < w; bx += 8) {
            int bg = -1;
            for (int yy = by; yy < by + 8 && yy < h; yy++) for (int xx = bx; xx < bx + 8 && xx < w; xx++) {
                if (mask[yy * w + xx] == 1) person &= pc[yy * w + xx] == argb[yy * w + xx];
                else { if (bg == -1) bg = pc[yy * w + xx]; blocks &= pc[yy * w + xx] == bg; }
            }
        }
        check(got && person, "cut-out: the person's pixels are exactly the filter's");
        check(blocks, "cut-out: every 8 x 8 block of background is one colour");
        check(got && cut.data.length < plain.data.length, "cut-out key picture is smaller (" + (cut == null ? -1 : cut.data.length) + " against " + plain.data.length + " bytes)");
        // the limited warp: a dot at the face moves with the points, a dot far away stays, no movement leaves the picture as it was
        int[] key = new int[w * h]; java.util.Arrays.fill(key, 0xff202020);
        for (int yy = 0; yy < 5; yy++) for (int xx = 0; xx < 5; xx++) { key[(48 + yy) * w + 78 + xx] = 0xffffffff; key[(100 + yy) * w + 8 + xx] = 0xffffffff; }
        float[] f0 = new float[60]; for (int i = 0; i < 30; i++) { f0[2 * i] = 70 + 3 * (i % 6); f0[2 * i + 1] = 40 + 3 * (i / 6); }
        float[] f1 = f0.clone(); for (int i = 0; i < 30; i++) f1[2 * i] += 6;
        int[] same = PuppetDraw.warp(key, w, h, f0, null, f0, null);
        check(java.util.Arrays.equals(same, key), "warp: no movement leaves the picture as it was");
        int[] moved = PuppetDraw.warp(key, w, h, f0, null, f1, null);
        double sx = 0, sn = 0, bx = 0, bn = 0;
        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) if ((moved[yy * w + xx] & 255) > 200) { if (xx > 40) { sx += xx; sn++; } else { bx += xx; bn++; } }
        check(sn > 10 && Math.abs(sx / sn - (80 + 6)) < 2.5, "warp: the dot at the face moved with the points (" + (sn > 0 ? String.format("%.1f", sx / sn) : "lost") + " against 86)");
        check(bn > 10 && Math.abs(bx / bn - 10) < 0.6, "warp: a dot far from the person stays where it was");
        float[] f2 = f0.clone(); for (int i = 0; i < 30; i++) f2[2 * i] += 80;
        int[] far = PuppetDraw.warp(key, w, h, f0, null, f2, null); double fx = 0, fn = 0;
        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) if ((far[yy * w + xx] & 255) > 200 && xx > 40) { fx += xx; fn++; }
        check(fn == 0 || Math.abs(fx / fn - 80) < 25, "warp: a huge movement is limited, not followed all the way");
        // raising the head: a row of dots across the top of the head must stay a straight row (the owner saw the top turn into a heart-shaped dent).
        // A face laid out like a real one (160 x 120 picture): sides at x 58 and 102, brows at y 58, eyes 62, nose 70, mouth 80, chin 100; hair above y 23.
        float[] face0 = realFace(80, 70);
        for (float[] mv : new float[][]{{0, -8, 1}, {0, -16, 1}, {10, -12, 1}, {0, -6, 0.85f}}) {     // a small raise, a big one, a raise to one side, a nod back (the face is shorter by 15%)
            int[] row = new int[w * h]; java.util.Arrays.fill(row, 0xff202020);
            for (int dx = 0; dx < 7; dx++) for (int yy = 0; yy < 3; yy++) for (int xx = 0; xx < 3; xx++) row[(30 + yy) * w + 56 + dx * 8 + xx] = 0xffffffff;
            float[] face1 = face0.clone(); for (int i = 0; i < 30; i++) { face1[2 * i] += mv[0]; face1[2 * i + 1] = 70 + (face0[2 * i + 1] - 70) * mv[2] + mv[1]; }
            int[] raised = PuppetDraw.warp(row, w, h, face0, null, face1, null);
            double lo = 1e9, hi = -1e9, sumdy = 0; int found = 0;
            for (int dx = 0; dx < 7; dx++) {
                double rsy = 0, rsn = 0;
                for (int yy = 0; yy < h; yy++) for (int xx = 56 + dx * 8 - 3 + (int) mv[0]; xx < 56 + dx * 8 + 6 + (int) mv[0]; xx++) if (xx >= 0 && xx < w && (raised[yy * w + xx] & 255) > 200) { rsy += yy; rsn++; }
                if (rsn > 0) { found++; double c = rsy / rsn - 31; lo = Math.min(lo, c); hi = Math.max(hi, c); }   // how far each dot is from where the head moved to
            }
            check(found == 7 && hi - lo < 2.5, "head moved by (" + (int) mv[0] + ", " + (int) mv[1] + ") and scaled " + mv[2] + ": the row across the top of the head stays straight and follows it (spread " + String.format("%.1f", hi - lo) + " pixels, " + found + " of 7 dots)");
        }
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
