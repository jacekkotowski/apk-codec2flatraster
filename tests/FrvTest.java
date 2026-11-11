package pl.flatraster;

import java.io.ByteArrayOutputStream;
import java.util.Random;

/** Round trip and damage test for the .frv format. Run by tests/frv_test.sh on a computer. */
public class FrvTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        check(FrvWriter.crc16("123456789".getBytes("US-ASCII"), 0, 9) == 0x29B1, "CRC16 matches the standard check value");

        Random r = new Random(3);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FrvWriter w = new FrvWriter(bo, FrvWriter.AV1, 320, 240, 5);
        byte[][] csd = {{1, 2, 3}, {}, {9}};
        byte[] cfg = FrvWriter.packConfig(csd);
        w.write(FrvWriter.CONFIG, 0, cfg, 0, cfg.length);
        int n = 200; byte[][] sent = new byte[n][]; int[] types = new int[n]; long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            sent[i] = new byte[i == 7 ? 0 : i == 8 ? 70000 : r.nextInt(400)]; r.nextBytes(sent[i]);
            if (sent[i].length > 4) { sent[i][0] = (byte) 0xF5; sent[i][1] = (byte) 0x7A; } // payload that looks like a sync
            types[i] = i % 10 == 0 ? FrvWriter.KEY : i % 3 == 0 ? FrvWriter.AUDIO : FrvWriter.DELTA;
            times[i] = i * 333L + (i == 199 ? 4000000000L : 0); // also a time above 2^31
            w.write(types[i], times[i], sent[i], 0, sent[i].length);
        }
        w.close();
        byte[] file = bo.toByteArray();
        check(w.bytes == file.length, "writer's byte count equals the file size");

        FrvReader rd = new FrvReader(file);
        check(rd.codec == FrvWriter.AV1 && rd.width == 320 && rd.height == 240 && rd.c2mode == 5, "header round trip");
        check(rd.packets.size() == n + 1 && rd.skippedBytes == 0, "all packets read back, nothing skipped");
        boolean same = true;
        for (int i = 0; i < n; i++) { FrvReader.Packet p = rd.packets.get(i + 1); same &= p.type == types[i] && p.timeMs == times[i] && java.util.Arrays.equals(p.data, sent[i]); }
        check(same, "types, times and payloads identical");
        byte[][] back = rd.config();
        check(back != null && back.length == 3 && java.util.Arrays.equals(back[0], csd[0]) && back[1].length == 0 && java.util.Arrays.equals(back[2], csd[2]), "decoder setup buffers round trip");
        check(rd.durationMs() == times[199], "duration is the last packet time");

        // damage: flip one byte in each of 20 packets, cut the tail, insert junk
        byte[] bad = file.clone();
        int o = FrvWriter.HEADER; java.util.List<Integer> starts = new java.util.ArrayList<>();
        while (o < bad.length) { starts.add(o); o += FrvWriter.OVERHEAD + ((bad[o + 7] & 255) << 16 | (bad[o + 8] & 255) << 8 | (bad[o + 9] & 255)); }
        int hit = 0;
        for (int i = 20; i < starts.size() && hit < 20; i += 9, hit++) bad[starts.get(i) + 5] ^= 0x40;
        byte[] cut = java.util.Arrays.copyOf(bad, starts.get(starts.size() - 1) + 5);
        FrvReader rb = new FrvReader(cut);
        check(rb.packets.size() == n + 1 - hit - 1, "damaged file: " + rb.packets.size() + " good packets kept, " + (hit + 1) + " lost, " + rb.skippedBytes + " bytes skipped");
        boolean valid = true;
        for (FrvReader.Packet p : rb.packets) { boolean found = p.type == FrvWriter.CONFIG; for (int i = 0; i < n && !found; i++) found = p.timeMs == times[i] && java.util.Arrays.equals(p.data, sent[i]); valid &= found; }
        check(valid, "every packet kept from the damaged file is a true original");

        byte[] junk = new byte[file.length + 300];
        System.arraycopy(file, 0, junk, 0, 500); r.nextBytes(new byte[1]); for (int i = 500; i < 800; i++) junk[i] = (byte) r.nextInt();
        System.arraycopy(file, 500, junk, 800, file.length - 500);
        FrvReader rj = new FrvReader(junk);
        check(rj.packets.size() >= n - 1, "300 junk bytes in the middle cost at most 2 packets (" + (n + 1 - rj.packets.size()) + " lost)");

        boolean threw = false;
        try { new FrvReader(new byte[]{'F', 'R', 'V', '1', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}); } catch (java.io.IOException e) { threw = true; }
        check(threw, "damaged header is refused");

        System.out.println(fails == 0 ? "all checks passed" : "FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
