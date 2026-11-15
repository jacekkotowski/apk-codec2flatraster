package pl.flatraster;

import pl.dyktafon.SelfTest;

/** Computer test of the self-test: the voice speed test on the real DRED library, and the picture test on the real filter. The scene must come out
 *  as at most 8 colours, with changing pictures, and the pictures must have the size the encoders are given. Run with tests/selftest/run.sh. */
public class ST {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
    static final SelfTest.Log LOG = new SelfTest.Log() { public void line(String s) { System.out.println("   | " + s); } public boolean cancelled() { return false; } };

    public static void main(String[] a) throws Exception {
        SelfTest.dredSpeed(LOG, 10);
        java.util.List<byte[]> frames = PictureTest.filterSpeed(LOG, 30);
        check(frames.size() == 30 && frames.get(0).length == 320 * 240 * 3 / 2, "30 pictures of the encoder's size");
        // Posterized: the luma plane holds few distinct values (8 colours, so 8 luma values at most plus rounding of the limited-range conversion).
        java.util.HashSet<Integer> lum = new java.util.HashSet<>(); byte[] f = frames.get(29); for (int i = 0; i < 320 * 240; i++) lum.add(f[i] & 255);
        check(lum.size() <= 8, "picture is posterized: " + lum.size() + " luma values");
        int diff = 0; for (int i = 0; i < 320 * 240; i++) if (frames.get(0)[i] != f[i]) diff++;
        check(diff > 100, "the head moves between pictures: " + diff + " luma pixels differ");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
    }
}
