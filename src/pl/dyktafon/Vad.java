package pl.dyktafon;

/**
 * Voice activation from speech-band level relative to a self-calibrating background:
 * the background is a low percentile of the last 8 s, which also follows fans and room noise.
 */
final class Vad {
    private final double[] ring; private int w = 0, count = 0, hold = 0, onRun = 0, tick = 0;
    private final int needFrames, frameMs; private double floor = 0, fast = -999;
    boolean active = false;
    /** dB above background, for the meter; 0..1 is 0..24 dB. */
    double meter = 0, aboveDb = 0;

    Vad(int frameMs) { this.frameMs = frameMs; ring = new double[Math.max(20, 8000 / frameMs)]; needFrames = Math.max(1, 80 / frameMs); }

    /** Sensitivity 1 needs 18 dB above background, 10 needs 2 dB. */
    static double thresholdDb(int s) { return 20 - 1.8 * Math.max(1, Math.min(10, s)); }

    boolean update(double bandDb, int sensitivity, double holdSeconds) {
        ring[w] = bandDb; w = (w + 1) % ring.length; if (count < ring.length) count++;
        if (tick++ % 5 == 0 || count < 10) {
            double[] c = java.util.Arrays.copyOf(ring, count); java.util.Arrays.sort(c);
            floor = c[(int) (count * 0.15)];
        }
        fast = fast < -900 ? bandDb : 0.5 * fast + 0.5 * bandDb;
        double above = fast - floor, th = thresholdDb(sensitivity);
        aboveDb = above;
        meter = Math.max(0, Math.min(1, above / 24));
        if (count * frameMs < 700) return false; // too little to judge the background
        onRun = above > th ? onRun + 1 : 0;
        int holdFrames = (int) (holdSeconds * 1000 / frameMs);
        if (onRun >= needFrames || (active && above > th - 3 && hold > holdFrames / 2)) { active = true; hold = holdFrames; }
        else if (hold > 0) hold--;
        else active = false;
        return active;
    }
}
