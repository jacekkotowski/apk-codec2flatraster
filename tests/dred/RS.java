package pl.dyktafon;

import java.util.*;

/** Resampler check: tones at common source rates into 16 kHz. Speech-band tones must pass at full level with the right length;
 *  tones above 8 kHz must not fold back into the recording. java -cp tests/dred/out pl.dyktafon.RS */
public class RS {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    /** Level in dB of frequency f in x (16 kHz), and total level, skipping the start. */
    static double[] measure(List<Double> y, double f) {
        int st = 2000, n = y.size() - st - 2000; double c = 0, s = 0, e = 0;
        for (int i = 0; i < n; i++) { double v = y.get(st + i); c += v * Math.cos(2 * Math.PI * f * i / 16000); s += v * Math.sin(2 * Math.PI * f * i / 16000); e += v * v; }
        return new double[]{20 * Math.log10(2 * Math.hypot(c, s) / n + 1e-12), 10 * Math.log10(e / n * 2 + 1e-24)};
    }

    public static void main(String[] a) throws Exception {
        for (int rate : new int[]{8000, 11025, 16000, 22050, 32000, 44100, 48000}) {
            for (double f : new double[]{300, 1000, 3400, 6000, 7000, 9000, 15000, 20000}) {
                if (f >= rate / 2.0) continue;
                List<Double> y = new ArrayList<>(); Resampler r = new Resampler(rate, 16000, y::add);
                int n = rate * 2;
                for (int i = 0; i < n; i++) r.put(Math.sin(2 * Math.PI * f * i / rate));
                double[] m = measure(y, f);
                int lenErr = Math.abs(y.size() - 32000);
                if (f <= 6000 && f < rate * 0.42 || rate == 8000 && f == 3400) check(Math.abs(m[0]) < (f > 3000 && rate == 8000 ? 1.0 : 0.1) && lenErr < 300, String.format("%5d Hz source, %5.0f Hz tone: %+.2f dB, %d samples (32000 expected less the filter delay)", rate, f, m[0], y.size()));
                else if (f >= 8500) check(m[1] < -60, String.format("%5d Hz source, %5.0f Hz tone above 8 kHz: everything left %.0f dB", rate, f, m[1]));
                else System.out.printf("info %5d Hz source, %5.0f Hz tone in the transition band: %+.1f dB%n", rate, f, m[0]);
            }
        }
        // Same rate: the output is the input, delayed.
        List<Double> y = new ArrayList<>(); Resampler r = new Resampler(16000, 16000, y::add); Random R = new Random(1); double[] x = new double[16000];
        for (int i = 0; i < x.length; i++) { x[i] = R.nextGaussian() * 1000; }
        double lp = 0; for (int i = 0; i < x.length; i++) { lp = 0.7 * lp + 0.3 * x[i]; x[i] = lp; r.put(x[i]); } // keep it well below the cutoff
        double err = 0, sig = 0; for (int i = 2000; i < y.size() - 100; i++) { err += (y.get(i) - x[i]) * (y.get(i) - x[i]); sig += x[i] * x[i]; }
        check(err == 0, String.format("16 kHz to 16 kHz, noise passes unchanged: output matches input (error %.0f)", err));
        System.out.println(fails == 0 ? "RESAMPLER OK" : fails + " FAILURES"); System.exit(fails == 0 ? 0 : 1);
    }
}
