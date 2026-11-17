package pl.flatraster;

/** Records the filter's pictures with the exact coder instead of a phone video encoder. Same use as VideoEncoder. */
final class ExactEncoder implements PictureEncoder {
    private final Exact ex;
    private final PictureEncoder.Sink sink;
    private final long flat;
    private final long keyMs;
    private long lastKeyMs = -1;
    private boolean forceKey = true;

    /** flat: the Flat handle whose picture is coded at each encode(); keySeconds: a full picture at least this often. */
    ExactEncoder(long flat, int w, int h, int keySeconds, PictureEncoder.Sink sink) {
        this.flat = flat; this.sink = sink; this.keyMs = Math.max(1, keySeconds) * 1000L;
        ex = new Exact(w, h);
    }

    @Override public int codec() { return FrvWriter.EXACT; }
    @Override public String describe() { return "Exact " + ex.w + "x" + ex.h + " (lossless, FlatRaster only)"; }

    @Override public void encode(byte[] i420, long timeMs) throws java.io.IOException {
        boolean key = forceKey || lastKeyMs < 0 || timeMs - lastKeyMs >= keyMs;
        Exact.Coded c = ex.encode(flat, key);
        if (c == null) throw new java.io.IOException("the picture could not be coded (more than 32 colours?)");
        if (c.type == FrvWriter.KEY) { lastKeyMs = timeMs; forceKey = false; }
        sink.video(c.type, timeMs, c.data);
    }

    @Override public void requestKeyFrame() { forceKey = true; }
    @Override public void close() { ex.close(); }
}
