package pl.flatraster;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
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
        void frame(Image img, int rotation, boolean mirror, long timeNs);
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
                            d.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession s) {
                                    if (closed) return;
                                    session = s;
                                    try { s.setRepeatingRequest(rb.build(), null, handler); }
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
            listener.frame(img, rot, false, now); // front camera is not mirrored: the recording shows the scene as it is
        } catch (IllegalStateException ignored) {
        } finally { if (img != null) img.close(); }
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
