package pl.dyktafon;

/**
 * Noise reduction at 8 kHz for weak speech.
 * Noise per band comes from an optional measured sample plus continuous minimum tracking, so it follows changing noise.
 * Gain is a decision-directed Wiener filter, smoothed across bands to avoid musical noise.
 * The same estimate gives a likelihood-ratio speech score for voice activation.
 */
final class Denoiser {
    static final int N = 256, HOP = 128, BINS = N / 2 + 1;
    private static final int WINDOW = 31, WINDOWS = 4; // about 2 s of minimum tracking
    private final double[] win = new double[N], ring = new double[N], ola = new double[N], re = new double[N], im = new double[N];
    private final double[] S = new double[BINS], Smin = new double[BINS], Stmp = new double[BINS], noise = new double[BINS], prevClean = new double[BINS], g = new double[BINS], g2 = new double[BINS];
    private final double[][] hist = new double[WINDOWS][BINS];
    private final double[] outQ; private int outR, outW, fill, hIdx; private long hops;
    private final double[] llr = new double[BINS];
    private double[] profile;
    private double[] sampleSum; private int sampleLeft, sampleCount;
    private double scoreSum; private int scoreN; private double lastScore, bandSum, lastBand; private int bandN;
    /** When false the audio passes through unchanged, but noise tracking and the speech score still run. */
    boolean reduce = true;
    /** Lowest gain: 0.3 is gentle, 0.1 is strong. Higher values keep more noise and more of quiet consonants. */
    double floor = 0.18;

    Denoiser(int maxBlock, double[] noiseProfile) {
        for (int i = 0; i < N; i++) win[i] = Math.sin(Math.PI * (i + 0.5) / N);
        outQ = new double[Math.max(4 * N, 4 * maxBlock)];
        outW = N; // one window of silence is the filter's delay (32 ms)
        java.util.Arrays.fill(Smin, Double.MAX_VALUE); java.util.Arrays.fill(Stmp, Double.MAX_VALUE);
        for (double[] h : hist) java.util.Arrays.fill(h, Double.MAX_VALUE);
        if (noiseProfile != null && noiseProfile.length == BINS) { profile = noiseProfile.clone(); System.arraycopy(profile, 0, noise, 0, BINS); }
    }

    /** Measure noise for the next seconds; speech must not be present. */
    void startSample(double seconds) { sampleSum = new double[BINS]; sampleCount = 0; sampleLeft = (int) (seconds * 8000 / HOP); }
    boolean sampling() { return sampleSum != null; }
    double[] profile() { return profile; }

    /** Average speech score since the last call; about 0 for noise, larger for speech. */
    double takeScore() { if (scoreN > 0) { lastScore = scoreSum / scoreN; scoreSum = 0; scoreN = 0; } return lastScore; }

    /** Speech-band level in dB since the last call. */
    double takeBandDb() { if (bandN > 0) { lastBand = 10 * Math.log10(bandSum / bandN + 1); bandSum = 0; bandN = 0; } return lastBand; }

    void process(double[] x, int n) {
        for (int i = 0; i < n; i++) {
            System.arraycopy(ring, 1, ring, 0, N - 1);
            ring[N - 1] = x[i];
            if (++fill >= HOP) { fill = 0; hop(); }
            x[i] = outQ[outR]; outQ[outR] = 0; outR = (outR + 1) % outQ.length;
        }
    }

