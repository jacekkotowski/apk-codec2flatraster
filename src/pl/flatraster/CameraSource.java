package pl.flatraster;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.graphics.Rect;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import java.util.Collections;

/** Opens a camera and hands YUV frames, at most `fps` per second, to a listener on its own thread. */
final class CameraSource {
    interface Listener {
        /** The image is closed after this returns. rotation: degrees clockwise to make the picture upright. */
        /** faces: boxes of x0,y0,x1,y1 in the image's own pixels, already widened to head and shoulders; empty if none. */
        void frame(Image img, int rotation, boolean mirror, long timeNs, int[] faces);
        void cameraError(String message);
    }

    private final HandlerThread thread = new HandlerThread("camera");
    private final Handler handler;
    private final Listener listener;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile double fps = 2;
    private volatile int deviceDegrees;
    private long lastNs;
    private int sensorDegrees;
    private boolean front;
    private volatile boolean closed;
    private CaptureRequest.Builder request;
    private CameraCaptureSession.CaptureCallback callback;
    private volatile boolean lock;
    /** True if this camera reports faces itself (most do). */
    volatile boolean faceSupported;
    private volatile int[] faces = new int[0];
    private long facesNs;
    private int activeW, activeH, imgW, imgH;

    CameraSource(Listener l) { listener = l; thread.start(); handler = new Handler(thread.getLooper()); }

    void setFps(double f) { fps = Math.max(0.2, Math.min(15, f)); }
    /** How far the phone is turned from its natural position: 0, 90, 180 or 270. */
    void setDeviceDegrees(int d) { deviceDegrees = d; }

