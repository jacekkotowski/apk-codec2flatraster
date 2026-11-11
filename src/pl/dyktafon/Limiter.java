package pl.dyktafon;

/**
 * Look-ahead limiter. It lowers the level before a peak arrives instead of bending the waveform,
 * so it adds no clipping distortion. The output can never exceed the ceiling.
 */
final class Limiter {
    private final double ceiling, release; private final int L;
    private final double[] delay, need, avg; private int p; private double sum, g = 1;

    Limiter(double ceiling, int lookahead) {
        this.ceiling = ceiling; L = lookahead; release = 1 - Math.exp(-1 / (0.25 * 8000)); // about 250 ms back up
        delay = new double[L + 1]; need = new double[L + 1]; avg = new double[L + 1];
        java.util.Arrays.fill(need, 1); java.util.Arrays.fill(avg, 1); sum = L + 1;
    }

    double process(double in) {
        delay[p] = in; need[p] = Math.abs(in) > ceiling ? ceiling / Math.abs(in) : 1;
        double m = 1; for (double v : need) if (v < m) m = v; // lowest gain any sample in the window requires
        sum += m - avg[p]; avg[p] = m;
        double smooth = sum / (L + 1); // every value averaged is at or below what the delayed sample needs
        g = Math.min(smooth, g + release * (1 - g));
        p = (p + 1) % (L + 1);
        double y = delay[p] * g; // p now points at the oldest sample
        return Math.max(-ceiling, Math.min(ceiling, y));
    }
}
