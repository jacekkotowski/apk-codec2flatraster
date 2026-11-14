package pl.flatraster;

import java.io.*;
import java.util.*;
import pl.dyktafon.VoiceBridge;

/** Voice track of a .frv without Android (PLAN-dred.md step 4 and 5): a 16 kHz speech file through VoicePackets into a .frv with
 *  audio only, read back with FrvReader, laid out by AudioPlan and decoded into out.raw on the recording's time line.
 *  java -Djava.library.path=tests/dred/out -cp tests/dred/out pl.flatraster.VP Q SKIP(0/1) OFFSET_MS in.raw out.frv out.raw
 *  Also checks AudioPlan's rules on made-up packets. */
public class VP {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }
    static FrvReader.Packet pk(long t, int n) { return new FrvReader.Packet(FrvWriter.AUDIO, t, new byte[n]); }

    public static void main(String[] a) throws Exception {
        // AudioPlan on made-up packets: gap, a run that overlaps the padded end of the one before, two at the same time.
        List<FrvReader.Packet> ps = new ArrayList<>(Arrays.asList(pk(2500, 9), pk(500, 9), pk(1500, 9), new FrvReader.Packet(FrvWriter.KEY, 700, new byte[3]), pk(3000, 9), pk(3000, 7), pk(6000, 0)));
        List<AudioPlan.Item> pl = AudioPlan.of(ps);
        long[] sil = new long[pl.size()]; int[] len = new int[pl.size()];
        for (int i = 0; i < pl.size(); i++) { sil[i] = pl.get(i).silence; len[i] = pl.get(i).samples; }
        check(Arrays.equals(sil, new long[]{8000, 0, 0, 0, 32000}) && Arrays.equals(len, new int[]{16000, 16000, 8000, 16000, 16000}),
                "AudioPlan: gap 0.5 s, cut where the next run starts, of two at the same time the later wins: silence " + Arrays.toString(sil) + " samples " + Arrays.toString(len));

        int q = Integer.parseInt(a[0]); boolean skip = a[1].equals("1"); long off = Long.parseLong(a[2]);
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[3])));
        long n = new File(a[3]).length() / 2;
        List<long[]> times = new ArrayList<>();
        try (FrvWriter w = new FrvWriter(new File(a[4]), FrvWriter.AV1, 320, 240, VoiceBridge.bitstream(), q); VoiceBridge vb = new VoiceBridge(q, false)) {
            VoicePackets vp = new VoicePackets(vb, skip, off, (t, d, len2) -> { w.write(FrvWriter.AUDIO, t, d, 0, len2); times.add(new long[]{t, len2}); });
            short[] pcm = new short[VoiceBridge.CHUNK];
            for (long i = 0; i + pcm.length <= n; i += pcm.length) { for (int k = 0; k < pcm.length; k++) pcm[k] = Short.reverseBytes(in.readShort()); vp.chunk(pcm); }
            vp.finish();
        }
        FrvReader r = new FrvReader(new File(a[4]));
        boolean rising = true; for (int i = 1; i < times.size(); i++) rising &= times.get(i)[0] > times.get(i - 1)[0];
        check(r.q == q && r.bitstream == 12 && r.packets.size() == times.size(), "read back: " + r.packets.size() + " audio packets, q " + r.q);
        check(rising, "packet times rise");
        long bytes = 0; for (long[] t : times) bytes += t[1] + FrvWriter.OVERHEAD;
        System.out.printf("info input %.1f s, %d blocks, first at %d ms, last at %d ms, voice track %.0f bit/s with packet headers%n", n / 16000.0, times.size(), times.get(0)[0], times.get(times.size() - 1)[0], bytes * 8 / (n / 16000.0));
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(a[5])); VoiceBridge.Decoder d = new VoiceBridge.Decoder()) {
            for (AudioPlan.Item it : AudioPlan.of(r.packets)) {
                for (long s = 0; s < it.silence; s++) { o.write(0); o.write(0); }
                int m = Math.min(it.samples, d.block(it.block, it.block.length));
                for (int k = 0; k < m; k++) { o.write(d.pcm[k]); o.write(d.pcm[k] >> 8); }
            }
        }
        System.exit(fails == 0 ? 0 : 1);
    }
}
