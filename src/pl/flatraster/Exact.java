package pl.flatraster;

/** The exact picture coder in jni/flatcode.c (the prototype of tools/coder/flatcoder.py): the filter's posterized picture is sent without loss.
 *  One instance codes, or decodes, one picture stream: it remembers the last picture, which every delta picture is coded against. */
final class Exact implements java.io.Closeable {
    static { System.loadLibrary("flat"); }
    static final int KEY_FLAG = 1 << 30;
    private static native long create(int w, int h);
    private static native void destroy(long h);
    /** Codes the filter's present picture (flat is a Flat handle). Returns the coded length, plus KEY_FLAG for a key picture, or -1. */
    private static native int encode(long h, long flat, boolean forceKey, byte[] out);
    /** As encode, a key picture whose background (mask byte 0) is a coarse colour map of block x block squares; mask holds w*h bytes, 1 = person. */
    private static native int encodeMasked(long h, long flat, byte[] mask, int block, byte[] out);
    /** Decodes one packet into argb (w*h). Returns 0 or -1. */
    private static native int decode(long h, boolean key, byte[] data, int[] argb);

    final int w, h;
    private long handle;
    private final byte[] buf;

    Exact(int w, int h) {
        this.w = w; this.h = h;
        handle = create(w, h);
        if (handle == 0) throw new IllegalStateException("not enough memory for the picture coder");
        buf = new byte[w * h * 3 + 4096];
    }

    /** Result of encode(): the packet type (FrvWriter.KEY or DELTA) and its bytes. */
    static final class Coded { final int type; final byte[] data; Coded(int type, byte[] data) { this.type = type; this.data = data; } }

    Coded encode(long flat, boolean forceKey) {
        int r = encode(handle, flat, forceKey, buf);
        if (r < 0) return null;
        return new Coded((r & KEY_FLAG) != 0 ? FrvWriter.KEY : FrvWriter.DELTA, java.util.Arrays.copyOf(buf, r & ~KEY_FLAG));
    }

    /** A key picture with the person kept as the filter made it and the background reduced to a coarse colour map. */
    Coded encodeMasked(long flat, byte[] mask, int block) {
        int r = encodeMasked(handle, flat, mask, block, buf);
        return r < 0 ? null : new Coded(FrvWriter.KEY, java.util.Arrays.copyOf(buf, r & ~KEY_FLAG));
    }

    /** Decodes a KEY or DELTA packet into argb; false if it can't be decoded (damaged, or a delta before any key picture). */
    boolean decode(int type, byte[] data, int[] argb) { return decode(handle, type == FrvWriter.KEY, data, argb) == 0; }

    @Override public void close() { if (handle != 0) { destroy(handle); handle = 0; } }
}
