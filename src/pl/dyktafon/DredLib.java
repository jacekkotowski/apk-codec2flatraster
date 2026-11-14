package pl.dyktafon;

/** The DRED coder and player in libdred.so (app/jni/dredjni.c). One block is one second: 50 frames of 320 samples at 16 kHz. */
final class DredLib {
    static { System.loadLibrary("dred"); }
    static native int bitstream();
    private static native long encOpen(int q);
    private static native int encFrame(long h, short[] pcm, byte[] out);
    private static native int encPending(long h);
    private static native void encClose(long h);
    private static native long decOpen();
    private static native int decBlock(long h, byte[] in, int n, short[] out);
    private static native void decClose(long h);

    interface BlockSink { void block(byte[] b, int n) throws java.io.IOException; }

    /** Native code uses more stack than Android gives a thread by default, so coding and playing run on threads made with this. */
    static Thread thread(Runnable r, String name) { return new Thread(null, r, name, 4 << 20); }

    static final class Coder implements AutoCloseable {
        private long h; private boolean fed;
        final byte[] buf = new byte[Dred.MAX_BLOCK];
        private final short[] zero = new short[Dred.FRAME];

        Coder(int q) {
            if (bitstream() != Dred.BITSTREAM) throw new IllegalStateException("the DRED library is version " + bitstream() + ", the file format expects " + Dred.BITSTREAM);
            h = encOpen(q);
            if (h == 0) throw new IllegalStateException("the DRED coder did not start");
        }
        /** Codes one 20 ms frame. Returns the length of a finished block (its bytes are in buf), or -1. */
        int frame(short[] pcm) { fed = true; return encFrame(h, pcm, buf); }
        /** After the last frame: silence until the block is complete, at least two frames, so the coder's look-ahead lets the last speech through. */
        void finish(BlockSink sink) throws java.io.IOException {
            if (!fed) return;
            for (int tail = 0; tail < 2 || encPending(h) != 0; tail++) { int n = encFrame(h, zero, buf); if (n >= 0) sink.block(buf, n); }
            fed = false;
        }
        @Override public void close() { if (h != 0) encClose(h); h = 0; }
    }

    static final class Player implements AutoCloseable {
        private long h;
        final short[] pcm = new short[Dred.BLOCK_SAMPLES];
        Player() { h = decOpen(); if (h == 0) throw new IllegalStateException("the DRED player did not start"); }
        /** Plays one block of n bytes into pcm. Returns the number of samples. */
        int block(byte[] b, int n) {
            int r = decBlock(h, b, n, pcm);
            if (r < 0) throw new IllegalStateException("the DRED player failed (" + r + ")");
            return r;
        }
        @Override public void close() { if (h != 0) decClose(h); h = 0; }
    }
}
