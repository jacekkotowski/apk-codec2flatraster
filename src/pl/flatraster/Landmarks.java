package pl.flatraster;

import android.content.Context;
import android.media.Image;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.ByteBufferImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import java.nio.ByteBuffer;
import java.util.List;

/** Face and body landmarks from the camera frame with MediaPipe's two models (face_landmarker.task, pose_landmarker_lite.task in assets).
 *  The frame is rotated and mirrored the way the filter makes its picture, so the points land in the picture's own coordinates.
 *  Models run on the phone's processor; nothing leaves the phone (MediaPipe's statistics are switched off, see TasksStatsLoggerFactory).
 *  NOT yet run on a phone. */
final class Landmarks implements java.io.Closeable {
    /** The 22 points of MediaPipe's 478-point face mesh that are kept (tools/edge/marks.py FACE_IDX; the order PuppetDraw expects). */
    static final int[] FACE_IDX = {33, 133, 362, 263, 159, 145, 386, 374, 70, 105, 300, 334, 61, 291, 13, 14, 0, 17, 1, 152, 234, 454};
    static final int SIZE = 320;           // long side of the frame given to the models
    private final FaceLandmarker face;
    private final PoseLandmarker pose;
    private long lastTs = -1;
    private byte[] rgba = new byte[0];

    /** Points of one frame in picture fractions (x, y in 0..1): face = 44 floats, body = 16 floats; null when not found. */
    static final class Result { float[] face, body; byte[] mask; int mw, mh; /* person mask at the size given to the models: 1 = person */ String maskNote = "pose model gave no mask"; }

    Landmarks(Context ctx) {
        face = FaceLandmarker.createFromOptions(ctx, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO).setNumFaces(1).build());
        pose = PoseLandmarker.createFromOptions(ctx, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.VIDEO).setNumPoses(1).setOutputSegmentationMasks(true).build());
    }

    Result detect(Image img, int rotation, boolean mirror, long timeMs) {
        int sw = img.getWidth(), sh = img.getHeight();
        boolean turned = rotation % 180 != 0;
        int pw = turned ? sh : sw, ph = turned ? sw : sh;
        float s = (float) SIZE / Math.max(pw, ph);
        int ow = Math.max(2, Math.round(pw * s)), oh = Math.max(2, Math.round(ph * s));
        if (rgba.length != ow * oh * 4) rgba = new byte[ow * oh * 4];
        toRgba(img.getPlanes(), sw, sh, rotation, mirror, ow, oh, rgba);
        ByteBuffer bb = ByteBuffer.allocateDirect(rgba.length); bb.put(rgba); bb.rewind();
        MPImage mp = new ByteBufferImageBuilder(bb, ow, oh, MPImage.IMAGE_FORMAT_RGBA).build();
        long ts = Math.max(lastTs + 1, timeMs); lastTs = ts;            // MediaPipe wants strictly increasing times
        Result r = new Result();
        FaceLandmarkerResult fr = face.detectForVideo(mp, ts);
        if (!fr.faceLandmarks().isEmpty()) {
            List<NormalizedLandmark> l = fr.faceLandmarks().get(0);
            r.face = new float[FACE_IDX.length * 2];
            for (int i = 0; i < FACE_IDX.length; i++) { r.face[2 * i] = l.get(FACE_IDX[i]).x(); r.face[2 * i + 1] = l.get(FACE_IDX[i]).y(); }
        }
        PoseLandmarkerResult pr = pose.detectForVideo(mp, ts);
        if (!pr.landmarks().isEmpty()) {
            List<NormalizedLandmark> l = pr.landmarks().get(0);
            r.body = new float[PuppetDraw.BODY * 2];
            for (int i = 0; i < PuppetDraw.BODY; i++) { NormalizedLandmark q = l.get(PuppetDraw.BODY_IDX[i]); r.body[2 * i] = q.x(); r.body[2 * i + 1] = q.y(); }
        }
        if (pr.segmentationMasks().isPresent() && !pr.segmentationMasks().get().isEmpty()) {
            try {
                MPImage m = pr.segmentationMasks().get().get(0);
                ByteBuffer fb = ByteBufferExtractor.extract(m);
                int mw = m.getWidth(), mh = m.getHeight();
                r.mask = new byte[mw * mh]; r.mw = mw; r.mh = mh;
                fb.rewind();
                if (fb.remaining() >= mw * mh * 4) { java.nio.FloatBuffer f = fb.asFloatBuffer(); for (int i = 0; i < mw * mh; i++) r.mask[i] = (byte) (f.get(i) > 0.5f ? 1 : 0); }
                else if (fb.remaining() >= mw * mh) for (int i = 0; i < mw * mh; i++) r.mask[i] = (byte) ((fb.get(i) & 255) > 127 ? 1 : 0);
                else { r.mask = null; r.maskNote = "mask in an unknown format (" + fb.remaining() + " bytes for " + mw + "x" + mh + ")"; }
                if (r.mask != null) r.maskNote = "person mask " + mw + "x" + mh;
            } catch (RuntimeException e) { r.mask = null; r.maskNote = "mask failed: " + e; }          // no mask: the key picture is then the whole filter picture
        }
        return r;
    }

    /** Camera planes to RGBA in the picture's orientation: rotation clockwise (0, 90, 180, 270), then mirrored left to right (as flat.c does). */
    static void toRgba(Image.Plane[] pl, int sw, int sh, int rotation, boolean mirror, int ow, int oh, byte[] out) {
        boolean turned = rotation % 180 != 0;
        int pw = turned ? sh : sw, ph = turned ? sw : sh;
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int ys = pl[0].getRowStride(), yp = pl[0].getPixelStride(), us = pl[1].getRowStride(), up = pl[1].getPixelStride(), vs = pl[2].getRowStride(), vp = pl[2].getPixelStride();
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
            int X = Math.min(pw - 1, (int) ((mirror ? ow - 1 - x : x) * (long) pw / ow)), Y = Math.min(ph - 1, (int) (y * (long) ph / oh));
            int sx, sy;
            switch (rotation) {
                case 90: sx = Y; sy = sh - 1 - X; break;
                case 180: sx = sw - 1 - X; sy = sh - 1 - Y; break;
                case 270: sx = sw - 1 - Y; sy = X; break;
                default: sx = X; sy = Y;
            }
            int yy = 298 * ((yb.get(sy * ys + sx * yp) & 255) - 16);
            int u = (ub.get((sy / 2) * us + (sx / 2) * up) & 255) - 128, v = (vb.get((sy / 2) * vs + (sx / 2) * vp) & 255) - 128;
            int r = (yy + 409 * v + 128) >> 8, g = (yy - 100 * u - 208 * v + 128) >> 8, b = (yy + 516 * u + 128) >> 8;
            int o = (y * ow + x) * 4;
            out[o] = (byte) (r < 0 ? 0 : r > 255 ? 255 : r); out[o + 1] = (byte) (g < 0 ? 0 : g > 255 ? 255 : g); out[o + 2] = (byte) (b < 0 ? 0 : b > 255 ? 255 : b); out[o + 3] = (byte) 255;
        }
    }

    @Override public void close() { face.close(); pose.close(); }
}
