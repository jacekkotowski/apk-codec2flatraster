package pl.flatraster;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import pl.dyktafon.VoiceBridge;

/** One screen: the posterized camera picture on top (or at the side), the controls in a WebView. */
public class MainActivity extends Activity implements CameraSource.Listener {
    private static final int BG = 0xff1b2430, REQ_PERM = 1;
    /** Landscape picture sizes; portrait swaps them. All even, as the filter and encoders need. */
    static final int[][] SIZES = {{160, 120}, {240, 176}, {320, 240}, {480, 360}};
    private static final int HOLD = 12, CLEAN = 1;

    private WebView web;
    private ImageView preview;
    private LinearLayout split;
    private Bitmap bmp;
    private CameraSource camera;
    private DisplayManager.DisplayListener displayListener;

    // Settings, set from the controls.
    private volatile int sizeIdx = 2, colors = 8, smooth = 2, kbps = 5, wantCodec = -1, c2mode = 5, palSec = 0;
    private volatile double fps = 2;
    private volatile boolean front, portrait = true;

    // Filter and recording state, guarded by lock.
    private final Object lock = new Object();
    private long flat;
    private int fw, fh, fk;
    private byte[] i420;
    private int[] argb;
    private VideoEncoder enc;
    private FrvWriter writer;
    private long recStartNs;
    private long lastPalNs; // when the palette was last built; guarded by lock
    private volatile boolean recording;
    private volatile long frames;
    private AudioCapture audio;
    private boolean pendingStart, askedPermission;

    private volatile Player player;
    private volatile String playName, notice, encoderInfo = "", avail = "";
    private volatile boolean exporting, cameraOn;
    private int availW, availH;
    private final HashMap<String, long[]> infoCache = new HashMap<>();

