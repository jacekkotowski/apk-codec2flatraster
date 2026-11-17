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
    /** A camera frame between two pictures (same frame arguments as process): the last six are kept, and the next process() joins them
     *  into its picture: it starts from the sharpest and averages the others in where they agree. Returns -1 if the frame was refused. */
    static native int gather(long h, ByteBuffer y, int yStride, ByteBuffer u, ByteBuffer v, int uvStride, int uvPixelStride,
                             int srcW, int srcH, int rotation, boolean mirror);
    /** Holds the face still: the picture is a window of the camera frame that follows the face, and the focus box follows the window. Call
     *  before every process, gather and track, with the camera's face rectangle (centre and size in camera-frame pixels; cx < 0: no
     *  face seen). zoom: 125 makes the window 80% of the frame. alpha: thousandths of the way the smoothed face position moves per call.
     *  dead: camera pixels the face may move before a detector-driven window follows (0 always). box: thousandths of the face's size
     *  the box extends beyond it on every side, or -1 to leave the boxes to setFocus (whose level is used either way). See jni/flat.h. */
    static native void setHold(long h, int cx, int cy, int faceW, int faceH, int zoom, int alpha, int dead, int box);
    /** Measures the face's movement since the previous camera frame from the brightness plane, so the window does not follow the face
     *  detector's trembling. Call after setHold for every camera frame. */
    static native void track(long h, ByteBuffer y, int yStride, int srcW, int srcH);
    /** Forgets the gathered frames, for when they have gone stale. */
    static native void clearGathered(long h);
    /** What the last process() joined: frames gathered | which it started from (0 the newest) << 8 | the noise limit it used << 16. */
    static native int gatherInfo(long h);
    /** Enlarges a picture of w x h for the screen, `factor` times, with smooth borders between the colours instead of blocks (jni/smooth.c).
     *  tol: 0 for an exact posterized picture, about 40 for one decoded from video. dst holds w*factor * h*factor ints. */
    static native void upscale(int[] src, int w, int h, int factor, int tol, int[] dst);
    /** How many times the smooth enlarger makes a picture bigger: to about 1200 pixels on its long side, which a phone screen then
     *  scales a little further by itself. */
    static int viewFactor(int w, int h) { return Math.max(2, Math.min(6, Math.round(1200f / Math.max(w, h)))); }
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