    /** Needs the CAMERA permission. */
    void open(Context ctx, boolean wantFront) {
        handler.post(() -> {
            try {
                CameraManager cm = ctx.getSystemService(CameraManager.class);
                String pick = null;
                for (String id : cm.getCameraIdList()) {
                    Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                    if (pick == null || isFront == wantFront) { pick = id; front = isFront; if (isFront == wantFront) break; }
                }
                if (pick == null) { listener.cameraError("This phone reports no camera"); return; }
                CameraCharacteristics ch = cm.getCameraCharacteristics(pick);
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorDegrees = so == null ? 90 : so;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size best = null;
                if (map != null) for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    // The smallest size that still covers 640x480: less work, same result after downscaling.
                    if (s.getWidth() < 640 || s.getHeight() < 480) continue;
                    if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
                }
                if (best == null) best = new Size(640, 480);
                reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.YUV_420_888, 2);
                imgW = best.getWidth(); imgH = best.getHeight();
                // Face boxes come from the camera's own chip with each frame: no work for the app.
                Rect active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                int[] modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                boolean canFace = false;
                if (modes != null) for (int m : modes) canFace |= m == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE;
                if (active != null) { activeW = active.width(); activeH = active.height(); }
                final boolean useFace = faceSupported = canFace && active != null;
                reader.setOnImageAvailableListener(this::onImage, handler);
                // Lowest frame rate range the camera offers: longer exposures, less noise, less power.
                Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                Range<Integer> low = null;
                if (ranges != null) for (Range<Integer> r : ranges) if (low == null || r.getUpper() < low.getUpper() || (r.getUpper().equals(low.getUpper()) && r.getLower() < low.getLower())) low = r;
                final Range<Integer> fpsRange = low;
                cm.openCamera(pick, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) {
                        if (closed) { d.close(); return; }
                        device = d;
                        try {
                            final CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            rb.addTarget(reader.getSurface());
                            if (fpsRange != null) rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                            if (useFace) rb.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE);
                            d.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession s) {
                                    if (closed) return;
                                    session = s;
                                    request = rb;
                                    callback = !useFace ? null : new CameraCaptureSession.CaptureCallback() {
                                        @Override public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest rq, TotalCaptureResult res) { onFaces(res); }
                                    };
                                    try { applyLock(); }
                                    catch (CameraAccessException | IllegalStateException e) { listener.cameraError("The camera did not start: " + e.getMessage()); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) { listener.cameraError("The camera refused the picture settings"); }
                            }, handler);
                        } catch (CameraAccessException | IllegalStateException e) { listener.cameraError("The camera did not start: " + e.getMessage()); }
                    }
                    @Override public void onDisconnected(CameraDevice d) { d.close(); if (!closed) listener.cameraError("Another app took the camera"); }
                    @Override public void onError(CameraDevice d, int error) { d.close(); if (!closed) listener.cameraError("Camera error " + error); }
                }, handler);
            } catch (SecurityException e) { listener.cameraError("Camera permission is needed");
            } catch (Exception e) { listener.cameraError("The camera did not open: " + e.getMessage()); }
        });
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || closed) return;
            long now = System.nanoTime();
            if (now - lastNs < (long) (1e9 / fps) - 5_000_000L) return; // not due yet: drop the frame
            lastNs = now;
            int rot = front ? (sensorDegrees + deviceDegrees) % 360 : (sensorDegrees - deviceDegrees + 360) % 360;
            listener.frame(img, rot, false, now, faces); // front camera is not mirrored: the recording shows the scene as it is
        } catch (IllegalStateException ignored) {
        } finally { if (img != null) img.close(); }
    }

    /** Freezes exposure and white balance as they are now, or lets them follow the light again. With them frozen the
     *  colours of a still scene stop wandering, so the palette keeps fitting and borders stay put. */
    void setLock(boolean on) {
        lock = on;
        handler.post(() -> {
            if (session == null || closed) return;
            try { applyLock(); } catch (CameraAccessException | IllegalStateException e) { listener.cameraError("The camera refused the exposure lock: " + e.getMessage()); }
        });
    }

    private void applyLock() throws CameraAccessException {
        request.set(CaptureRequest.CONTROL_AE_LOCK, lock);
        request.set(CaptureRequest.CONTROL_AWB_LOCK, lock);
        session.setRepeatingRequest(request.build(), callback, handler);
    }

    /** Turns the camera's face boxes (sensor coordinates) into boxes in the image the app receives. */
    private void onFaces(TotalCaptureResult res) {
        Face[] fs = res.get(CaptureResult.STATISTICS_FACES);
        long now = System.nanoTime();
        if (fs == null || fs.length == 0) {
            if (now - facesNs > 2_000_000_000L) faces = new int[0]; // keep the last boxes for 2 s: detection drops out briefly when a head turns
            return;
        }
        // The image is the middle part of the sensor's crop region with the image's shape, scaled down.
        Rect crop = res.get(CaptureResult.SCALER_CROP_REGION);
        if (crop == null) crop = new Rect(0, 0, activeW, activeH);
        double cw = crop.width(), ch = crop.height(), shape = (double) imgW / imgH, vw = cw, vh = ch;
        if (cw / ch > shape) vw = ch * shape; else vh = cw / shape;
        double ox = crop.left + (cw - vw) / 2, oy = crop.top + (ch - vh) / 2;
        int n = Math.min(4, fs.length);
        int[] out = new int[4 * n];
        for (int i = 0; i < n; i++) {
            Rect b = fs[i].getBounds();
            double x0 = (b.left - ox) * imgW / vw, x1 = (b.right - ox) * imgW / vw, y0 = (b.top - oy) * imgH / vh, y1 = (b.bottom - oy) * imgH / vh;
            // Widen to head and shoulders. The same on all sides, because the sensor may be turned any way against the picture.
            double gx = 0.75 * (x1 - x0), gy = 0.75 * (y1 - y0);
            // Snap outwards to a 32-pixel grid, so a box that trembles by a few pixels stays the same box.
            out[4 * i] = Math.max(0, (int) Math.floor((x0 - gx) / 32) * 32);
            out[4 * i + 1] = Math.max(0, (int) Math.floor((y0 - gy) / 32) * 32);
            out[4 * i + 2] = Math.min(imgW, (int) Math.ceil((x1 + gx) / 32) * 32);
            out[4 * i + 3] = Math.min(imgH, (int) Math.ceil((y1 + gy) / 32) * 32);
        }
        faces = out; facesNs = now;
    }

    /** Frees the camera. Safe to call twice. */
    void close() {
        closed = true;
        handler.post(() -> {
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (device != null) device.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            thread.quitSafely();
        });
    }
}
