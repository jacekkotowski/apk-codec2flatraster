package pl.flatraster;

/** The landmark point coder in jni/motion.c (byte-identical with tools/edge/motion.py). Objects of n[o] points; coordinates are integer units;
 *  one instance codes, or decodes, one stream of pictures: reset() at every key picture on both sides. */
final class Points implements java.io.Closeable {
    static { System.loadLibrary("flat"); }
    private static native long create(int[] n, int alpha);
    private static native void destroy(long h);
    private static native void reset(long h);
    private static native int encode(long h, int[] present, int[] units, byte[] out);
    private static native int decode(long h, byte[] data, int len, int[] present, int[] units);

    final int objects, points;
    private final int[] sizes;
    private long handle;
    private final byte[] buf = new byte[4096];

    Points(int[] sizes) {
        this.sizes = sizes.clone(); objects = sizes.length;
        int t = 0; for (int s : sizes) t += s; points = t;
        handle = create(sizes, 0);
        if (handle == 0) throw new IllegalStateException("could not make the point coder");
    }

    void reset() { reset(handle); }

    /** present[o]: object o is in this picture; units: the points of the present objects in order (x, y each). Returns the coded bytes, or null. */
    byte[] encode(int[] present, int[] units) {
        int n = encode(handle, present, units, buf);
        return n < 0 ? null : java.util.Arrays.copyOf(buf, n);
    }

    /** Decodes into present (length = objects) and units (length at least points * 2; only the present objects' points are filled, in order). */
    boolean decode(byte[] data, int[] present, int[] units) { return units.length >= points * 2 && decode(handle, data, data.length, present, units) == 0; }

    @Override public void close() { if (handle != 0) { destroy(handle); handle = 0; } }
}
