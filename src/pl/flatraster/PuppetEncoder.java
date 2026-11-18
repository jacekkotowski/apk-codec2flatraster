package pl.flatraster;

import android.content.Context;
import android.media.Image;

/** Records the "puppet" stream: a posterized key picture (the Exact coder) at the start and every keySeconds, and between them only the
 *  landmark points of the face and the upper body, found by MediaPipe's neural models on the camera frame, coded with the point coder.
 *  See FrvWriter for the packet layout. The receiver draws the key picture faded with the puppet over it. */
final class PuppetEncoder implements PictureEncoder, CameraAware {
    /** Coordinates are integer units of width / UNITS across the picture, in both directions. */
    static final int UNITS = PuppetDraw.UNITS;
    private final Exact ex;
    private final Points points = new Points(PuppetDraw.SIZES);
    private final Landmarks marks;
    private final PictureEncoder.Sink sink;
    private final long flat, keyMs;
    private final int w, h;
    private long lastKeyMs = -1;
    private boolean forceKey = true;
    private Landmarks.Result last = new Landmarks.Result();
    private int pointsBytes, keyBytes, pictures;
    private byte[] keyGrey; private int keyGw, keyGh; private int[] keyBox;      // the key frame's grey picture and the person's box in it (grey pixels), for the background shift
    private volatile String keyNote = "no key picture yet", pointsNote = "no frame yet";

    PuppetEncoder(Context ctx, long flat, int w, int h, int keySeconds, PictureEncoder.Sink sink) {
        this.flat = flat; this.w = w; this.h = h; this.sink = sink; this.keyMs = Math.max(1, keySeconds) * 1000L;
        ex = new Exact(w, h);
        try { marks = new Landmarks(ctx); } catch (RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            ex.close(); points.close();
            throw new IllegalStateException("The puppet models can't start on this phone (" + e.getMessage() + "). They need a 64-bit ARM phone.");
        }
    }

    @Override public int codec() { return FrvWriter.PUPPET; }
    @Override public String describe() { return "Puppet " + w + "x" + h + ", " + keyNote + ", " + pointsNote + ", " + pictures + " pictures, face " + (last.face != null ? "found" : "not found") + ", body " + (last.body != null ? "found" : "not found"); }

    @Override public void cameraFrame(Image img, int rotation, boolean mirror, long timeMs) {
        boolean keyDue = forceKey || lastKeyMs < 0 || timeMs - lastKeyMs >= keyMs;      // the person mask is only needed for a key picture
        try { last = marks.detect(img, rotation, mirror, timeMs, keyDue); pointsNote = "points ok"; }
        catch (Throwable e) { last = new Landmarks.Result(); pointsNote = "POINTS FAILED: " + e; }   // a frame the models fail on counts as "nobody found", and the status line says why
    }

    @Override public void encode(byte[] i420, long timeMs) throws java.io.IOException {
        if (forceKey || lastKeyMs < 0 || timeMs - lastKeyMs >= keyMs) {
            Exact.Coded c = null;
            if (last.mask != null) c = ex.encodeMasked(flat, pictureMask(last), Math.max(4, w / 20));   // the person as seen, the background as a coarse colour map
            keyNote = c != null ? "key with cut-out (" + last.maskNote + ")" : "key WITHOUT cut-out: " + last.maskNote;
            if (c == null) c = ex.encode(flat, true);
            if (c == null) throw new java.io.IOException("the key picture could not be coded");
            sink.video(FrvWriter.KEY, timeMs, c.data);
            keyBytes += c.data.length; lastKeyMs = timeMs; forceKey = false;
            points.reset();
            keyGrey = last.grey; keyGw = last.gw; keyGh = last.gh; keyBox = personBox(last, last.gw, last.gh);
        }
        int[] present = {last.face != null ? 1 : 0, last.body != null ? 1 : 0, 1};
        int[] units = new int[PuppetDraw.SIZES[0] * 2 + PuppetDraw.SIZES[1] * 2 + PuppetDraw.SIZES[2] * 2];
        int u = 0;
        u = put(units, u, last.face);
        u = put(units, u, last.body);
        float[] sh = backgroundShift();                                   // grey pixels the background moved since the key picture
        units[u++] = PuppetDraw.CAM_ZERO + Math.max(-500, Math.min(500, Math.round(sh[0] * UNITS / Math.max(1, last.gw))));
        units[u++] = PuppetDraw.CAM_ZERO + Math.max(-500, Math.min(500, Math.round(sh[1] * UNITS / Math.max(1, last.gw))));
        byte[] b = points.encode(present, units.length == 0 ? new int[2] : java.util.Arrays.copyOf(units, Math.max(2, u)));
        if (b == null) throw new java.io.IOException("the points could not be coded");
        sink.video(FrvWriter.POINTS, timeMs, b);
        pointsBytes += b.length; pictures++;
    }

    /** The person's box (grey pixels, widened by 20%) from the face and body points, or null if there are none. */
    private static int[] personBox(Landmarks.Result r, int gw, int gh) {
        float x0 = 9, y0 = 9, x1 = -9, y1 = -9;
        for (float[] a : new float[][]{r.face, r.body}) if (a != null) for (int i = 0; i < a.length; i += 2) { x0 = Math.min(x0, a[i]); x1 = Math.max(x1, a[i]); y0 = Math.min(y0, a[i + 1]); y1 = Math.max(y1, a[i + 1]); }
        if (x1 < x0) return null;
        float mx = 0.2f * (x1 - x0), my = 0.2f * (y1 - y0);
        return new int[]{Math.max(0, (int) ((x0 - mx) * gw)), Math.max(0, (int) ((y0 - my) * gh)), Math.min(gw, (int) Math.ceil((x1 + mx) * gw)), Math.min(gh, (int) Math.ceil((y1 + my) * gh))};
    }

    /** How far the background has moved since the key picture's frame, in grey pixels. */
    private float[] backgroundShift() {
        if (keyGrey == null || last.grey == null || last.gw != keyGw || last.gh != keyGh) return new float[]{0, 0};
        int[] a = keyBox, b = personBox(last, last.gw, last.gh), box = a;
        if (a != null && b != null) box = new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])}; else if (b != null) box = b;
        return ShiftEstimator.estimate(keyGrey, last.grey, last.gw, last.gh, box, 10);
    }

    /** The models' mask (mw x mh) at the picture's size, nearest pixel. */
    private byte[] pictureMask(Landmarks.Result r) {
        byte[] m = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) m[y * w + x] = r.mask[Math.min(r.mh - 1, y * r.mh / h) * r.mw + Math.min(r.mw - 1, x * r.mw / w)];
        return m;
    }

    private int put(int[] units, int u, float[] p) {
        if (p == null) return u;
        for (int i = 0; i < p.length; i += 2) {                      // picture fractions to units: x * UNITS, y * UNITS * h / w
            units[u++] = Math.max(0, Math.min(1023, (int) Math.rint(p[i] * UNITS)));
            units[u++] = Math.max(0, Math.min(1023, (int) Math.rint(p[i + 1] * UNITS * h / w)));
        }
        return u;
    }

    /** Ignored: the filter asks for a key picture whenever it rebuilds its palette (so a video encoder can start afresh), but every Puppet key picture
     *  carries its own palette and the points do not depend on it, so only the "Full picture every" interval makes key pictures (0.19 and 0.20 sent one
     *  about every 5 s because of this: 4 of the 5 keys in the owner's recordings). */
    @Override public void requestKeyFrame() { }
    @Override public void close() { marks.close(); points.close(); ex.close(); }
}