    static File dir(Context c) { File d = new File(c.getExternalFilesDir(null), "recordings"); d.mkdirs(); return d; }
    private File named(String name) { return new File(dir(this), new File(name).getName()); }
    private boolean has(String perm) { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        preview = new ImageView(this);
        preview.setBackgroundColor(0xff000000);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setBlockNetworkLoads(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        split = new LinearLayout(this);
        split.addView(preview, new LinearLayout.LayoutParams(0, 0, 1f));
        split.addView(web, new LinearLayout.LayoutParams(0, 0, 1.25f));
        // A WebView ignores its own padding, so the system-bar insets go on a parent layout.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(split, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((View v, WindowInsets i) -> {
                android.graphics.Insets p = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(p.left, p.top, p.right, p.bottom);
                return WindowInsets.CONSUMED;
            });
        } else root.setFitsSystemWindows(true);
        setContentView(root);
        applyRotation();
        // Turning the phone upside down or from one side to the other changes no layout, so listen to the display too.
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @Override public void onDisplayChanged(int id) { applyRotation(); }
        };
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, null);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onDestroy() {
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        synchronized (lock) { if (flat != 0) { Flat.destroy(flat); flat = 0; } }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); applyRotation(); }

    /** Lays the picture above the controls in portrait and beside them in landscape, and tells the camera how the phone is held. */
    @SuppressWarnings("deprecation")
    private void applyRotation() {
        boolean p = getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        portrait = p;
        split.setOrientation(p ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < split.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) split.getChildAt(i).getLayoutParams();
            lp.width = p ? LinearLayout.LayoutParams.MATCH_PARENT : 0; lp.height = p ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
            split.getChildAt(i).setLayoutParams(lp);
        }
        if (camera != null) camera.setDeviceDegrees(getWindowManager().getDefaultDisplay().getRotation() * 90);
    }

    @Override protected void onResume() {
        super.onResume();
        if (has(Manifest.permission.CAMERA)) startCamera();
        else if (!askedPermission) { askedPermission = true; requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERM); }
    }

    @Override protected void onPause() {
        stopRecording();
        stopPlayback();
        if (camera != null) { camera.close(); camera = null; cameraOn = false; }
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERM) return;
        boolean start = pendingStart; pendingStart = false;
        if (!has(Manifest.permission.CAMERA)) { notice = "FlatRaster needs the camera. Allow it in Android Settings > Apps > FlatRaster > Permissions."; return; }
        if (camera == null) startCamera();
        if (start) {
            if (has(Manifest.permission.RECORD_AUDIO)) startRecording();
            else notice = "Recording needs the microphone. Allow it in Android Settings > Apps > FlatRaster > Permissions.";
        }
    }

    @SuppressWarnings("deprecation")
    private void startCamera() {
        if (camera != null) camera.close();
        camera = new CameraSource(this);
        camera.setFps(fps);
        camera.setDeviceDegrees(getWindowManager().getDefaultDisplay().getRotation() * 90);
        camera.open(this, front);
    }

    @Override public void cameraError(String message) { cameraOn = false; notice = message; }

    /** Camera thread: posterize the frame, show it, and encode it while recording. */
    @Override public void frame(Image img, int rotation, boolean mirror, long timeNs) {
        if (player != null && !recording) return; // the picture area is showing a recording
        final int[] copy; final int w, h; String fail = null;
        synchronized (lock) {
            int[] s = SIZES[sizeIdx];
            int dw = portrait ? s[1] : s[0], dh = portrait ? s[0] : s[1];
            if (!recording && (flat == 0 || fw != dw || fh != dh || fk != colors)) {
                if (flat != 0) Flat.destroy(flat);
                fw = dw; fh = dh; fk = colors;
                flat = Flat.create(fw, fh, fk);
                i420 = new byte[fw * fh * 3 / 2]; argb = new int[fw * fh];
            }
            if (flat == 0) return;
            // New colours every palSec seconds. The first frame of the new palette is a full key picture, since every colour changes.
            if (palSec > 0 && lastPalNs != 0 && timeNs - lastPalNs >= palSec * 1_000_000_000L) Flat.reset(flat);
            Image.Plane[] p = img.getPlanes();
            int r = Flat.process(flat, p[0].getBuffer(), p[0].getRowStride(), p[1].getBuffer(), p[2].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(),
                    img.getWidth(), img.getHeight(), rotation, mirror, smooth, HOLD, CLEAN, i420, argb);
            if (r < 0) return;
            cameraOn = true;
            if (r == 1) { // the palette was just rebuilt, by the timer or because the scene stopped fitting it
                lastPalNs = timeNs;
                if (recording && enc != null) enc.requestKeyFrame();
            }
            if (recording && enc != null) {
                try { enc.encode(i420, Math.max(0, (timeNs - recStartNs) / 1_000_000)); frames++; }
                catch (Exception e) { fail = "Recording stopped: " + e.getMessage(); }
            }
            copy = argb.clone(); w = fw; h = fh;
        }
        if (fail != null) { final String f = fail; runOnUiThread(() -> { stopRecording(); notice = f; }); }
        show(copy, w, h);
    }

    /** Draws a picture with hard pixel edges: no smoothing when it is scaled up. */
    private void show(final int[] px, final int w, final int h) {
        runOnUiThread(() -> {
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
                d.setFilterBitmap(false);
                preview.setImageDrawable(d);
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            preview.invalidate();
        });
    }

    private void startRecording() {
        if (recording) return;
        if (!has(Manifest.permission.CAMERA) || !has(Manifest.permission.RECORD_AUDIO)) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERM);
            return;
        }
        stopPlayback();
        final FrvWriter w; final long start;
        synchronized (lock) {
            if (flat == 0 || !cameraOn) { notice = "The camera has not started yet"; return; }
            File f = new File(dir(this), new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".frv");
            VideoEncoder e = null;
            try {
                // The sink reads `writer` when a packet comes out, which is always after it is set below.
                e = new VideoEncoder(wantCodec, fw, fh, fps, kbps * 1000, (type, t, d) -> { FrvWriter fw2 = writer; if (fw2 != null) fw2.write(type, t, d, 0, d.length); });
                writer = new FrvWriter(f, e.codec, fw, fh, c2mode);
            } catch (Exception ex) {
                if (e != null) e.close();
                writer = null; f.delete();
                notice = ex.getMessage() == null ? "Recording did not start" : ex.getMessage();
                return;
            }
            enc = e; frames = 0;
            encoderInfo = FrvWriter.CODEC_NAME[e.codec] + " " + fw + "x" + fh + ", asked " + (e.bitrate / 100 / 10.0) + " kbps (" + e.name + ")";
            recStartNs = start = System.nanoTime();
            lastPalNs = start;
            recording = true;
            w = writer;
        }
        audio = new AudioCapture(c2mode, start, (t, d, len) -> w.write(FrvWriter.AUDIO, t, d, 0, len));
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void stopRecording() {
        if (!recording) return;
        AudioCapture a = audio; audio = null;
        if (a != null) { a.stop(); if (a.error != null) notice = a.error; }
        synchronized (lock) {
            recording = false;
            if (enc != null) { enc.close(); enc = null; } // flushes the last pictures into the file
            if (writer != null) { try { writer.close(); } catch (Exception e) { notice = "The recording may be incomplete: " + e.getMessage(); } writer = null; }
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void play(String name) {
        if (recording) return;
        stopPlayback();
        final File f = named(name);
        if (!f.exists()) return;
        playName = f.getName();
        final Player[] self = new Player[1];
        self[0] = player = new Player(f, new Player.View() {
            @Override public void picture(int[] px, int w, int h) { if (player == self[0]) show(px, w, h); }
            @Override public void finished(String error) {
                if (error != null) notice = error;
                if (player == self[0]) { player = null; playName = null; }
            }
        });
    }

    private void stopPlayback() {
        Player p = player; player = null; playName = null;
        if (p != null) p.stop();
    }

    /** Saves an ordinary .mp4 copy to Movies/FlatRaster, where the gallery and other apps can see it. */
    private void exportMp4(String name) {
        final File src = named(name);
        if (exporting || !src.exists()) return;
        exporting = true;
        new Thread(() -> {
            String out = src.getName().replaceAll("\\.frv$", "") + ".mp4";
            File tmp = new File(getCacheDir(), out);
            Uri uri = null;
            try {
                Mp4Export.export(src, tmp);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, out);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FlatRaster");
                uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new java.io.IOException("Android refused to create the file");
                try (InputStream in = new FileInputStream(tmp); OutputStream o = getContentResolver().openOutputStream(uri)) {
                    if (o == null) throw new java.io.IOException("Android refused to open the file");
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                }
                notice = "Saved to Movies/FlatRaster/" + out;
            } catch (Exception e) {
                if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                notice = "MP4 not saved: " + e.getMessage();
            } finally { tmp.delete(); exporting = false; }
        }, "export").start();
    }

    /** Duration in ms and video format of a recording; cached, because it means reading the whole file. */
    private long[] info(File f) {
        String key = f.getName() + ":" + f.length() + ":" + f.lastModified();
        synchronized (infoCache) {
            long[] v = infoCache.get(key);
            if (v == null) {
                try { FrvReader r = new FrvReader(f); v = new long[]{r.durationMs(), r.codec}; } catch (Exception e) { v = new long[]{-1, -1}; }
                if (infoCache.size() > 500) infoCache.clear();
                infoCache.put(key, v);
            }
            return v;
        }
    }

    private final class Bridge {
        @JavascriptInterface public String status() {
            try {
                JSONObject o = new JSONObject();
                boolean rec = recording;
                o.put("rec", rec).put("camera", cameraOn).put("exporting", exporting).put("encoder", encoderInfo);
                int[] s = SIZES[sizeIdx];
                int w = portrait ? s[1] : s[0], h = portrait ? s[0] : s[1];
                if (w != availW || h != availH) { avail = VideoEncoder.available(w, h); availW = w; availH = h; }
                o.put("avail", avail).put("w", w).put("h", h);
                FrvWriter wr = writer; AudioCapture a = audio;
                if (rec && wr != null) {
                    double sec = Math.max(0.001, (System.nanoTime() - recStartNs) / 1e9);
                    o.put("ms", (long) (sec * 1000)).put("bytes", wr.bytes).put("frames", frames)
                            .put("vbps", wr.videoBytes * 8 / sec).put("abps", wr.audioBytes * 8 / sec)
                            .put("level", a == null ? 0 : a.level).put("overload", a != null && a.overload);
                    if (a != null) a.overload = false;
                }
                Player p = player;
                if (p != null) o.put("play", playName).put("pos", p.positionMs).put("dur", p.durationMs);
                String n = notice; notice = null;
                if (n != null) o.put("notice", n);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String list() {
            JSONArray arr = new JSONArray();
            File[] fs = dir(MainActivity.this).listFiles((d, n) -> n.endsWith(".frv"));
            if (fs == null) return "[]";
            Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            FrvWriter wr = writer;
            try {
                for (File f : fs) {
                    JSONObject o = new JSONObject().put("name", f.getName()).put("size", f.length()).put("time", f.lastModified());
                    if (!(recording && wr != null && f.equals(fs[0]))) { // the file being written is not complete yet
                        long[] i = info(f);
                        o.put("ms", i[0]).put("codec", i[1] >= 0 ? FrvWriter.CODEC_NAME[(int) i[1]] : "");
                    }
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        @JavascriptInterface public String voiceModes() { return new JSONArray(Arrays.asList(VoiceBridge.modeNames())).toString(); }

        /** Settings from the controls. Size, colours, format and voice mode wait until a recording has ended. */
        @JavascriptInterface public void config(String json) {
            try {
                JSONObject o = new JSONObject(json);
                smooth = Math.max(0, Math.min(6, o.optInt("smooth", smooth)));
                palSec = Math.max(0, Math.min(600, o.optInt("pal", palSec)));
                if (recording) return;
                sizeIdx = Math.max(0, Math.min(SIZES.length - 1, o.optInt("size", sizeIdx)));
                colors = Math.max(2, Math.min(32, o.optInt("colors", colors)));
                kbps = Math.max(1, Math.min(1000, o.optInt("kbps", kbps)));
                wantCodec = Math.max(-1, Math.min(FrvWriter.MIME.length - 1, o.optInt("codec", wantCodec)));
                int m = VoiceBridge.modeOf(o.optString("voice", ""));
                if (m >= 0) c2mode = m;
                fps = Math.max(0.2, Math.min(15, o.optDouble("fps", fps)));
                final boolean f = o.optBoolean("front", front);
                runOnUiThread(() -> {
                    if (camera != null) camera.setFps(fps);
                    if (f != front) { front = f; if (camera != null) startCamera(); }
                });
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void newPalette() { synchronized (lock) { if (flat != 0) Flat.reset(flat); } } // the next frame rebuilds it and asks for a key picture
        @JavascriptInterface public void startRec() { runOnUiThread(MainActivity.this::startRecording); }
        @JavascriptInterface public void stopRec() { runOnUiThread(MainActivity.this::stopRecording); }
        @JavascriptInterface public void play(String name) { runOnUiThread(() -> MainActivity.this.play(name)); }
        @JavascriptInterface public void stopPlay() { runOnUiThread(MainActivity.this::stopPlayback); }
        @JavascriptInterface public void mp4(String name) { exportMp4(name); }
        @JavascriptInterface public void remove(String name) { if (new File(name).getName().equals(playName)) runOnUiThread(MainActivity.this::stopPlayback); named(name).delete(); }
        @JavascriptInterface public boolean rename(String name, String to) {
            String clean = to.trim().replaceAll("[^\\p{L}\\p{N} _.-]", "_");
            if (clean.isEmpty()) return false;
            if (!clean.endsWith(".frv")) clean += ".frv";
            File dst = named(clean);
            return !dst.exists() && named(name).renameTo(dst);
        }
        @JavascriptInterface public void share(String name) {
            runOnUiThread(() -> {
                Uri u = new Uri.Builder().scheme("content").authority("pl.flatraster.files").appendPath(new File(name).getName()).build();
                Intent i = new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share recording"));
            });
        }
    }
}
