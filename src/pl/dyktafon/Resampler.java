package pl.dyktafon;

/** Converts mono audio of any sample rate to another, up or down, with a windowed-sinc filter that also keeps out aliasing. */
final class Resampler {
    interface Sink { void sample(double v) throws Exception; }
    private static final int PHASES = 128;
    private final int taps, half; private final double[][] table; private final double[] buf;
    private final double step; private double t; private long got;
    private final Sink sink; private final boolean same;

    Resampler(int rate, int outRate, Sink sink) {
        this.sink = sink; step = rate / (double) outRate; same = rate == outRate;
        // Cutoff below both Nyquist limits, as a fraction of the input rate. The Blackman window's transition is about 5.5 / taps,
        // so 80 / fc taps make it about 7% of the cutoff either side: full level to about 6.9 kHz and nothing past 7.7 kHz for a 16 kHz output,
        // and full level to about 3.35 kHz from an 8 kHz source.
        double fc = 0.45 * Math.min(rate, outRate) / rate;
        taps = same ? 2 : ((int) Math.ceil(80 / fc) + 1) & ~1; half = taps / 2;
        table = new double[PHASES + 1][taps]; buf = new double[2 * taps]; // each sample is stored twice, so a window is always one run
        for (int ph = 0; ph <= PHASES; ph++) {
            double frac = ph / (double) PHASES, sum = 0;
            for (int i = 0; i < taps; i++) {
                double m = i - (half - 1) - frac; // tap i holds input position base - (half - 1) + i, the output is at base + frac
                double w = Math.abs(m) >= half ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * m / half) + 0.08 * Math.cos(2 * Math.PI * m / half);
                double h = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
                table[ph][i] = h * w; sum += h * w;
            }
            for (int i = 0; i < taps; i++) table[ph][i] /= sum;
        }
    }

    /** One input sample. At the same rate it passes unchanged. Otherwise outputs come out about taps / 2 input samples later; output k is the input at time k / outRate. */
    void put(double v) throws Exception {
        if (same) { sink.sample(v); return; }
        int p = (int) (got % taps); buf[p] = v; buf[p + taps] = v; got++;
        while (true) {
            long base = (long) Math.floor(t);
            if (base + half > got - 1) break; // the newest sample this output needs has not arrived
            double[] c = table[(int) Math.round((t - base) * PHASES)];
            int s = (int) Math.floorMod(base - (half - 1), (long) taps); // positions before the start read the zeros the buffer began with
            double y = 0;
            for (int i = 0; i < taps; i++) y += c[i] * buf[s + i];
            sink.sample(y);
            t += step;
        }
    }
}