    private void hop() {
        hops++;
        for (int i = 0; i < N; i++) { re[i] = ring[i] * win[i]; im[i] = 0; }
        fft(re, im, false);
        double band = 0;
        boolean windowEnd = hops > 12 && hops % WINDOW == 0;
        double score = 0; int scored = 0;
        for (int k = 0; k < BINS; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            S[k] = hops == 1 ? p : 0.8 * S[k] + 0.2 * p;
            if (hops > 12) { // the first windows are still filling and read too low
                if (S[k] < Stmp[k]) Stmp[k] = S[k];
                if (S[k] < Smin[k]) Smin[k] = S[k];
            }
            if (sampleSum != null) sampleSum[k] += p;
            double track = 2.5 * Smin[k]; // smoothed minima sit below the mean power
            double target;
            if (Smin[k] == Double.MAX_VALUE) target = profile != null ? profile[k] : S[k];
            else if (profile != null) {
                if (track < profile[k]) profile[k] += 0.0003 * (track - profile[k]); // quieter noise: let go slowly
                target = Math.max(profile[k], track); // louder noise: follow at once
            } else target = track;
            noise[k] = hops <= 12 && profile == null ? target : 0.9 * noise[k] + 0.1 * target;
            double nn = noise[k] + 1e-6, post = p / nn;
            double prio = 0.98 * prevClean[k] / nn + 0.02 * Math.max(post - 1, 0);
            double raw = Math.max(k >= 64 ? Math.min(1, floor * 1.6) : floor, prio / (prio + 1.25)); // above 2 kHz keep more: consonants are weak there
            prevClean[k] = raw * raw * p;
            // Open at once, close slowly (about 100 ms), so word endings are not cut off.
            g[k] = Math.max(raw, g[k] * 0.86);
            if (k >= 10 && k <= 108) { // 300 to 3400 Hz
                band += p;
                llr[scored++] = Math.min(post, 50) * prio / (1 + prio) - Math.log(1 + prio);
            }
        }
        if (windowEnd) {
            double[] h = hist[hIdx]; System.arraycopy(Stmp, 0, h, 0, BINS); hIdx = (hIdx + 1) % WINDOWS;
            for (int k = 0; k < BINS; k++) { double m = Double.MAX_VALUE; for (double[] w : hist) if (w[k] < m) m = w[k]; Smin[k] = m; Stmp[k] = Double.MAX_VALUE; }
        }
        java.util.Arrays.sort(llr, 0, scored); // voiced speech fills only some bands, so use the strongest quarter
        for (int k = scored - scored / 4; k < scored; k++) score += llr[k];
        scoreSum += score / (scored / 4); scoreN++;
        bandSum += band / 99; bandN++;
        if (sampleSum != null) {
            sampleCount++;
            if (--sampleLeft <= 0) {
                double[] pr = new double[BINS];
                for (int k = 0; k < BINS; k++) pr[k] = sampleSum[k] / sampleCount;
                profile = pr; sampleSum = null;
                for (int k = 0; k < BINS; k++) noise[k] = pr[k];
            }
        }
        // Smoothing gain across neighbouring bands reduces musical noise.
        for (int k = 0; k < BINS; k++) g2[k] = (g[Math.max(0, k - 1)] + 2 * g[k] + g[Math.min(BINS - 1, k + 1)]) / 4;
        if (reduce) {
            for (int k = 0; k < BINS; k++) { re[k] *= g2[k]; im[k] *= g2[k]; if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; } }
        } else for (int k = 1; k < N / 2; k++) { re[N - k] = re[k]; im[N - k] = -im[k]; }
        fft(re, im, true);
        for (int i = 0; i < N; i++) ola[i] += re[i] * win[i];
        // Sine window squared at 50% overlap sums to 1.
        for (int i = 0; i < HOP; i++) outQ[(outW + i) % outQ.length] = ola[i];
        System.arraycopy(ola, HOP, ola, 0, N - HOP);
        java.util.Arrays.fill(ola, N - HOP, N, 0);
        outW = (outW + HOP) % outQ.length;
    }

    private static void fft(double[] a, double[] b, boolean inv) {
        int n = a.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = a[i]; a[i] = a[j]; a[j] = t; t = b[i]; b[i] = b[j]; b[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inv ? 1 : -1), wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    double xr = a[v] * cr - b[v] * ci, xi = a[v] * ci + b[v] * cr;
                    a[v] = a[u] - xr; b[v] = b[u] - xi; a[u] += xr; b[u] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inv) for (int i = 0; i < n; i++) { a[i] /= n; b[i] /= n; }
    }
}
