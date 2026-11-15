package pl.dyktafon;

import java.util.Locale;

/**
 * The voice part of the self-test, copied from Codec2 Recorder's SelfTest.java (which also holds the RADE and modem-loop parts).
 * Keep the shared functions identical in both repositories; this copy differs only in being public, because FlatRaster's own code is in another package. Stage 0 of ROADMAP.md in apk-codec2-recorder.
 * No Android code here, so it runs on a computer (tests/selftest/ST.java) against the real DRED library. It holds the made-up speech used
 * for the speed tests, the voice-chain and DRED speed test, and the summary of a microphone clip. FlatRaster's own parts are PictureTest
 * (the filter, no Android) and VideoTest (the phone's video encoders).
 */
public final class SelfTest {
    /** The voice chain's frame, 40 ms at 16 kHz: the same number as Recorder.CHUNK in Codec2 Recorder, so this file needs no Recorder. */
    public static final int CHUNK = 2 * Dred.FRAME;

    public interface Log { void line(String s); boolean cancelled(); }

    public static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    // ---- test sound ----

    /** Speech-like sound: voiced syllables with moving pitch and formants, pauses, and a little noise. Deterministic. Peak about 9000. */
    public static short[] speechLike(int fs, double seconds) {
        int n = (int) (seconds * fs); short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(7);
        double phase = 0, f1 = 500, f2 = 1500, f3 = 2500, y1a = 0, y1b = 0, y2a = 0, y2b = 0, y3a = 0, y3b = 0;
        int syl = (int) (0.22 * fs), pause = (int) (0.12 * fs), pos = 0, k = 0;
        double t1 = 500, t2 = 1500, pitch = 120, tp = 120; boolean voiced = true; int left = syl;
        double[] peak = {0};
        for (int i = 0; i < n; i++) {
            if (left-- <= 0) {
                k++; voiced = k % 5 != 4; left = voiced ? syl + rnd.nextInt(syl / 2) : pause + rnd.nextInt(pause);
                t1 = 300 + rnd.nextInt(500); t2 = 900 + rnd.nextInt(1400); tp = 95 + rnd.nextInt(90);
            }
            f1 += (t1 - f1) * 0.0008 * 16000 / fs; f2 += (t2 - f2) * 0.0008 * 16000 / fs; pitch += (tp - pitch) * 0.0006 * 16000 / fs;
            phase += pitch / fs; if (phase >= 1) phase -= 1;
            double glottal = voiced ? (phase < 0.4 ? Math.sin(Math.PI * phase / 0.4) : 0) - 0.3 : 0; // a pulse per period
            double e = glottal + 0.02 * (rnd.nextDouble() - 0.5) + (voiced ? 0 : 0.01 * (rnd.nextDouble() - 0.5));
            double y1 = e + 2 * Math.exp(-Math.PI * 80 / fs) * Math.cos(2 * Math.PI * f1 / fs) * y1a - Math.exp(-2 * Math.PI * 80 / fs) * y1b;
            y1b = y1a; y1a = y1;
            double y2 = y1 * 0.3 + 2 * Math.exp(-Math.PI * 120 / fs) * Math.cos(2 * Math.PI * f2 / fs) * y2a - Math.exp(-2 * Math.PI * 120 / fs) * y2b;
            y2b = y2a; y2a = y2;
            double y3 = y2 * 0.3 + 2 * Math.exp(-Math.PI * 200 / fs) * Math.cos(2 * Math.PI * f3 / fs) * y3a - Math.exp(-2 * Math.PI * 200 / fs) * y3b;
            y3b = y3a; y3a = y3;
            double v = y3 * 0.3; peak[0] = Math.max(peak[0], Math.abs(v));
            out[i] = (short) Math.max(-32767, Math.min(32767, v * 4000)); // scaled below once the peak is known
        }
        // Fix the peak at 9000 whatever the filters gave.
        double pk = 1; for (short s : out) pk = Math.max(pk, Math.abs((int) s));
        for (int i = 0; i < n; i++) out[i] = (short) Math.round(out[i] * 9000.0 / pk);
        return out;
    }

    /** Level facts about a stretch of microphone sound. */
    static final class Level {
        int peak, clipped; double rmsDb;
        static Level of(short[] x, int from, int to) {
            Level l = new Level(); double sum = 0; int n = Math.max(1, to - from);
            for (int i = from; i < to; i++) { int a = Math.abs((int) x[i]); l.peak = Math.max(l.peak, a); if (a >= 32000) l.clipped++; sum += (double) x[i] * x[i]; }
            l.rmsDb = 10 * Math.log10(Math.max(1e-9, sum / n) / (32767.0 * 32767.0));
            return l;
        }
        double peakDb() { return 20 * Math.log10(Math.max(1, peak) / 32767.0); }
    }

