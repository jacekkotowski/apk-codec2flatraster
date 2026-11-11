package pl.flatraster;

import java.nio.ByteBuffer;

/** The posterize filter in jni/flat.c. */
final class Flat {
    static { System.loadLibrary("flat"); }
    static native long create(int w, int h, int colors);
    static native void destroy(long h);
    static native void reset(long h);
    /** i420 must hold w*h*3/2 bytes and argb w*h ints. Returns 1 if the palette was rebuilt, -1 if the frame was refused. */
    static native int process(long h, ByteBuffer y, int yStride, ByteBuffer u, ByteBuffer v, int uvStride, int uvPixelStride,
                              int srcW, int srcH, int rotation, boolean mirror, int smooth, int hold, int clean, int ignore, byte[] i420, int[] argb);
    /** Pixels that differ from the last picture marked as sent. */
    static native int changed(long h);
    static native void markSent(long h);
    /** 0: colours locked between rebuilds. Above 0 with mode 2: a brand-new palette on every picture, and each new colour within the threshold of a colour shown in the previous picture is shown as that previous colour. Modes 0 and 1 refit from the previous palette instead (gliding or completely). */
    static native void setTrack(long h, int threshold, int mode);
    /** Border rounding passes, 0..2. */
    static native void setRound(long h, int passes);
    /** Up to 4 boxes of x0,y0,x1,y1 in camera-frame pixels. coarse 1 or 2 gives everything outside them a half or a quarter of the colours; 0 switches it off. */
    static native void setFocus(long h, int[] rects, int count, int coarse);
}
