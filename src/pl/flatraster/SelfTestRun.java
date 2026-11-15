package pl.flatraster;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import pl.dyktafon.MicRoute;
import pl.dyktafon.SelfTest;

/**
 * The self-test on the phone (stage 0 of ROADMAP.md in apk-codec2-recorder): speed of the voice chain and DRED, of the posterize filter,
 * what the phone's video encoders really produce, and a 30 s test clip from the microphone. One text report, shared as text and saved
 * to Download/FlatRaster. The tests themselves are in SelfTest, PictureTest and VideoTest. One job at a time.
 */
final class SelfTestRun {
    private static final StringBuilder text = new StringBuilder();
    private static volatile boolean running, cancel, done;
    private static volatile String step = "";

    static synchronized String report() { return text.toString(); }
    private static synchronized void add(String s) { text.append(s).append('\n'); }
    static boolean running() { return running; }
    static void cancel() { cancel = true; }

    static org.json.JSONObject state() throws org.json.JSONException {
        return new org.json.JSONObject().put("on", running).put("done", done).put("step", step).put("text", report());
    }

    private static final SelfTest.Log LOG = new SelfTest.Log() {
        @Override public void line(String s) { add(s); }
        @Override public boolean cancelled() { return cancel; }
    };

    private static void begin(String title) { cancel = false; done = false; running = true; step = title; }
    /** Native code needs more stack than Android gives a thread by default. */
    private static Thread thread(Runnable r, String name) { return new Thread(null, r, name, 4 << 20); }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()); }

    static void runAll(Context c, Runnable onDone) {
        synchronized (SelfTestRun.class) { text.setLength(0); }
        begin("Starting");
        thread(() -> {
            try {
                add("Self-test " + now());
                device(c);
                part("Speed: voice chain, DRED", () -> SelfTest.dredSpeed(LOG, 20));
                part("Picture: posterize filter and video encoders", () -> {
                    List<byte[]> frames = PictureTest.filterSpeed(LOG, 60);
                    step = "Video encoders";
                    VideoTest.run(LOG, frames);
                });
                add(""); add(cancel ? "Stopped before the end." : "End of report.");
                saveReport(c);
            } finally { running = false; done = true; step = ""; onDone.run(); }
        }, "selftest").start();
    }

    private interface Part { void run() throws Exception; }
    /** A part that fails reports why and the test goes on: one broken part must not hide the other results. */
    private static void part(String title, Part p) {
        if (cancel) return;
        step = title; add(""); add("== " + title + " ==");
        try { p.run(); } catch (Throwable e) { add("FAILED: " + e); }
    }

    /** 30 seconds of the microphone with no processing by the app, saved as a WAV file for tests on a computer. */
    static void clip(Context c, int source, Runnable onDone) {
        synchronized (SelfTestRun.class) { text.setLength(0); }
        begin("Test clip");
        thread(() -> {
            AudioRecord rec = null;
            try {
                add("Test clip " + now());
                final int fs = 16000, total = 30 * fs, n = 1600; short[] all = new short[total], b = new short[n];
                int min = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(source, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 2 * fs));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("the microphone did not start");
                rec.startRecording();
                int got = 0;
                while (got < total && !cancel) {
                    int k = rec.read(b, 0, Math.min(n, total - got));
                    if (k < 0) throw new IllegalStateException("read error " + k);
                    System.arraycopy(b, 0, all, got, k); got += k; step = "Test clip: " + got / fs + " of 30 s";
                }
                String name = "testclip_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".wav";
                final short[] heard = java.util.Arrays.copyOf(all, got);
                saveWav(c, name, heard, fs);
                add("Source " + sourceName(source) + ", no processing by the app.");
                add(SelfTest.clipSummary(heard, fs));
                add("Saved to Download/FlatRaster/" + name);
                saveReport(c);
            } catch (Throwable e) { add("FAILED: " + e); }
            finally { if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); } running = false; done = true; step = ""; onDone.run(); }
        }, "testclip").start();
    }

    private static void device(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            add("App " + c.getPackageName() + " " + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        } catch (Exception e) { add("App version unknown: " + e); }
        add("Phone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        add("Processor types: " + String.join(", ", Build.SUPPORTED_ABIS) + "; " + Runtime.getRuntime().availableProcessors() + " cores; "
                + (Runtime.getRuntime().maxMemory() >> 20) + " MB for the app");
        AudioManager am = c.getSystemService(AudioManager.class);
        StringBuilder m = new StringBuilder();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) { String nm = MicRoute.micName(d); if (nm != null) m.append(m.length() > 0 ? ", " : "").append(nm); }
        add("Microphones: " + m);
    }
    private static String sourceName(int s) {
        return s == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED (9)" : s == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "VOICE_RECOGNITION (6)" : s == MediaRecorder.AudioSource.MIC ? "MIC (1)" : String.valueOf(s);
    }

    // ---- saving to Download/FlatRaster ----

    private interface Body { void write(java.io.OutputStream o) throws Exception; }
    private static void toDownloads(Context c, String display, String mime, Body body) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, display); v.put(MediaStore.Downloads.MIME_TYPE, mime); v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/FlatRaster");
        ContentResolver r = c.getContentResolver();
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IllegalStateException("Android refused to create the file");
        boolean ok = false;
        try (java.io.OutputStream o = new java.io.BufferedOutputStream(r.openOutputStream(u), 65536)) { body.write(o); ok = true; }
        finally { if (!ok) try { r.delete(u, null, null); } catch (Exception ignored) {} }
    }
    private static void saveWav(Context c, String name, short[] pcm, int fs) throws Exception {
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN); int bytes = pcm.length * 2;
        h.put("RIFF".getBytes()).putInt(36 + bytes).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(fs).putInt(2 * fs).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(bytes);
        toDownloads(c, name, "audio/wav", o -> { o.write(h.array()); o.write(SelfTest.shortsToBytes(pcm, pcm.length)); });
    }
    private static void saveReport(Context c) {
        try {
            String name = "selftest_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".txt";
            byte[] b = report().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            toDownloads(c, name, "text/plain", o -> o.write(b));
            add("Report saved: Download/FlatRaster/" + name);
        } catch (Exception e) { add("The report could not be saved: " + e.getMessage()); }
    }
}