    /** One line about a long microphone recording: overall level, clipping, and the loudest and quietest second. */
    public static String clipSummary(short[] x, int fs) {
        Level all = Level.of(x, 0, x.length); double loud = -200, quiet = 0; int secs = x.length / fs;
        for (int i = 0; i < secs; i++) { double d = Level.of(x, i * fs, (i + 1) * fs).rmsDb; loud = Math.max(loud, d); quiet = i == 0 ? d : Math.min(quiet, d); }
        return f("%.0f s, peak %.0f dBFS%s, loudest second %.0f dBFS, quietest second %.0f dBFS", x.length / (double) fs, all.peakDb(),
                all.clipped > 0 ? f(", CLIPPED %d samples", all.clipped) : "", loud, quiet);
    }

    // ---- speed ----

    /** Verdict words for a speed factor (how many times faster than real time). */
    public static String verdict(double x, double okAt, double tightAt) { return x >= okAt ? "ok" : x >= tightAt ? "TIGHT" : "TOO SLOW"; }

    /** The recording path: voice chain with the default settings, then DRED coding, for each quality level; then playing. */
    public static void dredSpeed(Log log, double seconds) throws Exception {
        final int fs = Dred.FS, n = CHUNK;
        short[] sp = speechLike(fs, seconds);
        log.line(f("Test sound: %.0f s of made-up speech, 16 kHz.", seconds));
        // The voice chain alone: Denoise on, automatic level, the app's default settings.
        VoiceChain chain = new VoiceChain(fs, VoiceChain.CEILING_DRED, n, true, 0.18, 1, null, 180, 6, 12, 3);
        short[] copy = sp.clone(); short[] chunk = new short[n];
        long t0 = System.nanoTime();
        for (int i = 0; i + n <= copy.length; i += n) { System.arraycopy(copy, i, chunk, 0, n); chain.process(chunk, -100, 5, 1.0); System.arraycopy(chunk, 0, copy, i, n); }
        double chainX = seconds / ((System.nanoTime() - t0) / 1e9);
        log.line(f("Voice chain (Denoise on, automatic level): %.0f x real time, %s", chainX, verdict(chainX, 4, 1.5)));
        // Warm-up: the first coding and playing pass is slower (Java compiling, library loading, caches), so it is run once and not counted.
        try (DredLib.Coder c = new DredLib.Coder(Dred.LEVELS[0]); DredLib.Player p = new DredLib.Player()) {
            short[] fr = new short[Dred.FRAME]; byte[] bits = new byte[Dred.MAX_BLOCK];
            for (int i = 0; i + Dred.FRAME <= Math.min(copy.length, 3 * fs); i += Dred.FRAME) {
                System.arraycopy(copy, i, fr, 0, Dred.FRAME);
                int m = c.frame(fr); if (m >= 0) { System.arraycopy(c.buf, 0, bits, 0, m); p.block(bits, m); }
            }
        }
        double worstEnc = 1e9, worstDec = 1e9;
        for (int li = 0; li < Dred.LEVELS.length && !log.cancelled(); li++) {
            int q = Dred.LEVELS[li];
            java.io.ByteArrayOutputStream blocks = new java.io.ByteArrayOutputStream(); java.util.ArrayList<byte[]> list = new java.util.ArrayList<>();
            short[] fr = new short[Dred.FRAME];
            long a = System.nanoTime();
            try (DredLib.Coder c = new DredLib.Coder(q)) {
                for (int i = 0; i + Dred.FRAME <= copy.length; i += Dred.FRAME) {
                    System.arraycopy(copy, i, fr, 0, Dred.FRAME);
                    int m = c.frame(fr); if (m >= 0) list.add(java.util.Arrays.copyOf(c.buf, m));
                }
                c.finish((b, m) -> list.add(java.util.Arrays.copyOf(b, m)));
            }
            double enc = seconds / ((System.nanoTime() - a) / 1e9);
            long bytes = 0; for (byte[] b : list) bytes += 2 + b.length;
            long b0 = System.nanoTime(); double samples = 0;
            try (DredLib.Player p = new DredLib.Player()) {
                byte[] bits = new byte[Dred.MAX_BLOCK];
                for (byte[] b : list) { System.arraycopy(b, 0, bits, 0, b.length); samples += p.block(bits, b.length); }
            }
            double dec = (samples / fs) / ((System.nanoTime() - b0) / 1e9);
            worstEnc = Math.min(worstEnc, enc); worstDec = Math.min(worstDec, dec);
            log.line(f("DRED %-8s (q%d): %.0f bytes a second; coding %.0f x, playing %.0f x real time", Dred.NAMES[li], q, bytes / (double) list.size(), enc, dec));
        }
        double rec = 1 / (1 / chainX + 1 / worstEnc);
        log.line(f("Recording needs the chain and the coder together: %.0f x real time, %s", rec, verdict(rec, 3, 1.3)));
        log.line(f("Playing, slowest level: %.0f x real time, %s", worstDec, verdict(worstDec, 3, 1.3)));
    }

    public static byte[] shortsToBytes(short[] s, int n) {
        byte[] b = new byte[2 * n]; for (int i = 0; i < n; i++) { b[2 * i] = (byte) s[i]; b[2 * i + 1] = (byte) (s[i] >> 8); } return b;
    }
    public static short[] bytesToShorts(byte[] b) {
        short[] s = new short[b.length / 2]; for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 255) | (b[2 * i + 1] << 8)); return s;
    }
}
