package pl.dyktafon;

/** Soft-knee compressor: evens out loud and quiet speech before the limiter, with attack 10 ms and release 150 ms. */
final class Compressor {
    private static final double T_DB = 20 * Math.log10(2400), KNEE = 6;
    private final double slope, makeup, att = 1 - Math.exp(-1 / (8000 * 0.010)), rel = 1 - Math.exp(-1 / (8000 * 0.150));
    private double e2;

    Compressor(double ratio) { slope = ratio <= 1 ? 0 : 1 - 1 / ratio; makeup = 1; }

    double process(double y) {
        if (slope == 0) return y;
        double p = y * y; e2 += (p > e2 ? att : rel) * (p - e2);
        double over = 10 * Math.log10(e2 + 1) - T_DB, gr;
        if (over <= -KNEE / 2) gr = 0;
        else if (over >= KNEE / 2) gr = slope * over;
        else { double k = over + KNEE / 2; gr = slope * k * k / (2 * KNEE); }
        return y * makeup * Math.pow(10, -gr / 20);
    }
}
